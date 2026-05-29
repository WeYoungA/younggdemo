package dev.yuyang.app.task.taskF_imageloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap

/**
 * ============================================================================
 * Task F：迷你图片加载库（仿 Glide）—— 进阶综合题
 * ============================================================================
 *
 * 【题目】实现 `ImageLoader.get(context).load(url, imageView)`，要求：
 *   1. 内存缓存（LRU）+ 磁盘缓存 + 网络，三级回退
 *   2. 同一个 url 的并发请求只下载一次（去重）
 *   3. 大图按目标尺寸降采样，避免 OOM
 *   4. RecyclerView 复用安全：ImageView 被复用去加载新图时，旧请求的结果不能错贴上去
 *   5. 解码在后台线程，设图在主线程
 *
 * 这道题综合了 Task 1（SDK）、Task 2（多级缓存）的所有点，外加 Bitmap 内存管理和
 * View 复用这两个 Android 特有难点，是最能体现“写过被千万人用的 SDK”的题。
 * ============================================================================
 */
class ImageLoader private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 内存缓存：用 Android 的 LruCache，容量取“App 可用内存的 1/8”（经验值）。
     * sizeOf 返回每张图占多少 KB —— LruCache 按这个累计大小、超了就淘汰最久没用的。
     */
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    /** 磁盘缓存目录（放在 cacheDir 下，系统空间紧张时可被清理）。 */
    private val diskDir = File(appContext.cacheDir, "img_cache").apply { mkdirs() }

    /** 正在下载中的请求：url -> Deferred，用于并发去重。 */
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val mutex = Mutex()

    /**
     * 记录“每个 ImageView 当前想要哪个 url”。
     * WeakHashMap：以 ImageView 为弱引用 key，View 被回收时条目自动消失，不泄漏。
     * 用它解决【复用错位】：异步结果回来时，先看这个 View 是不是还想要这个 url，
     * 不是就丢弃（说明它已经被复用去加载别的图了）。
     */
    private val targets: MutableMap<ImageView, String> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 加载入口。
     * @param reqWidth/reqHeight 目标尺寸（像素），用于降采样；传 0 表示不降采样。
     */
    fun load(url: String, into: ImageView, reqWidth: Int = 0, reqHeight: Int = 0) {
        // 记录这个 View 最新想要的 url（覆盖掉它上一次的请求意图）。
        targets[into] = url

        // 内存命中：直接同步贴图，最快路径。
        memoryCache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }

        scope.launch {
            val bitmap = getBitmap(url, reqWidth, reqHeight)
            main.post {
                // 关键判断：只有这个 View 现在“仍然”想要这个 url 才贴，
                // 否则说明它已被 RecyclerView 复用去加载别的图了，丢弃本次结果。
                if (bitmap != null && targets[into] == url) {
                    into.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** 三级获取：内存 → 磁盘 → 网络（网络做去重）。 */
    private suspend fun getBitmap(url: String, reqW: Int, reqH: Int): Bitmap? {
        memoryCache.get(url)?.let { return it }

        // 磁盘：解码已缓存文件（同样降采样），命中后回填内存。
        diskFile(url).takeIf { it.exists() }?.let { f ->
            decodeSampled(BitmapFactory.Options(), reqW, reqH) { opts ->
                BitmapFactory.decodeFile(f.absolutePath, opts)
            }?.let {
                memoryCache.put(url, it)
                return it
            }
        }

        // 网络：单飞去重——同一个 url 并发只下一次，大家共享结果。
        val deferred = mutex.withLock {
            inFlight[url] ?: scope.async {
                try {
                    downloadAndDecode(url, reqW, reqH)
                } finally {
                    mutex.withLock { inFlight.remove(url) }
                }
            }.also { inFlight[url] = it }
        }
        return deferred.await()
    }

    /** 下载字节 → 写磁盘 → 降采样解码 → 回填内存。 */
    private fun downloadAndDecode(url: String, reqW: Int, reqH: Int): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        conn.inputStream.use { input ->
            val bytes = input.readBytes()
            runCatching { diskFile(url).writeBytes(bytes) } // 持久化（失败不致命）
            return decodeSampled(BitmapFactory.Options(), reqW, reqH) { opts ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }?.also { memoryCache.put(url, it) }
        }
    }

    /**
     * 降采样解码的通用流程（两步走，避免一次性把大图读进内存导致 OOM）：
     *   第一步 inJustDecodeBounds=true：只读图片原始宽高，不真正分配像素内存
     *   计算 inSampleSize：把图缩小到刚好覆盖目标尺寸的 2 的幂次
     *   第二步 inJustDecodeBounds=false：按 inSampleSize 真正解码
     * @param decode 真正执行解码的函数（文件 or 字节数组都复用这套逻辑）
     */
    private inline fun decodeSampled(
        opts: BitmapFactory.Options,
        reqW: Int,
        reqH: Int,
        decode: (BitmapFactory.Options) -> Bitmap?,
    ): Bitmap? {
        if (reqW <= 0 || reqH <= 0) return decode(BitmapFactory.Options()) // 不需要降采样
        opts.inJustDecodeBounds = true
        decode(opts) // 只量尺寸
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
        opts.inJustDecodeBounds = false
        return decode(opts) // 真正解码
    }

    /** 计算降采样倍数：不断 /2 直到刚好不小于目标尺寸。 */
    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        var halfW = srcW / 2
        var halfH = srcH / 2
        while (halfW / sample >= reqW && halfH / sample >= reqH) {
            sample *= 2
        }
        return sample
    }

    /** url -> 磁盘文件（用哈希做文件名）。 */
    private fun diskFile(url: String) = File(diskDir, Integer.toHexString(url.hashCode()))

    /** 清缓存。 */
    fun clear() {
        memoryCache.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        @Volatile
        private var instance: ImageLoader? = null

        fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
            instance ?: ImageLoader(context.applicationContext).also { instance = it }
        }
    }
}
