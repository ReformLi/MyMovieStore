package com.hpu.mymoviestore.data.model

/**
 * 豆瓣首页“电影”分栏分页结果。
 */
data class DoubanMoviePageResult(
    val type: String,
    val start: Int,
    val limit: Int,
    val total: Int,
    val items: List<VideoItem>
) {
    val nextStart: Int
        get() = start + items.size

    val hasMore: Boolean
        get() = nextStart < total
}
