package com.hpu.mymoviestore.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hpu.mymoviestore.BuildConfig
import com.hpu.mymoviestore.data.HttpClientProvider
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * App 远程权限配置模型。
 *
 * - searchEnabled/danmakuEnabled：功能开关，需满足 app_name + version 匹配才生效
 * - enableUpdate：更新检查开关（app_name 匹配即生效，**不受 version 匹配限制**——
 *   否则远程版本领先时 versionMatch=false，版本滞后的用户将永远收不到更新提示）
 * - forceUpdateUrl：APK 下载地址
 * - updateDetails：更新说明文案（展示到关于页更新卡片）
 * - latestVersion：远程最新版本号（metadata.version 原始值），与本地版本比较判断是否有更新
 */
data class PermissionConfig(
    val searchEnabled: Boolean,
    val danmakuEnabled: Boolean,
    val enableUpdate: Boolean = false,
    val forceUpdateUrl: String? = null,
    val updateDetails: String? = null,
    val latestVersion: String? = null
) {
    /**
     * 序列化为本地缓存用的 JSON（结构与远程 JSON 无关，仅存解析结果）。
     *
     * 包含 cached_for_version 字段（方案 B）：记录该缓存对应的 App 版本，
     * 读取时与当前 BuildConfig.VERSION_NAME 校验，版本升级后旧缓存自动失效，
     * 避免升级后 24h 内带着旧版本获取的权限状态（如 version 不匹配导致的全关）。
     */
    fun toCacheJson(): String {
        val url = forceUpdateUrl?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        val details = updateDetails?.replace("\\", "\\\\")
            ?.replace("\"", "\\\"")?.replace("\n", "\\n")?.replace("\r", "") ?: ""
        val version = latestVersion?.replace("\"", "\\\"") ?: ""
        return """{"search":$searchEnabled,"danmaku":$danmakuEnabled,"enableUpdate":$enableUpdate,"forceUpdateUrl":"$url","updateDetails":"$details","latestVersion":"$version","cached_for_version":"${BuildConfig.VERSION_NAME}"}"""
    }

    companion object {
        /** 网络获取失败/无缓存时的默认配置：全部放行，避免远程异常锁死本地功能 */
        val DEFAULT = PermissionConfig(searchEnabled = true, danmakuEnabled = true)
    }
}

/**
 * 更新检查结果。
 *
 * @param latestVersion 远程最新版本号
 * @param downloadUrl APK 下载地址
 * @param details 更新说明文案
 */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val details: String?
)

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

        // 本地固定值（LOCAL_VERSION 从 BuildConfig 读取，发版时只需改 build.gradle.kts 的 versionName）
        const val LOCAL_APP_NAME = "MyMovieStore"
        val LOCAL_VERSION: String = BuildConfig.VERSION_NAME
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val okHttpClient: OkHttpClient = HttpClientProvider.standardClient

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
     * 读取本地缓存。
     *
     * 方案 B：缓存 JSON 内记录 cached_for_version（获取该配置时的 App 版本），
     * 与当前 BuildConfig.VERSION_NAME 不一致即视为无效（App 升级后旧缓存自动失效，
     * 重新联网拉取，避免升级后 24h 内带着旧版本获取的权限状态）。
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

            // 版本校验：缓存获取时的 App 版本与当前版本不一致 → 缓存失效
            val cachedForVersion = json.optString("cached_for_version", "")
            if (cachedForVersion != BuildConfig.VERSION_NAME) {
                Log.d(TAG, "本地缓存版本不匹配（缓存=$cachedForVersion, 当前=${BuildConfig.VERSION_NAME}），视为无效")
                return null
            }

            PermissionConfig(
                searchEnabled = json.optBoolean("search", true),
                danmakuEnabled = json.optBoolean("danmaku", true),
                enableUpdate = json.optBoolean("enableUpdate", false),
                forceUpdateUrl = json.optString("forceUpdateUrl", "").takeIf { it.isNotEmpty() },
                updateDetails = json.optString("updateDetails", "").takeIf { it.isNotEmpty() },
                latestVersion = json.optString("latestVersion", "").takeIf { it.isNotEmpty() }
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
     *
     * - 搜索/弹幕权限：开关为 true 且 app_name + version 与本地匹配时生效
     * - 更新检查：enable_update 且 app_name 匹配即生效（不要求 version 匹配，
     *   否则远程版本领先时 versionMatch=false，用户收不到更新提示）
     * - strings 下的 force_update_url / update_details / metadata.version 原样带出，
     *   供更新检查与关于页展示
     */
    private fun parseConfig(json: JSONObject): PermissionConfig {
        val switchesObj = json.optJSONObject("switches")
        val stringsObj = json.optJSONObject("strings")
        val metadataObj = json.optJSONObject("metadata")

        val myapp = switchesObj?.optBoolean("myapp", false) ?: false
        val enableDanmaku = switchesObj?.optBoolean("enable_danmaku", false) ?: false
        val enableUpdateSwitch = switchesObj?.optBoolean("enable_update", false) ?: false
        val remoteAppName = metadataObj?.optString("app_name", "") ?: ""
        val remoteVersion = metadataObj?.optString("version", "") ?: ""
        val forceUpdateUrl = stringsObj?.optString("force_update_url", "")?.takeIf { it.isNotEmpty() }
        val updateDetails = stringsObj?.optString("update_details", "")?.takeIf { it.isNotEmpty() }

        Log.d(TAG, "远程配置: myapp=$myapp, enable_danmaku=$enableDanmaku, enable_update=$enableUpdateSwitch, app_name='$remoteAppName', version='$remoteVersion'")
        Log.d(TAG, "本地配置: app_name='$LOCAL_APP_NAME', version='$LOCAL_VERSION'")

        val nameMatch = remoteAppName == LOCAL_APP_NAME
        val versionMatch = remoteVersion == LOCAL_VERSION

        Log.d(TAG, "条件判断: myapp=$myapp, enable_danmaku=$enableDanmaku, nameMatch=$nameMatch(name='$remoteAppName' vs '$LOCAL_APP_NAME'), versionMatch=$versionMatch(version='$remoteVersion' vs '$LOCAL_VERSION')")

        return PermissionConfig(
            searchEnabled = myapp && nameMatch && versionMatch,
            danmakuEnabled = enableDanmaku && nameMatch && versionMatch,
            // 更新检查：app_name 匹配 + enable_update 开关（不要求 versionMatch，理由见方法注释）
            enableUpdate = enableUpdateSwitch && nameMatch,
            forceUpdateUrl = forceUpdateUrl,
            updateDetails = updateDetails,
            latestVersion = remoteVersion.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * 检查是否有可用更新。
     *
     * 判断条件（三者同时满足）：
     * 1. enableUpdate：远程 enable_update 开关开启且 app_name 匹配
     * 2. 远程 latestVersion > 本地版本（语义化版本比较，1.10.0 > 1.9.0）
     * 3. force_update_url 非空（有可下载的 APK 地址）
     *
     * 数据来源为本地缓存（内存或 SharedPreferences，方案 B 带版本校验）；
     * 缓存不存在时触发一次后台拉取再判断。
     *
     * @return 有更新返回 [UpdateInfo]；无更新或无法判断返回 null
     */
    suspend fun checkUpdate(): UpdateInfo? {
        // 确保配置已加载：无缓存时尝试后台拉取（成功后写入内存）
        if (loadConfigFast() == null) {
            Log.d(TAG, "更新检查：无缓存，尝试拉取配置")
            fetchPermissionAsync()
        }
        val config = loadConfigFast() ?: run {
            Log.d(TAG, "更新检查：配置不可用，跳过检查")
            return null
        }

        // 1. 更新开关
        if (!config.enableUpdate) {
            Log.d(TAG, "更新检查：enable_update 未开启或 app_name 不匹配")
            return null
        }

        // 2. 版本比较：远程版本需大于本地版本
        val latest = config.latestVersion
        if (latest == null || compareVersion(latest, LOCAL_VERSION) <= 0) {
            Log.d(TAG, "更新检查：本地已是最新（remote=$latest, local=$LOCAL_VERSION）")
            return null
        }

        // 3. 下载地址
        val url = config.forceUpdateUrl
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "更新检查：发现新版本 $latest 但缺少下载地址")
            return null
        }

        Log.d(TAG, "更新检查：发现新版本 $latest（本地=$LOCAL_VERSION）")
        return UpdateInfo(latestVersion = latest, downloadUrl = url, details = config.updateDetails)
    }

    /**
     * 语义化版本比较（按 "." 分段逐位比较数字，位数不足补 0）。
     * 例：1.10.0 vs 1.9.0 → 1（正确处理字符串比较会出错的场景）
     *
     * @return 正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等
     */
    private fun compareVersion(v1: String, v2: String): Int {
        val p1 = v1.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val p2 = v2.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val a = p1.getOrElse(i) { 0 }
            val b = p2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }
}
