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
 * 达达兔影视播放源
 *
 * 搜索页 URL 格式：/vodsearch/{keyword}----------{page}---.html
 */
class DadatuVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("DDT", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_dadatu"
    override val sourceName = "达达兔影视"
    override val baseUrl = "https://www.******.com"
    override val cachePrefix = "dadatu"
    override val rateLimiterTag = "DDT"
    override val logTag = "DadatuVideoSource"

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

        // 1. 定位搜索结果项：每个 .detail-wrap.row 包含一个视频条目
        val resultItems = doc.select(".detail-wrap.row")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 封面和详情链接
            val imgLink = item.select("a.detail-img-link").first()
            val detailUrl = imgLink?.attr("abs:href").orEmpty()

            // 标题：.media-title
            val title = item.select(".media-title").first()?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图：.detail-img 的 data-original
            val imgDiv = item.select(".detail-img").first()
            var coverUrl = imgDiv?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                coverUrl = imgDiv?.attr("src").orEmpty()
            }
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            // 解析详情信息：ul.desc li
            val descItems = item.select("ul.desc li")
            var year = ""
            var area = ""
            var actors = ""
            var description = ""

            descItems.forEachIndexed { idx, li ->
                val text = li.text().trim()
                when (idx) {
                    0 -> {
                        // 格式：2026 / 中国大陆 或 2026 / 日本
                        val parts = text.split("/").map { it.trim() }
                        if (parts.isNotEmpty()) year = parts[0]
                        if (parts.size >= 2) area = parts[1]
                    }
                    1 -> {
                        // 主演：格式 "主演：xxx"
                        actors = text.replace("主演：", "").trim()
                            .replace(Regex("\\s+"), " ")
                    }
                    2 -> {
                        // 简介：格式 "简介：xxx"
                        description = text.replace("简介：", "").trim()
                    }
                }
            }

            // 如果主演没取到，尝试从 .text-row-1 取（但注意 .text-row-1 也可能是标题行，需要排除）
            if (actors.isBlank()) {
                val actorLi = item.select("ul.desc li.text-row-1").first()
                if (actorLi != null && !actorLi.select(".hidden-xs").isEmpty()) {
                    // 如果 li.text-row-1 包含 .hidden-xs，说明是主演行
                    val actorText = actorLi.text().trim()
                    if (actorText.contains("主演：")) {
                        actors = actorText.replace("主演：", "").trim()
                    }
                }
            }

            // 分类：从详情页获取更准确，搜索页暂不提取
            val category = ""

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "year='$year', area='$area'"
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
                director = "",
                actors = actors,
                description = description,
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        var totalPages = 1
        var hasNext = false
        var hasPrev = false

        // 从 "1/445" 中提取总页数
        val pageInfo = doc.select(".ewave-page li.active .num").first()?.text()?.trim()
        if (pageInfo != null && pageInfo.contains("/")) {
            totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
        }

        // 判断是否有下一页
        val nextLink = doc.select(".ewave-page li a:contains(下一页)").first()
        hasNext = nextLink != null && !nextLink.attr("href").contains("javascript:")

        // 判断是否有上一页
        val prevLink = doc.select(".ewave-page li a:contains(上一页)").first()
        hasPrev = prevLink != null && !prevLink.attr("href").contains("javascript:")

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
        val title = doc.select(".detail-media h1.media-title").first()?.text()?.trim()
            ?: doc.select("h1.media-title").first()?.text()?.trim()
            ?: ""

        // 2. 封面图
        var coverUrl = doc.select(".detail-img img").first()?.attr("src").orEmpty()
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        // 3. 提取详细信息
        var category = ""
        var year = ""
        var area = ""
        var director = ""
        var actors = ""
        var description = ""
        var rating = ""

        // 遍历 ul.desc 中的 li
        val descItems = doc.select("ul.desc li")
        for (item in descItems) {
            val text = item.text().trim()
            when {
                // 类型：包含 "类型：" 图标
                item.select("i.fa-bars").isNotEmpty() -> {
                    category = item.select("span a").joinToString("/") { it.text().trim() }
                }
                // 年份：包含 "年份：" 图标
                item.select("i.fa-calendar").isNotEmpty() -> {
                    year = item.select("span").first()?.text()?.trim().orEmpty()
                }
                // 地区：包含 "地区：" 图标
                item.select("i.fa-map-marker").isNotEmpty() -> {
                    area = item.select("span").first()?.text()?.trim().orEmpty()
                }
                // 导演：包含 "导演：" 图标
                item.select("i.fa-user-o").isNotEmpty() && text.contains("导演") -> {
                    director = item.select("span a").joinToString(" ") { it.text().trim() }
                        .ifBlank { item.select("span").first()?.text()?.trim().orEmpty() }
                }
                // 主演：包含 "主演：" 图标
                item.select("i.fa-user-o").isNotEmpty() && text.contains("主演") -> {
                    actors = item.select("span a").joinToString(" ") { it.text().trim() }
                        .ifBlank { item.select("span").first()?.text()?.trim().orEmpty() }
                }
                // 豆瓣评分：包含 "豆瓣：" 图标
                item.select("i.fa-leaf").isNotEmpty() -> {
                    rating = text.replace("豆瓣：", "").replace("分", "").trim()
                }
                // 简介：包含 "简介：" 图标（桌面端）
                item.select("i.fa-file-text-o").isNotEmpty() -> {
                    // 提取简介文本，去掉 "简介：" 前缀
                    val descText = text.replace("简介：", "").trim()
                    if (descText.isNotBlank()) description = descText
                }
            }
        }

        // 如果简介为空，尝试从移动端的 .detail-intro-txt 提取
        if (description.isBlank()) {
            description = doc.select(".detail-intro-txt").first()?.text()?.trim().orEmpty()
        }

        // 4. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取线路名称
        val tabItems = doc.select(".player-from-box .swiper-slide")
        Log.d(logTag, "找到 ${tabItems.size} 个线路标签")

        // 获取集数列表容器
        val playlistContents = doc.select(".playlist-slide.ewave-tab-content")
        Log.d(logTag, "找到 ${playlistContents.size} 个集数列表")

        tabItems.forEachIndexed { index, tab ->
            val lineName = tab.text().trim()
            if (lineName.isBlank()) return@forEachIndexed

            // 获取对应索引的集数列表
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