package dev.yuyang.app.task.task02_cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ============================================================================
 * Task 2：多层缓存系统（内存 LRU + 磁盘 + 网络）
 * ============================================================================
 *
 * 【面试场景】
 * 实现一个多级缓存，取数据时：
 *   1. 先查内存（最快）
 *   2. 内存没有就查磁盘
 *   3. 磁盘也没有才走网络（mock）
 *   4. 同一个 key 的并发请求只能发一次网络（去重，避免重复请求）
 *   5. 线程安全
 *
 * 【面试官重点看】
 *   - LRU 淘汰策略写得对不对（这其实就是 LeetCode 146）
 *   - 并发去重（single-flight）—— 这是区分初级和高级的关键点
 *   - 磁盘 IO 有没有放到后台线程（不能阻塞调用方）
 * ============================================================================
 */

/**
 * 线程安全的内存 LRU 缓存。
 *
 * 实现技巧：用 LinkedHashMap 的“访问顺序模式”（构造第三个参数传 true）：
 *   - 每次 get/put 都会把该元素移到链表尾部（表示“最近用过”）
 *   - 重写 removeEldestEntry：当 size 超过上限时，自动淘汰链表头部（最久没用的）
 * 这样几行就实现了 LRU，比手写双向链表 + HashMap 简洁。
 *
 * 故意写成不依赖任何 Android 类，方便直接写单元测试。
 */
class LruMemoryCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        // 返回 true 表示“需要淘汰最老的元素”。size 超过 maxSize 时触发。
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    // 所有方法加 @Synchronized：LinkedHashMap 本身非线程安全，必须自己加锁。
    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}

/**
 * 极简磁盘缓存：把每个 key 哈希成一个文件名，内容直接写文件。
 * 真实场景会用 DiskLruCache（带容量上限、LRU 淘汰），这里 demo 够用。
 */
class DiskCache(private val dir: File) {
    init {
        if (!dir.exists()) dir.mkdirs()  // 确保目录存在
    }

    // 用 key 的哈希值（转 16 进制）当文件名，避免非法字符。
    private fun fileFor(key: String) = File(dir, Integer.toHexString(key.hashCode()))

    fun get(key: String): String? = fileFor(key).takeIf { it.exists() }?.readText()

    fun put(key: String, value: String) {
        fileFor(key).writeText(value)
    }
}

/**
 * 多层缓存主体：内存 → 磁盘 → 网络，逐级回退，并对网络层做并发去重。
 *
 * @param dir     磁盘缓存目录
 * @param fetcher 网络层：给一个 key，返回它对应的值（挂起函数，模拟网络请求）
 * @param memory  内存 LRU（默认容量 100）
 * @param disk    磁盘缓存
 */
class MultiTierCache(
    dir: File,
    private val fetcher: suspend (String) -> String,
    private val memory: LruMemoryCache<String, String> = LruMemoryCache(100),
    private val disk: DiskCache = DiskCache(dir),
) {
    /**
     * 正在进行的网络请求表：key -> 该 key 对应的 Deferred（异步结果）。
     * 这是实现“single-flight 并发去重”的核心数据结构。
     */
    private val inFlight = HashMap<String, Deferred<String>>()

    /** 保护 inFlight 表的协程锁（Mutex 是挂起锁，不阻塞线程，适合协程）。 */
    private val mutex = Mutex()

    /**
     * 取数据。逐级查找：
     */
    suspend fun get(key: String): String = coroutineScope {
        // 第 1 级：内存。命中直接返回，最快。
        memory.get(key)?.let { return@coroutineScope it }

        // 第 2 级：磁盘。注意 withContext(Dispatchers.IO)：磁盘读是阻塞 IO，
        // 必须切到 IO 线程池，不能占用调用方线程（可能是主线程）。
        withContext(Dispatchers.IO) { disk.get(key) }?.let {
            memory.put(key, it)              // 回填内存，下次更快
            return@coroutineScope it
        }

        // 第 3 级：网络，并且做【并发去重 single-flight】：
        // 加锁后看 inFlight 里有没有同一个 key 正在请求：
        //   - 有：直接复用那个 Deferred（不发起第二次网络请求）
        //   - 没有：新建一个异步任务，存进 inFlight
        val deferred = mutex.withLock {
            inFlight[key] ?: scopeAsync(this, key).also { inFlight[key] = it }
        }
        // 在锁外 await 结果：多个并发调用方会等在同一个 Deferred 上，
        // 网络只发了一次，结果共享给所有人。
        deferred.await()
    }

    /**
     * 创建一个“去网络拉数据”的异步任务。
     * 拿到结果后写磁盘 + 写内存；无论成功失败，finally 里都把自己从 inFlight 移除，
     * 否则失败后这个 key 永远卡在“进行中”，再也不会重试。
     */
    private fun scopeAsync(scope: CoroutineScope, key: String): Deferred<String> =
        scope.async(Dispatchers.IO) {
            try {
                val value = fetcher(key)
                disk.put(key, value)
                memory.put(key, value)
                value
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
}
