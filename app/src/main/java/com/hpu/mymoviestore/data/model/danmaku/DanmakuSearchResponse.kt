package com.hpu.mymoviestore.data.model.danmaku

/**
 * 弹幕搜索响应（example/danmu/弹幕搜索.json）
 *
 * 字段按样例精确匹配：
 *   errorCode      0 = 成功
 *   success        true / false
 *   errorMessage   错误信息
 *   animes         匹配到的番剧/影视列表（按 keyword 匹配）
 */
data class DanmakuSearchResponse(
    val errorCode: Int = -1,
    val success: Boolean = false,
    val errorMessage: String = "",
    val animes: List<DanmakuAnime> = emptyList()
)

/**
 * 搜索结果中的单条动漫/影视
 */
data class DanmakuAnime(
    val animeId: Long = 0,
    val bangumiId: String = "",
    val animeTitle: String = "",
    val type: String = "",
    val typeDescription: String = "",
    val imageUrl: String = "",
    val startDate: String = "",
    val episodeCount: Int = 0,
    val rating: Int = 0,
    val isFavorited: Boolean = false,
    val source: String = ""
)
