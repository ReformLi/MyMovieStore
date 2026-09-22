package com.hpu.mymoviestore.presentation.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentHomeBinding
import com.hpu.mymoviestore.presentation.activity.MainActivity
import com.hpu.mymoviestore.presentation.adapter.VideoAdapter
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.viewmodel.VideoViewModel

/**
 * 首页 Fragment —— 展示视频列表，提供 Tab 分类切换
 *
 * 视频数据完全来自 JSON 挡板 assets/sample_video_source.json。
 * - Tab 0 = 全部视频
 * - Tab 1~5 = 电影 / 电视剧 / 综艺 / 动漫 / 纪录片
 * - 点击列表项 → DetailActivity
 *
 * TV 端：复用手机布局，通过 TvFocus 为 TabLayout、子分类 chip、网格卡片提供
 * D-pad 焦点支持和视觉反馈。
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
        setupSubTabsGestureConflict()
        observeData()

        // 首次加载：全量视频列表
        viewModel.loadAllVideos()
    }

    /**
     * 修复：子分类横向滚动条 [com.hpu.mymoviestore.R.id.layoutMovieSubTabs]（HorizontalScrollView）
     * 嵌在 MainActivity 的 ViewPager2 里，二者都响应横向拖拽。ViewPager2 内部是 RecyclerView，
     * 会在横滑时拦截事件，而 HorizontalScrollView 不参与嵌套滚动 → 子分类超出屏宽时，
     * 手指在其上横滑会被 ViewPager2 抢去切主 Tab，手机端滑不到后面的分类。
     *
     * 处理：按下即禁止父级拦截；当已滑到左/右边界、用户仍继续朝该方向拉时放行，
     * 让 ViewPager2 接管切页。返回 false 不消费事件，HSV 自身滚动不受影响。
     * 仅触屏需要，电视端走 D-pad（scrollContainer 已处理），此监听对遥控器无副作用。
     */
    private fun setupSubTabsGestureConflict() {
        var lastX = 0f
        binding.layoutMovieSubTabs.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    lastX = event.x
                    // dx>0 向右拉（看左侧内容）；dx<0 向左拉（看右侧内容）
                    val atLeftEdgePullingRight = dx > 0 && !v.canScrollHorizontally(-1)
                    val atRightEdgePullingLeft = dx < 0 && !v.canScrollHorizontally(1)
                    val yieldToPager = atLeftEdgePullingRight || atRightEdgePullingLeft
                    v.parent?.requestDisallowInterceptTouchEvent(!yieldToPager)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private var currentSpanCount = 3

    /**
     * TV 适配：电视端进入首页时给网格首项一个初始焦点落点，
     * 用户一按方向键即可在卡片间移动（手机端不介入）。
     * 仅当前可见页（ViewPager2 有预加载的离屏页）参与抢焦点。
     */
    override fun onResume() {
        super.onResume()
        if (!isVisible) return
        if (!TvUiSupport.isTelevision(requireContext())) return
        if (binding.recyclerView.findFocus() != null) return
        TvFocus.focusFirstItem(binding.recyclerView)
    }

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
        // 使用当前上下文的资源（电视端已被 TvUiSupport 放大密度）：
        // 若改用 windowManager 的物理屏幕参数，电视上会按 960dp 算出 8 列，卡片过小
        val dm = resources.displayMetrics
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
                minHeight = dp(48)  // 触控热区达 48dp 标准（原 padding 高度约 34dp，手机易误触相邻项）
            }
            // TV 适配：子分类可遥控器聚焦 + 焦点框，获焦时自动横向滚动到可见
            TvFocus.applyTo(chip, scale = 1.06f, scrollContainer = binding.layoutMovieSubTabs)
            chip.setOnClickListener {
                currentSubType = type
                renderSubTabs()
                viewModel.loadHomeDoubanCategory(currentMainCategory, type)
                binding.recyclerView.scrollToPosition(0)
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
                    ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                } else {
                    ContextCompat.getColor(requireContext(), R.color.colorOnSurfaceSecondary)
                }
            )
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_episode_normal
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

        // TV 适配：顶部 Tab 可遥控器聚焦（否则遥控器无法在分类间移动）
        for (i in 0 until tabLayout.tabCount) {
            tabLayout.getTabAt(i)?.view?.let { TvFocus.applyFocusableOnly(it) }
        }

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
        // 观察 loading 状态，控制加载覆盖层显示/隐藏
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading == true) {
                showLoading()
            } else {
                hideLoading()
            }
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

    // ======================== 加载动画 ========================

    /** 显示加载覆盖层 */
    private fun showLoading() {
        binding.loadingOverlay.root.visibility = View.VISIBLE
    }

    /** 隐藏加载覆盖层 */
    private fun hideLoading() {
        binding.loadingOverlay.root.visibility = View.GONE
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
