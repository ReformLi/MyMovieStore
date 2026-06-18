package com.hpu.mymoviestore.data.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.model.danmaku.DanmakuEpisode
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * 弹幕数据缓存管理器
 *
 * 缓存策略：
 *  - 搜索缓存：按 keyword 缓存搜索结果（DanmakuAnime 列表）
 *  - 分集缓存：按 animeId 缓存 bangumi 详情（含 episodes）
 *  - 集弹幕缓存：按 episodeId 缓存弹幕列表（DanmakuComment 列表）
 *  - 统一过期时间：1天（24小时）
 *  - 关键规则：第一次获取任何一集弹幕时，搜索+分集+该集弹幕的缓存时间统一
 *    后续播放其他集时，搜索和分集缓存跟随已有缓存的剩余时间
 *
 * 缓存键：
 *  - 搜索: "search_{keyword}"
 *  - 分集: "bangumi_{animeId}"
 *  - 弹幕: "comments_{episodeId}"
 */
class DanmakuCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // 类型适配器
    private val animeListType = Types.newParameterizedType(List::class.java, DanmakuAnime::class.java)
    private val animeListAdapter = moshi.adapter<List<DanmakuAnime>>(animeListType)
    private val bangumiAdapter = moshi.adapter(DanmakuBangumi::class.java)
    private val commentListType = Types.newParameterizedType(List::class.java, DanmakuComment::class.java)
    private val commentListAdapter = moshi.adapter<List<DanmakuComment>>(commentListType)

    // ================== 搜索缓存 ==================

    fun getSearchCache(keyword: String): List<DanmakuAnime>? {
        val key = "search_$keyword"
        if (isExpired(key)) return null
        val json = prefs.getString(key, null) ?: return null
        return try {
            animeListAdapter.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "搜索缓存解析失败: ${e.message}")
            null
        }
    }

    fun putSearchCache(keyword: String, animes: List<DanmakuAnime>, expireAt: Long = calculateExpireAt()) {
        val key = "search_$keyword"
        val json = animeListAdapter.toJson(animes)
        prefs.edit().putString(key, json).putLong("${key}_expire", expireAt).apply()
        Log.d(TAG, "搜索缓存已保存: keyword=$keyword, expireAt=${formatTime(expireAt)}")
    }

    // ================== 分集缓存 ==================

    fun getBangumiCache(animeId: Long): DanmakuBangumi? {
        val key = "bangumi_$animeId"
        if (isExpired(key)) return null
        val json = prefs.getString(key, null) ?: return null
        return try {
            bangumiAdapter.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "分集缓存解析失败: ${e.message}")
            null
        }
    }

    fun putBangumiCache(animeId: Long, bangumi: DanmakuBangumi, expireAt: Long = calculateExpireAt()) {
        val key = "bangumi_$animeId"
        val json = bangumiAdapter.toJson(bangumi)
        prefs.edit().putString(key, json).putLong("${key}_expire", expireAt).apply()
        Log.d(TAG, "分集缓存已保存: animeId=$animeId, expireAt=${formatTime(expireAt)}")
    }

    // ================== 集弹幕缓存 ==================

    fun getCommentsCache(episodeId: Long): List<DanmakuComment>? {
        val key = "comments_$episodeId"
        if (isExpired(key)) return null
        val json = prefs.getString(key, null) ?: return null
        return try {
            commentListAdapter.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "弹幕缓存解析失败: ${e.message}")
            null
        }
    }

    fun putCommentsCache(episodeId: Long, comments: List<DanmakuComment>, expireAt: Long = calculateExpireAt()) {
        val key = "comments_$episodeId"
        val json = commentListAdapter.toJson(comments)
        prefs.edit().putString(key, json).putLong("${key}_expire", expireAt).apply()
        Log.d(TAG, "弹幕缓存已保存: episodeId=$episodeId, count=${comments.size}, expireAt=${formatTime(expireAt)}")
    }

    // ================== 统一缓存时间 ==================

    /**
     * 获取当前缓存的统一过期时间。
     * 如果已有缓存（搜索或分集或任意集弹幕），返回其剩余过期时间；
     * 否则返回新的 1 天后过期时间。
     */
    fun getUnifiedExpireAt(keyword: String, animeId: Long): Long {
        val now = System.currentTimeMillis()
        val keys = listOf(
            "search_$keyword",
            "bangumi_$animeId"
        )
        // 也检查任意已缓存的集弹幕
        val allKeys = prefs.all.keys.filter { it.endsWith("_expire") }
        val candidateKeys = keys + allKeys

        var maxExpireAt = -1L
        for (key in candidateKeys) {
            val expireAt = prefs.getLong("${key}_expire", -1)
            if (expireAt > now && expireAt > maxExpireAt) {
                maxExpireAt = expireAt
            }
        }
        return if (maxExpireAt > now) {
            Log.d(TAG, "使用已有缓存过期时间: ${formatTime(maxExpireAt)}")
            maxExpireAt
        } else {
            calculateExpireAt()
        }
    }

    // ================== 过期检查 ==================

    private fun isExpired(key: String): Boolean {
        val expireAt = prefs.getLong("${key}_expire", -1)
        return expireAt <= 0 || System.currentTimeMillis() > expireAt
    }

    private fun calculateExpireAt(): Long {
        return System.currentTimeMillis() + CACHE_DURATION_MS
    }

    // ================== 清理 ==================

    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "所有弹幕缓存已清除")
    }

    private fun formatTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    companion object {
        private const val TAG = "DanmakuCache"
        private const val PREF_NAME = "danmaku_cache"
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L  // 1天
    }
}
