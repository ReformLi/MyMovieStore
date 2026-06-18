package com.hpu.mymoviestore.data.source

import android.util.Log
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumiResponse
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.model.danmaku.DanmakuCommentResponse
import com.hpu.mymoviestore.data.model.danmaku.DanmakuEpisode
import com.hpu.mymoviestore.data.model.danmaku.DanmakuSearchResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

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
 * - 若网络异常或 API 未启动，返回 null，调用方应按"无弹幕"处理
 */
class DanmakuApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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
        Log.d(TAG, "弹幕 Base URL 设置为: $url")
    }

    fun getBaseUrl(): String = baseUrl

    /**
     * 按 keyword 搜索匹配的番剧/影视
     */
    @Throws(IOException::class)
    fun searchAnime(keyword: String): List<DanmakuAnime> {
        val url = "$baseUrl/api/v2/search/anime?keyword=${keyword.urlEncode()}"
        Log.d(TAG, "搜索弹幕: $url")

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "搜索请求失败: code=${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val parsed = try {
                searchAdapter.fromJson(body)
            } catch (t: Throwable) {
                Log.w(TAG, "搜索响应解析失败: ${t.message}")
                null
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
        Log.d(TAG, "获取 bangumi: $url")

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "bangumi 请求失败: code=${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val parsed = try {
                bangumiAdapter.fromJson(body)
            } catch (t: Throwable) {
                Log.w(TAG, "bangumi 响应解析失败: ${t.message}")
                null
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
        Log.d(TAG, "获取弹幕 JSON: $url")

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "弹幕请求失败: code=${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            Log.d(TAG, "弹幕 JSON 下载成功: ${body.length.toDouble() / 1024} KB")
            Log.d(TAG, "原始 JSON 前 300 字符: ${body.take(300)}")

            val parsed = try {
                commentAdapter.fromJson(body)
            } catch (t: Throwable) {
                Log.w(TAG, "弹幕 JSON 解析失败: ${t.message}")
                null
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
