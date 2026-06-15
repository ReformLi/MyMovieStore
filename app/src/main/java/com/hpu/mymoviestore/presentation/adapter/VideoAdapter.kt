package com.hpu.mymoviestore.presentation.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.ItemHomeLoadMoreBinding
import com.hpu.mymoviestore.databinding.ItemVideoBinding

/**
 * 视频列表适配器 —— 数据源来自 JSON 挡板
 * 支持：首页分类列表、搜索结果、收藏列表（已移除）
 */
class VideoAdapter(
    private val onItemClick: (VideoItem) -> Unit,
    private val onLoadMoreClick: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<VideoItem> = emptyList()
    private var showLoadMore: Boolean = false

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
            LoadMoreViewHolder(binding)
        } else {
            val binding = ItemVideoBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            VideoViewHolder(binding)
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
            binding.tvTitle.text = video.title

            binding.tvRating.text = if (video.rating.isNotEmpty()) {
                "评分 ${video.rating}"
            } else {
                "暂无评分"
            }

            if (video.coverUrl.isNotEmpty()) {
                binding.ivCover.load(video.coverUrl)
            } else {
                binding.ivCover.setImageDrawable(null)
            }

            binding.root.setOnClickListener {
                onItemClick(video)
            }
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
