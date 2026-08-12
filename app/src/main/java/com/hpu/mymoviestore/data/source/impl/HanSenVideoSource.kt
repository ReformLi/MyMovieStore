package com.hpu.mymoviestore.data.source.impl

import android.util.Log
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
 * 汉森影院（han-sen.cn）播放源
 *
 * 搜索页 URL 格式：/search.php?page={page}&searchword={keyword}
 * 搜索方式：GET 请求
 */
class HanSenVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("HS", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_hansen"
    override val cachePrefix = "hansen"
    override val rateLimiterTag = "HS"
    override val logTag = "HanSenVideoSource"

    private val playUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ========== 构建搜索 URL ==========

    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        return "$baseUrl/search.php?page=$safePage&searchword=$encoded&searchtype="
    }

    // ========== 解析搜索结果页 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        // 1. 定位搜索结果项：每个 li.active 包含一个视频条目
        val resultItems = doc.select("ul.myui-vodlist__media > li.active")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接
            val titleLink = item.select(".detail h4.title a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图
            val thumb = item.select(".thumb .myui-vodlist__thumb").first()
            var coverUrl = thumb?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = thumb?.attr("src").orEmpty()
            if (coverUrl.startsWith("//")) coverUrl = "http:$coverUrl"
            else if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            // 评分：从 .pic-tag-top 提取
            val rating = item.select(".pic-tag-top").first()?.text()?.trim()
                ?.replace("分", "").orEmpty()

            // 导演
            val director = item.select(".detail p:contains(导演：)").first()?.let { p ->
                p.text().replace("导演：", "").trim()
            }.orEmpty()

            // 主演
            val actors = item.select(".detail p:contains(主演：)").first()?.let { p ->
                p.text().replace("主演：", "").trim()
            }.orEmpty()

            // 分类、地区、年份：从 p:contains(分类：) 提取
            var category = ""
            var area = ""
            var year = ""
            val infoP = item.select(".detail p:contains(分类：)").first()
            if (infoP != null) {
                val text = infoP.text()
                // 格式：分类：海外剧  地区：马来西亚  年份：2026
                val typeRegex = Regex("分类：([^\\s]+)")
                val areaRegex = Regex("地区：([^\\s]+)")
                val yearRegex = Regex("年份：([^\\s]+)")
                category = typeRegex.find(text)?.groupValues?.get(1).orEmpty()
                area = areaRegex.find(text)?.groupValues?.get(1).orEmpty()
                year = yearRegex.find(text)?.groupValues?.get(1).orEmpty()
            }

            // 简介
            val description = item.select(".detail p.hidden-xs").first()?.text()?.trim().orEmpty()

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "category='$category', year='$year', area='$area', rating='$rating'"
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
        val paginationLinks = doc.select("ul.myui-page li a")
        Log.d(logTag, "分页元素数量: ${paginationLinks.size}")

        // 提取总页数：从 "1/314" 中提取
        var totalPages = 1
        val pageInfo = doc.select("ul.myui-page li.visible-xs a").first()?.text()?.trim()
        if (pageInfo != null && pageInfo.contains("/")) {
            totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
        } else {
            // 备用：从尾页链接提取
            val lastLink = paginationLinks.find { it.text().trim() == "尾页" }
            if (lastLink != null) {
                val href = lastLink.attr("href")
                Regex("page=(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let {
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
            text == "下一页" || text == "下页" || a.attr("href").contains("page=${page + 1}")
        }

        val hasPrev = paginationLinks.any { a ->
            val text = a.text().trim()
            text == "上一页" || text == "上页" || a.attr("href").contains("page=${page - 1}")
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

        // 1. 标题：从 h1.title 提取，包含状态标签需要移除
        val titleElement = doc.select(".myui-content__detail h1.title").first()
        var title = ""
        if (titleElement != null) {
            // 移除 font 标签（状态显示）后取文本
            val clone = titleElement.clone()
            clone.select("font").remove()
            title = clone.text().trim()
        }
        if (title.isBlank()) {
            title = doc.select(".myui-content__detail h1.title").first()?.text()?.trim()
                ?.replace(Regex("\\s+更新至.*"), "")?.trim() ?: ""
        }

        // 2. 评分：从 #rating .branch 提取
        val rating = doc.select("#rating .branch").first()?.text()?.trim().orEmpty()

        // 3. 封面图：从 .myui-content__thumb img 的 data-original 提取
        var coverUrl = doc.select(".myui-content__thumb img").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".myui-content__thumb img").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("//")) coverUrl = "http:$coverUrl"
        else if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

        // 4. 从 p.data 中提取分类、地区、年份
        var category = ""
        var area = ""
        var year = ""
        var director = ""
        var actors = ""
        var description = ""

        val dataItems = doc.select(".myui-content__detail p.data")
        for (item in dataItems) {
            val text = item.text()
            when {
                text.contains("分类：") -> {
                    category = item.select("a").first()?.text()?.trim().orEmpty()
                }
                text.contains("地区：") -> {
                    area = item.select("a").first()?.text()?.trim().orEmpty()
                }
                text.contains("年份：") -> {
                    year = item.select("a").first()?.text()?.trim().orEmpty()
                }
                text.contains("导演：") -> {
                    director = item.select("a").joinToString(" ") { it.text().trim() }
                }
                text.contains("主演：") -> {
                    actors = item.select("a").joinToString(" ") { it.text().trim() }
                }
            }
        }

        // 5. 简介：从 .desc .sketch 提取
        val descElement = doc.select(".desc .sketch").first()
        if (descElement != null) {
            description = descElement.text().trim()
        } else {
            description = doc.select(".desc").first()?.text()?.trim().orEmpty()
                .replace("简介：", "").trim()
        }

        // 6. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取线路名称：从 .nav-tabs li a 提取（排除 active 标签）
        val tabItems = doc.select(".nav-tabs li a")
        Log.d(logTag, "找到 ${tabItems.size} 个线路标签")

        // 获取集数列表容器：.tab-content .tab-pane
        val tabPanes = doc.select(".tab-content .tab-pane")
        Log.d(logTag, "找到 ${tabPanes.size} 个集数面板")

        // 注意：tabItems 中每个 a 的 href 对应 #playlist1, #playlist2...
        tabItems.forEachIndexed { index, tab ->
            val lineName = tab.text().trim()
            if (lineName.isBlank()) return@forEachIndexed

            // 获取对应索引的集数列表容器
            val pane = tabPanes.getOrNull(index)
            if (pane == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEachIndexed
            }

            // 提取集数链接
            val episodeLinks = pane.select(".myui-content__list.sort-list li a")
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
            val fallbackEpisodes = doc.select(".myui-content__list.sort-list li a")
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

        // 方法1：提取 now="..."
        val nowRegex = Regex("""now\s*=\s*"([^"]+)""")
        var match = nowRegex.find(scriptContent)
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
                Log.d(logTag, "✅ 从 now 提取到播放地址: $videoUrl")
                return videoUrl
            }
        }

        // 方法2：提取 url 字段（兼容 player_aaaa 或其他格式）
        val urlRegex = Regex("""url\s*:\s*"([^"]+)""")
        match = urlRegex.find(scriptContent)
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
                Log.d(logTag, "✅ 从 url 字段提取到播放地址: $videoUrl")
                return videoUrl
            }
        }

        // 方法3：提取 next 作为备用地址
        val nextRegex = Regex("""next\s*=\s*"([^"]+)""")
        match = nextRegex.find(scriptContent)
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
                Log.d(logTag, "✅ 从 next 提取到备用地址: $videoUrl")
                return videoUrl
            }
        }

        // 方法4：直接搜索 .m3u8 链接（兜底）
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

    override suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(
        Dispatchers.IO) {
        try {
            if (playPageUrl.isBlank()) {
                return@withContext Result.failure(
                    CrawlError(
                        CrawlErrorType.EMPTY_RESULT,
                        sourceName,
                        "播放页地址为空"
                    )
                )
            }

            // 内存缓存
            playUrlCache[playPageUrl]?.let { cachedUrl ->
                if (cachedUrl.isNotBlank()) {
                    Log.d(logTag, "真实播放地址缓存命中（内存）: ${cachedUrl.take(120)}")
                    return@withContext Result.success(cachedUrl)
                }
            }

            Log.d(logTag, "开始请求播放页提取真实地址: $playPageUrl")
            val doc = requestDocument(playPageUrl, RequestRateLimiter.Priority.PLAY)

            var videoUrl: String? = null

            // 查找包含播放数据的脚本
            val scripts = doc.select("script")
            for (script in scripts) {
                val content = script.html()
                if (content.contains("now=") || content.contains("url:") || content.contains(".m3u8")) {
                    // 1. 提取 now="..."
                    val nowRegex = Regex("""now\s*=\s*"([^"]+)""")
                    val match = nowRegex.find(content)
                    if (match != null) {
                        var url = match.groupValues[1].replace("\\/", "/").trim()
                        url = try { java.net.URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
                        if (url.isNotBlank() && url.contains(".m3u8")) {
                            videoUrl = url
                            break
                        }
                    }

                    // 2. 提取 url:"..."（兼容其他格式）
                    val urlRegex = Regex("""url\s*:\s*"([^"]+)""")
                    val urlMatch = urlRegex.find(content)
                    if (urlMatch != null) {
                        var url = urlMatch.groupValues[1].replace("\\/", "/").trim()
                        url = try { java.net.URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
                        if (url.isNotBlank() && url.contains(".m3u8")) {
                            videoUrl = url
                            break
                        }
                    }

                    // 3. 直接搜索 .m3u8 链接
                    val m3u8Regex = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*")
                    val m3u8Match = m3u8Regex.find(content)
                    if (m3u8Match != null) {
                        var url = m3u8Match.value.trim()
                        url = try { java.net.URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
                        if (url.isNotBlank() && url.contains(".m3u8")) {
                            videoUrl = url
                            break
                        }
                    }
                }
            }

            if (!videoUrl.isNullOrBlank()) {
                Log.d(logTag, "成功提取视频地址: $videoUrl")
                playUrlCache[playPageUrl] = videoUrl
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