package com.hpu.mymoviestore.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 OkHttpClient 提供者（单例）。
 *
 * 统一管理应用内的 OkHttpClient 实例，避免各模块各自 new OkHttpClient 导致：
 * - 连接池分散，无法复用 TCP 连接
 * - 实例过多增加内存开销
 * - 超时配置不一致
 *
 * 提供 3 种实例：
 * - [standardClient]：通用 HTTP 请求（15s 超时），用于弹幕 API、m3u8 解析、权限配置、远程配置等
 * - [downloadClient]：大文件下载（30s 超时 + 独立连接池），用于 DownloadEngine
 * - [crawlerClient]：爬虫专用（15s 超时 + UA 拦截器），用于 CrawlerVideoSource
 *
 * 例外（保留独立实例）：
 * - Coil 图片加载（MovieApplication.newImageLoader）：带豆瓣防盗链域名判断拦截器，与图片生命周期绑定
 * - ExoPlayer 数据源（PlayerActivity）：Media3 建议使用独立实例，避免与业务请求互相影响
 */
object HttpClientProvider {

    /** 通用请求超时（秒） */
    private const val STANDARD_TIMEOUT = 15L

    /** 下载请求超时（秒） */
    private const val DOWNLOAD_TIMEOUT = 30L

    /**
     * 通用 OkHttpClient（15s 超时）。
     * 用于弹幕 API、m3u8 解析、权限配置、远程配置等常规 HTTP 请求。
     * 全局单例，连接池由 OkHttp 内部管理（默认 5 个空闲连接，5 分钟超时）。
     */
    val standardClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(STANDARD_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(STANDARD_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 下载专用 OkHttpClient（30s 超时 + 独立连接池 10 连接）。
     * 用于 DownloadEngine 的分片下载，独立连接池避免与业务请求竞争连接资源。
     */
    val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 爬虫专用 OkHttpClient（15s 超时 + 统一 UA 拦截器）。
     * 用于 CrawlerVideoSource 的网页爬取，自动添加浏览器 User-Agent。
     */
    val crawlerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(STANDARD_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(STANDARD_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
