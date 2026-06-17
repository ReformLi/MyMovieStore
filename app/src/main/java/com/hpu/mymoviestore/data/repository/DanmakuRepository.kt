package com.hpu.mymoviestore.data.repository

import android.util.Log
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.model.danmaku.DanmakuEpisode
import com.hpu.mymoviestore.data.source.DanmakuApi

/**
 * 弹幕仓库
 *
 * 数据流：
 *  1. searchCandidates(title)        → 返回匹配到的 DanmakuAnime 列表（供 Spinner 展示做源切换）
 *  2. fetchBangumi(anime.animeId)    → 获取某部番的 episodes 列表
 *  3. fetchDanmakuComments(...)      → 找到匹配集数并拉取弹幕列表（JSON 格式）
 *
 * 集数匹配策略：
 *  - 若外部传入 episodeNumber（如 "第3集"）→ 解析数字精确匹配 DanmakuEpisode.episodeNumber
 *  - 若外部未提供集数或无法解析 → 使用第 1 集（bangumi.episodes.firstOrNull()）
 */
class DanmakuRepository(private val api: DanmakuApi = DanmakuApi()) {

    companion object {
        private const val TAG = "DanmakuRepo"
    }

    /** 切换 Base URL（如从 "我的" 页面或设置中设置） */
    fun setBaseUrl(url: String) {
        api.setBaseUrl(url)
    }

    fun getBaseUrl(): String = api.getBaseUrl()

    /**
     * 搜索候选弹幕源（同步方法 —— 调用方需放到 IO 协程或线程中）
     */
    fun searchCandidates(title: String): List<DanmakuAnime> {
        if (title.isBlank()) return emptyList()
        return try {
            api.searchAnime(title)
        } catch (t: Throwable) {
            Log.w(TAG, "搜索弹幕源失败: ${t.message}")
            emptyList()
        }
    }

    /**
     * 拉取某部番的详情（含 episodes）
     */
    fun fetchBangumi(animeId: Long): DanmakuBangumi? {
        return try {
            api.getBangumi(animeId)
        } catch (t: Throwable) {
            Log.w(TAG, "获取 bangumi 失败: ${t.message}")
            null
        }
    }

    /**
     * 拉取某集的弹幕列表（JSON 格式）
     * @param preferredEpisodeNumber 当前正在播放的集数序号（如"第3集" → "3"）；无法确定传 null
     */
    fun fetchDanmakuComments(
        bangumi: DanmakuBangumi,
        preferredEpisodeNumber: String? = null
    ): List<DanmakuComment> {
        val episode = pickEpisode(bangumi, preferredEpisodeNumber)
        if (episode == null) {
            Log.w(TAG, "未找到匹配集数的 episode")
            return emptyList()
        }
        Log.d(
            TAG,
            "选中集数: episodeId=${episode.episodeId}, " +
                "number=${episode.episodeNumber}, title=${episode.episodeTitle}"
        )
        return try {
            api.getDanmakuComments(episode.episodeId)
        } catch (t: Throwable) {
            Log.w(TAG, "下载弹幕失败: ${t.message}")
            emptyList()
        }
    }

    /**
     * 选择一集弹幕的 episode：
     * - 优先匹配 preferredEpisodeNumber 数字
     * - 否则使用第一集（如果存在）
     */
    private fun pickEpisode(
        bangumi: DanmakuBangumi,
        preferredEpisodeNumber: String?
    ): DanmakuEpisode? {
        val eps = bangumi.episodes
        if (eps.isEmpty()) return null

        val preferred = preferredEpisodeNumber?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            // 从 preferred 中提取数字（如 "第3集" → "3"）
            val num = Regex("\\d+").find(preferred)?.value ?: preferred.trim()
            val matched = eps.firstOrNull { it.episodeNumber == num }
            if (matched != null) {
                Log.d(TAG, "按集数匹配到: episodeNumber=$num")
                return matched
            }
            // 也支持 episodeTitle 中包含相同数字的匹配
            val matchedByTitle = eps.firstOrNull { ep ->
                Regex("\\d+").find(ep.episodeTitle)?.value == num
            }
            if (matchedByTitle != null) {
                Log.d(TAG, "按标题匹配到: ${matchedByTitle.episodeTitle}")
                return matchedByTitle
            }
        }

        // 默认：第一集
        return eps.firstOrNull().also {
            Log.d(TAG, "使用默认集（第一集）: ${it?.episodeTitle}")
        }
    }
}
