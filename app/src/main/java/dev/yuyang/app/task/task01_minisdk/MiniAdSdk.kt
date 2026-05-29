package dev.yuyang.app.task.task01_minisdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 * Task 1：实现一个 Mini 广告 SDK（最可能出的题型）
 * ============================================================================
 *
 * 【面试场景】
 * 面试官会让你实现一个简单的广告 SDK，要求大致是：
 *   1. init(context, apiKey) 初始化
 *   2. loadAd(adUnitId, listener) 从（mock 的）服务器拉广告
 *   3. showAd(container) 把广告渲染进一个 ViewGroup
 *   4. 通过 listener 回调事件：onLoaded / onFailed / onDisplayed / onClicked
 *   5. 处理并发加载、生命周期、资源清理
 *
 * 【SDK 是被几千个第三方 App 集成的代码，所以面试官重点看这些“SDK 思维”】
 *   - 公开 API 要小、要稳、绝不抛未声明异常
 *   - 永远 thread-safe（用户可能从任意线程调用你的方法）
 *   - 绝不持有 Activity，只存 applicationContext（否则内存泄漏，泄漏的是宿主 App）
 *   - 回调默认回到主线程（用户期望在主线程更新 UI）
 *   - 提供明确的 destroy()，释放后不能再回调到已销毁的 listener
 *   - @JvmStatic 方便 Java 调用方（很多宿主 App 还是 Java）
 * ============================================================================
 */

/** 广告数据模型：一个广告就是 id + 标题 + 正文（真实里还会有素材 URL、落地页等）。 */
data class Ad(val adUnitId: String, val title: String, val body: String)

/**
 * 广告事件回调接口。
 * SDK 通过它把“加载成功/失败/展示/点击”这些事件通知给宿主 App。
 * 这些回调全部会在主线程触发（见下面实现），宿主可以直接更新 UI。
 */
interface AdListener {
    /** 广告加载成功，把广告对象给回去。 */
    fun onLoaded(ad: Ad)

    /** 广告加载失败，带上失败原因（字符串而不是抛异常，对调用方更友好）。 */
    fun onFailed(reason: String)

    /** 广告已经渲染到容器上、展示出来了。 */
    fun onDisplayed(ad: Ad)

    /** 用户点击了广告。 */
    fun onClicked(ad: Ad)
}

/**
 * SDK 主体。构造函数私有：外部只能通过 [MiniAdSdk.init] 拿到唯一实例（单例）。
 *
 * @param appContext 已经是 applicationContext（在 init 里转好的），保证不泄漏 Activity。
 * @param apiKey     初始化时传入的 key（这里 demo 没真正用到，加 @Suppress 消除告警）。
 */
class MiniAdSdk private constructor(
    private val appContext: Context,
    @Suppress("unused") private val apiKey: String,
) {
    /**
     * 每个广告位（adUnitId）的状态机：
     * IDLE（空闲，没加载过）→ LOADING（加载中）→ LOADED（加载好了可以展示）
     *                                          ↘ FAILED（加载失败）
     * LOADED → SHOWN（已经展示出去了）
     * 面试官想看你“用状态机管理生命周期”，而不是一堆零散的 boolean。
     */
    enum class State { IDLE, LOADING, LOADED, SHOWN, FAILED }

    /** 后台线程池：广告加载是网络/IO 操作，绝不能放主线程，否则 ANR（界面卡死）。 */
    private val executor = Executors.newCachedThreadPool()

    /** 绑定主线程 Looper 的 Handler：用它把回调从后台线程切回主线程。 */
    private val main = Handler(Looper.getMainLooper())

    /** 是否已销毁。AtomicBoolean 保证多线程下的可见性和原子性（compareAndSet 一次性翻转）。 */
    private val destroyed = AtomicBoolean(false)

    /** 每个广告位当前的状态。ConcurrentHashMap：多线程读写安全。 */
    private val states = ConcurrentHashMap<String, State>()

    /** 已加载好、等待展示的广告缓存。 */
    private val loadedAds = ConcurrentHashMap<String, Ad>()

    /** 正在加载中的广告位标记，用于“并发去重”（同一个广告位不重复发起加载）。 */
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * 加载广告。
     * @param adUnitId 广告位 id
     * @param listener 结果回调（会在主线程触发）
     */
    fun loadAd(adUnitId: String, listener: AdListener) {
        // 已销毁就直接抛（这是编程错误，应当让调用方在开发期就发现）。
        check(!destroyed.get()) { "SDK is destroyed" }

        // 【并发去重】putIfAbsent 是原子操作：如果该广告位已经在加载（返回非 null），
        // 说明有重复请求，直接回调失败、不再发起第二次网络请求。
        if (inFlight.putIfAbsent(adUnitId, true) != null) {
            postFail(listener, "load already in progress for $adUnitId")
            return
        }

        states[adUnitId] = State.LOADING
        // 真正的加载丢到后台线程池执行。
        executor.execute {
            try {
                val ad = fetchAd(adUnitId)            // 模拟网络请求
                states[adUnitId] = State.LOADED
                loadedAds[adUnitId] = ad
                inFlight.remove(adUnitId)             // 加载结束，去掉“进行中”标记
                // 切回主线程回调；如果此刻已经 destroy，则不回调（避免回调到已释放对象）。
                main.post { if (!destroyed.get()) listener.onLoaded(ad) }
            } catch (e: Exception) {
                states[adUnitId] = State.FAILED
                inFlight.remove(adUnitId)
                postFail(listener, e.message ?: "unknown error")
            }
        }
    }

    /**
     * 展示已加载好的广告。
     * @param container 宿主提供的容器 ViewGroup，广告会渲染进去。
     */
    fun showAd(adUnitId: String, container: ViewGroup, listener: AdListener) {
        check(!destroyed.get()) { "SDK is destroyed" }

        val ad = loadedAds[adUnitId]
        // 没加载好就不能展示，回调失败而不是崩溃。
        if (ad == null || states[adUnitId] != State.LOADED) {
            postFail(listener, "ad not loaded for $adUnitId")
            return
        }

        // 渲染 View 必须在主线程做。
        main.post {
            if (destroyed.get()) return@post
            container.removeAllViews()                 // 清掉旧内容，避免重复叠加
            container.addView(renderAdView(ad, listener))
            states[adUnitId] = State.SHOWN
            listener.onDisplayed(ad)
        }
    }

    /** 查询某广告位当前状态；没记录过就是 IDLE。 */
    fun stateOf(adUnitId: String): State = states[adUnitId] ?: State.IDLE

    /**
     * 销毁 SDK，释放资源。
     * compareAndSet(false, true)：只有第一次调用会真正执行，重复调用是安全的（幂等）。
     */
    fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            executor.shutdownNow()  // 立即停止线程池里所有任务
            states.clear()
            loadedAds.clear()
            inFlight.clear()
        }
    }

    /** 把广告渲染成一个简单的 View（demo 用 TextView，点一下触发 onClicked）。 */
    private fun renderAdView(ad: Ad, listener: AdListener): FrameLayout {
        val frame = FrameLayout(appContext)
        val tv = TextView(appContext).apply {
            text = "${ad.title}\n${ad.body}"
            setOnClickListener { listener.onClicked(ad) }  // 点击事件回调
        }
        frame.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
        return frame
    }

    /** 统一的失败回调：切回主线程 + 销毁后不回调。 */
    private fun postFail(listener: AdListener, reason: String) {
        main.post { if (!destroyed.get()) listener.onFailed(reason) }
    }

    /**
     * 模拟一次网络请求。
     * 真实场景这里会用 OkHttp/Retrofit 去拉广告；面试时可以先 mock，把架构搭起来再说。
     */
    private fun fetchAd(adUnitId: String): Ad {
        Thread.sleep(300)                                  // 模拟网络耗时
        require(adUnitId.isNotBlank()) { "empty adUnitId" } // 入参校验
        return Ad(adUnitId, "Sponsored", "Demo creative for $adUnitId")
    }

    /**
     * 伴生对象：负责单例的创建和获取。
     * 这是 SDK 的标准入口写法，面试官会盯着看你单例写得对不对。
     */
    companion object {
        // @Volatile：保证 instance 的写对其他线程立即可见（双检锁的必备条件）。
        @Volatile
        private var instance: MiniAdSdk? = null

        /**
         * 初始化并返回单例。
         * 【双重检查锁定 Double-Checked Locking】：
         *   - 第一次判空在锁外（已初始化时无需加锁，性能好）
         *   - 第二次判空在锁内（防止两个线程同时通过第一次判空、重复创建）
         * context.applicationContext：在这里就转成 application 级别，从源头杜绝 Activity 泄漏。
         */
        @JvmStatic
        fun init(context: Context, apiKey: String): MiniAdSdk =
            instance ?: synchronized(this) {
                instance ?: MiniAdSdk(context.applicationContext, apiKey).also { instance = it }
            }

        /** 获取已初始化的单例；没 init 过就抛异常（明确提示调用顺序错误）。 */
        @JvmStatic
        fun getInstance(): MiniAdSdk =
            instance ?: throw IllegalStateException("Call MiniAdSdk.init() first")
    }
}
