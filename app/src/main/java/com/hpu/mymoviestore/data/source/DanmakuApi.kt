package com.hpu.mymoviestore.data.source

import android.util.Log
import com.hpu.mymoviestore.data.HttpClientProvider
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumiResponse
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.model.danmaku.DanmakuCommentResponse
import com.hpu.mymoviestore.data.model.danmaku.DanmakuSearchResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 弹幕 API 客户端（基于 danmu_api 开源项目）
 *
 * 接口：
 * - 搜索  : GET /api/v2/search/anime?keyword=...           → DanmakuSearchResponse
 * - 详情  : GET /api/v2/bangumi/{animeId}                   → DanmakuBangumiResponse
 * - 弹幕  : GET /api/v2/comment/{episodeId}                 → JSON 格式弹幕列表
 *
 * 注：
 * - Base URL 支持通过 setBaseUrl() 覆盖（默认 http://192.168.1.1:4567）
 * - 弹幕内容采用 JSON 格式 {"count":..., "comments":[{"p":"...", "text":"..."}]}
 * - 服务端错误（HTTP 非 2xx、响应体为空、JSON 解析失败）抛出 IOException，
 *   由上层重试机制（DanmakuRepository.retryWithBackoff）处理；
 *   业务级空结果（success=false 或确实无数据）返回空列表/null，调用方按"无弹幕"处理
 */
class DanmakuApi {

    private val client: OkHttpClient = HttpClientProvider.standardClient

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val searchAdapter = moshi.adapter(DanmakuSearchResponse::class.java)
    private val bangumiAdapter = moshi.adapter(DanmakuBangumiResponse::class.java)
    private val commentAdapter = moshi.adapter(DanmakuCommentResponse::class.java)

    @Volatile
    private var baseUrl: String = "http://192.168.1.1:4567"//http://192.168.1.1:4567

    fun setBaseUrl(url: String) {
        baseUrl = url
        Log.d(TAG, "弹幕 Base URL 已更新（域名脱敏）")
    }

    fun getBaseUrl(): String = baseUrl

    /**
     * 脱敏 URL：只保留 path + query，不打印域名，避免 Release 包日志泄露服务器地址。
     * 例：https://example.com/api/v2/search?keyword=xxx → /api/v2/search?keyword=xxx
     */
    private fun maskUrl(url: String): String {
        return try {
            val parsed = java.net.URI(url)
            val path = parsed.path ?: ""
            val query = parsed.query?.let { "?$it" } ?: ""
            path + query
        } catch (e: Exception) {
            // 解析失败时返回固定占位符，不泄露原始 URL
            "<invalid url>"
        }
    }

    /**
     * 按 keyword 搜索匹配的番剧/影视
     */
    @Throws(IOException::class)
    fun searchAnime(keyword: String): List<DanmakuAnime> {
        val url = "$baseUrl/api/v2/search/anime?keyword=${keyword.urlEncode()}"
        Log.d(TAG, "搜索弹幕: ${maskUrl(url)}")

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("搜索请求失败: code=${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("搜索响应体为空")
            val parsed = try {
                searchAdapter.fromJson(body)
            } catch (t: Throwable) {
                throw IOException("搜索响应解析失败: ${t.message}", t)
            }
            val result = if (parsed != null && parsed.success && parsed.errorCode == 0) {
                parsed.animes
            } else {
                Log.w(TAG, "搜索响应非预期 (errorCode=${parsed?.errorCode}, success=${parsed?.success})")
                emptyList()
            }
            Log.d(TAG, "搜索命中 ${result.size} 条")
            return result
        }
    }

    /**
     * 获取某部番剧的 bangumi 详情（含 episode 列表）
     */
    @Throws(IOException::class)
    fun getBangumi(animeId: Long): DanmakuBangumi? {
        val url = "$baseUrl/api/v2/bangumi/$animeId"
        Log.d(TAG, "获取 bangumi: ${maskUrl(url)}")

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("bangumi 请求失败: code=${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("bangumi 响应体为空")
            val parsed = try {
                bangumiAdapter.fromJson(body)
            } catch (t: Throwable) {
                throw IOException("bangumi 响应解析失败: ${t.message}", t)
            }
            if (parsed != null && parsed.success && parsed.errorCode == 0 && parsed.bangumi != null) {
                Log.d(
                    TAG,
                    "bangumi 解析成功: title=${parsed.bangumi!!.animeTitle}, " +
                        "episodes=${parsed.bangumi!!.episodes.size}"
                )
                return parsed.bangumi
            }
            Log.w(TAG, "bangumi 响应非预期 (errorCode=${parsed?.errorCode})")
            return null
        }
    }

    /**
     * 获取某一集的弹幕（JSON 格式，返回弹幕列表）
     */
    @Throws(IOException::class)
    fun getDanmakuComments(episodeId: Long): List<DanmakuComment> {
        val url = "$baseUrl/api/v2/comment/$episodeId"
        Log.d(TAG, "获取弹幕 JSON: ${maskUrl(url)}")

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("弹幕请求失败: code=${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("弹幕响应体为空")
            Log.d(TAG, "弹幕 JSON 下载成功: ${body.length.toDouble() / 1024} KB")
            Log.d(TAG, "原始 JSON 前 300 字符: ${body.take(300)}")

            val parsed = try {
                commentAdapter.fromJson(body)
            } catch (t: Throwable) {
                throw IOException("弹幕 JSON 解析失败: ${t.message}", t)
            }

            if (parsed != null) {
                Log.d(TAG, "弹幕解析成功: count=${parsed.count}, comments=${parsed.comments.size}")
                // 调试：打印前 3 条弹幕的原始内容，确认字段名
                parsed.comments.take(3).forEachIndexed { idx, c ->
                    Log.d(TAG, "  弹幕[$idx] p=${c.p.take(40)}, m=[${c.m.take(30)}], m.isEmpty=${c.m.isEmpty()}")
                }
                return parsed.comments
            }
            return emptyList()
        }
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val TAG = "DanmakuApi"
    }
}
