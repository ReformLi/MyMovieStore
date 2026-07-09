package com.hpu.mymoviestore.data.source

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import dalvik.system.DexFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 视频源远程配置管理器。
 *
 * ## 核心职责
 * 1. 从远程 JSON 获取播放源配置（sourceId → name/baseUrl）
 * 2. 动态构建/更新 [VideoSource] 实例列表
 * 3. 本地缓存管理，确保离线可用
 *
 * ## 启动策略
 * - **首次启动（无缓存）**：同步阻塞获取远程配置，重试 5 次，每次间隔 10 秒。
 *   全部失败则标记为 FAILED，下次启动重新尝试。
 * - **有缓存启动**：立即从缓存加载（毫秒级），然后异步进行每天一次的更新。
 *   每天仅发一次请求，成功且与缓存不同才更新。
 *
 * ## 远程 JSON 格式
 * ```json
 * {
 *   "video_sources": [
 *     { "source_id": "crawler_jju", "name": "剧集屋", "base_url": "https://www.******.com" },
 *     { "source_id": "crawler_yinghua", "name": "樱花动漫", "base_url": "https://www.******.com" }
 *   ]
 * }
 * ```
 */
class VideoSourceConfigManager(
    private val context: Context,
    private val cacheRepository: ApiCacheRepository
) {

    /** 配置加载状态 */
    enum class ConfigState {
        /** 首次获取中（无缓存，正在重试远程配置） */
        LOADING,
        /** 配置就绪（从缓存或远程加载成功） */
        READY,
        /** 首次获取失败（所有重试均失败） */
        FAILED
    }

    /** 单个视频源的远程配置项 */
    data class SourceConfig(
        val sourceId: String,
        val name: String,
        val baseUrl: String
    )

    companion object {
        private const val TAG = "VideoSourceConfig"

        // ====== 远程配置 URL ======
        private const val CONFIG_URL_DEFAULT =
            "https://www.******.json"

        // ====== SharedPreferences 键 ======
        private const val PREFS_NAME = "video_source_config"
        private const val KEY_CACHED_JSON = "cached_config_json"
        private const val KEY_LAST_FETCH_DATE = "last_fetch_date"
        private const val KEY_CONFIG_URL = "config_url"

        // ====== 首次获取重试参数 ======
        private const val MAX_RETRIES = 5
        private const val RETRY_INTERVAL_MS = 10_000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 配置状态，供 UI 观察（LODAING 时显示加载界面，READY 时隐藏） */
    private val _state = MutableLiveData(ConfigState.LOADING)
    val state: LiveData<ConfigState> = _state

    /** 当前使用的配置 URL */
    fun getConfigUrl(): String =
        prefs.getString(KEY_CONFIG_URL, null) ?: CONFIG_URL_DEFAULT

    /** 更新配置 URL（持久化，下次获取生效） */
    fun setConfigUrl(url: String) {
        prefs.edit().putString(KEY_CONFIG_URL, url).apply()
    }

    // ===================== 初始化入口 =====================

    /**
     * 初始化配置。在 [MovieApplication.onCreate] 中调用。
     *
     * - 有缓存 → 同步加载缓存并构建视频源，状态设为 READY，然后异步进行每天更新
     * - 无缓存 → 启动首次获取（重试 5 次），成功则缓存并构建，失败则标记 FAILED
     */
    fun initConfig() {
        val cachedJson = prefs.getString(KEY_CACHED_JSON, null)
        if (cachedJson != null) {
            // ── 有缓存：同步加载 ──
            try {
                val configs = parseConfig(cachedJson)
                if (configs.isNotEmpty()) {
                    val sources = discoverAndBuildSources(configs, cacheRepository)
                    applySources(sources)
                    _state.value = ConfigState.READY
                    Log.d(TAG, "缓存配置加载完成，${sources.size} 个源")

                    // 异步进行每天更新
                    CoroutineScope(Dispatchers.IO).launch {
                        maybeDailyUpdate()
                    }
                } else {
                    Log.w(TAG, "缓存配置为空，尝试远程获取")
                    startFirstLaunchFetch()
                }
            } catch (e: Exception) {
                Log.w(TAG, "缓存配置解析失败，尝试远程获取: ${e.message}")
                startFirstLaunchFetch()
            }
        } else {
            // ── 无缓存：首次获取 ──
            Log.d(TAG, "无本地缓存，开始首次远程获取")
            startFirstLaunchFetch()
        }
    }

    /**
     * 手动重试（用户在失败界面点击"重试"按钮时调用）。
     */
    fun retryFetch() {
        Log.d(TAG, "用户手动触发重试")
        startFirstLaunchFetch()
    }

    // ===================== 首次获取（重试 5 次） =====================

    /**
     * 首次获取远程配置，带重试逻辑。
     * 在协程中执行，通过 [_state] 通知 UI。
     */
    private fun startFirstLaunchFetch() {
        _state.value = ConfigState.LOADING
        CoroutineScope(Dispatchers.IO).launch {
            val result = fetchWithRetries()
            if (result != null) {
                // 成功：缓存 + 构建源 + 标记今天已获取
                prefs.edit()
                    .putString(KEY_CACHED_JSON, result.rawJson)
                    .putString(KEY_LAST_FETCH_DATE, LocalDate.now().toString())
                    .apply()

                val sources = discoverAndBuildSources(result.configs, cacheRepository)
                applySources(sources)
                _state.postValue(ConfigState.READY)
                Log.d(TAG, "首次获取成功，已缓存 ${sources.size} 个源")
            } else {
                // 全部失败
                _state.postValue(ConfigState.FAILED)
                Log.w(TAG, "首次获取失败（$MAX_RETRIES 次重试均失败），等待下次启动重试")
            }
        }
    }

    /**
     * 重试获取远程配置，最多 [MAX_RETRIES] 次，每次间隔 [RETRY_INTERVAL_MS]。
     */
    private suspend fun fetchWithRetries(): ParsedConfig? {
        repeat(MAX_RETRIES) { attempt ->
            Log.d(TAG, "首次获取远程配置，第 ${attempt + 1}/$MAX_RETRIES 次尝试")
            val result = fetchRemoteConfig()
            if (result != null) return result

            if (attempt < MAX_RETRIES - 1) {
                Log.d(TAG, "等待 ${RETRY_INTERVAL_MS}ms 后重试...")
                delay(RETRY_INTERVAL_MS)
            }
        }
        return null
    }

    // ===================== 每天更新（仅一次请求） =====================

    /**
     * 每天首次启动时尝试更新远程配置。
     *
     * - 每天仅发一次请求（无论成功失败，不再重试）
     * - 成功且与缓存不同 → 更新缓存 + 重建视频源
     * - 失败 → 保持现有缓存不变
     */
    private suspend fun maybeDailyUpdate() {
        val today = LocalDate.now().toString()
        val lastFetch = prefs.getString(KEY_LAST_FETCH_DATE, null)

        if (lastFetch == today) {
            Log.d(TAG, "今天已获取过远程配置，跳过每日更新")
            return
        }

        Log.d(TAG, "每天首次启动，尝试更新远程配置")
        val result = fetchRemoteConfig()

        // 无论成功失败，都标记今天已尝试
        prefs.edit().putString(KEY_LAST_FETCH_DATE, today).apply()

        if (result != null) {
            val cachedJson = prefs.getString(KEY_CACHED_JSON, null)
            if (cachedJson != result.rawJson) {
                // 配置有变化 → 更新缓存 + 重建源
                prefs.edit().putString(KEY_CACHED_JSON, result.rawJson).apply()
                val sources = discoverAndBuildSources(result.configs, cacheRepository)
                applySources(sources)
                Log.d(TAG, "每日更新：远程配置有变化，已更新缓存和视频源（${sources.size} 个源）")
            } else {
                Log.d(TAG, "每日更新：远程配置与本地缓存相同，跳过")
            }
        } else {
            Log.w(TAG, "每日更新：获取失败，保持现有缓存")
        }
    }

    // ===================== 远程获取 =====================

    /**
     * 单次获取远程配置（无重试）。
     * @return 解析后的配置，失败返回 null
     */
    private suspend fun fetchRemoteConfig(): ParsedConfig? = withContext(Dispatchers.IO) {
        try {
            val url = getConfigUrl()
            Log.d(TAG, "请求远程配置: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "远程配置获取失败: HTTP ${response.code}")
                return@withContext null
            }

            val json = response.body?.string()
            if (json.isNullOrBlank()) {
                Log.w(TAG, "远程配置响应体为空")
                return@withContext null
            }

            val configs = parseConfig(json)
            if (configs.isEmpty()) {
                Log.w(TAG, "远程配置解析结果为空")
                return@withContext null
            }

            ParsedConfig(configs = configs, rawJson = json)
        } catch (e: Exception) {
            Log.w(TAG, "远程配置获取异常: ${e.message}")
            null
        }
    }

    // ===================== 内部工具 =====================

    /**
     * 通过反射自动发现并实例化视频源。
     *
     * 1. 扫描 DEX 中 `impl` 包下所有 [CrawlerVideoSource] 子类
     * 2. 反射调用无参构造函数实例化（所有构造参数均有默认值）
     * 3. 注入 [cacheRepository]
     * 4. 按 [configs] 中的 sourceId 匹配，设置 name/baseUrl
     *
     * 远程配置中有但代码中没有的 sourceId 自动跳过。
     */
    @Suppress("DEPRECATION")
    private fun discoverAndBuildSources(
        configs: List<SourceConfig>,
        cacheRepository: ApiCacheRepository
    ): List<CrawlerVideoSource> {
        // 1. 扫描 DEX 发现所有 CrawlerVideoSource 子类
        val discoveredClasses = discoverSourceClasses()
        Log.d(TAG, "DEX 扫描发现 ${discoveredClasses.size} 个视频源类")

        // 2. 实例化并建立 sourceId → 实例 映射
        val availableSources = mutableMapOf<String, CrawlerVideoSource>()
        for (clazz in discoveredClasses) {
            try {
                val instance = clazz.getDeclaredConstructor().newInstance() as CrawlerVideoSource
                instance.cacheRepository = cacheRepository
                availableSources[instance.sourceId] = instance
                Log.d(TAG, "实例化视频源: ${instance.sourceId} (${clazz.simpleName})")
            } catch (e: Exception) {
                Log.w(TAG, "实例化失败: ${clazz.name} - ${e.message}")
            }
        }

        // 3. 按远程配置顺序匹配，注入 name/baseUrl
        val result = mutableListOf<CrawlerVideoSource>()
        for (cfg in configs) {
            val source = availableSources[cfg.sourceId]
            if (source == null) {
                Log.w(TAG, "远程配置包含未知源 ID '${cfg.sourceId}'，代码中无对应实现，跳过")
                continue
            }
            source.sourceName = cfg.name
            source.baseUrl = cfg.baseUrl
            result.add(source)
        }

        return result
    }

    /**
     * 扫描 APK 的 DEX 文件，查找 `impl` 包下所有 [CrawlerVideoSource] 的非抽象子类。
     */
    @Suppress("DEPRECATION")
    private fun discoverSourceClasses(): List<Class<out CrawlerVideoSource>> {
        val result = mutableListOf<Class<out CrawlerVideoSource>>()
        val prefix = "com.hpu.mymoviestore.data.source.impl."

        try {
            val sourceDir = context.applicationInfo.sourceDir
            val dexFile = DexFile(sourceDir)
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (!className.startsWith(prefix)) continue
                try {
                    val clazz = Class.forName(className, false, context.classLoader)
                    if (CrawlerVideoSource::class.java.isAssignableFrom(clazz) &&
                        !Modifier.isAbstract(clazz.modifiers)
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        result.add(clazz as Class<out CrawlerVideoSource>)
                    }
                } catch (_: Throwable) {
                    // 跳过无法加载的类
                }
            }
            dexFile.close()
        } catch (e: Exception) {
            Log.e(TAG, "DexFile 扫描失败", e)
        }

        return result
    }

    /**
     * 解析 JSON 配置字符串为 [SourceConfig] 列表。
     */
    private fun parseConfig(json: String): List<SourceConfig> {
        val result = mutableListOf<SourceConfig>()
        try {
            val root = JSONObject(json)
            val sourcesArray = root.optJSONArray("video_sources") ?: run {
                Log.w(TAG, "JSON 中未找到 video_sources 数组")
                return result
            }
            for (i in 0 until sourcesArray.length()) {
                val item = sourcesArray.getJSONObject(i)
                val sourceId = item.optString("source_id", "")
                val name = item.optString("name", "")
                val baseUrl = item.optString("base_url", "")
                if (sourceId.isNotEmpty()) {
                    result.add(SourceConfig(sourceId, name, baseUrl))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON 解析异常: ${e.message}")
        }
        return result
    }

    /**
     * 将视频源列表应用到 MovieApplication 和 VideoRepository。
     * 同时从 SharedPreferences 恢复各源的启用/禁用状态。
     */
    private fun applySources(sources: List<com.hpu.mymoviestore.data.source.CrawlerVideoSource>) {
        val app = context.applicationContext as com.hpu.mymoviestore.MovieApplication
        val sourcePrefs = app.getSharedPreferences("video_sources", Context.MODE_PRIVATE)
        sources.forEach { source ->
            source.enabled = sourcePrefs.getBoolean("enabled_${source.sourceId}", true)
        }
        app.updateVideoSources(sources)
    }

    // ===================== 数据类 =====================

    /** 远程获取成功的解析结果 */
    private data class ParsedConfig(
        val configs: List<SourceConfig>,
        val rawJson: String
    )
}
