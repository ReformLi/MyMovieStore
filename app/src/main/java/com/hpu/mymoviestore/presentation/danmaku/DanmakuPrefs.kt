package com.hpu.mymoviestore.presentation.danmaku

import android.content.Context

/**
 * 弹幕偏好管理
 *
 * 持久化两项：
 *   1. 弹幕总开关（默认开启）—— ProfileFragment 中设置
 *   2. 当前播放会话中选中的弹幕源 ID（用于再次进入同一片源时自动切换）
 *
 * 注意：子开关（播放页 Switch）不持久化 —— 每次进入播放页默认跟随总开关。
 */
class DanmakuPrefs(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 弹幕总开关（默认 true） */
    fun isMasterEnabled(): Boolean = prefs.getBoolean(KEY_MASTER_ENABLED, true)

    fun setMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "danmu_prefs"
        private const val KEY_MASTER_ENABLED = "master_enabled"
    }
}
