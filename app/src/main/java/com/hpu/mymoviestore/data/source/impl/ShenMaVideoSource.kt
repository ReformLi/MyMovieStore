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

/**
 * 神马电影网（www.******.cc）播放源
 *
 * 搜索页 URL 格式：/vod-search-{keyword}----------{page}---.html
 */
class ShenMaVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("SM", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_shenma"
    override val cachePrefix = "shenma"
    override val rateLimiterTag = "SM"
    override val logTag = "ShenMaVideoSource"

    // ========== 构建搜索 URL ==========

    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/vod-search-$encoded----------$safePage---.html"
    }

    // ========== 解析搜索结果页 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        val resultItems = doc.select("ul.vodlist > li.vodlist-item")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            val titleLink = item.select(".vodlist-title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            val thumb = item.select(".vodlist-thumb").first()
            var coverUrl = thumb?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                coverUrl = thumb?.attr("src").orEmpty()
            }
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            Log.d(logTag, "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl")

            VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = title,
                coverUrl = coverUrl,
                playUrl = "",
                category = "",
                detailUrl = detailUrl,
                rating = "",
                year = "",
                area = "",
                director = "",
                actors = "",
                description = "",
                sourceName = this.sourceName
            )
        }

        // 分页解析
        val paginationLinks = doc.select("ul.page li a")
        var totalPages = 1
        val pageInfo = doc.select("ul.page li.visible-xs.active span.num").first()?.text()?.trim()
        if (pageInfo != null && pageInfo.contains("/")) {
            totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
        } else {
            val lastLink = paginationLinks.find { it.text().trim() == "尾页" }
            if (lastLink != null) {
                val href = lastLink.attr("href")
                Regex("/vod-search-.*?----------(\\d+)---\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    totalPages = it
                }
            }
            if (totalPages <= 1) {
                val pageNums = paginationLinks.mapNotNull { a ->
                    a.text().trim().toIntOrNull()
                }.filter { it > 0 }
                if (pageNums.isNotEmpty()) {
                    totalPages = pageNums.maxOrNull() ?: 1
                }
            }
        }

        val hasNext = paginationLinks.any { a ->
            val text = a.text().trim()
            text == "下一页" || text == "下页" || a.attr("href").contains("----------${page + 1}---.html")
        }

        val hasPrev = paginationLinks.any { a ->
            val text = a.text().trim()
            text == "上一页" || text == "上页" || a.attr("href").contains("----------${page - 1}---.html")
        } || (page > 1 && paginationLinks.isNotEmpty())

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

        val title = doc.select(".content-detail h1.title").first()?.text()?.trim()
            ?: doc.select("h1.title").first()?.text()?.trim()
            ?: ""

        var coverUrl = doc.select(".content-thumb .pic img").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".content-thumb .pic img").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        var category = ""
        var area = ""
        var year = ""
        var director = ""
        var actors = ""
        var description = ""

        val dataItems = doc.select(".content-detail p.data")
        for (item in dataItems) {
            val text = item.text().trim()
            when {
                text.contains("分类：") -> {
                    category = item.select("a").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("分类：", "").trim() }
                }
                text.contains("上映地区：") -> {
                    area = text.replace("上映地区：", "").trim()
                }
                text.contains("上映年份：") -> {
                    year = text.replace("上映年份：", "").trim()
                }
                text.contains("导演：") -> {
                    director = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("导演：", "").trim() }
                }
                text.contains("演员：") || text.contains("主要演员：") -> {
                    val actorText = item.select("a").joinToString(" ") { it.text().trim() }
                    if (actorText.isNotBlank()) {
                        actors = actorText
                    } else {
                        actors = text.replace("演员：", "").replace("主要演员：", "").trim()
                    }
                }
            }
        }

        if (actors.isBlank()) {
            val actorItem = doc.select(".content-detail p.data.visible-xs:contains(演员：)").first()
            if (actorItem != null) {
                actors = actorItem.select("a").joinToString(" ") { it.text().trim() }
                    .ifBlank { actorItem.text().replace("演员：", "").trim() }
            }
        }

        val descElement = doc.select("#desc .content-desc p").first()
        if (descElement != null) {
            description = descElement.text().trim()
        } else {
            description = doc.select(".content-desc p").first()?.text()?.trim().orEmpty()
        }

        val rating = ""

        // 播放线路和集数
        val lines = mutableListOf<PlayLine>()
        val tabItems = doc.select(".detail-tab li a")
        val tabPanes = doc.select(".detail-content .tab-pane")

        tabItems.forEachIndexed { index, tab ->
            var lineName = tab.text().trim()
            lineName = lineName.replace(Regex("\\(\\d+\\)"), "").trim()
            if (lineName.isBlank()) return@forEachIndexed

            val pane = tabPanes.getOrNull(index)
            if (pane == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEachIndexed
            }

            val episodeLinks = pane.select("li a")
            val episodes = episodeLinks.mapNotNull { a ->
                val episodeTitle = a.text().trim()
                val playPageUrl = a.attr("abs:href")
                if (episodeTitle.isBlank() || playPageUrl.isBlank()) null
                else PlayEpisode(episodeTitle, playPageUrl)
            }.distinctBy { it.playPageUrl }

            if (episodes.isNotEmpty()) {
                Log.d(logTag, "✅ 线路 '$lineName' 解析成功，共 ${episodes.size} 集")
                lines.add(PlayLine(lineName, episodes))
            } else {
                Log.w(logTag, "线路 '$lineName' 无有效集数")
            }
        }

        if (lines.isEmpty()) {
            val fallbackEpisodes = doc.select(".detail-play-list li a")
            if (fallbackEpisodes.isNotEmpty()) {
                val episodes = fallbackEpisodes.mapNotNull { a ->
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

        // 方法1：从 player_aaaa 对象中提取 "url" 字段
        // 匹配格式: "url":"https:\/\/...\/index.m3u8"
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        val match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")  // 将 \/ 转换为 /
                .trim()
            // 部分 URL 可能被编码，尝试解码
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (_: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 player_aaaa.url 提取到播放地址: $videoUrl")
                return videoUrl
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
}