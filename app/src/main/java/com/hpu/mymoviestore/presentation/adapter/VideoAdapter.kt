package com.hpu.mymoviestore.presentation.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.ItemVideoBinding

/**
 * 视频列表适配器 —— 数据源来自 JSON 挡板
 * 支持：首页分类列表、搜索结果、收藏列表（已移除）
 */
class VideoAdapter(
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private var items: List<VideoItem> = emptyList()

    fun submitList(list: List<VideoItem>) {
        items = list
        Log.d(TAG, "submitList: 共 ${list.size} 条")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(private val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.tvTitle.text = video.title

            val meta = StringBuilder()
            if (video.year.isNotEmpty()) meta.append(video.year)
            if (video.area.isNotEmpty()) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append(video.area)
            }
            if (video.category.isNotEmpty()) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append(video.category)
            }
            binding.tvMeta.text = meta.toString()

            if (video.rating.isNotEmpty()) {
                binding.tvRating.text = video.rating
                binding.tvRating.visibility = android.view.View.VISIBLE
            } else {
                binding.tvRating.visibility = android.view.View.GONE
            }

            if (video.coverUrl.isNotEmpty()) {
                binding.ivCover.load(video.coverUrl)
            }

            binding.root.setOnClickListener {
                onItemClick(video)
            }
        }
    }

    companion object {
        private const val TAG = "VideoAdapter"
    }
}
