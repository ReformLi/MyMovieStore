package com.hpu.mymoviestore.presentation.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentSearchBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.adapter.SearchResultAdapter
import com.hpu.mymoviestore.presentation.viewmodel.SearchHistoryViewModel
import com.hpu.mymoviestore.presentation.viewmodel.VideoViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页 Fragment
 *
 * 数据源：
 *   - 视频列表：VideoRepository（JSON 挡板，内存缓存）
 *   - 搜索历史：SearchHistoryRepository（Room，持久化）
 *
 * 流程：
 * 1. 首次进入 -> 展示搜索历史（若有）+ 全量视频列表（便于浏览）
 * 2. 输入关键字（自动过滤）-> 搜索标题/演员/导演/简介
 * 3. 点击历史关键词 -> 填入搜索框 + 立即搜索 + 更新历史（刷新搜索次数 & 时间）
 * 4. 点击"清空历史" -> 删除全部历史
 * 5. 点击视频列表项 -> DetailActivity（携带完整 VideoItem 信息）
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VideoViewModel
    private lateinit var historyViewModel: SearchHistoryViewModel
    private lateinit var adapter: SearchResultAdapter
    private var currentKeyword: String = ""
    private var currentPage: Int = 1
    private var hasPrevPage: Boolean = false
    private var hasNextPage: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "SearchFragment onViewCreated")

        viewModel = ViewModelProvider(this)[VideoViewModel::class.java]
        historyViewModel = ViewModelProvider(this)[SearchHistoryViewModel::class.java]
        adapter = SearchResultAdapter { video -> openDetail(video) }

        setupViews()
        observeData()
        binding.tvEmpty.visibility = View.VISIBLE
    }

    private fun setupViews() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        Log.d(TAG, "RecyclerView + VideoAdapter 初始化完成")

        // 文本变化：关键字为空时显示搜索历史；实际搜索由按钮、键盘搜索或历史词触发，避免频繁请求源站
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s?.toString()?.trim() ?: ""
                Log.d(TAG, "搜索框变化: '$keyword'")
                if (keyword.isEmpty()) {
                    binding.layoutSearchHistory.visibility = View.VISIBLE
                    binding.tvSearchSummary.visibility = View.GONE
                    binding.layoutPagination.visibility = View.GONE
                    binding.recyclerView.visibility = View.GONE
                    binding.tvEmpty.text = "输入关键词后点击搜索"
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    binding.layoutSearchHistory.visibility = View.GONE
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // 输入法的"搜索"按钮：提交搜索 -> 写入搜索历史
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            val keyword = binding.etSearch.text?.toString()?.trim() ?: ""
            if (actionId == EditorInfo.IME_ACTION_SEARCH && keyword.isNotEmpty()) {
                performSearch(keyword, 1)
                true
            } else {
                false
            }
        }

        binding.btnSearch.setOnClickListener {
            val keyword = binding.etSearch.text?.toString()?.trim().orEmpty()
            if (keyword.isNotEmpty()) {
                performSearch(keyword, 1)
            }
        }

        binding.btnPrevPage.setOnClickListener {
            if (hasPrevPage && currentPage > 1) {
                performSearch(currentKeyword, currentPage - 1)
            }
        }

        binding.btnNextPage.setOnClickListener {
            if (hasNextPage) {
                performSearch(currentKeyword, currentPage + 1)
            }
        }

        // 点击"清空历史"
        binding.tvClearHistory.setOnClickListener {
            Log.d(TAG, "点击清空搜索历史")
            historyViewModel.clearAll()
        }
    }

    /**
     * 观察：
     *  - 视频搜索结果：allVideos / searchVideos -> 渲染列表
     *  - 搜索历史：searchHistory -> 动态生成 Chip 并展示
     */
    private fun observeData() {
        viewModel.searchPageResult.observe(viewLifecycleOwner) { result ->
            Log.d(TAG, "searchPageResult: keyword=${result.keyword}, page=${result.page}, size=${result.items.size}")
            currentKeyword = result.keyword
            currentPage = result.page
            hasPrevPage = result.hasPrev
            hasNextPage = result.hasNext
            renderList(result.items)
            renderPagination(result.page, result.totalPages, result.hasPrev, result.hasNext)
            binding.tvSearchSummary.text = "${result.keyword}搜索结果：第 ${result.page} 页，共 ${result.items.size} 条"
            binding.tvSearchSummary.visibility = View.VISIBLE
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            if (loading == true) {
                binding.recyclerView.visibility = View.GONE
                binding.tvEmpty.text = "搜索中..."
                binding.tvEmpty.visibility = View.VISIBLE
                binding.layoutPagination.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Log.w(TAG, "搜索加载错误: ${error.userFacingMessage}")
                Toast.makeText(requireContext(), error.userFacingMessage, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // 搜索历史变化：重新构建 Chip 列表
        historyViewModel.searchHistory.observe(viewLifecycleOwner) { historyList ->
            renderHistoryChips(historyList.orEmpty())
        }
    }

    /** 视频列表为空 -> 显示空状态；否则显示列表 */
    private fun renderList(list: List<VideoItem>) {
        if (list.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "没有搜索到相关影片"
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            adapter.submitList(list)
        }
    }

    private fun renderPagination(page: Int, totalPages: Int, hasPrev: Boolean, hasNext: Boolean) {
        val showPagination = hasPrev || hasNext || totalPages > 1
        Log.d(
            TAG,
            "renderPagination: page=$page, totalPages=$totalPages, " +
                "hasPrev=$hasPrev, hasNext=$hasNext, show=$showPagination"
        )
        binding.layoutPagination.visibility = if (showPagination) View.VISIBLE else View.GONE
        binding.tvPageInfo.text = "第 $page / $totalPages 页"
        binding.btnPrevPage.isEnabled = hasPrev && page > 1
        binding.btnNextPage.isEnabled = hasNext
        binding.btnPrevPage.alpha = if (binding.btnPrevPage.isEnabled) 1f else 0.45f
        binding.btnNextPage.alpha = if (binding.btnNextPage.isEnabled) 1f else 0.45f
    }

    private fun performSearch(keyword: String, page: Int) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return
        currentKeyword = cleanKeyword
        currentPage = page.coerceAtLeast(1)

        // 检查搜索权限（非阻塞快速检查）
        val hasPermission = MovieApplication.get().searchPermissionRepository.checkPermissionFast()
        if (!hasPermission) {
            Log.w(TAG, "搜索权限检查未通过，禁止搜索")
            binding.tvSearchSummary.visibility = View.GONE
            binding.tvEmpty.text = "搜索功能暂不可用"
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.layoutPagination.visibility = View.GONE
            Toast.makeText(requireContext(), "搜索功能暂不可用", Toast.LENGTH_SHORT).show()
            return
        }

        // 后台异步触发权限检查（fetchPermissionAsync 内部会判断缓存，有效则跳过）
        CoroutineScope(Dispatchers.IO).launch {
            MovieApplication.get().searchPermissionRepository.fetchPermissionAsync()
        }

        // 权限通过，继续搜索
        historyViewModel.addKeyword(cleanKeyword)
        binding.layoutSearchHistory.visibility = View.GONE
        binding.tvSearchSummary.text = "正在搜索\"$cleanKeyword\"..."
        binding.tvSearchSummary.visibility = View.VISIBLE
        // 隐藏输入法键盘
        hideKeyboard()
        viewModel.searchVideosPage(cleanKeyword, currentPage)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val view = activity?.currentFocus ?: binding.etSearch
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun searchFromExternal(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank() || _binding == null) return
        Log.d(TAG, "外部跳转搜索: keyword='$cleanKeyword'")
        binding.etSearch.setText(cleanKeyword)
        binding.etSearch.setSelection(cleanKeyword.length)
        binding.recyclerView.scrollToPosition(0)
        performSearch(cleanKeyword, 1)
    }

    fun resetToInitialState() {
        if (_binding == null) return
        Log.d(TAG, "手动进入搜索页，重置为初始状态")
        currentKeyword = ""
        currentPage = 1
        hasPrevPage = false
        hasNextPage = false
        binding.etSearch.setText("")
        adapter.submitList(emptyList())
        binding.recyclerView.visibility = View.GONE
        binding.tvSearchSummary.visibility = View.GONE
        binding.layoutPagination.visibility = View.GONE
        binding.tvEmpty.text = "输入关键词后点击搜索"
        binding.tvEmpty.visibility = View.VISIBLE
        binding.layoutSearchHistory.visibility = View.VISIBLE
    }

    /**
     * 是否处于"已展示搜索结果"的状态。
     * 用于 MainActivity 在系统返回键时判断：
     *   - true：先回到搜索原页面（清空结果，保留历史）
     *   - false：再按返回，由上层切回首页或退出
     */
    fun isShowingSearchResult(): Boolean {
        if (_binding == null) return false
        return currentKeyword.isNotBlank() ||
            binding.recyclerView.visibility == View.VISIBLE ||
            binding.layoutPagination.visibility == View.VISIBLE
    }

    /**
     * 渲染搜索历史 chip 列表
     * - 每行放若干 chip，超出自动换行
     * - 最多显示 12 条（避免过长）
     */
    private fun renderHistoryChips(list: List<SearchHistoryEntity>) {
        Log.d(TAG, "renderHistoryChips: ${list.size} 条历史")

        val container = binding.containerHistoryChips
        container.removeAllViews()

        if (list.isEmpty()) {
            // 没有历史 -> 整个 layoutSearchHistory 隐藏
            binding.layoutSearchHistory.visibility = View.GONE
            return
        }

        // 只有输入框为空时才显示
        val keyword = binding.etSearch.text?.toString()?.trim() ?: ""
        binding.layoutSearchHistory.visibility =
            if (keyword.isEmpty()) View.VISIBLE else View.GONE

        // 动态生成 chip，按行 FlowLayout 风格（这里用 nested linear layout + 水平权重/换行）
        val maxCount = 12
        val limited = list.take(maxCount)

        // 简单做法：用 LinearLayout（horizontal）每一行固定放若干 chip
        val density = resources.displayMetrics.density
        val chipPaddingPx = (8 * density).toInt()
        val chipMarginPx = (6 * density).toInt()

        val chipBg = ResourcesCompat.getDrawable(resources, R.drawable.bg_chip, null)
        val textColor = resources.getColor(R.color.text_primary, null)

        // 每行用一个 horizontal LinearLayout，超出自动换行（简单累加文本宽度估算）
        val screenWidthPx = (resources.displayMetrics.widthPixels - 24 * density).toInt()
        var currentRow: LinearLayout? = null
        var currentRowWidthPx = 0

        for (item in limited) {
            val chip = TextView(requireContext()).apply {
                text = item.keyword
                setPadding(chipPaddingPx, chipPaddingPx / 2, chipPaddingPx, chipPaddingPx / 2)
                setTextColor(textColor)
                textSize = 13f
                background = chipBg
                // 让 chip 可点击：点击填入搜索框 + 触发搜索
                setOnClickListener {
                    Log.d(TAG, "点击历史关键词: '${item.keyword}'")
                    binding.etSearch.setText(item.keyword)
                    binding.etSearch.setSelection(item.keyword.length)
                    performSearch(item.keyword, 1)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(chipMarginPx, chipMarginPx, 0, 0)
                }
            }

            // 粗略估算 chip 宽度：文本宽度 + padding
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val chipWidth = chip.measuredWidth

            if (currentRow == null || currentRowWidthPx + chipWidth > screenWidthPx) {
                currentRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.START
                }
                container.addView(currentRow)
                currentRowWidthPx = 0
            }

            currentRow.addView(chip)
            currentRowWidthPx += chipWidth + chipMarginPx
        }
    }

    /** 点击列表项 -> DetailActivity（携带完整信息） */
    private fun openDetail(video: VideoItem) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_VIDEO_ID, video.id)
            putExtra(DetailActivity.EXTRA_VIDEO_TITLE, video.title)
            putExtra(DetailActivity.EXTRA_VIDEO_COVER, video.coverUrl)
            putExtra(DetailActivity.EXTRA_VIDEO_CATEGORY, video.category)
            putExtra(DetailActivity.EXTRA_VIDEO_RATING, video.rating)
            putExtra(DetailActivity.EXTRA_VIDEO_PLAY_URL, video.playUrl)
            putExtra(DetailActivity.EXTRA_VIDEO_YEAR, video.year)
            putExtra(DetailActivity.EXTRA_VIDEO_AREA, video.area)
            putExtra(DetailActivity.EXTRA_VIDEO_DIRECTOR, video.director)
            putExtra(DetailActivity.EXTRA_VIDEO_ACTORS, video.actors)
            putExtra(DetailActivity.EXTRA_VIDEO_DESCRIPTION, video.description)
            putExtra(DetailActivity.EXTRA_VIDEO_DETAIL_URL, video.detailUrl)
        }
        Log.d(TAG, "跳转到详情页: id=${video.id}, title=${video.title}")
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "SearchFragment onDestroyView")
        _binding = null
    }

    companion object {
        private const val TAG = "SearchFragment"
    }
}
