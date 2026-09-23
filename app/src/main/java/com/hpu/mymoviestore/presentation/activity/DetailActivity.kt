package com.hpu.mymoviestore.presentation.activity

import android.content.Intent
import android.content.pm.ActivityInfo
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
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.data.download.DownloadService
import com.hpu.mymoviestore.data.model.CrawlerVideoDetail
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.data.model.PlayLine
import com.hpu.mymoviestore.databinding.ActivityDetailBinding
import com.hpu.mymoviestore.presentation.dialog.EpisodeSelectDialog
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.viewmodel.AppViewModelFactory
import com.hpu.mymoviestore.presentation.viewmodel.DownloadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** TV 适配：电视端放大 UI 密度（10-foot UI），手机端原样返回 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hpu.mymoviestore.presentation.tv.TvUiSupport.wrapContext(newBase))
    }


    private lateinit var binding: ActivityDetailBinding
    private lateinit var downloadViewModel: DownloadViewModel

    /** 是否运行在电视端（10-foot UI）：决定下载按钮显隐与初始焦点策略 */
    private var isTv: Boolean = false

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

    /** TV 适配：选集网格里「当前高亮那集」的视图，供 ensureTvFocus 把初始焦点直接交给它 */
    private var selectedEpisodeView: View? = null

    /**
     * TV 适配：首次进入的初始焦点是否已交付给「高亮那一集」。
     *
     * 只交付一次 —— 之后焦点完全交给方向键几何搜索与用户操作，页面不再抢焦点。
     * 数据尚未就绪（[selectedEpisodeView] 仍为 null，例如页面刚 onCreate、选集还在爬）时
     * **不消费这次机会**，留给下一次调用重试。
     */
    private var initialEpisodeFocusDone: Boolean = false

    private var hasSelectedEpisodeHistory: Boolean = false

    companion object {
        private const val SAVED_SELECTED_LINE_INDEX = "saved_selected_line_index"
        private const val SAVED_SELECTED_EPISODE_URL = "saved_selected_episode_url"
        private const val SAVED_HAS_SELECTED_EPISODE_HISTORY = "saved_has_selected_episode_history"

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
        // 形态定方向（必须在 inflate 之前 —— 方向决定用 layout/ 还是 layout-land/）：
        // 手机恒竖屏、电视恒横屏。清单里刻意不写 screenOrientation（清单分不出形态，
        // 写死会让手机竖屏被强制转横屏），下载按钮显隐与初始焦点也依赖 isTv。
        isTv = com.hpu.mymoviestore.presentation.tv.TvUiSupport.isTelevision(this)
        applyOrientation()
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
        downloadViewModel = ViewModelProvider(
            this,
            AppViewModelFactory(application as MovieApplication)
        )[DownloadViewModel::class.java]

        // TV 适配：播放按钮自绘焦点框 + 获焦放大。
        // 播放/下载按钮是品牌橙底，焦点环须用白色变体 —— 橙环压在橙底上等于没有焦点框。
        TvFocus.applyTo(
            binding.btnPlay,
            scale = 1.04f,
            ringRes = R.drawable.bg_tv_focus_ring_light
        )
        if (isTv) {
            // 电视端整体隐藏下载入口：文件落在电视本机没有意义，且遥控器操作成本高
            binding.btnDownload.visibility = View.GONE
        } else {
            TvFocus.applyTo(
                binding.btnDownload,
                scale = 1.04f,
                ringRes = R.drawable.bg_tv_focus_ring_light
            )
        }

        // TV 适配：下方三个「信息模块」（导演 / 主演 / 简介）在**横屏布局**里是"可聚焦不可点击"
        // 的只读停靠点（结构约束 descendantFocusability=blocksDescendants 仍由 XML 承担）。
        // 聚焦能力 + 自绘焦点环在这里由 TvFocus 在**电视端**赋予，**不写进 layout-land**：
        // 那是手机横屏也会加载的目录，写死会让手机横屏一进详情页就冒出橙色描边。
        // 左栏「影片信息」卡不在列 —— 它在横屏布局里本就不参与焦点导航。
        listOf(binding.cardDirector, binding.cardActors, binding.cardDescription)
            .forEach { TvFocus.applyFocusableOnly(it) }

        // 下载按钮
        binding.btnDownload.setOnClickListener {
            handleDownloadClick()
        }

        // 3. 播放按钮
        binding.btnPlay.setOnClickListener {
            playSelectedEpisodeOrVideo()
        }

        // 4. 若关键字段（导演/主演/简介/playUrl）缺失，走回查补全
        val needFetch = director.isEmpty() || actors.isEmpty() || description.isEmpty() || videoUrl.isEmpty() || (playLines.isEmpty() && detailUrl.isNotBlank())
        if (needFetch) {
            Log.d(TAG, "部分字段缺失，从 JSON 挡板回查视频详情 (videoId=$videoId)")
            showLoading("正在加载详情...")
            fetchVideoDetail()
        } else {
            Log.d(TAG, "Intent 已提供全部字段，跳过回查")
        }

        // 5. 读取并显示播放进度（从播放历史）
        loadProgressFromHistory()

        // 6. TV 适配：保证进页面就有焦点落点
        ensureTvFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SAVED_SELECTED_LINE_INDEX, selectedLineIndex)
        outState.putString(SAVED_SELECTED_EPISODE_URL, selectedEpisode?.playPageUrl)
        outState.putBoolean(SAVED_HAS_SELECTED_EPISODE_HISTORY, hasSelectedEpisodeHistory)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        selectedLineIndex = savedInstanceState.getInt(SAVED_SELECTED_LINE_INDEX, 0)
        savedInstanceState.getString(SAVED_SELECTED_EPISODE_URL)?.let { url ->
            selectedEpisode = playLines.flatMap { it.episodes }.firstOrNull { it.playPageUrl == url }
        }
        hasSelectedEpisodeHistory = savedInstanceState.getBoolean(SAVED_HAS_SELECTED_EPISODE_HISTORY, false)
        if (playLines.isNotEmpty()) {
            renderPlayLines()
            updatePlayButtonText()
            // Activity 重建后视图都是新的、没有焦点，补一次初始焦点兜底
            ensureTvFocus()
        }
    }

    /**
     * 消费系统栏 insets：**叠加**到布局声明的 padding 之上，而不是替代它。
     *
     * 基准 padding 只在挂监听前取一次，此后恒为「XML 值 + insets」。
     * 旧实现 `setPadding(0, top, 0, bottom)` 会把左右 padding 整段清零 ——
     * 这正是 layout-land 的 Activity 根布局一直无法声明 TV overscan 安全边距的原因：
     * 写在根上的 paddingStart/End 会被这里抹掉，只能一层层挂到子容器上。
     *
     * insets 取 systemBars ∪ displayCutout：刘海屏下两者的安全区不总是相等，
     * getInsets 传并集即为「逐边取大」。
     */
    private fun applySystemBarInsets() {
        val root = binding.root
        val baseStart = root.paddingStart
        val baseTop = root.paddingTop
        val baseEnd = root.paddingEnd
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPaddingRelative(
                baseStart + bars.left,
                baseTop + bars.top,
                baseEnd + bars.right,
                baseBottom + bars.bottom
            )
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
                    hideLoading()
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
                hideLoading()
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
            hideLoading()
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

        // 恢复上次播放位置：先按 detailUrl 找该详情页的最近记录，查不到再按 videoId 兜底
        // （从首页/搜索进入时 detailUrl 可能为空，此时 detailUrl 查询恒为空集）。
        // 命中后把 selectedLineIndex / selectedEpisode 一并切到历史那一集 ——
        // 选集网格的高亮（bg_episode_selected）与电视端初始焦点都取自 selectedEpisode，
        // 因此两者天然落在同一集上。
        val historyRepo = MovieApplication.get().playHistoryRepository
        val latestHistory = (if (detail.detailUrl.isNotBlank()) {
            historyRepo.getLatestHistoryByDetailUrl(detail.detailUrl)
        } else null) ?: historyRepo.getHistoryByVideoId(videoId)
        val historyEpisodeUrl = latestHistory?.playPageUrl.orEmpty()
        if (historyEpisodeUrl.isNotBlank()) {
            for ((lineIndex, line) in playLines.withIndex()) {
                val matched = line.episodes.firstOrNull { it.playPageUrl == historyEpisodeUrl }
                if (matched != null) {
                    selectedLineIndex = lineIndex
                    selectedEpisode = matched
                    break
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
        // 数据就绪：把首次进入的初始焦点交给「高亮那一集」
        // （无历史 = 第 1 集；有历史 = 历史进度那一集），而不是笼统的兜底落点
        ensureTvFocus()
    }

    /**
     * 形态定方向：与 MainActivity.applyOrientation 同一策略（手机竖屏 / 电视横屏，不做动态切换）。
     */
    private fun applyOrientation() {
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * TV 适配：确保页面上有焦点落点。分两步，都 post 到主线程队列执行。
     *
     * **1. 初始焦点（只交付一次）** —— 选集渲染完成后，把焦点交给「高亮那一集」：
     * 无播放历史时高亮是第 1 集，有「继续观看」历史时高亮是历史进度那一集
     * （见 [applyCrawlerDetail] 的历史匹配）—— 焦点与高亮因此天然落在同一集上。
     * 数据未就绪（[selectedEpisodeView] 为 null）**不消费这次机会**，留给后续调用重试。
     *
     * **2. 兜底落点（可重复）** —— 只在「当前一个获焦视图都没有」时才补一个安全落点，
     * 避免数据加载失败或极慢时页面完全没有焦点（遥控器要先按方向键才「唤醒」）。
     *
     * 候选里刻意没有左栏「影片信息」卡 —— 它在横屏布局里已改为不可聚焦，
     * 遥控器焦点只走「右栏（线路 / 选集）+ 下方三卡」；竖屏手机端由 isTv 守卫整体跳过。
     */
    private fun ensureTvFocus() {
        if (!isTv) return

        // 1. 初始焦点：数据就绪后才消费这一次机会
        if (!initialEpisodeFocusDone) {
            selectedEpisodeView?.let { target ->
                initialEpisodeFocusDone = true
                binding.root.post { TvFocus.requestInitialFocus(target) }
            }
        }

        // 2. 兜底落点
        binding.root.post {
            if (binding.root.findFocus() != null) return@post
            val target = when {
                // 竖屏布局里的主播放按钮（横屏布局下它是 GONE，播放入口就是选集网格）。
                // GONE 的 View requestFocus 会静默失败，故必须同时判可见性
                binding.btnPlay.isEnabled && binding.btnPlay.visibility == View.VISIBLE -> binding.btnPlay
                // 选集网格已渲染：交给线路 chip 里的第一个（几何搜索也能到选集）
                binding.layoutPlayLines.childCount > 0 -> binding.layoutPlayLines.getChildAt(0)
                // 兜底：数据还没回来时焦点先停在下方「导演」卡上（左栏影片信息卡已不可聚焦）
                else -> binding.cardDirector
            }
            TvFocus.requestInitialFocus(target)
        }
    }

    private fun renderPlayLines() {
        binding.layoutPlayLines.removeAllViews()
        binding.gridEpisodes.removeAllViews()

        if (playLines.isEmpty()) {
            binding.layoutPlayLinesBlock.visibility = View.GONE
            // 电视端整卡一并隐藏：主操作行（立即播放 / 下载）在电视上是整体 GONE 的，
            // 没有播放线路时卡片里只剩一层空背景，会在右栏留一张「空白卡」。
            // 手机竖屏保留卡片 —— 「立即播放」仍是 videoUrl 直连时的可用入口。
            if (isTv) binding.cardPlayLines.visibility = View.GONE
            return
        }

        binding.cardPlayLines.visibility = View.VISIBLE
        binding.layoutPlayLinesBlock.visibility = View.VISIBLE
        // 线路 chip 的横向滚动容器：线路较多时把获焦项滚入可视区（否则焦点可能落在屏幕外的 chip 上）
        val lineScrollContainer =
            binding.layoutPlayLines.parent as? android.widget.HorizontalScrollView
        playLines.forEachIndexed { index, line ->
            val chip = TextView(this).apply {
                text = line.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minHeight = dp(48)  // 触控热区达 48dp 标准
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
            // TV 适配：播放线路可遥控器聚焦 + 获焦时横向滚入可视区
            TvFocus.applyTo(chip, scale = 1.06f, scrollContainer = lineScrollContainer)
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
        // TV 适配：重渲染会销毁旧视图，先记录焦点是否在集数网格内，渲染后还给选中项
        val gridHadFocus = binding.gridEpisodes.findFocus() != null
        binding.gridEpisodes.removeAllViews()
        selectedEpisodeView = null
        binding.tvEpisodeTitle.text = if (line.episodes.size <= 1) "播放入口" else "选集 · 共 ${line.episodes.size} 集"
        var selectedView: View? = null

        line.episodes.forEach { episode ->
            val isSelected = episode.playPageUrl == selectedEpisode?.playPageUrl
            val item = TextView(this).apply {
                text = episode.title
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(6), dp(8), dp(6), dp(8))
                minHeight = dp(48)  // 触控热区达 48dp 标准
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
            // TV 适配：集数条目可遥控器聚焦
            TvFocus.applyTo(item, scale = 1.06f)
            if (isSelected) selectedView = item
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(8))
            }
            binding.gridEpisodes.addView(item, params)
        }

        // 焦点重定位：仅当重渲染前焦点在网格内时才交还，避免初次进入抢走播放按钮焦点
        if (gridHadFocus) selectedView?.let { TvFocus.requestInitialFocus(it) }
        // 记录高亮集视图，供 ensureTvFocus 做初始焦点兜底
        selectedEpisodeView = selectedView
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
     * 显示剧集选择对话框（居中卡片多选弹窗 EpisodeSelectDialog）
     *
     * - 默认选中当前播放的集数
     * - 已在下载管理中的集数默认选中且置灰（不可取消，全选/全不选也会跳过）
     * - 支持「全选 ⇄ 全不选」切换 + 已选计数（全部已添加时隐藏该按钮）
     * - 确认后调用 startDownloadForEpisodes 创建下载任务
     */
    private fun showEpisodeSelectDialog(episodes: List<PlayEpisode>) {
        // 查询该视频已有的下载任务（通过 lifecycleScope 异步获取）
        lifecycleScope.launch(Dispatchers.IO) {
            val existingTasks = try {
                MovieApplication.get().downloadRepository.getTasksByVideoId(videoId)
            } catch (e: Exception) {
                Log.w(TAG, "查询已有下载任务失败: ${e.message}")
                emptyList<com.hpu.mymoviestore.data.entity.DownloadTaskEntity>()
            }
            withContext(Dispatchers.Main) {
                showEpisodeSelectDialogInner(episodes, existingTasks)
            }
        }
    }

    private fun showEpisodeSelectDialogInner(
        episodes: List<PlayEpisode>,
        existingTasks: List<com.hpu.mymoviestore.data.entity.DownloadTaskEntity>
    ) {
        // 用 playPageUrl 匹配已有任务（避免索引不一致问题）
        val existingUrls = existingTasks.map { it.playUrl }.toSet()

        EpisodeSelectDialog.show(
            context = this,
            episodes = episodes,
            existingUrls = existingUrls,
            selectedUrl = selectedEpisode?.playPageUrl
        ) { selectedEpisodes ->
            // 回调里只含本次新增的集数（已排除已在下载列表中的集）
            startDownloadForEpisodes(selectedEpisodes)
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
        // Toast 提示
        Toast.makeText(this, "已添加到下载列表", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "已创建 ${episodes.size} 个下载任务: videoId=$videoId, title=$videoTitle")

        // 启动前台服务
        val serviceIntent = Intent(this, DownloadService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        val app = MovieApplication.get()
        val downloadEngine = DownloadEngine.getInstance(this)
        val danmakuManager = DanmakuDownloadManager.getInstance(this)

        // 使用 Application 级 CoroutineScope，确保离开详情页后下载回调仍能更新数据库
        val downloadScope = MovieApplication.get().applicationScope

        downloadScope.launch {
            // 第零步：先同步创建数据库任务，确保后续 startDanmakuDownload 调用
            // dao.updateDanmakuStatus 时数据库中已有对应行（否则更新 0 行，弹幕状态永远停留 0）
            episodes.forEach { episode ->
                val stableIndex = episode.playPageUrl.hashCode()
                try {
                    app.downloadRepository.createTask(
                        videoId = videoId,
                        title = videoTitle,
                        coverUrl = videoCover,
                        sourceName = sourceName,
                        episodeIndex = stableIndex,
                        episodeTitle = episode.title,
                        playUrl = episode.playPageUrl
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "创建下载任务失败: episode=${episode.title}, error=${t.message}")
                }
            }
            Log.d(TAG, "批量创建下载任务完成: videoId=$videoId, count=${episodes.size}")
            // 第一步：串行解析每集的 m3u8 地址（每集之间保持 3~5 秒间隔以保护源站）
            // 必须串行：RequestRateLimiter 对同优先级(PLAY)任务会互相抢占，
            // 并行提交多个 PLAY 请求会导致除最后一个外全部被取消
            val m3u8Results = mutableListOf<Pair<PlayEpisode, String?>>()
            episodes.forEachIndexed { index, episode ->
                if (index > 0) {
                    val delayMs = (3000L..5000L).random()
                    Log.d(TAG, "下载：等待 ${delayMs}ms 后解析下一集（${index + 1}/${episodes.size}）")
                    delay(delayMs)
                }
                val result = app.videoRepository.getRealPlayUrlByPlayPageUrl(episode.playPageUrl)
                m3u8Results.add(episode to result.getOrNull())
            }

            // 第二步：按顺序提交到 DownloadEngine
            m3u8Results.forEach { (episode, m3u8Url) ->

                if (m3u8Url.isNullOrBlank()) {
                    Log.w(TAG, "下载：解析播放地址失败, episode=${episode.title}")
                    // 将数据库中的任务标记为失败，避免任务永远停留在 PENDING 状态
                    val stableIndex = episode.playPageUrl.hashCode()
                    val dbTaskId = "${videoId}_$stableIndex"
                    downloadScope.launch {
                        try {
                            app.downloadRepository.markFailed(dbTaskId, "解析播放地址失败")
                        } catch (e: Exception) {
                            Log.w(TAG, "标记任务失败状态出错: ${e.message}")
                        }
                    }
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
                                        DownloadStatus.MERGING -> app.downloadRepository.updateStatus(taskId, DownloadTaskEntity.STATUS_MERGING, "合并中…")
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

                // 提交任务后立即开始弹幕下载，不等待视频下载完成
                danmakuManager.startDanmakuDownload(
                    taskId = taskId,
                    title = videoTitle,
                    episodeTitle = episode.title,
                    dao = MovieDatabase.getInstance(app).downloadTaskDao()
                )

                Log.d(TAG, "已提交下载: taskId=$taskId, episode=${episode.title}, m3u8=${m3u8Url.take(60)}")
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    // ======================== 加载动画 ========================

    /** 显示加载覆盖层 */
    private fun showLoading(message: String = "加载中...") {
        binding.loadingOverlay.root.visibility = View.VISIBLE
        binding.loadingOverlay.tvLoadingText.text = message
    }

    /** 隐藏加载覆盖层 */
    private fun hideLoading() {
        binding.loadingOverlay.root.visibility = View.GONE
    }
}
