// CrawlerVideoSource.kt 修改后
package com.hpu.mymoviestore.data.source

import android.util.Log
import com.hpu.mymoviestore.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
        .build()
) {
    // 模拟人类延迟，避免请求过快
    private suspend fun humanDelay() {
        delay(Random.nextLong(1500, 3500))
    }

    // 获取首页视频列表
    suspend fun fetchHomepageVideos(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        Log.d("Crawler", "fetchHomepageVideos 开始执行，URL: https://www.******.com/")
        try {
            humanDelay()
            // 1. 获取页面
            val doc = Jsoup.connect("https://www.******.com/")
                .timeout(15000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .get()
            Log.d("Crawler", "页面获取成功，开始解析")

            // 2. 选择所有 .r-item 卡片（PC + 移动端）
            val items = doc.select(".r-item")
            Log.d("Crawler", "找到 .r-item 数量: ${items.size}")

            val videoItems = mutableListOf<VideoItem>()
            val baseUrl = "https://www.******.com"

            for (element in items) {
                // 详情链接：.r-poster 或 .r-title 的 href
                val detailLink = element.select(".r-poster").first() ?: element.select(".r-title").first()
                val detailUrl = detailLink?.attr("abs:href")
                    ?: element.select("a").attr("abs:href")
                if (detailUrl.isBlank()) continue

                // 标题
                val title = element.select(".r-title").text()
                if (title.isBlank()) continue

                // 封面：优先取 data-original，其次是 style 中的 background-image
                var coverUrl = element.select(".r-poster").attr("data-original")
                if (coverUrl.isBlank()) {
                    val styleAttr = element.select(".r-poster").attr("style")
                    val regex = Regex("background-image:url\\(([^)]+)\\)")
                    coverUrl = regex.find(styleAttr)?.groupValues?.get(1) ?: ""
                }
                // 转为绝对路径
                if (coverUrl.isNotBlank() && coverUrl.startsWith("/")) {
                    coverUrl = baseUrl + coverUrl
                }

                val id = detailUrl.hashCode().toLong()
                videoItems.add(
                    VideoItem(
                        id = id,
                        title = title,
                        coverUrl = coverUrl,
                        playUrl = "",   // 播放地址需要进入详情页获取
                        category = "影视",
                        detailUrl = detailUrl,   // 保存详情页 URL
                        rating = "",
                        year = "",
                        area = "",
                        director = "",
                        actors = "",
                        description = ""
                    )
                )
            }

            Log.d("Crawler", "解析完成，共 ${videoItems.size} 条视频")
            Result.success(videoItems)
        } catch (e: Exception) {
            Log.e("Crawler", "爬取失败", e)
            Result.failure(e)
        }
    }

    // 获取视频真实播放地址
    /**
     * 根据详情页 URL 获取真实视频播放地址
     * @param detailUrl 详情页地址，例如 https://www.******.com/voddetail/68381.html
     * @return 视频流地址（m3u8/mp4）
     */
    suspend fun fetchVideoUrl(detailUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            humanDelay()
            Log.d("Crawler", "开始解析详情页: $detailUrl")

            // 1. 先请求详情页，找到第一个播放链接（如 /vodplay/68381-1-1.html）
            val detailDoc = Jsoup.connect(detailUrl)
                .timeout(15000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .get()

            val playLink = detailDoc.select(".channel-set a.item").first()
            if (playLink == null) {
                Log.e("Crawler", "未找到播放链接")
                return@withContext Result.failure(IOException("未找到播放链接"))
            }

            val playPageUrl = playLink.attr("abs:href")
            Log.d("Crawler", "找到播放页: $playPageUrl")

            // 2. 请求播放页，提取 player_aaaa 变量中的 url
            val playDoc = Jsoup.connect(playPageUrl)
                .timeout(15000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .get()

            // 查找包含 player_aaaa 的 script 标签
            val scriptElement = playDoc.select("script:containsData(player_aaaa)").first()
            if (scriptElement == null) {
                Log.e("Crawler", "未找到 player_aaaa 脚本")
                return@withContext Result.failure(IOException("未找到播放数据"))
            }

            val scriptContent = scriptElement.html()
            Log.d("Crawler", "脚本片段: ${scriptContent.take(200)}")

            // 使用正则提取 "url":"https://..."
            val urlRegex = Regex("\"url\":\"([^\"]+)\"")
            val match = urlRegex.find(scriptContent)
            val videoUrl = match?.groupValues?.get(1)?.replace("\\/", "/")
            if (!videoUrl.isNullOrBlank()) {
                Log.d("Crawler", "成功提取视频地址: $videoUrl")
                Result.success(videoUrl)
            } else {
                // 备用方案：直接查找 .m3u8 链接
                val m3u8Regex = Regex("https?://[^\"]+\\.m3u8")
                val m3u8Match = m3u8Regex.find(scriptContent)
                if (m3u8Match != null) {
                    Log.d("Crawler", "通过 m3u8 正则找到: ${m3u8Match.value}")
                    Result.success(m3u8Match.value)
                } else {
                    Log.e("Crawler", "未能提取视频地址")
                    Result.failure(IOException("未找到视频地址"))
                }
            }
        } catch (e: Exception) {
            Log.e("Crawler", "获取视频地址失败", e)
            Result.failure(e)
        }
    }
}