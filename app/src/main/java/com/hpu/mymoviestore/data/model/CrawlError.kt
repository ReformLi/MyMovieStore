package com.hpu.mymoviestore.data.model

import java.io.IOException
import java.net.UnknownHostException

/**
 * 统一的爬取错误分类模型。
 *
 * 将底层 [Throwable] 映射为面向用户的错误类型和可读提示文案，
 * 供 ViewModel / UI 层直接展示。
 */
enum class CrawlErrorType(val userMessage: String) {

    /** DNS 解析失败，设备可能无网络或域名不可达 */
    DNS_FAILURE("网络连接失败，请检查网络设置"),

    /** HTTP 403 被目标服务器拒绝（可能触发反爬） */
    FORBIDDEN("访问被拒绝（403），请稍后重试"),

    /** HTTP 4xx 客户端错误 */
    CLIENT_ERROR("请求错误，请稍后重试"),

    /** HTTP 5xx 服务端错误 */
    SERVER_ERROR("服务器异常，请稍后重试"),

    /** 目标页面包含验证码（如豆瓣 403 页面含 captcha） */
    CAPTCHA("需要验证码，请稍后在浏览器中访问后重试"),

    /** 请求超时 */
    TIMEOUT("请求超时，请检查网络后重试"),

    /** 网络连接断开或不可达 */
    NETWORK_ERROR("网络连接失败，请检查网络设置"),

    /** 响应解析失败（JSON/HTML 格式不符预期） */
    PARSE_ERROR("数据解析失败，请稍后重试"),

    /** 请求被限流器取消 */
    RATE_LIMITED("请求过于频繁，请稍后重试"),

    /** 空结果（服务器返回成功但无有效数据） */
    EMPTY_RESULT("暂无数据"),

    /** 未知错误 */
    UNKNOWN("加载失败，请稍后重试")
}

/**
 * 封装爬取过程中的错误信息。
 *
 * 继承 [Exception] 以兼容 Kotlin [Result] 类型（`Result.failure()` 要求 [Throwable]）。
 *
 * @param type 错误分类
 * @param source 错误来源标识（如 "豆瓣"、"剧集屋"）
 * @param detail 技术细节（用于日志，不展示给用户）
 * @param cause 原始异常
 */
class CrawlError(
    val type: CrawlErrorType,
    val source: String,
    val detail: String = "",
    cause: Throwable? = null
) : Exception("[$source] ${type.userMessage}: $detail", cause) {
    /** 面向用户的完整提示文案，包含来源前缀 */
    val userFacingMessage: String
        get() = "[$source] ${type.userMessage}"
}

/**
 * 从原始异常推断 [CrawlError]。
 *
 * @param source 错误来源标识
 * @param responseBody 可选的 HTTP 响应体（用于检测验证码等）
 * @param statusCode 可选的 HTTP 状态码
 */
fun Throwable.toCrawlError(
    source: String,
    responseBody: String? = null,
    statusCode: Int? = null
): CrawlError {
    // 1) DNS / 网络层
    if (this is UnknownHostException) {
        return CrawlError(CrawlErrorType.DNS_FAILURE, source, message ?: "DNS 解析失败", this)
    }
    if (this is java.net.SocketTimeoutException || this is java.net.ConnectException) {
        return CrawlError(CrawlErrorType.TIMEOUT, source, message ?: "连接超时", this)
    }
    if (this is IOException && this !is java.net.SocketException) {
        return CrawlError(CrawlErrorType.NETWORK_ERROR, source, message ?: "网络异常", this)
    }
    if (this is java.net.SocketException) {
        return CrawlError(CrawlErrorType.NETWORK_ERROR, source, message ?: "网络连接异常", this)
    }

    // 2) 限流器取消
    if (this is kotlinx.coroutines.CancellationException && message?.contains("抢占") == true) {
        return CrawlError(CrawlErrorType.RATE_LIMITED, source, message ?: "被限流取消", this)
    }

    // 3) HTTP 状态码
    val code = statusCode
    if (code != null) {
        if (code == 403) {
            // 检测验证码
            val body = responseBody.orEmpty()
            if (body.contains("captcha", ignoreCase = true) ||
                body.contains("验证码", ignoreCase = true)
            ) {
                return CrawlError(CrawlErrorType.CAPTCHA, source, "HTTP 403 + 验证码页面", this)
            }
            return CrawlError(CrawlErrorType.FORBIDDEN, source, "HTTP $code", this)
        }
        if (code in 400..499) {
            return CrawlError(CrawlErrorType.CLIENT_ERROR, source, "HTTP $code: ${message.orEmpty()}", this)
        }
        if (code in 500..599) {
            return CrawlError(CrawlErrorType.SERVER_ERROR, source, "HTTP $code: ${message.orEmpty()}", this)
        }
    }

    // 4) 响应体检测验证码（即使状态码不是 403）
    val body = responseBody.orEmpty()
    if (body.contains("captcha", ignoreCase = true) || body.contains("验证码", ignoreCase = true)) {
        return CrawlError(CrawlErrorType.CAPTCHA, source, "响应体包含验证码", this)
    }

    // 5) 解析错误
    if (this is org.json.JSONException ||
        this is com.squareup.moshi.JsonDataException ||
        message?.contains("parse", ignoreCase = true) == true ||
        message?.contains("解析", ignoreCase = true) == true
    ) {
        return CrawlError(CrawlErrorType.PARSE_ERROR, source, message ?: "数据解析失败", this)
    }

    // 6) 兜底
    return CrawlError(CrawlErrorType.UNKNOWN, source, "${this::class.java.simpleName}: ${message.orEmpty()}", this)
}
