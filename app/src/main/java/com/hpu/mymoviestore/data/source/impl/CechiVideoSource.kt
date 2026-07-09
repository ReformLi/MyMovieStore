package com.hpu.mymoviestore.data.source.impl

import android.util.Log
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.data.model.SearchPageResult
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.hpu.mymoviestore.data.source.CrawlerVideoSource
import com.hpu.mymoviestore.data.source.RequestRateLimiter
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.net.URLEncoder

class CechiVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("CC", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_cechi"
    override val cachePrefix = "cechi"
    override val rateLimiterTag = "CC"
    override val logTag = "CechiVideoSource"

    // ========== 搜索 URL 构建 ==========

    /**
     * 构建策驰影视搜索 URL
     * 格式：/ccliusc/{encodedKeyword}----------{page}---.html
     * 示例：/ccliusc/%E7%88%B1----------1---.html
     */
    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/ccliusc/$encoded----------$safePage---.html"
    }

    // ========== 搜索结果解析 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        // 1. 解析结果列表
        val resultItems = doc.select(".search-box")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 详情链接和标题
            val titleLink = item.select(".public-list-exp").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = item.select(".thumb-txt").first()?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图
            val imgEl = item.select(".gen-movie-img").first()
            var coverUrl = imgEl?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                // 尝试从 style 背景中提取
                val style = imgEl?.attr("style").orEmpty()
                val bgMatch = Regex("url\\(([^)]+)\\)").find(style)
                coverUrl = bgMatch?.groupValues?.get(1).orEmpty()
            }
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
            else if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            // 提取年份、地区、类型（从 .thumb-else 中的 <a> 按顺序取）
            var year = ""
            var area = ""
            var category = ""
            val elseLinks = item.select(".thumb-else a")
            if (elseLinks.size >= 1) year = elseLinks[0].text().trim()
            if (elseLinks.size >= 2) area = elseLinks[1].text().trim()
            if (elseLinks.size >= 3) category = elseLinks[2].text().trim()
            // 如果类型有多个（可能多个分类链接），但这里只取第三个，可能有多个，但我们只取一个简单处理

            // 导演：从 .thumb-director 中提取
            val director = item.select(".thumb-director a")
                .joinToString(" ") { it.text().trim() }
                .ifBlank {
                    // 有时导演在 .thumb-director 文本中，但不包含 a 标签，直接取文本
                    item.select(".thumb-director").first()?.text()
                        ?.replace("导演：", "")
                        ?.trim()
                        .orEmpty()
                }

            // 主演：从 .thumb-actor 中提取（过滤空链接）
            val actorLinks = item.select(".thumb-actor a")
            val actors = actorLinks
                .mapNotNull { a ->
                    val text = a.text().trim()
                    if (text.isNotBlank()) text else null
                }
                .joinToString(" ")

            // 简介
            val description = item.select(".thumb-blurb").first()?.text()?.trim().orEmpty()

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "year='$year', area='$area', category='$category'"
            )

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
                description = description,
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        // 从 .page-tip 提取总页数
        var totalPages = 1
        val pageTip = doc.select(".page-tip").first()?.text()?.trim()
        if (pageTip != null) {
            // 格式：共9827条数据,当前1/983页
            val pageMatch = Regex("当前(\\d+)/(\\d+)页").find(pageTip)
            if (pageMatch != null) {
                totalPages = pageMatch.groupValues[2].toIntOrNull() ?: 1
            } else {
                // 尝试从尾页链接提取
                val lastLink = doc.select(".page-link:contains(尾页)").first()
                if (lastLink != null) {
                    val href = lastLink.attr("href")
                    Regex("/ccliusc/.*?----------(\\d+)---\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let {
                        totalPages = it
                    }
                }
            }
        }

        // 判断是否有下一页/上一页
        val nextLink = doc.select(".page-link:contains(下一页)").first()
        val hasNext = nextLink != null && !nextLink.attr("href").contains("javascript:")

        val prevLink = doc.select(".page-link:contains(上一页)").first()
        val hasPrev = prevLink != null && !prevLink.attr("href").contains("javascript:")

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

        // 1. 标题：优先使用 og:title，其次 slide-info-title
        val title = doc.select("meta[property=og:title]").first()?.attr("content")?.trim()
            ?: doc.select(".slide-info-title").first()?.text()?.trim()
            ?: doc.select("title").first()?.text()?.trim()?.replace(" - 策驰影视", "")?.trim()
            ?: ""

        // 2. 评分：从 .fraction 中提取
        var rating = doc.select(".fraction").first()?.text()?.trim().orEmpty()
            .replace("评分", "").trim()

        // 3. 封面图：优先从 .detail-pic img 的 data-src 提取，否则用 src
        var coverUrl = doc.select(".detail-pic img").first()?.attr("data-src").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".detail-pic img").first()?.attr("src").orEmpty()
        }
        // 处理相对路径
        if (coverUrl.isNotBlank()) {
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
            else if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        }
        Log.d(logTag, "封面图: $coverUrl")

        // 4. 基本信息：年份、地区、类型
        var category = ""
        var year = ""
        var area = ""
        val remarkLinks = doc.select(".slide-info .slide-info-remarks a")
        if (remarkLinks.size >= 1) year = remarkLinks[0].text().trim()
        if (remarkLinks.size >= 2) area = remarkLinks[1].text().trim()
        // 类型：从 .slide-info:contains(类型 :) 中提取 a 标签
        val typeDiv = doc.select(".slide-info:contains(类型 :)").first()
        if (typeDiv != null) {
            category = typeDiv.select("a").joinToString("/") { it.text().trim() }
        }

        // 5. 导演
        val directorDiv = doc.select(".slide-info:contains(导演 :)").first()
        val director = directorDiv?.text()?.replace("导演 :", "")?.trim().orEmpty()

        // 6. 主演
        val actorDiv = doc.select(".slide-info:contains(演员 :)").first()
        val actors = actorDiv?.text()?.replace("演员 :", "")?.trim()
            ?.replace(Regex("\\s+"), " ") ?: ""

        // 7. 简介：直接取 #height_limit 元素（注意选择器）
        var description = ""
        val descElement = doc.select("#height_limit").first()
        if (descElement != null) {
            description = descElement.text().trim()
                .replace(Regex("\\s+"), " ")
        }
        Log.d(logTag, "简介长度: ${description.length}")

        // 8. 播放线路和集数
        val lines = mutableListOf<PlayLine>()
        val tabItems = doc.select(".anthology-tab .swiper-slide")
        val listBoxes = doc.select(".anthology-list-box")
        tabItems.forEachIndexed { index, tab ->
            val lineName = tab.text().trim()
            if (lineName.isBlank()) return@forEachIndexed
            val listContainer = listBoxes.getOrNull(index) ?: return@forEachIndexed
            val episodeLinks = listContainer.select(".anthology-list-play li a")
            val episodes = episodeLinks.mapNotNull { a ->
                val episodeTitle = a.text().trim()
                val playPageUrl = a.attr("abs:href")
                if (episodeTitle.isBlank() || playPageUrl.isBlank()) null
                else PlayEpisode(episodeTitle, playPageUrl)
            }.distinctBy { it.playPageUrl }
            if (episodes.isNotEmpty()) {
                lines.add(PlayLine(lineName, episodes))
            }
        }

        Log.d(logTag, "parseVideoDetail 完成: title='$title', lines=${lines.size}")

        return CrawlerVideoDetail(
            id = detailUrl.hashCode().toLong(),
            title = title,
            coverUrl = coverUrl,
            category = category,
            year = year,
            rating = rating.ifBlank { "0.0" },
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

        // 方法1：提取 player_aaaa 中的 "url"
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        var match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")  // 反转义斜杠
                .trim()
            // URL 解码（防止百分号编码）
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (_: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 url 提取到播放地址: $videoUrl")
                return videoUrl
            } else {
                Log.w(logTag, "提取到的 url 不是有效的 m3u8: $videoUrl")
            }
        }

        // 方法2：尝试从 url_next 提取备用地址
        val urlNextRegex = Regex("\"url_next\"\\s*:\\s*\"([^\"]+)\"")
        match = urlNextRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")
                .trim()
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (_: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 url_next 提取到备用地址: $videoUrl")
                return videoUrl
            }
        }

        // 方法3：直接搜索 .m3u8 链接（兜底方案）
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
}