package com.hpu.mymoviestore.data.download

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * HLS 加密信息（#EXT-X-KEY 解析结果）。
 *
 * @param method 加密方式，仅支持 "AES-128"（method 为 NONE 时不产生本对象）
 * @param keyUri 解密密钥的绝对 URL（AES-128 时非空）
 * @param iv 显式 IV（16 字节）；null 表示使用分片序号作为默认 IV（RFC 8216）
 */
data class HlsEncryption(
    val method: String,
    val keyUri: String? = null,
    val iv: ByteArray? = null
)

/**
 * M3U8 解析结果。
 *
 * @param segments ts 分片 URL 列表
 * @param encryption 加密信息；null = 未加密
 */
data class M3u8Playlist(
    val segments: List<String>,
    val encryption: HlsEncryption?
)

/**
 * 加密流不受支持时抛出的异常（携带面向用户的失败原因）。
 */
class HlsEncryptionException(message: String) : Exception(message)

/**
 * M3U8 解析器
 *
 * 功能：
 * - 解析 m3u8 文本，提取所有 ts 分片 URL
 * - 处理相对 URL（基于 m3u8 基础 URL）
 * - 处理多级 m3u8（master playlist -> media playlist）
 * - 解析 HLS 加密信息（AES-128 的 key URI 与 IV）
 * - 返回 M3u8Playlist（分片列表 + 加密信息）
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

        /** 加密标记（支持 AES-128 解密；SAMPLE-AES 等不支持） */
        private const val ENCRYPTION_TAG = "#EXT-X-KEY"

        /** fMP4/CMAF 的 init 段声明（当前引擎不支持，直接拦截） */
        private const val MAP_TAG = "#EXT-X-MAP"

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
     * 从 m3u8 URL 解析出所有 ts 分片 URL 列表及加密信息。
     *
     * @param m3u8Url m3u8 文件的完整 URL
     * @return 解析结果（分片 + 加密信息）；解析失败返回 null
     * @throws HlsEncryptionException 加密流不受支持（SAMPLE-AES / 多 key）
     */
    suspend fun parse(m3u8Url: String): M3u8Playlist? {
        return try {
            Log.d(TAG, "开始解析 m3u8: $m3u8Url")
            val content = fetchM3u8Content(m3u8Url)
            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
            val result = parseContent(content, baseUrl, m3u8Url)
            if (result != null) {
                Log.d(TAG, "解析完成，共 ${result.segments.size} 个 ts 分片")
            } else {
                Log.e(TAG, "解析 m3u8 失败，未找到有效分片")
            }
            result
        } catch (e: HlsEncryptionException) {
            // 加密方式不支持：向上抛出，让调用方给出明确失败原因
            Log.e(TAG, e.message ?: "加密流不受支持")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解析 m3u8 异常: ${e.message}", e)
            null
        }
    }

    /**
     * 从 m3u8 文本内容解析出所有 ts 分片 URL 及加密信息。
     *
     * @param content m3u8 文件文本内容
     * @param baseUrl m3u8 文件所在的基础 URL（用于拼接相对路径）
     * @param originalUrl 原始 m3u8 URL（用于多级解析时传递）
     * @return 解析结果（分片 + 加密信息）；解析失败返回 null
     * @throws HlsEncryptionException 加密流不受支持（SAMPLE-AES / 多 key）
     */
    suspend fun parseContent(content: String, baseUrl: String, originalUrl: String): M3u8Playlist? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // fMP4/CMAF 流：分片无容器头，init 段由 #EXT-X-MAP 单独声明，
        // 当前引擎未提取 init 段，拼接产物无法播放，直接拦截并给出明确原因
        if (lines.any { it.startsWith(MAP_TAG) }) {
            throw HlsEncryptionException("检测到 fMP4/CMAF 流（EXT-X-MAP），暂不支持下载")
        }

        // 解析加密信息（不支持的加密方式会抛 HlsEncryptionException）
        val encryption = parseEncryption(lines, baseUrl)
        if (encryption != null) {
            Log.w(TAG, "检测到加密 m3u8 流: $originalUrl (method=${encryption.method}, keyUri=${encryption.keyUri})")
        }

        // 判断是 master playlist 还是 media playlist
        val isMasterPlaylist = lines.any { it.startsWith(MASTER_TAG) }
        val isMediaPlaylist = lines.any { it.startsWith(MEDIA_PLAYLIST_TAG) }

        return when {
            isMasterPlaylist -> {
                Log.d(TAG, "检测到 Master Playlist，开始多级解析")
                // 直接返回子层完整结果（含子层 media playlist 的加密信息）
                parseMasterPlaylist(lines, baseUrl)
            }
            isMediaPlaylist -> {
                Log.d(TAG, "检测到 Media Playlist，直接提取 ts 分片")
                parseMediaPlaylist(lines, baseUrl)?.let { M3u8Playlist(it, encryption) }
            }
            else -> {
                // 尝试按 media playlist 解析（有些非标准 m3u8 可能没有 TARGETDURATION）
                Log.d(TAG, "未检测到标准标记，尝试按 Media Playlist 解析")
                parseMediaPlaylist(lines, baseUrl)?.let { M3u8Playlist(it, encryption) }
            }
        }
    }

    /**
     * 从 media playlist 行中解析 HLS 加密信息（#EXT-X-KEY）。
     *
     * 规则：
     * - 无 #EXT-X-KEY 或全部为 METHOD=NONE → 返回 null（未加密）
     * - 单条 METHOD=AES-128 → 返回 key URI + 显式 IV（缺失时用分片序号）
     * - SAMPLE-AES / 多个不同 key → 抛 HlsEncryptionException
     */
    private fun parseEncryption(lines: List<String>, baseUrl: String): HlsEncryption? {
        val keyLines = lines.filter { it.startsWith(ENCRYPTION_TAG) }
        if (keyLines.isEmpty()) return null

        // 收集所有非 NONE 的加密条目
        val encryptions = keyLines.mapNotNull { line ->
            val method = Regex("""METHOD=([^,\s]+)""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            if (method == "NONE") null else method to line
        }
        if (encryptions.isEmpty()) return null

        // 校验加密方式
        if (encryptions.any { it.first.contains("SAMPLE") }) {
            throw HlsEncryptionException("该源使用 SAMPLE-AES 加密，暂不支持下载")
        }
        if (encryptions.size > 1) {
            throw HlsEncryptionException("该源包含多个不同的加密密钥，暂不支持下载")
        }

        val (method, keyLine) = encryptions.first()
        if (method != "AES-128") {
            throw HlsEncryptionException("该源使用不支持的加密方式 $method，暂不支持下载")
        }

        // 解析 key URI（可能是相对路径）
        val keyUriRaw = Regex("""URI="([^"]+)"""").find(keyLine)?.groupValues?.get(1)
        val keyUri = keyUriRaw?.let { resolveUrl(it, baseUrl) }
        if (keyUri.isNullOrEmpty()) {
            throw HlsEncryptionException("无法解析加密密钥地址")
        }

        // 解析显式 IV（0x 开头的 32 位十六进制 = 16 字节；缺失时用分片序号）
        val ivHex = Regex("""IV=0x([0-9A-Fa-f]+)""").find(keyLine)?.groupValues?.get(1)
        val iv = ivHex?.takeIf { it.length == 32 }?.let {
            ByteArray(16) { i -> it.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        Log.d(TAG, "AES-128 加密解析成功: keyUri=$keyUri, explicitIv=${iv != null}")
        return HlsEncryption(method = "AES-128", keyUri = keyUri, iv = iv)
    }

    /**
     * 解析 Master Playlist，选择最高带宽的 media playlist 并递归解析。
     *
     * @return 子层 media playlist 的完整解析结果（含加密信息）；失败返回 null
     */
    private suspend fun parseMasterPlaylist(lines: List<String>, baseUrl: String): M3u8Playlist? {
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

        // 递归解析 media playlist，直接返回子层完整结果（含加密信息）
        return try {
            val mediaContent = fetchM3u8Content(bestMediaUrl)
            val mediaBaseUrl = bestMediaUrl.substringBeforeLast("/") + "/"
            parseContent(mediaContent, mediaBaseUrl, bestMediaUrl)
        } catch (e: HlsEncryptionException) {
            // 加密方式不支持：无需回退其他带宽（同源加密策略一致），直接上抛
            throw e
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
                    if (result != null && result.segments.isNotEmpty()) return result
                } catch (e2: HlsEncryptionException) {
                    throw e2
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
