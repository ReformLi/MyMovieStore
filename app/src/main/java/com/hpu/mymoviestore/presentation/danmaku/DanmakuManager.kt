package com.hpu.mymoviestore.presentation.danmaku

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment

/**
 * 弹幕管理器（基于自实现的 DanmakuView）
 *
 * 职责：
 *  - 动态创建 DanmakuView 并添加到指定容器（覆盖容器顶部，占容器的全部区域）
 *  - 暴露 loadDanmaku(comments)、syncTo(positionMs)、pause()、resume()、release()、enable/disable 等 API
 *  - 为 PlayerActivity 提供生命周期对齐的弹幕控制
 *
 * 弹幕显示区域：占 danmakuContainer 的全部区域（PlayerActivity 在布局中把 danmakuContainer 的高度设置为屏幕 1/4 左右）
 */
class DanmakuManager(private val context: Context) {

    private var danmakuView: DanmakuView? = null
    private var lastSyncTimeMs: Long = -1L
    private var enabled: Boolean = true
    private var prepared: Boolean = false

    /** 挂载到容器 */
    fun attachToContainer(container: ViewGroup) {
        if (danmakuView != null) {
            Log.d(TAG, "DanmakuView 已存在，跳过 attach")
            return
        }
        // 在容器上设置裁剪属性
        container.clipChildren = false
        container.clipToPadding = false

        val view = DanmakuView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(view)
        danmakuView = view
        Log.d(TAG, "DanmakuView 已挂载到容器（容器 childCount=${container.childCount}）")
    }

    /** 加载弹幕列表（JSON 格式） */
    fun loadDanmaku(comments: List<DanmakuComment>?) {
        val view = danmakuView
        if (view == null) {
            Log.w(TAG, "loadDanmaku: DanmakuView 未初始化")
            return
        }
        view.loadDanmakuComments(comments)
        prepared = view.isPrepared()
        Log.d(TAG, "loadDanmaku: comments=${comments?.size}, prepared=$prepared")
    }

    /** 启动弹幕（播放器进入 STATE_READY 或首次加载时调用） */
    fun ensureStarted() {
        val view = danmakuView ?: return
        if (!enabled) return
        view.invalidate()
        Log.d(TAG, "ensureStarted")
    }

    /** 同步到播放器位置（毫秒） —— 每帧调用或每 100ms 调用一次都安全 */
    fun syncTo(positionMs: Long) {
        val view = danmakuView ?: return
        if (!prepared) return
        lastSyncTimeMs = positionMs
        view.seekTo(positionMs)
    }

    fun pause() {
        Log.d(TAG, "pause")
        // 自实现的 DanmakuView 不依赖内部状态暂停，只要不再 invalidate 就会停
        danmakuView?.setDanmakuEnabled(false)
    }

    fun resume() {
        if (!enabled) return
        danmakuView?.setDanmakuEnabled(true)
        Log.d(TAG, "resume")
    }

    /** 弹幕显示开关（不持久化，不影响总开关） */
    fun setDanmakuEnabled(on: Boolean) {
        enabled = on
        danmakuView?.setDanmakuEnabled(on)
        Log.d(TAG, "setDanmakuEnabled=$on")
    }

    fun isEnabled(): Boolean = enabled

    /** 释放资源（Activity onDestroy 调用） */
    fun release() {
        val v = danmakuView ?: return
        val parent = v.parent as? ViewGroup
        parent?.removeView(v)
        v.release()
        danmakuView = null
        prepared = false
        Log.d(TAG, "release")
    }

    companion object {
        private const val TAG = "DanmakuManager"
    }
}
