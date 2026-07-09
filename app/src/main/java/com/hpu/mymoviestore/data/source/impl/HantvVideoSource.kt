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

class HantvVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("HTV", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_hantv"
    override val cachePrefix = "hantv"
    override val rateLimiterTag = "HTV"
    override val logTag = "HantvVideoSource"

    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        Log.d(logTag, "========== parseVideoDetail 开始 ==========")
        Log.d(logTag, "解析 URL: $detailUrl")
        Log.d(logTag, "文档标题: ${doc.title()}")

        // 1. 标题
        val title = doc.select(".hl-dc-title").first()?.text()?.trim()
            ?: doc.select(".hl-full-title").first()?.text()?.trim()
            ?: doc.select("h2.hl-dc-title").first()?.text()?.trim()
            ?: ""
        Log.d(logTag, "标题: $title")

        // 2. 封面图
        var coverUrl = doc.select(".hl-dc-pic .hl-item-thumb").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".hl-dc-pic .hl-item-thumb").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        Log.d(logTag, "封面: $coverUrl")

        // 3. 从详情信息列表中提取各字段
        // 详情信息在 .hl-vod-data 中，使用 .hl-data-xs 或 .hl-full-box 内的 li
        val infoItems = doc.select(".hl-vod-data .hl-full-box li, .hl-vod-data li")
        Log.d(logTag, "找到 ${infoItems.size} 个信息项")

        var category = ""
        var year = ""
        var area = ""
        var director = ""
        var actors = ""
        var description = ""
        var rating = ""

        for (item in infoItems) {
            val labelEm = item.select("em.hl-text-muted").first()
            if (labelEm == null) continue

            val label = labelEm.text().trim().replace("：", "").trim()
            val valueText = item.ownText().trim()
            val linkElements = item.select("a")

            when {
                label.contains("类型") -> {
                    category = linkElements.joinToString("/") { it.text().trim() }
                        .ifBlank { valueText }
                }
                label.contains("年份") -> {
                    year = valueText.ifBlank { linkElements.firstOrNull()?.text()?.trim().orEmpty() }
                }
                label.contains("地区") -> {
                    area = valueText.ifBlank { linkElements.firstOrNull()?.text()?.trim().orEmpty() }
                }
                label.contains("导演") -> {
                    director = linkElements.joinToString(" ") { it.text().trim() }
                        .ifBlank { valueText }
                }
                label.contains("主演") -> {
                    actors = linkElements.joinToString(" ") { it.text().trim() }
                        .ifBlank { valueText }
                }
            }
        }

        // 如果上面的方式没取到，尝试从 .hl-data-xs 中提取（移动端简洁版）
        if (category.isBlank() || year.isBlank() || area.isBlank()) {
            val dataXs = doc.select(".hl-data-xs").first()
            if (dataXs != null) {
                val xsText = dataXs.text()
                Log.d(logTag, "从 .hl-data-xs 提取: $xsText")
                // 格式示例: "2026 / 韩国 / 剧情 同性 / 2026-05-28上映 / 韩语"
                // 按 / 分隔
                val parts = xsText.split("/").map { it.trim() }
                if (parts.size >= 3) {
                    if (year.isBlank()) year = parts.getOrNull(0).orEmpty()
                    if (area.isBlank()) area = parts.getOrNull(1).orEmpty()
                    if (category.isBlank()) category = parts.getOrNull(2).orEmpty()
                }
            }
        }

        // 4. 简介
        val descElement = doc.select(".hl-content-text").first()
        if (descElement != null) {
            description = descElement.text().trim()
        } else {
            description = doc.select(".blurb").first()?.text()?.trim().orEmpty()
        }
        Log.d(logTag, "简介长度: ${description.length}")

        // 5. 评分
        val scoreElement = doc.select(".hl-text-conch.score").first()
        if (scoreElement != null) {
            rating = scoreElement.text().trim()
        } else {
            val scoreInSub = doc.select(".hl-item-sub .score").first()
            if (scoreInSub != null) {
                rating = scoreInSub.text().trim()
            }
        }
        Log.d(logTag, "评分: $rating")

        // 6. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取所有线路名称
        val tabBtns = doc.select(".hl-plays-from .hl-tabs-btn")
        Log.d(logTag, "找到 ${tabBtns.size} 个线路标签")

        // 获取所有线路对应的集数列表容器
        val tabBoxes = doc.select(".hl-tabs-box")
        Log.d(logTag, "找到 ${tabBoxes.size} 个集数列表")

        tabBtns.forEachIndexed { index, tab ->
            val lineName = tab.text().trim()
            if (lineName.isBlank()) return@forEachIndexed

            // 获取对应索引的集数列表
            val listContainer = tabBoxes.getOrNull(index)
            if (listContainer == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEachIndexed
            }

            // 提取集数链接，排除"展开全部"等按钮
            val episodeLinks = listContainer.select(".hl-plays-list li a").filter { a ->
                val text = a.text().trim()
                !text.contains("展开") && !text.contains("收起") && text.isNotBlank()
            }

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

        Log.d(logTag, "parseVideoDetail 完成，共 ${lines.size} 条线路")

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

    override fun extractRealVideoUrl(scriptContent: String): String? {
        Log.d(logTag, "========== extractRealVideoUrl 开始 ==========")

        // 1. 提取 url
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        var match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")
                .trim()
            // URL 解码
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (e: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 url 提取到地址: $videoUrl")
                return videoUrl
            }
        }

        // 2. 提取 url_next（备用）
        val urlNextRegex = Regex("\"url_next\"\\s*:\\s*\"([^\"]+)\"")
        match = urlNextRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")
                .trim()
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (e: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 url_next 提取到备用地址: $videoUrl")
                return videoUrl
            }
        }

        // 3. 全局搜索 .m3u8
        val m3u8Regex = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*")
        val m3u8Match = m3u8Regex.find(scriptContent)
        if (m3u8Match != null) {
            var videoUrl = m3u8Match.value.trim()
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (e: Exception) {
                videoUrl
            }
            Log.d(logTag, "✅ 从全局搜索提取到 m3u8: $videoUrl")
            return videoUrl
        }

        Log.e(logTag, "❌ 未能提取到播放地址")
        return null
    }

    /**
     * 构建热剧TV网的搜索URL
     * 格式：/vodsearch/{encodedKeyword}----------{page}---.html
     * 示例：/vodsearch/%E7%88%B1----------1---.html
     */
    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/vodsearch/$encodedKeyword----------$safePage---.html"
    }

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page, 文档标题=${doc.title()}")

        // 1. 解析搜索结果列表
        val resultItems = doc.select("ul.hl-one-list > li.hl-list-item")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接
            val titleLink = item.select(".hl-item-title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面
            val thumb = item.select(".hl-item-thumb").first()
            var coverUrl = thumb?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = thumb?.attr("src").orEmpty()
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            // 提取分类、年份、地区
            val subItems = item.select(".hl-item-sub").first()?.text()?.split("·")?.map { it.trim() } ?: emptyList()
            val category = subItems.getOrNull(0).orEmpty()
            val year = subItems.getOrNull(1).orEmpty()
            val area = subItems.getOrNull(2).orEmpty()

            // 提取导演/演员（从 .hl-item-sub.hl-text-muted.hl-lc-1）
            val castText = item.select(".hl-item-sub.hl-text-muted.hl-lc-1").first()?.text()?.trim().orEmpty()
            // 可能包含导演和演员，以 "/" 分隔，但实际是 "&nbsp;/&nbsp;" 分隔，Jsoup解析后得到空格，我们可以按 "/" 或空格分隔
            // 简单处理：取第一个作为导演，其余作为演员（但格式不统一，我们统一放到 actors 字段）
            val actors = castText
            // 也可以尝试分离导演，但该站没有明显标识，暂统一当演员

            // 简介
            val description = item.select(".hl-item-sub.hl-text-muted.hl-lc-2").first()?.text()?.trim().orEmpty()

            // 评分（可选）
            val rating = item.select(".hl-text-conch.score").first()?.text()?.trim().orEmpty()

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "category='$category', year='$year', area='$area', " +
                        "actors='${actors.take(40)}', descLength=${description.length}"
            )

            VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = title,
                coverUrl = coverUrl,
                playUrl = "",
                category = category,
                detailUrl = detailUrl,
                rating = rating,
                year = year,
                area = area,
                director = "", // 该页面未单独提供导演
                actors = actors,
                description = description,
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        val paginationLinks = doc.select("ul.hl-page-wrap li a")
        val currentPageText = doc.select("ul.hl-page-wrap li.hl-page-tips a").first()?.text()?.trim()
        // 格式 "1 / 46"，提取总页数
        val totalPages = currentPageText?.let {
            Regex("""(\d+)\s*/\s*(\d+)""").find(it)?.groupValues?.get(2)?.toIntOrNull()
        } ?: 1

        // 或从页码链接中提取最大页码
        val maxPageFromHref = paginationLinks.mapNotNull { a ->
            val href = a.attr("href")
            Regex("/vodsearch/.*?----------(\\d+)---\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()

        val finalTotalPages = listOfNotNull(totalPages, maxPageFromHref, page).maxOrNull()?.coerceAtLeast(1) ?: 1

        val hasNext = paginationLinks.any { a ->
            val text = a.text().trim()
            text.contains("下一页") || text.contains("下页") ||
                    a.attr("href").contains("----------${page + 1}---.html")
        }

        val hasPrev = paginationLinks.any { a ->
            val text = a.text().trim()
            text.contains("上一页") || text.contains("上页") ||
                    a.attr("href").contains("----------${page - 1}---.html")
        } || (page > 1 && paginationLinks.isNotEmpty())

        Log.d(
            logTag,
            "分页结果: totalPages=$finalTotalPages, hasPrev=$hasPrev, hasNext=$hasNext"
        )

        return SearchPageResult(
            keyword = keyword,
            page = page,
            totalPages = finalTotalPages,
            hasPrev = hasPrev,
            hasNext = hasNext,
            items = items
        )
    }
}
