package com.hpu.mymoviestore.presentation.activity

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.util.Log
import android.view.GestureDetector
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.database.MovieDatabase
import com.hpu.mymoviestore.data.download.DanmakuDownloadManager
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.model.danmaku.DanmakuCommentResponse
import com.hpu.mymoviestore.data.repository.DanmakuRepository
import com.hpu.mymoviestore.databinding.ActivityPlayerBinding
import com.hpu.mymoviestore.presentation.danmaku.DanmakuManager
import com.hpu.mymoviestore.presentation.danmaku.DanmakuPrefs
import com.hpu.mymoviestore.presentation.viewmodel.PlayerViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt

/**
 * 播放器 Activity
 *
 * 职责：
 * 1. 使用 Media3 (ExoPlayer) 播放视频
 * 2. 管理播放生命周期（初始化 / 暂停 / 恢复 / 释放）
 * 3. 播放启动后去重写入播放历史 + 播放进度（通过 PlayerViewModel）
 * 4. 弹幕层（DanmakuManager）：搜索弹幕源 → 选择 bangumi → 拉取 XML → 显示
 * 5. 弹幕控制：弹幕源 Spinner 切换 + 子开关（独立于"我的"页面总开关）
 * 6. 手势控制：双击暂停/播放、长按左右滑动快进/快退、长按上下滑动亮度/音量
 * 7. 屏幕锁定：锁定后禁用手势和按钮，仅接受返回和解锁
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var viewModel: PlayerViewModel

    // 播放器相关
    private var player: ExoPlayer? = null
    private var videoId: Long = 0
    private var videoTitle: String = ""
    private var videoUrl: String = ""
    private var episodeTitle: String = ""
    private var resumeFromMs: Long = 0L
    private var isOfflineMode: Boolean = false
    private var offlineTaskId: String = ""
    private var hasSeekedResume: Boolean = false
    private var lastSavedProgressMs: Long = -1L
    private var lastSyncPositionMs: Long = -1L
    private val progressSaveIntervalMs: Long = 30_000L

    // 弹幕相关
    private var danmakuManager: DanmakuManager? = null
    private lateinit var danmakuRepository: DanmakuRepository
    private var candidateList: List<DanmakuAnime> = emptyList()
    private var selectedBangumi: DanmakuBangumi? = null
        private var danmakuLoadJob: Job? = null
        private var danmakuSearchJob: Job? = null
        private var lastLoadedAnimeId: Long = 0L
        private var isRestoringSelection: Boolean = false
        private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // PlayerView 内部的弹幕控件引用（在自定义控制栏中）
    private val danmakuSwitch: android.widget.Switch get() =
        binding.playerView.findViewById(R.id.switchDanmaku)!!
    private val danmakuSpinner: android.widget.Spinner get() =
        binding.playerView.findViewById(R.id.spinnerDanmakuSource)!!

    // 手势相关
    private lateinit var gestureDetector: GestureDetector
    private var isLongPressing = false
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // 亮度/音量
    private var currentBrightness = -1f
    private var currentVolume = -1
    private var currentVolumeFloat = -1f  // 音量浮点数，用于连续调节
    private var maxVolume = 0

    // 手势方向锁定：长按触发后确定方向，整个手势过程不再切换
    // 0=未锁定, 1=水平(快进), 2=垂直(亮度/音量)
    private var gestureDirection = 0

    // 快进拖拽：记录长按触发时的播放位置，MOVE 时只更新预览偏移
    private var seekBaseMs = 0L     // 长按触发时的 currentPosition
    private var seekTargetMs = 0L    // 手指抬起时要 seek 到的位置

    // 屏幕锁定
    private var isScreenLocked = false

    // 状态信息显示（时间、电量、网络）
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkReceiver: BroadcastReceiver? = null
    private var timeUpdateHandler: Handler? = null
    private var timeUpdateRunnable: Runnable? = null

    companion object {
        private const val TAG = "PlayerActivity"
        private const val LONG_PRESS_THRESHOLD_MS = 500L  // 拉长到 500ms，避免误触发
        private const val SEEK_STEP_MS = 10_000L
        private const val GESTURE_DIR_HORIZONTAL = 1
        private const val GESTURE_DIR_VERTICAL = 2

        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_COVER = "extra_video_cover"
        const val EXTRA_VIDEO_CATEGORY = "extra_video_category"
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_DETAIL_URL = "extra_detail_url"
        const val EXTRA_PLAY_PAGE_URL = "extra_play_page_url"
        const val EXTRA_EPISODE_TITLE = "extra_episode_title"
        const val EXTRA_SOURCE_NAME = "extra_source_name"
        const val EXTRA_LOCAL_FILE_PATH = "extra_local_file_path"
        const val EXTRA_DANMAKU_FILE_PATH = "extra_danmaku_file_path"

        fun newIntent(
            context: Context,
            videoId: Long,
            title: String,
            coverUrl: String,
            category: String,
            url: String,
            detailUrl: String = "",
            playPageUrl: String = "",
            episodeTitle: String = "",
            sourceName: String = ""
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_VIDEO_TITLE, title)
                putExtra(EXTRA_VIDEO_COVER, coverUrl)
                putExtra(EXTRA_VIDEO_CATEGORY, category)
                putExtra(EXTRA_VIDEO_URL, url)
                putExtra(EXTRA_DETAIL_URL, detailUrl)
                putExtra(EXTRA_PLAY_PAGE_URL, playPageUrl)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
                putExtra(EXTRA_SOURCE_NAME, sourceName)
            }
        }

        /**
         * 创建离线播放的 Intent
         *
         * @param localFilePath 本地 mp4 文件路径
         * @param danmakuFilePath 本地弹幕 json 文件路径（可选）
         * @param title 视频标题（用于显示）
         * @param episodeTitle 集数标题（可选）
         */
        fun newIntent(
            context: Context,
            localFilePath: String,
            danmakuFilePath: String? = null,
            title: String = "",
            episodeTitle: String = ""
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_LOCAL_FILE_PATH, localFilePath)
                if (danmakuFilePath != null) {
                    putExtra(EXTRA_DANMAKU_FILE_PATH, danmakuFilePath)
                }
                putExtra(EXTRA_VIDEO_TITLE, title)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.danmakuContainer.bringToFront()
        binding.danmakuContainer.clipChildren = true
        binding.danmakuContainer.clipToPadding = false

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        videoId = intent.getLongExtra(EXTRA_VIDEO_ID, 0)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: ""
        val coverUrl = intent.getStringExtra(EXTRA_VIDEO_COVER) ?: ""
        val category = intent.getStringExtra(EXTRA_VIDEO_CATEGORY) ?: ""
        val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME) ?: ""

        // 离线播放相关参数
        val localFilePath = intent.getStringExtra(EXTRA_LOCAL_FILE_PATH)
        val danmakuFilePath = intent.getStringExtra(EXTRA_DANMAKU_FILE_PATH)
        val offlineTaskId = intent.getStringExtra("extra_offline_task_id") ?: ""
        isOfflineMode = !localFilePath.isNullOrEmpty()

        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        Log.d(TAG, "========== PlayerActivity.onCreate ==========")
        Log.d(TAG, "收到 Intent: videoId=$videoId, title=$videoTitle, category=$category, source=$sourceName")

        title = videoTitle

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        danmakuRepository = DanmakuRepository(context = this)

        setupPlayerUi()
        setupDanmakuUi()
        setupGestures()
        setupLockButton()

        if (!localFilePath.isNullOrEmpty()) {
            // ========== 离线播放模式 ==========
            Log.d(TAG, "离线播放模式: localFilePath=$localFilePath, taskId=$offlineTaskId")
            this.offlineTaskId = offlineTaskId
            this.videoId = offlineTaskId.substringBeforeLast("_").toLongOrNull() ?: 0L
            Log.d(TAG, "离线模式解析 videoId=$videoId from taskId=$offlineTaskId")
            val localFile = File(localFilePath)
            if (localFile.exists()) {
                val localUri = Uri.fromFile(localFile)
                Log.d(TAG, "本地文件存在，使用 Uri: $localUri")
                initializePlayerWithLocalFile(localUri)
                startOfflinePlayback(offlineTaskId, danmakuFilePath)
            } else {
                Log.e(TAG, "本地文件不存在: $localFilePath")
                Toast.makeText(this, "本地文件不存在: $localFilePath", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            // ========== 在线播放模式 ==========
            viewModel.getHistoryByVideoId(videoId) { history ->
                val canResume = history != null && history.playProgressSeconds > 0 &&
                    (videoUrl.isBlank() || history.playUrl == videoUrl || true)
                resumeFromMs = if (canResume) history.playProgressSeconds * 1000 else 0L
                if (resumeFromMs > 0) {
                    Log.d(TAG, "上次进度 ${history!!.playProgressSeconds}s → seekTo $resumeFromMs ms")
                } else {
                    Log.d(TAG, "无历史进度或 URL 不匹配 → 从头播放")
                }

                viewModel.setVideoInfo(
                    videoId = videoId,
                    title = videoTitle,
                    coverUrl = coverUrl,
                    category = category,
                    playUrl = videoUrl,
                    detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL) ?: "",
                    playPageUrl = intent.getStringExtra(EXTRA_PLAY_PAGE_URL) ?: "",
                    episodeTitle = episodeTitle,
                    sourceName = sourceName
                )

                if (videoUrl.isEmpty()) {
                    viewModel.getVideoInfoById(videoId) { video ->
                        val url = video?.playUrl.orEmpty()
                        Log.d(TAG, "回查 videoId=$videoId → url=$url")
                        if (url.isNotEmpty()) {
                            videoUrl = url
                            runOnUiThread { initializePlayer(url) }
                        }
                    }
                } else {
                    initializePlayer(videoUrl)
                }
            }

            launchDanmakuSearch(videoTitle, episodeTitle)
        }
    }

    // ================== 播放器初始化 ==================

    private fun initializePlayer(url: String) {
        if (url.isEmpty()) {
            Log.w(TAG, "播放地址为空，无法初始化播放器")
            return
        }

        val dataSourceFactory = OkHttpDataSource.Factory(OkHttpClient.Builder().build())
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                val mediaItem = MediaItem.fromUri(url)
                exoPlayer.setMediaItem(mediaItem)
                Log.d(TAG, "设置 MediaItem: ${url.take(80)}")
                exoPlayer.prepare()
                if (resumeFromMs > 0) {
                    exoPlayer.seekTo(resumeFromMs)
                    Log.d(TAG, "续播: seekTo ${resumeFromMs}ms")
                }
                exoPlayer.playWhenReady = true
                Log.d(TAG, "播放器 prepare() 完成，playWhenReady=true")

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d(TAG, "播放状态: STATE_READY")
                                danmakuManager?.ensureStarted()
                                danmakuManager?.setPaused(!exoPlayer.isPlaying)
                            }
                            Player.STATE_BUFFERING -> Log.d(TAG, "播放状态: STATE_BUFFERING")
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "播放状态: STATE_ENDED，保存最终进度")
                                saveCurrentProgress(true)
                            }
                            Player.STATE_IDLE -> Log.d(TAG, "播放状态: STATE_IDLE")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
                        danmakuManager?.setPaused(!isPlaying)
                        // 更新播放/暂停按钮图标
                        updatePlayPauseIcon(isPlaying)
                    }

                    // 添加错误监听
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "在线播放错误: ${error.message}", error)
                        Log.e(TAG, "错误类型: ${error.errorCodeName}, errorCode: ${error.errorCode}")
                        
                        // 尝试从错误中恢复（如广告导致的时间戳不连续）
                        tryRecoverFromError(exoPlayer, error)
                    }
                })
            }

        startProgressSyncRunnable()
    }

    /**
     * 使用本地文件初始化播放器（离线播放）
     * 不使用 OkHttp DataSourceFactory，使用默认的 DefaultMediaSourceFactory
     */
    private fun initializePlayerWithLocalFile(uri: Uri) {
        player = ExoPlayer.Builder(this).build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer.setMediaItem(mediaItem)
                Log.d(TAG, "离线播放 - 设置 MediaItem: $uri")
                Log.d(TAG, "离线播放 - URI scheme: ${uri.scheme}, path: ${uri.path}")
                
                // 增强日志记录
                if (uri.scheme == "file") {
                    val file = File(uri.path!!)
                    Log.d(TAG, "离线播放 - 文件存在: ${file.exists()}, 可读: ${file.canRead()}, 大小: ${file.length()} bytes")
                }
                
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                Log.d(TAG, "离线播放 - 播放器 prepare() 完成，playWhenReady=true")

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d(TAG, "离线播放状态: STATE_READY")
                                // 续播：seek 到上次进度（只执行一次）
                                if (!hasSeekedResume && resumeFromMs > 0 && exoPlayer.duration > 0) {
                                    hasSeekedResume = true
                                    exoPlayer.seekTo(resumeFromMs)
                                    Log.d(TAG, "离线播放续播: seekTo ${resumeFromMs}ms")
                                }
                                danmakuManager?.ensureStarted()
                                danmakuManager?.setPaused(!exoPlayer.isPlaying)
                                // 确保控制栏显示
                                binding.playerView.showController()
                            }
                            Player.STATE_BUFFERING -> Log.d(TAG, "离线播放状态: STATE_BUFFERING")
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "离线播放状态: STATE_ENDED")
                            }
                            Player.STATE_IDLE -> Log.d(TAG, "离线播放状态: STATE_IDLE")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "离线播放 onIsPlayingChanged: isPlaying=$isPlaying")
                        danmakuManager?.setPaused(!isPlaying)
                        updatePlayPauseIcon(isPlaying)
                    }

                    // 添加错误监听
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "离线播放错误: ${error.message}", error)
                        Log.e(TAG, "错误类型: ${error.errorCodeName}, errorCode: ${error.errorCode}")
                        
                        // 尝试从错误中恢复（如广告导致的时间戳不连续）
                        tryRecoverFromError(exoPlayer, error)
                    }
                })
            }

        startProgressSyncRunnable()
    }

    /**
     * 尝试从播放错误中恢复（如广告导致的时间戳不连续）
     * 如果是音频时间戳不连续，尝试 seek 跳过问题片段
     */
    private fun tryRecoverFromError(exoPlayer: ExoPlayer, error: androidx.media3.common.PlaybackException) {
        val isAudioError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.message?.contains("discontinuity", ignoreCase = true) == true ||
                error.message?.contains("timestamp", ignoreCase = true) == true

        if (isAudioError && exoPlayer.playbackState != Player.STATE_IDLE) {
            Log.w(TAG, "检测到音频错误，尝试恢复播放: ${error.errorCodeName}")
            Toast.makeText(this@PlayerActivity, "广告已跳过", Toast.LENGTH_SHORT).show()

            // seek 到当前位置之后 2 秒，跳过广告片段
            val currentPos = exoPlayer.currentPosition
            val seekTo = (currentPos + 2000).coerceAtMost(exoPlayer.duration)
            Log.d(TAG, "恢复播放: seekTo ${seekTo}ms (当前位置: ${currentPos}ms)")

            try {
                exoPlayer.seekTo(seekTo)
                exoPlayer.playWhenReady = true
                Log.d(TAG, "错误恢复: seek 成功，继续播放")
            } catch (e: Exception) {
                Log.e(TAG, "错误恢复失败: ${e.message}", e)
                Toast.makeText(this@PlayerActivity, "播放失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this@PlayerActivity, "播放错误: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 从本地 JSON 文件加载弹幕（离线播放）
     * 支持两种格式：
     * 1. 数组格式：直接是 [{"cid":1, "p":"...", "m":"..."}, ...]
     * 2. 对象格式：{"count": N, "comments": [{"cid":1, "p":"...", "m":"..."}, ...]}
     */
    private fun loadDanmakuFromLocalFile(file: File) {
        danmakuLoadJob?.cancel()
        danmakuLoadJob = uiScope.launch {
            val comments = withContext(Dispatchers.IO) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                    
                    // 尝试按数组格式解析（直接是 List<DanmakuComment>）
                    return@withContext try {
                        val type = Types.newParameterizedType(List::class.java, DanmakuComment::class.java)
                        val listAdapter = moshi.adapter<List<DanmakuComment>>(type)
                        val list = listAdapter.fromJson(json)
                        Log.d(TAG, "本地弹幕文件解析成功(数组格式): ${list?.size ?: 0} 条")
                        list ?: emptyList()
                    } catch (e: Exception) {
                        // 如果数组解析失败，尝试按对象格式解析（DanmakuCommentResponse）
                        try {
                            val objAdapter = moshi.adapter(DanmakuCommentResponse::class.java)
                            val response = objAdapter.fromJson(json)
                            Log.d(TAG, "本地弹幕文件解析成功(对象格式): ${response?.comments?.size ?: 0} 条")
                            response?.comments ?: emptyList()
                        } catch (e2: Exception) {
                            Log.e(TAG, "本地弹幕文件解析失败(两种格式都失败): ${e2.message}", e2)
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "本地弹幕文件读取失败: ${e.message}", e)
                    emptyList()
                }
            }

            if (comments.isEmpty()) {
                Log.w(TAG, "本地弹幕列表为空")
                danmakuManager?.loadDanmaku(null)
                // 弹幕为空时，显示 Spinner 让用户可以搜索在线弹幕源
                danmakuSpinner.visibility = View.VISIBLE
            } else {
                Log.d(TAG, "加载本地弹幕成功: ${comments.size} 条")
                danmakuManager?.loadDanmaku(comments)
                player?.let { p ->
                    danmakuManager?.syncTo(p.currentPosition)
                    if (danmakuSwitch.isChecked) danmakuManager?.ensureStarted()
                }
                // 离线播放且有本地弹幕时，隐藏弹幕源 Spinner（不需要切换源）
                danmakuSpinner.visibility = View.GONE
                Log.d(TAG, "本地弹幕加载成功，隐藏弹幕源 Spinner")
            }
        }
    }

    // ================== 进度同步 ==================

    private val progressSyncRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val currentMs = p.currentPosition
                val timeDiff = kotlin.math.abs(currentMs - lastSyncPositionMs)
                if (timeDiff > 3000L && lastSyncPositionMs >= 0) {
                    danmakuManager?.seekTo(currentMs)
                } else {
                    danmakuManager?.syncTo(currentMs)
                }
                lastSyncPositionMs = currentMs

                val delta = currentMs - lastSavedProgressMs
                if (delta >= progressSaveIntervalMs || delta < 0) {
                    saveCurrentProgress(false)
                    lastSavedProgressMs = currentMs
                }

                // 更新锁定状态下的只读进度条
                if (isScreenLocked) {
                    updateLockedProgress()
                }
            }
            binding.playerView.postDelayed(this, 1000)
        }
    }

    private fun startProgressSyncRunnable() {
        binding.playerView.removeCallbacks(progressSyncRunnable)
        binding.playerView.postDelayed(progressSyncRunnable, 1000)
    }

    private fun stopProgressSyncRunnable() {
        binding.playerView.removeCallbacks(progressSyncRunnable)
    }

    private fun saveCurrentProgress(force: Boolean) {
        val p = player ?: return
        val currentMs = p.currentPosition
        val currentSec = currentMs / 1000
        val durMs = p.duration
        val durSec = if (durMs > 0) durMs / 1000 else 0L

        if (isOfflineMode && offlineTaskId.isNotEmpty()) {
            // 离线播放：保存进度到下载任务，不记录历史
            val percent = if (durMs > 0) ((currentMs * 100) / durMs).toInt() else -1
            val app = MovieApplication.get()
            uiScope.launch(Dispatchers.IO) {
                try {
                    app.downloadRepository.updateOfflinePlayProgress(
                        offlineTaskId, percent, currentMs, durMs
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "保存离线播放进度失败: ${e.message}")
                }
            }
            if (force) Log.d(TAG, "离线进度: ${currentMs}ms / ${durMs}ms ($percent%)")
        } else {
            // 在线播放：保存到历史记录
            viewModel.updateProgress(videoId, currentSec, durSec)
            if (force) Log.d(TAG, "强制写入进度: ${currentSec}s / ${durSec}s")
        }
    }

    // ================== UI 设置 ==================

    private fun setupPlayerUi() {
        binding.tvPlayerTitle.text = videoTitle.ifBlank { "正在播放" }
        val normalized = normalizeEpisodeTitle(episodeTitle)
        if (normalized.isNotBlank()) {
            binding.tvPlayerEpisode.text = normalized
            binding.tvPlayerEpisode.visibility = View.VISIBLE
        } else {
            binding.tvPlayerEpisode.visibility = View.GONE
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRotate.setOnClickListener {
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            requestedOrientation = if (isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }

        // 绑定自定义播放器控制按钮（快退10s、播放/暂停、快进10s）
        binding.playerView.findViewById<android.widget.ImageButton>(R.id.btnRewind10)?.setOnClickListener {
            player?.let { p ->
                val newPos = (p.currentPosition - 10_000).coerceAtLeast(0)
                p.seekTo(newPos)
                danmakuManager?.seekTo(newPos)
            }
        }
        binding.playerView.findViewById<android.widget.ImageButton>(R.id.btnForward10)?.setOnClickListener {
            player?.let { p ->
                val newPos = (p.currentPosition + 10_000).coerceAtMost(p.duration)
                p.seekTo(newPos)
                danmakuManager?.seekTo(newPos)
            }
        }
        val btnPlayPause = binding.playerView.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
        btnPlayPause?.setOnClickListener {
            togglePlayPause()
        }

        // 弹幕控制条跟随播放器控制栏显示/隐藏
        val listener = object : androidx.media3.ui.PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                binding.topControls.visibility = visibility
                binding.statusContainer.visibility = visibility
                if (!isScreenLocked) {
                    binding.btnLock.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                }
            }
        }
        binding.playerView.setControllerVisibilityListener(listener)

        // 初始化状态信息显示（时间、电量、网络）
        initStatusInfo()
    }

    // ================== 弹幕 UI ==================

    private fun setupDanmakuUi() {
        val container = binding.danmakuContainer
        container.setBackgroundColor(Color.TRANSPARENT)
        container.clipChildren = true
        container.isClickable = false
        container.isFocusable = false
        Log.d(TAG, "弹幕容器使用 XML 约束高度 25%")

        val dm = DanmakuManager(this)
        dm.attachToContainer(container)
        this.danmakuManager = dm

        val prefs = DanmakuPrefs(this)
        val masterEnabled = prefs.isMasterEnabled()
        val subEnabled = masterEnabled
        danmakuSwitch.isChecked = subEnabled
        dm.setDanmakuEnabled(subEnabled)
        Log.d(TAG, "弹幕子开关初始状态: $subEnabled (总开关=$masterEnabled)")

        danmakuSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "弹幕子开关切换: $isChecked")
            dm.setDanmakuEnabled(isChecked)
        }

        val initialTitles = listOf("搜索弹幕源中…")
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, initialTitles
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.textSize = 13f
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setBackgroundColor(ContextCompat.getColor(context, R.color.colorSurface))
                v.setPadding(24, 20, 24, 20)
                v.textSize = 13f
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        danmakuSpinner.adapter = adapter

        danmakuSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isRestoringSelection) return
                if (position < 0 || position >= candidateList.size) return
                val anime = candidateList[position]
                Log.d(TAG, "用户选择弹幕源: animeId=${anime.animeId}, title=${anime.animeTitle}")
                DanmakuPrefs(this@PlayerActivity).saveAnimeId(videoId, anime.animeId)
                // 强制重新加载，不使用 lastLoadedAnimeId 跳过
                lastLoadedAnimeId = 0L
                // 提取集数数字，兼容不同源的集数格式
                val epNum = extractEpisodeNumber(episodeTitle)
                loadDanmakuForAnime(anime.animeId, epNum)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // ================== 屏幕锁定 ==================

    private fun setupLockButton() {
        binding.btnLock.setOnClickListener {
            if (isScreenLocked) {
                // 解锁
                isScreenLocked = false
                binding.btnLock.setImageResource(R.drawable.ic_player_unlock)
                binding.playerView.useController = true
                binding.playerView.showController()
                binding.lockedProgressBar.visibility = View.GONE
                Log.d(TAG, "屏幕已解锁")
            } else {
                // 锁定
                isScreenLocked = true
                binding.btnLock.setImageResource(R.drawable.ic_player_lock)
                binding.playerView.useController = false
                binding.playerView.hideController()
                binding.lockedProgressBar.visibility = View.VISIBLE
                updateLockedProgress()
                Log.d(TAG, "屏幕已锁定")
            }
        }
    }

    /** 更新锁定状态下的只读进度条 */
    private fun updateLockedProgress() {
        val p = player ?: return
        val currentMs = p.currentPosition
        val durationMs = p.duration
        binding.tvLockedPosition.text = formatTime(currentMs)
        binding.tvLockedDuration.text = formatTime(durationMs)
        val percent = if (durationMs > 0) ((currentMs * 100) / durationMs).toInt() else 0
        binding.progressBarLocked.progress = percent
    }

    // ================== 手势控制 ==================

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isScreenLocked) return true
                togglePlayPause()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isScreenLocked) {
                    // 锁定状态下点击显示/隐藏锁定按钮和进度条
                    val show = binding.btnLock.visibility != View.VISIBLE
                    binding.btnLock.visibility = if (show) View.VISIBLE else View.GONE
                    binding.lockedProgressBar.visibility = if (show) View.VISIBLE else View.GONE
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isScreenLocked) {
            gestureDetector.onTouchEvent(event)
            return super.dispatchTouchEvent(event)
        }

        // 检测触摸是否在进度条区域（ExoPlayer 的 DefaultTimeBar）
        if (isTouchOnProgressBar(event)) {
            // 进度条上的触摸：如果正在长按手势，先结束
            if (isLongPressing) {
                finishLongPressGesture()
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handler.removeCallbacks(longPressRunnable)
            }
            return super.dispatchTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 正在长按手势时，忽略新的 ACTION_DOWN（华为 HiTouch 会注入假的 DOWN 事件）
                if (isLongPressing) {
                    Log.d(TAG, "长按手势中收到 ACTION_DOWN，已忽略（华为 HiTouch 干扰）")
                    return true
                }
                isLongPressing = false
                gestureDirection = 0
                seekBaseMs = 0L
                seekTargetMs = 0L
                longPressStartX = event.x
                longPressStartY = event.y
                currentBrightness = -1f
                currentVolume = -1
                currentVolumeFloat = -1f
                handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isLongPressing) {
                    handleLongPressMove(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (isLongPressing) {
                    finishLongPressGesture()
                    return true
                }
                gestureDirection = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                // 正在长按手势时，忽略 ACTION_CANCEL（华为 HiTouch 会触发 CANCEL 导致手势中断）
                if (isLongPressing) {
                    Log.d(TAG, "长按手势中收到 ACTION_CANCEL，已忽略（华为 HiTouch 干扰）")
                    return true
                }
                gestureDirection = 0
            }
        }

        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /**
     * 检测触摸事件是否在 ExoPlayer 进度条（DefaultTimeBar）区域内
     */
    private fun isTouchOnProgressBar(event: MotionEvent): Boolean {
        try {
            val playerView = binding.playerView
            // ExoPlayer 的进度条 ID 是 exo_progress
            val progressBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                ?: playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress_placeholder)
                ?: return false

            val location = IntArray(2)
            progressBar.getLocationOnScreen(location)
            val rect = Rect(
                location[0],
                location[1],
                location[0] + progressBar.width,
                location[1] + progressBar.height
            )
            // 扩大检测区域（进度条上下各 20dp 的触摸热区）
            val touchSlop = (20 * resources.displayMetrics.density).toInt()
            rect.top -= touchSlop
            rect.bottom += touchSlop
            return rect.contains(event.rawX.toInt(), event.rawY.toInt())
        } catch (e: Exception) {
            return false
        }
    }

    private val longPressRunnable = Runnable {
        isLongPressing = true
        gestureDirection = 0 // 尚未锁定方向，等第一次 MOVE 再决定
        Log.d(TAG, "长按触发，等待方向锁定")
    }

    /**
     * 长按触发后，第一次 MOVE 时根据滑动方向锁定手势类型：
     * - 水平滑动 → 快进/快退（整手势过程不再响应垂直）
     * - 垂直滑动 → 亮度/音量（整手势过程不再响应水平）
     *
     * 快进逻辑：实时显示预览位置（基于按下时的进度 + 拖拽偏移），
     *           手指抬起时才真正 seek，避免频繁 seek 导致卡顿。
     */
    private fun handleLongPressMove(event: MotionEvent) {
        val deltaX = event.x - longPressStartX
        val deltaY = event.y - longPressStartY
        val absX = abs(deltaX)
        val absY = abs(deltaY)

        // 首次 MOVE：根据方向锁定手势类型
        if (gestureDirection == 0) {
            if (absX < 15 && absY < 15) return // 忽略微小移动
            gestureDirection = if (absX >= absY) GESTURE_DIR_HORIZONTAL else GESTURE_DIR_VERTICAL
            // 锁定方向后重置起始位置，避免累计误差
            longPressStartX = event.x
            longPressStartY = event.y
            seekBaseMs = player?.currentPosition ?: 0L
            seekTargetMs = seekBaseMs
            Log.d(TAG, "手势方向锁定: ${if (gestureDirection == GESTURE_DIR_HORIZONTAL) "水平(快进)" else "垂直(亮度/音量)"}")
            return
        }

        when (gestureDirection) {
            GESTURE_DIR_HORIZONTAL -> {
                // 水平：快进/快退预览（不真正 seek，只更新 UI）
                // 滑动距离占屏幕宽度比例 → 最大偏移 90 秒
                val maxSeekMs = 90_000L
                val offsetMs = (deltaX / screenWidth * maxSeekMs).toLong()
                seekTargetMs = (seekBaseMs + offsetMs).coerceIn(0L, player?.duration ?: 0L)
                val diffSec = (seekTargetMs - seekBaseMs) / 1000
                val currentSec = seekTargetMs / 1000
                val totalSec = (player?.duration ?: 0L) / 1000
                val sign = if (diffSec >= 0) "+" else ""
                showGestureTip("$sign${diffSec}s / ${currentSec}s (共${totalSec}s)")
            }
            GESTURE_DIR_VERTICAL -> {
                // 垂直：亮度/音量（传入本次 MOVE 的增量 deltaY，每次只调一点）
                val deltaY = event.y - longPressStartY
                // 死区：忽略小于 5px 的抖动（华为 HiTouch 会导致微小反方向抖动）
                if (abs(deltaY) >= 5) {
                    if (event.x < screenWidth / 2) {
                        adjustBrightness(deltaY)
                    } else {
                        adjustVolume(deltaY)
                    }
                    // 更新起始 Y，使下次 MOVE 基于当前位置计算增量
                    longPressStartY = event.y
                }
            }
        }
    }

    /**
     * 长按手势结束（手指抬起）：
     * - 水平方向：真正 seek 到预览位置
     * - 垂直方向：清除提示
     */
    private fun finishLongPressGesture() {
        if (gestureDirection == GESTURE_DIR_HORIZONTAL) {
            player?.let { p ->
                val finalMs = seekTargetMs.coerceIn(0L, p.duration)
                p.seekTo(finalMs)
                Log.d(TAG, "快进/快退完成: seekTo=${finalMs}ms")
            }
        }
        isLongPressing = false
        gestureDirection = 0
        seekBaseMs = 0L
        seekTargetMs = 0L
        currentVolumeFloat = -1f // 重置音量浮点数
        hideGestureTip()
    }

    /**
     * deltaY：本次 MOVE 的像素增量（上滑为负，下滑为正）
     * 亮度：上滑变暗，下滑变亮
     * 阈值：每 300px 对应 1.0 的亮度变化（比之前 400px 更灵敏）
     */
    private fun adjustBrightness(deltaY: Float) {
        val layoutParams = window.attributes
        if (currentBrightness < 0) {
            currentBrightness = layoutParams.screenBrightness
            if (currentBrightness < 0) currentBrightness = 0.5f
        }
        val change = -deltaY / 300f
        currentBrightness = (currentBrightness + change).coerceIn(0.05f, 1f)
        layoutParams.screenBrightness = currentBrightness
        window.attributes = layoutParams
        showGestureTip("亮度 ${(currentBrightness * 100).roundToInt()}%")
    }

    /**
     * deltaY：本次 MOVE 的像素增量（上滑为负，下滑为正）
     * 音量：上滑升高，下滑降低
     * 连续调整：每 100px 对应 1 档音量变化（和亮度调节一样丝滑）
     */
    private fun adjustVolume(deltaY: Float) {
        if (currentVolumeFloat < 0) {
            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            currentVolumeFloat = currentVolume.toFloat()
        }
        // deltaY 每 100px 对应 1 档音量变化（更灵敏，和亮度一样连续）
        val change = -deltaY / 100f
        currentVolumeFloat = (currentVolumeFloat + change).coerceIn(0f, maxVolume.toFloat())
        currentVolume = currentVolumeFloat.roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        Log.d(TAG, "音量调节: currentVolume=$currentVolume, change=$change, float=$currentVolumeFloat")
        showGestureTip("音量 ${if (maxVolume > 0) (currentVolume * 100 / maxVolume) else 0}%")
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
            } else {
                p.play()
            }
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val playPauseBtn = binding.playerView.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
        playPauseBtn?.setImageResource(
            if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
    }

    private fun showGestureTip(text: String) {
        binding.tvGestureTip.text = text
        binding.tvGestureTip.visibility = View.VISIBLE
    }

    private fun hideGestureTip() {
        binding.tvGestureTip.visibility = View.GONE
    }

    // ================== 弹幕搜索/加载 ==================

    /** 从标题中提取集数数字，兼容不同源的集数格式（"第1集"、"EP1" → "1"） */
    private fun extractEpisodeNumber(title: String): String {
        val match = Regex("""(\d+)""").find(title)
        return match?.value ?: title
    }

    private fun launchDanmakuSearch(title: String, episode: String) {
        danmakuSearchJob?.cancel()
        danmakuLoadJob?.cancel()

        val prefs = DanmakuPrefs(this)
        val savedAnimeId = prefs.getSavedAnimeId(videoId)

        // 有保存的弹幕源时，提前直接加载（搜索完成前即可显示）
        if (savedAnimeId > 0L) {
            Log.d(TAG, "发现保存的弹幕源: videoId=$videoId, savedAnimeId=$savedAnimeId")
            val epNum = extractEpisodeNumber(episode)
            uiScope.launch {
                loadDanmakuForAnime(savedAnimeId, epNum)
            }
        }

        danmakuSearchJob = uiScope.launch {
            try {
                val candidates = danmakuRepository.searchCandidates(title)
                Log.d(TAG, "弹幕搜索完成: ${candidates.size} 条")

                if (candidates.isEmpty()) {
                    danmakuSpinner.visibility = View.GONE
                    return@launch
                }

                candidateList = candidates
                val sourceLabels = candidates.map { "弹幕源 ${it.source}" }
                val adapter = object : ArrayAdapter<String>(
                    this@PlayerActivity, android.R.layout.simple_spinner_item, sourceLabels
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent) as TextView
                        v.setTextColor(Color.WHITE)
                        v.textSize = 13f
                        return v
                    }
                    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getDropDownView(position, convertView, parent) as TextView
                        v.text = candidateList.getOrNull(position)?.animeTitle ?: ""
                        v.setTextColor(Color.WHITE)
                        v.setBackgroundColor(ContextCompat.getColor(context, R.color.colorSurface))
                        v.setPadding(24, 20, 24, 20)
                        v.textSize = 13f
                        return v
                    }
                }
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

                // 设置适配器和选中位置时不触发 onItemSelected 保存偏好
                isRestoringSelection = true
                danmakuSpinner.adapter = adapter

                if (savedAnimeId > 0L) {
                    val savedIndex = candidates.indexOfFirst { it.animeId == savedAnimeId }
                    if (savedIndex >= 0) {
                        danmakuSpinner.setSelection(savedIndex)
                        Log.d(TAG, "Spinner 选中保存的弹幕源: position=$savedIndex, animeId=$savedAnimeId")
                    } else {
                        Log.w(TAG, "保存的弹幕源不在搜索结果中: savedAnimeId=$savedAnimeId")
                        // 不覆盖偏好，仅 UI 选中第一个
                        danmakuSpinner.setSelection(0)
                    }
                } else {
                    danmakuSpinner.setSelection(0)
                }
                isRestoringSelection = false
            } catch (e: Exception) {
                Log.e(TAG, "弹幕搜索失败: ${e.message}", e)
                danmakuSpinner.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, "弹幕搜索失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadDanmakuForAnime(animeId: Long, episode: String) {
        if (animeId == lastLoadedAnimeId) {
            Log.d(TAG, "弹幕源已加载，跳过: animeId=$animeId")
            return
        }
        lastLoadedAnimeId = animeId

        danmakuLoadJob?.cancel()
        danmakuLoadJob = uiScope.launch {
            val bangumi = danmakuRepository.fetchBangumi(
                animeId = animeId, keyword = videoTitle
            ) { success, _, _ ->
                if (!success) Log.w(TAG, "获取 bangumi 最终失败: animeId=$animeId")
            }
            if (bangumi == null) {
                Log.w(TAG, "bangumi 为空，无法加载弹幕: animeId=$animeId")
                return@launch
            }
            selectedBangumi = bangumi
            Log.d(TAG, "获取 bangumi: title=${bangumi.animeTitle}, episodes=${bangumi.episodes.size}")

            val comments = danmakuRepository.fetchDanmakuComments(
                bangumi = bangumi, preferredEpisodeNumber = episode, keyword = videoTitle
            ) { success, data, fromCache ->
                when {
                    success && !fromCache -> {
                        Toast.makeText(this@PlayerActivity, "弹幕已刷新", Toast.LENGTH_SHORT).show()
                    }
                    success && fromCache -> Log.d(TAG, "弹幕从缓存加载: ${data.size} 条")
                    else -> Log.w(TAG, "弹幕获取最终失败")
                }
            }

            if (comments.isEmpty()) {
                Log.w(TAG, "弹幕列表为空: animeId=$animeId")
                danmakuManager?.loadDanmaku(null)
                return@launch
            }

            Log.d(TAG, "加载弹幕成功: ${comments.size} 条")
            danmakuManager?.loadDanmaku(comments)
            player?.let { p ->
                danmakuManager?.syncTo(p.currentPosition)
                if (danmakuSwitch.isChecked) danmakuManager?.ensureStarted()
            }

            // 保存弹幕到本地文件
            saveDanmakuToLocalFile(animeId, episode, comments)
        }
    }

    /**
     * 将弹幕列表保存到本地文件
     *
     * 离线播放时写入关联下载任务；在线播放时若对应任务存在也写入。
     * 文件保存在 filesDir/Danmaku/{taskId}.json
     */
    private fun saveDanmakuToLocalFile(animeId: Long, episode: String, comments: List<com.hpu.mymoviestore.data.model.danmaku.DanmakuComment>) {
        uiScope.launch(Dispatchers.IO) {
            try {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter<List<com.hpu.mymoviestore.data.model.danmaku.DanmakuComment>>(List::class.java)
                val json = adapter.toJson(comments)

                // 确定 taskId
                val taskId = if (isOfflineMode && offlineTaskId.isNotEmpty()) {
                    offlineTaskId
                } else {
                    // 在线模式：尝试根据 videoId + episodeTitle 查找下载任务
                    val tasks = MovieApplication.get().downloadRepository.getTasksByVideoId(videoId)
                    tasks.find { matchEpisodeTitle(it.episodeTitle, episode) }?.taskId
                }

                if (taskId == null) {
                    Log.d(TAG, "saveDanmakuToLocalFile: 无匹配下载任务，跳过写文件")
                    return@launch
                }

                val danmakuDir = java.io.File(filesDir, "Danmaku").also { it.mkdirs() }
                val file = java.io.File(danmakuDir, "$taskId.json")
                file.writeText(json, Charsets.UTF_8)
                Log.d(TAG, "弹幕已缓存到本地: ${file.absolutePath}, size=${file.length()}")

                // 更新数据库中的弹幕文件路径
                MovieApplication.get().downloadRepository.updateDanmakuStatus(
                    taskId, DownloadTaskEntity.DANMAKU_COMPLETED,
                    file.absolutePath, ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "saveDanmakuToLocalFile 失败: ${e.message}", e)
            }
        }
    }

    /**
     * 粗略匹配集数标题中的数字
     */
    private fun matchEpisodeTitle(taskEpisodeTitle: String, currentEpisode: String): Boolean {
        val taskNum = Regex("""\d+""").find(taskEpisodeTitle)?.value
        val currentNum = Regex("""\d+""").find(currentEpisode)?.value
        return taskNum != null && currentNum != null && taskNum == currentNum
    }

    // ================== 生命周期 ==================

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause → 暂停播放器并保存进度")
        player?.pause()
        danmakuManager?.pause()
        saveCurrentProgress(true)
        stopProgressSyncRunnable()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume → 恢复播放")
        enterImmersiveMode()
        player?.play()
        danmakuManager?.resume()
        startProgressSyncRunnable()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy → 保存最终进度并释放播放器")
        saveCurrentProgress(true)
        stopProgressSyncRunnable()
        releasePlayer()
        danmakuSearchJob?.cancel()
        danmakuLoadJob?.cancel()
        uiScope.cancel()
        danmakuManager?.release()
        unregisterStatusReceivers()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releasePlayer() {
        player?.let {
            Log.d(TAG, "释放 ExoPlayer")
            it.release()
            player = null
        }
    }

    // ================== 工具 ==================

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun normalizeEpisodeTitle(title: String): String {
        if (title.isBlank()) return ""
        val number = Regex("\\d+").find(title)?.value?.toIntOrNull()
        return if (number != null && title.contains("集")) "第${number}集" else title
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersiveMode()
        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
    }

    // ================== 状态信息显示（时间、电量、网络） ==================

    /**
     * 初始化状态信息显示（时间、电量、网络）
     * 在播放器控制栏显示时同步显示
     */
    private fun initStatusInfo() {
        // 初始更新
        updateTime()
        updateNetwork()

        // 注册电池状态广播接收器
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateBattery(intent)
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 注册网络状态广播接收器
        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateNetwork()
            }
        }
        registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        // 启动时间更新（每30秒更新一次）
        timeUpdateHandler = Handler(Looper.getMainLooper())
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                updateTime()
                timeUpdateHandler?.postDelayed(this, 30_000L) // 30秒
            }
        }
        timeUpdateHandler?.postDelayed(timeUpdateRunnable!!, 30_000L)
    }

    /**
     * 更新时间显示（格式：HH:mm）
     */
    private fun updateTime() {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.tvTime.text = currentTime
    }

    /**
     * 更新电量显示
     */
    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            val batteryPct = (level * 100 / scale.toFloat()).toInt()
            binding.tvBattery.text = "$batteryPct%"
        }
    }

    /**
     * 更新网络状态显示
     */
    private fun updateNetwork() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
        val isConnected = networkInfo?.isConnected == true

        if (!isConnected) {
            // 无网络
            binding.ivNetwork.setImageResource(R.drawable.ic_wifi)
            binding.ivNetwork.alpha = 0.5f
        } else {
            binding.ivNetwork.alpha = 1.0f
            when (networkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> {
                    binding.ivNetwork.setImageResource(R.drawable.ic_wifi)
                }
                ConnectivityManager.TYPE_MOBILE -> {
                    binding.ivNetwork.setImageResource(R.drawable.ic_mobile_network)
                }
                else -> {
                    binding.ivNetwork.setImageResource(R.drawable.ic_wifi)
                }
            }
        }
    }

    /**
     * 注销状态信息相关的广播接收器，并停止时间更新
     */
    private fun unregisterStatusReceivers() {
        try {
            batteryReceiver?.let {
                unregisterReceiver(it)
                batteryReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "注销电池广播接收器失败: ${e.message}")
        }

        try {
            networkReceiver?.let {
                unregisterReceiver(it)
                networkReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "注销网络广播接收器失败: ${e.message}")
        }

        timeUpdateRunnable?.let {
            timeUpdateHandler?.removeCallbacks(it)
        }
        timeUpdateHandler = null
        timeUpdateRunnable = null
    }

    private fun startOfflinePlayback(taskId: String, danmakuFilePath: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val task = MovieApplication.get().downloadRepository.getTaskById(taskId)
            if (task != null) {
                if (task.playProgressPercent >= 100) {
                    Log.d(TAG, "离线播放：已看完，从头开始")
                } else if (task.playPositionMs > 0) {
                    resumeFromMs = task.playPositionMs
                    Log.d(TAG, "离线播放：续播 ${task.playPositionMs}ms")
                }
                val masterEnabled = DanmakuPrefs(this@PlayerActivity).isMasterEnabled()
                if (masterEnabled && (task.danmakuStatus == DownloadTaskEntity.DANMAKU_FAILED ||
                        task.danmakuStatus == DownloadTaskEntity.DANMAKU_NOT_DOWNLOADED)) {
                    Log.d(TAG, "离线播放：弹幕状态=${task.danmakuStatus}，触发后台重试下载")
                    DanmakuDownloadManager.getInstance(this@PlayerActivity).retryDanmaku(
                        taskId = taskId,
                        title = task.title,
                        episodeTitle = task.episodeTitle,
                        dao = MovieDatabase.getInstance(this@PlayerActivity).downloadTaskDao()
                    )
                }
            }
        }
        if (!danmakuFilePath.isNullOrEmpty()) {
            val danmakuFile = File(danmakuFilePath)
            if (danmakuFile.exists()) {
                loadDanmakuFromLocalFile(danmakuFile)
            } else {
                Log.w(TAG, "本地弹幕文件不存在: $danmakuFilePath，尝试在线搜索弹幕")
                launchDanmakuSearch(videoTitle, episodeTitle)
            }
        } else {
            launchDanmakuSearch(videoTitle, episodeTitle)
        }
    }
}
