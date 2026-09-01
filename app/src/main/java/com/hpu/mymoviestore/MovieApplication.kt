package com.hpu.mymoviestore

import android.app.Application
import android.util.Log
import android.widget.Toast
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hpu.mymoviestore.data.database.MovieDatabase
import com.hpu.mymoviestore.data.repository.ApiCacheRepository
import com.hpu.mymoviestore.data.repository.DownloadRepository
import com.hpu.mymoviestore.data.repository.PermissionConfigRepository
import com.hpu.mymoviestore.data.repository.PlayHistoryRepository
import com.hpu.mymoviestore.data.repository.SearchHistoryRepository
import com.hpu.mymoviestore.data.repository.VideoRepository
import com.hpu.mymoviestore.data.source.DoubanDiscoverySource
import com.hpu.mymoviestore.data.source.VideoSource
import com.hpu.mymoviestore.data.source.VideoSourceConfigManager
import com.hpu.mymoviestore.data.source.VideoSourceManager
import com.hpu.mymoviestore.presentation.settings.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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

    /** Application 级协程作用域，生命周期与 Application 相同，用于下载回调等需要跨 Activity 生存的场景 */
    val applicationScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    lateinit var videoRepository: VideoRepository
        private set

    /** 所有视频源列表，由 VideoSourceConfigManager 从远程配置动态构建，供 ProfileFragment 等外部模块访问 */
    val allVideoSources: List<VideoSource>
        get() = _allVideoSources

    @Volatile
    private var _allVideoSources: List<VideoSource> = emptyList()

    lateinit var playHistoryRepository: PlayHistoryRepository
        private set

    lateinit var searchHistoryRepository: SearchHistoryRepository
        private set

    lateinit var apiCacheRepository: ApiCacheRepository
        private set

    lateinit var downloadRepository: DownloadRepository
        private set

    lateinit var permissionConfigRepository: PermissionConfigRepository
        private set

    /** 视频源远程配置管理器（播放源名称/URL 的远程动态配置） */
    lateinit var videoSourceConfigManager: VideoSourceConfigManager
        private set

    /**
     * 更新视频源列表（由 VideoSourceConfigManager 在远程配置加载/更新后调用）。
     * 同时更新 VideoRepository 内部的源列表引用。
     */
    fun updateVideoSources(sources: List<VideoSource>) {
        _allVideoSources = sources
        if (::videoRepository.isInitialized) {
            videoRepository.updateVideoSources(sources)
        }
        Log.d(TAG, "视频源列表已更新: ${sources.size} 个源")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 应用持久化的主题模式（浅色/深色），需在任何 Activity 创建前调用
        ThemeManager.applySaved(this)

        Log.d(TAG, "========== MovieApplication.onCreate 开始 ==========")

        val database = MovieDatabase.getInstance(this)
        Log.d(TAG, "Room 数据库初始化完成 (movie_database, v4)")

        // Repositories
        playHistoryRepository = PlayHistoryRepository(database.playHistoryDao())
        searchHistoryRepository = SearchHistoryRepository(database.searchHistoryDao())
        apiCacheRepository = ApiCacheRepository(database.apiCacheDao())
        downloadRepository = DownloadRepository(this, database.downloadTaskDao(), database.downloadedVideoIndexDao())
        permissionConfigRepository = PermissionConfigRepository(this, apiCacheRepository)
        Log.d(TAG, "数据仓库初始化完成 (PlayHistory/SearchHistory/ApiCache/Download/PermissionConfig)")

        // 视频源：assets JSON 挡板 + Room 缓存(TTL=1 天)
        val sourceManager = VideoSourceManager(this, apiCacheRepository)
        Log.d(TAG, "VideoSourceManager 初始化完成（JSON 挡板 + ApiCache TTL 缓存）")

        // 豆瓣发现源（非爬虫源，始终可用，不受远程配置控制）
        val doubanDiscoverySource = DoubanDiscoverySource()

        // 视频源列表初始为空，由 VideoSourceConfigManager 从远程配置动态构建
        // _allVideoSources 已初始化为 emptyList()

        // 先创建 VideoRepository（初始源列表为空，后续由 ConfigManager 更新）
        videoRepository = VideoRepository(
            localSource = sourceManager,
            videoSources = _allVideoSources,
            discoverySource = doubanDiscoverySource,
            cacheRepository = apiCacheRepository,
            preferCrawler = true   // 暂时开启爬虫优先，上线前可改为 false 或通过配置控制
        )

        // 视频源远程配置管理器：同步加载缓存（毫秒级）或首次远程获取（重试 5 次）
        videoSourceConfigManager = VideoSourceConfigManager(this, apiCacheRepository, applicationScope)
        videoSourceConfigManager.initConfig()
        videoSourceConfigManager.state.observeForever { state ->
            if (state == VideoSourceConfigManager.ConfigState.FAILED) {
                Log.w(TAG, "视频源配置获取失败，播放功能可能不可用")
            }
        }
        Log.d(TAG, "VideoSourceConfigManager 初始化完成")

        // 启动时顺手清理过期的爬虫缓存（避免数据库增长），并周期性清理
        // （启动单次清理只覆盖冷启动场景，App 常驻后台不重启时运行期死数据无法回收）
        applicationScope.launch {
            while (true) {
                try {
                    val deleted = apiCacheRepository.cleanExpiredInner()
                    if (deleted > 0) {
                        Log.d(TAG, "定时清理过期 api_cache: 共删除 $deleted 行")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "清理过期缓存失败（非致命）: ${t.message}")
                }
                delay(CACHE_CLEAN_INTERVAL_MS)
            }
        }

        Log.d(TAG, "========== MovieApplication.onCreate 结束 ==========\n")

        // 应用重启后，将数据库中"下载中/等待中"的任务重置为"暂停"
        // 因为 DownloadEngine 是内存态的，重启后任务已丢失，需要让用户手动恢复
        applicationScope.launch {
            try {
                downloadRepository.pauseAll()
                Log.d(TAG, "已将所有活跃任务重置为暂停状态（应用重启）")
            } catch (e: Exception) {
                Log.w(TAG, "重置活跃任务状态失败: ${e.message}")
            }
        }

        // 应用启动时异步触发权限配置检查（后台静默执行，不阻塞；搜索/弹幕等权限均来自该配置）
        applicationScope.launch {
            try {
                permissionConfigRepository.fetchPermissionAsync()
                Log.d(TAG, "应用启动时权限配置后台检查已触发")
            } catch (e: Exception) {
                Log.w(TAG, "权限配置后台检查触发失败: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MovieApplication? = null

        private const val DOUBAN_IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** api_cache 过期记录的周期性清理间隔：6 小时 */
        private const val CACHE_CLEAN_INTERVAL_MS = 6L * 60 * 60 * 1000

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
            // 磁盘缓存：LRU 策略，默认 250MB 偏大，收敛到 128MB
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .build()
    }

}
