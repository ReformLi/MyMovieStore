package com.hpu.mymoviestore.data.repository

import android.content.Context
import android.util.Log
import com.hpu.mymoviestore.data.cache.DanmakuCache
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.model.danmaku.DanmakuEpisode
import com.hpu.mymoviestore.data.source.DanmakuApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 弹幕仓库（带缓存和重试）
 *
 * 数据流：
 *  1. searchCandidates(title)        → 返回匹配到的 DanmakuAnime 列表
 *  2. fetchBangumi(anime.animeId)    → 获取某部番的 episodes 列表
 *  3. fetchDanmakuComments(...)      → 找到匹配集数并拉取弹幕列表
 *
 * 缓存策略：
 *  - 搜索、分集、集弹幕统一缓存 1 天
 *  - 第一次获取时统一设置过期时间
 *  - 后续获取其他集时，搜索和分集缓存跟随已有缓存的剩余时间
 *
 * 重试策略：
 *  - 网络失败时自动重试，最多 5 次
 *  - 每次重试间隔 1 分钟（60 秒）
 *  - 成功/最终失败时通过回调通知 UI
 */
class DanmakuRepository(
    private val api: DanmakuApi = DanmakuApi(),
    context: Context? = null
) {

    private val cache: DanmakuCache? = context?.let { DanmakuCache(it) }

    companion object {
        private const val TAG = "DanmakuRepo"
        private const val MAX_RETRY = 10
        private const val RETRY_INTERVAL_MS = 10_000L  // 1分钟
    }

    /** 切换 Base URL */
    fun setBaseUrl(url: String) {
        api.setBaseUrl(url)
    }

    fun getBaseUrl(): String = api.getBaseUrl()

    // ================== 搜索（带缓存和重试） ==================

    /**
     * 搜索候选弹幕源
     * @param onResult 结果回调 (success, data, fromCache)
     */
    suspend fun searchCandidates(
        title: String,
        onResult: ((Boolean, List<DanmakuAnime>, Boolean) -> Unit)? = null
    ): List<DanmakuAnime> {
        if (title.isBlank()) {
            onResult?.invoke(true, emptyList(), false)
            return emptyList()
        }

        // 先读缓存
        cache?.getSearchCache(title)?.let {
            Log.d(TAG, "搜索缓存命中: $title, ${it.size} 条")
            onResult?.invoke(true, it, true)
            return it
        }

        // 网络请求（带重试）
        val result = withContext(Dispatchers.IO) {
            retryWithBackoff(
                operation = { api.searchAnime(title) },
                onRetry = { attempt, e ->
                    Log.w(TAG, "搜索失败，第 $attempt 次重试", e)
                }
            )
        }

        val success = result != null
        val data = result ?: emptyList()
        val fromCache = false

        if (success && data.isNotEmpty()) {
            val expireAt = cache?.getUnifiedExpireAt(title, 0L) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
            cache?.putSearchCache(title, data, expireAt)
        }

        onResult?.invoke(success, data, fromCache)
        return data
    }

    // ================== 分集（带缓存和重试） ==================

    /**
     * 拉取某部番的详情（含 episodes）
     */
    suspend fun fetchBangumi(
        animeId: Long,
        keyword: String = "",
        onResult: ((Boolean, DanmakuBangumi?, Boolean) -> Unit)? = null
    ): DanmakuBangumi? {
        // 先读缓存
        cache?.getBangumiCache(animeId)?.let {
            Log.d(TAG, "分集缓存命中: animeId=$animeId")
            onResult?.invoke(true, it, true)
            return it
        }

        // 网络请求（带重试）
        val result = withContext(Dispatchers.IO) {
            retryWithBackoff(
                operation = { api.getBangumi(animeId) },
                onRetry = { attempt, e ->
                    Log.w(TAG, "获取 bangumi 失败，第 $attempt 次重试", e)
                }
            )
        }

        val success = result != null
        val fromCache = false

        if (success && result != null) {
            val expireAt = cache?.getUnifiedExpireAt(keyword, animeId) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
            cache?.putBangumiCache(animeId, result, expireAt)
        }

        onResult?.invoke(success, result, fromCache)
        return result
    }

    // ================== 集弹幕（带缓存和重试） ==================

    /**
     * 拉取某集的弹幕列表
     * @param preferredEpisodeNumber 当前正在播放的集数序号（如"第3集" → "3"）
     */
    suspend fun fetchDanmakuComments(
        bangumi: DanmakuBangumi,
        preferredEpisodeNumber: String? = null,
        keyword: String = "",
        onResult: ((Boolean, List<DanmakuComment>, Boolean) -> Unit)? = null
    ): List<DanmakuComment> {
        val episode = pickEpisode(bangumi, preferredEpisodeNumber)
        if (episode == null) {
            Log.w(TAG, "未找到匹配集数的 episode")
            onResult?.invoke(false, emptyList(), false)
            return emptyList()
        }

        Log.d(TAG, "选中集数: episodeId=${episode.episodeId}, number=${episode.episodeNumber}")

        // 先读缓存
        cache?.getCommentsCache(episode.episodeId)?.let {
            Log.d(TAG, "弹幕缓存命中: episodeId=${episode.episodeId}, ${it.size} 条")
            onResult?.invoke(true, it, true)
            return it
        }

        // 网络请求（带重试）
        val result = withContext(Dispatchers.IO) {
            retryWithBackoff(
                operation = { api.getDanmakuComments(episode.episodeId) },
                onRetry = { attempt, e ->
                    Log.w(TAG, "下载弹幕失败，第 $attempt 次重试", e)
                }
            )
        }

        val success = result != null
        val data = result ?: emptyList()
        val fromCache = false

        if (success) {
            val expireAt = cache?.getUnifiedExpireAt(keyword, bangumi.animeId)
                ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
            cache?.putCommentsCache(episode.episodeId, data, expireAt)
        }

        onResult?.invoke(success, data, fromCache)
        return data
    }

    // ================== 重试机制 ==================

    /**
     * 带退避重试的执行器
     * @param operation 要执行的网络操作
     * @param onRetry 每次重试前的回调 (attempt, exception)
     * @return 成功返回结果，全部重试失败后返回 null
     */
    private suspend fun <T> retryWithBackoff(
        operation: suspend () -> T,
        onRetry: ((Int, Throwable) -> Unit)? = null
    ): T? {
        var lastException: Throwable? = null
        for (attempt in 1..MAX_RETRY) {
            try {
                return operation()
            } catch (e: Throwable) {
                lastException = e
                if (attempt < MAX_RETRY) {
                    onRetry?.invoke(attempt, e)
                    delay(RETRY_INTERVAL_MS)
                }
            }
        }
        Log.e(TAG, "操作失败，已重试 $MAX_RETRY 次", lastException)
        return null
    }

    // ================== 集数匹配 ==================

    private fun pickEpisode(
        bangumi: DanmakuBangumi,
        preferredEpisodeNumber: String?
    ): DanmakuEpisode? {
        val eps = bangumi.episodes
        if (eps.isEmpty()) return null

        val preferred = preferredEpisodeNumber?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            val num = Regex("\\d+").find(preferred)?.value ?: preferred.trim()
            val matched = eps.firstOrNull { it.episodeNumber == num }
            if (matched != null) {
                Log.d(TAG, "按集数匹配到: episodeNumber=$num")
                return matched
            }
            val matchedByTitle = eps.firstOrNull { ep ->
                Regex("\\d+").find(ep.episodeTitle)?.value == num
            }
            if (matchedByTitle != null) {
                Log.d(TAG, "按标题匹配到: ${matchedByTitle.episodeTitle}")
                return matchedByTitle
            }
        }
        return eps.firstOrNull().also {
            Log.d(TAG, "使用默认集（第一集）: ${it?.episodeTitle}")
        }
    }
}
