package dev.yuyang.app.task.task06_modifyproject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * Task 6：在现有项目上改代码（加功能 / 修 bug / 重构）
 * ============================================================================
 *
 * 【面试场景】面试官给你一个小项目，让你：加个功能、修个 bug、重构某个模块。
 * 这类题考的不是从零写，而是：能不能读懂别人的代码、判断该改什么、
 * 重构时不破坏原有对外契约。
 *
 * 下面用一个“before / after”完整演示重构思路。
 *
 * ----------------------------------------------------------------------------
 * 【BEFORE】一个典型的“祖传代码”，有三个问题：
 *   1. 在调用方线程上做阻塞网络（通常是主线程 → ANR 卡死）
 *   2. 回调式 API，把错误吞成一个“可空结果”（调用方根本不知道为啥失败）
 *   3. 真 BUG：内存缓存永不失效 → 永远返回过期数据；而且不是线程安全的
 * ----------------------------------------------------------------------------
 */
class LegacyUserManager {

    // BUG：无上限、永不失效、非线程安全的缓存。
    private val cache = HashMap<String, String>()

    fun getUserName(userId: String, callback: (String?) -> Unit) {
        // BUG：在调用方线程上跑（往往是主线程）→ 下面的阻塞会导致 ANR。
        val cached = cache[userId]
        if (cached != null) {
            callback(cached)
            return
        }
        try {
            val name = blockingFetch(userId) // 阻塞
            cache[userId] = name
            callback(name)
        } catch (e: Exception) {
            callback(null) // 错误细节全丢了，调用方只拿到一个 null
        }
    }

    private fun blockingFetch(userId: String): String {
        Thread.sleep(500)
        return "User-$userId"
    }
}

/**
 * ----------------------------------------------------------------------------
 * 【AFTER】行为不变，但三个问题全修了：
 *   1. 改成 suspend + Dispatchers.IO，绝不阻塞调用方线程
 *   2. 返回 Result<T>，错误是显式的，而不是悄悄变成 null
 *   3. 缓存加了 TTL（过期时间）、有容量上限、加锁保证线程安全
 *
 * 关键：对外的“意图”没变（还是“拿用户名”），只是把回调升级成协程——
 * 这正是现代 Android 的标准契约。重构时保留语义、升级实现，就是好品味。
 * ----------------------------------------------------------------------------
 *
 * @param ttlMs 缓存有效期（毫秒），默认 60 秒
 * @param now   取当前时间的函数，做成可注入的，方便单元测试里控制时间
 */
class UserRepository(
    private val ttlMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 缓存条目：值 + 写入时间（用来判断是否过期）。 */
    private data class Entry(val name: String, val cachedAt: Long)

    private val lock = Any()
    // 访问顺序的 LinkedHashMap + removeEldestEntry：顺手做成 LRU，容量上限 200。
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > 200
    }

    /** 拿用户名：命中且没过期就用缓存，否则去网络（在 IO 线程），结果回填缓存。 */
    suspend fun getUserName(userId: String): Result<String> = runCatching {
        readFresh(userId)?.let { return@runCatching it }
        val name = withContext(Dispatchers.IO) { fetch(userId) } // 切 IO 线程，不阻塞调用方
        synchronized(lock) { cache[userId] = Entry(name, now()) }
        name
    }

    /** 读“没过期”的缓存；过期了就删掉并返回 null。整段加锁保证线程安全。 */
    private fun readFresh(userId: String): String? = synchronized(lock) {
        val entry = cache[userId] ?: return null
        if (now() - entry.cachedAt > ttlMs) {
            cache.remove(userId)
            null
        } else {
            entry.name
        }
    }

    private fun fetch(userId: String): String {
        Thread.sleep(500)
        return "User-$userId"
    }
}
