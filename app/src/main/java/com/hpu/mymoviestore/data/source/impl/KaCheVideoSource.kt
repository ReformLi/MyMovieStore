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
 * （www.******.com）播放源
 *
 * 搜索页 URL 格式：/vodsearch/{keyword}----------{page}---.html
 * 详情页 URL 格式：/voddetail/{id}.html
 * 播放页 URL 格式：/vodplay/{id}-{sid}-{nid}.html
 *
 * 模板：苹果CMS stui 模板
 */
class KaCheVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("KC", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_kache"
    override val cachePrefix = "kache"
    override val rateLimiterTag = "KC"
    override val logTag = "KaCheVideoSource"

    // ========== 构建搜索 URL ==========

    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/vodsearch/$encoded----------$safePage---.html"
    }

    // ========== 解析搜索结果页 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        val resultItems = doc.select("ul.stui-vodlist__media > li")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            val titleLink = item.select(".detail .title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            val thumb = item.select(".thumb a").first()
            var coverUrl = thumb?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = thumb?.attr("src").orEmpty()
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            val director = item.select(".detail p:contains(导演：)").first()?.let { p ->
                p.text().replace("导演：", "").trim()
            }.orEmpty()

            val actors = item.select(".detail p:contains(主演：)").first()?.let { p ->
                p.text().replace("主演：", "").trim()
            }.orEmpty()

            var category = ""
            var area = ""
            var year = ""
            val infoP = item.select(".detail p.hidden-mi").first()
            if (infoP != null) {
                val text = infoP.text()
                category = Regex("类型：([^\\s]+)").find(text)?.groupValues?.get(1).orEmpty()
                area = Regex("地区：([^\\s]+)").find(text)?.groupValues?.get(1).orEmpty()
                year = Regex("年份：([^\\s]+)").find(text)?.groupValues?.get(1).orEmpty()
            }

            Log.d(logTag, "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                    "category='$category', year='$year', area='$area'")

            VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = title,
                coverUrl = coverUrl,
                playUrl = "",
                category = category,
                detailUrl = detailUrl,
                rating = "",
                year = year,
                area = area,
                director = director,
                actors = actors,
                description = "",
                sourceName = this.sourceName
            )
        }

        // 分页解析
        val paginationLinks = doc.select("ul.stui-page li a")
        var totalPages = 1

        val lastLink = paginationLinks.find { it.text().trim() == "尾页" }
        if (lastLink != null) {
            val href = lastLink.attr("href")
            Regex("/vodsearch/.*?----------(\\d+)---\\.html").find(href)
                ?.groupValues?.get(1)?.toIntOrNull()?.let { totalPages = it }
        }
        if (totalPages <= 1) {
            val pageInfo = doc.select("ul.stui-page li.visible-xs span.num").first()?.text()?.trim()
            if (!pageInfo.isNullOrBlank() && pageInfo.contains("/")) {
                totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
            }
        }
        if (totalPages <= 1) {
            val pageNums = paginationLinks.mapNotNull { a -> a.text().trim().toIntOrNull() }.filter { it > 0 }
            if (pageNums.isNotEmpty()) totalPages = pageNums.maxOrNull() ?: 1
        }

        val hasNext = paginationLinks.any { it.text().trim() == "下一页" || it.text().trim() == "下页" }
        val hasPrev = paginationLinks.any { it.text().trim() == "上一页" || it.text().trim() == "上页" } || (page > 1 && paginationLinks.isNotEmpty())

        Log.d(logTag, "分页结果: totalPages=$totalPages, hasPrev=$hasPrev, hasNext=$hasNext")

        return SearchPageResult(
            keyword = keyword,
            page = page,
            totalPages = totalPages,
            hasPrev = hasPrev,
            hasNext = hasNext,
            items = items
        )
    }

    // ========== 详情页解析 ==========

    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        Log.d(logTag, "========== parseVideoDetail 开始 ==========")
        Log.d(logTag, "解析 URL: $detailUrl")

        // 1. 标题
        val title = doc.select(".stui-content__detail h1.title").first()?.text()?.trim().orEmpty()

        // 2. 封面图
        var coverUrl = doc.select(".stui-content__thumb img").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".stui-content__thumb img").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        // 3. 评分
        val rating = doc.select(".score .branch").first()?.text()?.trim().orEmpty().ifBlank { "0.0" }

        // 4. 从 .data 中提取分类、地区、年份、主演、导演
        var category = ""
        var area = ""
        var year = ""
        var director = ""
        var actors = ""

        val dataItems = doc.select(".stui-content__detail p.data")
        for (item in dataItems) {
            val text = item.text().trim()
            when {
                text.contains("类型：") -> {
                    category = item.select("a").joinToString("/") { it.text().trim() }
                        .ifBlank { text.replace("类型：", "").trim() }
                }
                text.contains("地区：") -> {
                    area = item.select("a").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("地区：", "").trim() }
                }
                text.contains("年份：") -> {
                    year = item.select("a").first()?.text()?.trim().orEmpty()
                        .ifBlank { Regex("年份：(\\d{4})").find(text)?.groupValues?.get(1).orEmpty() }
                }
                text.contains("主演：") -> {
                    actors = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("主演：", "").trim() }
                }
                text.contains("导演：") -> {
                    director = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("导演：", "").trim() }
                }
            }
        }

        if (year.isBlank()) {
            val yearLine = doc.select(".stui-content__detail p.data:contains(年份：)").first()
            if (yearLine != null) year = yearLine.select("a").first()?.text()?.trim().orEmpty()
        }

        // 5. 简介
        var description = ""
        val fullDesc = doc.select(".detail .detail-content").first()
        if (fullDesc != null) description = fullDesc.text().trim()
        if (description.isBlank()) {
            description = doc.select(".detail .detail-sketch").first()?.text()?.trim().orEmpty()
        }

        // 6. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        val lineNames = doc.select(".stui-pannel__head .title")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("剧情介绍") && !it.contains("猜你喜欢") }
        Log.d(logTag, "找到 ${lineNames.size} 个线路名称: $lineNames")

        val episodeLinks = doc.select(".stui-content__playlist li a")
        Log.d(logTag, "找到 ${episodeLinks.size} 个集数链接")

        if (lineNames.isNotEmpty() && episodeLinks.isNotEmpty()) {
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
            year = year,
            rating = rating,
            director = director,
            actors = actors,
            description = description,
            detailUrl = detailUrl,
            playLines = lines,
            sourceName = this.sourceName
        )
    }

    // ========== 播放地址提取（覆盖基类，支持 player_aaaa 解析） ==========

    override suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(Dispatchers.IO) {
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

            // 该站点播放地址在包含 player_aaaa 的脚本中
            val scriptElement = playDoc.select("script:containsData(player_aaaa)").first()
                ?: playDoc.select("script:containsData(cms_play)").first()

            if (scriptElement == null) {
                Log.e(logTag, "未找到包含 player_aaaa 的脚本")
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

    override fun extractRealVideoUrl(scriptContent: String): String? {
        Log.d(logTag, "========== extractRealVideoUrl 开始 ==========")

        // 方法1：提取 player_aaaa 中的 "url"
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        val match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")
                .trim()
            videoUrl = unescapeUnicode(videoUrl)
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (_: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 player_aaaa.url 提取到播放地址: $videoUrl")
                return videoUrl
            } else {
                Log.w(logTag, "提取到的 url 不是有效的 m3u8: $videoUrl")
            }
        }

        // 方法2：兜底方案，直接搜索 .m3u8 链接
        val m3u8Regex = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*")
        val m3u8Match = m3u8Regex.find(scriptContent)
        if (m3u8Match != null) {
            var videoUrl = m3u8Match.value.trim()
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (_: Exception) {
                videoUrl
            }
            Log.d(logTag, "✅ 从全局搜索提取到 m3u8: $videoUrl")
            return videoUrl
        }

        Log.e(logTag, "❌ 未能提取到播放地址")
        Log.d(logTag, "脚本片段预览: ${scriptContent.take(500)}")
        return null
    }

    /** 解码 Unicode 转义字符 */
    private fun unescapeUnicode(input: String): String {
        val regex = Regex("\\\\u([0-9a-fA-F]{4})")
        return regex.replace(input) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            String(Character.toChars(codePoint))
        }
    }
}