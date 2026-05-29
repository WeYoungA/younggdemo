package dev.yuyang.app.task.taskE_livechart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * ============================================================================
 * Task E：自定义折线图控件（Canvas 手绘）
 * ============================================================================
 *
 * 进阶文档里这道题是用 Jetpack Compose 的 Canvas 写的；本项目是 XML/View 栈，
 * 所以这里写成等价的【自定义 View + onDraw】版本——考的能力完全一样：
 * 用 Path 画折线、画渐变填充、圆头线帽、加载动画。
 *
 * 【面试官想看的高级信号】
 *   - Paint / Path 等对象在构造时只创建一次，绝不在 onDraw 里 new（避免每帧分配、
 *     触发 GC 卡顿）——这是自定义 View 性能的头号原则
 *   - 用 ValueAnimator 驱动一个 0→1 的进度，做出“折线逐渐画出来”的动画
 *   - 归一化到 min/max，并对“最大==最小”做除零保护
 *   - onDetachedFromWindow 里取消动画，避免泄漏
 *
 * 【用法】chartView.setData(listOf(3f, 5f, 2f, 8f, ...))
 * ============================================================================
 */
class LiveLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var lineColor: Int = Color.CYAN   // 线的颜色
    var fillGradient: Boolean = true  // 是否在线下方画渐变填充

    private var data: List<Float> = emptyList() // 当前数据
    private var progress: Float = 1f            // 动画进度 0→1（画出多少比例的折线）
    private var animator: ValueAnimator? = null

    // ===== 所有 Paint/Path 在这里创建一次，onDraw 里只用、不 new =====
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE      // 描边（画线）
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND     // 圆头线帽，端点更好看
        strokeJoin = Paint.Join.ROUND   // 转角圆滑
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val linePath = Path()  // 折线本身
    private val fillPath = Path()  // 折线 + 底边围成的填充区域

    /**
     * 设置数据并（可选）播放动画。
     * @param animate true 则从 0 开始动画画出来；false 则直接全画出来。
     */
    fun setData(values: List<Float>, animate: Boolean = true) {
        data = values
        animator?.cancel()
        if (animate) {
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500
                interpolator = DecelerateInterpolator()  // 先快后慢，更自然
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()                          // 每帧请求重绘
                }
                start()
            }
        } else {
            progress = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val points = data
        if (points.size < 2) return   // 少于两个点画不了线

        val w = width.toFloat()
        val h = height.toFloat()
        val maxVal = points.max()
        val minVal = points.min()
        val range = (maxVal - minVal).takeIf { it > 0f } ?: 1f  // 除零保护
        val xStep = w / (points.size - 1)                        // 相邻点的水平间距
        // 当前动画进度下，画到第几个点。
        val visibleCount = ((points.size - 1) * progress).toInt().coerceAtLeast(1)

        linePaint.color = lineColor
        dotPaint.color = lineColor

        // 构建折线路径：从第 0 个点开始，连到 visibleCount 个点。
        linePath.reset()
        linePath.moveTo(0f, yPos(points[0], minVal, range, h))
        for (i in 1..visibleCount) {
            linePath.lineTo(i * xStep, yPos(points[i], minVal, range, h))
        }

        // 渐变填充：在折线下方画一块“线色→透明”的竖直渐变，做出面积图效果。
        if (fillGradient) {
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(withAlpha(lineColor, 0.4f), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            fillPath.reset()
            fillPath.addPath(linePath)             // 复用折线
            fillPath.lineTo(visibleCount * xStep, h) // 拉到底边
            fillPath.lineTo(0f, h)                   // 再回到左下角
            fillPath.close()                         // 闭合成一块区域
            canvas.drawPath(fillPath, fillPaint)
        }

        canvas.drawPath(linePath, linePaint)  // 画折线
        // 在当前最新点画一个实心圆点，强调“实时最新值”。
        canvas.drawCircle(visibleCount * xStep, yPos(points[visibleCount], minVal, range, h), dp(5f), dotPaint)
    }

    /** 离开窗口时取消动画，避免动画持有 View 造成泄漏。 */
    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    /** 把数值映射成 y 像素坐标：值越大 y 越小（屏幕坐标系 y 向下）。 */
    private fun yPos(value: Float, min: Float, range: Float, height: Float): Float {
        val normalized = (value - min) / range
        return height - normalized * height
    }

    /** 给颜色叠加透明度（alpha 0~1）。 */
    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** dp 转 px。 */
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
