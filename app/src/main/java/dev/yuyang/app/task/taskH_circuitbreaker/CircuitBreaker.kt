package dev.yuyang.app.task.taskH_circuitbreaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ============================================================================
 * Task H：熔断器 Circuit Breaker（正好对应简历里的 "circuit-breaker fallback"）
 * ============================================================================
 *
 * 【背景】下游（交易所/网络）一直出错时，继续硬打请求只会雪上加霜，还让用户一直转圈。
 * 熔断器像电路保险丝：错误多到一定程度就“跳闸”，快速失败 + 走降级，给下游喘息时间，
 * 过一会儿再试探性放行，好了就恢复。
 *
 * 【三态状态机】
 *   CLOSED   （闭合/正常）：正常放行；用滑动窗口统计最近 N 次调用的失败数，
 *                          失败数达到阈值 → 跳到 OPEN
 *   OPEN     （断开/熔断）：直接快速失败走降级，不打下游；
 *                          经过 openDuration 后 → 跳到 HALF_OPEN
 *   HALF_OPEN（半开/试探）：只放行少量试探请求；
 *                          成功 → 回 CLOSED（恢复）；失败 → 回 OPEN（继续熔断）
 *
 * 这是分布式/客户端容错的经典模式，能讲清三态转换是很强的资深信号。
 * ============================================================================
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,   // 窗口内失败多少次就熔断
    private val windowSize: Int = 10,        // 滑动窗口：只看最近这么多次调用
    private val openDurationMs: Long = 10_000L, // 熔断后多久进入半开试探
    private val halfOpenMaxCalls: Int = 1,   // 半开时允许几个试探请求
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private val window = ArrayDeque<Boolean>() // 最近调用结果，true=成功
    private var openedAt = 0L                   // 进入 OPEN 的时间戳
    private var halfOpenInFlight = 0            // 半开状态下已放行的试探数
    private val mutex = Mutex()

    /** 当前状态（对外只读，便于打点/调试）。 */
    suspend fun currentState(): State = mutex.withLock { gateRefresh(); state }

    /**
     * 受熔断保护地执行一段逻辑。
     * @param block    真正要执行的操作（比如一次网络请求）
     * @param fallback 降级逻辑：熔断时、或 block 抛异常时调用，保证调用方总能拿到结果
     */
    suspend fun <T> execute(
        block: suspend () -> T,
        fallback: suspend (Throwable?) -> T,
    ): T {
        // 先过“闸门”：看当前状态允不允许放行。
        val allowed = mutex.withLock { tryPass() }
        if (!allowed) {
            // 熔断中，快速失败走降级（不打下游）。
            return fallback(null)
        }

        return try {
            val result = block()
            mutex.withLock { onSuccess() }
            result
        } catch (e: CancellationException) {
            throw e                                   // 协程取消不算业务失败，透传
        } catch (e: Throwable) {
            mutex.withLock { onFailure() }
            fallback(e)                               // 失败也走降级，把异常给降级逻辑参考
        }
    }

    /** 闸门判断（需在持锁下调用）：返回是否放行，并按需做状态迁移。 */
    private fun tryPass(): Boolean = when (state) {
        State.CLOSED -> true
        State.OPEN -> {
            if (now() - openedAt >= openDurationMs) {
                // 熔断时间够了 → 进入半开，放行第一个试探。
                state = State.HALF_OPEN
                halfOpenInFlight = 1
                true
            } else {
                false // 还在熔断窗口内，拒绝
            }
        }
        State.HALF_OPEN -> {
            if (halfOpenInFlight < halfOpenMaxCalls) {
                halfOpenInFlight++
                true
            } else {
                false // 半开期试探名额已满，其余请求仍快速失败
            }
        }
    }

    /** 成功回调（持锁）。 */
    private fun onSuccess() {
        when (state) {
            State.HALF_OPEN -> reset()                // 试探成功 → 恢复正常
            else -> record(true)
        }
    }

    /** 失败回调（持锁）。 */
    private fun onFailure() {
        when (state) {
            State.HALF_OPEN -> trip()                 // 试探又失败 → 继续熔断
            else -> {
                record(false)
                // 窗口内失败数达到阈值 → 跳闸。
                if (window.count { !it } >= failureThreshold) trip()
            }
        }
    }

    /** 记录一次结果到滑动窗口，超出窗口大小就丢掉最老的。 */
    private fun record(success: Boolean) {
        window.addLast(success)
        while (window.size > windowSize) window.removeFirst()
    }

    /** 跳闸：进入 OPEN。 */
    private fun trip() {
        state = State.OPEN
        openedAt = now()
        halfOpenInFlight = 0
    }

    /** 恢复：回到 CLOSED，清空统计。 */
    private fun reset() {
        state = State.CLOSED
        window.clear()
        halfOpenInFlight = 0
    }

    /** currentState 查询时顺手把“OPEN 到点该转 HALF_OPEN”刷新一下。 */
    private fun gateRefresh() {
        if (state == State.OPEN && now() - openedAt >= openDurationMs) {
            state = State.HALF_OPEN
            halfOpenInFlight = 0
        }
    }
}
