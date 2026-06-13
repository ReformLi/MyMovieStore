package com.hpu.mymoviestore.data.model.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteVideoResponse(
    @Json(name = "class") val categories: List<RemoteCategory> = emptyList(),
    val list: List<RemoteVideo> = emptyList(),
    val page: Int = 1,
    val pagecount: Int = 1,
    val limit: Int = 20,
    val total: Int = 0
)
