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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.database.MovieDatabase
import com.hpu.mymoviestore.data.download.DanmakuDownloadManager
import com.hpu.mymoviestore.data.download.DownloadCallback
import com.hpu.mymoviestore.data.download.DownloadEngine
import com.hpu.mymoviestore.data.download.DownloadStatus
import com.hpu.mymoviestore.data.download.DownloadService
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.databinding.ActivityDetailBinding
import com.hpu.mymoviestore.presentation.viewmodel.DownloadViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private lateinit var downloadViewModel: DownloadViewModel

    // 当前视频的业务字段（仅用于日志与播放跳转，不持久化收藏）
    private var videoId: Long = 0
    private var videoTitle: String = ""
    private var videoCover: String = ""
    private var videoCategory: String = ""
    private var videoUrl: String = ""
    private var detailUrl: String = ""
    private var sourceName: String = ""
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
        binding.btnDownload.isEnabled = videoUrl.isNotEmpty() || playLines.isNotEmpty()

        // 初始化 DownloadViewModel
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]

        // 下载按钮
        binding.btnDownload.setOnClickListener {
            handleDownloadClick()
        }

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
            binding.btnDownload.isEnabled = videoUrl.isNotEmpty() || playLines.isNotEmpty()
        }
    }

    private suspend fun applyCrawlerDetail(detail: CrawlerVideoDetail) {
        videoId = detail.id
        videoTitle = detail.title.ifBlank { videoTitle }
        videoCover = detail.coverUrl.ifBlank { videoCover }
        videoCategory = detail.category.ifBlank { videoCategory }
        sourceName = detail.sourceName
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
        binding.btnDownload.isEnabled = selectedEpisode != null || videoUrl.isNotBlank()
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
                setTextColor(if (index == selectedLineIndex) ContextCompat.getColor(this@DetailActivity, R.color.colorPrimary) else ContextCompat.getColor(this@DetailActivity, R.color.colorOnSurfaceSecondary))
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
                setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(this@DetailActivity, R.color.colorOnSurfaceSecondary))
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
                val result = MovieApplication.get().videoRepository.getRealPlayUrlByPlayPageUrl(episode.playPageUrl)
                binding.btnPlay.isEnabled = true
                updatePlayButtonText()
                val realUrl = result.getOrNull()
                if (realUrl.isNullOrBlank()) {
                    val errorMsg = result.exceptionOrNull()
                    val message = if (errorMsg != null && errorMsg is com.hpu.mymoviestore.data.model.CrawlError) {
                        errorMsg.userFacingMessage
                    } else {
                        "播放地址解析失败，请稍后重试"
                    }
                    Toast.makeText(this@DetailActivity, message, Toast.LENGTH_LONG).show()
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
                episodeTitle = if (isMultiEpisode()) episode?.title.orEmpty() else "",
                sourceName = sourceName
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

    // ======================== 下载功能 ========================

    /**
     * 处理下载按钮点击
     *
     * - 如果有多集，弹出剧集选择对话框（多选模式）
     * - 如果只有一集，直接创建下载任务
     */
    private fun handleDownloadClick() {
        val currentLine = playLines.getOrNull(selectedLineIndex)
        val episodes = currentLine?.episodes ?: emptyList()

        if (episodes.isEmpty()) {
            // 没有播放线路信息，使用 videoUrl 直接下载
            if (videoUrl.isNotBlank()) {
                startDownloadForEpisodes(
                    listOf(PlayEpisode(title = videoTitle, playPageUrl = videoUrl))
                )
            } else {
                Toast.makeText(this, "暂无可用的下载地址", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (episodes.size == 1) {
            // 只有一集，直接下载
            startDownloadForEpisodes(episodes)
            return
        }

        // 多集：弹出选择对话框
        showEpisodeSelectDialog(episodes)
    }

    /**
     * 显示剧集选择对话框（多选模式）
     *
     * - 默认选中当前播放的集数
     * - 已在下载管理中的集数默认选中且置灰（不可取消）
     * - 确认后调用 startDownloadForEpisodes 创建下载任务
     */
    private fun showEpisodeSelectDialog(episodes: List<PlayEpisode>) {
        // 查询该视频已有的下载任务
        val existingTasks = runBlocking {
            try {
                MovieApplication.get().downloadRepository.getTasksByVideoId(videoId)
            } catch (e: Exception) {
                Log.w(TAG, "查询已有下载任务失败: ${e.message}")
                emptyList()
            }
        }
        // 用 playPageUrl 匹配已有任务（避免索引不一致问题）
        val existingUrls = existingTasks.map { it.playUrl }.toSet()

        val episodeTitles = episodes.map { episode ->
            val suffix = if (episode.playPageUrl in existingUrls) "（已添加）" else ""
            "${episode.title}$suffix"
        }.toTypedArray()

        val checkedItems = BooleanArray(episodes.size) { index ->
            episodes[index].playPageUrl == selectedEpisode?.playPageUrl
                    || episodes[index].playPageUrl in existingUrls
        }

        lateinit var dialog: AlertDialog

        dialog = AlertDialog.Builder(this, R.style.RoundedDialog)
            .setTitle("选择下载集数")
            .setMultiChoiceItems(episodeTitles, checkedItems) { _, which, isChecked ->
                // 已在下载管理中的集数不允许取消选中
                if (episodes[which].playPageUrl in existingUrls) {
                    checkedItems[which] = true
                    dialog.listView.setItemChecked(which, true)
                } else {
                    checkedItems[which] = isChecked
                }
            }
            .setPositiveButton("确定") { _, _ ->
                // 只取非已有任务的集数
                val selectedEpisodes = episodes.filterIndexed { index, _ ->
                    checkedItems[index] && episodes[index].playPageUrl !in existingUrls
                }
                if (selectedEpisodes.isEmpty()) {
                    Toast.makeText(this, "没有新集需要下载", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startDownloadForEpisodes(selectedEpisodes)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

        // 初始化时置灰已有任务的选项
        dialog.listView.post {
            episodes.forEachIndexed { index, episode ->
                if (episode.playPageUrl in existingUrls) {
                    dialog.listView.setItemChecked(index, true)
                    val view = dialog.listView.getChildAt(index)
                    view?.isEnabled = false
                    view?.alpha = 0.5f
                }
            }
        }
    }

    /**
     * 为选中的集数创建下载任务
     *
     * 流程：
     * 1. 调用 DownloadViewModel.createTasks() 创建下载任务到数据库
     * 2. Toast 提示"已添加到下载列表"
     * 3. 启动 DownloadService（前台服务）
     * 4. 对每一集：解析真实播放地址 → 提交到 DownloadEngine → 下载弹幕
     */
    private fun startDownloadForEpisodes(episodes: List<PlayEpisode>) {
        // 1. 创建下载任务到数据库
        downloadViewModel.createTasks(
            videoId = videoId,
            title = videoTitle,
            coverUrl = videoCover,
            sourceName = sourceName,
            episodes = episodes
        )

        // 2. Toast 提示
        Toast.makeText(this, "已添加到下载列表", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "已创建 ${episodes.size} 个下载任务: videoId=$videoId, title=$videoTitle")

        // 3. 启动前台服务
        val serviceIntent = Intent(this, DownloadService::class.java)
        startForegroundService(serviceIntent)

        // 4. 对每一集解析真实播放地址并提交下载
        val app = MovieApplication.get()
        val downloadEngine = DownloadEngine.getInstance(this)
        val danmakuManager = DanmakuDownloadManager.getInstance(this)

        // 使用全局 CoroutineScope，确保离开详情页后仍能继续解析和下载
        val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        downloadScope.launch {
            // 第一步：并行解析所有集的 m3u8 地址（每集之间仍保持 3~5 秒间隔以保护源站）
            val m3u8Results = coroutineScope {
                episodes.mapIndexed { index, episode ->
                    async(Dispatchers.IO) {
                        // 从第二集开始，每集之间延迟 3~5 秒（模拟人工操作）
                        if (index > 0) {
                            val delayMs = (3000L..5000L).random()
                            Log.d(TAG, "下载：等待 ${delayMs}ms 后解析下一集（${index + 1}/${episodes.size}）")
                            delay(delayMs)
                        }
                        val result = app.videoRepository.getRealPlayUrlByPlayPageUrl(episode.playPageUrl)
                        episode to result.getOrNull()
                    }
                }
            }

            // 第二步：按顺序收集结果并提交到 DownloadEngine
            m3u8Results.forEach { deferred ->
                val (episode, m3u8Url) = deferred.await()

                if (m3u8Url.isNullOrBlank()) {
                    Log.w(TAG, "下载：解析播放地址失败, episode=${episode.title}")
                    return@forEach
                }

                // 使用数据库生成的 taskId（与 DownloadViewModel.createTasks 一致）
                // 使用 playPageUrl 的 hashCode 作为稳定的 episodeIndex
                val stableIndex = episode.playPageUrl.hashCode()
                val dbTaskId = "${videoId}_$stableIndex"

                // 提交到下载引擎
                val taskId = downloadEngine.submitTask(
                    m3u8Url = m3u8Url,
                    videoTitle = videoTitle,
                    episodeTitle = episode.title,
                    taskId = dbTaskId,
                    callback = object : DownloadCallback {
                        override fun onProgress(taskId: String, downloadedSegments: Int, totalSegments: Int, fileSize: Long) {
                            downloadScope.launch {
                                try {
                                    app.downloadRepository.updateProgress(
                                        taskId, downloadedSegments, totalSegments, fileSize
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "同步下载进度到数据库失败: ${e.message}")
                                }
                            }
                        }

                        override fun onStatusChanged(taskId: String, status: Int, errorMsg: String?) {
                            Log.d(TAG, "下载状态变更: taskId=$taskId, status=$status, error=$errorMsg")
                            downloadScope.launch {
                                try {
                                    when (status) {
                                        DownloadStatus.DOWNLOADING -> app.downloadRepository.markDownloading(taskId)
                                        DownloadStatus.PAUSED -> app.downloadRepository.pauseTask(taskId)
                                        DownloadStatus.FAILED -> app.downloadRepository.markFailed(taskId, errorMsg ?: "")
                                        else -> {}
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "同步下载状态到数据库失败: ${e.message}")
                                }
                            }
                        }

                        override fun onCompleted(taskId: String, localFilePath: String, fileSize: Long) {
                            Log.d(TAG, "下载完成: taskId=$taskId, path=$localFilePath, size=$fileSize")
                            downloadScope.launch {
                                try {
                                    app.downloadRepository.markCompleted(taskId, localFilePath, fileSize)
                                } catch (e: Exception) {
                                    Log.w(TAG, "同步下载完成到数据库失败: ${e.message}")
                                }
                            }
                        }
                    }
                )

                // 启动弹幕下载
                danmakuManager.startDanmakuDownload(
                    taskId = taskId,
                    title = videoTitle,
                    episodeTitle = episode.title,
                    dao = MovieDatabase.getInstance(this@DetailActivity).downloadTaskDao()
                )

                Log.d(TAG, "已提交下载: taskId=$taskId, episode=${episode.title}, m3u8=${m3u8Url.take(60)}")
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
