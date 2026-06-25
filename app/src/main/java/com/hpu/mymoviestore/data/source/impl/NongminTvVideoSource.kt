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
 * 农民TV播放源
 *
 * 搜索页 URL 格式：/vodsearch/{keyword}----------{page}---.html
 */
class NongminTvVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("NMTV", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_nongmin_tv"
    override val sourceName = "农民TV"
    override val baseUrl = "https://www.******.com"
    override val cachePrefix = "nongmin_tv"
    override val rateLimiterTag = "NMTV"
    override val logTag = "NongminTvVideoSource"

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

        // 1. 定位搜索结果项：每个 li.active 包含一个视频条目
        val resultItems = doc.select("ul.stui-vodlist__media > li.active")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接
            val titleLink = item.select(".detail h3.title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图
            val thumb = item.select(".thumb .v-thumb").first()
            var coverUrl = thumb?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = thumb?.attr("src").orEmpty()
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            // 导演：从 <p><span class="text-muted">导演：</span>内详</p> 中提取
            val director = item.select(".detail p:contains(导演：)").first()?.let { p ->
                p.text().replace("导演：", "").trim()
            }.orEmpty()

            // 主演：从 <p><span class="text-muted">主演：</span>...</p> 提取
            val actors = item.select(".detail p:contains(主演：)").first()?.let { p ->
                p.text().replace("主演：", "").trim()
            }.orEmpty()

            // 类型、地区、年份：从 .hidden-mi 提取
            var category = ""
            var area = ""
            var year = ""
            val infoP = item.select(".detail p.hidden-mi").first()
            if (infoP != null) {
                val text = infoP.text()
                // 格式：类型：日韩动漫  地区：日本  年份：2026
                val typeRegex = Regex("类型：([^\\s]+)")
                val areaRegex = Regex("地区：([^\\s]+)")
                val yearRegex = Regex("年份：([^\\s]+)")
                category = typeRegex.find(text)?.groupValues?.get(1).orEmpty()
                area = areaRegex.find(text)?.groupValues?.get(1).orEmpty()
                year = yearRegex.find(text)?.groupValues?.get(1).orEmpty()
            }

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "category='$category', year='$year', area='$area'"
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
                description = "",
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        val paginationLinks = doc.select("ul.stui-page li a")
        Log.d(logTag, "分页元素数量: ${paginationLinks.size}")

        // 提取总页数：从 "1/433" 中提取
        var totalPages = 1
        val pageInfo = doc.select("ul.stui-page li.active.visible-xs span.num").first()?.text()?.trim()
        if (pageInfo != null && pageInfo.contains("/")) {
            totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
        } else {
            // 备用：从尾页链接提取
            val lastLink = paginationLinks.find { it.text().trim() == "尾页" }
            if (lastLink != null) {
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

        // 1. 标题：从 h1.title 提取，包含评分标签
        val title = doc.select(".stui-content__detail h1.title").first()?.let { element ->
            // 移除评分标签 <span class="score">，只保留纯文本标题
            element.clone().apply { select(".score").remove() }.text().trim()
        } ?: ""

        // 2. 评分：从 h1.title .score 提取
        val rating = doc.select(".stui-content__detail h1.title .score").first()?.text()?.trim().orEmpty()

        // 3. 封面图：从 .stui-content__thumb img 的 data-original 提取
        var coverUrl = doc.select(".stui-content__thumb img").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".stui-content__thumb img").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
        else if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

        // 4. 从 p.data 中提取分类、地区、年份
        var category = ""
        var area = ""
        var year = ""
        val dataItems = doc.select(".stui-content__detail p.data")
        for (item in dataItems) {
            val text = item.text()
            when {
                text.contains("类型：") -> {
                    category = item.select("a").joinToString("/") { it.text().trim() }
                }
                text.contains("地区：") -> {
                    area = item.select("a").first()?.text()?.trim().orEmpty()
                }
                text.contains("年份：") -> {
                    year = item.select("a").first()?.text()?.trim().orEmpty()
                }
            }
        }

        // 5. 导演：从 p.data 中导演行提取
        val director = doc.select(".stui-content__detail p.data:contains(导演：) a")
            .joinToString(" ") { it.text().trim() }

        // 6. 主演：从 p.data 中主演行提取
        val actors = doc.select(".stui-content__detail p.data:contains(主演：) a")
            .joinToString(" ") { it.text().trim() }

        // 7. 简介：从 .desc 或 #desc .col-pd 提取
        var description = doc.select(".desc").first()?.text()?.trim().orEmpty()
        if (description.isBlank()) {
            description = doc.select("#desc .col-pd").first()?.text()?.trim().orEmpty()
        }

        // 8. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 更灵活地查找播放列表容器
        val playlistPanel = doc.select(".stui-pannel .stui-content__playlist").first()?.closest(".stui-pannel")
            ?: doc.select(".stui-pannel.b.playlist").first()
            ?: doc.select(".stui-pannel:has(.stui-content__playlist)").first()

        if (playlistPanel != null) {
            // 线路名称：优先从 .stui-pannel_hd .title 获取
            val lineName = playlistPanel.select(".stui-pannel_hd .title").first()?.text()?.trim()
                ?: playlistPanel.select(".stui-pannel__head .title").first()?.text()?.trim()
                ?: "默认线路"

            val episodeLinks = playlistPanel.select(".stui-content__playlist li a")
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
        } else {
            Log.w(logTag, "未找到播放列表容器，尝试直接查找 .stui-content__playlist")
            // 兜底：直接查找 .stui-content__playlist
            val fallbackEpisodes = doc.select(".stui-content__playlist li a")
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