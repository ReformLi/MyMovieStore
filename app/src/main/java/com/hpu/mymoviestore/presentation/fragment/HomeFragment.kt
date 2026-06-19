package com.hpu.mymoviestore.presentation.fragment

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentHomeBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.activity.MainActivity
import com.hpu.mymoviestore.presentation.adapter.VideoAdapter
import com.hpu.mymoviestore.presentation.viewmodel.VideoViewModel

/**
 * 首页 Fragment —— 展示视频列表，提供 Tab 分类切换
 *
 * 视频数据完全来自 JSON 挡板 assets/sample_video_source.json。
 * - Tab 0 = 全部视频
 * - Tab 1~5 = 电影 / 电视剧 / 综艺 / 动漫 / 纪录片
 * - 点击列表项 → DetailActivity
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VideoViewModel
    private lateinit var adapter: VideoAdapter
    private var currentMainCategory: String = ""
    private var currentSubType: String = "全部"

    /** 分类常量 ——与 TabLayout 顺序一致 */
    private val categories = listOf(
        "",                 // 全部
        "电影",
        "电视剧",
        "综艺",
        "动漫"
    )
    private val movieSubTypes = listOf("全部", "华语", "欧美", "韩国", "日本")
    private val tvSubTypes = listOf("综合", "国产剧", "欧美剧", "日剧", "韩剧", "纪录片")
    private val showSubTypes = listOf("综合", "国内", "国外")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "HomeFragment onViewCreated")

        viewModel = ViewModelProvider(this)[VideoViewModel::class.java]
        adapter = VideoAdapter(
            onItemClick = { video -> openDetail(video) },
            onLoadMoreClick = {
                if (isDoubanPagedCategory(currentMainCategory)) {
                    viewModel.loadMoreHomeDoubanCategory()
                }
            }
        )

        setupViews()
        setupTabs()
        observeData()

        // 首次加载：全量视频列表
        viewModel.loadAllVideos()
    }

    private var currentSpanCount = 3

    private fun setupViews() {
        currentSpanCount = calculateSpanCount()
        updateGridLayoutManager()
        adapter.setSpanCount(currentSpanCount)
        binding.recyclerView.adapter = adapter
        setupSubTabs(movieSubTypes)
        Log.d(TAG, "RecyclerView + 九宫格 Adapter 初始化完成: span=$currentSpanCount")
    }

    /**
     * 根据屏幕宽度动态计算列数。
     * 每个卡片最小宽度约 120dp（含 margin），保证在不同屏幕和横竖屏下都有合适的列数。
     */
    private fun calculateSpanCount(): Int {
        val dm = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(dm)
        val screenWidthPx = dm.widthPixels
        val density = dm.density
        // 每个卡片最小宽度 120dp，margin 10dp，padding 8dp
        val minCardWidthDp = 120f
        val screenWidthDp = screenWidthPx / density
        val spanCount = (screenWidthDp / minCardWidthDp).toInt().coerceAtLeast(2).coerceAtMost(8)
        Log.d(TAG, "calculateSpanCount: screenWidthDp=$screenWidthDp, span=$spanCount")
        return spanCount
    }

    private fun updateGridLayoutManager() {
        val gridLayoutManager = GridLayoutManager(context, currentSpanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.isLoadMorePosition(position)) currentSpanCount else 1
            }
        }
        binding.recyclerView.layoutManager = gridLayoutManager
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newSpanCount = calculateSpanCount()
        if (newSpanCount != currentSpanCount) {
            currentSpanCount = newSpanCount
            updateGridLayoutManager()
            adapter.setSpanCount(currentSpanCount)
            Log.d(TAG, "屏幕旋转，更新列数: $currentSpanCount")
        }
    }

    private fun setupSubTabs(types: List<String>) {
        binding.layoutMovieSubTabContainer.removeAllViews()
        types.forEach { type ->
            val chip = TextView(requireContext()).apply {
                text = type
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    currentSubType = type
                    renderSubTabs()
                    viewModel.loadHomeDoubanCategory(currentMainCategory, type)
                    binding.recyclerView.scrollToPosition(0)
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(10)
            }
            binding.layoutMovieSubTabContainer.addView(chip, params)
        }
        renderSubTabs()
    }

    private fun renderSubTabs() {
        for (i in 0 until binding.layoutMovieSubTabContainer.childCount) {
            val chip = binding.layoutMovieSubTabContainer.getChildAt(i) as TextView
            val selected = chip.text.toString() == currentSubType
            chip.setTextColor(
                if (selected) {
                    ContextCompat.getColor(requireContext(), com.hpu.mymoviestore.R.color.colorPrimary)
                } else {
                    ContextCompat.getColor(requireContext(), com.hpu.mymoviestore.R.color.colorOnSurfaceSecondary)
                }
            )
            chip.setBackgroundResource(
                if (selected) {
                    com.hpu.mymoviestore.R.drawable.bg_chip_selected
                } else {
                    com.hpu.mymoviestore.R.drawable.bg_episode_normal
                }
            )
        }
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        categories.forEach { name ->
            tabLayout.addTab(
                tabLayout.newTab().setText(name.ifEmpty { "全部" })
            )
        }
        Log.d(TAG, "TabLayout 已添加 ${categories.size} 个分类")

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val category = categories[tab.position]
                currentMainCategory = category
                Log.d(TAG, "Tab 切换: position=${tab.position}, category='$category'")
                if (category.isEmpty()) {
                    binding.layoutMovieSubTabs.visibility = View.GONE
                    adapter.setShowLoadMore(false)
                    viewModel.loadAllVideos()
                } else if (category == "电影") {
                    currentSubType = "全部"
                    setupSubTabs(movieSubTypes)
                    binding.layoutMovieSubTabs.visibility = View.VISIBLE
                    adapter.setShowLoadMore(false)
                    viewModel.loadHomeDoubanCategory(category, currentSubType)
                } else if (category == "电视剧") {
                    currentSubType = "综合"
                    setupSubTabs(tvSubTypes)
                    binding.layoutMovieSubTabs.visibility = View.VISIBLE
                    adapter.setShowLoadMore(false)
                    viewModel.loadHomeDoubanCategory(category, currentSubType)
                } else if (category == "综艺") {
                    currentSubType = "综合"
                    setupSubTabs(showSubTypes)
                    binding.layoutMovieSubTabs.visibility = View.VISIBLE
                    adapter.setShowLoadMore(false)
                    viewModel.loadHomeDoubanCategory(category, currentSubType)
                } else if (category == "动漫") {
                    currentSubType = "综合"
                    binding.layoutMovieSubTabs.visibility = View.GONE
                    adapter.setShowLoadMore(false)
                    viewModel.loadHomeDoubanCategory(category, currentSubType)
                } else {
                    binding.layoutMovieSubTabs.visibility = View.GONE
                    adapter.setShowLoadMore(false)
                    viewModel.loadVideosByCategory(category)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * 观察 ViewModel 的 LiveData：
     * - allVideos    → 全量视频
     * - filterVideos → 分类过滤后的视频
     */
    private fun observeData() {
        viewModel.allVideos.observe(viewLifecycleOwner) { list ->
            Log.d(TAG, "allVideos 观察到变化: ${list?.size ?: 0} 条")
            renderList(list.orEmpty())
        }
        viewModel.filterVideos.observe(viewLifecycleOwner) { list ->
            Log.d(TAG, "filterVideos 观察到变化: ${list?.size ?: 0} 条")
            renderList(list.orEmpty())
        }
        viewModel.homeMovieHasMore.observe(viewLifecycleOwner) { hasMore ->
            adapter.setShowLoadMore(isDoubanPagedCategory(currentMainCategory) && hasMore == true)
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Log.w(TAG, "首页加载错误: ${error.userFacingMessage}")
                Toast.makeText(requireContext(), error.userFacingMessage, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    /** 根据当前列表展示视频或空状态 */
    private fun renderList(list: List<VideoItem>) {
        if (list.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            adapter.submitList(list)
        }
    }

    /** 首页内容发现点击 → 跳转搜索页并按影视名自动搜索 */
    private fun openDetail(video: VideoItem) {
        Log.d(TAG, "首页点击内容发现项，跳转搜索: id=${video.id}, title=${video.title}")
        (activity as? MainActivity)?.navigateToSearchWithKeyword(video.title)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "HomeFragment onDestroyView")
        _binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun isDoubanPagedCategory(category: String): Boolean {
        return category == "电影" || category == "电视剧" || category == "动漫" || category == "综艺"
    }
}
