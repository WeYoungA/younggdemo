package dev.yuyang.app.task.taskA_mediationsdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * ============================================================================
 * Task A：实时竞价广告聚合 SDK（最贴 AppLovin 主业，MAX 就是做这个的）
 * ============================================================================
 *
 * 【题目】实现一个广告聚合 SDK：同时向多个广告网络竞价，返回 CPM（千次展示价）
 * 最高的那个广告；每个网络有独立超时；要有兜底；记录瀑布流事件用于分析。
 *
 * 【这道题的核心是“并发协调”，面试官想看的高级信号】
 *   - supervisorScope：一个广告网络失败/超时，不能连累整场竞价
 *   - 每个网络独立 withTimeout：慢的网络不能拖死整体
 *   - awaitAll：所有网络真正并发地等结果
 *   - 用 auctionId 串起整场竞价的埋点（瀑布流可追溯）
 *   - SDK 级作用域用 SupervisorJob；release() 一次性取消
 *   - 回调切回主线程（Main.immediate）
 * ============================================================================
 */

/** 广告内容（真实里还有素材、落地页、追踪链接等）。 */
data class AdContent(val id: String, val creativeUrl: String, val payload: String)

/**
 * 一个广告网络的竞价结果，三选一：
 *   Won 出价成功（带 CPM 价格）/ Failed 失败 / Timeout 超时。
 * 用 sealed interface，when 分支能被编译器检查“是否覆盖全”。
 */
sealed interface BidResult {
    val networkName: String

    data class Won(val ad: AdContent, val cpm: Double, override val networkName: String) : BidResult
    data class Failed(val reason: String, override val networkName: String) : BidResult
    data class Timeout(override val networkName: String) : BidResult
}

/** 广告网络适配器接口：每接入一个广告平台就实现一个。 */
interface AdNetworkAdapter {
    val networkName: String
    val priority: Int
    suspend fun bid(adUnitId: String): BidResult  // 向这个网络竞价
}

/** 埋点上报接口（解耦：引擎不关心埋点具体怎么发）。 */
interface AnalyticsClient {
    fun track(event: String, params: Map<String, Any?>)
}

/** 配置：单个网络的竞价超时（默认 3 秒）。 */
data class MediationConfig(val bidTimeoutMs: Long = 3_000L)

/** 整场竞价的最终结果：成功（拿到最高价广告）或 NoFill（全军覆没，带各家原因）。 */
sealed interface MediationResult {
    data class Success(val ad: AdContent, val cpm: Double, val networkName: String) : MediationResult
    data class NoFill(val reasons: List<BidResult>) : MediationResult
}

/**
 * 竞价引擎（核心）。
 * @param adapters  所有接入的广告网络
 * @param analytics 埋点上报
 * @param config    配置（超时）
 * @param now       取时间函数，可注入便于测试
 */
class MediationEngine(
    private val adapters: List<AdNetworkAdapter>,
    private val analytics: AnalyticsClient,
    private val config: MediationConfig = MediationConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 发起一场竞价。
     * supervisorScope：作用域内某个子协程失败，不会取消其它子协程，也不会向上抛——
     * 正好满足“一个网络挂了，竞价继续”。
     */
    suspend fun loadAd(adUnitId: String): MediationResult = supervisorScope {
        val auctionId = UUID.randomUUID().toString()   // 本场竞价唯一 id，串联所有埋点
        val start = now()
        analytics.track(
            "auction_started",
            mapOf("auctionId" to auctionId, "adUnitId" to adUnitId, "networkCount" to adapters.size),
        )

        // 把每个网络包成一个 async（并发启动），每个都套 withTimeout 独立超时。
        val results: List<BidResult> = adapters.map { adapter ->
            async(Dispatchers.IO) {
                val result = try {
                    withTimeout(config.bidTimeoutMs) { adapter.bid(adUnitId) }
                } catch (e: TimeoutCancellationException) {
                    BidResult.Timeout(adapter.networkName)        // 超时 → 记为 Timeout
                } catch (e: Exception) {
                    BidResult.Failed(e.message ?: "unknown", adapter.networkName) // 其它异常 → Failed
                }
                analytics.track(
                    "bid_result",
                    mapOf(
                        "auctionId" to auctionId,
                        "network" to adapter.networkName,
                        "outcome" to result::class.simpleName,
                    ),
                )
                result
            }
        }.awaitAll()  // 等所有网络都返回（并发，总耗时≈最慢的那个，不是求和）

        // 从所有“出价成功”里挑 CPM 最高的当赢家。
        val winner = results.filterIsInstance<BidResult.Won>().maxByOrNull { it.cpm }
        analytics.track(
            "auction_completed",
            mapOf(
                "auctionId" to auctionId,
                "winner" to (winner?.networkName ?: "none"),
                "winningCpm" to (winner?.cpm ?: 0.0),
                "durationMs" to (now() - start),
            ),
        )

        if (winner != null) {
            MediationResult.Success(winner.ad, winner.cpm, winner.networkName)
        } else {
            MediationResult.NoFill(results)  // 没人出价，返回兜底，带上各家失败原因
        }
    }
}

/**
 * 对外的线程安全入口（SDK 门面）。
 * 内部维护一个 SupervisorJob 作用域，把挂起的引擎包成一个回调式 API 给调用方。
 */
class AdMediator private constructor(private val engine: MediationEngine) {

    // SDK 级作用域：SupervisorJob 保证单次任务失败不会拖垮整个 scope。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 回调式加载广告：内部跑协程，结果切回主线程回调。 */
    fun loadAd(adUnitId: String, callback: (MediationResult) -> Unit) {
        scope.launch {
            val result = engine.loadAd(adUnitId)
            // Main.immediate：如果当前已经在主线程，就不再多调度一次，直接执行。
            withContext(Dispatchers.Main.immediate) { callback(result) }
        }
    }

    /** 释放：取消作用域内所有协程。 */
    fun release() {
        scope.cancel()
    }

    companion object {
        @Volatile
        private var instance: AdMediator? = null

        // 双检锁单例。
        fun init(
            adapters: List<AdNetworkAdapter>,
            analytics: AnalyticsClient,
            config: MediationConfig = MediationConfig(),
        ): AdMediator = instance ?: synchronized(this) {
            instance ?: AdMediator(MediationEngine(adapters, analytics, config)).also { instance = it }
        }
    }
}

/** 一个示例广告网络适配器，让引擎开箱即可跑/测（固定延迟 + 固定 CPM）。 */
class FakeAdNetworkAdapter(
    override val networkName: String,
    override val priority: Int,
    private val cpm: Double,
    private val latencyMs: Long,
) : AdNetworkAdapter {
    override suspend fun bid(adUnitId: String): BidResult {
        kotlinx.coroutines.delay(latencyMs)  // 模拟该网络的竞价耗时
        return BidResult.Won(
            ad = AdContent(id = "$networkName-$adUnitId", creativeUrl = "https://cdn/$networkName", payload = "{}"),
            cpm = cpm,
            networkName = networkName,
        )
    }
}
