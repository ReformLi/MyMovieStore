package com.hpu.mymoviestore.presentation.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch

/**
 * 视频详情页 Activity
 *
 * 职责：
 * 1. 展示视频详细信息（标题 / 封面 / 分类 / 年份 / 地区 / 评分 / 导演 / 演员 / 简介）
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

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 1. 解析 Intent
        videoId = intent.getLongExtra(EXTRA_VIDEO_ID, 0)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        videoCover = intent.getStringExtra(EXTRA_VIDEO_COVER) ?: ""
        videoCategory = intent.getStringExtra(EXTRA_VIDEO_CATEGORY) ?: ""
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_PLAY_URL) ?: ""

        val rating = intent.getStringExtra(EXTRA_VIDEO_RATING) ?: ""
        val year = intent.getStringExtra(EXTRA_VIDEO_YEAR) ?: ""
        val area = intent.getStringExtra(EXTRA_VIDEO_AREA) ?: ""
        val director = intent.getStringExtra(EXTRA_VIDEO_DIRECTOR) ?: ""
        val actors = intent.getStringExtra(EXTRA_VIDEO_ACTORS) ?: ""
        val description = intent.getStringExtra(EXTRA_VIDEO_DESCRIPTION) ?: ""
        val detailUrl = intent.getStringExtra(EXTRA_VIDEO_DETAIL_URL) ?: ""

        title = videoTitle

        Log.d(TAG, "========== DetailActivity.onCreate ==========")
        Log.d(TAG, "收到 Intent: videoId=$videoId, title=$videoTitle, category=$videoCategory")
        Log.d(TAG, "playUrl=${if (videoUrl.isNotEmpty()) videoUrl.take(60) + "..." else "(空)"}")
        Log.d(TAG, "rating=$rating, year=$year, area=$area, director=$director")

        // 2. 基础 UI：立即显示已有的标题/分类/封面
        binding.tvTitle.text = videoTitle
        binding.tvCategory.text = videoCategory

        // 年份/地区（若存在则合并显示在 tvYear，tvArea 隐藏避免重复）
        val yearArea = StringBuilder()
        if (year.isNotEmpty()) yearArea.append(year)
        if (area.isNotEmpty()) {
            if (yearArea.isNotEmpty()) yearArea.append(" · ")
            yearArea.append(area)
        }
        if (yearArea.isNotEmpty()) {
            binding.tvYear.text = yearArea.toString()
            binding.tvYear.visibility = android.view.View.VISIBLE
            binding.tvArea.visibility = android.view.View.GONE
        } else {
            binding.tvYear.visibility = android.view.View.GONE
            binding.tvArea.visibility = android.view.View.GONE
        }

        // 评分
        if (rating.isNotEmpty()) {
            binding.tvRating.text = rating
            binding.tvRating.visibility = android.view.View.VISIBLE
        } else {
            binding.tvRating.visibility = android.view.View.GONE
        }

        // 导演 / 演员 / 简介（若来自播放历史可能为空，由后面的回查补全）
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
        binding.btnPlay.isEnabled = false   // 初始不可点
        // 3. 播放按钮
        binding.btnPlay.setOnClickListener {
            Log.d(TAG, "点击播放: url=${if (videoUrl.isNotEmpty()) videoUrl.take(60) + "..." else "(空)"}")
            if (videoUrl.isNotEmpty()) {
                startActivity(
                    PlayerActivity.newIntent(
                        this@DetailActivity,
                        videoId,
                        videoTitle,
                        videoCover,
                        videoCategory,
                        videoUrl
                    )
                )
            } else {
                Toast.makeText(this@DetailActivity, "视频地址加载中，请稍后", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. 若关键字段（导演/演员/简介/playUrl）缺失，走回查补全
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

    /**
     * 获取视频详情（优先使用 detailUrl 爬取，否则 fallback 到本地 JSON）
     */
    private fun fetchVideoDetail() {
        val detailUrl = intent.getStringExtra(EXTRA_VIDEO_DETAIL_URL) ?: ""
        lifecycleScope.launch {
            if (detailUrl.isNotBlank()) {
                // 优先使用爬虫获取真实播放地址
                Log.d(TAG, "使用爬虫获取视频地址: detailUrl=$detailUrl")
                val videoFromCrawler = MovieApplication.get().videoRepository.getVideoByDetailUrl(detailUrl)
                if (videoFromCrawler != null && videoFromCrawler.playUrl.isNotBlank()) {
                    // 更新播放地址
                    videoUrl = videoFromCrawler.playUrl
                    Log.d(TAG, "爬虫获取到播放地址: ${videoUrl.take(60)}")
                    // 可选：同时更新其他字段（如果爬虫返回了完整信息）
                    if (videoFromCrawler.title.isNotBlank()) binding.tvTitle.text = videoFromCrawler.title
                    if (videoFromCrawler.coverUrl.isNotBlank()) {
                        videoCover = videoFromCrawler.coverUrl
                        binding.ivCover.load(videoFromCrawler.coverUrl)
                    }
                    if (videoFromCrawler.director.isNotBlank()) binding.tvDirector.text = videoFromCrawler.director
                    if (videoFromCrawler.actors.isNotBlank()) binding.tvActors.text = videoFromCrawler.actors
                    if (videoFromCrawler.description.isNotBlank()) binding.tvDescription.text = videoFromCrawler.description
                    // 年份、地区等按需更新
                    // ...
                    // 刷新播放按钮可用性
                    binding.btnPlay.isEnabled = true
                    return@launch
                } else {
                    Log.w(TAG, "爬虫获取播放地址失败，尝试本地 JSON 回查")
                }
            }

            // 降级：从本地 JSON 挡板根据 videoId 回查
            val video = MovieApplication.get().videoRepository.getVideoById(videoId)
            if (video == null) {
                Log.w(TAG, "回查失败: 未找到 videoId=$videoId 的视频")
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
                    binding.tvYear.text = yearArea.toString()
                    binding.tvYear.visibility = android.view.View.VISIBLE
                }
            }
            if (video.rating.isNotEmpty() && binding.tvRating.text.isNullOrEmpty()) {
                binding.tvRating.text = video.rating
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
        }
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
            val history = app.playHistoryRepository.getHistoryByVideoId(videoId)
            if (history != null && history.playProgressSeconds > 0) {
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
                val displayText = if (totalSec > 0) {
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

                binding.tvProgressTip.text = displayText
                binding.tvProgressTip.visibility = android.view.View.VISIBLE
                Log.d(TAG, "显示进度提示: $displayText (progress=${curSec}s, duration=${totalSec}s)")
            } else {
                binding.tvProgressTip.visibility = android.view.View.GONE
                Log.d(TAG, "无历史进度，隐藏提示")
            }
        }
    }
}
