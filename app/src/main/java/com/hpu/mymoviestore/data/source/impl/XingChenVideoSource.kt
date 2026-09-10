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
 * 星辰影院（www.******.com）播放源
 *
 * 搜索页 URL 格式：/search/?wd={keyword}  （第一页）
 *                  /search/{keyword}-{page}.html （后续页）
 * 详情页 URL 格式：/dongman/{slug}/ 等
 * 播放页 URL 格式：/dongman/{slug}/{sid}-{nid}.html
 *
 * 模板：ZanPianCMS
 */
class XingChenVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("XC", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_xingchen"
    override val cachePrefix = "xingchen"
    override val rateLimiterTag = "XC"
    override val logTag = "XingChenVideoSource"

    // ========== 构建搜索 URL ==========

    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return if (page <= 1) {
            "$baseUrl/search/?wd=$encoded"
        } else {
            "$baseUrl/search/$encoded-$page.html"
        }
    }

    // ========== 解析搜索结果页 ==========

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        // 1. 定位搜索结果项：div#content > div.details-info-min
        val resultItems = doc.select("div#content > div.details-info-min")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 标题和详情链接：ul.info li 中的第一个 a[title]
            val titleLink = item.select("ul.info li a[title]").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            var title = titleLink?.attr("title")?.trim().orEmpty()
            if (title.isBlank()) {
                title = titleLink?.text()?.trim().orEmpty()
            }

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图：a.video-pic 的 data-original
            val picLink = item.select("a.video-pic").first()
            var coverUrl = picLink?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) coverUrl = picLink?.attr("src").orEmpty()
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

            // 状态
            val status = item.select("ul.info li:contains(状态：)").first()?.let { li ->
                li.text().replace("状态：", "").trim()
            }.orEmpty()

            // 类型
            val category = item.select("ul.info li:contains(类型：)").first()?.let { li ->
                li.select("a").joinToString("/") { it.text().trim() }
            }.orEmpty()

            // 主演
            val actors = item.select("ul.info li:contains(主演：)").first()?.let { li ->
                li.select("a").joinToString(" ") { it.text().trim() }
            }.orEmpty()

            // 导演
            val director = item.select("ul.info li:contains(导演：)").first()?.let { li ->
                li.select("a").joinToString(" ") { it.text().trim() }
            }.orEmpty()

            // 年代
            val year = item.select("ul.info li:contains(年代：)").first()?.let { li ->
                val text = li.text().replace("年代：", "").trim()
                Regex("(\\d{4})").find(text)?.groupValues?.get(1).orEmpty()
            }.orEmpty()

            // 地区
            val area = item.select("ul.info li:contains(国家/地区：)").first()?.let { li ->
                li.text().replace("国家/地区：", "").trim()
            }.orEmpty()

            // 简介
            val description = item.select("span.details-content-default").first()?.text()?.trim().orEmpty()

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
                description = description,
                sourceName = this.sourceName
            )
        }

        // 2. 分页解析
        val paginationLinks = doc.select("div#long-page ul li a")
        Log.d(logTag, "分页元素数量: ${paginationLinks.size}")

        // 提取总页数
        var totalPages = 1
        // 优先从"尾页"链接提取
        val lastLink = paginationLinks.find { it.text().trim() == "尾页" }
        if (lastLink != null) {
            val href = lastLink.attr("href")
            Regex("/search/.*?-(\\d+)\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalPages = it
            }
        }
        // 备用：从 "1/4" 文本提取
        if (totalPages <= 1) {
            val pageInfo = doc.select("div#long-page ul li.visible-xs span.num").first()?.text()?.trim()
            if (pageInfo != null && pageInfo.contains("/")) {
                totalPages = pageInfo.split("/").getOrNull(1)?.toIntOrNull() ?: 1
            }
        }
        // 兜底：从页码数字取最大值
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
            a.text().trim() == "下一页" || a.text().trim() == "下页"
        }
        val hasPrev = paginationLinks.any { a ->
            a.text().trim() == "上一页" || a.text().trim() == "上页"
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

        // 1. 标题
        val title = doc.select("h1.text-overflow").first()?.text()?.trim().orEmpty()

        // 2. 封面图：从 a.video-pic 的 style 中提取 background url
        var coverUrl = ""
        val picStyle = doc.select("a.video-pic").first()?.attr("style").orEmpty()
        if (picStyle.isNotBlank()) {
            val regex = Regex("""url\(['"]?(.*?)['"]?\)""")
            coverUrl = regex.find(picStyle)?.groupValues?.get(1).orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"

        // 3. 从 ul.info 中提取各类信息
        var category = ""
        var area = ""
        var year = ""
        var director = ""
        var actors = ""
        var description = ""
        var status = ""

        val infoItems = doc.select("ul.info li")
        for (item in infoItems) {
            val text = item.text().trim()
            when {
                text.contains("状态：") -> {
                    status = text.replace("状态：", "").trim()
                }
                text.contains("类型：") -> {
                    category = item.select("a").joinToString("/") { it.text().trim() }
                        .ifBlank { text.replace("类型：", "").trim() }
                }
                text.contains("主演：") -> {
                    actors = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("主演：", "").trim() }
                }
                text.contains("导演：") -> {
                    director = item.select("a").joinToString(" ") { it.text().trim() }
                        .ifBlank { text.replace("导演：", "").trim() }
                }
                text.contains("年代：") -> {
                    year = Regex("(\\d{4})").find(text)?.groupValues?.get(1).orEmpty()
                }
                text.contains("国家/地区：") -> {
                    area = text.replace("国家/地区：", "").trim()
                }
                text.contains("详细介绍：") -> {
                    // 优先取完整简介
                    val fullDesc = item.select("span.details-content-all").first()?.text()?.trim()
                    val shortDesc = item.select("span.details-content-default").first()?.text()?.trim()
                    description = fullDesc?.takeIf { it.isNotBlank() } ?: shortDesc.orEmpty()
                }
            }
        }

        // 4. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取线路标签：ul#playTab li a
        val tabLinks = doc.select("ul#playTab li a")
        Log.d(logTag, "找到 ${tabLinks.size} 个线路标签")

        tabLinks.forEach { tab ->
            val lineName = tab.text().trim()
            val href = tab.attr("href")  // 如 #con_playlist_1
            if (lineName.isBlank() || href.isBlank()) return@forEach

            // 根据 href 找到对应的集数列表容器
            val pane = doc.select(href).first()
            if (pane == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEach
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

        // 兜底：如果没找到线路，直接找所有集数
        if (lines.isEmpty()) {
            val fallbackLinks = doc.select("div.playlist ul li a")
            if (fallbackLinks.isNotEmpty()) {
                val episodes = fallbackLinks.mapNotNull { a ->
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
            rating = "0.0", // 该站点评分暂时不提取
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

        // 方法1：提取 zanpiancms_player 对象中的 "url"
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        val match = urlRegex.find(scriptContent)
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
                Log.d(logTag, "✅ 从 zanpiancms_player.url 提取到播放地址: $videoUrl")
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

    override suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(
        Dispatchers.IO) {
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

            // 该站点播放地址在包含 zanpiancms_player 的脚本中
            val scriptElement = playDoc.select("script:containsData(zanpiancms_player)").first()
                ?: playDoc.select("script:containsData(cms_play)").first()

            if (scriptElement == null) {
                Log.e(logTag, "未找到包含 zanpiancms_player 的脚本")
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
}