package com.hpu.mymoviestore.data.source

import android.util.Log
import com.hpu.mymoviestore.data.model.CrawlError
import com.hpu.mymoviestore.data.model.CrawlErrorType
import com.hpu.mymoviestore.data.model.DoubanMoviePageResult
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.model.toCrawlError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.random.Random

/**
 * 内容发现层：负责从豆瓣发现影视内容。
 *
 * 当前只实现首页“全部”分类：
 * - 请求豆瓣电影首页并输出网页返回日志。
 * - 请求最近热门电视剧 5 个滑动页内容。
 * - 请求最近热门电影 5 个滑动页内容。
 * - 每个滑动页内将电视剧和电影随机混排，再按第 1 页、第 2 页 ... 依次拼接。
 */
class DoubanDiscoverySource {

    suspend fun fetchHomeAll(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== 豆瓣内容发现：首页全部 开始 ==========")
            logDoubanHomeResponse()

            val tvItems = fetchHotSubjects(type = TYPE_TV, categoryName = "电视剧")
            val movieItems = fetchHotSubjects(type = TYPE_MOVIE, categoryName = "电影")

            Log.d(
                TAG,
                "豆瓣热门内容抓取完成: tv=${tvItems.size}, movie=${movieItems.size}, " +
                    "pageLimit=$PAGE_LIMIT, pageCount=$PAGE_COUNT"
            )

            val merged = mergeBySlidePage(tvItems, movieItems)
            Log.d(
                TAG,
                "豆瓣首页全部混排完成: total=${merged.size}, " +
                    "first10=${merged.take(10).joinToString { it.title }}"
            )
            Result.success(merged)
        } catch (t: Throwable) {
            Log.e(TAG, "豆瓣首页全部抓取失败", t)
            Result.failure(t.toCrawlError(source = SOURCE_TAG))
        }
    }

    suspend fun fetchExploreMoviePage(
        type: String,
        start: Int,
        limit: Int = EXPLORE_PAGE_LIMIT
    ): Result<DoubanMoviePageResult> = withContext(Dispatchers.IO) {
        fetchRecentHotPage(
            supportType = TYPE_MOVIE,
            pageCategory = "热门",
            type = normalizeMovieType(type),
            displayCategory = "电影",
            start = start,
            limit = limit,
            pageName = "电影",
            referer = DOUBAN_EXPLORE_URL
        )
    }

    suspend fun fetchExploreTvRelatedPage(
        pageCategory: String,
        type: String,
        displayCategory: String,
        start: Int,
        limit: Int = EXPLORE_PAGE_LIMIT
    ): Result<DoubanMoviePageResult> = withContext(Dispatchers.IO) {
        fetchRecentHotPage(
            supportType = TYPE_TV,
            pageCategory = pageCategory,
            type = type,
            displayCategory = displayCategory,
            start = start,
            limit = limit,
            pageName = displayCategory,
            referer = DOUBAN_TV_URL
        )
    }

    private suspend fun fetchRecentHotPage(
        supportType: String,
        pageCategory: String,
        type: String,
        displayCategory: String,
        start: Int,
        limit: Int,
        pageName: String,
        referer: String
    ): Result<DoubanMoviePageResult> {
        return try {
            val safeType = type.ifBlank { pageCategory }
            val safeStart = start.coerceAtLeast(0)
            val safeLimit = limit.coerceAtLeast(1)

            Log.d(
                TAG,
                "========== 豆瓣首页-$pageName 抓取开始: supportType=$supportType, " +
                    "category=$pageCategory, type=$safeType, start=$safeStart, limit=$safeLimit =========="
            )

            if (safeStart == 0 && supportType == TYPE_MOVIE) {
                runCatching {
                    logDoubanExploreResponse()
                }.onFailure { pageError ->
                    Log.w(
                        TAG,
                        "豆瓣首页-电影页面 HTML 日志请求失败，但继续请求 JSON 接口: " +
                            "errorType=${pageError::class.java.name}, message=${pageError.message}",
                        pageError
                    )
                }
            }

            humanDelay()
            val url = buildRecentHotUrl(supportType, pageCategory, safeType, safeStart, safeLimit)
            Log.d(
                TAG,
                "请求豆瓣首页-$pageName JSON 接口: supportType=$supportType, " +
                    "category=$pageCategory, type=$safeType, start=$safeStart, " +
                    "limit=$safeLimit, url=$url, referer=$referer"
            )

            val response = Jsoup.connect(url)
                .timeout(15_000)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .userAgent(USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Origin", DOUBAN_HOME_URL)
                .referrer(referer)
                .execute()

            val body = response.body()
            Log.d(
                TAG,
                "豆瓣首页-$pageName 返回: status=${response.statusCode()}, type=$safeType, " +
                    "start=$safeStart, contentType=${response.contentType()}, " +
                    "bodyLength=${body.length}, bodyHead='${body.take(500)}'"
            )

            if (response.statusCode() !in 200..299) {
                Log.w(
                    TAG,
                    "豆瓣首页-$pageName 接口 HTTP 非成功: status=${response.statusCode()}, " +
                        "url=$url, body='${body.take(1000)}'"
                )
                (response as java.io.Closeable).close()
                val crawlError = CrawlError(
                    type = when (response.statusCode()) {
                        403 -> {
                            if (body.contains("captcha", ignoreCase = true) ||
                                body.contains("验证码", ignoreCase = true)
                            ) CrawlErrorType.CAPTCHA else CrawlErrorType.FORBIDDEN
                        }
                        in 400..499 -> CrawlErrorType.CLIENT_ERROR
                        in 500..599 -> CrawlErrorType.SERVER_ERROR
                        else -> CrawlErrorType.UNKNOWN
                    },
                    source = SOURCE_TAG,
                    detail = "HTTP ${response.statusCode()} for $url",
                    cause = null
                )
                return Result.failure(crawlError)
            }

            val json = JSONObject(body)
            val total = json.optInt("total", 0)
            val itemsJson = json.optJSONArray("items")
            val items = mutableListOf<VideoItem>()
            if (itemsJson == null) {
                Log.w(TAG, "豆瓣首页-$pageName 解析失败: items 为空, type=$safeType, raw=${body.take(500)}")
            } else {
                for (index in 0 until itemsJson.length()) {
                    val itemJson = itemsJson.optJSONObject(index) ?: continue
                    val idText = itemJson.optString("id")
                    val title = itemJson.optString("title").trim()
                    if (title.isBlank()) continue
                    val pic = itemJson.optJSONObject("pic")
                    val cover = pic?.optString("normal")?.ifBlank { pic.optString("large") }.orEmpty()
                    val ratingObj = itemJson.optJSONObject("rating")
                    val ratingValue = ratingObj?.optDouble("value", 0.0) ?: 0.0
                    val rating = if (ratingValue > 0.0) String.format("%.1f", ratingValue) else ""
                    val uri = itemJson.optString("uri")
                    val detailUrl = if (idText.isNotBlank()) {
                        "$DOUBAN_HOME_URL/subject/$idText/"
                    } else {
                        uri
                    }

                    items.add(
                        VideoItem(
                            id = idText.toLongOrNull() ?: detailUrl.hashCode().toLong(),
                            title = title,
                            coverUrl = cover,
                            playUrl = "",
                            category = displayCategory,
                            detailUrl = detailUrl,
                            rating = rating,
                            year = itemJson.optString("card_subtitle").substringBefore(" / ").trim(),
                            area = "豆瓣",
                            director = "",
                            actors = "",
                            description = itemJson.optString("card_subtitle")
                        )
                    )
                    Log.d(
                        TAG,
                        "豆瓣首页-$pageName 第 ${safeStart + index + 1} 条: " +
                            "type=$safeType, id=$idText, title='$title', rating='$rating', cover='$cover'"
                    )
                }
            }

            val result = DoubanMoviePageResult(
                type = safeType,
                start = safeStart,
                limit = safeLimit,
                total = total,
                items = items
            )
            Log.d(
                TAG,
                "豆瓣首页-$pageName 解析完成: type=$safeType, start=$safeStart, " +
                    "items=${items.size}, total=$total, hasMore=${result.hasMore}"
            )
            (response as java.io.Closeable).close()
            Result.success(result)
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "豆瓣首页-$pageName 抓取失败: supportType=$supportType, category=$pageCategory, " +
                    "type=$type, start=$start, " +
                    "errorType=${t::class.java.name}, message=${t.message}",
                t
            )
            Result.failure(t.toCrawlError(source = SOURCE_TAG))
        }
    }

    private suspend fun logDoubanHomeResponse() {
        humanDelay()
        val doc = Jsoup.connect(DOUBAN_HOME_URL)
            .timeout(15_000)
            .userAgent(USER_AGENT)
            .referrer(DOUBAN_HOME_URL)
            .get()

        val recentHot = doc.select("#recent-hot")
        val gaiaConfigScript = doc.select("script:containsData(gaiaConfig)").first()?.html().orEmpty()
        Log.d(
            TAG,
            "豆瓣首页返回: url=$DOUBAN_HOME_URL, finalLocation=${doc.location()}, " +
                "title='${doc.title()}', htmlLength=${doc.outerHtml().length}, " +
                "recentHotNodes=${recentHot.size}, gaiaConfigLength=${gaiaConfigScript.length}"
        )
        Log.d(TAG, "豆瓣首页 recent-hot HTML: '${recentHot.outerHtml().take(300)}'")
        Log.d(TAG, "豆瓣首页 gaiaConfig 片段: '${gaiaConfigScript.take(500)}'")
    }

    private suspend fun logDoubanExploreResponse() {
        humanDelay()
        Log.d(TAG, "请求豆瓣首页-电影页面 HTML: url=$DOUBAN_EXPLORE_URL")
        val doc = Jsoup.connect(DOUBAN_EXPLORE_URL)
            .timeout(15_000)
            .userAgent(USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .referrer(DOUBAN_HOME_URL)
            .get()
        val configScript = doc.select("script:containsData(support_type)").first()?.html().orEmpty()
        Log.d(
            TAG,
            "豆瓣首页-电影页面返回: url=$DOUBAN_EXPLORE_URL, finalLocation=${doc.location()}, " +
                "title='${doc.title()}', htmlLength=${doc.outerHtml().length}, appNodes=${doc.select("#app").size}"
        )
        Log.d(TAG, "豆瓣首页-电影 #app HTML: '${doc.select("#app").outerHtml().take(300)}'")
        Log.d(TAG, "豆瓣首页-电影 config 片段: '${configScript.take(500)}'")
    }

    private suspend fun fetchHotSubjects(type: String, categoryName: String): List<VideoItem> {
        humanDelay()
        val url = buildHotSubjectsUrl(type)
        Log.d(TAG, "请求豆瓣最近热门$categoryName: type=$type, url=$url")

        val response = Jsoup.connect(url)
            .timeout(15_000)
            .ignoreContentType(true)
            .userAgent(USER_AGENT)
            .referrer(DOUBAN_HOME_URL)
            .execute()

        val body = response.body()
        Log.d(
            TAG,
            "豆瓣最近热门$categoryName 返回: status=${response.statusCode()}, " +
                "contentType=${response.contentType()}, bodyLength=${body.length}, bodyHead='${body.take(300)}'"
        )

        val subjects = JSONObject(body).optJSONArray("subjects")
        if (subjects == null) {
            Log.w(TAG, "豆瓣最近热门$categoryName 解析失败: subjects 为空")
            (response as java.io.Closeable).close()
            return emptyList()
        }

        val result = mutableListOf<VideoItem>()
        for (index in 0 until subjects.length()) {
            val subject = subjects.optJSONObject(index) ?: continue
            val idText = subject.optString("id")
            val title = subject.optString("title").trim()
            val cover = subject.optString("cover").trim()
            val rate = subject.optString("rate").trim()
            val urlFromJson = subject.optString("url").trim()

            if (title.isBlank()) {
                Log.w(TAG, "豆瓣最近热门$categoryName 第 ${index + 1} 条跳过: title 为空, raw=$subject")
                continue
            }

            val item = VideoItem(
                id = idText.toLongOrNull() ?: urlFromJson.hashCode().toLong(),
                title = title,
                coverUrl = cover,
                playUrl = "",
                category = categoryName,
                detailUrl = urlFromJson,
                rating = rate,
                year = "",
                area = "豆瓣",
                director = "",
                actors = "",
                description = ""
            )
            result.add(item)
            Log.d(
                TAG,
                "豆瓣最近热门$categoryName 第 ${index + 1} 条: " +
                    "id=$idText, title='$title', rate='$rate', cover='$cover', url='$urlFromJson'"
            )
        }

        Log.d(TAG, "豆瓣最近热门$categoryName 解析完成: count=${result.size}")
        (response as java.io.Closeable).close()
        return result
    }

    private fun mergeBySlidePage(tvItems: List<VideoItem>, movieItems: List<VideoItem>): List<VideoItem> {
        val merged = mutableListOf<VideoItem>()
        repeat(PAGE_COUNT) { pageIndex ->
            val tvPage = tvItems.drop(pageIndex * PAGE_LIMIT).take(PAGE_LIMIT)
            val moviePage = movieItems.drop(pageIndex * PAGE_LIMIT).take(PAGE_LIMIT)
            // 只允许在同一个豆瓣滑动页内部随机，不能把后续滑动页内容打乱到前面。
            val pageItems = (tvPage + moviePage).shuffled(Random(System.currentTimeMillis() + pageIndex))
            Log.d(
                TAG,
                "豆瓣第 ${pageIndex + 1} 个滑动页内部混排: tv=${tvPage.size}, movie=${moviePage.size}, " +
                    "merged=${pageItems.size}, titles=${pageItems.joinToString { it.title }}"
            )
            merged.addAll(pageItems)
        }
        return merged
    }

    private fun buildHotSubjectsUrl(type: String): String {
        return "$DOUBAN_HOME_URL/j/search_subjects" +
            "?type=$type" +
            "&tag=%E7%83%AD%E9%97%A8" +
            "&sort=recommend" +
            "&page_limit=${PAGE_LIMIT * PAGE_COUNT}" +
            "&page_start=0"
    }

    private fun buildRecentHotUrl(
        supportType: String,
        pageCategory: String,
        type: String,
        start: Int,
        limit: Int
    ): String {
        val encodedCategory = java.net.URLEncoder.encode(pageCategory, "UTF-8")
        val encodedType = java.net.URLEncoder.encode(type, "UTF-8")
        return "https://m.douban.com/rexxar/api/v2/subject/recent_hot/$supportType" +
            "?start=$start" +
            "&limit=$limit" +
            "&category=$encodedCategory" +
            "&type=$encodedType"
    }

    private fun normalizeMovieType(type: String): String {
        return if (type.isBlank()) "全部" else type
    }

    private suspend fun humanDelay() {
        delay(Random.nextLong(600, 1200))
    }

    companion object {
        private const val TAG = "DoubanDiscoverySource"
        const val SOURCE_TAG = "豆瓣"
        private const val DOUBAN_HOME_URL = "https://movie.douban.com"
        private const val DOUBAN_EXPLORE_URL = "https://movie.douban.com/explore/"
        private const val DOUBAN_TV_URL = "https://movie.douban.com/tv/"
        private const val TYPE_MOVIE = "movie"
        private const val TYPE_TV = "tv"
        private const val PAGE_COUNT = 5
        private const val PAGE_LIMIT = 10
        const val EXPLORE_PAGE_LIMIT = 20
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
