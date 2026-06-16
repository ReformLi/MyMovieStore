package com.hpu.mymoviestore.data.model

import com.squareup.moshi.Json

/**
 * 网页搜索结果分页模型。
 */
data class SearchPageResult(
    val keyword: String,
    val page: Int,
    val totalPages: Int,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val items: List<VideoItem>,
    /** 爬取过程中的错误（如有），UI 层可据此展示细粒度提示。不缓存。 */
    @Json(ignore = true)
    val error: CrawlError? = null
)
