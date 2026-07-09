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

class DoujiaoVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("DJ", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_dj"
    override val cachePrefix = "doujiao"
    override val rateLimiterTag = "DJ"
    override val logTag = "DoujiaoVideoSource"

    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        Log.d(logTag, "========== parseVideoDetail 开始 ==========")
        Log.d(logTag, "解析 URL: $detailUrl")
        Log.d(logTag, "文档标题: ${doc.title()}")

        // 1. 标题
        val title = doc.select(".macplus-content__detail h1.title").first()?.text()?.trim()
            ?: doc.select("h1.title").first()?.text()?.trim()
            ?: ""
        Log.d(logTag, "标题: $title")

        // 2. 封面图
        val coverImg = doc.select(".macplus-content__thumb img").first()
        var coverUrl = coverImg?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) coverUrl = coverImg?.attr("src").orEmpty()
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        Log.d(logTag, "封面: $coverUrl")

        // 3. 从详情信息中提取各字段
        var category = ""
        var year = ""
        var area = ""
        var director = ""
        var actors = ""
        var description = ""
        var rating = ""

        // 遍历所有 p.data
        val dataItems = doc.select(".macplus-content__detail p.data")
        for (item in dataItems) {
            val text = item.text()
            when {
                text.contains("导演：") -> {
                    director = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("导演：", "").trim() }
                }
                text.contains("年代：") -> {
                    year = item.select("a").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("年代：", "").trim() }
                }
                text.contains("地区：") -> {
                    area = item.select("a").first()?.text()?.trim().orEmpty()
                        .ifBlank { text.replace("地区：", "").trim() }
                }
                text.contains("类型：") -> {
                    category = item.select("a").joinToString("/") { it.text().trim() }
                        .ifBlank { text.replace("类型：", "").trim() }
                }
                text.contains("主演：") -> {
                    actors = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("主演：", "").trim() }
                }
            }
        }

        // 4. 评分（页面中有 .douban .text，但可能不存在）
        val ratingElement = doc.select(".douban .text").first()
        if (ratingElement != null) {
            rating = ratingElement.text().trim()
        }

        // 5. 简介
        val descElement = doc.select("#cText").first()
        if (descElement != null) {
            description = descElement.text().trim()
        } else {
            description = doc.select(".detail #cText").first()?.text()?.trim().orEmpty()
        }
        // 如果简介为空，尝试从 .detail 中提取
        if (description.isBlank()) {
            description = doc.select(".detail").first()?.text()?.trim().orEmpty()
        }
        Log.d(logTag, "简介长度: ${description.length}")

        // 6. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取线路名称（从 #playTab 或 .nav-tabs 中提取）
        val lineName = doc.select("#playTab .active a, .nav-tabs .active a").first()?.text()?.trim()
            ?: "云播资源"

        // 获取集数列表
        val episodeLinks = doc.select("#con_playlist_1 li a")
        if (episodeLinks.isNotEmpty()) {
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
            Log.w(logTag, "未找到集数列表")
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

    /**
     * 从豆角网播放页脚本中提取真实视频地址（m3u8）
     *
     * 数据格式示例：
     * ```javascript
     * var player_aaaa = {
     *     "flag":"play",
     *     "encrypt":0,
     *     "trysee":0,
     *     "points":0,
     *     "link":"/vodplay/580-1-1.html",
     *     "link_next":"/vodplay/580-1-2.html",
     *     "link_pre":"",
     *     "url":"https://vip.ffzyread2.com/20230829/9711_f0153862/index.m3u8",
     *     "url_next":"https://vip.ffzyread2.com/20230829/9712_06516bec/index.m3u8",
     *     "from":"ffm3u8",
     *     ...
     * }
     * ```
     */
    override fun extractRealVideoUrl(scriptContent: String): String? {
        Log.d(logTag, "========== extractRealVideoUrl 开始 ==========")

        // 方法1：提取 player_aaaa 中的 "url"
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        var match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")  // 反转义斜杠
                .trim()
            // URL 解码（处理可能的百分号编码）
            videoUrl = try {
                java.net.URLDecoder.decode(videoUrl, "UTF-8")
            } catch (e: Exception) {
                videoUrl
            }
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 url 提取到地址: $videoUrl")
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
            } catch (e: Exception) {
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
            } catch (e: Exception) {
                videoUrl
            }
            Log.d(logTag, "✅ 从全局搜索提取到 m3u8: $videoUrl")
            return videoUrl
        }

        Log.e(logTag, "❌ 未能从脚本中提取到有效的播放地址")
        Log.d(logTag, "脚本片段预览: ${scriptContent.take(500)}")
        return null
    }

    /**
     * 构建豆角网搜索 URL
     * 格式：/vodsearch/{encodedKeyword}----------{page}---.html
     * 示例：/vodsearch/%E7%88%B1----------1---.html
     */
    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/vodsearch/$encodedKeyword----------$safePage---.html"
    }

    /**
     * 解析豆角网搜索结果页
     */
    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        // 1. 提取结果列表项
        val resultItems = doc.select("ul.macplus-vodlist__media > li.bottom-line")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接
            val titleLink = item.select(".detail h3.title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            // 标题中可能包含 <span class="text-red"> 高亮，需要获取纯文本
            val title = titleLink?.text()?.trim() ?: ""

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图
            val thumb = item.select(".thumb .macplus-vodlist__thumb").first()
            var coverUrl = thumb?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = thumb?.attr("src").orEmpty()
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            // 导演：从 <p> 中提取，格式：导演：xxx
            val director = item.select(".detail p:contains(导演：)").first()?.let { p ->
                p.text().replace("导演：", "").trim()
            }.orEmpty()

            // 主演：类似
            val actors = item.select(".detail p:contains(主演：)").first()?.let { p ->
                p.text().replace("主演：", "").trim()
            }.orEmpty()

            // 类型、地区、年份：通常在同一行，格式：类型：香港 地区：香港 年份：2017
            val infoP = item.select(".detail p:contains(类型：)").first()
            var category = ""
            var area = ""
            var year = ""
            if (infoP != null) {
                val text = infoP.text()
                // 用正则分别提取
                val typeRegex = Regex("类型：([^\\s]+)")
                val areaRegex = Regex("地区：([^\\s]+)")
                val yearRegex = Regex("年份：([^\\s]+)")
                category = typeRegex.find(text)?.groupValues?.get(1).orEmpty()
                area = areaRegex.find(text)?.groupValues?.get(1).orEmpty()
                year = yearRegex.find(text)?.groupValues?.get(1).orEmpty()
            }

            // 简介：可能没有，或者有其他描述，暂不提取

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "coverUrl=$coverUrl, category='$category', year='$year', area='$area', " +
                        "director='$director', actors='${actors.take(40)}'"
            )

            VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = title,
                coverUrl = coverUrl,
                playUrl = "",
                category = category,
                detailUrl = detailUrl,
                rating = "",  // 搜索结果页无评分
                year = year,
                area = area,
                director = director,
                actors = actors,
                description = "",
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        val paginationLinks = doc.select(".macplus-page_info li a")
        Log.d(logTag, "分页元素数量: ${paginationLinks.size}")

        // 提取最大页码（从"尾页"链接或页码链接中）
        var totalPages = 1
        // 从"尾页"链接提取
        val lastPageLink = paginationLinks.find { it.text().trim() == "尾页" }
        if (lastPageLink != null) {
            val href = lastPageLink.attr("href")
            Regex("/vodsearch/.*?----------(\\d+)---\\.html").find(href)?.let {
                totalPages = it.groupValues[1].toIntOrNull() ?: 1
            }
        }
        // 如果没找到，从页码数字中找最大值
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
}
