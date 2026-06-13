package com.hpu.mymoviestore.presentation.fragment

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentSearchBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.adapter.VideoAdapter
import com.hpu.mymoviestore.presentation.viewmodel.SearchHistoryViewModel
import com.hpu.mymoviestore.presentation.viewmodel.VideoViewModel

/**
 * 搜索页 Fragment
 *
 * 数据源：
 *   - 视频列表：VideoRepository（JSON 挡板，内存缓存）
 *   - 搜索历史：SearchHistoryRepository（Room，持久化）
 *
 * 流程：
 * 1. 首次进入 → 展示搜索历史（若有）+ 全量视频列表（便于浏览）
 * 2. 输入关键字（自动过滤）→ 搜索标题/演员/导演/简介
 * 3. 点击历史关键词 → 填入搜索框 + 立即搜索 + 更新历史（刷新搜索次数 & 时间）
 * 4. 点击"清空历史" → 删除全部历史
 * 5. 点击视频列表项 → DetailActivity（携带完整 VideoItem 信息）
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VideoViewModel
    private lateinit var historyViewModel: SearchHistoryViewModel
    private lateinit var adapter: VideoAdapter

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
        adapter = VideoAdapter { video -> openDetail(video) }

        setupViews()
        observeData()

        // 首次加载：全量视频列表
        viewModel.loadAllVideos()
    }

    private fun setupViews() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        Log.d(TAG, "RecyclerView + VideoAdapter 初始化完成")

        // 文本变化：关键字为空 → 显示搜索历史 + 全量列表；非空 → 隐藏历史 + 搜索
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s?.toString()?.trim() ?: ""
                Log.d(TAG, "搜索框变化: '$keyword'")
                if (keyword.isEmpty()) {
                    viewModel.loadAllVideos()
                    // 关键字为空，显示搜索历史
                    binding.layoutSearchHistory.visibility = View.VISIBLE
                } else {
                    viewModel.searchVideos(keyword)
                    // 输入内容后，隐藏搜索历史（避免干扰搜索结果）
                    binding.layoutSearchHistory.visibility = View.GONE
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // 输入法的"搜索"按钮：提交搜索 → 写入搜索历史
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            val keyword = binding.etSearch.text?.toString()?.trim() ?: ""
            if (keyword.isNotEmpty()) {
                Log.d(TAG, "用户提交搜索: '$keyword' → 写入搜索历史")
                historyViewModel.addKeyword(keyword)
            }
            false
        }

        // 点击"清空历史"
        binding.tvClearHistory.setOnClickListener {
            Log.d(TAG, "点击清空搜索历史")
            historyViewModel.clearAll()
        }
    }

    /**
     * 观察：
     *  - 视频搜索结果：allVideos / searchVideos → 渲染列表
     *  - 搜索历史：searchHistory → 动态生成 Chip 并展示
     */
    private fun observeData() {
        viewModel.allVideos.observe(viewLifecycleOwner) { list ->
            Log.d(TAG, "allVideos 观察到变化: ${list?.size ?: 0} 条")
            renderList(list.orEmpty())
        }
        viewModel.searchVideos.observe(viewLifecycleOwner) { list ->
            Log.d(TAG, "searchVideos 观察到变化: ${list?.size ?: 0} 条")
            renderList(list.orEmpty())
        }

        // 搜索历史变化：重新构建 Chip 列表
        historyViewModel.searchHistory.observe(viewLifecycleOwner) { historyList ->
            renderHistoryChips(historyList.orEmpty())
        }
    }

    /** 视频列表为空 → 显示空状态；否则显示列表 */
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
            // 没有历史 → 整个 layoutSearchHistory 隐藏
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
                    // 写入历史（刷新搜索次数 & 时间）
                    historyViewModel.addKeyword(item.keyword)
                    // 立即搜索
                    viewModel.searchVideos(item.keyword)
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

    /** 点击列表项 → DetailActivity（携带完整信息） */
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
