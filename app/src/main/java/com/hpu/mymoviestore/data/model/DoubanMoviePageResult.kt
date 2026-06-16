package com.hpu.mymoviestore.data.model

import com.squareup.moshi.Json

/**
 * 豆瓣首页"电影"分栏分页结果。
 */
data class DoubanMoviePageResult(
    val type: String,
    val start: Int,
    val limit: Int,
    val total: Int,
    val items: List<VideoItem>,
    /** 爬取过程中的错误（如有），UI 层可据此展示细粒度提示。不缓存。 */
    @Json(ignore = true)
    val error: CrawlError? = null
) {
    val nextStart: Int
        get() = start + items.size

    val hasMore: Boolean
        get() = nextStart < total
}
