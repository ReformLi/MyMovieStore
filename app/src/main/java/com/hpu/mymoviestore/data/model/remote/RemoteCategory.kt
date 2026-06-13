package com.hpu.mymoviestore.data.model.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteCategory(
    @Json(name = "type_id") val typeId: String,
    @Json(name = "type_name") val typeName: String
)
