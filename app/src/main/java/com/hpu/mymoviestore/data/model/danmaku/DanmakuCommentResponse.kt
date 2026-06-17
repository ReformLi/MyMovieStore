package com.hpu.mymoviestore.data.model.danmaku

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 弹幕 comment API 响应（JSON 格式）
 *
 * 格式：
 * {
 *   "count": 20001,
 *   "comments": [
 *     {"cid": 1, "p": "0.00,1,16777215,...", "text": "弹幕文本"},
 *     ...
 *   ]
 * }
 */
@JsonClass(generateAdapter = true)
data class DanmakuCommentResponse(
    @Json(name = "count") val count: Int = 0,
    @Json(name = "comments") val comments: List<DanmakuComment> = emptyList()
)

/**
 * 单条弹幕
 *
 * p 字段格式（逗号分隔）：
 * - 0: 出现时间（秒）
 * - 1: 类型（1/2/3=滚动，4=底部，5=顶部，6=逆向，7=定位，8=高级）
 * - 2: 字号（18/25/36）
 * - 3: 颜色（十进制整数）
 * - 4: 发送时间戳
 * - 5: 弹幕池（0/1/2）
 * - 6: 用户哈希
 * - 7: 弹幕ID
 *
 * m 字段：弹幕文本内容
 */
@JsonClass(generateAdapter = true)
data class DanmakuComment(
    @Json(name = "cid") val cid: Long = 0,
    @Json(name = "p") val p: String = "",
    @Json(name = "m") val m: String = "",  // 弹幕文本
    @Json(name = "t") val t: Int = 0,
    @Json(name = "like") val like: Int = 0
)