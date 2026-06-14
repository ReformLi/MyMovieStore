package com.hpu.mymoviestore.presentation.fragment

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import com.hpu.mymoviestore.databinding.FragmentHistoryBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
import com.hpu.mymoviestore.presentation.adapter.HistoryAdapter
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
        adapter = HistoryAdapter { history -> openDetail(history) }

        setupViews()
        observeData()
    }

    private fun setupViews() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        Log.d(TAG, "RecyclerView + HistoryAdapter 初始化完成")

        // 点击「清空历史」弹出确认对话框，确认后通过 ViewModel 调用 Room 删除
        binding.tvClear.setOnClickListener {
            Log.d(TAG, "点击清空历史，弹出确认对话框")
            AlertDialog.Builder(requireContext())
                .setTitle("清空历史记录")
                .setMessage("确定要清空所有观看历史吗？")
                .setPositiveButton("确定") { _, _ ->
                    Log.d(TAG, "用户确认清空历史")
                    viewModel.clearAllHistory()
                }
                .setNegativeButton("取消", null)
                .show()
        }
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
