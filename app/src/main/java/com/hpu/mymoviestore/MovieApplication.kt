package com.hpu.mymoviestore

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hpu.mymoviestore.data.database.MovieDatabase
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.hpu.mymoviestore.data.repository.DownloadRepository
import com.hpu.mymoviestore.data.repository.PlayHistoryRepository
import com.hpu.mymoviestore.data.repository.SearchHistoryRepository
import com.hpu.mymoviestore.data.repository.SearchPermissionRepository
import com.hpu.mymoviestore.data.repository.VideoRepository
import com.hpu.mymoviestore.data.source.DoubanDiscoverySource
import com.hpu.mymoviestore.data.source.VideoSource
import com.hpu.mymoviestore.data.source.VideoSourceManager
import com.hpu.mymoviestore.data.source.impl.CechiVideoSource
import com.hpu.mymoviestore.data.source.impl.DoujiaoVideoSource
import com.hpu.mymoviestore.data.source.impl.HantvVideoSource
import com.hpu.mymoviestore.data.source.impl.JujiwuVideoSource
import com.hpu.mymoviestore.data.source.impl.NongmingVideoSource
import com.hpu.mymoviestore.data.source.impl.TiantangVideoSource
import com.hpu.mymoviestore.data.source.impl.YinghuaVideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 全局 Application
 *
 * 初始化：
 * - MovieDatabase（Room）：play_history / search_history / api_cache 三张表
 * - VideoSourceManager：读取 JSON 挡板数据，接入 api_cache 做 TTL 缓存
 * - VideoRepository：UI 获取视频列表
 * - PlayHistoryRepository：播放历史（含续播进度）
 * - SearchHistoryRepository：搜索历史
 * - ApiCacheRepository：爬虫源缓存（TTL）
 */
class MovieApplication : Application(), ImageLoaderFactory {

    private val TAG = "MovieApplication"

    lateinit var videoRepository: VideoRepository
        private set

    /** 所有视频源列表，供 ProfileFragment 等外部模块访问 */
    val allVideoSources: List<VideoSource>
        get() = _allVideoSources

    private lateinit var _allVideoSources: List<VideoSource>

    lateinit var playHistoryRepository: PlayHistoryRepository
        private set

    lateinit var searchHistoryRepository: SearchHistoryRepository
        private set

    lateinit var apiCacheRepository: ApiCacheRepository
        private set

    lateinit var downloadRepository: DownloadRepository
        private set

    lateinit var searchPermissionRepository: SearchPermissionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.d(TAG, "========== MovieApplication.onCreate 开始 ==========")

        val database = MovieDatabase.getInstance(this)
        Log.d(TAG, "Room 数据库初始化完成 (movie_database, v4)")

        // Repositories
        playHistoryRepository = PlayHistoryRepository(database.playHistoryDao())
        searchHistoryRepository = SearchHistoryRepository(database.searchHistoryDao())
        apiCacheRepository = ApiCacheRepository(database.apiCacheDao())
        downloadRepository = DownloadRepository(database.downloadTaskDao(), database.downloadedVideoIndexDao())
        searchPermissionRepository = SearchPermissionRepository(this, apiCacheRepository)
        Log.d(TAG, "数据仓库初始化完成 (PlayHistory/SearchHistory/ApiCache/Download/SearchPermission)")

        // 视频源：assets JSON 挡板 + Room 缓存(TTL=1 天)
        val sourceManager = VideoSourceManager(this, apiCacheRepository)
        Log.d(TAG, "VideoSourceManager 初始化完成（JSON 挡板 + ApiCache TTL 缓存）")

        // 新增：初始化多个爬虫源，并接入 ApiCacheRepository 做分类型 TTL 缓存
        // - 首页/详情页播放入口：1 天
        // - 真实播放地址（m3u8/mp4）：30 分钟
        val crawlerSource = JujiwuVideoSource(cacheRepository = apiCacheRepository)
        val yinghuaSource = YinghuaVideoSource(cacheRepository = apiCacheRepository)
        val tiantangSource = TiantangVideoSource(cacheRepository = apiCacheRepository)
        val hantvVideoSource = HantvVideoSource(cacheRepository = apiCacheRepository)
        val doujiaoVideoSource = DoujiaoVideoSource(cacheRepository = apiCacheRepository)
        val nongmingVideoSource = NongmingVideoSource(cacheRepository = apiCacheRepository)
        val cechiVideoSource = CechiVideoSource(cacheRepository = apiCacheRepository)
        val doubanDiscoverySource = DoubanDiscoverySource()
        _allVideoSources = listOf(nongmingVideoSource,cechiVideoSource,doujiaoVideoSource,hantvVideoSource, tiantangSource,crawlerSource,yinghuaSource)

        // 从 SharedPreferences 恢复视频源启用状态
        restoreSourceEnabledStates()

        videoRepository = VideoRepository(
            localSource = sourceManager,
            videoSources = _allVideoSources,
            discoverySource = doubanDiscoverySource,
            cacheRepository = apiCacheRepository,
            preferCrawler = true   // 暂时开启爬虫优先，上线前可改为 false 或通过配置控制
        )

        // 启动时顺手清理过期的爬虫缓存（避免数据库增长）
        val app = this
        Thread {
            try {
                // 这里走 DAO 的阻塞查询：cleanExpired 是 suspend 函数，
                // 在子线程中通过 runBlocking 执行不会阻塞 UI
                kotlinx.coroutines.runBlocking {
                    val deleted = app.apiCacheRepository.cleanExpiredInner()
                    if (deleted > 0) {
                        Log.d(TAG, "启动时清理过期 api_cache: 共删除 $deleted 行")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "清理过期缓存失败（非致命）: ${t.message}")
            }
        }.start()

        Log.d(TAG, "========== MovieApplication.onCreate 结束 ==========\n")

        // 应用重启后，将数据库中"下载中/等待中"的任务重置为"暂停"
        // 因为 DownloadEngine 是内存态的，重启后任务已丢失，需要让用户手动恢复
        CoroutineScope(Dispatchers.IO).launch {
            try {
                downloadRepository.pauseAll()
                Log.d(TAG, "已将所有活跃任务重置为暂停状态（应用重启）")
            } catch (e: Exception) {
                Log.w(TAG, "重置活跃任务状态失败: ${e.message}")
            }
        }

        // 应用启动时异步触发搜索权限检查（后台静默执行，不阻塞）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                searchPermissionRepository.fetchPermissionAsync()
                Log.d(TAG, "应用启动时搜索权限后台检查已触发")
            } catch (e: Exception) {
                Log.w(TAG, "搜索权限后台检查触发失败: ${e.message}")
            }
        }
    }

    /**
     * 从 SharedPreferences 恢复视频源启用状态。
     * 在 Application.onCreate 和 ProfileFragment.onViewCreated 中都会调用。
     */
    private fun restoreSourceEnabledStates() {
        val prefs = getSharedPreferences("video_sources", MODE_PRIVATE)
        _allVideoSources.forEach { source ->
            val enabled = prefs.getBoolean("enabled_${source.sourceId}", true)
            source.enabled = enabled
            Log.d(TAG, "恢复视频源状态: ${source.sourceName} enabled=$enabled")
        }
    }

    companion object {
        @Volatile
        private var instance: MovieApplication? = null

        private const val DOUBAN_IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun get(): MovieApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    /**
     * 全局 Coil 图片加载器。
     *
     * 豆瓣图片域名通常会检查 Referer / User-Agent，如果直接用默认 ImageView.load(url)
     * 可能被防盗链拦截。这里对豆瓣图片请求补充浏览器请求头，其他图片不受影响。
     */
    override fun newImageLoader(): ImageLoader {
        val imageClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host
                val builder = original.newBuilder()
                    .header("User-Agent", DOUBAN_IMAGE_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

                if (host.contains("doubanio.com") || host.contains("douban.com")) {
                    builder
                        .header("Referer", "https://movie.douban.com/")
                        .header("Origin", "https://movie.douban.com")
                    Log.d(TAG, "Coil 加载豆瓣图片，已添加防盗链请求头: ${original.url}")
                }

                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .crossfade(true)
            .build()
    }

}
