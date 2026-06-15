package com.hpu.mymoviestore.presentation.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch

/**
 * 视频详情页 Activity
 *
 * 职责：
 * 1. 展示视频详细信息（标题 / 封面 / 分类 / 年份 / 地区 / 评分 / 导演 / 主演 / 简介）
 * 2. 点击「播放」按钮 → PlayerActivity
 *
 * 数据来源（两种跳转方式，统一处理）：
 * - 方式 A（来自首页/搜索）：Intent 附带完整 VideoItem 字段
 *                 （videoId, title, coverUrl, category, rating, year, area,
 *                  director, actors, description, playUrl）
 * - 方式 B（来自播放历史）：Intent 至少附带 videoId/title/coverUrl/category/playUrl，
 *                 其余字段若缺失，通过 videoRepository.getVideoById 从 JSON 挡板回查补全
 *
 * 去重 & 更新播放历史：在 PlayerActivity.setVideoInfo 中统一处理（调用 PlayHistoryRepository.addOrUpdateHistory）
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    // 当前视频的业务字段（仅用于日志与播放跳转，不持久化收藏）
    private var videoId: Long = 0
    private var videoTitle: String = ""
    private var videoCover: String = ""
    private var videoCategory: String = ""
    private var videoUrl: String = ""
    private var detailUrl: String = ""
    private var playLines: List<PlayLine> = emptyList()
    private var selectedLineIndex: Int = 0
    private var selectedEpisode: PlayEpisode? = null
    private var hasSelectedEpisodeHistory: Boolean = false

    companion object {
        private const val TAG = "DetailActivity"

        // —— Intent extra key ——
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_COVER = "extra_video_cover"
        const val EXTRA_VIDEO_CATEGORY = "extra_video_category"
        const val EXTRA_VIDEO_RATING = "extra_video_rating"
        const val EXTRA_VIDEO_PLAY_URL = "extra_video_play_url"
        const val EXTRA_VIDEO_YEAR = "extra_video_year"
        const val EXTRA_VIDEO_AREA = "extra_video_area"
        const val EXTRA_VIDEO_DIRECTOR = "extra_video_director"
        const val EXTRA_VIDEO_ACTORS = "extra_video_actors"
        const val EXTRA_VIDEO_DESCRIPTION = "extra_video_description"
        const val EXTRA_VIDEO_DETAIL_URL = "extra_video_detail_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 1. 解析 Intent
        videoId = intent.getLongExtra(EXTRA_VIDEO_ID, 0)
        videoTitle = cleanHistoryTitle(intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "")
        videoCover = intent.getStringExtra(EXTRA_VIDEO_COVER) ?: ""
        videoCategory = intent.getStringExtra(EXTRA_VIDEO_CATEGORY) ?: ""
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_PLAY_URL) ?: ""

        val rating = intent.getStringExtra(EXTRA_VIDEO_RATING) ?: ""
        val year = intent.getStringExtra(EXTRA_VIDEO_YEAR) ?: ""
        val area = intent.getStringExtra(EXTRA_VIDEO_AREA) ?: ""
        val director = intent.getStringExtra(EXTRA_VIDEO_DIRECTOR) ?: ""
        val actors = intent.getStringExtra(EXTRA_VIDEO_ACTORS) ?: ""
        val description = intent.getStringExtra(EXTRA_VIDEO_DESCRIPTION) ?: ""
        detailUrl = intent.getStringExtra(EXTRA_VIDEO_DETAIL_URL) ?: ""

        title = videoTitle

        Log.d(TAG, "========== DetailActivity.onCreate ==========")
        Log.d(TAG, "收到 Intent: videoId=$videoId, title=$videoTitle, category=$videoCategory")
        Log.d(TAG, "playUrl=${if (videoUrl.isNotEmpty()) videoUrl.take(60) + "..." else "(空)"}")
        Log.d(TAG, "rating=$rating, year=$year, area=$area, director=$director")

        // 2. 基础 UI：立即显示已有的标题/分类/封面
        binding.tvTitle.text = videoTitle.ifBlank { "加载中..." }
        binding.tvCategory.text = formatField("类型", videoCategory)

        // 年份/地区（若存在则合并显示在 tvYear，tvArea 隐藏避免重复）
        val yearArea = StringBuilder()
        if (year.isNotEmpty()) yearArea.append(year)
        if (area.isNotEmpty()) {
            if (yearArea.isNotEmpty()) yearArea.append(" · ")
            yearArea.append(area)
        }
        if (yearArea.isNotEmpty()) {
            binding.tvYear.text = formatField("上映时间", yearArea.toString())
            binding.tvYear.visibility = android.view.View.VISIBLE
            binding.tvArea.visibility = android.view.View.GONE
        } else {
            binding.tvYear.text = formatField("上映时间", "未知")
            binding.tvYear.visibility = android.view.View.VISIBLE
            binding.tvArea.visibility = android.view.View.GONE
        }

        // 评分
        if (rating.isNotEmpty()) {
            binding.tvRating.text = formatField("评分", rating)
            binding.tvRating.visibility = android.view.View.VISIBLE
        } else {
            binding.tvRating.text = formatField("评分", "0.0")
            binding.tvRating.visibility = android.view.View.VISIBLE
        }

        // 导演 / 主演 / 简介（若来自播放历史可能为空，由后面的回查补全）
        binding.tvDirector.text = director.ifEmpty { "加载中..." }
        binding.tvActors.text = actors.ifEmpty { "加载中..." }
        binding.tvDescription.text = description.ifEmpty { "加载中..." }

        // 封面
        if (videoCover.isNotEmpty()) {
            Log.d(TAG, "加载封面: ${videoCover.take(60)}")
            binding.ivCover.load(videoCover)
        } else {
            Log.d(TAG, "封面为空，不加载封面图")
        }
        binding.btnPlay.isEnabled = videoUrl.isNotEmpty()
        // 3. 播放按钮
        binding.btnPlay.setOnClickListener {
            playSelectedEpisodeOrVideo()
        }

        // 4. 若关键字段（导演/主演/简介/playUrl）缺失，走回查补全
        val needFetch = director.isEmpty() || actors.isEmpty() || description.isEmpty() || videoUrl.isEmpty()
        if (needFetch) {
            Log.d(TAG, "部分字段缺失，从 JSON 挡板回查视频详情 (videoId=$videoId)")
            fetchVideoDetail()
        } else {
            Log.d(TAG, "Intent 已提供全部字段，跳过回查")
        }

        // 5. 读取并显示播放进度（从播放历史）
        loadProgressFromHistory()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
    }

    /**
     * 获取视频详情（优先使用 detailUrl 爬取，否则 fallback 到本地 JSON）
     */
    private fun fetchVideoDetail() {
        lifecycleScope.launch {
            if (detailUrl.isNotBlank()) {
                Log.d(TAG, "使用爬虫获取详情页信息: detailUrl=$detailUrl")
                val detail = MovieApplication.get().videoRepository.getCrawlerVideoDetail(detailUrl)
                if (detail != null) {
                    applyCrawlerDetail(detail)
                    return@launch
                } else {
                    Log.w(TAG, "爬虫获取详情失败，尝试本地 JSON 回查")
                }
            }

            // 降级：从本地 JSON 挡板根据 videoId 回查
            val video = MovieApplication.get().videoRepository.getVideoById(videoId)
            if (video == null) {
                Log.w(TAG, "回查失败: 未找到 videoId=$videoId 的视频")
                if (binding.tvDirector.text == "加载中...") binding.tvDirector.text = "暂无导演信息"
                if (binding.tvActors.text == "加载中...") binding.tvActors.text = "暂无主演信息"
                if (binding.tvDescription.text == "加载中...") binding.tvDescription.text = "暂无简介"
                return@launch
            }
            Log.d(TAG, "本地回查成功: director=${video.director}, actors=${video.actors}")

            // 更新 UI 缺失字段
            if (binding.tvYear.text.isNullOrEmpty()) {
                val yearArea = StringBuilder()
                if (video.year.isNotEmpty()) yearArea.append(video.year)
                if (video.area.isNotEmpty()) {
                    if (yearArea.isNotEmpty()) yearArea.append(" · ")
                    yearArea.append(video.area)
                }
                if (yearArea.isNotEmpty()) {
                    binding.tvYear.text = formatField("上映时间", yearArea.toString())
                    binding.tvYear.visibility = android.view.View.VISIBLE
                }
            }
            if (video.rating.isNotEmpty() && binding.tvRating.text.isNullOrEmpty()) {
                binding.tvRating.text = formatField("评分", video.rating)
                binding.tvRating.visibility = android.view.View.VISIBLE
            }
            if (binding.tvDirector.text == "加载中...") binding.tvDirector.text = video.director
            if (binding.tvActors.text == "加载中...") binding.tvActors.text = video.actors
            if (binding.tvDescription.text == "加载中...") binding.tvDescription.text = video.description

            // 若 playUrl 此前为空，用回查结果更新
            if (videoUrl.isEmpty() && video.playUrl.isNotEmpty()) {
                videoUrl = video.playUrl
                Log.d(TAG, "从本地回查获取 playUrl: ${video.playUrl.take(60)}")
            }
            // 封面兜底
            if (videoCover.isEmpty() && video.coverUrl.isNotEmpty()) {
                videoCover = video.coverUrl
                binding.ivCover.load(video.coverUrl)
            }
            binding.btnPlay.isEnabled = videoUrl.isNotEmpty()
        }
    }

    private suspend fun applyCrawlerDetail(detail: CrawlerVideoDetail) {
        videoId = detail.id
        videoTitle = detail.title.ifBlank { videoTitle }
        videoCover = detail.coverUrl.ifBlank { videoCover }
        videoCategory = detail.category.ifBlank { videoCategory }
        playLines = detail.playLines
        selectedLineIndex = 0
        selectedEpisode = playLines.firstOrNull()?.episodes?.firstOrNull()

        val latestHistory = MovieApplication.get().playHistoryRepository.getLatestHistoryByDetailUrl(detail.detailUrl)
        if (latestHistory != null && latestHistory.playPageUrl.isNotBlank()) {
            playLines.forEachIndexed { lineIndex, line ->
                val matched = line.episodes.firstOrNull { it.playPageUrl == latestHistory.playPageUrl }
                if (matched != null) {
                    selectedLineIndex = lineIndex
                    selectedEpisode = matched
                    return@forEachIndexed
                }
            }
        }

        title = videoTitle
        binding.tvTitle.text = videoTitle.ifBlank { "未知片名" }
        binding.tvCategory.text = formatField("类型", detail.category.ifBlank { "未知" })
        binding.tvYear.text = formatField("上映时间", detail.year.ifBlank { "未知" })
        binding.tvRating.text = formatField("评分", detail.rating.ifBlank { "0.0" })
        binding.tvDirector.text = detail.director.ifBlank { "暂无导演信息" }
        binding.tvActors.text = detail.actors.ifBlank { "暂无主演信息" }
        binding.tvDescription.text = detail.description.ifBlank { "暂无简介" }

        if (videoCover.isNotBlank()) {
            binding.ivCover.load(videoCover)
        }

        renderPlayLines()
        binding.btnPlay.isEnabled = selectedEpisode != null || videoUrl.isNotBlank()
        updatePlayButtonText(false)
        loadProgressFromHistory()
    }

    private fun renderPlayLines() {
        binding.layoutPlayLines.removeAllViews()
        binding.gridEpisodes.removeAllViews()

        if (playLines.isEmpty()) {
            binding.layoutPlayLinesBlock.visibility = View.GONE
            return
        }

        binding.layoutPlayLinesBlock.visibility = View.VISIBLE
        playLines.forEachIndexed { index, line ->
            val chip = TextView(this).apply {
                text = line.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(if (index == selectedLineIndex) Color.parseColor("#FFFF6A3D") else Color.parseColor("#FF4B5563"))
                setBackgroundResource(if (index == selectedLineIndex) R.drawable.bg_chip_selected else R.drawable.bg_episode_normal)
                setOnClickListener {
                    selectedLineIndex = index
                    selectedEpisode = line.episodes.firstOrNull()
                    renderPlayLines()
                    renderEpisodes(line)
                    updatePlayButtonText(false)
                    loadProgressFromHistory()
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(10)
            }
            binding.layoutPlayLines.addView(chip, params)
        }

        renderEpisodes(playLines[selectedLineIndex])
    }

    private fun renderEpisodes(line: PlayLine) {
        binding.gridEpisodes.removeAllViews()
        binding.tvEpisodeTitle.text = if (line.episodes.size <= 1) "播放入口" else "选集 · 共 ${line.episodes.size} 集"

        line.episodes.forEach { episode ->
            val isSelected = episode.playPageUrl == selectedEpisode?.playPageUrl
            val item = TextView(this).apply {
                text = episode.title
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#FF374151"))
                setBackgroundResource(if (isSelected) R.drawable.bg_episode_selected else R.drawable.bg_episode_normal)
                setOnClickListener {
                    selectedEpisode = episode
                    renderEpisodes(line)
                    updatePlayButtonText(false)
                    loadProgressFromHistory()
                    playSelectedEpisodeOrVideo()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(8))
            }
            binding.gridEpisodes.addView(item, params)
        }
    }

    private fun updatePlayButtonText(hasProgress: Boolean = hasSelectedEpisodeHistory) {
        hasSelectedEpisodeHistory = hasProgress
        binding.btnPlay.text = if (hasProgress) "继续播放" else "立即播放"
    }

    private fun playSelectedEpisodeOrVideo() {
        val episode = selectedEpisode
        if (episode != null) {
            binding.btnPlay.isEnabled = false
            binding.btnPlay.text = "解析中..."
            lifecycleScope.launch {
                val realUrl = MovieApplication.get().videoRepository.getRealPlayUrlByPlayPageUrl(episode.playPageUrl)
                binding.btnPlay.isEnabled = true
                updatePlayButtonText()
                if (realUrl.isNullOrBlank()) {
                    Toast.makeText(this@DetailActivity, "播放地址解析失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                videoUrl = realUrl
                openPlayer(episode, realUrl)
            }
            return
        }

        if (videoUrl.isNotBlank()) {
            openPlayer(null, videoUrl)
        } else {
            Toast.makeText(this@DetailActivity, "视频地址加载中，请稍后", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayer(episode: PlayEpisode?, url: String) {
        val playbackId = getPlaybackId(episode)
        startActivity(
            PlayerActivity.newIntent(
                this@DetailActivity,
                playbackId,
                videoTitle,
                videoCover,
                videoCategory,
                url,
                detailUrl = detailUrl,
                playPageUrl = episode?.playPageUrl.orEmpty(),
                episodeTitle = if (isMultiEpisode()) episode?.title.orEmpty() else ""
            )
        )
    }

    /** 点击 ActionBar 返回箭头结束 Activity */
    override fun onSupportNavigateUp(): Boolean {
        Log.d(TAG, "onSupportNavigateUp → 结束当前 Activity")
        finish()
        return true
    }

    /**
     * 从播放历史中读取播放进度（如果存在）并在 tvProgressTip 上显示
     *   —— 例如 "继续观看 00:32:15" 或 "已观看 00:32:15 / 02:10:00"
     */
    private fun loadProgressFromHistory() {
        val app = MovieApplication.get()
        lifecycleScope.launch {
            val episode = selectedEpisode
            val history = app.playHistoryRepository.getHistoryByVideoId(getPlaybackId(episode))
            val isSameEpisode = episode == null ||
                !isMultiEpisode() ||
                history?.playPageUrl == episode.playPageUrl
            if (history != null && isSameEpisode && history.playProgressSeconds > 0) {
                updatePlayButtonText(true)
                val curSec = history.playProgressSeconds
                val totalSec = history.durationSeconds

                val curH = curSec / 3600
                val curM = (curSec % 3600) / 60
                val curS = curSec % 60

                val tip = if (curH > 0) {
                    String.format("继续观看 %d:%02d:%02d", curH, curM, curS)
                } else {
                    String.format("继续观看 %02d:%02d", curM, curS)
                }

                // 总时长已知，拼接成 "继续观看 00:32 / 02:10:00"
                val progressText = if (totalSec > 0) {
                    val totalH = totalSec / 3600
                    val totalM = (totalSec % 3600) / 60
                    val totalStr = if (totalH > 0) {
                        String.format("%d:%02d:%02d", totalH, totalM, totalSec % 60)
                    } else {
                        String.format("%02d:%02d", totalM, totalSec % 60)
                    }
                    "$tip  /  总时长 $totalStr"
                } else {
                    tip
                }

                val displayText = if (episode != null && isMultiEpisode()) {
                    "继续观看${normalizeEpisodeTitle(episode.title)} $progressText"
                } else {
                    progressText
                }

                binding.tvProgressTip.text = displayText
                binding.tvProgressTip.visibility = android.view.View.VISIBLE
                Log.d(TAG, "显示进度提示: $displayText (progress=${curSec}s, duration=${totalSec}s)")
            } else {
                updatePlayButtonText(false)
                binding.tvProgressTip.visibility = android.view.View.GONE
                Log.d(TAG, "无历史进度，隐藏提示")
            }
        }
    }

    private fun getPlaybackId(episode: PlayEpisode?): Long {
        // 播放历史按影视维度去重：同一部电视剧/电影只保留一条最新记录。
        // 当前播放到哪一集通过 playPageUrl / episodeTitle 冗余字段保存。
        return videoId
    }

    private fun isMultiEpisode(): Boolean {
        return playLines.any { it.episodes.size > 1 }
    }

    private fun formatField(label: String, value: String): String {
        return "$label：${value.ifBlank { "未知" }}"
    }

    private fun normalizeEpisodeTitle(title: String): String {
        val number = Regex("\\d+").find(title)?.value?.toIntOrNull()
        return if (number != null && title.contains("集")) {
            "第${number}集"
        } else {
            title
        }
    }

    private fun cleanHistoryTitle(title: String): String {
        return title.replace(Regex("\\s*第\\d+集\\s*$"), "").trim()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
