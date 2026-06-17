package com.hpu.mymoviestore.data.model.danmaku

/**
 * 弹幕 bangumi 详情响应（example/danmu/弹幕.json）
 *
 * 字段按样例精确匹配：
 *   bangumi   番剧详情（含 episodes 列表）
 */
data class DanmakuBangumiResponse(
    val errorCode: Int = -1,
    val success: Boolean = false,
    val errorMessage: String = "",
    val bangumi: DanmakuBangumi? = null
)

data class DanmakuBangumi(
    val animeId: Long = 0,
    val bangumiId: String = "",
    val animeTitle: String = "",
    val imageUrl: String = "",
    val isOnAir: Boolean = false,
    val airDay: Int = 0,
    val isFavorited: Boolean = false,
    val rating: Int = 0,
    val type: String = "",
    val typeDescription: String = "",
    val seasons: List<DanmakuSeason> = emptyList(),
    val episodes: List<DanmakuEpisode> = emptyList()
)

data class DanmakuSeason(
    val id: String = "",
    val airDate: String = "",
    val name: String = "",
    val episodeCount: Int = 0
)

data class DanmakuEpisode(
    val seasonId: String = "",
    val episodeId: Long = 0,
    val episodeTitle: String = "",
    val episodeNumber: String = "",
    val airDate: String = ""
)
