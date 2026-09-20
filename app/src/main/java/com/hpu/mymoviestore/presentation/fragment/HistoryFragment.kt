package com.hpu.mymoviestore.presentation.fragment

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import com.hpu.mymoviestore.databinding.FragmentHistoryBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.adapter.HistoryAdapter
import com.hpu.mymoviestore.presentation.dialog.ConfirmDialog
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.viewmodel.HistoryViewModel

/**
 * 播放历史 Fragment
 *
 * 数据源：Room play_history 表（LiveData，自动刷新）
 *
 * 功能：
 * 1. 展示全部历史记录（按时间倒序）
 * 2. 点击某条历史 → DetailActivity（携带 videoId/title/coverUrl/category/playUrl）
 *    - 若 playUrl 非空，详情页可直接播放
 *    - 若 playUrl 为空，详情页从 JSON 挡板回查补全
 * 3. 右上角（或底部）「清空历史」按钮
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: HistoryViewModel
    private lateinit var adapter: HistoryAdapter

    /** 电视/横屏网格形态（与首页一致：网格卡片）；手机竖屏仍是横向行卡片 */
    private var isTv = false
    private var currentSpanCount = 3

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "HistoryFragment onViewCreated")

        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        isTv = TvUiSupport.isTelevision(requireContext())
        adapter = HistoryAdapter(gridMode = isTv) { history -> openDetail(history) }

        setupViews()
        observeData()
    }

    private fun setupViews() {
        if (isTv) {
            // 网格形态：列数算法与首页完全一致，视觉上「和首页一样」
            currentSpanCount = calculateSpanCount()
            binding.recyclerView.layoutManager = GridLayoutManager(context, currentSpanCount)
            adapter.setSpanCount(currentSpanCount)
        } else {
            binding.recyclerView.layoutManager = LinearLayoutManager(context)
        }
        binding.recyclerView.adapter = adapter
        Log.d(TAG, "RecyclerView + HistoryAdapter 初始化完成: isTv=$isTv, span=$currentSpanCount")

        // 点击「清空历史」弹出确认对话框，确认后通过 ViewModel 调用 Room 删除
        binding.tvClear.setOnClickListener {
            Log.d(TAG, "点击清空历史，弹出确认对话框")
            ConfirmDialog.show(
                context = requireContext(),
                title = "清空历史记录",
                message = "确定要清空所有观看历史吗？",
                positiveText = "确定",
                negativeText = "取消"
            ) {
                Log.d(TAG, "用户确认清空历史")
                viewModel.clearAllHistory()
            }
        }

        // TV 适配：「清空历史」可遥控器聚焦（无触摸屏时否则无法触达）
        TvFocus.applyTo(binding.tvClear, scale = 1.06f)
    }

    /** 观察 Room LiveData，自动刷新列表 */
    private fun observeData() {
        viewModel.getAllHistory().observe(viewLifecycleOwner) { history ->
            Log.d(TAG, "观察到历史数据变化: ${history?.size ?: 0} 条")
            if (history.isNullOrEmpty()) {
                binding.recyclerView.visibility = View.GONE
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.tvEmpty.visibility = View.GONE
                adapter.submitList(history)
            }
        }
    }

    /**
     * TV 适配：进入页面时给遥控器一个焦点落点。
     * 有历史 → 落在首条；无历史 → 落在「清空历史」按钮，避免按方向键没反应。
     */
    override fun onResume() {
        super.onResume()
        if (!TvUiSupport.isTelevision(requireContext())) return
        if (view?.findFocus() != null) return
        if (binding.recyclerView.visibility == View.VISIBLE) {
            TvFocus.focusFirstItem(binding.recyclerView)
        } else {
            TvFocus.requestInitialFocus(binding.tvClear)
        }
    }

    /**
     * 网格列数：每张卡片最小 120dp（含 margin），与首页 [HomeFragment.calculateSpanCount] 同一算法。
     *
     * 必须读「当前上下文」的 resources —— 电视端已被 TvUiSupport 放大密度，
     * 若改用 windowManager 的物理屏幕参数，电视上会按 960dp 算出 8 列、卡片过小。
     */
    private fun calculateSpanCount(): Int {
        val dm = resources.displayMetrics
        val screenWidthDp = dm.widthPixels / dm.density
        val span = (screenWidthDp / 120f).toInt().coerceIn(2, 8)
        Log.d(TAG, "calculateSpanCount: screenWidthDp=$screenWidthDp, span=$span")
        return span
    }

    /**
     * 点击历史条目 → 跳转详情页
     * 注意：PlayHistoryEntity 已冗余存储 playUrl，详情页拿到后可直接跳转播放器；
     *      若 playUrl 为空，详情页会从 JSON 回查补全。
     */
    private fun openDetail(history: PlayHistoryEntity) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_VIDEO_ID, history.videoId)
            putExtra(DetailActivity.EXTRA_VIDEO_TITLE, history.title)
            putExtra(DetailActivity.EXTRA_VIDEO_COVER, history.coverUrl)
            putExtra(DetailActivity.EXTRA_VIDEO_CATEGORY, history.category)
            putExtra(DetailActivity.EXTRA_VIDEO_PLAY_URL, history.playUrl)
            putExtra(DetailActivity.EXTRA_VIDEO_DETAIL_URL, history.detailUrl)
        }
        Log.d(
            TAG,
            "点击历史: videoId=${history.videoId}, title=${history.title}, " +
                "playUrl=${if (history.playUrl.isNotEmpty()) history.playUrl.take(40) + "..." else "(空)"}"
        )
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "HistoryFragment onDestroyView")
        _binding = null
    }

    companion object {
        private const val TAG = "HistoryFragment"
    }
}
