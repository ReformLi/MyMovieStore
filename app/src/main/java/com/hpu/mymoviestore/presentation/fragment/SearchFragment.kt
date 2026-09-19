package com.hpu.mymoviestore.presentation.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentSearchBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.adapter.SearchResultAdapter
import com.hpu.mymoviestore.presentation.tv.QrCodeGenerator
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvSearchServer
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.viewmodel.SearchHistoryViewModel
import com.hpu.mymoviestore.presentation.viewmodel.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 搜索页 Fragment
 *
 * TV 模式：左右分栏布局，左侧搜索框 + QR 码（手机扫码搜索）+ 搜索历史，右侧结果列表。
 * TV 端启动内置 HTTP 服务器，手机扫码后在网页输入搜索内容，POST 回 TV。
 *
 * 手机端：保持原有布局不变。
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

    // ======================== TV 模式组件 ========================
    private var isTvMode: Boolean = false
    private var tvRoot: View? = null
    private var tvSearchServer: TvSearchServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastServerQueryTime: Long = 0L

    // TV 模式下的 View 引用（不用 ViewBinding，因为 TV 布局不同）
    private var tvEtSearch: EditText? = null
    private var tvBtnSearch: View? = null
    private var tvQrCode: ImageView? = null
    private var tvQrUrl: TextView? = null
    private var tvLayoutQrCode: View? = null
    private var tvLayoutSearchHistory: View? = null
    private var tvClearHistory: View? = null
    private var tvContainerHistoryChips: LinearLayout? = null
    private var tvSearchSummary: TextView? = null
    private var tvRecyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var tvEmpty: TextView? = null
    private var tvLayoutPagination: View? = null
    private var tvBtnPrevPage: View? = null
    private var tvBtnNextPage: View? = null
    private var tvPageInfo: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isTvMode = TvUiSupport.isTelevision(requireContext())
        return if (isTvMode) {
            createTvLayout(inflater, container)
        } else {
            _binding = FragmentSearchBinding.inflate(inflater, container, false)
            binding.root
        }
    }

    private fun createTvLayout(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = inflater.inflate(R.layout.fragment_search, container, false)
        tvRoot = root
        tvEtSearch = root.findViewById(R.id.etSearch)
        tvBtnSearch = root.findViewById(R.id.btnSearch)
        tvQrCode = root.findViewById(R.id.ivQrCode)
        tvQrUrl = root.findViewById(R.id.tvQrUrl)
        tvLayoutQrCode = root.findViewById(R.id.layoutQrCode)
        tvLayoutSearchHistory = root.findViewById(R.id.layoutSearchHistory)
        tvClearHistory = root.findViewById(R.id.tvClearHistory)
        tvContainerHistoryChips = root.findViewById(R.id.containerHistoryChips)
        tvSearchSummary = root.findViewById(R.id.tvSearchSummary)
        tvRecyclerView = root.findViewById(R.id.recyclerView)
        tvEmpty = root.findViewById(R.id.tvEmpty)
        tvLayoutPagination = root.findViewById(R.id.layoutPagination)
        tvBtnPrevPage = root.findViewById(R.id.btnPrevPage)
        tvBtnNextPage = root.findViewById(R.id.btnNextPage)
        tvPageInfo = root.findViewById(R.id.tvPageInfo)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "SearchFragment onViewCreated (TV=$isTvMode)")

        viewModel = ViewModelProvider(this)[VideoViewModel::class.java]
        historyViewModel = ViewModelProvider(this)[SearchHistoryViewModel::class.java]
        adapter = SearchResultAdapter { video -> openDetail(video) }

        setupViews()
        observeData()

        if (isTvMode) {
            setupTvSearch()
        } else {
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    // ======================== TV 搜索功能 ========================

    private fun setupTvSearch() {
        tvEmpty?.visibility = View.VISIBLE

        // 搜索按钮
        tvBtnSearch?.let { TvFocus.applyTo(it, scale = 1.05f) }
        tvClearHistory?.let { TvFocus.applyTo(it, scale = 1.05f) }

        tvBtnSearch?.setOnClickListener {
            val keyword = tvEtSearch?.text?.toString()?.trim().orEmpty()
            if (keyword.isNotEmpty()) {
                performSearch(keyword, 1)
            }
        }

        tvEtSearch?.setOnEditorActionListener { _, actionId, _ ->
            val keyword = tvEtSearch?.text?.toString()?.trim() ?: ""
            if (actionId == EditorInfo.IME_ACTION_SEARCH && keyword.isNotEmpty()) {
                performSearch(keyword, 1)
                true
            } else false
        }

        tvClearHistory?.setOnClickListener {
            historyViewModel.clearAll()
        }

        // 文本变化监听
        tvEtSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s?.toString()?.trim() ?: ""
                if (keyword.isEmpty()) {
                    tvLayoutSearchHistory?.visibility = View.VISIBLE
                    tvSearchSummary?.visibility = View.GONE
                    tvLayoutPagination?.visibility = View.GONE
                    tvRecyclerView?.visibility = View.GONE
                    tvEmpty?.text = "输入关键词后搜索，或用手机扫码"
                    tvEmpty?.visibility = View.VISIBLE
                } else {
                    tvLayoutSearchHistory?.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 启动 HTTP 服务器 + 显示 QR 码
        startTvSearchServer()
    }

    private fun startTvSearchServer() {
        val ip = TvSearchServer.getLocalIpAddress()
        if (ip == null) {
            Log.w(TAG, "无法获取局域网 IP，跳过搜索服务器")
            return
        }

        tvSearchServer = TvSearchServer(requireContext()) { query ->
            // 收到手机端搜索请求，切到主线程执行搜索
            handler.post {
                tvEtSearch?.setText(query)
                tvEtSearch?.setSelection(query.length)
                performSearch(query, 1)
            }
        }.also { it.start() }

        // 显示 QR 码
        val url = "http://$ip:${tvSearchServer!!.port}/search.html"
        tvQrUrl?.text = url
        tvLayoutQrCode?.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = QrCodeGenerator.generate(url, 512)
            if (bitmap != null && isAdded) {
                handler.post { tvQrCode?.setImageBitmap(bitmap) }
            }
        }

        Log.d(TAG, "TV 搜索服务器启动: $url")
    }

    // ======================== 手机端 + 通用逻辑 ========================

    private fun setupViews() {
        val recyclerView = if (isTvMode) tvRecyclerView else binding.recyclerView
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter

        if (!isTvMode) {
            // 手机端 TV 适配
            TvFocus.applyTo(binding.btnSearch, scale = 1.05f)
            TvFocus.applyTo(binding.tvClearHistory, scale = 1.05f)
            TvFocus.applyTo(binding.btnPrevPage, scale = 1.05f)
            TvFocus.applyTo(binding.btnNextPage, scale = 1.05f)

            binding.etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val keyword = s?.toString()?.trim() ?: ""
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

            binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
                val keyword = binding.etSearch.text?.toString()?.trim() ?: ""
                if (actionId == EditorInfo.IME_ACTION_SEARCH && keyword.isNotEmpty()) {
                    performSearch(keyword, 1)
                    true
                } else false
            }

            binding.btnSearch.setOnClickListener {
                val keyword = binding.etSearch.text?.toString()?.trim().orEmpty()
                if (keyword.isNotEmpty()) performSearch(keyword, 1)
            }

            binding.btnPrevPage.setOnClickListener {
                if (hasPrevPage && currentPage > 1) performSearch(currentKeyword, currentPage - 1)
            }

            binding.btnNextPage.setOnClickListener {
                if (hasNextPage) performSearch(currentKeyword, currentPage + 1)
            }

            binding.tvClearHistory.setOnClickListener {
                historyViewModel.clearAll()
            }
        } else {
            // TV 端翻页
            tvBtnPrevPage?.setOnClickListener {
                if (hasPrevPage && currentPage > 1) performSearch(currentKeyword, currentPage - 1)
            }
            tvBtnNextPage?.setOnClickListener {
                if (hasNextPage) performSearch(currentKeyword, currentPage + 1)
            }
        }
    }

    private fun observeData() {
        viewModel.searchPageResult.observe(viewLifecycleOwner) { result ->
            currentKeyword = result.keyword
            currentPage = result.page
            hasPrevPage = result.hasPrev
            hasNextPage = result.hasNext
            renderList(result.items)
            renderPagination(result.page, result.totalPages, result.hasPrev, result.hasNext)
            val summary = "${result.keyword}搜索结果：第 ${result.page} 页，共 ${result.items.size} 条"
            if (isTvMode) {
                tvSearchSummary?.text = summary
                tvSearchSummary?.visibility = View.VISIBLE
            } else {
                binding.tvSearchSummary.text = summary
                binding.tvSearchSummary.visibility = View.VISIBLE
            }
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            if (loading == true) {
                val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
                rv?.visibility = View.GONE
                val empty = if (isTvMode) tvEmpty else binding.tvEmpty
                empty?.text = "搜索中..."
                empty?.visibility = View.VISIBLE
                val pagination = if (isTvMode) tvLayoutPagination else binding.layoutPagination
                pagination?.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Log.w(TAG, "搜索加载错误: ${error.userFacingMessage}")
                Toast.makeText(requireContext(), error.userFacingMessage, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        historyViewModel.searchHistory.observe(viewLifecycleOwner) { historyList ->
            renderHistoryChips(historyList.orEmpty())
        }
    }

    private fun renderList(list: List<VideoItem>) {
        if (list.isEmpty()) {
            val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
            rv?.visibility = View.GONE
            val empty = if (isTvMode) tvEmpty else binding.tvEmpty
            empty?.text = "没有搜索到相关影片"
            empty?.visibility = View.VISIBLE
        } else {
            val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
            rv?.visibility = View.VISIBLE
            val empty = if (isTvMode) tvEmpty else binding.tvEmpty
            empty?.visibility = View.GONE
            adapter.submitList(list)
        }
    }

    private fun renderPagination(page: Int, totalPages: Int, hasPrev: Boolean, hasNext: Boolean) {
        val showPagination = hasPrev || hasNext || totalPages > 1
        if (isTvMode) {
            tvLayoutPagination?.visibility = if (showPagination) View.VISIBLE else View.GONE
            tvPageInfo?.text = "第 $page / $totalPages 页"
            tvBtnPrevPage?.isEnabled = hasPrev && page > 1
            tvBtnNextPage?.isEnabled = hasNext
            tvBtnPrevPage?.alpha = if (tvBtnPrevPage?.isEnabled == true) 1f else 0.45f
            tvBtnNextPage?.alpha = if (tvBtnNextPage?.isEnabled == true) 1f else 0.45f
        } else {
            binding.layoutPagination.visibility = if (showPagination) View.VISIBLE else View.GONE
            binding.tvPageInfo.text = "第 $page / $totalPages 页"
            binding.btnPrevPage.isEnabled = hasPrev && page > 1
            binding.btnNextPage.isEnabled = hasNext
            binding.btnPrevPage.alpha = if (binding.btnPrevPage.isEnabled) 1f else 0.45f
            binding.btnNextPage.alpha = if (binding.btnNextPage.isEnabled) 1f else 0.45f
        }
    }

    private fun performSearch(keyword: String, page: Int) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return
        currentKeyword = cleanKeyword
        currentPage = page.coerceAtLeast(1)

        val hasPermission = MovieApplication.get().permissionConfigRepository.checkSearchPermissionFast()
        if (!hasPermission) {
            Log.w(TAG, "搜索权限检查未通过")
            val empty = if (isTvMode) tvEmpty else binding.tvEmpty
            empty?.text = "搜索功能暂不可用"
            empty?.visibility = View.VISIBLE
            val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
            rv?.visibility = View.GONE
            val pagination = if (isTvMode) tvLayoutPagination else binding.layoutPagination
            pagination?.visibility = View.GONE
            Toast.makeText(requireContext(), "搜索功能暂不可用", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            MovieApplication.get().permissionConfigRepository.fetchPermissionAsync()
        }

        historyViewModel.addKeyword(cleanKeyword)
        val historyLayout = if (isTvMode) tvLayoutSearchHistory else binding.layoutSearchHistory
        historyLayout?.visibility = View.GONE
        val summary = if (isTvMode) tvSearchSummary else binding.tvSearchSummary
        summary?.text = "正在搜索\"$cleanKeyword\"..."
        summary?.visibility = View.VISIBLE
        hideKeyboard()
        viewModel.searchVideosPage(cleanKeyword, currentPage)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val view = activity?.currentFocus ?: (if (isTvMode) tvEtSearch else binding.etSearch)
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    fun searchFromExternal(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return
        if (isTvMode) {
            tvEtSearch?.setText(cleanKeyword)
            tvEtSearch?.setSelection(cleanKeyword.length)
            tvRecyclerView?.scrollToPosition(0)
        } else if (_binding != null) {
            binding.etSearch.setText(cleanKeyword)
            binding.etSearch.setSelection(cleanKeyword.length)
            binding.recyclerView.scrollToPosition(0)
        }
        performSearch(cleanKeyword, 1)
    }

    fun resetToInitialState() {
        currentKeyword = ""
        currentPage = 1
        hasPrevPage = false
        hasNextPage = false

        if (isTvMode) {
            tvEtSearch?.setText("")
            adapter.submitList(emptyList())
            tvRecyclerView?.visibility = View.GONE
            tvSearchSummary?.visibility = View.GONE
            tvLayoutPagination?.visibility = View.GONE
            tvEmpty?.text = "输入关键词后搜索，或用手机扫码"
            tvEmpty?.visibility = View.VISIBLE
            tvLayoutSearchHistory?.visibility = View.VISIBLE
        } else if (_binding != null) {
            binding.etSearch.setText("")
            adapter.submitList(emptyList())
            binding.recyclerView.visibility = View.GONE
            binding.tvSearchSummary.visibility = View.GONE
            binding.layoutPagination.visibility = View.GONE
            binding.tvEmpty.text = "输入关键词后点击搜索"
            binding.tvEmpty.visibility = View.VISIBLE
            binding.layoutSearchHistory.visibility = View.VISIBLE
        }
    }

    fun isShowingSearchResult(): Boolean {
        return currentKeyword.isNotBlank() ||
            (if (isTvMode) tvRecyclerView?.visibility else binding.recyclerView?.visibility) == View.VISIBLE ||
            (if (isTvMode) tvLayoutPagination?.visibility else binding.layoutPagination?.visibility) == View.VISIBLE
    }

    private fun renderHistoryChips(list: List<SearchHistoryEntity>) {
        val container = if (isTvMode) tvContainerHistoryChips else binding.containerHistoryChips
        container?.removeAllViews() ?: return

        if (list.isEmpty()) {
            val historyLayout = if (isTvMode) tvLayoutSearchHistory else binding.layoutSearchHistory
            historyLayout?.visibility = View.GONE
            return
        }

        val etSearch = if (isTvMode) tvEtSearch else binding.etSearch
        val keyword = etSearch?.text?.toString()?.trim() ?: ""
        val historyLayout = if (isTvMode) tvLayoutSearchHistory else binding.layoutSearchHistory
        historyLayout?.visibility = if (keyword.isEmpty()) View.VISIBLE else View.GONE

        val maxCount = 12
        val limited = list.take(maxCount)
        val density = resources.displayMetrics.density
        val chipPaddingPx = (8 * density).toInt()
        val chipMarginPx = (6 * density).toInt()
        val chipBg = ResourcesCompat.getDrawable(resources, R.drawable.bg_chip, null)
        val textColor = resources.getColor(R.color.text_primary, null)
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
                setOnClickListener {
                    etSearch?.setText(item.keyword)
                    etSearch?.setSelection(item.keyword.length)
                    performSearch(item.keyword, 1)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(chipMarginPx, chipMarginPx, 0, 0) }
            }
            TvFocus.applyTo(chip, scale = 1.06f)
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
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "SearchFragment onDestroyView")
        tvSearchServer?.stop()
        tvSearchServer = null
        _binding = null
        tvRoot = null
    }

    companion object {
        private const val TAG = "SearchFragment"
    }
}
