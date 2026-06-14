package com.hpu.mymoviestore.data.source

import android.util.Log
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
    private val cacheRepository: ApiCacheRepository? = null
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val videoListAdapter = moshi.adapter<List<VideoItem>>(
        Types.newParameterizedType(List::class.java, VideoItem::class.java)
    )
    private val detailAdapter = moshi.adapter(CrawlerVideoDetail::class.java)

    /** 模拟人类延迟，避免请求过快；缓存命中时不会执行该延迟 */
    private suspend fun humanDelay() {
        delay(Random.nextLong(1500, 3500))
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
            humanDelay()
            Log.d(TAG, "准备请求文档: $HOME_URL")
            val doc = requestDocument(HOME_URL)
            Log.d(TAG, "页面获取成功，状态码: ${doc.location()}")
            val videoItems = parseHomepageVideos(doc)
            Log.d(TAG, "解析完成，共 ${videoItems.size} 条视频")
            writeVideoListCache(CACHE_KEY_HOME_LIST, videoItems, ApiCacheEntity.TTL_ONE_DAY)
            Result.success(videoItems)
        } catch (e: Exception) {
            Log.e(TAG, "爬取首页视频列表失败", e)
            e.printStackTrace()
            Result.failure(e)
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
                return@withContext Result.failure(IOException("详情页地址为空"))
            }

            Log.d(TAG, "开始解析详情页: $detailUrl")
            val playPageUrl = getFirstPlayPageUrl(detailUrl)
            fetchVideoUrlByPlayPageUrl(playPageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "获取视频地址失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取详情页完整信息：标题、封面、类型、上映时间、评分、导演、主演、简介、播放线路和集数。
     */
    suspend fun fetchVideoDetail(detailUrl: String): Result<CrawlerVideoDetail> = withContext(Dispatchers.IO) {
        try {
            if (detailUrl.isBlank()) {
                return@withContext Result.failure(IOException("详情页地址为空"))
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

            humanDelay()
            val doc = requestDocument(detailUrl)
            val detail = parseVideoDetail(doc, detailUrl)
            cacheRepository?.put(cacheKey, detailAdapter.toJson(detail), ApiCacheEntity.TTL_ONE_DAY)
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "获取详情页元数据失败", e)
            Result.failure(e)
        }
    }

    /**
     * 根据具体播放页 URL 获取真实播放地址，适用于用户点击某条线路下的某一集。
     */
    suspend fun fetchVideoUrlByPlayPageUrl(playPageUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (playPageUrl.isBlank()) {
                return@withContext Result.failure(IOException("播放页地址为空"))
            }

            val realUrlCacheKey = cacheKey(CACHE_PREFIX_REAL_VIDEO_URL, playPageUrl)
            cacheRepository?.get(realUrlCacheKey)?.let { cachedUrl ->
                if (cachedUrl.isNotBlank()) {
                    Log.d(TAG, "真实播放地址缓存命中（30分钟内）: ${cachedUrl.take(120)}")
                    return@withContext Result.success(cachedUrl)
                }
            }

            Log.d(TAG, "开始请求播放页提取真实地址: $playPageUrl")
            humanDelay()
            val playDoc = requestDocument(playPageUrl)
            val scriptElement = playDoc.select("script:containsData(player_aaaa)").first()
            if (scriptElement == null) {
                Log.e(TAG, "未找到 player_aaaa 脚本")
                return@withContext Result.failure(IOException("未找到播放数据"))
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
                Result.failure(IOException("未找到视频地址"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取播放页真实地址失败", e)
            Result.failure(e)
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

        humanDelay()
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

    private fun requestDocument(url: String): Document {
        return Jsoup.connect(url)
            .timeout(15000)
            .userAgent(USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "max-age=0")
            .header("Connection", "keep-alive")
            .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            // Cookie 一般不需要硬编码，让会话自动管理即可，除非网站需要登录态
            // .header("Cookie", "your_cookie_here")
            .get()
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
    }
}
