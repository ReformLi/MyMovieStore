package com.hpu.mymoviestore.presentation.fragment

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.FragmentHomeBinding
import com.hpu.mymoviestore.presentation.activity.DetailActivity
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

    /** 分类常量 ——与 TabLayout 顺序一致 */
    private val categories = listOf(
        "",                 // 全部
        "电影",
        "电视剧",
        "综艺",
        "动漫",
        "纪录片"
    )

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
        adapter = VideoAdapter { video -> openDetail(video) }

        setupViews()
        setupTabs()
        observeData()

        // 首次加载：全量视频列表
        viewModel.loadAllVideos()
    }

    private fun setupViews() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        Log.d(TAG, "RecyclerView + Adapter 初始化完成")
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
                Log.d(TAG, "Tab 切换: position=${tab.position}, category='$category'")
                if (category.isEmpty()) {
                    viewModel.loadAllVideos()
                } else {
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

    /** 列表项点击 → 跳转详情页 */
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
            // 新增：传递详情页URL
            putExtra(DetailActivity.EXTRA_VIDEO_DETAIL_URL, video.detailUrl)
        }
        Log.d(TAG, "跳转到详情页: id=${video.id}, title=${video.title}, detailUrl=${video.detailUrl}")
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "HomeFragment onDestroyView")
        _binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
    }
}
