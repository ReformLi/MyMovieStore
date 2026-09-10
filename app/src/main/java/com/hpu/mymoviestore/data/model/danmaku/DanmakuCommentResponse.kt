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
 * p 字段格式（逗号分隔）—— 本项目服务端 danmu_api / 弹弹play v2 兼容：
 * - 0: 出现时间（秒）
 * - 1: 类型（1=滚动，4=底部，5=顶部；2/3/6 归滚动，7/8 其它）
 * - 2: 颜色（十进制 RGB，16777215=白色）
 * - 3: 来源平台标签（如 [imgo]），非数字
 *
 * 例："1920.54,1,16777215,[imgo]"
 *
 * 解析层（DanmakuView.parseComment）会按 index3 是否为纯数字自动兼容 B 站标准
 * 8 段格式 time,mode,字号,color,...（此时颜色取 index3、字号取 index2）。
 *
 * m 字段：弹幕文本内容
 */
@JsonClass(generateAdapter = true)
data class DanmakuComment(
    @Json(name = "cid") val cid: Long = 0,
    @Json(name = "p") val p: String = "",
    @Json(name = "m") val m: String = "",  // 弹幕文本
    // 服务端偶发返回小数秒（如 0.1），Int 会因 Moshi 严格类型校验导致整个弹幕 JSON 解析失败；
    // 本字段不参与渲染（渲染用 p 字段第一位），Double 兼容整数/小数两种返回
    @Json(name = "t") val t: Double = 0.0,
    @Json(name = "like") val like: Int = 0
)