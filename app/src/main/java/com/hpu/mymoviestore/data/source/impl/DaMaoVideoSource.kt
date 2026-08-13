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
 * 大猫影院（www.******.com）播放源
 *
 * 搜索页 URL 格式：/vodsearch/{keyword}----------{page}---.html
 */
class DaMaoVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("DM", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_damao"
    override val cachePrefix = "damao"
    override val rateLimiterTag = "DM"
    override val logTag = "DaMaoVideoSource"

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

        // 1. 定位搜索结果项：每个 article.post-list.contt.blockimg 包含一个视频条目
        val resultItems = doc.select("article.post-list.contt.blockimg")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接
            val titleLink = item.select(".entry-title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图：从 a.block-fea 的 data-original 提取
            val feaLink = item.select("a.block-fea").first()
            var coverUrl = feaLink?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                coverUrl = feaLink?.attr("src").orEmpty()
            }
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            // 分类：从 .vodlist_top .voddate_year 提取
            val category = item.select(".vodlist_top .voddate_year").first()?.text()?.trim().orEmpty()

            // 演员：从 p:contains(演员：) 提取
            val actors = item.select(".entry-summary p:contains(演员：)").first()?.let { p ->
                p.text().replace("演员：", "").trim()
            }.orEmpty()

            // 导演：从 p:contains(导演：) 提取
            val director = item.select(".entry-summary p:contains(导演：)").first()?.let { p ->
                p.text().replace("导演：", "").trim()
            }.orEmpty()

            // 简介：从 .hidden-xs 提取
            val description = item.select(".entry-summary p.hidden-xs").first()?.text()?.trim().orEmpty()

            // 年份：页面无明确年份，留空
            val year = ""
            val area = ""

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "category='$category', actors='${actors.take(40)}'"
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
        var totalPages = 1
        var hasNext = false
        var hasPrev = false

        // 从分页信息中提取总页数
        // 方式1：从 "1/122" 中提取
        val pageInfo = doc.select("ol.page-navigator li.current a").first()?.text()?.trim()
        if (pageInfo != null && pageInfo.contains("/")) {
            totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
        }

        // 方式2：从尾页链接提取
        val paginationLinks = doc.select("ol.page-navigator li a")
        val lastLink = paginationLinks.find { it.text().trim().contains("122") || it.attr("href").contains("----------122---.html") }
        if (lastLink != null && totalPages <= 1) {
            val href = lastLink.attr("href")
            Regex("/vodsearch/.*?----------(\\d+)---\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalPages = it
            }
        }

        // 如果还找不到，从页码数字中取最大值
        if (totalPages <= 1) {
            val pageNums = paginationLinks.mapNotNull { a ->
                a.text().trim().toIntOrNull()
            }.filter { it > 0 }
            if (pageNums.isNotEmpty()) {
                totalPages = pageNums.maxOrNull() ?: 1
            }
        }

        // 判断是否有下一页
        val nextLink = paginationLinks.find { a ->
            val text = a.text().trim()
            text.contains("下一页") || text.contains("下页") || a.attr("href").contains("----------${page + 1}---.html")
        }
        hasNext = nextLink != null

        // 判断是否有上一页
        val prevLink = paginationLinks.find { a ->
            val text = a.text().trim()
            text.contains("上一页") || text.contains("上页") || a.attr("href").contains("----------${page - 1}---.html")
        }
        hasPrev = prevLink != null || (page > 1 && paginationLinks.isNotEmpty())

        Log.d(
            logTag,
            "分页结果: totalPages=$totalPages, hasPrev=$hasPrev, hasNext=$hasNext"
        )

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
        val title = doc.select(".detail-info-title").first()?.text()?.trim()
            ?: doc.select("h1.detail-info-title").first()?.text()?.trim()
            ?: ""

        // 2. 封面图
        var coverUrl = doc.select(".block-fea").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".block-fea").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        // 3. 分类：从 .vodlist_top .voddate_year 提取
        val category = doc.select(".vodlist_top .voddate_year").first()?.text()?.trim().orEmpty()

        // 4. 提取详细信息：演员、导演、类型、年份、地区、状态、简介
        var actors = ""
        var director = ""
        var type = ""
        var year = ""
        var area = ""
        var status = ""
        var description = ""
        var rating = ""

        // 从 .detail-info-text 中提取
        val infoText = doc.select(".detail-info-text")
        for (item in infoText) {
            // 演员
            val actorP = item.select("p:contains(演员：)").first()
            if (actorP != null) {
                actors = actorP.text().replace("演员：", "").trim()
            }

            // 导演
            val directorP = item.select("p:contains(导演：)").first()
            if (directorP != null) {
                director = directorP.text().replace("导演：", "").trim()
            }
        }

        // 从 .detail-info-text .row 中的 li 提取
        val infoItems = doc.select(".detail-info-text .row li")
        for (item in infoItems) {
            val text = item.text().trim()
            when {
                text.contains("类型：") -> {
                    type = item.select("a").joinToString("/") { it.text().trim() }
                        .ifBlank { text.replace("类型：", "").trim() }
                }
                text.contains("年份：") -> {
                    year = item.select("a").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("年份：", "").trim() }
                }
                text.contains("地区：") -> {
                    area = text.replace("地区：", "").trim()
                }
                text.contains("状态：") -> {
                    status = text.replace("状态：", "").trim()
                }
            }
        }

        // 评分：从 .ewave-star-num 提取
        val ratingElement = doc.select(".ewave-star-num.text-theme").first()
        if (ratingElement != null) {
            rating = ratingElement.text().trim()
        }

        // 简介：从 #desc .entry-content 提取
        val descElement = doc.select("#desc .entry-content").first()
        if (descElement != null) {
            description = descElement.text().trim()
        } else {
            description = doc.select(".show_text .entry-content").first()?.text()?.trim().orEmpty()
        }

        // 5. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取线路名称：从 .playlist-tab .swiper-slide 中提取，但排除非线路元素
        val tabItems = doc.select(".playlist-tab .swiper-slide")
        Log.d(logTag, "找到 ${tabItems.size} 个线路标签")

        // 获取集数列表容器：多个线路对应多个 #ewave-playlist-{id}
        val playlistContents = doc.select(".ewave-tab-content.ewave-playlist-content")
        Log.d(logTag, "找到 ${playlistContents.size} 个集数列表")

        tabItems.forEachIndexed { index, tab ->
            // 获取线路名称，过滤掉空文本
            val lineName = tab.text().trim()
            if (lineName.isBlank()) return@forEachIndexed

            // 获取对应索引的集数列表容器
            val listContainer = playlistContents.getOrNull(index)
            if (listContainer == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEachIndexed
            }

            // 提取集数链接
            val episodeLinks = listContainer.select(".ewave-playlist-item a")
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

        // 兜底方案：如果通过线路-列表匹配没找到，直接查找所有集数
        if (lines.isEmpty()) {
            val fallbackEpisodes = doc.select(".playlist .ewave-playlist-item a")
            if (fallbackEpisodes.isNotEmpty()) {
                val episodes = fallbackEpisodes.mapNotNull { a ->
                    val episodeTitle = a.text().trim()
                    val playPageUrl = a.attr("abs:href")
                    if (episodeTitle.isBlank() || playPageUrl.isBlank()) null
                    else PlayEpisode(episodeTitle, playPageUrl)
                }.distinctBy { it.playPageUrl }
                if (episodes.isNotEmpty()) {
                    Log.d(logTag, "✅ 兜底解析成功，共 ${episodes.size} 集")
                    lines.add(PlayLine("高清云播", episodes))
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