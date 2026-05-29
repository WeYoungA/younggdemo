package dev.yuyang.app.task.taskJ_viewability

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * ============================================================================
 * Task J：广告可见性 / 曝光追踪（ad-tech 最核心的 viewability，进阶题）
 * ============================================================================
 *
 * 【背景】广告计费/统计里，“展示了”不等于“被看见了”。行业标准（IAB）：
 * 广告至少 50% 的面积，连续可见至少 1 秒，才算一次有效曝光（impression）。
 *
 * 【题目】给一个广告 View，当它“连续 ≥50% 可见 ≥1 秒”时，触发一次曝光回调，且只触发一次。
 *
 * 【难点】
 *   - 怎么算“可见比例”：getLocalVisibleRect 拿到 View 露出来的部分，除以总面积
 *   - 怎么知道可见性变了：监听滚动（OnScrollChangedListener）和布局（OnGlobalLayout）
 *   - “连续 1 秒”：可见时起一个 1 秒定时器；中途掉到 50% 以下就取消定时器（重新计时）
 *   - 退到后台不算可见：用 DefaultLifecycleObserver，前台才追踪
 *   - 防泄漏：停止时务必反注册监听 + 移除定时器
 * ============================================================================
 */
class ViewabilityTracker(
    private val view: View,
    private val minVisibleFraction: Double = 0.5, // 至少可见比例（50%）
    private val minDurationMs: Long = 1_000L,     // 至少持续时间（1 秒）
    private val onImpression: () -> Unit,         // 满足条件时回调（只回调一次）
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val rect = Rect() // 复用，避免每次 new

    private var impressionFired = false // 是否已上报过（只报一次）
    private var timerScheduled = false  // 1 秒定时器是否在计时中

    // 滚动会改变可见性 → 重新评估
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { evaluate() }
    // 布局变化（比如刚被加进列表、尺寸确定）→ 重新评估
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { evaluate() }

    /** 1 秒后真正确认曝光的任务。 */
    private val impressionRunnable = Runnable {
        // 关键：定时器到点时再确认一次“此刻仍然可见”，防止这 1 秒里偷偷滚走了。
        if (!impressionFired && currentVisibleFraction() >= minVisibleFraction) {
            impressionFired = true
            onImpression()
            stop() // 报过就不用再追踪了，顺手清理
        } else {
            timerScheduled = false
        }
    }

    /** 开始追踪：注册监听并立即评估一次。 */
    fun start() {
        if (impressionFired) return
        view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        evaluate()
    }

    /** 停止追踪：反注册 + 移除定时器（防泄漏的关键，必须和 start 配对）。 */
    fun stop() {
        // viewTreeObserver 可能已失效，用 view 自身的（更稳妥）。
        view.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        handler.removeCallbacks(impressionRunnable)
        timerScheduled = false
    }

    /**
     * 评估当前可见性，决定是否开始/取消计时。
     *   - 够 50%：若还没计时，启动 1 秒定时器
     *   - 不够：若在计时，取消（“连续”被打断，下次得重新累计）
     */
    private fun evaluate() {
        if (impressionFired) return
        val fraction = currentVisibleFraction()
        if (fraction >= minVisibleFraction) {
            if (!timerScheduled) {
                timerScheduled = true
                handler.postDelayed(impressionRunnable, minDurationMs)
            }
        } else {
            if (timerScheduled) {
                timerScheduled = false
                handler.removeCallbacks(impressionRunnable)
            }
        }
    }

    /** 计算 View 当前露出来的面积占自身总面积的比例（0~1）。 */
    private fun currentVisibleFraction(): Double {
        // isShown=false 表示自己或某层父 View 不可见（如所在页退到后台）。
        if (!view.isShown) return 0.0
        val total = view.width.toLong() * view.height
        if (total <= 0L) return 0.0
        // getLocalVisibleRect：把“在屏幕上真正露出的部分”填进 rect（View 自身坐标系）。
        if (!view.getLocalVisibleRect(rect)) return 0.0
        val visible = rect.width().toLong() * rect.height()
        return visible.toDouble() / total
    }

    // ---- 生命周期感知：前台才追踪，后台停（后台的“可见”不算曝光）----
    override fun onResume(owner: LifecycleOwner) = start()
    override fun onPause(owner: LifecycleOwner) = stop()
}
