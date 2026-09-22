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
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentSearchBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.adapter.SearchResultAdapter
import com.hpu.mymoviestore.presentation.adapter.VideoAdapter
import com.hpu.mymoviestore.presentation.tv.QrCodeGenerator
import com.hpu.mymoviestore.presentation.tv.TvContentKeyHandler
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvInitialFocusProvider
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
 * TV 端内置 HTTP 服务器：手机扫码后在网页输入搜索内容，POST 回 TV。
 * **服务器与二维码展示严格绑定**：仅当本 Tab 为当前可见页且处于「输入态（二维码可见）」
 * 时才监听 8234 端口；切页 / 进入结果页 / 退后台即关闭端口，其余场景网页链接无法打开
 * （见 updateSearchServerState / setTvQrVisible / onResume / onPause）。
 *
 * 手机端：保持原有布局不变。
 */
class SearchFragment : Fragment(), TvInitialFocusProvider, TvContentKeyHandler {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VideoViewModel
    private lateinit var historyViewModel: SearchHistoryViewModel
    /** 电视端（横屏）结果适配器：首页式网格卡片 */
    private lateinit var tvAdapter: VideoAdapter
    /** 手机端（竖屏）结果适配器：与 9/11 基线一致的「海报 + 主演 + 简介」大卡片列表 */
    private lateinit var phoneAdapter: SearchResultAdapter
    private var currentSpanCount: Int = 3
    /** 结果页接管标记：true = 显示「搜索结果页」，false = 显示搜索输入区 */
    private var resultPageShown: Boolean = false
    /** 结果就绪后是否把焦点移到首个结果（进入结果页 / 翻页时置位） */
    private var pendingResultFocus: Boolean = false
    private var currentKeyword: String = ""
    private var currentPage: Int = 1
    private var hasPrevPage: Boolean = false
    private var hasNextPage: Boolean = false
    /** 电视端：本轮结果是否有可翻的页（决定「下键」能否掀起分页栏） */
    private var paginationAvailable: Boolean = false
    /** 电视端：分页栏当前是否已被「下键走到最后一行」掀起 */
    private var paginationRevealed: Boolean = false

    // ======================== TV 模式组件 ========================
    private var isTvMode: Boolean = false
    private var tvRoot: View? = null
    private var tvSearchServer: TvSearchServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastServerQueryTime: Long = 0L
    /**
     * 搜索页是否为「当前可见页」：ViewPager2(FragmentStateAdapter) 只把当前页提到 RESUMED，
     * 离屏预加载页最高 STARTED —— 用它区分「视图存在」与「用户真的停留在搜索页」。
     */
    private var pageResumed: Boolean = false
    /** 二维码是否处于展示态（输入态可见、结果页/输入了关键词后隐藏），同时是服务器的开关信号 */
    private var qrVisible: Boolean = false
    /** 局域网 IP 是否可用（prepareQrCode 判定）；不可用时二维码隐藏、服务器永不启动 */
    private var tvQrAvailable: Boolean = false

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

    // 结果页（整页接管）控件
    private var tvInputArea: View? = null
    private var tvResultPage: View? = null
    private var tvResultTitle: TextView? = null
    private var tvBtnBackToSearch: View? = null
    private var tvResultEmpty: TextView? = null

    /** 结果网格的 XML 底部内边距基线，供分页栏覆盖层掀起时在其上叠加高度（见 [syncGridBottomPaddingForPagination]） */
    private var resultGridBasePaddingBottom = 0

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
        tvInputArea = root.findViewById(R.id.layoutSearchInputArea)
        tvResultPage = root.findViewById(R.id.layoutSearchResultPage)
        tvResultTitle = root.findViewById(R.id.tvResultTitle)
        tvBtnBackToSearch = root.findViewById(R.id.btnBackToSearch)
        tvResultEmpty = root.findViewById(R.id.tvResultEmpty)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "SearchFragment onViewCreated (TV=$isTvMode)")

        viewModel = ViewModelProvider(this)[VideoViewModel::class.java]
        historyViewModel = ViewModelProvider(this)[SearchHistoryViewModel::class.java]
        // 结果样式按形态分流，横屏那套网格不外溢到竖屏：
        // - 电视端：首页同款卡片（图片 + 标题 + 视频源，评分位置换成来源）+ 网格
        // - 手机端：9/11 基线的大卡片列表（海报 + 标题 + 类型/年份 + 主演 + 简介）
        tvAdapter = VideoAdapter(
            onItemClick = { video -> openDetail(video) },
            sourceMode = true
        )
        phoneAdapter = SearchResultAdapter(onItemClick = { video -> openDetail(video) })

        setupViews()
        observeData()
        // 结果页返回键接管只对电视端有意义：手机端结果就列在搜索框下方，不存在「整页结果态」
        if (isTvMode) setupBackToInput()

        if (isTvMode) {
            setupTvSearch()
        } else {
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    /**
     * ViewPager2 + FragmentStateAdapter 只把「当前页」提到 RESUMED，离屏预加载页最高 STARTED。
     * 因此 onResume/onPause 精确等价于「用户进入/离开搜索页」，作为扫码服务器的第二道门控。
     */
    override fun onResume() {
        super.onResume()
        pageResumed = true
        updateSearchServerState()
    }

    override fun onPause() {
        pageResumed = false
        // 先落门控再走 super：离开搜索页（切 Tab / 进详情/播放器 / 应用退后台）立即关服务器
        updateSearchServerState()
        super.onPause()
    }

    // ======================== TV 搜索功能 ========================

    /** 电视端：导航栏按「下键」时的内容区首焦点控件（搜索框） */
    override fun tvInitialFocusView(): View? = if (isTvMode) tvEtSearch else binding.etSearch

    private fun setupTvSearch() {
        tvEmpty?.visibility = View.VISIBLE

        // 搜索按钮
        // 搜索按钮是品牌橙底，焦点环用白色变体
        tvBtnSearch?.let {
            TvFocus.applyTo(it, scale = 1.05f, ringRes = R.drawable.bg_tv_focus_ring_light)
        }
        tvClearHistory?.let { TvFocus.applyTo(it, scale = 1.05f) }
        tvEtSearch?.let { TvFocus.applyFocusableOnly(it) }

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
                    tvEmpty?.text = "输入关键词后点击搜索"
                    tvEmpty?.visibility = View.VISIBLE
                    setTvQrVisible(true)
                    showResultPage(false)
                } else {
                    tvLayoutSearchHistory?.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 准备二维码（一次性生成 URL + 位图）；服务器不再常驻，改由二维码可见性驱动
        prepareQrCode()
    }

    /**
     * 一次性准备二维码：取局域网 IP、写 URL 文案、后台生成位图。
     * **不启动服务器** —— 服务器的启停完全交给 [updateSearchServerState]（二维码可见 + 当前页 resumed）。
     * IP 取不到时标记 [tvQrAvailable]=false 并隐藏二维码，服务器在本次会话内也不会再启动。
     */
    private fun prepareQrCode() {
        val ip = TvSearchServer.getLocalIpAddress()
        if (ip == null) {
            Log.w(TAG, "无法获取局域网 IP，二维码/扫码搜索不可用")
            tvQrAvailable = false
            tvLayoutQrCode?.visibility = View.GONE
            return
        }
        tvQrAvailable = true
        val url = "http://$ip:${TvSearchServer.PORT}/search.html"
        tvQrUrl?.text = url
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = QrCodeGenerator.generate(url, 512)
            if (bitmap != null && isAdded) {
                handler.post { tvQrCode?.setImageBitmap(bitmap) }
            }
        }
        // 初始为输入态：显示二维码（若此刻已是当前可见页，顺带启动服务器）
        setTvQrVisible(true)
        Log.d(TAG, "TV 二维码就绪: $url")
    }

    /**
     * 服务器启停的唯一入口 —— 只有「用户正停留在搜索页」且「二维码正在展示」两者同时成立才运行。
     *
     * 任一条件失效就 stop()：端口随之关闭，此时手机扫码或打开旧链接都会被连接拒绝，
     * 完全满足「只有二维码出现时才能扫码/链接搜索，别的情况网页打不开」。
     * 放在离屏预加载场景下也成立：ViewPager2 会预加载搜索页视图，但非当前页不 RESUMED，
     * pageResumed=false → 服务器不启动，避免后台常驻。
     */
    private fun updateSearchServerState() {
        if (!isTvMode) return
        val shouldRun = pageResumed && qrVisible
        if (shouldRun && tvSearchServer == null) {
            tvSearchServer = TvSearchServer(requireContext()) { query ->
                // 收到手机端搜索请求，切到主线程执行搜索
                handler.post {
                    tvEtSearch?.setText(query)
                    tvEtSearch?.setSelection(query.length)
                    performSearch(query, 1)
                }
            }.also { it.start() }
            Log.d(TAG, "搜索服务器启动（二维码可见 + 当前页）")
        } else if (!shouldRun && tvSearchServer != null) {
            tvSearchServer?.stop()
            tvSearchServer = null
            Log.d(TAG, "搜索服务器停止（离开搜索页或二维码隐藏），端口 ${TvSearchServer.PORT} 已释放")
        }
    }

    /**
     * TV 横屏：右侧面板在「二维码伴侣」与「结果列表」之间切换。
     * 空闲（输入框为空 / 已重置）时显示二维码；一旦开始搜索则隐藏二维码，让出右侧给结果列表。
     * 二维码视图本身不可聚焦，结果列表项与翻页按钮可聚焦，互不冲突。
     *
     * 本方法是「二维码可见性」的唯一改动点：置位 [qrVisible] 并驱动 [updateSearchServerState]，
     * 从而把扫码/链接搜索严格绑定到二维码展示期间。
     */
    private fun setTvQrVisible(show: Boolean) {
        tvLayoutQrCode?.visibility = if (show) View.VISIBLE else View.GONE
        qrVisible = show && tvQrAvailable
        updateSearchServerState()
    }

    // ======================== 手机端 + 通用逻辑 ========================

    private fun setupViews() {
        if (isTvMode) {
            currentSpanCount = calculateSpanCount()
            tvRecyclerView?.layoutManager = GridLayoutManager(context, currentSpanCount)
            tvRecyclerView?.adapter = tvAdapter
            tvAdapter.setSpanCount(currentSpanCount)
            // 记下 XML 里的底部内边距基线：分页栏覆盖层掀起时在这段基线上加分页栏高度
            tvRecyclerView?.let { resultGridBasePaddingBottom = it.paddingBottom }
        } else {
            // 手机端：竖向大卡片列表（9/11 基线的搜索结果形态）
            binding.recyclerView.layoutManager = LinearLayoutManager(context)
            binding.recyclerView.adapter = phoneAdapter
        }

        if (!isTvMode) {
            // 手机端不做任何焦点改造（TvFocus 内部也已按形态门控，这里干脆不挂）
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
                        showResultPage(false)
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

            binding.btnBackToSearch.setOnClickListener {
                returnToInput()
            }
        } else {
            tvBtnBackToSearch?.let { TvFocus.applyTo(it, scale = 1.05f) }
            tvBtnBackToSearch?.setOnClickListener { returnToInput() }
            // 翻页按钮的聚焦能力原先写死在 layout-land/fragment_search.xml（focusable + 焦点环）。
            // 已下沉到这里：XML 只留视觉，能力由 TvFocus 在电视端赋予 —— 本目录同时服务手机横屏，
            // 写死会让触屏形态也带上橙环。
            // 用 applyFocusableOnly 而不是 applyTo：后者会重设 OnFocusChangeListener，
            // 顶掉 setupPaginationFocusWatch() 装好的「焦点离开分页栏就收起」监听。
            tvBtnPrevPage?.let { TvFocus.applyFocusableOnly(it) }
            tvBtnNextPage?.let { TvFocus.applyFocusableOnly(it) }
            // TV 端翻页
            tvBtnPrevPage?.setOnClickListener {
                // focusResult=false：翻页时焦点留在分页栏，方便连续翻页（不抢到首个结果）
                if (hasPrevPage && currentPage > 1) {
                    performSearch(currentKeyword, currentPage - 1, focusResult = false)
                }
            }
            tvBtnNextPage?.setOnClickListener {
                if (hasNextPage) performSearch(currentKeyword, currentPage + 1, focusResult = false)
            }
        }
        setupPaginationFocusWatch()
    }

    private fun observeData() {
        viewModel.searchPageResult.observe(viewLifecycleOwner) { result ->
            currentKeyword = result.keyword
            currentPage = result.page
            hasPrevPage = result.hasPrev
            hasNextPage = result.hasNext
            renderList(result.items)
            renderPagination(result.page, result.totalPages, result.hasPrev, result.hasNext)
            setResultTitle(result.keyword)
            val summary = "共 ${result.items.size} 条 · 第 ${result.page} 页"
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
                val empty = if (isTvMode) tvResultEmpty else binding.tvResultEmpty
                empty?.text = "搜索中..."
                empty?.visibility = View.VISIBLE
                // 加载中不动分页栏：电视端保留占位/掀起态，避免每次翻页网格上下跳
                if (!isTvMode) binding.layoutPagination.visibility = View.GONE
                setTvQrVisible(false)
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

    /** 结果提交：按形态写入对应适配器（横屏网格 / 竖屏列表） */
    private fun submitResults(list: List<VideoItem>) {
        if (isTvMode) tvAdapter.submitList(list) else phoneAdapter.submitList(list)
    }

    /** 当前形态下的结果条目数 */
    private fun resultItemCount(): Int =
        if (isTvMode) tvAdapter.itemCount else phoneAdapter.itemCount

    private fun renderList(list: List<VideoItem>) {
        // 结果渲染在「结果页」容器上：电视端整页接管（标题 = 搜索内容，首页式网格），
        // 手机端只是把提示语换成结果列表，搜索框与历史仍留在上方（同 9/11 基线）
        showResultPage(true)
        if (list.isEmpty()) {
            val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
            rv?.visibility = View.GONE
            val empty = if (isTvMode) tvResultEmpty else binding.tvResultEmpty
            empty?.text = "没有搜索到相关影片"
            empty?.visibility = View.VISIBLE
        } else {
            val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
            rv?.visibility = View.VISIBLE
            val empty = if (isTvMode) tvResultEmpty else binding.tvResultEmpty
            empty?.visibility = View.GONE
            submitResults(list)
            rv?.scrollToPosition(0)
            if (isTvMode && pendingResultFocus) {
                pendingResultFocus = false
                rv?.post { rv?.let { target -> TvFocus.focusFirstItem(target) } }
            }
        }
    }

    private fun renderPagination(page: Int, totalPages: Int, hasPrev: Boolean, hasNext: Boolean) {
        val showPagination = hasPrev || hasNext || totalPages > 1
        paginationAvailable = showPagination
        if (isTvMode) {
            // 电视端不再常驻底部：默认 INVISIBLE 占位，只有「下键走到网格最后一行」才掀起
            tvPageInfo?.text = "第 $page / $totalPages 页"
            tvBtnPrevPage?.isEnabled = hasPrev && page > 1
            tvBtnNextPage?.isEnabled = hasNext
            tvBtnPrevPage?.alpha = if (tvBtnPrevPage?.isEnabled == true) 1f else 0.45f
            tvBtnNextPage?.alpha = if (tvBtnNextPage?.isEnabled == true) 1f else 0.45f
            applyPaginationVisibility()
            restorePaginationFocus()
        } else {
            binding.layoutPagination.visibility = if (showPagination) View.VISIBLE else View.GONE
            binding.tvPageInfo.text = "第 $page / $totalPages 页"
            binding.btnPrevPage.isEnabled = hasPrev && page > 1
            binding.btnNextPage.isEnabled = hasNext
            binding.btnPrevPage.alpha = if (binding.btnPrevPage.isEnabled) 1f else 0.45f
            binding.btnNextPage.alpha = if (binding.btnNextPage.isEnabled) 1f else 0.45f
        }
    }

    // ==================== 电视端分页栏：下键走到最后一行才掀起 ====================

    /**
     * 电视端分页栏显隐。
     *
     * 为何未掀起时用 INVISIBLE 而不是 GONE：结果网格是 `weight=1`，分页栏一旦 GONE，
     * 网格高度会跟着变，掀起/收起时整片网格上下跳；INVISIBLE 保留占位，分页栏「原地浮现」。
     * 同时未掀起时把子控件设为 FOCUS_BLOCK_DESCENDANTS —— 不可见就绝不能可聚焦
     * （本项目已多次被「看不见却可聚焦」坑过）。
     */
    private fun applyPaginationVisibility() {
        // 整段逻辑只服务遥控器焦点导航，手机端不参与：显式收口。
        // （tvLayoutPagination 在竖屏布局里不存在，原来靠 `?: return` 间接兜住；
        //  但下面写的是 descendantFocusability 这种「正向」焦点能力，不该依赖间接推断。）
        if (!isTvMode) return
        // descendantFocusability 是 ViewGroup 的属性，这里按 ViewGroup 取用
        val bar = tvLayoutPagination as? ViewGroup ?: return
        if (!paginationAvailable) {
            val wasRevealed = paginationRevealed
            paginationRevealed = false
            bar.visibility = View.GONE
            bar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (wasRevealed) handOffFocusToResult()      // 掀起态下分页栏失效：焦点不能凭空消失
            return
        }
        bar.visibility = if (paginationRevealed) View.VISIBLE else View.INVISIBLE
        bar.descendantFocusability = if (paginationRevealed) {
            ViewGroup.FOCUS_BEFORE_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    /** 焦点离开分页栏就收起。延后一拍判断，避免 prev -> next 的内部移动被误判成「离开」 */
    private fun setupPaginationFocusWatch() {
        if (!isTvMode) return
        val bar = tvLayoutPagination ?: return
        val watcher = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@OnFocusChangeListener
            bar.post {
                if (isAdded && paginationRevealed && !bar.hasFocus()) collapsePagination()
            }
        }
        tvBtnPrevPage?.onFocusChangeListener = watcher
        tvBtnNextPage?.onFocusChangeListener = watcher
    }

    /** 掀起分页栏，并把焦点交给可用的翻页按钮（「下一页」优先，禁用时自动落到「上一页」） */
    private fun revealPagination(): Boolean {
        if (!isTvMode || !paginationAvailable) return false
        val target = when {
            tvBtnNextPage?.isEnabled == true -> tvBtnNextPage
            tvBtnPrevPage?.isEnabled == true -> tvBtnPrevPage
            else -> null
        } ?: return false
        paginationRevealed = true
        applyPaginationVisibility()
        if (!target.requestFocus()) {
            target.post { if (isAdded) target.requestFocus() }
        }
        return true
    }

    /** 收起分页栏（回输入态 / 无可用页时调用） */
    private fun collapsePagination() {
        if (!isTvMode) return
        paginationRevealed = false
        applyPaginationVisibility()
    }

    /**
     * 掀起态下补回焦点：`View.setEnabled(false)`（例如翻到最后一页后「下一页」被禁用）
     * 会**直接清掉焦点**，不补就又是「焦点不见了」。这里在按钮变更后把焦点交给仍可用的一侧。
     */
    private fun restorePaginationFocus() {
        if (!isTvMode || !paginationRevealed) return
        val prev = tvBtnPrevPage ?: return
        val next = tvBtnNextPage ?: return
        if (prev.hasFocus() || next.hasFocus()) return
        val target = when {
            next.isEnabled -> next
            prev.isEnabled -> prev
            else -> null
        } ?: return
        target.requestFocus()
    }

    /** 分页栏收起/失效时把焦点交回结果网格，避免焦点凭空中断 */
    private fun handOffFocusToResult() {
        val rv = tvRecyclerView
        if (rv == null || resultItemCount() <= 0) {
            tvBtnBackToSearch?.requestFocus()
            return
        }
        rv.scrollToPosition(0)
        TvFocus.focusFirstItem(rv)
    }

    /** 焦点所在视图在结果网格里的 adapter 位置（焦点可能落在 item 内部子视图上，逐层上溯） */
    private fun adapterPositionOf(rv: androidx.recyclerview.widget.RecyclerView, focused: View): Int {
        var v: View? = focused
        while (v != null) {
            if (v.parent === rv) return rv.getChildAdapterPosition(v)
            v = v.parent as? View
        }
        return androidx.recyclerview.widget.RecyclerView.NO_POSITION
    }

    /**
     * 电视端方向键兜底接管（MainActivity 在内容区方向键里优先询问本方法）。
     *
     * 只接管一种情况：**结果网格最后一行按下键** —— 网格下面就是分页栏，但 RecyclerView 的
     * `focusSearch()` 只在自身子树里找候选，永远找不到网格之外的翻页按钮，所以「按了没反应」。
     * 这里改成：掀起分页栏 + 焦点交给翻页按钮。其余任何位置一律返回 false，交回系统原有逻辑。
     */
    override fun onContentDirectionKey(direction: Int): Boolean {
        if (direction != View.FOCUS_DOWN) return false
        if (!isTvMode || !resultPageShown || !paginationAvailable || paginationRevealed) return false
        val rv = tvRecyclerView ?: return false
        val focused = activity?.currentFocus ?: return false
        val position = adapterPositionOf(rv, focused)
        val itemCount = resultItemCount()
        if (position < 0 || itemCount <= 0) return false
        val span = currentSpanCount.coerceAtLeast(1)
        val lastRowStart = itemCount - 1 - ((itemCount - 1) % span)
        if (position < lastRowStart) return false     // 还没到最后一行 -> 交回系统正常移动
        return revealPagination()
    }

    /**
     * @param focusResult 结果就绪后是否把焦点移到首个结果。
     *        从分页栏翻页时传 false —— 焦点留在翻页按钮上，方便连续翻页。
     */
    private fun performSearch(keyword: String, page: Int, focusResult: Boolean = true) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return
        currentKeyword = cleanKeyword
        currentPage = page.coerceAtLeast(1)

        // 立即切到结果页：标题显示搜索内容，正文显示「搜索中…」/结果
        setResultTitle(cleanKeyword)
        showResultPage(true)
        pendingResultFocus = focusResult

        val hasPermission = MovieApplication.get().permissionConfigRepository.checkSearchPermissionFast()
        if (!hasPermission) {
            Log.w(TAG, "搜索权限检查未通过")
            val empty = if (isTvMode) tvResultEmpty else binding.tvResultEmpty
            empty?.text = "搜索功能暂不可用"
            empty?.visibility = View.VISIBLE
            val rv = if (isTvMode) tvRecyclerView else binding.recyclerView
            rv?.visibility = View.GONE
            if (isTvMode) collapsePagination()
            else binding.layoutPagination.visibility = View.GONE
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
        setTvQrVisible(false)
        hideKeyboard()
        viewModel.searchVideosPage(cleanKeyword, currentPage)
    }

    private fun hideKeyboard() {
        // 电视端多数没有软键盘（收起键盘本身无意义），且部分盒子 INPUT_METHOD_SERVICE 可能缺失，
        // 因此安全转换 + 空判断，避免「收键盘」这个收尾动作反而把应用搞崩。
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
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
            paginationAvailable = false
            collapsePagination()
            tvEtSearch?.setText("")
            submitResults(emptyList())
            tvRecyclerView?.visibility = View.GONE
            tvSearchSummary?.visibility = View.GONE
            tvResultEmpty?.visibility = View.GONE
            showResultPage(false)
            tvLayoutSearchHistory?.visibility = View.VISIBLE
            setTvQrVisible(true)
        } else if (_binding != null) {
            binding.etSearch.setText("")
            submitResults(emptyList())
            binding.recyclerView.visibility = View.GONE
            binding.tvSearchSummary.visibility = View.GONE
            binding.layoutPagination.visibility = View.GONE
            binding.tvResultEmpty.visibility = View.GONE
            showResultPage(false)
            binding.layoutSearchHistory.visibility = View.VISIBLE
        }
    }

    fun isShowingSearchResult(): Boolean {
        if (resultPageShown) return true
        return currentKeyword.isNotBlank() ||
            (if (isTvMode) tvRecyclerView?.visibility else binding.recyclerView?.visibility) == View.VISIBLE ||
            (if (isTvMode) tvLayoutPagination?.visibility else binding.layoutPagination?.visibility) == View.VISIBLE
    }

    /** 结果页标题 = 搜索的内容（关键词），风格与首页标题一致 */
    private fun setResultTitle(keyword: String) {
        val title = if (isTvMode) tvResultTitle else binding.tvResultTitle
        title?.text = keyword
    }

    /**
     * 结果页显隐 —— 只有电视端是「整页切换」：
     * - 电视（横屏）：true = 整页结果页（标题 + 首页式网格 + 分页），搜索框 / 历史 / 二维码全隐
     * - 手机（竖屏）：布局本身就是「搜索区 + 结果区」上下常驻（与 9/11 基线一致），
     *   这里只收起「输入关键词后点击搜索」这个提示，不隐藏搜索框。
     *   结果页头部（关键词标题 + 重新搜索）是电视端整页形态的产物，竖屏布局里已静态隐藏。
     */
    private fun showResultPage(show: Boolean) {
        if (!isTvMode) {
            binding.tvEmpty.visibility = if (show) View.GONE else View.VISIBLE
            resultPageShown = false      // 不接管返回键，保持基线：退出交给 MainActivity
            return
        }
        resultPageShown = show
        tvInputArea?.visibility = if (show) View.GONE else View.VISIBLE
        tvResultPage?.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) collapsePagination()          // 结果页不在了，分页栏状态一并清掉
        // 二维码伴侣只在输入态出现，结果页要让位给网格
        setTvQrVisible(!show)
    }

    /** 结果页「重新搜索」：回到输入态，电视端把焦点放回搜索框 */
    private fun returnToInput() {
        showResultPage(false)
        if (isTvMode) tvEtSearch?.requestFocus()
    }

    /** 遥控器/系统返回键：在结果页时先回输入态，而不是直接退出应用 */
    private fun setupBackToInput() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (resultPageShown) {
                        returnToInput()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    /** 按首页同一规则计算列数（卡片最小 120dp，2~8 列） */
    private fun calculateSpanCount(): Int {
        val dm = resources.displayMetrics
        val screenWidthDp = dm.widthPixels / dm.density
        return (screenWidthDp / 120f).toInt().coerceIn(2, 8)
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
        // 注意：ResourcesCompat 返回的是「共享 ConstantState」的 Drawable，同一个实例设给 12 个
        // TextView 会共用 bounds/state（只有最后一个 View 持有 callback），背景表现不可预期。
        // 每个 chip 用 newDrawable().mutate() 拿一份独立副本。
        val chipBgProto = ResourcesCompat.getDrawable(resources, R.drawable.bg_chip, null)
        val textColor = resources.getColor(R.color.text_primary, null)
        // 换行预算必须用「容器真实宽度」，不能用整屏宽度：横屏（TV）左栏只占 3:2 分栏的 3/5，
        // 按整屏宽换行会让一行 chip 溢出卡片、被祖先裁掉 —— 看不见却仍然可聚焦，
        // 按方向键时焦点会落到这些看不见的 chip 上（表现为「焦点消失且找不回」）。
        var rowWidthPx = container.width - container.paddingStart - container.paddingEnd
        if (rowWidthPx <= 0) {
            // 尚未完成布局：先用整屏宽估一个，布局完成后再按真实宽度重算一次
            rowWidthPx = (resources.displayMetrics.widthPixels - 24 * density).toInt()
            container.post {
                if (isAdded && container.width > 0) renderHistoryChips(list)
            }
        }
        var currentRow: LinearLayout? = null
        var currentRowWidthPx = 0

        for (item in limited) {
            val chip = TextView(requireContext()).apply {
                text = item.keyword
                setPadding(chipPaddingPx, chipPaddingPx / 2, chipPaddingPx, chipPaddingPx / 2)
                setTextColor(textColor)
                textSize = 13f
                background = chipBgProto?.constantState?.newDrawable()?.mutate()
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
            if (currentRow == null || currentRowWidthPx + chipWidth > rowWidthPx) {
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
