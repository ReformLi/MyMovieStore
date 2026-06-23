package com.hpu.mymoviestore.data.source

import android.util.Log
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import com.hpu.mymoviestore.data.model.CrawlError
import com.hpu.mymoviestore.data.model.CrawlErrorType
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.SearchPageResult
import com.hpu.mymoviestore.data.model.toCrawlError
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 爬虫视频源抽象基类。
 *
 * 提取所有通用逻辑（网络请求、缓存、限流、搜索/详情/播放页处理），
 * 子类只需实现 [parseVideoDetail] 和 [parseSearchPage] 两个解析方法，
 * 并提供源相关的元数据属性。
 */
abstract class CrawlerVideoSource(
    private val client: OkHttpClient = defaultClient(),
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
) : VideoSource {

    override var enabled: Boolean = true

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val detailAdapter = moshi.adapter(CrawlerVideoDetail::class.java)
    private val searchPageAdapter = moshi.adapter(SearchPageResult::class.java)

    // ========== 子类必须实现的抽象属性 ==========

    abstract override val sourceId: String
    abstract override val sourceName: String
    abstract val baseUrl: String
    abstract val cachePrefix: String
    abstract val rateLimiterTag: String
    abstract val logTag: String

    // ========== 子类必须实现的抽象方法 ==========

    /** 解析详情页 HTML → [CrawlerVideoDetail] */
    abstract fun parseVideoDetail(doc: Document, detailUrl: String): CrawlerVideoDetail

    /** 解析搜索页 HTML → [SearchPageResult] */
    abstract fun parseSearchPage(doc: Document, keyword: String, page: Int): SearchPageResult

    // ========== VideoSource 接口实现 ==========

    /**
     * 根据详情页 URL 获取真实视频播放地址。
     */
    override suspend fun fetchVideoUrl(detailUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (detailUrl.isBlank()) {
                return@withContext Result.failure(CrawlError(CrawlErrorType.EMPTY_RESULT, sourceName, "详情页地址为空"))
            }

            Log.d(logTag, "开始解析详情页: $detailUrl")
            val playPageUrl = getFirstPlayPageUrl(detailUrl)
            fetchVideoUrlByPlayPageUrl(playPageUrl)
        } catch (e: Exception) {
            Log.e(logTag, "获取视频地址失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = sourceName))
        }
    }

    /**
     * 获取详情页完整信息：标题、封面、类型、上映时间、评分、导演、主演、简介、播放线路和集数。
     */
    override suspend fun fetchVideoDetail(detailUrl: String): Result<CrawlerVideoDetail> = withContext(Dispatchers.IO) {
        try {
            if (detailUrl.isBlank()) {
                return@withContext Result.failure(CrawlError(CrawlErrorType.EMPTY_RESULT, sourceName, "详情页地址为空"))
            }

            val cacheKey = cacheKey("$cachePrefix$cachePrefixDetailMeta", detailUrl)
            cacheRepository?.get(cacheKey)?.let { cachedJson ->
                try {
                    val cached = detailAdapter.fromJson(cachedJson)
                    if (cached != null) {
                        Log.d(logTag, "详情页元数据缓存命中: ${cached.title}, 线路=${cached.playLines.size}")
                        return@withContext Result.success(cached)
                    }
                } catch (t: Throwable) {
                    Log.w(logTag, "详情页缓存解析失败，重新请求: ${t.message}")
                }
            }

            val doc = requestDocument(detailUrl, RequestRateLimiter.Priority.DETAIL)
            val detail = parseVideoDetail(doc, detailUrl)
            cacheRepository?.put(cacheKey, detailAdapter.toJson(detail), ApiCacheEntity.TTL_ONE_DAY)
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(logTag, "获取详情页元数据失败", e)
            Result.failure((e as? CrawlError) ?: e.toCrawlError(source = sourceName))
        }
    }

    /**
     * 根据具体播放页 URL 获取真实播放地址，适用于用户点击某条线路下的某一集。
     */
    override suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (playPageUrl.isBlank()) {
                return@withContext Result.failure(CrawlError(CrawlErrorType.EMPTY_RESULT, sourceName, "播放页地址为空"))
            }

            val realUrlCacheKey = cacheKey("$cachePrefix$cachePrefixRealVideoUrl", playPageUrl)
            cacheRepository?.get(realUrlCacheKey)?.let { cachedUrl ->
                if (cachedUrl.isNotBlank()) {
                    Log.d(logTag, "真实播放地址缓存命中（30分钟内）: ${cachedUrl.take(120)}")
                    return@withContext Result.success(cachedUrl)
                }
            }

            Log.d(logTag, "开始请求播放页提取真实地址: $playPageUrl")
            val playDoc = requestDocument(playPageUrl, RequestRateLimiter.Priority.PLAY)
            val scriptElement = playDoc.select("script:containsData(player_aaaa)").first()
            if (scriptElement == null) {
                Log.e(logTag, "未找到 player_aaaa 脚本")
                return@withContext Result.failure(CrawlError(CrawlErrorType.PARSE_ERROR, sourceName, "未找到播放数据"))
            }

            val scriptContent = scriptElement.html()
            Log.d(logTag, "脚本片段: ${scriptContent.take(200)}")

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

    /**
     * 根据网页搜索页获取分页搜索结果。
     */
    override suspend fun searchVideos(keyword: String, page: Int): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) {
            return@withContext Result.success(
                SearchPageResult("", 1, 1, hasPrev = false, hasNext = false, items = emptyList())
            )
        }
        val safePage = page.coerceAtLeast(1)
        val cacheKey = searchCacheKey(cleanKeyword, safePage)
        val firstPageCacheKey = searchCacheKey(cleanKeyword, 1)
        try {
            val url = buildSearchUrl(cleanKeyword, safePage)
            Log.d(
                logTag,
                "搜索请求准备: keyword='$cleanKeyword', page=$safePage, url=$url, cacheKey=$cacheKey"
            )
            cacheRepository?.get(cacheKey)?.let { cachedJson ->
                // 负缓存命中：跳过该源，不发网络请求
                if (cachedJson.startsWith(NEG_CACHE_PREFIX)) {
                    val negType = cachedJson.removePrefix(NEG_CACHE_PREFIX)
                    Log.w(
                        logTag,
                        "负缓存命中，跳过该源: keyword=$cleanKeyword, page=$safePage, type=$negType"
                    )
                    return@withContext when (negType) {
                        NEG_TYPE_EMPTY -> Result.success(
                            SearchPageResult("", 1, 1, hasPrev = false, hasNext = false, items = emptyList())
                        )
                        else -> Result.failure(
                            CrawlError(CrawlErrorType.UNKNOWN, sourceName, "负缓存命中: $negType")
                        )
                    }
                }
                // 正常缓存命中
                try {
                    val cached = searchPageAdapter.fromJson(cachedJson)
                    if (cached != null) {
                        Log.d(
                            logTag,
                            "搜索结果缓存命中: keyword=$cleanKeyword, page=$safePage, " +
                                "size=${cached.items.size}, totalPages=${cached.totalPages}, " +
                                "hasPrev=${cached.hasPrev}, hasNext=${cached.hasNext}"
                        )
                        if (cached.items.isNotEmpty()) {
                            // sourceName 不缓存，缓存命中后需要补上
                            val fixed = cached.copy(
                                items = cached.items.map { it.copy(sourceName = this@CrawlerVideoSource.sourceName) }
                            )
                            return@withContext Result.success(fixed)
                        } else {
                            Log.w(logTag, "搜索缓存为空结果，强制失效并重新请求: key=$cacheKey, url=$url")
                            cacheRepository.invalidate(cacheKey)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(logTag, "搜索结果缓存解析失败，重新请求: ${t.message}")
                    cacheRepository.invalidate(cacheKey)
                }
            }

            Log.d(logTag, "开始网络请求搜索页: $url")
            val doc = requestDocument(url, RequestRateLimiter.Priority.SEARCH)
            Log.d(
                logTag,
                "搜索页响应成功: requestedUrl=$url, finalLocation=${doc.location()}, " +
                    "title='${doc.title()}', htmlLength=${doc.outerHtml().length}"
            )
            val result = parseSearchPage(doc, cleanKeyword, safePage)
            Log.d(
                logTag,
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
                        logTag,
                        "搜索结果写入缓存: keyword=$cleanKeyword, page=$safePage, " +
                            "ttl=${ttlSeconds}s, firstPageKey=$firstPageCacheKey"
                    )
                } else {
                    Log.w(
                        logTag,
                        "搜索首页缓存已过期或无剩余时间，本页不写入缓存: " +
                            "keyword=$cleanKeyword, page=$safePage"
                    )
                }
            } else {
                // 空结果：写入负缓存（1 天），下次直接跳过该源
                Log.w(logTag, "搜索结果为空，写入负缓存（1天）: keyword=$cleanKeyword, url=$url")
                cacheRepository?.put(
                    cacheKey,
                    "$NEG_CACHE_PREFIX$NEG_TYPE_EMPTY",
                    ApiCacheEntity.TTL_ONE_DAY
                )
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(logTag, "搜索视频失败", e)
            // 根据错误类型写入对应 TTL 的负缓存，避免反复发网络请求
            val crawlError = (e as? CrawlError) ?: e.toCrawlError(source = sourceName)
            val (negType, negTtl) = when (crawlError.type) {
                CrawlErrorType.SERVER_ERROR ->
                    NEG_TYPE_SERVER_ERROR to ApiCacheEntity.TTL_ONE_HOUR
                CrawlErrorType.CLIENT_ERROR, CrawlErrorType.FORBIDDEN ->
                    NEG_TYPE_CLIENT_ERROR to ApiCacheEntity.TTL_ONE_DAY
                CrawlErrorType.TIMEOUT, CrawlErrorType.NETWORK_ERROR, CrawlErrorType.DNS_FAILURE ->
                    NEG_TYPE_TIMEOUT to ApiCacheEntity.TTL_ONE_HOUR
                else -> null to 0L
            }
            if (negType != null && negTtl > 0) {
                try {
                    cacheRepository?.put(
                        cacheKey,
                        "$NEG_CACHE_PREFIX$negType",
                        negTtl
                    )
                    Log.w(
                        logTag,
                        "写入负缓存: keyword=$keyword, type=$negType, ttl=${negTtl}s"
                    )
                } catch (cacheEx: Exception) {
                    Log.w(logTag, "写入负缓存失败（不影响主流程）: ${cacheEx.message}")
                }
            }
            Result.failure(crawlError)
        }
    }

    // ========== 通用工具方法（open，允许子类覆盖） ==========

    /**
     * 从详情页解析首个播放页 URL。
     * 当前版本只播放第一集，因此缓存的是"详情页 → 第一集播放页"的映射。
     */
    protected open suspend fun getFirstPlayPageUrl(detailUrl: String): String {
        val cacheKey = cacheKey("$cachePrefix$cachePrefixFirstPlayPage", detailUrl)
        cacheRepository?.get(cacheKey)?.let { cachedPlayPageUrl ->
            if (cachedPlayPageUrl.isNotBlank()) {
                Log.d(logTag, "首个播放页链接缓存命中: $cachedPlayPageUrl")
                return cachedPlayPageUrl
            }
        }

        val detail = fetchVideoDetail(detailUrl).getOrThrow()
        val playLink = detail.playLines.firstOrNull()?.episodes?.firstOrNull()
            ?: throw java.io.IOException("未找到播放链接")

        val playPageUrl = playLink.playPageUrl
        if (playPageUrl.isBlank()) {
            throw java.io.IOException("播放链接为空")
        }

        Log.d(logTag, "从详情页解析到首个播放页: $playPageUrl")
        cacheRepository?.put(cacheKey, playPageUrl, ApiCacheEntity.TTL_ONE_DAY)
        return playPageUrl
    }

    /**
     * 从播放页脚本内容中提取真实视频地址。
     */
    protected open fun extractRealVideoUrl(scriptContent: String): String? {
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
     */
    protected open suspend fun requestDocument(
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
                    source = sourceName,
                    detail = "HTTP $code for $url",
                    cause = null
                )
            }
            Jsoup.parse(body, url)
        }
    }

    /**
     * 构建搜索 URL。
     */
    protected open fun buildSearchUrl(keyword: String, page: Int): String {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        return if (page <= 1) {
            "$baseUrl/vodsearch/-------------.html?wd=$encodedKeyword"
        } else {
            "$baseUrl/vodsearch/$encodedKeyword----------$page---.html"
        }
    }

    /**
     * 计算搜索缓存 TTL（秒）。
     */
    protected open suspend fun getSearchCacheTtlSeconds(
        currentPage: Int,
        firstPageCacheKey: String
    ): Long {
        // 测试用：搜索结果缓存 10 秒
        return ApiCacheEntity.TTL_ONE_DAY
    }

    /**
     * 搜索缓存 key。
     * 注意：必须包含 cachePrefix（源标识），否则多源搜索结果会互相覆盖。
     */
    protected open fun searchCacheKey(keyword: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return "$cachePrefix$cachePrefixSearch:${keyword.hashCode()}:$safePage:$keyword"
    }

    /**
     * 通用缓存 key。
     */
    protected open fun cacheKey(prefix: String, raw: String): String {
        return "$prefix:${raw.hashCode()}:$raw"
    }

    /**
     * 长日志分块输出。
     */
    protected open fun logLong(label: String, content: String, chunkSize: Int = 1200) {
        if (content.isBlank()) {
            Log.d(logTag, "$label: <empty>")
            return
        }
        content.chunked(chunkSize).take(6).forEachIndexed { index, chunk ->
            Log.d(logTag, "$label [${index + 1}]: $chunk")
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        /** 详情页解析出的首个播放页链接：等价于当前的剧集播放模板，缓存 1 天 */
        private const val cachePrefixFirstPlayPage = ":detail:first_play_page"

        /** 详情页元数据与播放线路：标题、封面、导演、主演、简介、线路和集数，缓存 1 天 */
        private const val cachePrefixDetailMeta = ":detail:meta"

        /** 播放页解析出的真实播放地址：短时效 token，缓存 30 分钟 */
        private const val cachePrefixRealVideoUrl = ":play:real_url"

        /** 搜索结果页：缓存 30 分钟；v3 用于刷新旧 1 天分页缓存 */
        private const val cachePrefixSearch = ":search:v3"

        // ── 负缓存（Negative Cache）──────────────────────────────────────────
        /** 负缓存 payload 前缀，用于与正常 JSON 区分 */
        private const val NEG_CACHE_PREFIX = "__NEG__:"

        /** 负缓存类型：搜索结果为空（TTL 1 天） */
        private const val NEG_TYPE_EMPTY = "EMPTY"

        /** 负缓存类型：HTTP 5xx 服务器错误（TTL 1 小时） */
        private const val NEG_TYPE_SERVER_ERROR = "SERVER_ERROR"

        /** 负缓存类型：HTTP 4xx 客户端错误（TTL 1 天） */
        private const val NEG_TYPE_CLIENT_ERROR = "CLIENT_ERROR"

        /** 负缓存类型：连接超时 / 网络不可达（TTL 1 小时） */
        private const val NEG_TYPE_TIMEOUT = "TIMEOUT"
        // ────────────────────────────────────────────────────────────────────

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
