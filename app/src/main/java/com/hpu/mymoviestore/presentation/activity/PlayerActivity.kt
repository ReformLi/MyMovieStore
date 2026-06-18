package com.hpu.mymoviestore.presentation.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.withContext
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
    private var lastSyncPositionMs: Long = -1L  // 用于检测 seek 跳变
    private val progressSaveIntervalMs: Long = 30_000L

    // 弹幕相关
    private var danmakuManager: DanmakuManager? = null
    private lateinit var danmakuRepository: DanmakuRepository
    private var candidateList: List<DanmakuAnime> = emptyList()
    private var selectedBangumi: DanmakuBangumi? = null
    private var danmakuLoadJob: Job? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

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

        // 1. 先初始化 binding
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. 现在可以安全使用 binding 了
        binding.danmakuContainer.bringToFront()
        binding.danmakuContainer.clipChildren = false
        binding.danmakuContainer.clipToPadding = false

        // 3. 其他初始化
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

        Log.d(TAG, "========== PlayerActivity.onCreate ==========")
        Log.d(TAG, "收到 Intent: videoId=$videoId, title=$videoTitle, category=$category, source=$sourceName")

        title = videoTitle

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]

        // 初始化弹幕仓库（传入 Context 启用缓存）
        danmakuRepository = DanmakuRepository(context = this)

        setupPlayerUi()
        setupDanmakuUi()

        // 1) 查询播放历史 —— 决定续播位置
        viewModel.getHistoryByVideoId(videoId) { history ->
            val canResume = history != null && history.playProgressSeconds > 0 &&
                (videoUrl.isBlank() || history.playUrl == videoUrl || true) // 只要 id 匹配就续播
            resumeFromMs = if (canResume) history.playProgressSeconds * 1000 else 0L
            if (resumeFromMs > 0) {
                Log.d(TAG, "上次进度 ${history!!.playProgressSeconds}s → seekTo $resumeFromMs ms")
            } else {
                Log.d(TAG, "无历史进度或 URL 不匹配 → 从头播放")
            }

            // 2) 写入历史
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

            // 3) 启动播放器
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

        // 4) 异步搜索弹幕源（即使失败也不影响播放）
        launchDanmakuSearch(videoTitle, episodeTitle)
    }

    /** 播放器初始化（和之前的逻辑保持一致） */
    private fun initializePlayer(url: String) {
        if (url.isEmpty()) {
            Log.w(TAG, "播放地址为空，无法初始化播放器")
            return
        }

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

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d(TAG, "播放状态: STATE_READY")
                                danmakuManager?.ensureStarted()
                                // 同步弹幕暂停状态
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
                    }
                })
            }

        // 开始进度轮询（每 30s 写入一次 + 同步弹幕时间）
        startProgressSyncRunnable()
    }

    /** 定期（1s）同步弹幕时间 + 每 30s 写入一次播放进度 */
    private val progressSyncRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val currentMs = p.currentPosition
                // 检测 seek：如果位置跳变超过 3 秒，视为用户拖动进度条，需要清空重建弹幕
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

    /** 标题/剧集/返回/旋转等 UI */
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

        // 显式创建 androidx.media3.ui.PlayerView.ControllerVisibilityListener，
        // 避免和 deprecated 的 PlayerControlView.VisibilityListener 发生 SAM 重载二义性
        val listener = object : androidx.media3.ui.PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                binding.topControls.visibility = visibility
                binding.layoutDanmakuControls.visibility = visibility
            }
        }
        binding.playerView.setControllerVisibilityListener(listener)
    }

    /**
     * 初始化弹幕 UI：
     * - 动态设置 danmakuContainer 高度为屏幕高度的 1/4（限制弹幕显示区域）
     * - 挂载 DanmakuManager 到容器
     * - 设置弹幕开关（子开关，独立于全局总开关）
     * - 配置弹幕源 Spinner（搜索完成前显示“搜索中…”，完成后展示候选列表）
     */
    private fun setupDanmakuUi() {
        // ==================== 1. 设置弹幕容器的高度和触摸穿透 ====================
        val container = binding.danmakuContainer

        // 弹幕容器高度已在 XML 中通过 layout_constraintHeight_percent="0.25" 设置
        // 不再在代码中动态设置高度，避免横竖屏切换时高度计算异常
        container.setBackgroundColor(Color.TRANSPARENT)
        container.clipChildren = true          // 裁剪超出边界的子视图（弹幕不会溢出）
        container.isClickable = false          // 触摸事件穿透，让点击能传递到播放器
        container.isFocusable = false
        Log.d(TAG, "弹幕容器使用 XML 约束高度 25%")

        // ==================== 2. 创建并挂载 DanmakuManager ====================
        val dm = DanmakuManager(this)
        dm.attachToContainer(container)        // attach 内部会创建 DanmakuView 并填满容器
        this.danmakuManager = dm

        // ==================== 3. 弹幕子开关 ====================
        // 从全局配置读取总开关状态，子开关默认与总开关同步，但后续切换只影响当前播放器
        val prefs = DanmakuPrefs(this)
        val masterEnabled = prefs.isMasterEnabled()
        val subEnabled = masterEnabled
        binding.switchDanmaku.isChecked = subEnabled
        dm.setDanmakuEnabled(subEnabled)
        Log.d(TAG, "弹幕子开关初始状态: $subEnabled (总开关=$masterEnabled)")

        binding.switchDanmaku.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "弹幕子开关切换: $isChecked")
            dm.setDanmakuEnabled(isChecked)
        }

        // ==================== 4. 弹幕源 Spinner ====================
        // 初始显示“搜索中…”，等搜索完成后动态替换 adapter
        val initialTitles = listOf("搜索弹幕源中…")
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            initialTitles
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
        binding.spinnerDanmakuSource.adapter = adapter

        // 用户选择弹幕源时触发加载对应弹幕
        binding.spinnerDanmakuSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position < 0 || position >= candidateList.size) return
                val anime = candidateList[position]
                Log.d(TAG, "用户选择弹幕源: animeId=${anime.animeId}, title=${anime.animeTitle}")
                loadDanmakuForAnime(anime, episodeTitle)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /** 异步搜索候选弹幕源 */
    private fun launchDanmakuSearch(title: String, episode: String) {
        danmakuLoadJob?.cancel()
        danmakuLoadJob = uiScope.launch {
            val candidates = danmakuRepository.searchCandidates(title)
            Log.d(TAG, "弹幕搜索完成: ${candidates.size} 条")

            if (candidates.isEmpty()) {
                // 无匹配 → 隐藏 Spinner
                binding.spinnerDanmakuSource.visibility = View.GONE
                return@launch
            }

            candidateList = candidates
            val titles = candidates.map { it.animeTitle }
            val adapter = object : ArrayAdapter<String>(
                this@PlayerActivity,
                android.R.layout.simple_spinner_item,
                titles
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
            binding.spinnerDanmakuSource.adapter = adapter

            // 默认选第一个
            binding.spinnerDanmakuSource.setSelection(0)
        }
    }

    /** 加载某个弹幕源的 bangumi + 弹幕列表 */
    private fun loadDanmakuForAnime(anime: DanmakuAnime, episode: String) {
        danmakuLoadJob?.cancel()
        danmakuLoadJob = uiScope.launch {
            // 1. 获取 bangumi（带缓存和重试）
            val bangumi = danmakuRepository.fetchBangumi(
                animeId = anime.animeId,
                keyword = videoTitle
            ) { success, data, fromCache ->
                if (!success) {
                    Log.w(TAG, "获取 bangumi 最终失败")
                }
            }
            if (bangumi == null) {
                Log.w(TAG, "bangumi 为空，无法加载弹幕")
                return@launch
            }
            selectedBangumi = bangumi
            Log.d(TAG, "获取 bangumi: title=${bangumi.animeTitle}, episodes=${bangumi.episodes.size}")

            // 2. 获取弹幕（带缓存、重试和 Toast）
            val comments = danmakuRepository.fetchDanmakuComments(
                bangumi = bangumi,
                preferredEpisodeNumber = episode,
                keyword = videoTitle
            ) { success, data, fromCache ->
                when {
                    success && !fromCache -> {
                        // 网络获取成功，显示 Toast
                        Toast.makeText(this@PlayerActivity, "弹幕已刷新", Toast.LENGTH_SHORT).show()
                    }
                    success && fromCache -> {
                        Log.d(TAG, "弹幕从缓存加载: ${data.size} 条")
                    }
                    else -> {
                        Log.w(TAG, "弹幕获取最终失败（已重试5次）")
                    }
                }
            }

            if (comments.isEmpty()) {
                Log.w(TAG, "弹幕列表为空或失败")
                danmakuManager?.loadDanmaku(null)
            } else {
                Log.d(TAG, "加载弹幕成功: ${comments.size} 条")
                danmakuManager?.loadDanmaku(comments)
                // 加载完成后立即同步当前播放时间
                player?.let { p ->
                    danmakuManager?.syncTo(p.currentPosition)
                    if (binding.switchDanmaku.isChecked) danmakuManager?.ensureStarted()
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
    }

    // 手势相关保持不变
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // 简化为仅处理触摸传播（复杂手势控制可按需扩展）
        return super.dispatchTouchEvent(event)
    }
}
