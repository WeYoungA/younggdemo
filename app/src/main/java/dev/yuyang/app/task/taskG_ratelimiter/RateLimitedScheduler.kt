package dev.yuyang.app.task.taskG_ratelimiter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 * Task G：令牌桶限流 + 优先级并发调度器（进阶题）
 * ============================================================================
 *
 * 【题目】SDK 要往服务器发大量请求，需要一个调度器：
 *   1. 限速：每秒最多发 N 个（令牌桶 Token Bucket）
 *   2. 限并发：同时最多 M 个在飞
 *   3. 按优先级调度：高优先级请求先执行
 *   4. 调用方拿到一个 Deferred，能 await 结果
 *
 * 考点：时间维度（限速）+ 数量维度（并发）+ 排序（优先级）三种约束同时满足，
 * 而且全用协程做到“等待时不阻塞线程”。这是很能区分高级/资深的题。
 * ============================================================================
 */

/**
 * 令牌桶：以固定速率往桶里加令牌，桶有容量上限（允许一定突发）。
 * acquire() 要消费一个令牌；没有就按“还需多久才攒够”挂起等待。
 *
 * @param capacity        桶容量（最多攒多少令牌 = 允许的突发量）
 * @param refillPerSecond 每秒补充多少令牌（= 稳态限速）
 */
class TokenBucket(
    private val capacity: Double,
    private val refillPerSecond: Double,
    private val now: () -> Long = System::nanoTime,
) {
    private var tokens = capacity     // 当前令牌数（用 Double 便于按比例补充）
    private var last = now()          // 上次补充时间
    private val mutex = Mutex()

    /** 申请 permits 个令牌，不够就挂起等待（不阻塞线程）。 */
    suspend fun acquire(permits: Double = 1.0) {
        while (true) {
            val waitMs = mutex.withLock {
                refill()
                if (tokens >= permits) {
                    tokens -= permits
                    0L                                      // 够了，立刻返回
                } else {
                    // 不够：算出“再等多久能攒够缺的部分”。
                    val needed = permits - tokens
                    ((needed / refillPerSecond) * 1000).toLong().coerceAtLeast(1)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)                                   // 挂起等待，期间令牌会继续累积
        }
    }

    /** 按“距上次补充过了多久”补令牌，封顶 capacity。 */
    private fun refill() {
        val n = now()
        val elapsedSec = (n - last) / 1_000_000_000.0
        tokens = (tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity)
        last = n
    }
}

/**
 * 限速 + 限并发 + 优先级 的任务调度器。
 *
 * @param maxConcurrent 最大并发数
 * @param bucket        令牌桶（限速）
 */
class RateLimitedScheduler(
    maxConcurrent: Int,
    private val bucket: TokenBucket,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /**
     * 待执行任务。
     * @param priority 优先级（越大越先执行）
     * @param seq      入队序号，优先级相同时按入队顺序（FIFO），避免“饿死”
     * @param block    真正要跑的挂起逻辑
     * @param result   回填结果的 CompletableDeferred
     */
    private class PendingTask(
        val priority: Int,
        val seq: Long,
        val block: suspend () -> Any?,
        val result: CompletableDeferred<Any?>,
    )

    // 优先级队列：先按优先级降序，再按序号升序。
    private val comparator =
        compareByDescending<PendingTask> { it.priority }.thenBy { it.seq }
    private val queue = PriorityQueue(comparator)
    private val mutex = Mutex()

    // 每入队一个任务就往这个 channel 发一个信号，pump 收到信号才去取任务（避免空转）。
    private val signal = Channel<Unit>(Channel.UNLIMITED)
    private val semaphore = Semaphore(maxConcurrent)
    private val seqGen = AtomicLong(0)

    init {
        scope.launch { pump() }  // 启动调度循环
    }

    /**
     * 提交一个任务，返回 Deferred 供调用方 await。
     * @param priority 优先级，默认 0
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> submit(priority: Int = 0, block: suspend () -> T): Deferred<T> {
        val seq = seqGen.getAndIncrement()       // 同步分配序号，保证 FIFO 语义
        val deferred = CompletableDeferred<Any?>()
        scope.launch {
            mutex.withLock { queue.add(PendingTask(priority, seq, block, deferred)) }
            signal.send(Unit)
        }
        return deferred as Deferred<T>
    }

    /** 调度循环：每次取一个最高优先级任务，过限速 + 限并发后并发执行。 */
    private suspend fun pump() {
        while (true) {
            signal.receive()                                  // 等到“有任务”信号
            val task = mutex.withLock { queue.poll() } ?: continue
            bucket.acquire()                                  // ① 限速：没令牌就在这等
            semaphore.acquire()                               // ② 限并发：满了就在这等
            // ③ 真正执行：丢到子协程跑，互不阻塞；结束后释放并发许可。
            scope.launch {
                try {
                    task.result.complete(task.block())
                } catch (e: Throwable) {
                    task.result.completeExceptionally(e)
                } finally {
                    semaphore.release()
                }
            }
        }
    }
}
