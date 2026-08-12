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
 * 八戒影视（www.hfyqb.com）播放源
 *
 * 搜索页 URL 格式：/bjyssearch/{keyword}----------{page}---.html
 */
class BaJieVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("BJ", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_bajie"
    override val cachePrefix = "bajie"
    override val rateLimiterTag = "BJ"
    override val logTag = "BaJieVideoSource"

    // ========== 构建搜索 URL ==========

    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/bjyssearch/$encoded----------$safePage---.html"
    }

    // ========== 解析搜索结果页 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        // 1. 定位搜索结果项：每个 .reusltbox 包含一个视频条目
        val resultItems = doc.select(".reusltbox")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接
            val titleLink = item.select(".result_title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            var title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图：从 .img_wrapper 的 data-original 提取
            val imgWrapper = item.select(".img_wrapper").first()
            var coverUrl = imgWrapper?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                coverUrl = imgWrapper?.attr("src").orEmpty()
            }
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            // 评分：从 .reuslt_score 提取
            val rating = item.select(".reuslt_score").first()?.text()?.trim().orEmpty()

            // 年份和分类：从标题后的括号中提取
            var year = ""
            var category = ""
            val yearSpan = item.select(".result_title .hidden-mobile").first()
            if (yearSpan != null) {
                val text = yearSpan.text().trim()
                // 格式：(2026) 或 [国产剧]
                val yearMatch = Regex("\\((\\d{4})\\)").find(text)
                if (yearMatch != null) {
                    year = yearMatch.groupValues[1]
                }
                val categoryMatch = Regex("\\[(.+?)\\]").find(text)
                if (categoryMatch != null) {
                    category = categoryMatch.groupValues[1]
                }
            }

            // 提取详细信息：演员、导演、地区、类型、状态、更新
            var actors = ""
            var director = ""
            var area = ""
            var status = ""
            var description = ""

            val detailItems = item.select(".result_detail li")
            for (li in detailItems) {
                val text = li.text().trim()
                when {
                    text.contains("演员：") -> {
                        actors = text.replace("演员：", "").trim()
                            .replace(Regex("\\s+"), " ")
                    }
                    text.contains("导演：") -> {
                        director = text.replace("导演：", "").trim()
                            .replace(Regex("\\s+"), " ")
                    }
                    text.contains("地区：") -> {
                        area = text.replace("地区：", "").trim()
                    }
                    text.contains("类型：") -> {
                        // 如果分类还没从标题中提取，从类型中提取
                        if (category.isBlank()) {
                            category = text.replace("类型：", "").trim()
                        }
                    }
                    text.contains("状态：") && li.select("span").isNotEmpty() -> {
                        status = li.select("span").first()?.text()?.trim().orEmpty()
                    }
                }
            }

            // 简介：从 .reusltbox_info 提取
            val descElement = item.select(".reusltbox_info").first()
            if (descElement != null) {
                description = descElement.text().trim()
            }

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "year='$year', category='$category', rating='$rating', area='$area'"
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
                director = director,
                actors = actors,
                description = description,
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        val paginationLinks = doc.select(".pages a")
        Log.d(logTag, "分页元素数量: ${paginationLinks.size}")

        // 提取总页数：从尾页链接或页码数字中提取
        var totalPages = 1

        // 方式1：从尾页链接提取
        val lastLink = paginationLinks.find { it.text().trim() == "尾页" }
        if (lastLink != null) {
            val href = lastLink.attr("href")
            Regex("/bjyssearch/.*?----------(\\d+)---\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalPages = it
            }
        }

        // 方式2：从页码数字中取最大值
        if (totalPages <= 1) {
            val pageNums = paginationLinks.mapNotNull { a ->
                a.text().trim().toIntOrNull()
            }.filter { it > 0 }
            if (pageNums.isNotEmpty()) {
                totalPages = pageNums.maxOrNull() ?: 1
            }
        }

        // 判断是否有下一页/上一页
        val hasNext = paginationLinks.any { a ->
            val text = a.text().trim()
            text == "下一页" || text == "下页" || a.attr("href").contains("----------${page + 1}---.html")
        }

        val hasPrev = paginationLinks.any { a ->
            val text = a.text().trim()
            text == "上一页" || text == "上页" || a.attr("href").contains("----------${page - 1}---.html")
        } || (page > 1 && paginationLinks.isNotEmpty())

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

        // 1. 标题：从 h1 中提取
        val title = doc.select(".con_name h1 a span").first()?.text()?.trim()
            ?: doc.select(".con_name h1").first()?.text()?.trim()
            ?: doc.select("title").first()?.text()?.trim()?.replace(" - 八戒影视", "")?.trim()
            ?: ""

        // 2. 评分：从 .detail-rating-result 提取
        val rating = doc.select(".detail-rating-result").first()?.text()?.trim().orEmpty()

        // 3. 封面图：从 .leftimg .img_wrapper 的 data-original 提取
        var coverUrl = doc.select(".leftimg .img_wrapper").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".leftimg .img_wrapper").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        // 4. 提取详细信息
        var category = ""
        var year = ""
        var area = ""
        var director = ""
        var actors = ""
        var description = ""

        // 从 .yplx_c1 中提取类型、导演、状态、年份、地区等
        val infoItems = doc.select(".yplx_c1 i")
        for (item in infoItems) {
            val text = item.text().trim()
            when {
                text.contains("类型：") -> {
                    category = item.select("span a").joinToString("/") { it.text().trim() }
                        .ifBlank { text.replace("类型：", "").trim() }
                }
                text.contains("年份：") -> {
                    year = item.select("span").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("年份：", "").trim() }
                }
                text.contains("地区：") -> {
                    area = item.select("span").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("地区：", "").trim() }
                }
                text.contains("导演：") -> {
                    director = item.select("span a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("导演：", "").trim() }
                }
            }
        }

        // 主演：从 .zy p:contains(主演：) 提取
        val actorP = doc.select(".zy p:contains(主演：)").first()
        if (actorP != null) {
            actors = actorP.text().replace("主演：", "").trim()
                .replace(Regex("\\s+"), " ")
        } else {
            // 备用：从 .zy span 中提取
            val actorSpan = doc.select(".zy span").first()
            if (actorSpan != null) {
                actors = actorSpan.text().trim()
                    .replace(Regex("\\s+"), " ")
            }
        }

        // 简介：从 .yplx_c3 span 或 #content 提取
        val descSpan = doc.select(".yplx_c3 span").first()
        if (descSpan != null) {
            description = descSpan.text().trim()
        } else {
            description = doc.select("#content").first()?.text()?.trim().orEmpty()
        }

        // 5. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取线路名称：从 .con_c2_title .swiper-slide 提取
        val tabItems = doc.select(".con_c2_title .swiper-slide")
        Log.d(logTag, "找到 ${tabItems.size} 个线路标签")

        // 获取对应的集数列表
        val playlistContainers = doc.select(".tab-content")
        Log.d(logTag, "找到 ${playlistContainers.size} 个集数列表")

        tabItems.forEachIndexed { index, tab ->
            // 线路名称
            var lineName = tab.text().trim()
            if (lineName.isBlank()) return@forEachIndexed
            // 移除可能的分隔符
            lineName = lineName.replace("|", "").trim()
            if (lineName.isBlank()) return@forEachIndexed

            // 获取对应的集数列表容器
            val container = playlistContainers.getOrNull(index)
            if (container == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEachIndexed
            }

            // 提取集数链接
            val episodeLinks = container.select(".con_c2_list li a")
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

        // 兜底方案：如果通过线路匹配没找到，直接查找所有集数
        if (lines.isEmpty()) {
            val fallbackEpisodes = doc.select(".con_c2_list li a")
            if (fallbackEpisodes.isNotEmpty()) {
                val episodes = fallbackEpisodes.mapNotNull { a ->
                    val episodeTitle = a.text().trim()
                    val playPageUrl = a.attr("abs:href")
                    if (episodeTitle.isBlank() || playPageUrl.isBlank()) null
                    else PlayEpisode(episodeTitle, playPageUrl)
                }.distinctBy { it.playPageUrl }
                if (episodes.isNotEmpty()) {
                    Log.d(logTag, "✅ 兜底解析成功，共 ${episodes.size} 集")
                    lines.add(PlayLine("高清", episodes))
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