package com.hpu.mymoviestore.presentation.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.hpu.mymoviestore.databinding.ActivityPlayerBinding
import com.hpu.mymoviestore.presentation.viewmodel.PlayerViewModel
import okhttp3.OkHttpClient

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
    private var resumeFromMs: Long = 0L   // 续播起点（毫秒），= playProgressSeconds * 1000

    // 用于定期写入播放进度的计时器（避免每秒写数据库）
    private var lastSavedProgressMs: Long = -1L
    private val progressSaveIntervalMs: Long = 30_000L   // 每 30 秒写入一次
    private var playbackRunnable: Runnable? = null
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "PlayerActivity"

        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_COVER = "extra_video_cover"
        const val EXTRA_VIDEO_CATEGORY = "extra_video_category"
        const val EXTRA_VIDEO_URL = "extra_video_url"

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
            url: String
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_VIDEO_TITLE, title)
                putExtra(EXTRA_VIDEO_COVER, coverUrl)
                putExtra(EXTRA_VIDEO_CATEGORY, category)
                putExtra(EXTRA_VIDEO_URL, url)
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
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoId = intent.getLongExtra(EXTRA_VIDEO_ID, 0)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        videoCover = intent.getStringExtra(EXTRA_VIDEO_COVER) ?: ""
        videoCategory = intent.getStringExtra(EXTRA_VIDEO_CATEGORY) ?: ""
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""

        Log.d(TAG, "========== PlayerActivity.onCreate ==========")
        Log.d(TAG, "收到 Intent: videoId=$videoId, title=$videoTitle, category=$videoCategory")
        Log.d(TAG, "playUrl=${if (videoUrl.isNotEmpty()) videoUrl.take(60) + "..." else "(空)"}")

        title = videoTitle

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]

        // ① 查询历史记录中的播放进度（用于续播）
        Log.d(TAG, "查询历史记录中的进度: videoId=$videoId")
        viewModel.getHistoryByVideoId(videoId) { history ->
            resumeFromMs = if (history != null && history.playProgressSeconds > 0) {
                val ms = history.playProgressSeconds * 1000
                Log.d(
                    TAG,
                    "发现上次播放进度: ${history.playProgressSeconds}秒 ($ms ms), " +
                        "总时长=${history.durationSeconds}秒 → 续播"
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
                playUrl = videoUrl
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
    }
}
