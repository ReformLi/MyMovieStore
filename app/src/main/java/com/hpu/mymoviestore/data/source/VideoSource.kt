package com.hpu.mymoviestore.data.source

import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.SearchPageResult
import com.hpu.mymoviestore.data.model.VideoItem

/**
 * 视频播放源接口 —— 所有播放源必须实现此接口。
 */
interface VideoSource {
    /** 源的唯一标识 */
    val sourceId: String

    /** 源的显示名称（如"剧集屋"、"樱花动漫"），支持远程配置动态更新 */
    var sourceName: String

    /** 源是否启用 */
    var enabled: Boolean

    /** 搜索视频（分页） */
    suspend fun searchVideos(keyword: String, page: Int = 1): Result<SearchPageResult>

    /** 获取视频详情 */
    suspend fun fetchVideoDetail(detailUrl: String): Result<CrawlerVideoDetail>

    /** 从详情页获取首个播放页 URL */
    suspend fun fetchVideoUrl(detailUrl: String): Result<String>

    /** 从播放页解析真实播放地址 */
    suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String>
}
