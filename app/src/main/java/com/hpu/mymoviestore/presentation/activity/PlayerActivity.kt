package com.hpu.mymoviestore.presentation.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.model.danmaku.DanmakuAnime
import com.hpu.mymoviestore.data.model.danmaku.DanmakuBangumi
import com.hpu.mymoviestore.data.repository.DanmakuRepository
import com.hpu.mymoviestore.databinding.ActivityPlayerBinding
import com.hpu.mymoviestore.presentation.danmaku.DanmakuManager
import com.hpu.mymoviestore.presentation.danmaku.DanmakuPrefs
import com.hpu.mymoviestore.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.abs
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
    private var lastSavedProgressMs: Long = -1L
    private var lastSyncPositionMs: Long = -1L
    private val progressSaveIntervalMs: Long = 30_000L

    // 弹幕相关
    private var danmakuManager: DanmakuManager? = null
    private lateinit var danmakuRepository: DanmakuRepository
    private var candidateList: List<DanmakuAnime> = emptyList()
    private var selectedBangumi: DanmakuBangumi? = null
    private var danmakuLoadJob: Job? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

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
    private var maxVolume = 0

    // 屏幕锁定
    private var isScreenLocked = false

    companion object {
        private const val TAG = "PlayerActivity"
        private const val LONG_PRESS_THRESHOLD_MS = 300L
        private const val SEEK_STEP_MS = 10_000L  // 10秒

        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_COVER = "extra_video_cover"
        const val EXTRA_VIDEO_CATEGORY = "extra_video_category"
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_DETAIL_URL = "extra_detail_url"
        const val EXTRA_PLAY_PAGE_URL = "extra_play_page_url"
        const val EXTRA_EPISODE_TITLE = "extra_episode_title"
        const val EXTRA_SOURCE_NAME = "extra_source_name"

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
                })
            }

        startProgressSyncRunnable()
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
        val currentSec = p.currentPosition / 1000
        val durSec = if (p.duration > 0) p.duration / 1000 else 0L
        viewModel.updateProgress(videoId, currentSec, durSec)
        if (force) Log.d(TAG, "强制写入进度: ${currentSec}s / ${durSec}s")
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
                if (!isScreenLocked) {
                    binding.btnLock.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                }
            }
        }
        binding.playerView.setControllerVisibilityListener(listener)
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
                v.setBackgroundColor(Color.parseColor("#CC222222"))
                v.setPadding(24, 20, 24, 20)
                v.textSize = 13f
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        danmakuSpinner.adapter = adapter

        danmakuSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < 0 || position >= candidateList.size) return
                val anime = candidateList[position]
                Log.d(TAG, "用户选择弹幕源: animeId=${anime.animeId}, title=${anime.animeTitle}")
                loadDanmakuForAnime(anime, episodeTitle)
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
                Log.d(TAG, "屏幕已解锁")
            } else {
                // 锁定
                isScreenLocked = true
                binding.btnLock.setImageResource(R.drawable.ic_player_lock)
                binding.playerView.useController = false
                binding.playerView.hideController()
                Log.d(TAG, "屏幕已锁定")
            }
        }
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
                    // 锁定状态下点击显示/隐藏锁定按钮
                    binding.btnLock.visibility = if (binding.btnLock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isScreenLocked) {
            // 锁定状态：只处理单击显示锁定按钮和点击解锁按钮
            gestureDetector.onTouchEvent(event)
            return super.dispatchTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isLongPressing = false
                longPressStartX = event.x
                longPressStartY = event.y
                currentBrightness = -1f
                currentVolume = -1
                handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isLongPressing) {
                    handleLongPressMove(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (isLongPressing) {
                    isLongPressing = false
                    hideGestureTip()
                    return true
                }
            }
        }

        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private val longPressRunnable = Runnable {
        isLongPressing = true
        Log.d(TAG, "长按触发")
    }

    private fun handleLongPressMove(event: MotionEvent) {
        val deltaX = event.x - longPressStartX
        val deltaY = event.y - longPressStartY
        val absX = abs(deltaX)
        val absY = abs(deltaY)

        if (absX > absY && absX > 20) {
            // 水平滑动：快进/快退
            val direction = if (deltaX > 0) 1 else -1
            val seekMs = (absX / screenWidth * 60000).toLong().coerceIn(0, 120000)
            val totalSeek = direction * seekMs.coerceAtLeast(SEEK_STEP_MS)
            player?.let { p ->
                val newPos = (p.currentPosition + totalSeek).coerceIn(0, p.duration)
                p.seekTo(newPos)
                showGestureTip("${if (direction > 0) "+" else ""}${totalSeek / 1000}s")
            }
            longPressStartX = event.x
        } else if (absY > absX && absY > 20) {
            // 垂直滑动：亮度/音量
            val ratio = deltaY / screenHeight
            if (event.x < screenWidth / 2) {
                // 左半屏：亮度
                adjustBrightness(ratio)
            } else {
                // 右半屏：音量
                adjustVolume(ratio)
            }
            longPressStartY = event.y
        }
    }

    private fun adjustBrightness(ratio: Float) {
        val layoutParams = window.attributes
        if (currentBrightness < 0) {
            currentBrightness = layoutParams.screenBrightness
            if (currentBrightness < 0) currentBrightness = 0.5f
        }
        currentBrightness = (currentBrightness - ratio * 2).coerceIn(0.05f, 1f)
        layoutParams.screenBrightness = currentBrightness
        window.attributes = layoutParams
        showGestureTip("亮度 ${(currentBrightness * 100).roundToInt()}%")
    }

    private fun adjustVolume(ratio: Float) {
        if (currentVolume < 0) {
            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        val delta = (ratio * maxVolume * 2).roundToInt()
        currentVolume = (currentVolume - delta).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        showGestureTip("音量 ${(currentVolume * 100 / maxVolume)}%")
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

    private fun launchDanmakuSearch(title: String, episode: String) {
        danmakuLoadJob?.cancel()
        danmakuLoadJob = uiScope.launch {
            val candidates = danmakuRepository.searchCandidates(title)
            Log.d(TAG, "弹幕搜索完成: ${candidates.size} 条")

            if (candidates.isEmpty()) {
                danmakuSpinner.visibility = View.GONE
                return@launch
            }

            candidateList = candidates
            // Spinner 收起时显示 source（如 "弹幕源 tencent"）
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
                // 下拉列表显示 animeTitle（番剧名称）
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.text = candidateList.getOrNull(position)?.animeTitle ?: ""
                    v.setTextColor(Color.WHITE)
                    v.setBackgroundColor(Color.parseColor("#CC222222"))
                    v.setPadding(24, 20, 24, 20)
                    v.textSize = 13f
                    return v
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            danmakuSpinner.adapter = adapter
            danmakuSpinner.setSelection(0)
        }
    }

    private fun loadDanmakuForAnime(anime: DanmakuAnime, episode: String) {
        danmakuLoadJob?.cancel()
        danmakuLoadJob = uiScope.launch {
            val bangumi = danmakuRepository.fetchBangumi(
                animeId = anime.animeId, keyword = videoTitle
            ) { success, _, _ ->
                if (!success) Log.w(TAG, "获取 bangumi 最终失败")
            }
            if (bangumi == null) {
                Log.w(TAG, "bangumi 为空，无法加载弹幕")
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
                Log.w(TAG, "弹幕列表为空或失败")
                danmakuManager?.loadDanmaku(null)
            } else {
                Log.d(TAG, "加载弹幕成功: ${comments.size} 条")
                danmakuManager?.loadDanmaku(comments)
                player?.let { p ->
                    danmakuManager?.syncTo(p.currentPosition)
                    if (danmakuSwitch.isChecked) danmakuManager?.ensureStarted()
                }
            }
        }
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
        danmakuLoadJob?.cancel()
        danmakuManager?.release()
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
}
