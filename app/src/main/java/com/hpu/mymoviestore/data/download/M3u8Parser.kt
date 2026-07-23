package com.hpu.mymoviestore.data.download

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * M3U8 解析器
 *
 * 功能：
 * - 解析 m3u8 文本，提取所有 ts 分片 URL
 * - 处理相对 URL（基于 m3u8 基础 URL）
 * - 处理多级 m3u8（master playlist -> media playlist）
 * - 返回 List<String>（ts URL 列表）
 */
class M3u8Parser(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "M3u8Parser"

        /** Master playlist 标记行 */
        private const val MASTER_TAG = "#EXT-X-STREAM-INF"

        /** 媒体分片标记行 */
        private const val SEGMENT_TAG = "#EXTINF"

        /** 是否为 m3u8 文件（非 ts） */
        private const val MEDIA_PLAYLIST_TAG = "#EXT-X-TARGETDURATION"

        /** 加密标记（暂不支持加密流，仅做日志提示） */
        private const val ENCRYPTION_TAG = "#EXT-X-KEY"

        /** 广告区间标记 */
        private const val CUE_OUT_TAG = "#EXT-X-CUE-OUT"
        private const val CUE_IN_TAG = "#EXT-X-CUE-IN"

        /** 广告分片 URL 特征关键词 */
        private val AD_URL_PATTERNS = listOf(
            "/ad/", ".ad.", "adservice", "adserver",
            "/ad_", "_ad_", "/ads/", ".ads.",
            "advert", "ad-ts", "-ad.ts",
        )
    }

    /**
     * 从 m3u8 URL 解析出所有 ts 分片 URL 列表。
     *
     * @param m3u8Url m3u8 文件的完整 URL
     * @return ts 分片 URL 列表；解析失败返回 null
     */
    suspend fun parse(m3u8Url: String): List<String>? {
        return try {
            Log.d(TAG, "开始解析 m3u8: $m3u8Url")
            val content = fetchM3u8Content(m3u8Url)
            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
            val result = parseContent(content, baseUrl, m3u8Url)
            if (result != null) {
                Log.d(TAG, "解析完成，共 ${result.size} 个 ts 分片")
            } else {
                Log.e(TAG, "解析 m3u8 失败，未找到有效分片")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "解析 m3u8 异常: ${e.message}", e)
            null
        }
    }

    /**
     * 从 m3u8 文本内容解析出所有 ts 分片 URL。
     *
     * @param content m3u8 文件文本内容
     * @param baseUrl m3u8 文件所在的基础 URL（用于拼接相对路径）
     * @param originalUrl 原始 m3u8 URL（用于多级解析时传递）
     * @return ts 分片 URL 列表；解析失败返回 null
     */
    suspend fun parseContent(content: String, baseUrl: String, originalUrl: String): List<String>? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // 检查是否有加密标记
        if (lines.any { it.startsWith(ENCRYPTION_TAG) && !it.contains("METHOD=NONE") }) {
            Log.w(TAG, "检测到加密 m3u8 流，可能无法正常下载: $originalUrl")
        }

        // 判断是 master playlist 还是 media playlist
        val isMasterPlaylist = lines.any { it.startsWith(MASTER_TAG) }
        val isMediaPlaylist = lines.any { it.startsWith(MEDIA_PLAYLIST_TAG) }

        return when {
            isMasterPlaylist -> {
                Log.d(TAG, "检测到 Master Playlist，开始多级解析")
                parseMasterPlaylist(lines, baseUrl)
            }
            isMediaPlaylist -> {
                Log.d(TAG, "检测到 Media Playlist，直接提取 ts 分片")
                parseMediaPlaylist(lines, baseUrl)
            }
            else -> {
                // 尝试按 media playlist 解析（有些非标准 m3u8 可能没有 TARGETDURATION）
                Log.d(TAG, "未检测到标准标记，尝试按 Media Playlist 解析")
                parseMediaPlaylist(lines, baseUrl)
            }
        }
    }

    /**
     * 解析 Master Playlist，选择最高带宽的 media playlist 并递归解析。
     */
    private suspend fun parseMasterPlaylist(lines: List<String>, baseUrl: String): List<String>? {
        // 从 master playlist 中提取所有 media playlist URL
        val mediaPlaylistUrls = mutableListOf<Pair<Int, String>>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith(MASTER_TAG)) {
                // 解析带宽
                val bandwidth = parseBandwidth(line)
                // 下一行应该是 media playlist URL
                if (i + 1 < lines.size) {
                    val mediaUrl = resolveUrl(lines[i + 1], baseUrl)
                    mediaPlaylistUrls.add(Pair(bandwidth, mediaUrl))
                    i += 2
                    continue
                }
            }
            i++
        }

        if (mediaPlaylistUrls.isEmpty()) {
            Log.e(TAG, "Master Playlist 中未找到 media playlist URL")
            return null
        }

        // 按带宽降序排列，选择最高带宽
        mediaPlaylistUrls.sortByDescending { it.first }
        val bestMediaUrl = mediaPlaylistUrls.first().second
        Log.d(TAG, "选择最高带宽 media playlist (bandwidth=${mediaPlaylistUrls.first().first}): $bestMediaUrl")

        // 递归解析 media playlist
        return try {
            val mediaContent = fetchM3u8Content(bestMediaUrl)
            val mediaBaseUrl = bestMediaUrl.substringBeforeLast("/") + "/"
            parseContent(mediaContent, mediaBaseUrl, bestMediaUrl)
        } catch (e: Exception) {
            Log.e(TAG, "解析 media playlist 失败: ${e.message}", e)
            // 尝试其他带宽的 media playlist
            for (j in 1 until mediaPlaylistUrls.size) {
                try {
                    val fallbackUrl = mediaPlaylistUrls[j].second
                    Log.d(TAG, "回退到备选 media playlist (bandwidth=${mediaPlaylistUrls[j].first}): $fallbackUrl")
                    val fallbackContent = fetchM3u8Content(fallbackUrl)
                    val fallbackBaseUrl = fallbackUrl.substringBeforeLast("/") + "/"
                    val result = parseContent(fallbackContent, fallbackBaseUrl, fallbackUrl)
                    if (result != null && result.isNotEmpty()) return result
                } catch (e2: Exception) {
                    Log.w(TAG, "备选 media playlist 也失败: ${e2.message}")
                }
            }
            null
        }
    }

    /**
     * 解析 Media Playlist，提取所有 ts 分片 URL（跳过广告分片）。
     *
     * 广告检测策略：
     * 1. #EXT-X-CUE-OUT / #EXT-X-CUE-IN 区间内的分片全部跳过
     * 2. URL 包含广告关键词的分片跳过
     */
    private fun parseMediaPlaylist(lines: List<String>, baseUrl: String): List<String>? {
        val segments = mutableListOf<String>()
        var inCueOut = false
        var skippedAdCount = 0

        for (i in lines.indices) {
            val line = lines[i]
            when {
                line.startsWith(CUE_OUT_TAG) -> {
                    inCueOut = true
                    Log.d(TAG, "检测到 #EXT-X-CUE-OUT，进入广告区间")
                }
                line.startsWith(CUE_IN_TAG) -> {
                    inCueOut = false
                    Log.d(TAG, "检测到 #EXT-X-CUE-IN，退出广告区间，跳过 $skippedAdCount 个广告分片")
                    skippedAdCount = 0
                }
                line.startsWith(SEGMENT_TAG) -> {
                    if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1]
                        if (nextLine.startsWith("#") || nextLine.isBlank()) continue
                        val segmentUrl = resolveUrl(nextLine, baseUrl)

                        if (inCueOut || isAdSegmentUrl(segmentUrl)) {
                            skippedAdCount++
                            continue
                        }

                        segments.add(segmentUrl)
                    }
                }
            }
        }

        if (skippedAdCount > 0) {
            Log.d(TAG, "广告过滤完成: 保留 ${segments.size} 个分片, 跳过 $skippedAdCount 个广告分片")
        }
        return if (segments.isNotEmpty()) segments else null
    }

    /**
     * 检查分片 URL 是否为广告（基于关键词匹配）。
     */
    private fun isAdSegmentUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_URL_PATTERNS.any { pattern -> lower.contains(pattern) }
    }

    /**
     * 从 #EXT-X-STREAM-INF 行中解析带宽值。
     * 格式示例：#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
     */
    private fun parseBandwidth(line: String): Int {
        val regex = Regex("""BANDWIDTH=(\d+)""")
        return regex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * 解析相对 URL 为绝对 URL。
     *
     * - 如果已经是绝对 URL（以 http:// 或 https:// 开头），直接返回
     * - 否则基于 baseUrl 拼接
     */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        return try {
            val baseUri = URI(baseUrl)
            val resolved = baseUri.resolve(url)
            resolved.toString()
        } catch (e: Exception) {
            Log.w(TAG, "URL 解析失败: base=$baseUrl, relative=$url, error=${e.message}")
            // 降级：简单字符串拼接
            baseUrl + url
        }
    }

    /**
     * 通过 OkHttp 获取 m3u8 文件内容。
     */
    private suspend fun fetchM3u8Content(url: String): String {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .build()

            val call = okHttpClient.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        if (!response.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.failure(
                                        java.io.IOException("HTTP ${response.code}: ${response.message}")
                                    )
                                )
                            }
                            return
                        }
                        val body = response.body?.string()
                        if (body != null && continuation.isActive) {
                            continuation.resumeWith(Result.success(body))
                        } else if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(java.io.IOException("响应体为空"))
                            )
                        }
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }
}
