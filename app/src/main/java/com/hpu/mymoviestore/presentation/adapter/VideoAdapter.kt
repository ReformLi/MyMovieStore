package com.hpu.mymoviestore.presentation.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.ItemHomeLoadMoreBinding
import com.hpu.mymoviestore.databinding.ItemVideoBinding
import com.hpu.mymoviestore.presentation.tv.TvFocus

/**
 * 视频列表适配器 —— 数据源来自 JSON 挡板
 * 支持：首页分类列表、搜索结果、收藏列表（已移除）
 */
class VideoAdapter(
    private val onItemClick: (VideoItem) -> Unit,
    private val onLoadMoreClick: (() -> Unit)? = null,
    /** 搜索结果页用：卡片底部一行显示「视频源」而不是「评分」 */
    private val sourceMode: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<VideoItem> = emptyList()
    private var showLoadMore: Boolean = false
    private var spanCount: Int = 3

    /**
     * 更新列数，用于动态计算封面高度。
     * 列数越多，每个卡片越窄，封面高度按比例缩小。
     */
    fun setSpanCount(span: Int) {
        if (spanCount == span) return
        spanCount = span
        notifyDataSetChanged()
    }

    fun submitList(list: List<VideoItem>) {
        items = list
        Log.d(TAG, "submitList: 共 ${list.size} 条")
        notifyDataSetChanged()
    }

    fun setShowLoadMore(show: Boolean) {
        if (showLoadMore == show) return
        showLoadMore = show
        notifyDataSetChanged()
    }

    fun isLoadMorePosition(position: Int): Boolean {
        return showLoadMore && position == items.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (isLoadMorePosition(position)) VIEW_TYPE_LOAD_MORE else VIEW_TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LOAD_MORE) {
            val binding = ItemHomeLoadMoreBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            LoadMoreViewHolder(binding).apply {
                // TV 适配：「加载更多」也需可聚焦，否则遥控器无法触发翻页。
                // 满宽整行卡片本会被 canScaleUp 的满宽规则拦掉放大，与首页/历史网格、
                // 搜索结果整行卡统一取 1.05f + alwaysScale（左右 14dp margin 内、
                // 列表容器 clipChildren=false，外扩不会被裁）。
                TvFocus.applyTo(binding.root, scale = 1.05f, alwaysScale = true)
            }
        } else {
            val binding = ItemVideoBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            VideoViewHolder(binding).apply {
                // TV 适配：卡片可被遥控器聚焦 + 获焦放大（列表卡片绕开贴边误判，见 TvFocus.alwaysScale）
                TvFocus.applyTo(binding.root, scale = 1.05f, alwaysScale = true)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is VideoViewHolder) {
            holder.bind(items[position])
        } else if (holder is LoadMoreViewHolder) {
            holder.bind()
        }
    }

    override fun getItemCount(): Int = items.size + if (showLoadMore) 1 else 0

    inner class VideoViewHolder(private val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            // TV 适配：复用项恢复原状，避免残留上一项的放大状态
            TvFocus.resetAppearance(binding.root)
            binding.tvTitle.text = video.title

            binding.tvRating.text = if (sourceMode) {
                // 搜索结果页：同样位置显示来源视频源
                video.sourceName.ifBlank { "未知来源" }
            } else if (video.rating.isNotEmpty()) {
                "评分 ${video.rating}"
            } else {
                "暂无评分"
            }

            // 根据列数动态调整封面高度，保持 3:4 的宽高比
            val coverHeight = calculateCoverHeight()
            binding.ivCover.layoutParams.height = coverHeight

            if (video.coverUrl.isNotEmpty()) {
                binding.ivCover.load(video.coverUrl)
            } else {
                binding.ivCover.setImageDrawable(null)
            }

            binding.root.setOnClickListener {
                onItemClick(video)
            }
        }

        /**
         * 根据列数计算封面高度。
         * 基准：3列时封面 150dp，按列数反比缩放，最小 80dp，最大 200dp。
         */
        private fun calculateCoverHeight(): Int {
            val density = binding.root.context.resources.displayMetrics.density
            val baseHeightDp = 150f
            val heightDp = (baseHeightDp * 3f / spanCount)
                .coerceIn(80f, 200f)
            return (heightDp * density + 0.5f).toInt()
        }
    }

    inner class LoadMoreViewHolder(private val binding: ItemHomeLoadMoreBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.root.setOnClickListener {
                onLoadMoreClick?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "VideoAdapter"
        private const val VIEW_TYPE_VIDEO = 1
        private const val VIEW_TYPE_LOAD_MORE = 2
    }
}
