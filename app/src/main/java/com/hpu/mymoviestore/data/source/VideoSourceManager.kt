package com.hpu.mymoviestore.data.source

import android.content.Context
import android.util.Log
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.data.model.remote.RemoteVideoResponse
import com.hpu.mymoviestore.data.model.remote.toVideoItemList
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * 视频数据源管理器
 *
 * 数据来源：assets JSON 挡板
 * 缓存机制：Room api_cache（TTL，默认 1 天 = ApiCacheEntity.TTL_ONE_DAY）
 *
 * 流程：
 * 1. 首次调用 loadAllVideos() → api_cache 无有效缓存 → 读取 assets JSON → 解析 → 写入 api_cache → 交付
 * 2. 后续在 TTL 有效期内调用 → 直接从 api_cache 读取（无需解析 assets）
 * 3. 超出 TTL → 自动重新读取并刷新缓存
 *
 * 内存缓存 (cachedVideos)：进程内最近一次解析结果，避免同生命周期内重复反序列化
 */
class VideoSourceManager(
    private val context: Context,
    private val cacheRepository: ApiCacheRepository? = null
) {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(RemoteVideoResponse::class.java)

    /** 内存缓存：最近一次解析结果；null = 未初始化 */
    @Volatile
    private var cachedVideos: List<VideoItem>? = null

    /**
     * 获取全量视频列表
     * 步骤：内存缓存 → Room api_cache 缓存 → assets JSON（重新抓取）
     */
    suspend fun loadAllVideos(): List<VideoItem> {
        // 1) 内存缓存
        val memory = cachedVideos
        if (!memory.isNullOrEmpty()) {
            Log.d(TAG, "使用内存缓存视频列表: ${memory.size} 条")
            return memory
        }

        // 2) Room api_cache 缓存（TTL 生效）
        if (cacheRepository != null) {
            val cachedJson = cacheRepository.get(CACHE_KEY_ALL_VIDEOS)
            if (cachedJson != null) {
                val list = try {
                    val resp = adapter.fromJson(cachedJson) ?: RemoteVideoResponse()
                    resp.list.toVideoItemList()
                } catch (t: Throwable) {
                    Log.w(TAG, "缓存 JSON 解析失败，回退到 assets 读取: ${t.message}")
                    emptyList()
                }
                if (list.isNotEmpty()) {
                    Log.d(TAG, "api_cache 命中并解析成功: ${list.size} 条")
                    cachedVideos = list
                    return list
                }
            }
        } else {
            Log.d(TAG, "未启用 api_cache（cacheRepository 为 null）")
        }

        // 3) 真正读取 assets JSON（解析 + 写入 api_cache）
        Log.d(TAG, "从 assets 读取视频源: $DEFAULT_ASSET_FILE")
        val jsonString = readJsonFromAssets(DEFAULT_ASSET_FILE)
        Log.d(TAG, "读取 JSON 成功: ${jsonString.length} 字符")

        val response = parseJson(jsonString)
        Log.d(TAG, "解析结果: page=${response.page}, total=${response.total}, list=${response.list.size}")

        val list = response.list.toVideoItemList()
        Log.d(TAG, "映射为 VideoItem，共 ${list.size} 条")
        list.forEachIndexed { idx, it ->
            Log.d(
                TAG,
                "  [${idx + 1}] id=${it.id}, title=${it.title}, " +
                    "category=${it.category}, " +
                    "playUrl=${if (it.playUrl.isNotEmpty()) it.playUrl.take(40) + "..." else "(空)"}"
            )
        }

        // 写入 api_cache（TTL = 1 天）
        if (cacheRepository != null) {
            try {
                cacheRepository.put(
                    CACHE_KEY_ALL_VIDEOS,
                    jsonString,
                    ApiCacheEntity.TTL_ONE_DAY
                )
            } catch (t: Throwable) {
                Log.w(TAG, "写入 api_cache 失败（不影响功能）: ${t.message}")
            }
        }

        cachedVideos = list
        Log.d(TAG, "========== 数据源加载完成，已写入内存 + Room 缓存 ==========\n")
        return list
    }

    /** 按分类过滤视频列表（分类判断完全来自内存缓存，不另存） */
    suspend fun loadVideosByCategory(category: String): List<VideoItem> {
        val all = loadAllVideos()
        return if (category.isBlank()) {
            all
        } else {
            all.filter { it.category.equals(category, ignoreCase = true) }
        }.also { filtered ->
            Log.d(TAG, "按分类过滤: '$category' → ${filtered.size} 条")
        }
    }

    /** 关键字搜索（标题/演员/导演/简介） */
    suspend fun searchVideos(keyword: String): List<VideoItem> {
        val all = loadAllVideos()
        val kw = keyword.trim().lowercase()
        return if (kw.isEmpty()) {
            emptyList()
        } else {
            all.filter {
                it.title.lowercase().contains(kw) ||
                    it.actors.lowercase().contains(kw) ||
                    it.director.lowercase().contains(kw) ||
                    it.description.lowercase().contains(kw)
            }
        }.also { result ->
            Log.d(TAG, "搜索关键字: '$keyword' → ${result.size} 条")
        }
    }

    /** 根据视频 id 找到单条视频信息 */
    suspend fun getVideoById(id: Long): VideoItem? {
        return loadAllVideos().firstOrNull { it.id == id }
            .also { item ->
                if (item == null) {
                    Log.w(TAG, "getVideoById(id=$id) → 未找到")
                } else {
                    Log.d(TAG, "getVideoById(id=$id) → ${item.title}")
                }
            }
    }

    /**
     * 清空所有缓存（内存 + api_cache）
     * 用于调试或"强制刷新"场景。
     */
    suspend fun clearCache() {
        cachedVideos = null
        Log.d(TAG, "内存缓存已清除")
        cacheRepository?.invalidate(CACHE_KEY_ALL_VIDEOS)
        Log.d(TAG, "api_cache[$CACHE_KEY_ALL_VIDEOS] 已失效")
    }

    private fun readJsonFromAssets(fileName: String): String {
        val startTime = System.currentTimeMillis()
        val content = context.assets.open(fileName).bufferedReader().use { it.readText() }
        Log.d(TAG, "读取文件耗时: ${System.currentTimeMillis() - startTime}ms")
        return content
    }

    private fun parseJson(jsonString: String): RemoteVideoResponse {
        val startTime = System.currentTimeMillis()
        val response = adapter.fromJson(jsonString) ?: RemoteVideoResponse()
        Log.d(TAG, "Moshi 解析耗时: ${System.currentTimeMillis() - startTime}ms")
        return response
    }

    companion object {
        private const val TAG = "VideoSourceManager"
        private const val DEFAULT_ASSET_FILE = "sample_video_source.json"
        private const val CACHE_KEY_ALL_VIDEOS = "json:assets:sample_video_source.json"
    }
}
