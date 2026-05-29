package dev.yuyang.app.task.task05_downloadmanager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * ============================================================================
 * Task 5：下载管理器
 * ============================================================================
 *
 * 【面试场景】实现一个下载管理器：
 *   1. 最多同时下载 N 个（限制并发）
 *   2. 上报进度
 *   3. 支持暂停 / 继续 / 取消
 *   4. App 重启后能续传（不从头下）
 *
 * 【面试官重点看】
 *   - 用什么限制并发（这里用协程的 Semaphore 信号量）
 *   - 进度怎么对外暴露（用 StateFlow，UI 直接观察）
 *   - 续传怎么做（HTTP Range 请求头 + 在已下载的文件后面接着写）
 *   - 协程取消是否“协作式”（在循环里检查 ensureActive，能被及时取消）
 * ============================================================================
 */

/** 下载状态。 */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/**
 * 单个下载任务的快照（不可变）。
 * progress 是计算属性：已下字节 / 总字节。
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val destPath: String,                          // 文件保存路径
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,                      // -1 表示还不知道总大小
) {
    val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
}

class DownloadManager(
    private val maxConcurrent: Int = 3,
    // SupervisorJob：一个下载失败不会连累其他下载（默认 Job 会父子联动取消）。
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** 信号量：最多发 maxConcurrent 个“许可证”，拿不到的任务排队等。 */
    private val semaphore = Semaphore(maxConcurrent)

    /** 每个任务对应的协程 Job，用于取消。ConcurrentHashMap 保证多线程安全。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 暂停标记：下载循环每轮检查它，为 true 就停下。 */
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    // 所有任务的快照表。对外暴露只读 StateFlow，UI 观察它刷新进度条。
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    /** 加入一个下载任务并立即开始（重复 id 直接忽略）。 */
    fun enqueue(id: String, url: String, destPath: String) {
        if (_tasks.value.containsKey(id)) return
        _tasks.update { it + (id to DownloadTask(id, url, destPath)) }
        start(id)
    }

    /** 暂停：只置标记，真正停下发生在下载循环里检查到标记时。 */
    fun pause(id: String) {
        pauseFlags[id] = true
    }

    /** 继续：只有处于 PAUSED 才重新开始（会基于已下文件续传）。 */
    fun resume(id: String) {
        if (_tasks.value[id]?.status == DownloadStatus.PAUSED) start(id)
    }

    /** 取消：取消协程；下载循环里的 ensureActive 会抛出取消异常并清理。 */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    /** 启动/重启一个任务的下载协程。 */
    private fun start(id: String) {
        pauseFlags[id] = false
        jobs[id] = scope.launch {
            // withPermit：拿到许可证才执行，离开时自动归还。这就是“最多 N 个并发”。
            semaphore.withPermit { runDownload(id) }
        }
    }

    /** 真正的下载逻辑（在 IO 线程、持有信号量许可的情况下执行）。 */
    private suspend fun runDownload(id: String) {
        val task = _tasks.value[id] ?: return
        update(id) { it.copy(status = DownloadStatus.RUNNING) }
        try {
            val file = File(task.destPath)
            // 【续传关键】看本地已经下了多少字节。
            val existing = if (file.exists()) file.length() else 0L
            val conn = (URL(task.url).openConnection() as HttpURLConnection).apply {
                // 告诉服务器“从 existing 字节开始给我”，实现断点续传。
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            conn.connect()
            // 服务器返回的是“剩余字节数”，加上已下的才是总大小。
            val remaining = conn.contentLengthLong.coerceAtLeast(0)
            update(id) { it.copy(totalBytes = existing + remaining, downloadedBytes = existing) }

            conn.inputStream.use { input ->
                // RandomAccessFile + seek：定位到已下位置，往后接着写（而不是覆盖）。
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(existing)
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = existing
                    while (true) {
                        // 每轮先看暂停标记：要暂停就改状态并退出（许可证随之归还，让排队任务上）。
                        if (pauseFlags[id] == true) {
                            update(id) { it.copy(status = DownloadStatus.PAUSED) }
                            return
                        }
                        // 协作式取消：被 cancel 时这里抛 CancellationException。
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break                  // 读完了
                        raf.write(buffer, 0, read)
                        downloaded += read
                        update(id) { it.copy(downloadedBytes = downloaded) }  // 上报进度
                    }
                }
            }
            update(id) { it.copy(status = DownloadStatus.COMPLETED) }
        } catch (e: CancellationException) {
            // 取消是正常流程：标记 CANCELLED 后必须把异常重新抛出（结构化并发要求）。
            update(id) { it.copy(status = DownloadStatus.CANCELLED) }
            throw e
        } catch (e: Exception) {
            update(id) { it.copy(status = DownloadStatus.FAILED) }
        }
    }

    /**
     * 原子地更新某个任务的快照。
     * 注意：任务“队列本身”的持久化（重启后恢复列表）生产里会用 Room/DataStore；
     * 这里靠磁盘上的半成品文件 + Range 实现“内容”续传已经够演示。
     */
    private inline fun update(id: String, crossinline f: (DownloadTask) -> DownloadTask) {
        _tasks.update { map -> map[id]?.let { map + (id to f(it)) } ?: map }
    }
}
