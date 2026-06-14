package com.hpu.mymoviestore.data.model

/**
 * 爬虫详情页模型。
 *
 * 由详情页 HTML 解析得到，用于详情页展示影片元数据、播放线路和集数。
 */
data class CrawlerVideoDetail(
    val id: Long,
    val title: String,
    val coverUrl: String,
    val category: String,
    val year: String,
    val rating: String,
    val director: String,
    val actors: String,
    val description: String,
    val detailUrl: String,
    val playLines: List<PlayLine> = emptyList()
)

/**
 * 播放线路，例如「高清播放」「超清播放」。
 */
data class PlayLine(
    val name: String,
    val episodes: List<PlayEpisode> = emptyList()
)

/**
 * 某条线路下的一集或一个播放入口。
 */
data class PlayEpisode(
    val title: String,
    val playPageUrl: String
)
