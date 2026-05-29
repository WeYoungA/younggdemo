package dev.yuyang.app.task.taskI_experiment

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * Task I：A/B 实验 & 特性开关 SDK（广告/增长平台标配，进阶题）
 * ============================================================================
 *
 * 【题目】实现一个实验 SDK：
 *   1. 把用户稳定地分到实验的某个分组（variant），按权重分流
 *   2. “粘性”：同一个用户每次拿到的分组必须一致（否则数据没法分析）
 *   3. 第一次真正读到某实验时上报一次“曝光”（exposure），且只报一次
 *   4. 同时支持简单的特性开关（typed flag：布尔/字符串/整数，带默认值）
 *
 * 【核心难点：一致性哈希分桶】
 *   用 hash(userId + 实验key) 把用户映射到 [0,10000) 的一个桶，再按权重区间落到某个分组。
 *   同一个用户 + 同一个实验 → 永远同一个桶 → 永远同一个分组（这就是“稳定/粘性”的根基），
 *   而且不需要服务器记录每个人分到哪，纯靠计算，海量用户也扛得住。
 * ============================================================================
 */

/** 一个分组：名字 + 权重（权重决定分流比例，比如 50/50 或 10/90）。 */
data class Variant(val name: String, val weight: Int)

/** 一个实验：key + 若干分组。 */
data class Experiment(val key: String, val variants: List<Variant>) {
    init {
        require(variants.isNotEmpty()) { "experiment must have at least one variant" }
        require(variants.all { it.weight > 0 }) { "weights must be positive" }
    }
}

/** 曝光上报接口（解耦，SDK 不关心埋点怎么发）。 */
fun interface ExposureTracker {
    fun onExposed(experimentKey: String, variant: String)
}

/**
 * 粘性分配的存储接口：把“用户分到的组”存下来，保证下次一致、甚至跨启动一致。
 * 默认给一个内存实现；生产可换成 DataStore/SharedPreferences。
 */
interface AssignmentStore {
    fun get(key: String): String?
    fun put(key: String, variant: String)
}

class InMemoryAssignmentStore : AssignmentStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, variant: String) {
        map[key] = variant
    }
}

class ExperimentSdk(
    private val userId: String,
    experiments: List<Experiment>,
    flags: Map<String, Any> = emptyMap(),
    private val exposureTracker: ExposureTracker = ExposureTracker { _, _ -> },
    private val store: AssignmentStore = InMemoryAssignmentStore(),
) {
    private val experiments = experiments.associateBy { it.key }
    private val flags = HashMap(flags)

    // 记录已经上报过曝光的实验，保证“只报一次”。
    private val exposed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 取用户在某实验里的分组。
     * 流程：先查粘性存储（命中直接用）→ 否则一致性哈希算一个 → 存进存储；
     * 然后（每个实验首次）上报一次曝光。
     */
    fun variant(experimentKey: String): String? {
        val experiment = experiments[experimentKey] ?: return null

        // 粘性：已分配过就复用，保证一致。
        val cacheKey = "$userId:$experimentKey"
        val assigned = store.get(cacheKey) ?: assign(experiment).also { store.put(cacheKey, it) }

        // 曝光只报一次（putIfAbsent 风格：add 成功说明是第一次）。
        if (exposed.add(experimentKey)) {
            exposureTracker.onExposed(experimentKey, assigned)
        }
        return assigned
    }

    /** 便捷判断：用户是否落在某实验的某个目标分组。 */
    fun isInVariant(experimentKey: String, variant: String): Boolean =
        variant(experimentKey) == variant

    /** 一致性哈希分桶 + 按权重落组。 */
    private fun assign(experiment: Experiment): String {
        val bucket = bucketOf("$userId:${experiment.key}") // 0..9999
        val total = experiment.variants.sumOf { it.weight }
        // 把桶值（0~9999）映射到 [0,total) 区间，再按累积权重找落点。
        var cursor = (bucket.toLong() * total / BUCKETS).toInt()
        for (v in experiment.variants) {
            if (cursor < v.weight) return v.name
            cursor -= v.weight
        }
        return experiment.variants.last().name // 理论到不了，兜底
    }

    /**
     * 把字符串稳定地映射到 [0, BUCKETS) 的一个桶。
     * 用 MD5 取前 4 字节拼成无符号整数再取模 —— 跨平台、跨启动都稳定（不依赖 String.hashCode）。
     */
    private fun bucketOf(input: String): Int {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        val unsigned = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return (unsigned % BUCKETS).toInt()
    }

    // ---- 特性开关：类型安全 + 默认值 ----
    fun getBoolean(key: String, default: Boolean): Boolean = (flags[key] as? Boolean) ?: default
    fun getString(key: String, default: String): String = (flags[key] as? String) ?: default
    fun getInt(key: String, default: Int): Int = (flags[key] as? Number)?.toInt() ?: default

    companion object {
        private const val BUCKETS = 10_000L
    }
}
