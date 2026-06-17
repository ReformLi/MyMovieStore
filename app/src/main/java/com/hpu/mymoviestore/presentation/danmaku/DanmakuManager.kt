package com.hpu.mymoviestore.presentation.danmaku

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment

/**
 * 弹幕管理器（基于自实现的 DanmakuView）
 *
 * 职责：
 *  - 动态创建 DanmakuView 并添加到指定容器
 *  - 暴露 loadDanmaku、syncTo、pause、resume、release、enable/disable 等 API
 *  - 为 PlayerActivity 提供生命周期对齐的弹幕控制
 *
 * 时间同步策略：
 *  - syncTo(positionMs)：正常播放时每秒调用，仅校准时间基准，不清空弹幕
 *  - seekTo(positionMs)：用户拖动进度条跳转时调用，清空并重建弹幕
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

    /**
     * 同步到播放器位置（毫秒）—— 播放过程中每秒调用
     * 仅校准时间基准，不清空活跃弹幕
     */
    fun syncTo(positionMs: Long) {
        val view = danmakuView ?: return
        if (!prepared) return
        lastSyncTimeMs = positionMs
        view.syncTo(positionMs, reset = false)
    }

    /**
     * 真正的 seek（用户拖动进度条跳转）
     * 清空活跃弹幕并重建
     */
    fun seekTo(positionMs: Long) {
        val view = danmakuView ?: return
        if (!prepared) return
        lastSyncTimeMs = positionMs
        view.syncTo(positionMs, reset = true)
    }

    fun pause() {
        Log.d(TAG, "pause")
        danmakuView?.setPaused(true)
    }

    fun resume() {
        if (!enabled) return
        danmakuView?.setPaused(false)
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
