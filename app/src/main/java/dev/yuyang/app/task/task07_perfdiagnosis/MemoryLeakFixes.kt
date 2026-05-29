package dev.yuyang.app.task.task07_perfdiagnosis

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * ============================================================================
 * Task 7：性能 / 内存问题诊断
 * ============================================================================
 *
 * 【面试场景】“这个 App 有内存泄漏 / 启动慢，找出来并修复。”
 *
 * 本文件把 Android 最常见的三种内存泄漏，各写一个【错误版】和【正确版】，
 * 把“为什么泄漏、怎么修”讲清楚。
 *
 * 【实战怎么定位】
 *   - LeakCanary（项目里已经以 debugImplementation 引入）：会直接弹出
 *     “被泄漏对象 → 一直引用它的链路”，照着链路改即可
 *   - Android Studio Profiler：看 Memory 曲线是不是只涨不降，确认堆增长
 * ============================================================================
 */

// ---------------------------------------------------------------------------
// 泄漏 1：单例持有 Activity 的 Context。
// 单例活得和进程一样久，它引用着 Activity，Activity 就永远回收不掉。
// ---------------------------------------------------------------------------

/** 错误示范：如果有人把 Activity 赋给这个 context，Activity 就泄漏了。 */
object BadConfigStore {
    var context: Context? = null // BUG：存了 Activity 就泄漏
}

/** 正确做法：内部强制只存 applicationContext。 */
object GoodConfigStore {
    private var appContext: Context? = null

    /** 入参可能是 Activity，但这里立刻 .applicationContext，从源头杜绝泄漏。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun context(): Context = requireNotNull(appContext) { "call init() first" }
}

// ---------------------------------------------------------------------------
// 泄漏 2：Handler 的延时消息把外部（通常是 Activity）一直拽着。
// ---------------------------------------------------------------------------

/** 错误示范：postDelayed 的 lambda 捕获了 this（常常连带 Activity）。 */
class BadHandlerHolder(private val onTick: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())

    fun schedule() {
        // BUG：这条消息要 60 秒后才执行，这 60 秒里它一直引用着 onTick（及其外部对象）。
        // 如果 Activity 在这期间销毁，也没法回收，要等消息执行完。
        handler.postDelayed({ onTick() }, 60_000L)
    }
}

/**
 * 正确做法：
 *   1. 用 WeakReference 弱引用回调（外部该回收就回收，这里 get() 拿到 null 就不调）
 *   2. 保存 Runnable 引用，提供 cancel() 在 onDestroy/onStop 里移除待执行消息
 */
class GoodHandlerHolder(onTick: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val callbackRef = WeakReference(onTick)
    private val runnable = Runnable { callbackRef.get()?.invoke() }

    fun schedule() {
        handler.postDelayed(runnable, 60_000L)
    }

    /** 在 onDestroy / onStop 里调用，移除还没执行的消息，断开引用。 */
    fun cancel() {
        handler.removeCallbacks(runnable)
    }
}

// ---------------------------------------------------------------------------
// 泄漏 3：向一个长生命周期的源注册了监听，却从不反注册。
// ---------------------------------------------------------------------------

/** 一个“活很久”的事件源（比如定位服务、传感器、EventBus）。 */
interface LocationSource {
    fun addListener(listener: (Double) -> Unit)
    fun removeListener(listener: (Double) -> Unit)
}

/** 错误示范：只 add 不 remove，consumer（及它引用的界面）被 source 永久持有。 */
class BadLocationConsumer(private val source: LocationSource) {
    fun start() {
        // BUG：注册了却没有对应的反注册。
        source.addListener { /* 更新 UI */ }
    }
}

/**
 * 正确做法：把 listener 存成一个稳定引用，保证“注册的”和“反注册的”是同一个对象，
 * 并且每个 start() 都要有生命周期驱动的 stop() 来配对。
 */
class GoodLocationConsumer(private val source: LocationSource) {
    private val listener: (Double) -> Unit = { /* 更新 UI */ }

    fun start() = source.addListener(listener)

    /** 在合适的生命周期（onStop/onDestroy）里反注册。 */
    fun stop() = source.removeListener(listener)
}
