package com.hpu.mymoviestore.data.source

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
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CrawlerVideoSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // 模拟真实浏览器行为的关键：设置 User-Agent
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build(),
    private val cacheRepository: ApiCacheRepository? = null,
    /**
     * 单源限流器：搜索/详情/播放页统一排队，最大 3 个，最小间隔 3 秒。
     * 同源内不同类型请求共享该队列，符合"内容播放层独立限流"的约束。
     */
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(
        sourceTag = "JJU",
        minIntervalMs = 3_000L,
        maxQueueSize = 3
    )
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val videoListAdapter = moshi.adapter<List<VideoItem>>(
        Types.newParameterizedType(List::class.java, VideoItem::class.java)
    )
    private val detailAdapter = moshi.adapter(CrawlerVideoDetail::class.java)
    private val searchPageAdapter = moshi.adapter(SearchPageResult::class.java)

    /**
     * 已废弃：原本用于"模拟人类延迟"，现在统一由 [rateLimiter] 控制 3 秒最小间隔。
     * 保留空实现以减少改动面，调用点已被移除。
     */
    @Deprecated("由 RequestRateLimiter 统一限流，不再单独使用", ReplaceWith(""))
    private suspend fun humanDelay() {
        // no-op: 由 rateLimiter 统一管理请求间隔
    }

    /**
     * 获取首页视频列表。
     *
     * 缓存策略：
     * - 首页推荐/热播列表变化频率较低，缓存 1 天。
     * - 缓存内容为解析后的 `List<VideoItem>` JSON，命中后无需再次访问源站。
     */
    suspend fun fetchHomepageVideos(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchHomepageVideos 开始执行，URL: $HOME_URL")
        try {
            Log.d(TAG, "尝试读取缓存...")
            readVideoListCache(CACHE_KEY_HOME_LIST)?.let { cachedList ->
                if (cachedList.isNotEmpty()) {
                    Log.d(TAG, "首页视频列表缓存命中，共 ${cachedList.size} 条")
                    return@withContext Result.success(cachedList)
                }
            }
            Log.d(TAG, "缓存未命中，开始网络请求")
            Log.d(TAG, "准备请求文档: $HOME_URL")
            val doc = requestDocument(HOME_URL, RequestRateLimiter.Priority.SEARCH)
            Log.d(TAG, "页面获取成功，状态码: ${doc.location()}")
            val videoItems = parseHomepageVideos(doc)
            Log.d(TAG, "解析完成，共 ${videoItems.size} 条视频")
            writeVideoListCache(CACHE_KEY_HOME_LIST, videoItems, ApiCacheEntity.TTL_ONE_DAY)
            Result.success(videoItems)
        } catch (e: Exception) {
            Log.e(TAG, "爬取首页视频列表失败", e)
            e.printStackTrace()
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = SOURCE_TAG))
        }
    }

    /**
     * 根据详情页 URL 获取真实视频播放地址。
     *
     * 缓存策略：
     * - 详情页解析出的首个播放页链接相当于剧集列表/播放链接模板的一部分，缓存 1 天。
     * - 最终 `.m3u8` / `mp4` 真实播放地址可能带短时效 token，只缓存 30 分钟。
     * - 不按 1 天缓存播放页 HTML，避免把其中的短时效真实地址间接长期缓存。
     *
     * @param detailUrl 详情页地址，例如 https://www.******.com/voddetail/68381.html
     * @return 视频流地址（m3u8/mp4）
     */
    suspend fun fetchVideoUrl(detailUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (detailUrl.isBlank()) {
                return@withContext Result.failure(CrawlError(CrawlErrorType.EMPTY_RESULT, SOURCE_TAG, "详情页地址为空"))
            }

            Log.d(TAG, "开始解析详情页: $detailUrl")
            val playPageUrl = getFirstPlayPageUrl(detailUrl)
            fetchVideoUrlByPlayPageUrl(playPageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "获取视频地址失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = SOURCE_TAG))
        }
    }

    /**
     * 获取详情页完整信息：标题、封面、类型、上映时间、评分、导演、主演、简介、播放线路和集数。
     */
    suspend fun fetchVideoDetail(detailUrl: String): Result<CrawlerVideoDetail> = withContext(Dispatchers.IO) {
        try {
            if (detailUrl.isBlank()) {
                return@withContext Result.failure(CrawlError(CrawlErrorType.EMPTY_RESULT, SOURCE_TAG, "详情页地址为空"))
            }

            val cacheKey = cacheKey(CACHE_PREFIX_DETAIL_META, detailUrl)
            cacheRepository?.get(cacheKey)?.let { cachedJson ->
                try {
                    val cached = detailAdapter.fromJson(cachedJson)
                    if (cached != null) {
                        Log.d(TAG, "详情页元数据缓存命中: ${cached.title}, 线路=${cached.playLines.size}")
                        return@withContext Result.success(cached)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "详情页缓存解析失败，重新请求: ${t.message}")
                }
            }

            val doc = requestDocument(detailUrl, RequestRateLimiter.Priority.DETAIL)
            val detail = parseVideoDetail(doc, detailUrl)
            cacheRepository?.put(cacheKey, detailAdapter.toJson(detail), ApiCacheEntity.TTL_ONE_DAY)
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "获取详情页元数据失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = SOURCE_TAG))
        }
    }

    /**
     * 根据具体播放页 URL 获取真实播放地址，适用于用户点击某条线路下的某一集。
     */
    suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (playPageUrl.isBlank()) {
                return@withContext Result.failure(CrawlError(CrawlErrorType.EMPTY_RESULT, SOURCE_TAG, "播放页地址为空"))
            }

            val realUrlCacheKey = cacheKey(CACHE_PREFIX_REAL_VIDEO_URL, playPageUrl)
            cacheRepository?.get(realUrlCacheKey)?.let { cachedUrl ->
                if (cachedUrl.isNotBlank()) {
                    Log.d(TAG, "真实播放地址缓存命中（30分钟内）: ${cachedUrl.take(120)}")
                    return@withContext Result.success(cachedUrl)
                }
            }

            Log.d(TAG, "开始请求播放页提取真实地址: $playPageUrl")
            val playDoc = requestDocument(playPageUrl, RequestRateLimiter.Priority.PLAY)
            val scriptElement = playDoc.select("script:containsData(player_aaaa)").first()
            if (scriptElement == null) {
                Log.e(TAG, "未找到 player_aaaa 脚本")
                return@withContext Result.failure(CrawlError(CrawlErrorType.PARSE_ERROR, SOURCE_TAG, "未找到播放数据"))
            }

            val scriptContent = scriptElement.html()
            Log.d(TAG, "脚本片段: ${scriptContent.take(200)}")

            val videoUrl = extractRealVideoUrl(scriptContent)
            if (!videoUrl.isNullOrBlank()) {
                Log.d(TAG, "成功提取视频地址: $videoUrl")
                cacheRepository?.put(
                    realUrlCacheKey,
                    videoUrl,
                    ApiCacheEntity.TTL_THIRTY_MINUTES
                )
                Result.success(videoUrl)
            } else {
                Log.e(TAG, "未能提取视频地址")
                Result.failure(CrawlError(CrawlErrorType.PARSE_ERROR, SOURCE_TAG, "未找到视频地址"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取播放页真实地址失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = SOURCE_TAG))
        }
    }

    /**
     * 根据网页搜索页获取分页搜索结果。
     *
     * 示例：
     * https://www.******.com/vodsearch/-------------.html?wd=斗罗大陆
     */
    suspend fun searchVideos(keyword: String, page: Int = 1): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        try {
            val cleanKeyword = keyword.trim()
            if (cleanKeyword.isBlank()) {
                return@withContext Result.success(
                    SearchPageResult("", 1, 1, hasPrev = false, hasNext = false, items = emptyList())
                )
            }

            val safePage = page.coerceAtLeast(1)
            val cacheKey = searchCacheKey(cleanKeyword, safePage)
            val firstPageCacheKey = searchCacheKey(cleanKeyword, 1)
            val url = buildSearchUrl(cleanKeyword, safePage)
            Log.d(
                TAG,
                "搜索请求准备: keyword='$cleanKeyword', page=$safePage, url=$url, cacheKey=$cacheKey"
            )
            cacheRepository?.get(cacheKey)?.let { cachedJson ->
                try {
                    val cached = searchPageAdapter.fromJson(cachedJson)
                    if (cached != null) {
                        Log.d(
                            TAG,
                            "搜索结果缓存命中: keyword=$cleanKeyword, page=$safePage, " +
                                "size=${cached.items.size}, totalPages=${cached.totalPages}, " +
                                "hasPrev=${cached.hasPrev}, hasNext=${cached.hasNext}"
                        )
                        if (cached.items.isNotEmpty()) {
                            return@withContext Result.success(cached)
                        } else {
                            Log.w(TAG, "搜索缓存为空结果，强制失效并重新请求: key=$cacheKey, url=$url")
                            cacheRepository.invalidate(cacheKey)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "搜索结果缓存解析失败，重新请求: ${t.message}")
                    cacheRepository.invalidate(cacheKey)
                }
            }

            Log.d(TAG, "开始网络请求搜索页: $url")
            val doc = requestDocument(url, RequestRateLimiter.Priority.SEARCH)
            Log.d(
                TAG,
                "搜索页响应成功: requestedUrl=$url, finalLocation=${doc.location()}, " +
                    "title='${doc.title()}', htmlLength=${doc.outerHtml().length}"
            )
            val result = parseSearchPage(doc, cleanKeyword, safePage)
            Log.d(
                TAG,
                "搜索页解析完成: keyword=$cleanKeyword, page=$safePage, " +
                    "items=${result.items.size}, totalPages=${result.totalPages}, " +
                    "hasPrev=${result.hasPrev}, hasNext=${result.hasNext}"
            )
            if (result.items.isNotEmpty()) {
                val ttlSeconds = getSearchCacheTtlSeconds(
                    currentPage = safePage,
                    firstPageCacheKey = firstPageCacheKey
                )
                if (ttlSeconds > 0) {
                    cacheRepository?.put(cacheKey, searchPageAdapter.toJson(result), ttlSeconds)
                    Log.d(
                        TAG,
                        "搜索结果写入缓存: keyword=$cleanKeyword, page=$safePage, " +
                            "ttl=${ttlSeconds}s, firstPageKey=$firstPageCacheKey"
                    )
                } else {
                    Log.w(
                        TAG,
                        "搜索首页缓存已过期或无剩余时间，本页不写入缓存: " +
                            "keyword=$cleanKeyword, page=$safePage"
                    )
                }
            } else {
                Log.w(TAG, "搜索结果为空，本次不写入缓存，避免空结果挡住后续请求: url=$url")
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "搜索视频失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = SOURCE_TAG))
        }
    }

    private fun parseHomepageVideos(doc: Document): List<VideoItem> {
        val items = doc.select(".r-item")
        Log.d(TAG, "找到 .r-item 数量: ${items.size}")

        return items.mapNotNull { element ->
            parseHomepageItem(element)
        }
    }

    private fun parseHomepageItem(element: Element): VideoItem? {
        // 详情链接：.r-poster 或 .r-title 的 href
        val detailLink = element.select(".r-poster").first() ?: element.select(".r-title").first()
        val detailUrl = detailLink?.attr("abs:href")
            ?: element.select("a").attr("abs:href")
        if (detailUrl.isBlank()) return null

        val title = element.select(".r-title").text()
        if (title.isBlank()) return null

        // 封面：优先取 data-original，其次是 style 中的 background-image
        var coverUrl = element.select(".r-poster").attr("data-original")
        if (coverUrl.isBlank()) {
            val styleAttr = element.select(".r-poster").attr("style")
            coverUrl = Regex("background-image:url\\(([^)]+)\\)")
                .find(styleAttr)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        }
        if (coverUrl.isNotBlank() && coverUrl.startsWith("/")) {
            coverUrl = BASE_URL + coverUrl
        }

        return VideoItem(
            id = detailUrl.hashCode().toLong(),
            title = title,
            coverUrl = coverUrl,
            playUrl = "",
            category = "影视",
            detailUrl = detailUrl,
            rating = "",
            year = "",
            area = "",
            director = "",
            actors = "",
            description = ""
        )
    }

    private fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail {
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
            playLines = lines
        )
    }

    private fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult {
        val resultLinks = doc.select(".tList > a")
        val resultItems = doc.select(".tList > .item")
        val fallbackLinks = doc.select("a[href*=voddetail]")
        Log.d(
            TAG,
            "解析搜索页节点: keyword=$keyword, page=$page, " +
                ".tList>a=${resultLinks.size}, .tList>.item=${resultItems.size}, " +
                "a[href*=voddetail]=${fallbackLinks.size}, " +
                "searchTitle='${doc.select(".search-title").text().trim()}'"
        )

        if (resultItems.isEmpty()) {
            Log.w(
                TAG,
                "未找到 .tList > .item 搜索结果节点，HTML片段=${doc.select(".movie-main").outerHtml().take(500)}"
            )
        } else {
//            logLong("搜索结果容器HTML片段", doc.select(".tList").outerHtml())
            resultItems.take(5).forEachIndexed { index, item ->
                val firstDetailLink = item.select("a[href*=voddetail]").first()
                Log.d(
                    TAG,
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
                    TAG,
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
            if (coverUrl.startsWith("/")) coverUrl = BASE_URL + coverUrl

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
                TAG,
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
                description = description
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
            TAG,
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

    private fun logLong(label: String, content: String, chunkSize: Int = 1200) {
        if (content.isBlank()) {
            Log.d(TAG, "$label: <empty>")
            return
        }
        content.chunked(chunkSize).take(6).forEachIndexed { index, chunk ->
            Log.d(TAG, "$label [${index + 1}]: $chunk")
        }
    }

    /**
     * 从详情页解析首个播放页 URL。
     * 当前版本只播放第一集，因此缓存的是“详情页 → 第一集播放页”的映射。
     */
    private suspend fun getFirstPlayPageUrl(detailUrl: String): String {
        val cacheKey = cacheKey(CACHE_PREFIX_FIRST_PLAY_PAGE, detailUrl)
        cacheRepository?.get(cacheKey)?.let { cachedPlayPageUrl ->
            if (cachedPlayPageUrl.isNotBlank()) {
                Log.d(TAG, "首个播放页链接缓存命中: $cachedPlayPageUrl")
                return cachedPlayPageUrl
            }
        }

        val detail = fetchVideoDetail(detailUrl).getOrThrow()
        val playLink = detail.playLines.firstOrNull()?.episodes?.firstOrNull()
            ?: throw IOException("未找到播放链接")

        val playPageUrl = playLink.playPageUrl
        if (playPageUrl.isBlank()) {
            throw IOException("播放链接为空")
        }

        Log.d(TAG, "从详情页解析到首个播放页: $playPageUrl")
        cacheRepository?.put(cacheKey, playPageUrl, ApiCacheEntity.TTL_ONE_DAY)
        return playPageUrl
    }

    private fun extractRealVideoUrl(scriptContent: String): String? {
        // 优先提取 player_aaaa 中的 "url":"https://..."
        val urlRegex = Regex("\"url\":\"([^\"]+)\"")
        val videoUrl = urlRegex.find(scriptContent)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/")
        if (!videoUrl.isNullOrBlank()) return videoUrl

        // 备用方案：直接查找 .m3u8 链接
        val m3u8Regex = Regex("https?://[^\"]+\\.m3u8[^\"']*")
        return m3u8Regex.find(scriptContent)?.value?.replace("\\/", "/")
    }

    /**
     * 通过限流器调度的网络请求 + Jsoup 解析。
     *
     * 1. 通过 [rateLimiter] 排队：保证同源 3 秒最小间隔、最大 3 个并发；
     * 2. 使用 OkHttp 发起请求（注册 Call 以支持外部取消）；
     * 3. 收到响应后用 Jsoup 解析为 [Document]。
     */
    private suspend fun requestDocument(
        url: String,
        priority: RequestRateLimiter.Priority
    ): Document = rateLimiter.submit(priority, url) { handle ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "max-age=0")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .get()
            .build()

        val call = client.newCall(request)
        // 注册到限流器，使外部取消能直接 cancel 该 Call
        handle.registerCall(call)

        val response = call.execute()
        response.use { resp ->
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CrawlError(
                    type = when (code) {
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
                    detail = "HTTP $code for $url",
                    cause = null
                )
            }
            Jsoup.parse(body, url)
        }
    }

    private fun buildSearchUrl(keyword: String, page: Int): String {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        return if (page <= 1) {
            "$BASE_URL/vodsearch/-------------.html?wd=$encodedKeyword"
        } else {
            "$BASE_URL/vodsearch/$encodedKeyword----------$page---.html"
        }
    }

    private suspend fun getSearchCacheTtlSeconds(
        currentPage: Int,
        firstPageCacheKey: String
    ): Long {
        if (currentPage <= 1) {
            return ApiCacheEntity.TTL_ONE_DAY
        }

        val firstPageRemainingTtl = cacheRepository
            ?.getRemainingTtlSeconds(firstPageCacheKey)
            ?: ApiCacheEntity.TTL_ONE_DAY

        return firstPageRemainingTtl.coerceAtMost(ApiCacheEntity.TTL_ONE_DAY)
    }

    private fun searchCacheKey(keyword: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return "$CACHE_PREFIX_SEARCH:${keyword.hashCode()}:$safePage:$keyword"
    }

    private suspend fun readVideoListCache(cacheKey: String): List<VideoItem>? {
        val cachedJson = cacheRepository?.get(cacheKey) ?: return null
        return try {
            videoListAdapter.fromJson(cachedJson)
        } catch (t: Throwable) {
            Log.w(TAG, "视频列表缓存解析失败，忽略缓存: ${t.message}")
            null
        }
    }

    private suspend fun writeVideoListCache(
        cacheKey: String,
        list: List<VideoItem>,
        ttlSeconds: Long
    ) {
        if (list.isEmpty()) return
        try {
            cacheRepository?.put(cacheKey, videoListAdapter.toJson(list), ttlSeconds)
        } catch (t: Throwable) {
            Log.w(TAG, "写入视频列表缓存失败（不影响功能）: ${t.message}")
        }
    }

    private fun cacheKey(prefix: String, raw: String): String {
        return "$prefix:${raw.hashCode()}:$raw"
    }

    companion object {
        private const val TAG = "CrawlerVideoSource"
        const val SOURCE_TAG = "剧集屋"
        private const val BASE_URL = "https://www.******.com"
        private const val HOME_URL = "$BASE_URL/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        /** 首页视频列表：推荐位/热播榜，缓存 1 天 */
        private const val CACHE_KEY_HOME_LIST = "crawler:home:list"

        /** 详情页解析出的首个播放页链接：等价于当前的剧集播放模板，缓存 1 天 */
        private const val CACHE_PREFIX_FIRST_PLAY_PAGE = "crawler:detail:first_play_page"

        /** 详情页元数据与播放线路：标题、封面、导演、主演、简介、线路和集数，缓存 1 天 */
        private const val CACHE_PREFIX_DETAIL_META = "crawler:detail:meta"

        /** 播放页解析出的真实播放地址：短时效 token，缓存 30 分钟 */
        private const val CACHE_PREFIX_REAL_VIDEO_URL = "crawler:play:real_url"

        /** 搜索结果页：缓存 30 分钟；v3 用于刷新旧 1 天分页缓存 */
        private const val CACHE_PREFIX_SEARCH = "crawler:search:v3"
    }
}
