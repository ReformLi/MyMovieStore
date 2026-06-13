package com.hpu.mymoviestore.data.model.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteVideo(
    @Json(name = "vod_id") val vodId: Long = 0,
    @Json(name = "vod_name") val vodName: String = "",
    @Json(name = "vod_pic") val vodPic: String = "",
    @Json(name = "vod_remarks") val vodRemarks: String = "",
    @Json(name = "vod_actor") val vodActor: String = "",
    @Json(name = "vod_director") val vodDirector: String = "",
    @Json(name = "vod_year") val vodYear: String = "",
    @Json(name = "vod_area") val vodArea: String = "",
    @Json(name = "vod_content") val vodContent: String = "",
    @Json(name = "vod_play_url") val vodPlayUrl: String = "",
    @Json(name = "type_name") val typeName: String = ""
)
