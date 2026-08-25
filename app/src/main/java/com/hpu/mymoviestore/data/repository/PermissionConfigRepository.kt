package com.hpu.mymoviestore.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.hpu.mymoviestore.data.entity.ApiCacheEntity

/**
 * App 远程权限配置模型。
 * 每个字段对应远程 JSON switches 中的一个开关，且必须满足 app_name/version 匹配才生效。
 */
data class PermissionConfig(
    val searchEnabled: Boolean,
    val danmakuEnabled: Boolean
) {
    /** 序列化为本地缓存用的 JSON（结构与远程 JSON 无关，仅存解析结果） */
    fun toCacheJson(): String = """{"search":$searchEnabled,"danmaku":$danmakuEnabled}"""

    companion object {
        /** 网络获取失败/无缓存时的默认配置：全部放行，避免远程异常锁死本地功能 */
        val DEFAULT = PermissionConfig(searchEnabled = true, danmakuEnabled = true)
    }
}

/**
 * 远程权限配置仓库
 *
 * 从远程 JSON 文件获取 App 各项功能的权限开关配置（搜索、弹幕等），
 * 每个开关与本地 app_name / version 匹配后生效。
 *
 * 功能：
 * - 从远程 JSON 文件获取权限配置（switches 下的各开关 + metadata 的 app_name/version）
 * - 搜索权限：switches.myapp 且 app_name/version 匹配时为 true
 * - 弹幕权限：switches.enable_danmaku 且 app_name/version 匹配时为 true
 * - 网络获取失败（含响应解析失败）默认放行（全部开启），与各开关的"默认关闭"互不影响
 * - 结果缓存 1 天，有效期内直接读缓存
 */
class PermissionConfigRepository(
    private val context: Context,
    private val cacheRepository: ApiCacheRepository
) {

    companion object {
        private const val TAG = "PermissionConfigRepo"
        private const val PERMISSION_URL = "https:www.******.json"
        private const val CACHE_KEY = "permission_config_result"
        private const val PREFS_NAME = "permission_config_prefs"
        private const val PREFS_KEY_CONFIG = "permission_config_json"
        private const val PREFS_KEY_TIMESTAMP = "permission_timestamp"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 1天
        private const val MAX_RETRIES = 5
        private const val RETRY_INTERVAL_MS = 60 * 1000L // 1分钟

        // 本地固定值
        const val LOCAL_APP_NAME = "MyMovieStore"
        const val LOCAL_VERSION = "1.0.0"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 内存中的权限配置：
     * - null: 尚未检查（首次），默认放行，后台异步检查
     */
    @Volatile
    private var memoryConfig: PermissionConfig? = null

    /**
     * 检查搜索权限（非阻塞快速检查）。
     * 逻辑：
     * 1. 有缓存（本地或内存）→ 直接返回缓存结果
     * 2. 无缓存 → 默认放行（true），同时后台触发异步检查
     *
     * @return true 表示允许搜索，false 表示禁止搜索
     */
    fun checkSearchPermissionFast(): Boolean {
        return loadConfigFast()?.searchEnabled ?: true
    }

    /**
     * 检查弹幕权限（非阻塞快速检查）。
     * 与搜索权限同源：switches.enable_danmaku 开启且 app_name/version 匹配时为 true。
     * 无缓存/获取失败时默认放行（true），弹幕功能保持可用。
     *
     * @return true 表示允许弹幕联网获取，false 表示禁止（播放器只显示「弹幕已关闭」）
     */
    fun checkDanmakuPermissionFast(): Boolean {
        return loadConfigFast()?.danmakuEnabled ?: true
    }

    /** 读取内存或本地缓存，无有效缓存时返回 null */
    private fun loadConfigFast(): PermissionConfig? {
        // 1. 内存缓存
        val memConfig = memoryConfig
        if (memConfig != null) {
            Log.d(TAG, "权限配置：使用内存缓存 = $memConfig")
            return memConfig
        }

        // 2. 本地 SharedPreferences 缓存
        val localConfig = readLocalCache()
        if (localConfig != null) {
            memoryConfig = localConfig
            Log.d(TAG, "权限配置：使用本地缓存 = $localConfig")
            return localConfig
        }

        Log.d(TAG, "权限配置：无缓存")
        return null
    }

    /**
     * 异步触发权限检查（后台执行，不阻塞 UI）。
     *
     * - 缓存有效时直接跳过，避免不必要的网络请求
     * - 获取成功时更新到内存和本地缓存（24h TTL）
     * - 获取失败时不写缓存，本次会话默认放行，下次进入应用重新获取
     */
    suspend fun fetchPermissionAsync() {
        if (isCacheValid()) {
            Log.d(TAG, "权限配置：缓存有效，跳过后台网络请求")
            return
        }
        try {
            val config = fetchConfigWithRetry()
            if (config != null) {
                Log.d(TAG, "权限配置获取成功，已写入缓存: $config")
                memoryConfig = config
                saveLocalCache(config)
                try {
                    cacheRepository.put(CACHE_KEY, config.toCacheJson(), ApiCacheEntity.TTL_ONE_DAY)
                } catch (e: Exception) {
                    Log.w(TAG, "写入 ApiCache 失败: ${e.message}")
                }
            } else {
                // 获取失败：不写缓存，本次会话 checkPermissionFast 走 ?: true 放行
                Log.w(TAG, "权限配置获取失败，不写缓存，本次会话默认放行，下次进入应用重新获取")
            }
        } catch (e: Exception) {
            Log.w(TAG, "后台权限配置检查异常: ${e.message}")
        }
    }

    /**
     * 同步检查权限（会阻塞，等待网络请求完成）。
     * 用于应用启动时预加载，或需要立即知道结果的场景。
     *
     * - 缓存有效时直接返回缓存
     * - 获取成功时写入缓存（24h TTL）并返回实际配置
     * - 获取失败时返回 DEFAULT（放行）但不写缓存，下次进入应用重新获取
     *
     * @return 完整的权限配置（含搜索、弹幕等开关）
     */
    suspend fun checkPermissionSync(): PermissionConfig {
        // 1. 先检查本地缓存
        val cachedConfig = loadConfigFast()
        if (cachedConfig != null) {
            Log.d(TAG, "权限配置：使用本地缓存 = $cachedConfig")
            memoryConfig = cachedConfig
            return cachedConfig
        }

        // 2. 从网络获取
        val config = fetchConfigWithRetry()
        if (config != null) {
            Log.d(TAG, "权限配置获取成功，已写入缓存: $config")
            memoryConfig = config
            saveLocalCache(config)
            try {
                cacheRepository.put(CACHE_KEY, config.toCacheJson(), ApiCacheEntity.TTL_ONE_DAY)
            } catch (e: Exception) {
                Log.w(TAG, "写入 ApiCache 失败: ${e.message}")
            }
            return config
        }

        // 3. 获取失败：返回 DEFAULT 放行，但不写缓存
        Log.w(TAG, "权限配置获取失败，本次返回默认放行（不写缓存，下次进入应用重新获取）")
        return PermissionConfig.DEFAULT
    }

    /**
     * 强制刷新权限（清除缓存后重新获取）
     */
    suspend fun refreshPermission(): PermissionConfig {
        clearCache()
        return checkPermissionSync()
    }

    /**
     * 清除权限缓存（内存 + SharedPreferences 本地缓存，一并清空）
     */
    fun clearCache() {
        memoryConfig = null
        prefs.edit()
            .remove(PREFS_KEY_CONFIG)
            .remove(PREFS_KEY_TIMESTAMP)
            .apply()
        Log.d(TAG, "权限配置缓存已清除（内存 + 本地）")
    }

    /**
     * 检查是否有有效的缓存（内存缓存或本地缓存未过期）
     * @return true 表示有有效缓存，false 表示无缓存或缓存已过期
     */
    fun isCacheValid(): Boolean {
        // 1. 检查内存缓存
        if (memoryConfig != null) {
            Log.d(TAG, "权限配置：内存缓存有效")
            return true
        }

        // 2. 检查本地 SharedPreferences 缓存
        val localConfig = readLocalCache()
        if (localConfig != null) {
            memoryConfig = localConfig
            Log.d(TAG, "权限配置：本地缓存有效，已加载到内存")
            return true
        }

        Log.d(TAG, "权限配置：无有效缓存")
        return false
    }

    /**
     * 读取本地缓存
     */
    private fun readLocalCache(): PermissionConfig? {
        val timestamp = prefs.getLong(PREFS_KEY_TIMESTAMP, 0)
        if (timestamp == 0L) return null
        if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) {
            Log.d(TAG, "本地缓存已过期")
            return null
        }
        val jsonStr = prefs.getString(PREFS_KEY_CONFIG, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            PermissionConfig(
                searchEnabled = json.optBoolean("search", true),
                danmakuEnabled = json.optBoolean("danmaku", true)
            )
        } catch (e: Exception) {
            Log.w(TAG, "本地缓存解析失败，视为无效: ${e.message}")
            null
        }
    }

    /**
     * 保存到本地缓存
     */
    private fun saveLocalCache(config: PermissionConfig) {
        prefs.edit()
            .putString(PREFS_KEY_CONFIG, config.toCacheJson())
            .putLong(PREFS_KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "权限配置已缓存: $config")
    }

    /**
     * 带重试的权限配置获取。
     *
     * @return 成功时返回实际配置；所有重试均失败时返回 null（不返回 DEFAULT）。
     *         调用方负责在 null 时决定是否放行：快速检查 `?: true` 放行，
     *         但不写缓存，确保下次进入应用会重新获取。
     */
    private suspend fun fetchConfigWithRetry(): PermissionConfig? {
        repeat(MAX_RETRIES) { attempt ->
            try {
                val config = fetchConfigFromNetwork()
                Log.d(TAG, "权限配置获取成功（第 ${attempt + 1} 次）: $config")
                return config
            } catch (e: Exception) {
                Log.w(TAG, "权限配置获取失败（第 ${attempt + 1}/$MAX_RETRIES 次）: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    Log.d(TAG, "${RETRY_INTERVAL_MS / 1000} 秒后重试...")
                    delay(RETRY_INTERVAL_MS)
                }
            }
        }
        // 所有重试均失败：返回 null，不写缓存，下次进入应用重新获取
        Log.w(TAG, "所有重试均失败，本次会话默认放行（不写缓存，下次进入应用重新获取）")
        return null
    }

    /**
     * 从网络获取权限配置
     *
     * 解析失败（响应不是预期 JSON 结构）同样视为获取失败，默认放行，
     * 避免远程文件异常导致本地功能被锁死。
     */
    private suspend fun fetchConfigFromNetwork(): PermissionConfig {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(PERMISSION_URL)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile)")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP 请求失败: ${response.code}")
                }

                val bodyString = response.body?.string()
                    ?: throw Exception("响应体为空")

                Log.d(TAG, "原始响应: ${bodyString.take(500)}")

                // 尝试解析 JSON，失败默认放行
                val json = try {
                    JSONObject(bodyString)
                } catch (e: Exception) {
                    Log.w(TAG, "响应不是有效 JSON，默认放行: ${e.message}")
                    return@withContext PermissionConfig.DEFAULT
                }

                parseConfig(json)
            }
        }
    }

    /**
     * 解析远程 JSON 配置。
     * 每个开关独立判断：开关为 true 且 app_name/version 与本地匹配时该权限才生效；
     * 开关缺失/为 false 时对应权限禁止（与"获取失败默认放行"互不影响）。
     */
    private fun parseConfig(json: JSONObject): PermissionConfig {
        val switchesObj = json.optJSONObject("switches")
        val metadataObj = json.optJSONObject("metadata")

        val myapp = switchesObj?.optBoolean("myapp", false) ?: false
        val enableDanmaku = switchesObj?.optBoolean("enable_danmaku", false) ?: false
        val remoteAppName = metadataObj?.optString("app_name", "") ?: ""
        val remoteVersion = metadataObj?.optString("version", "") ?: ""

        Log.d(TAG, "远程配置: myapp=$myapp, enable_danmaku=$enableDanmaku, app_name='$remoteAppName', version='$remoteVersion'")
        Log.d(TAG, "本地配置: app_name='$LOCAL_APP_NAME', version='$LOCAL_VERSION'")

        // 条件判断：
        // 1. 各开关（myapp / enable_danmaku）必须为 true
        // 2. app_name 必须匹配
        // 3. version 必须匹配
        val nameMatch = remoteAppName == LOCAL_APP_NAME
        val versionMatch = remoteVersion == LOCAL_VERSION

        Log.d(TAG, "条件判断: myapp=$myapp, enable_danmaku=$enableDanmaku, nameMatch=$nameMatch(name='$remoteAppName' vs '$LOCAL_APP_NAME'), versionMatch=$versionMatch(version='$remoteVersion' vs '$LOCAL_VERSION')")

        return PermissionConfig(
            searchEnabled = myapp && nameMatch && versionMatch,
            danmakuEnabled = enableDanmaku && nameMatch && versionMatch
        )
    }
}
