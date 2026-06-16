package com.hpu.mymoviestore.presentation.adapter

import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.model.VideoItem
import com.hpu.mymoviestore.databinding.ItemSearchResultBinding

/**
 * 网页搜索结果列表适配器。
 */
class SearchResultAdapter(
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.SearchResultViewHolder>() {

    private var items: List<VideoItem> = emptyList()

    fun submitList(list: List<VideoItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SearchResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SearchResultViewHolder(
        private val binding: ItemSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.tvTitle.text = video.title

            if (video.sourceName.isNotBlank()) {
                binding.tvSource.text = video.sourceName
                binding.tvSource.visibility = View.VISIBLE
            } else {
                binding.tvSource.visibility = View.GONE
            }

            binding.tvMeta.text = buildString {
                append("类型：")
                append(video.category.ifBlank { "未知" })
                append("  上映时间：")
                append(video.year.ifBlank { "未知" })
            }
            binding.tvActors.text = "主演：${video.actors.ifBlank { "未知" }}"
            binding.tvDescription.text = video.description.ifBlank { "暂无剧情简介" }

            if (video.coverUrl.isNotBlank()) {
                binding.ivCover.load(video.coverUrl)
            } else {
                binding.ivCover.setImageDrawable(null)
            }

            binding.root.setOnClickListener {
                onItemClick(video)
            }
        }
    }
}
