// VideoRepository.kt (修正后)
package com.hpu.mymoviestore.data.repository

import android.util.Log
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.source.CrawlerVideoSource
import com.hpu.mymoviestore.data.source.VideoSourceManager

class VideoRepository(
    private val localSource: VideoSourceManager,
    private val crawlerSource: CrawlerVideoSource? = null,
    private val preferCrawler: Boolean = false
) {
    private val TAG = "VideoRepository"
    suspend fun getAllVideos(): List<VideoItem> {
        Log.d(TAG, "getAllVideos: preferCrawler=$preferCrawler, crawlerSource=${crawlerSource != null}")
        return if (preferCrawler && crawlerSource != null) {
            try {
                val result = crawlerSource.fetchHomepageVideos()
                Log.d(TAG, "爬虫结果: isSuccess=${result.isSuccess}, list size=${result.getOrNull()?.size}")
                if (result.isSuccess) {
                    val list = result.getOrNull()
                    if (!list.isNullOrEmpty()) {
                        Log.d(TAG, "使用爬虫数据，共 ${list.size} 条")
                        list
                    } else {
                        Log.d(TAG, "爬虫列表为空，回退本地")
                        localSource.loadAllVideos()
                    }
                } else {
                    Log.e(TAG, "爬虫失败，回退本地", result.exceptionOrNull())
                    localSource.loadAllVideos()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                localSource.loadAllVideos()
            }
        } else {
            Log.d(TAG, "使用本地数据源")
            localSource.loadAllVideos()
        }
    }

    suspend fun getVideosByCategory(category: String): List<VideoItem> {
        return localSource.loadVideosByCategory(category)
    }

    suspend fun searchVideos(keyword: String): List<VideoItem> {
        return localSource.searchVideos(keyword)
    }

    // 根据 ID 获取视频详情（优先从本地，本地没有则尝试爬虫）
    suspend fun getVideoById(id: Long): VideoItem? {
        val localVideo = localSource.getVideoById(id)
        if (localVideo != null && localVideo.playUrl.isNotBlank()) {
            return localVideo
        }

        // 如果本地没有有效的播放地址，且爬虫源可用
        if (preferCrawler && crawlerSource != null) {
            // 需要从 getAllVideos 中找到对应的详情页 URL（因为 VideoItem 没有 detailUrl 字段）
            // 临时方案：返回 null，让 UI 提示无法播放
            // 更好的方案：修改 VideoItem 增加 detailUrl 字段，或者在爬虫中维护一个 id -> detailUrl 的映射
            return null
        }
        return null
    }

    /**
     * 根据详情页 URL 获取完整视频信息（包含播放地址）
     * 这个方法供 DetailActivity 直接调用
     */
    suspend fun getVideoByDetailUrl(detailUrl: String): VideoItem? {
        if (!preferCrawler || crawlerSource == null) return null
        val playUrlResult = crawlerSource.fetchVideoUrl(detailUrl)
        if (playUrlResult.isSuccess) {
            val playUrl = playUrlResult.getOrNull() ?: return null
            // 这里可以返回一个临时的 VideoItem，只包含播放地址
            return VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = "",
                coverUrl = "",
                category = "",
                rating = "",
                year = "",
                area = "",
                director = "",
                actors = "",
                description = "",
                playUrl = playUrl
            )
        }
        return null
    }
}