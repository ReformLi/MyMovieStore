package com.hpu.mymoviestore.data.source.impl

import android.util.Log
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import com.hpu.mymoviestore.data.model.CrawlError
import com.hpu.mymoviestore.data.model.CrawlErrorType
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.data.model.SearchPageResult
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.model.toCrawlError
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.hpu.mymoviestore.data.source.CrawlerVideoSource
import com.hpu.mymoviestore.data.source.RequestRateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * A38TV（www.******.com）播放源
 *
 * 搜索页 URL 格式：/search/{keyword}  （keyword 需 URL 编码）
 * 搜索页不分页，仅一页。
 */
class A38TvVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("A38", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_a38tv"
    override val cachePrefix = "a38tv"
    override val rateLimiterTag = "A38"
    override val logTag = "A38TvVideoSource"

    // ========== 构建搜索 URL ==========

    override fun buildSearchUrl(keyword: String, page: Int): String {
        // 该站点搜索不分页，page 参数忽略
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return "$baseUrl/search/$encoded"
    }

    // ========== 解析搜索结果页 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page (该站点不分页)")

        // 结果列表容器：ul.img-list
        val resultItems = doc.select("ul.img-list > li")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 详情页链接和标题
            val picLink = item.select("a.img-pic").first()
            val detailUrl = picLink?.attr("abs:href").orEmpty()
            var title = picLink?.attr("title")?.trim().orEmpty()

            // 如果 title 为空，从 h2 a 中取
            if (title.isBlank()) {
                title = item.select("h2.font16 a").first()?.text()?.trim().orEmpty()
            }

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图
            var coverUrl = picLink?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = picLink?.attr("src").orEmpty()
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            // 分类：第一个 .news-tips 中的 a 标签文本
            var category = ""
            val newsTips = item.select(".news-tips")
            if (newsTips.isNotEmpty()) {
                category = newsTips.first()?.select("a")?.first()?.text()?.trim().orEmpty()
            }

            // 演员/其他信息：第二个 .news-tips（如果有）
            var actors = ""
            if (newsTips.size > 1) {
                actors = newsTips[1].select("a").joinToString(" ") { it.text().trim() }
            }

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, category='$category'"
            )

            VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = title,
                coverUrl = coverUrl,
                playUrl = "",
                category = category,
                detailUrl = detailUrl,
                rating = "",
                year = "",
                area = "",
                director = "",
                actors = actors,
                description = "",
                sourceName = this.sourceName
            )
        }

        // 该站点搜索页不分页，固定返回第一页
        Log.d(logTag, "分页结果: totalPages=1, hasPrev=false, hasNext=false")

        return SearchPageResult(
            keyword = keyword,
            page = 1,
            totalPages = 1,
            hasPrev = false,
            hasNext = false,
            items = items
        )
    }

    // ========== 详情页解析 ==========

    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        Log.d(logTag, "========== parseVideoDetail 开始 ==========")
        Log.d(logTag, "解析 URL: $detailUrl")

        // 1. 标题：从 h1 提取
        val title = doc.select("h1").first()?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            Log.w(logTag, "标题为空，尝试从 title 标签提取")
        }

        // 2. 封面图：从 .vod-detail-thumb 的 data-original 提取
        var coverUrl = doc.select(".vod-detail-thumb").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".vod-detail-thumb").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        // 3. 从 ul.img-list li 中提取各类信息
        var director = ""
        var actors = ""
        var category = ""
        var status = ""
        var description = ""

        val infoItems = doc.select("ul.img-list > li")
        for (item in infoItems) {
            val text = item.text().trim()
            when {
                text.contains("主演：") -> {
                    actors = item.select("a").joinToString(" ") { it.text().trim() }
                    if (actors.isBlank()) {
                        actors = text.replace("主演：", "").trim()
                    }
                }
                text.contains("导演：") -> {
                    director = text.replace("导演：", "").trim()
                    // 如果导演字段有链接，优先用链接文本
                    val directorLinks = item.select("a")
                    if (directorLinks.isNotEmpty()) {
                        director = directorLinks.joinToString(" ") { it.text().trim() }
                    }
                }
                text.contains("状态：") -> {
                    status = text.replace("状态：", "").trim()
                }
                text.contains("类型：") -> {
                    category = item.select("a").joinToString("/") { it.text().trim() }
                    if (category.isBlank()) {
                        category = text.replace("类型：", "").trim()
                    }
                }
            }
        }

        // 4. 简介：从 p.txt-hidden.two 提取
        val descElement = doc.select("p.txt-hidden.two").first()
        if (descElement != null) {
            description = descElement.text().trim()
                .replace("简介：", "")
                .trim()
        }

        // 5. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 线路名称：.details-play-nav li a
        val lineNames = doc.select(".details-play-nav li a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // 集数列表：.play-list li a
        val episodeLinks = doc.select(".play-list li a")
        Log.d(logTag, "找到 ${episodeLinks.size} 个集数链接")

        if (lineNames.isNotEmpty() && episodeLinks.isNotEmpty()) {
            // 通常只有一个线路
            val lineName = lineNames.first()

            val episodes = episodeLinks.mapNotNull { a ->
                val episodeTitle = a.text().trim()
                val playPageUrl = a.attr("abs:href")
                if (episodeTitle.isBlank() || playPageUrl.isBlank()) null
                else PlayEpisode(episodeTitle, playPageUrl)
            }.distinctBy { it.playPageUrl }

            if (episodes.isNotEmpty()) {
                Log.d(logTag, "✅ 线路 '$lineName' 解析成功，共 ${episodes.size} 集")
                lines.add(PlayLine(lineName, episodes))
            }
        } else if (episodeLinks.isNotEmpty()) {
            // 兜底：没有线路名，直接解析集数
            val episodes = episodeLinks.mapNotNull { a ->
                val episodeTitle = a.text().trim()
                val playPageUrl = a.attr("abs:href")
                if (episodeTitle.isBlank() || playPageUrl.isBlank()) null
                else PlayEpisode(episodeTitle, playPageUrl)
            }.distinctBy { it.playPageUrl }

            if (episodes.isNotEmpty()) {
                Log.d(logTag, "✅ 兜底解析成功，共 ${episodes.size} 集")
                lines.add(PlayLine("默认线路", episodes))
            }
        }

        Log.d(logTag, "parseVideoDetail 完成: title='$title', lines=${lines.size}")

        return CrawlerVideoDetail(
            id = detailUrl.hashCode().toLong(),
            title = title,
            coverUrl = coverUrl,
            category = category,
            year = "",           // 该页面未直接显示年份，可从简介或 meta 中提取
            rating = "0.0",      // 该站点无评分
            director = director,
            actors = actors,
            description = description,
            detailUrl = detailUrl,
            playLines = lines,
            sourceName = this.sourceName
        )
    }

    // ========== 播放地址提取 ==========

    override fun extractRealVideoUrl(scriptContent: String): String? {
        Log.d(logTag, "========== extractRealVideoUrl 开始 ==========")

        // 优先：提取 encodedVideo 变量并 Base64 解码
        val encodedRegex = Regex("""encodedVideo\s*=\s*"([^"]+)"""")
        val encodedMatch = encodedRegex.find(scriptContent)
        if (encodedMatch != null) {
            val encoded = encodedMatch.groupValues[1]
            val decoded = try {
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(logTag, "Base64 解码失败: ${e.message}")
                null
            }
            if (!decoded.isNullOrBlank() && decoded.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 encodedVideo 解码到播放地址: $decoded")
                return decoded
            }
        }

        // 兜底1：player_aaaa 里的 url 字段（以防万一某些集数使用旧结构）
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        val match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1].replace("\\/", "/").trim()
            videoUrl = unescapeUnicode(videoUrl)
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (_: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 url 提取到播放地址: $videoUrl")
                return videoUrl
            }
        }

        // 兜底2：直接搜索 .m3u8
        val m3u8Regex = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*")
        val m3u8Match = m3u8Regex.find(scriptContent)
        if (m3u8Match != null) {
            Log.d(logTag, "✅ 从全局搜索提取到 m3u8: ${m3u8Match.value}")
            return m3u8Match.value
        }

        Log.e(logTag, "❌ 未能提取到播放地址")
        Log.d(logTag, "脚本片段预览: ${scriptContent.take(500)}")
        return null
    }

    /** 解码 Unicode 转义字符（备用） */
    private fun unescapeUnicode(input: String): String {
        val regex = Regex("\\\\u([0-9a-fA-F]{4})")
        return regex.replace(input) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            String(Character.toChars(codePoint))
        }
    }

    override suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(
        Dispatchers.IO) {
        try {
            if (playPageUrl.isBlank()) {
                return@withContext Result.failure(
                    CrawlError(CrawlErrorType.EMPTY_RESULT, sourceName, "播放页地址为空")
                )
            }

            val realUrlCacheKey = cacheKey("$cachePrefix:play:real_url", playPageUrl)
            cacheRepository?.get(realUrlCacheKey)?.let { cachedUrl ->
                if (cachedUrl.isNotBlank()) {
                    Log.d(logTag, "真实播放地址缓存命中: ${cachedUrl.take(120)}")
                    return@withContext Result.success(cachedUrl)
                }
            }

            Log.d(logTag, "开始请求播放页提取真实地址: $playPageUrl")
            val playDoc = requestDocument(playPageUrl, RequestRateLimiter.Priority.PLAY)

            // 该站点播放地址在包含 encodedVideo 的脚本中
            val scriptElement = playDoc.select("script:containsData(encodedVideo)").first()
                ?: playDoc.select("script:containsData(load-player)").first()

            if (scriptElement == null) {
                Log.e(logTag, "未找到包含 encodedVideo 的脚本")
                return@withContext Result.failure(
                    CrawlError(CrawlErrorType.PARSE_ERROR, sourceName, "未找到播放数据")
                )
            }

            val scriptContent = scriptElement.html()
            Log.d(logTag, "脚本片段: ${scriptContent.take(300)}")

            val videoUrl = extractRealVideoUrl(scriptContent)
            if (!videoUrl.isNullOrBlank()) {
                Log.d(logTag, "成功提取视频地址: $videoUrl")
                cacheRepository?.put(
                    realUrlCacheKey,
                    videoUrl,
                    ApiCacheEntity.TTL_THIRTY_MINUTES
                )
                Result.success(videoUrl)
            } else {
                Log.e(logTag, "未能提取视频地址")
                Result.failure(CrawlError(CrawlErrorType.PARSE_ERROR, sourceName, "未找到视频地址"))
            }
        } catch (e: Exception) {
            Log.e(logTag, "获取播放页真实地址失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = sourceName))
        }
    }
}