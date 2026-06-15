// VideoRepository.kt (修正后)
package com.hpu.mymoviestore.data.repository

import android.util.Log
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.DoubanMoviePageResult
import com.hpu.mymoviestore.data.model.SearchPageResult
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.source.CrawlerVideoSource
import com.hpu.mymoviestore.data.source.DoubanDiscoverySource
import com.hpu.mymoviestore.data.source.VideoSourceManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class VideoRepository(
    private val localSource: VideoSourceManager,
    private val crawlerSource: CrawlerVideoSource? = null,
    private val discoverySource: DoubanDiscoverySource? = null,
    private val cacheRepository: ApiCacheRepository? = null,
    private val preferCrawler: Boolean = false
) {
    private val TAG = "VideoRepository"
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val videoListAdapter = moshi.adapter<List<VideoItem>>(
        Types.newParameterizedType(List::class.java, VideoItem::class.java)
    )
    private val doubanMoviePageAdapter = moshi.adapter(DoubanMoviePageResult::class.java)

    suspend fun getAllVideos(): List<VideoItem> {
        Log.d(
            TAG,
            "getAllVideos: preferCrawler=$preferCrawler, " +
                "crawlerSource=${crawlerSource != null}, discoverySource=${discoverySource != null}"
        )

        getCachedHomeVideos(HOME_CACHE_KEY_ALL, "首页全部")?.let {
            return it
        }

        if (discoverySource != null) {
            val discoveryResult = discoverySource.fetchHomeAll()
            Log.d(
                TAG,
                "内容发现结果: isSuccess=${discoveryResult.isSuccess}, " +
                    "size=${discoveryResult.getOrNull()?.size}"
            )
            val discoveryList = discoveryResult.getOrNull()
            if (!discoveryList.isNullOrEmpty()) {
                Log.d(TAG, "首页全部使用豆瓣内容发现数据，共 ${discoveryList.size} 条")
                putHomeVideosCache(HOME_CACHE_KEY_ALL, discoveryList, "首页全部")
                return discoveryList
            }
            Log.w(TAG, "豆瓣内容发现为空，首页全部回退本地挡板，不写入缓存")
        }

        Log.d(TAG, "首页全部使用本地挡板数据，不写入 api_cache")
        return localSource.loadAllVideos()
    }

    suspend fun getVideosByCategory(category: String): List<VideoItem> {
        if (category == "电影") {
            val result = getDoubanMoviePage(type = "全部", start = 0)
            if (result.items.isNotEmpty()) {
                Log.d(TAG, "首页电影分栏使用豆瓣电影数据: size=${result.items.size}, total=${result.total}")
                return result.items
            }
            Log.w(TAG, "首页电影分栏豆瓣数据为空，回退本地挡板，不写入缓存")
        }

        if (category == "电视剧" || category == "动漫" || category == "综艺") {
            prewarmDoubanTvBundle()
            val result = getDoubanHomePage(category = category, subType = "综合", start = 0)
            if (result.items.isNotEmpty()) {
                Log.d(TAG, "首页$category 分栏使用豆瓣数据: size=${result.items.size}, total=${result.total}")
                return result.items
            }
            Log.w(TAG, "首页$category 分栏豆瓣数据为空，回退本地挡板，不写入缓存")
        }

        val cleanCategory = category.ifBlank { "全部" }
        Log.d(TAG, "首页分栏[$cleanCategory] 使用本地挡板数据，不读取/写入 api_cache")
        val list = localSource.loadVideosByCategory(category)
        return list
    }

    suspend fun getDoubanMoviePage(
        type: String,
        start: Int,
        limit: Int = DoubanDiscoverySource.EXPLORE_PAGE_LIMIT
    ): DoubanMoviePageResult {
        val cleanType = type.ifBlank { "全部" }
        val safeStart = start.coerceAtLeast(0)
        val cacheKey = doubanMovieCacheKey(cleanType, safeStart)
        getCachedDoubanMoviePage(cacheKey, cleanType, safeStart)?.let { return it }

        val emptyResult = DoubanMoviePageResult(cleanType, safeStart, limit, 0, emptyList())
        val result = discoverySource?.fetchExploreMoviePage(cleanType, safeStart, limit)?.getOrNull()
            ?: emptyResult

        if (result.items.isNotEmpty()) {
            val ttlSeconds = getDoubanMovieCacheTtlSeconds(cleanType, safeStart)
            if (ttlSeconds > 0) {
                cacheRepository?.put(cacheKey, doubanMoviePageAdapter.toJson(result), ttlSeconds)
                Log.d(
                    TAG,
                    "首页电影[$cleanType] 写入缓存: start=$safeStart, size=${result.items.size}, " +
                        "total=${result.total}, ttl=${ttlSeconds}s"
                )
            } else {
                Log.w(TAG, "首页电影[$cleanType] 首页缓存无剩余时间，本页不写缓存: start=$safeStart")
            }
        } else {
            Log.w(TAG, "首页电影[$cleanType] 结果为空，不写缓存: start=$safeStart")
        }

        return result
    }

    suspend fun getDoubanHomePage(
        category: String,
        subType: String,
        start: Int,
        limit: Int = DoubanDiscoverySource.EXPLORE_PAGE_LIMIT
    ): DoubanMoviePageResult {
        return if (category == "电影") {
            getDoubanMoviePage(subType, start, limit)
        } else {
            getDoubanTvRelatedPage(category, subType, start, limit)
        }
    }

    suspend fun prewarmDoubanTvBundle() {
        val targets = listOf("电视剧" to "综合", "动漫" to "综合", "综艺" to "综合")
        targets.forEach { (category, subType) ->
            val key = doubanTvRelatedCacheKey(category, subType, 0)
            val cached = getCachedDoubanMoviePage(key, "$category/$subType", 0)
            if (cached == null) {
                Log.d(TAG, "预缓存豆瓣首页$category: subType=$subType")
                getDoubanTvRelatedPage(category, subType, 0)
            }
        }
    }

    private suspend fun getDoubanTvRelatedPage(
        category: String,
        subType: String,
        start: Int,
        limit: Int = DoubanDiscoverySource.EXPLORE_PAGE_LIMIT
    ): DoubanMoviePageResult {
        val mapping = doubanTvRelatedMapping(category, subType)
        val safeStart = start.coerceAtLeast(0)
        val cacheKey = doubanTvRelatedCacheKey(category, subType, safeStart)
        getCachedDoubanMoviePage(cacheKey, "${category}/${mapping.displaySubType}", safeStart)?.let { return it }

        val emptyResult = DoubanMoviePageResult(mapping.displaySubType, safeStart, limit, 0, emptyList())
        val result = discoverySource
            ?.fetchExploreTvRelatedPage(
                pageCategory = mapping.pageCategory,
                type = mapping.apiType,
                displayCategory = category,
                start = safeStart,
                limit = limit
            )
            ?.getOrNull()
            ?: emptyResult

        if (result.items.isNotEmpty()) {
            val ttlSeconds = getDoubanTvRelatedCacheTtlSeconds(category, subType, safeStart)
            if (ttlSeconds > 0) {
                cacheRepository?.put(cacheKey, doubanMoviePageAdapter.toJson(result), ttlSeconds)
                Log.d(
                    TAG,
                    "首页$category[${mapping.displaySubType}] 写入缓存: start=$safeStart, " +
                        "size=${result.items.size}, total=${result.total}, ttl=${ttlSeconds}s"
                )
            } else {
                Log.w(TAG, "首页$category[${mapping.displaySubType}] 首页缓存无剩余时间，本页不写缓存: start=$safeStart")
            }
        } else {
            Log.w(TAG, "首页$category[${mapping.displaySubType}] 结果为空，不写缓存: start=$safeStart")
        }

        return result
    }

    suspend fun searchVideos(keyword: String): List<VideoItem> {
        return if (preferCrawler && crawlerSource != null) {
            crawlerSource.searchVideos(keyword, 1).getOrNull()?.items
                ?: localSource.searchVideos(keyword)
        } else {
            localSource.searchVideos(keyword)
        }
    }

    suspend fun searchVideosPage(keyword: String, page: Int): SearchPageResult {
        return if (preferCrawler && crawlerSource != null) {
            crawlerSource.searchVideos(keyword, page).getOrElse {
                SearchPageResult(
                    keyword = keyword,
                    page = page.coerceAtLeast(1),
                    totalPages = 1,
                    hasPrev = page > 1,
                    hasNext = false,
                    items = localSource.searchVideos(keyword)
                )
            }
        } else {
            SearchPageResult(
                keyword = keyword,
                page = 1,
                totalPages = 1,
                hasPrev = false,
                hasNext = false,
                items = localSource.searchVideos(keyword)
            )
        }
    }

    // 根据 ID 获取视频详情（优先从本地，本地没有则尝试爬虫）
    suspend fun getVideoById(id: Long): VideoItem? {
        val localVideo = localSource.getVideoById(id)
        if (localVideo != null && localVideo.playUrl.isNotBlank()) {
            return localVideo
        }

        // 如果本地没有有效的播放地址，且爬虫源可用
        if (preferCrawler && crawlerSource != null) {
            // 需要从 getAllVideos 中找到对应的详情页 URL（因为 VideoItem 没有 detailUrl 字段）
            // 临时方案：返回 null，让 UI 提示无法播放
            // 更好的方案：修改 VideoItem 增加 detailUrl 字段，或者在爬虫中维护一个 id -> detailUrl 的映射
            return null
        }
        return null
    }

    /**
     * 根据详情页 URL 获取完整视频信息（包含播放地址）
     * 这个方法供 DetailActivity 直接调用
     */
    suspend fun getVideoByDetailUrl(detailUrl: String): VideoItem? {
        if (!preferCrawler || crawlerSource == null) return null
        val playUrlResult = crawlerSource.fetchVideoUrl(detailUrl)
        if (playUrlResult.isSuccess) {
            val playUrl = playUrlResult.getOrNull() ?: return null
            return VideoItem(
                id = detailUrl.hashCode().toLong(),
                title = "",  // 可以留空，或者额外解析标题
                coverUrl = "",
                category = "",
                rating = "",
                year = "",
                area = "",
                director = "",
                actors = "",
                description = "",
                playUrl = playUrl,
                detailUrl = detailUrl
            )
        }
        return null
    }

    suspend fun getCrawlerVideoDetail(detailUrl: String): CrawlerVideoDetail? {
        if (!preferCrawler || crawlerSource == null) return null
        return crawlerSource.fetchVideoDetail(detailUrl).getOrNull()
    }

    suspend fun getRealPlayUrlByPlayPageUrl(playPageUrl: String): String? {
        if (!preferCrawler || crawlerSource == null) return null
        return crawlerSource.fetchVideoUrlByPlayPageUrl(playPageUrl).getOrNull()
    }

    private suspend fun getCachedHomeVideos(cacheKey: String, label: String): List<VideoItem>? {
        val cachedJson = cacheRepository?.get(cacheKey) ?: return null
        return try {
            val cachedList = videoListAdapter.fromJson(cachedJson)
            if (!cachedList.isNullOrEmpty()) {
                Log.d(TAG, "$label 缓存命中: key=$cacheKey, size=${cachedList.size}")
                cachedList
            } else {
                Log.w(TAG, "$label 缓存为空，忽略缓存: key=$cacheKey")
                cacheRepository.invalidate(cacheKey)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label 缓存解析失败，忽略缓存: key=$cacheKey, error=${t.message}")
            cacheRepository.invalidate(cacheKey)
            null
        }
    }

    private suspend fun putHomeVideosCache(cacheKey: String, list: List<VideoItem>, label: String) {
        if (list.isEmpty()) {
            Log.w(TAG, "$label 结果为空，不写入首页缓存: key=$cacheKey")
            return
        }
        cacheRepository?.put(cacheKey, videoListAdapter.toJson(list), ApiCacheEntity.TTL_ONE_DAY)
        Log.d(TAG, "$label 写入首页缓存: key=$cacheKey, size=${list.size}, ttl=${ApiCacheEntity.TTL_ONE_DAY}s")
    }

    private suspend fun getCachedDoubanMoviePage(
        cacheKey: String,
        type: String,
        start: Int
    ): DoubanMoviePageResult? {
        val cachedJson = cacheRepository?.get(cacheKey) ?: return null
        return try {
            val cached = doubanMoviePageAdapter.fromJson(cachedJson)
            if (cached != null && cached.items.isNotEmpty()) {
                Log.d(
                    TAG,
                    "首页电影[$type] 缓存命中: start=$start, size=${cached.items.size}, total=${cached.total}"
                )
                cached
            } else {
                Log.w(TAG, "首页电影[$type] 缓存为空，忽略: start=$start")
                cacheRepository.invalidate(cacheKey)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "首页电影[$type] 缓存解析失败，忽略: start=$start, error=${t.message}")
            cacheRepository.invalidate(cacheKey)
            null
        }
    }

    private suspend fun getDoubanMovieCacheTtlSeconds(type: String, start: Int): Long {
        if (start <= 0) return ApiCacheEntity.TTL_ONE_DAY
        val firstPageKey = doubanMovieCacheKey(type, 0)
        return cacheRepository
            ?.getRemainingTtlSeconds(firstPageKey)
            ?.coerceAtMost(ApiCacheEntity.TTL_ONE_DAY)
            ?: ApiCacheEntity.TTL_ONE_DAY
    }

    private suspend fun getDoubanTvRelatedCacheTtlSeconds(category: String, subType: String, start: Int): Long {
        val baseBundleKey = doubanTvRelatedCacheKey("电视剧", "综合", 0)
        val firstPageKey = doubanTvRelatedCacheKey(category, subType, 0)
        if (start <= 0) {
            if (firstPageKey == baseBundleKey) return ApiCacheEntity.TTL_ONE_DAY
            return cacheRepository
                ?.getRemainingTtlSeconds(baseBundleKey)
                ?.coerceAtMost(ApiCacheEntity.TTL_ONE_DAY)
                ?: ApiCacheEntity.TTL_ONE_DAY
        }
        return cacheRepository
            ?.getRemainingTtlSeconds(firstPageKey)
            ?.coerceAtMost(ApiCacheEntity.TTL_ONE_DAY)
            ?: ApiCacheEntity.TTL_ONE_DAY
    }

    private fun doubanMovieCacheKey(type: String, start: Int): String {
        return "$HOME_CACHE_KEY_MOVIE_PREFIX${type.ifBlank { "全部" }}:start:${start.coerceAtLeast(0)}"
    }

    private fun doubanTvRelatedCacheKey(category: String, subType: String, start: Int): String {
        return "$HOME_CACHE_KEY_TV_RELATED_PREFIX$category:${subType.ifBlank { "综合" }}:start:${start.coerceAtLeast(0)}"
    }

    private fun doubanTvRelatedMapping(category: String, subType: String): DoubanTvRelatedMapping {
        return when (category) {
            "电视剧" -> when (subType.ifBlank { "综合" }) {
                "国产剧" -> DoubanTvRelatedMapping("tv", "tv_domestic", "国产剧")
                "欧美剧" -> DoubanTvRelatedMapping("tv", "tv_american", "欧美剧")
                "日剧" -> DoubanTvRelatedMapping("tv", "tv_japanese", "日剧")
                "韩剧" -> DoubanTvRelatedMapping("tv", "tv_korean", "韩剧")
                "纪录片" -> DoubanTvRelatedMapping("tv", "tv_documentary", "纪录片")
                else -> DoubanTvRelatedMapping("tv", "tv", "综合")
            }
            "动漫" -> DoubanTvRelatedMapping("tv", "tv_animation", "综合")
            "综艺" -> when (subType.ifBlank { "综合" }) {
                "国内" -> DoubanTvRelatedMapping("show", "show_domestic", "国内")
                "国外" -> DoubanTvRelatedMapping("show", "show_foreign", "国外")
                else -> DoubanTvRelatedMapping("show", "show", "综合")
            }
            else -> DoubanTvRelatedMapping("tv", "tv", "综合")
        }
    }

    private data class DoubanTvRelatedMapping(
        val pageCategory: String,
        val apiType: String,
        val displaySubType: String
    )

    companion object {
        private const val HOME_CACHE_KEY_ALL = "home:tab:all:v1"
        private const val HOME_CACHE_KEY_MOVIE_PREFIX = "home:tab:movie:v1:"
        private const val HOME_CACHE_KEY_TV_RELATED_PREFIX = "home:tab:tv_related:v1:"
    }
}
