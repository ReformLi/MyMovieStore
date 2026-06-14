package com.hpu.mymoviestore.data.model

/**
 * 网页搜索结果分页模型。
 */
data class SearchPageResult(
    val keyword: String,
    val page: Int,
    val totalPages: Int,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val items: List<VideoItem>
)
