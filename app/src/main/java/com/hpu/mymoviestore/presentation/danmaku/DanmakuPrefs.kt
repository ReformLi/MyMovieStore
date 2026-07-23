package com.hpu.mymoviestore.presentation.danmaku

import android.content.Context
import android.util.Log

/**
 * 弹幕偏好管理
 *
 * 持久化：
 *   1. 弹幕总开关（默认开启）—— ProfileFragment 中设置
 *   2. 每个视频选择的弹幕源 ID（以 videoId 为 key 存储，用于再次进入同一视频时自动使用）
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

    /** 保存指定视频选择的弹幕源 animeId */
    fun saveAnimeId(videoId: Long, animeId: Long) {
        if (videoId == 0L) {
            Log.w(TAG, "保存弹幕源跳过: videoId=$videoId == 0")
            return
        }
        Log.d(TAG, "保存弹幕源: videoId=$videoId, animeId=$animeId, key=danmaku_anime_$videoId")
        prefs.edit().putLong("danmaku_anime_$videoId", animeId).apply()
    }

    /** 读取指定视频保存的弹幕源 animeId，未保存返回 0 */
    fun getSavedAnimeId(videoId: Long): Long {
        if (videoId == 0L) {
            Log.w(TAG, "读取弹幕源跳过: videoId=$videoId == 0")
            return 0L
        }
        val result = prefs.getLong("danmaku_anime_$videoId", 0L)
        Log.d(TAG, "读取弹幕源: videoId=$videoId, key=danmaku_anime_$videoId → $result")
        return result
    }

    /** 清除所有弹幕源选择记录 */
    fun clearSavedAnimeChoices() {
        val all = prefs.all
        if (all.isNullOrEmpty()) return
        val keys = all.keys.filter { it.startsWith("danmaku_anime_") }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val TAG = "DanmakuPrefs"
        private const val PREFS_NAME = "danmu_prefs"
        private const val KEY_MASTER_ENABLED = "master_enabled"
    }
}
