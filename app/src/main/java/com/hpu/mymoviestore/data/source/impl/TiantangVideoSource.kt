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

class TiantangVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("TT", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_tiantang"
    override val cachePrefix = "tiantang"
    override val rateLimiterTag = "TT"
    override val logTag = "TiantangVideoSource"

    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        Log.d(logTag, "========== parseVideoDetail 开始 ==========")
        Log.d(logTag, "解析 URL: $detailUrl")
        Log.d(logTag, "文档标题: ${doc.title()}")

        // 1. 标题
        val title = doc.select(".vod-detail-info h1").first()?.text()?.trim()
            ?: doc.select("h1").first()?.text()?.trim()
            ?: ""
        Log.d(logTag, "标题: $title")

        // 2. 封面图
        var coverUrl = doc.select(".vod-detail-thumb").first()?.attr("data-original").orEmpty()
        if (coverUrl.isBlank()) {
            coverUrl = doc.select(".vod-detail-thumb").first()?.attr("src").orEmpty()
        }
        if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl
        Log.d(logTag, "封面: $coverUrl")

        // 3. 从详情信息列表中提取各字段
        val infoItems = doc.select(".vod-detail-info li")
        Log.d(logTag, "找到 ${infoItems.size} 个信息项")

        var category = ""
        var year = ""
        var area = ""
        var director = ""
        var actors = ""
        var description = ""

        for (item in infoItems) {
            val labelSpan = item.select(".text-ccc").first()
            if (labelSpan == null) continue

            val label = labelSpan.text().trim()
            val valueText = item.ownText().trim() // 文本节点部分
            val linkElements = item.select("a")

            when {
                label.contains("类型") -> {
                    category = linkElements.firstOrNull()?.text()?.trim() ?: valueText
                }
                label.contains("年代") -> {
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
        Log.d(logTag, "分类: $category, 年份: $year, 地区: $area, 导演: $director, 主演: $actors")

        // 4. 简介
        val descElement = doc.select(".detail-info p.txt-hidden").first()
        if (descElement != null) {
            description = descElement.text().trim()
                .replace(Regex("\\s+"), " ") // 合并多余空白
        } else {
            description = doc.select(".detail-info").first()?.text()?.trim().orEmpty()
        }
        Log.d(logTag, "简介长度: ${description.length}")

        // 5. 评分（如有点击展开元素，可能不存在，忽略）

        // 6. 播放线路和集数
        val lines = mutableListOf<PlayLine>()

        // 获取所有线路名称
        val tabItems = doc.select(".details-play-nav .swiper-slide a")
        Log.d(logTag, "找到 ${tabItems.size} 个线路标签")

        // 获取所有线路对应的集数列表容器
        val playLists = doc.select(".play-list")
        Log.d(logTag, "找到 ${playLists.size} 个集数列表")

        tabItems.forEachIndexed { index, tab ->
            val lineName = tab.text().trim()
            // 如果线路名称为空或为"更多剧集"等，跳过
            if (lineName.isBlank()) return@forEachIndexed

            // 获取对应索引的集数列表
            val listContainer = playLists.getOrNull(index)
            if (listContainer == null) {
                Log.w(logTag, "线路 '$lineName' 没有对应的集数列表")
                return@forEachIndexed
            }

            // 提取集数链接，排除"更多剧集"和"收起剧集"等按钮
            val episodeLinks = listContainer.select("li a").filter { a ->
                val text = a.text().trim()
                !text.contains("更多") && !text.contains("收起") && text.isNotBlank()
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
            rating = "", // 该站点无评分，留空
            director = director,
            actors = actors,
            description = description,
            detailUrl = detailUrl,
            playLines = lines,
            sourceName = this.sourceName
        )
    }

    /**
     * 从播放页脚本内容中提取真实视频地址。
     * 针对小蜜tv影院 player_aaaa 格式优化。
     */
    override fun extractRealVideoUrl(scriptContent: String): String? {
        Log.d(logTag, "========== extractRealVideoUrl 开始 ==========")

        // 方法1：提取 player_aaaa 中的 "url":"https://..."
        // 注意：小蜜tv的 url 值中包含 \/ 转义，需要替换
        val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        val match = urlRegex.find(scriptContent)
        if (match != null) {
            var videoUrl = match.groupValues[1]
                .replace("\\/", "/")  // 反转义斜杠
                .trim()
            // 确保是有效的 m3u8 地址
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 player_aaaa.url 提取到地址: $videoUrl")
                return videoUrl
            } else {
                Log.w(logTag, "提取到的 url 不是有效的 m3u8: $videoUrl")
            }
        }

        // 方法2：尝试从 url_next 提取备用地址
        val urlNextRegex = Regex("\"url_next\"\\s*:\\s*\"([^\"]+)\"")
        val nextMatch = urlNextRegex.find(scriptContent)
        if (nextMatch != null) {
            var videoUrl = nextMatch.groupValues[1]
                .replace("\\/", "/")
                .trim()
            if (videoUrl.isNotBlank() && videoUrl.contains(".m3u8")) {
                Log.d(logTag, "✅ 从 player_aaaa.url_next 提取到备用地址: $videoUrl")
                return videoUrl
            }
        }

        // 方法3：直接搜索 .m3u8 链接（兜底方案）
        val m3u8Regex = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*")
        val m3u8Match = m3u8Regex.find(scriptContent)
        if (m3u8Match != null) {
            val videoUrl = m3u8Match.value.trim()
            Log.d(logTag, "✅ 从全局搜索提取到 m3u8: $videoUrl")
            return videoUrl
        }

        Log.e(logTag, "❌ 未能从脚本中提取到有效的播放地址")
        Log.d(logTag, "脚本片段预览: ${scriptContent.take(500)}")
        return null
    }

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        Log.d(logTag, "========== parseSearchPage 开始 ==========")
        Log.d(logTag, "keyword=$keyword, page=$page")

        // 新页面结构：ul.img-list > li 为每个搜索结果
        val resultItems = doc.select("ul.img-list > li")
        Log.d(logTag, "找到 ${resultItems.size} 个搜索结果项")

        val items = resultItems.mapIndexedNotNull { index, item ->
            // 详情链接和标题：.text-overflow h2 a
            val titleLink = item.select(".text-overflow h2 a").first()
            val detailUrl = titleLink?.attr("abs:href").orEmpty()
            val title = titleLink?.text()?.trim().orEmpty()

            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(logTag, "第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title'")
                return@mapIndexedNotNull null
            }

            // 封面图：.img-pic 的 data-original
            val imgPic = item.select(".img-pic").first()
            var coverUrl = imgPic?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                coverUrl = imgPic?.attr("src").orEmpty()
            }
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            // 分类：取 .news-tips 下的第一个 a（排除演员标签）
            val categoryLinks = item.select(".news-tips a")
            val category = categoryLinks.firstOrNull()?.text()?.trim().orEmpty()

            // 年份：页面未提供，留空
            val year = ""

            // 演员：筛选 href 包含 "/tag/actor/" 的 a 标签
            val actors = item.select(".news-tips a[href*=/tag/actor/]")
                .joinToString(" ") { it.text().trim() }
                .ifBlank {
                    // 备用：取隐藏区域内的演员（如果有）
                    item.select(".news-tips.hidden-xs a[href*=/tag/actor/]")
                        .joinToString(" ") { it.text().trim() }
                }

            // 简介：.vod-txt 文本
            val description = item.select(".vod-txt").first()?.text()?.trim().orEmpty()

            Log.d(
                logTag,
                "搜索结果第 ${index + 1} 条: title='$title', detailUrl=$detailUrl, " +
                        "coverUrl=$coverUrl, category='$category', year='$year', " +
                        "actors='${actors.take(40)}', descLength=${description.length}"
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
                area = "",
                director = "",
                actors = actors,
                description = description,
                sourceName = this.sourceName
            )
        }

        // 分页解析：根据 #page ul li 中的链接
        val paginationLinks = doc.select("#page ul li a, #page ul li span")
        Log.d(logTag, "分页元素数量: ${paginationLinks.size}")

        // 提取最大页码（从数字链接中）
        val maxPageFromHref = paginationLinks.mapNotNull { el ->
            val href = el.attr("href")
            Regex("/tag/page/(\\d+)/wd/").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()

        val maxPageFromText = paginationLinks.mapNotNull { el ->
            el.text().trim().toIntOrNull()
        }.maxOrNull()

        val totalPages = listOfNotNull(maxPageFromHref, maxPageFromText, page).maxOrNull()?.coerceAtLeast(1) ?: 1

        // 判断是否有下一页/上一页
        val hasNext = paginationLinks.any { el ->
            val text = el.text().trim()
            text == "下一页" || text == "下页" || el.attr("href").contains("/tag/page/${page + 1}/")
        }

        val hasPrev = paginationLinks.any { el ->
            val text = el.text().trim()
            text == "上一页" || text == "上页" || el.attr("href").contains("/tag/page/${page - 1}/")
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

    /**
     * 构建小蜜tv影院的搜索URL
     * 格式：http://www.xiaomiyingyin.cc/tag/page/1/wd/斗罗大陆/
     */
    override fun buildSearchUrl(keyword: String, page: Int): String {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        val url = "$baseUrl/tag/page/$safePage/wd/$encodedKeyword/"
        Log.d(logTag, "构建搜索URL: $url")
        return url
    }
}
