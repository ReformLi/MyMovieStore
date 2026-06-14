package com.hpu.mymoviestore.presentation.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.ui.PlayerView
import com.hpu.mymoviestore.databinding.ActivityPlayerBinding
import com.hpu.mymoviestore.presentation.viewmodel.PlayerViewModel
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器 Activity
 *
 * 职责：
 * 1. 使用 Media3 (ExoPlayer) 播放视频
 * 2. 管理播放生命周期（初始化 / 暂停 / 恢复 / 释放）
 * 3. 播放启动后去重写入播放历史（通过 PlayerViewModel.setVideoInfo 调用 PlayHistoryRepository）
 *
 * 入口：由 DetailActivity 点击「播放」跳转，通过 newIntent() 构造 Intent。
 *
 * 去重更新播放历史逻辑：
 *   - 首次播放某 videoId → 插入新的 play_history 记录
 *   - 再次点击同一视频（首页/搜索/历史） → 更新 lastPlayTime = 当前时间
 *     （由 PlayHistoryRepository.addOrUpdateHistory 内部判断）
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var viewModel: PlayerViewModel

    /** Media3 ExoPlayer 实例：onCreate 初始化，onDestroy 释放 */
    private var player: ExoPlayer? = null

    // 从 Intent 解析的视频信息（同时传给 ViewModel 用于写入历史）
    private var videoId: Long = 0
    private var videoTitle: String = ""
    private var videoCover: String = ""
    private var videoCategory: String = ""
    private var videoUrl: String = ""
    private var detailUrl: String = ""
    private var playPageUrl: String = ""
    private var episodeTitle: String = ""
    private var resumeFromMs: Long = 0L   // 续播起点（毫秒），= playProgressSeconds * 1000

    // 用于定期写入播放进度的计时器（避免每秒写数据库）
    private var lastSavedProgressMs: Long = -1L
    private val progressSaveIntervalMs: Long = 30_000L   // 每 30 秒写入一次
    private var playbackRunnable: Runnable? = null
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideGestureTipRunnable = Runnable {
        if (::binding.isInitialized) {
            binding.tvGestureTip.visibility = View.GONE
        }
    }

    private lateinit var audioManager: AudioManager
    private var maxVolume: Int = 0
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var startVolume: Int = 0
    private var startBrightness: Float = 0.5f
    private var startSeekPositionMs: Long = 0L
    private var targetSeekPositionMs: Long = 0L
    private var gestureMode: GestureMode = GestureMode.NONE
    private var isVerticalGestureAdjusting: Boolean = false
    private var isHorizontalSeekAdjusting: Boolean = false
    private var ignoreGestureForControls: Boolean = false

    private enum class GestureMode {
        NONE,
        BRIGHTNESS,
        VOLUME,
        SEEK
    }

    companion object {
        private const val TAG = "PlayerActivity"

        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_COVER = "extra_video_cover"
        const val EXTRA_VIDEO_CATEGORY = "extra_video_category"
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_DETAIL_URL = "extra_detail_url"
        const val EXTRA_PLAY_PAGE_URL = "extra_play_page_url"
        const val EXTRA_EPISODE_TITLE = "extra_episode_title"

        /**
         * 构造播放器 Intent（供 DetailActivity 调用）
         * 注意：coverUrl / category / playUrl 全部用于播放历史冗余存储，
         *       以便从历史页点击时无需回查即可播放。
         */
        fun newIntent(
            context: Context,
            videoId: Long,
            title: String,
            coverUrl: String,
            category: String,
            url: String,
            detailUrl: String = "",
            playPageUrl: String = "",
            episodeTitle: String = ""
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
            }
        }
    }

    /**
     * Activity 创建流程：
     * 1. 解析 Intent 拿到 videoId / title / coverUrl / category / url
     * 2. 初始化 ViewModel，setVideoInfo(5 参数)：写入/去重播放历史
     * 3. url 非空 → 直接初始化播放器；为空 → 从 JSON 挡板回查后再播放
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoId = intent.getLongExtra(EXTRA_VIDEO_ID, 0)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        videoCover = intent.getStringExtra(EXTRA_VIDEO_COVER) ?: ""
        videoCategory = intent.getStringExtra(EXTRA_VIDEO_CATEGORY) ?: ""
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        detailUrl = intent.getStringExtra(EXTRA_DETAIL_URL) ?: ""
        playPageUrl = intent.getStringExtra(EXTRA_PLAY_PAGE_URL) ?: ""
        episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: ""

        Log.d(TAG, "========== PlayerActivity.onCreate ==========")
        Log.d(TAG, "收到 Intent: videoId=$videoId, title=$videoTitle, category=$videoCategory")
        Log.d(TAG, "playUrl=${if (videoUrl.isNotEmpty()) videoUrl.take(60) + "..." else "(空)"}")

        title = videoTitle

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        setupPlayerUi()

        // ① 查询历史记录中的播放进度（用于续播）
        Log.d(TAG, "查询历史记录中的进度: videoId=$videoId")
        viewModel.getHistoryByVideoId(videoId) { history ->
            val canResumeThisEpisode = history != null &&
                history.playProgressSeconds > 0 &&
                (playPageUrl.isBlank() || history.playPageUrl == playPageUrl)
            resumeFromMs = if (canResumeThisEpisode) {
                val ms = history.playProgressSeconds * 1000
                Log.d(
                    TAG,
                    "发现当前集上次播放进度: ${history?.playProgressSeconds}秒 ($ms ms), " +
                        "总时长=${history?.durationSeconds}秒 → 续播"
                )
                ms
            } else {
                Log.d(TAG, "无历史进度记录 → 从头播放")
                0L
            }

            // ② 写入/去重播放历史（PlayHistoryRepository 内部判断是否存在）
            viewModel.setVideoInfo(
                videoId = videoId,
                title = videoTitle,
                coverUrl = videoCover,
                category = videoCategory,
                playUrl = videoUrl,
                detailUrl = detailUrl,
                playPageUrl = playPageUrl,
                episodeTitle = episodeTitle
            )
            Log.d(TAG, "ViewModel 已设置视频信息（将异步写入播放历史）")

            // ③ 视频地址为空 → JSON 回查；非空 → 初始化播放器
            if (videoUrl.isEmpty()) {
                Log.d(TAG, "playUrl 为空 → 从 JSON 回查 videoId=$videoId")
                viewModel.getVideoInfoById(videoId) { video ->
                    val url = video?.playUrl.orEmpty()
                    Log.d(
                        TAG,
                        "回查完成: url=${if (url.isNotEmpty()) url.take(60) + "..." else "(仍为空)"}"
                    )
                    if (url.isNotEmpty()) {
                        videoUrl = url
                        runOnUiThread { initializePlayer(url) }
                    } else {
                        Log.w(TAG, "回查失败或 playUrl 为空，无法播放")
                    }
                }
            } else {
                Log.d(TAG, "playUrl 非空，直接初始化播放器")
                initializePlayer(videoUrl)
            }
        }
    }

    /**
     * 初始化 Media3 ExoPlayer：
     * 1. 创建 ExoPlayer 实例 → 绑定 PlayerView
     * 2. 从 uri 构造 MediaItem → setMediaItem → prepare()
     * 3. playWhenReady = true 自动开始播放
     * 4. 通过 Player.Listener 监听播放状态（缓冲/播放/结束）
     */
    private fun initializePlayer(url: String) {
        if (url.isEmpty()) {
            Log.w(TAG, "播放地址为空，无法初始化播放器")
            binding.playerView.hideController()
            return
        }

        // 使用 OkHttp 作为底层网络数据源（替换默认的 DefaultHttpDataSource）
        val dataSourceFactory = OkHttpDataSource.Factory(
            OkHttpClient.Builder().build()
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

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

                // 启动定期写入进度的轮询
                startProgressSaver()

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val statusText = when (playbackState) {
                            Player.STATE_BUFFERING -> "缓冲中...".also { Log.d(TAG, "播放状态: STATE_BUFFERING") }
                            Player.STATE_READY -> "播放中".also { Log.d(TAG, "播放状态: STATE_READY，开始播放") }
                            Player.STATE_ENDED -> "播放结束".also {
                                Log.d(TAG, "播放状态: STATE_ENDED，保存最终进度")
                                saveCurrentProgress(true)
                            }
                            Player.STATE_IDLE -> "准备就绪".also { Log.d(TAG, "播放状态: STATE_IDLE") }
                            else -> "未知状态($playbackState)"
                        }
                        viewModel.updatePlayStatus(statusText)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
                    }
                })
            }
    }

    private fun setupPlayerUi() {
        binding.tvPlayerTitle.text = videoTitle.ifBlank { "正在播放" }
        val normalizedEpisode = normalizeEpisodeTitle(episodeTitle)
        if (normalizedEpisode.isNotBlank()) {
            binding.tvPlayerEpisode.text = normalizedEpisode
            binding.tvPlayerEpisode.visibility = View.VISIBLE
        } else {
            binding.tvPlayerEpisode.visibility = View.GONE
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRotate.setOnClickListener {
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            requestedOrientation = if (isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            binding.playerView.showController()
            binding.topControls.visibility = View.VISIBLE
        }

        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.topControls.visibility = visibility
            }
        )
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
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersiveMode()
        binding.playerView.showController()
        binding.topControls.visibility = View.VISIBLE
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::binding.isInitialized) return super.dispatchTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                startBrightness = currentWindowBrightness()
                startSeekPositionMs = player?.currentPosition ?: 0L
                targetSeekPositionMs = startSeekPositionMs
                gestureMode = GestureMode.NONE
                isVerticalGestureAdjusting = false
                isHorizontalSeekAdjusting = false
                ignoreGestureForControls = isTouchInControllerArea(downY)
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreGestureForControls) {
                    return super.dispatchTouchEvent(event)
                }

                val deltaX = event.x - downX
                val deltaY = event.y - downY
                val absDeltaX = abs(deltaX)
                val absDeltaY = abs(deltaY)

                if (!isHorizontalSeekAdjusting &&
                    !isVerticalGestureAdjusting &&
                    absDeltaX > dp(24) &&
                    absDeltaX > absDeltaY * 1.2f
                ) {
                    isHorizontalSeekAdjusting = true
                    gestureMode = GestureMode.SEEK
                    binding.playerView.hideController()
                }

                if (!isHorizontalSeekAdjusting &&
                    !isVerticalGestureAdjusting &&
                    absDeltaY > dp(18) &&
                    absDeltaY > absDeltaX * 1.2f
                ) {
                    isVerticalGestureAdjusting = true
                    gestureMode = if (downX < binding.playerRoot.width / 2f) {
                        GestureMode.BRIGHTNESS
                    } else {
                        GestureMode.VOLUME
                    }
                }

                if (isVerticalGestureAdjusting) {
                    handleVerticalGesture(deltaY)
                    return true
                }

                if (isHorizontalSeekAdjusting) {
                    handleHorizontalSeekGesture(deltaX)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isHorizontalSeekAdjusting) {
                    player?.seekTo(targetSeekPositionMs)
                    saveCurrentProgress(true)
                    hideGestureTipDelay()
                    isHorizontalSeekAdjusting = false
                    gestureMode = GestureMode.NONE
                    binding.playerView.showController()
                    return true
                }

                if (isVerticalGestureAdjusting) {
                    hideGestureTipDelay()
                    isVerticalGestureAdjusting = false
                    gestureMode = GestureMode.NONE
                    return true
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private fun handleHorizontalSeekGesture(deltaX: Float) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0) {
            showGestureTip("正在获取时长")
            return
        }

        val width = binding.playerRoot.width.coerceAtLeast(1)
        val maxSeekDeltaMs = (duration / 5).coerceIn(30_000L, 300_000L)
        val ratio = (deltaX / width).coerceIn(-1f, 1f)
        val seekOffsetMs = (ratio * maxSeekDeltaMs).roundToInt().toLong()
        targetSeekPositionMs = (startSeekPositionMs + seekOffsetMs).coerceIn(0L, duration)

        val actionText = if (seekOffsetMs >= 0) "快进" else "后退"
        showGestureTip(
            "$actionText ${formatTime(abs(seekOffsetMs))}\n" +
                "${formatTime(targetSeekPositionMs)} / ${formatTime(duration)}"
        )
    }

    private fun handleVerticalGesture(deltaY: Float) {
        val height = binding.playerRoot.height.coerceAtLeast(1)
        val percentDelta = (-deltaY / height).coerceIn(-1f, 1f)

        when (gestureMode) {
            GestureMode.BRIGHTNESS -> {
                val brightness = (startBrightness + percentDelta).coerceIn(0.02f, 1f)
                val attrs = window.attributes
                attrs.screenBrightness = brightness
                window.attributes = attrs
                showGestureTip("亮度 ${(brightness * 100).roundToInt()}%")
            }

            GestureMode.VOLUME -> {
                val targetVolume = (startVolume + percentDelta * maxVolume)
                    .roundToInt()
                    .coerceIn(0, maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                showGestureTip("音量 ${(targetVolume * 100f / maxVolume).roundToInt()}%")
            }

            GestureMode.SEEK -> Unit
            GestureMode.NONE -> Unit
        }
    }

    private fun isTouchInControllerArea(y: Float): Boolean {
        val height = binding.playerRoot.height
        return height > 0 && y > height - dp(96)
    }

    private fun currentWindowBrightness(): Float {
        val current = window.attributes.screenBrightness
        return if (current >= 0f) current else 0.5f
    }

    private fun showGestureTip(text: String) {
        binding.tvGestureTip.text = text
        binding.tvGestureTip.visibility = View.VISIBLE
        binding.tvGestureTip.removeCallbacks(hideGestureTipRunnable)
    }

    private fun hideGestureTipDelay() {
        binding.tvGestureTip.removeCallbacks(hideGestureTipRunnable)
        binding.tvGestureTip.postDelayed(hideGestureTipRunnable, 700)
    }

    private fun normalizeEpisodeTitle(title: String): String {
        if (title.isBlank()) return ""
        val number = Regex("\\d+").find(title)?.value?.toIntOrNull()
        return if (number != null && title.contains("集")) {
            "第${number}集"
        } else {
            title
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * 启动定期写入进度的轮询（避免频繁写入数据库）
     * 规则：每 30 秒写入一次；实际位置与上次写入位置差异 ≥ 30s 才写（避免抖动）
     */
    private fun startProgressSaver() {
        if (playbackRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                saveCurrentProgress(false)
                progressHandler.postDelayed(this, progressSaveIntervalMs)
            }
        }
        playbackRunnable = runnable
        progressHandler.postDelayed(runnable, progressSaveIntervalMs)
        Log.d(TAG, "定期进度写入器已启动（每 $progressSaveIntervalMs ms 一次）")
    }

    private fun stopProgressSaver() {
        playbackRunnable?.let {
            progressHandler.removeCallbacks(it)
            Log.d(TAG, "定期进度写入器已停止")
        }
        playbackRunnable = null
    }

    /**
     * 保存当前播放进度到 Room
     * @param force 是否强制写入（否则仅当进度有明显变化时才写）
     */
    private fun saveCurrentProgress(force: Boolean) {
        val p = player ?: return
        val currentMs = p.currentPosition
        val durationMs = p.duration.coerceAtLeast(0L)

        if (currentMs <= 0) return
        if (!force && lastSavedProgressMs >= 0 &&
            (currentMs - lastSavedProgressMs) < progressSaveIntervalMs / 2
        ) return

        val progressSec = currentMs / 1000
        val durationSec = if (durationMs > 0) durationMs / 1000 else 0L

        lastSavedProgressMs = currentMs
        viewModel.updateProgress(
            videoId = videoId,
            progressSeconds = progressSec,
            durationSeconds = durationSec
        )
        Log.d(
            TAG,
            "写入播放进度: ${progressSec}秒, 总时长=${durationSec}秒, " +
                "force=$force"
        )
    }

    /** 释放 ExoPlayer（避免内存泄漏） */
    private fun releasePlayer() {
        player?.let {
            Log.d(TAG, "释放 ExoPlayer")
            it.release()
            player = null
        }
    }

    /** Activity 暂停 → 暂停播放 + 保存进度 */
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause → 暂停播放并保存进度")
        player?.pause()
        saveCurrentProgress(true)
        stopProgressSaver()
    }

    /** Activity 恢复 → 恢复播放 + 重启进度写入轮询 */
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume → 恢复播放")
        enterImmersiveMode()
        player?.play()
        startProgressSaver()
    }

    /** Activity 销毁 → 保存最终进度 + 释放播放器 */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy → 保存最终进度并释放播放器")
        saveCurrentProgress(true)
        stopProgressSaver()
        releasePlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
