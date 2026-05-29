package dev.yuyang.app.task.task04_videoplayer

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * ============================================================================
 * Task 4：视频/媒体播放器封装
 * ============================================================================
 *
 * 【面试场景】实现一个视频播放器组件：
 *   1. 从 URL 加载
 *   2. 播放 / 暂停 / 跳转
 *   3. 上报事件
 *   4. 退到后台暂停、回到前台恢复
 *
 * 【面试官最想看的 Android 老手信号】
 *   - 用 DefaultLifecycleObserver 让播放器“自己”监听生命周期，
 *     而不是在 Activity 里手写一堆 onPause/onStop（解耦、不易漏）
 *   - 退后台一定要 release() ExoPlayer！不释放会占着解码器、内存暴涨甚至 OOM，
 *     这是面试官最爱考的反例
 *   - 只存 applicationContext，不持有 Activity
 *   - 把 ExoPlayer 的事件抽象成自己的枚举，调用方不直接依赖 ExoPlayer 常量
 *
 * 【用法】
 *   val player = LifecycleVideoPlayer(context) { ...事件回调... }
 *   lifecycle.addObserver(player)        // 交给生命周期托管
 *   player.setUrl(url)
 *   playerView.player = player.exoPlayer // 把底层 player 挂到 Media3 的 PlayerView 上
 * ============================================================================
 */

/** 把 ExoPlayer 的内部状态码翻译成自己的枚举，调用方不需要认识 ExoPlayer 的常量。 */
enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

/** 播放事件回调接口。 */
interface PlayerEventListener {
    fun onStateChanged(state: PlaybackState)  // 状态变化（缓冲/就绪/结束…）
    fun onError(message: String)              // 出错
}

class LifecycleVideoPlayer(
    context: Context,
    private val listener: PlayerEventListener? = null,
) : DefaultLifecycleObserver {

    // 只存 applicationContext，杜绝 Activity 泄漏。
    private val appContext = context.applicationContext

    // ExoPlayer 实例。可空：因为退后台时会 release 置空，前台再重建。
    private var player: ExoPlayer? = null

    private var currentUrl: String? = null      // 当前要播放的地址
    private var resumePosition: Long = 0L        // 记住播放进度，用于恢复时 seek 回去
    private var resumeWhenReady: Boolean = true  // 记住“之前是不是在播”，恢复时还原

    /**
     * 监听 ExoPlayer 内部事件，转成我们自己的回调。
     * Player.Listener 是 ExoPlayer 的标准回调接口。
     */
    private val internalListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            listener?.onStateChanged(
                when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                },
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(error.message ?: "playback error")
        }
    }

    /** 对外暴露底层 player（懒创建），用于挂到 PlayerView 上显示画面。 */
    val exoPlayer: ExoPlayer
        get() = ensurePlayer()

    /** 设置要播放的 URL；换片源时把进度清零。 */
    fun setUrl(url: String) {
        currentUrl = url
        resumePosition = 0L
    }

    /** 播放。第一次会 setMediaItem + prepare，然后 seek 到记录的进度。 */
    fun play() {
        val p = ensurePlayer()
        val url = currentUrl ?: return
        if (p.currentMediaItem == null) {        // 还没设过片源才设，避免重复 prepare
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.seekTo(resumePosition)
        }
        p.playWhenReady = true                    // 准备好就自动播
    }

    /** 暂停：playWhenReady=false，准备好也不播。 */
    fun pause() {
        player?.playWhenReady = false
    }

    /** 跳转到指定毫秒。 */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    /** 懒创建 ExoPlayer：第一次用到才建，并注册事件监听。 */
    private fun ensurePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(appContext).build().also {
            it.addListener(internalListener)
            player = it
        }

    /**
     * 释放播放器。释放前先把当前进度和“是否在播”记下来，方便恢复时还原。
     * 一定要 removeListener + release，否则解码器和内存泄漏。
     */
    private fun release() {
        player?.let {
            resumePosition = it.currentPosition
            resumeWhenReady = it.playWhenReady
            it.removeListener(internalListener)
            it.release()
        }
        player = null
    }

    // ======== 生命周期回调：前台重建、后台释放 ========
    // 这是“lifecycle-aware 组件”的精髓：播放器自己管自己，Activity 啥都不用写。

    /** 回到前台：重建 player，恢复到之前的进度和播放状态。 */
    override fun onResume(owner: LifecycleOwner) {
        if (currentUrl != null) {
            val p = ensurePlayer()
            p.setMediaItem(MediaItem.fromUri(currentUrl!!))
            p.prepare()
            p.seekTo(resumePosition)
            p.playWhenReady = resumeWhenReady
        }
    }

    /** 退到后台：释放（省内存、释放解码器）。进度已在 release 里存好。 */
    override fun onPause(owner: LifecycleOwner) {
        release()
    }

    /** 界面销毁：再保险释放一次。 */
    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }
}
