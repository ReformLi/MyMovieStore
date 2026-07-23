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
 * 搜索权限检查仓库
 *
 * 功能：
 * - 从远程 JSON 文件获取搜索权限配置
 * - 与本地 app_name 和 version 对比，同时检查 myapp 开关
 * - 三个条件同时满足时返回 true，否则 false
 * - 网络异常/超时默认放行（true），但会重试（最多5次，间隔1分钟）
 * - 结果缓存1天，有效期内直接读缓存
 */
class SearchPermissionRepository(
    private val context: Context,
    private val cacheRepository: ApiCacheRepository
) {

    companion object {
        private const val TAG = "SearchPermissionRepo"
        private const val PERMISSION_URL = "https://cdn.jsdelivr.net/gh/ReformLi/tvbox@refs/heads/main/mytvbox.json"
        private const val CACHE_KEY = "search_permission_result"
        private const val PREFS_NAME = "search_permission_prefs"
        private const val PREFS_KEY_RESULT = "permission_result"
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
     * 内存中的权限状态：
     * - null: 尚未检查（首次），搜索默认放行，后台异步检查
     * - true: 允许搜索
     * - false: 禁止搜索
     */
    @Volatile
    private var memoryPermissionResult: Boolean? = null

    /**
     * 检查搜索权限（非阻塞快速检查）。
     * 逻辑：
     * 1. 有缓存（本地或内存）→ 直接返回缓存结果
     * 2. 无缓存 → 默认放行（true），同时后台触发异步检查
     *
     * @return true 表示允许搜索，false 表示禁止搜索
     */
    fun checkPermissionFast(): Boolean {
        // 1. 内存缓存
        val memResult = memoryPermissionResult
        if (memResult != null) {
            Log.d(TAG, "搜索权限：使用内存缓存结果 = $memResult")
            return memResult
        }

        // 2. 本地 SharedPreferences 缓存
        val localResult = readLocalCache()
        if (localResult != null) {
            memoryPermissionResult = localResult
            Log.d(TAG, "搜索权限：使用本地缓存结果 = $localResult")
            return localResult
        }

        // 3. 无缓存：默认放行，后台异步检查
        Log.d(TAG, "搜索权限：无缓存，默认放行，后台异步检查中...")
        return true
    }

    /**
     * 异步触发权限检查（后台执行，不阻塞 UI）。
     * 缓存有效时直接跳过，避免不必要的网络请求。
     * 检查结果会更新到内存和本地缓存。
     */
    suspend fun fetchPermissionAsync() {
        if (isCacheValid()) {
            Log.d(TAG, "搜索权限：缓存有效，跳过后台网络请求")
            return
        }
        try {
            val result = fetchPermissionWithRetry()
            Log.d(TAG, "后台权限检查完成: $result")
            memoryPermissionResult = result
            saveLocalCache(result)
            try {
                cacheRepository.put(CACHE_KEY, result.toString(), ApiCacheEntity.TTL_ONE_DAY)
            } catch (e: Exception) {
                Log.w(TAG, "写入 ApiCache 失败: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "后台权限检查异常: ${e.message}")
        }
    }

    /**
     * 同步检查权限（会阻塞，等待网络请求完成）。
     * 用于应用启动时预加载，或需要立即知道结果的场景。
     */
    suspend fun checkPermissionSync(): Boolean {
        // 1. 先检查本地缓存
        val cachedResult = readLocalCache()
        if (cachedResult != null) {
            Log.d(TAG, "搜索权限：使用本地缓存结果 = $cachedResult")
            memoryPermissionResult = cachedResult
            return cachedResult
        }

        // 2. 从网络获取
        val result = fetchPermissionWithRetry()
        Log.d(TAG, "搜索权限：网络获取 = $result")
        memoryPermissionResult = result
        saveLocalCache(result)
        try {
            cacheRepository.put(CACHE_KEY, result.toString(), ApiCacheEntity.TTL_ONE_DAY)
        } catch (e: Exception) {
            Log.w(TAG, "写入 ApiCache 失败: ${e.message}")
        }

        return result
    }

    /**
     * 强制刷新权限（清除缓存后重新获取）
     */
    suspend fun refreshPermission(): Boolean {
        clearCache()
        return checkPermissionSync()
    }

    /**
     * 清除权限缓存
     */
    fun clearCache() {
        prefs.edit()
            .remove(PREFS_KEY_RESULT)
            .remove(PREFS_KEY_TIMESTAMP)
            .apply()
        Log.d(TAG, "权限缓存已清除")
    }

    /**
     * 检查是否有有效的缓存（内存缓存或本地缓存未过期）
     * @return true 表示有有效缓存，false 表示无缓存或缓存已过期
     */
    fun isCacheValid(): Boolean {
        // 1. 检查内存缓存
        if (memoryPermissionResult != null) {
            Log.d(TAG, "搜索权限：内存缓存有效")
            return true
        }

        // 2. 检查本地 SharedPreferences 缓存
        val localResult = readLocalCache()
        if (localResult != null) {
            memoryPermissionResult = localResult
            Log.d(TAG, "搜索权限：本地缓存有效，已加载到内存")
            return true
        }

        Log.d(TAG, "搜索权限：无有效缓存")
        return false
    }

    /**
     * 读取本地缓存
     */
    private fun readLocalCache(): Boolean? {
        val timestamp = prefs.getLong(PREFS_KEY_TIMESTAMP, 0)
        if (timestamp == 0L) return null
        if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) {
            Log.d(TAG, "本地缓存已过期")
            return null
        }
        return prefs.getBoolean(PREFS_KEY_RESULT, false)
    }

    /**
     * 保存到本地缓存
     */
    private fun saveLocalCache(result: Boolean) {
        prefs.edit()
            .putBoolean(PREFS_KEY_RESULT, result)
            .putLong(PREFS_KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "权限结果已缓存: $result")
    }

    /**
     * 带重试的权限获取
     */
    private suspend fun fetchPermissionWithRetry(): Boolean {
        repeat(MAX_RETRIES) { attempt ->
            try {
                val result = fetchPermissionFromNetwork()
                Log.d(TAG, "权限获取成功（第 ${attempt + 1} 次）: $result")
                return result
            } catch (e: Exception) {
                Log.w(TAG, "权限获取失败（第 ${attempt + 1}/$MAX_RETRIES 次）: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    Log.d(TAG, "${RETRY_INTERVAL_MS / 1000} 秒后重试...")
                    delay(RETRY_INTERVAL_MS)
                }
            }
        }
        // 所有重试都失败，默认放行
        Log.w(TAG, "所有重试均失败，默认放行（true）")
        return true
    }

    /**
     * 从网络获取权限配置
     * @return true 表示允许搜索，false 表示禁止
     */
    private suspend fun fetchPermissionFromNetwork(): Boolean {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(PERMISSION_URL)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile)")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                val bodyString = response.body?.string()
                    ?: throw Exception("响应体为空")

                Log.d(TAG, "原始响应: ${bodyString.take(500)}")

                // 尝试解析 JSON
                val json = try {
                    JSONObject(bodyString)
                } catch (e: Exception) {
                    Log.w(TAG, "响应不是有效 JSON: ${e.message}")
                    Log.w(TAG, "原始响应内容: $bodyString")
                    return@withContext false
                }

                // 提取字段
                val switchesObj = json.optJSONObject("switches")
                val metadataObj = json.optJSONObject("metadata")

                Log.d(TAG, "JSON 结构: switches=${switchesObj != null}, metadata=${metadataObj != null}")
                Log.d(TAG, "metadata 内容: ${metadataObj?.toString() ?: "null"}")

                val myapp = switchesObj?.optBoolean("myapp", false) ?: false
                val remoteAppName = metadataObj?.optString("app_name", "") ?: ""
                val remoteVersion = metadataObj?.optString("version", "") ?: ""

                Log.d(TAG, "远程配置: myapp=$myapp, app_name='$remoteAppName', version='$remoteVersion'")
                Log.d(TAG, "本地配置: app_name='$LOCAL_APP_NAME', version='$LOCAL_VERSION'")

                // 条件判断：
                // 1. myapp 必须为 true
                // 2. app_name 必须匹配
                // 3. version 必须匹配
                // 三个条件同时满足才放行
                val nameMatch = remoteAppName == LOCAL_APP_NAME
                val versionMatch = remoteVersion == LOCAL_VERSION

                Log.d(TAG, "条件判断: myapp=$myapp, nameMatch=$nameMatch(name='$remoteAppName' vs '$LOCAL_APP_NAME'), versionMatch=$versionMatch(version='$remoteVersion' vs '$LOCAL_VERSION')")

                myapp && nameMatch && versionMatch
            }
        }
    }
}
