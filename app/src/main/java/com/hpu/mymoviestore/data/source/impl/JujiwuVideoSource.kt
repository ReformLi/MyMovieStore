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

class JujiwuVideoSource(
    client: OkHttpClient = defaultClient(),
    cacheRepository: ApiCacheRepository? = null,
    rateLimiter: RequestRateLimiter = RequestRateLimiter("JJU", 3_000L, 3)
) : CrawlerVideoSource(client, cacheRepository, rateLimiter) {

    override val sourceId = "crawler_jju"
    override val sourceName = "剧集屋"
    override val baseUrl = "https://www.laojuji.com"
    override val cachePrefix = "crawler"
    override val rateLimiterTag = "JJU"
    override val logTag = "JujiwuVideoSource"

    override fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
        val title = doc.select(".detail-header .info h1").first()?.text()?.trim()
            ?: doc.select("h1").first()?.text()?.trim()
            ?: ""

        val coverUrl = doc.select(".detail-header .poster img").first()?.attr("abs:src").orEmpty()
        val category = doc.select(".detail-header .info .item .tab-box").getOrNull(0)
            ?.select("a")
            ?.first()
            ?.text()
            ?.trim()
            .orEmpty()
        val year = doc.select(".detail-header .info .item .tab-box").getOrNull(1)
            ?.select("a")
            ?.first()
            ?.text()
            ?.trim()
            .orEmpty()
        val rating = doc.select(".score-box .star_tips").first()?.text()?.trim().orEmpty()
        val director = doc.select(".detail-header .item1 a").joinToString(" ") { it.text().trim() }
        val actors = doc.select(".detail-header .author-box a").joinToString(" ") { it.text().trim() }
        val description = doc.select(".movie-detail-box .content span").first()?.text()?.trim()
            ?: doc.select(".movie-detail-box .content").first()?.text()?.trim()
            ?: ""

        val lines = doc.select(".movie-channel").mapNotNull { channel ->
            val lineName = channel.select(".channel-header .title").first()?.text()?.trim().orEmpty()
            val episodes = channel
                .select("ul.channel-set[data-type=zheng] a.item")
                .mapNotNull { link ->
                    val episodeTitle = link.ownText().ifBlank { link.text() }
                        .replace("\\s+".toRegex(), " ")
                        .trim()
                    val playPageUrl = link.attr("abs:href")
                    if (episodeTitle.isBlank() || playPageUrl.isBlank()) {
                        null
                    } else {
                        PlayEpisode(episodeTitle, playPageUrl)
                    }
                }
                .distinctBy { it.playPageUrl }

            if (lineName.isBlank() || episodes.isEmpty()) {
                null
            } else {
                PlayLine(lineName, episodes)
            }
        }

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

    override fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        val resultLinks = doc.select(".tList > a")
        val resultItems = doc.select(".tList > .item")
        val fallbackLinks = doc.select("a[href*=voddetail]")
        Log.d(
            logTag,
            "解析搜索页节点: keyword=$keyword, page=$page, " +
                ".tList>a=${resultLinks.size}, .tList>.item=${resultItems.size}, " +
                "a[href*=voddetail]=${fallbackLinks.size}, " +
                "searchTitle='${doc.select(".search-title").text().trim()}'"
        )

        if (resultItems.isEmpty()) {
            Log.w(
                logTag,
                "未找到 .tList > .item 搜索结果节点，HTML片段=${doc.select(".movie-main").outerHtml().take(500)}"
            )
        } else {
            resultItems.take(5).forEachIndexed { index, item ->
                val firstDetailLink = item.select("a[href*=voddetail]").first()
                Log.d(
                    logTag,
                    "搜索原始条目第 ${index + 1} 条: href='${firstDetailLink?.attr("href").orEmpty()}', " +
                        "absHref='${firstDetailLink?.attr("abs:href").orEmpty()}', " +
                        "titleAttr='${firstDetailLink?.attr("title").orEmpty()}', " +
                        "text='${item.text().take(160)}'"
                )
                logLong("搜索原始条目第 ${index + 1} 条HTML", item.outerHtml())
            }
        }

        val items = resultItems.mapIndexedNotNull { index, item ->
            val detailLink = item.select("a[href*=voddetail]").first()
            val detailUrl = detailLink?.attr("abs:href").orEmpty()
            val title = item.select(".info .title").first()?.text()?.trim()
                ?: item.select(".title").first()?.text()?.trim()
                ?: detailLink?.attr("title")?.trim()
                ?: ""
            if (detailUrl.isBlank() || title.isBlank()) {
                Log.w(
                    logTag,
                    "搜索结果第 ${index + 1} 条跳过: detailUrl='$detailUrl', title='$title', " +
                        "href='${detailLink?.attr("href").orEmpty()}', html='${item.outerHtml().take(500)}'"
                )
                return@mapIndexedNotNull null
            }

            var coverUrl = item.select(".poster").first()?.attr("data-original").orEmpty()
            if (coverUrl.isBlank()) {
                val style = item.select(".poster").first()?.attr("style").orEmpty()
                coverUrl = Regex("url\\(([^)]+)\\)").find(style)?.groupValues?.get(1).orEmpty()
            }
            if (coverUrl.startsWith("/")) coverUrl = baseUrl + coverUrl

            val tabs = item.select(".tab-box .tab").map { it.text().trim() }
                .filter { it.isNotBlank() && it != "未知" }
            val category = tabs.getOrNull(0).orEmpty()
            val year = tabs.getOrNull(1).orEmpty()
            val actors = item.select(".author a").joinToString(" ") { it.text().trim() }
                .ifBlank {
                    item.select(".author").first()?.text()
                        ?.replace("主演：", "")
                        ?.trim()
                        .orEmpty()
                }
            val description = item.select(".content p").first()?.text()?.trim()
                ?: item.select(".content").first()?.text()?.trim()
                ?: ""

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

        val paginationLinks = doc.select(".page-box a, .page a, .mac_pages a, .pagination a, .page-number a")
        val maxPageFromHref = paginationLinks.mapNotNull { anchor ->
            val href = anchor.attr("href")
            Regex("/page/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("----------(\\d+)---\\.html").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()
        val maxPageFromText = paginationLinks.mapNotNull { anchor ->
            anchor.text().trim().toIntOrNull()
        }.maxOrNull()
        val totalPages = if (paginationLinks.isEmpty()) {
            1
        } else {
            listOfNotNull(maxPageFromHref, maxPageFromText, page).maxOrNull()?.coerceAtLeast(1) ?: 1
        }
        val hasNextByText = paginationLinks.any {
            val text = it.text().trim()
            text.contains("下一页") || text.contains("下页")
        }
        val hasNextByHref = paginationLinks.any {
            val href = it.attr("href")
            href.contains("/page/${page + 1}") || href.contains("----------${page + 1}---.html")
        }
        val hasPrevByText = paginationLinks.any {
            val text = it.text().trim()
            text.contains("上一页") || text.contains("上页")
        }
        val hasPrevByHref = paginationLinks.any {
            val href = it.attr("href")
            href.contains("/page/${page - 1}") || href.contains("----------${page - 1}---.html")
        }
        val hasPrev = paginationLinks.isNotEmpty() && page > 1 && (hasPrevByText || hasPrevByHref || page <= totalPages)
        val hasNext = paginationLinks.isNotEmpty() && page < totalPages && (hasNextByText || hasNextByHref || page < totalPages)
        Log.d(
            logTag,
            "分页解析: paginationLinks=${paginationLinks.size}, page=$page, " +
                "totalPages=$totalPages, hasPrevByText=$hasPrevByText, " +
                "hasPrevByHref=$hasPrevByHref, hasNextByText=$hasNextByText, " +
                "hasNextByHref=$hasNextByHref, hasPrev=$hasPrev, hasNext=$hasNext, " +
                "links='${paginationLinks.joinToString(" | ") { "${it.text().trim()}=>${it.attr("href")}" }.take(500)}'"
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
