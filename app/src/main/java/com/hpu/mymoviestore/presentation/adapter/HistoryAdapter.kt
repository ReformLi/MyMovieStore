package com.hpu.mymoviestore.presentation.adapter

import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import com.hpu.mymoviestore.databinding.ItemHistoryBinding
import java.util.Calendar

/**
 * 播放历史适配器
 *
 * - 展示：封面、标题、分类、最后播放时间、播放源（右下角）
 * - 点击：跳转到详情页（携带 videoId/title/coverUrl/category/playUrl）
 */
class HistoryAdapter(
    private val onItemClick: (PlayHistoryEntity) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private var items: List<PlayHistoryEntity> = emptyList()

    fun submitList(list: List<PlayHistoryEntity>) {
        items = list
        Log.d(TAG, "submitList: 共 ${list.size} 条历史记录")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(history: PlayHistoryEntity) {
            binding.tvTitle.text = history.title

            // 标题右侧的源标签（保留但默认隐藏，用底部标签替代）
            binding.tvSource.visibility = View.GONE

            binding.tvCategory.text = history.category

            val calendar = Calendar.getInstance()
            calendar.timeInMillis = history.lastPlayTime
            val timeStr = DateFormat.format("yyyy-MM-dd HH:mm", calendar).toString()
            // 将时间拼接到分类后面显示
            binding.tvCategory.text = "${history.category}  ·  $timeStr"

            if (history.episodeTitle.isNotBlank()) {
                binding.tvEpisode.text = "播放至 ${normalizeEpisodeTitle(history.episodeTitle)}"
                binding.tvEpisode.visibility = View.VISIBLE
            } else {
                binding.tvEpisode.visibility = View.GONE
            }

            // 右下角显示播放源，与播放记录同一行
            if (history.sourceName.isNotBlank()) {
                binding.tvSourceBottom.text = history.sourceName
                binding.tvSourceBottom.visibility = View.VISIBLE
            } else {
                binding.tvSourceBottom.visibility = View.GONE
            }

            if (history.coverUrl.isNotEmpty()) {
                binding.ivCover.load(history.coverUrl)
            }

            binding.root.setOnClickListener {
                onItemClick(history)
            }
        }
    }

    companion object {
        private const val TAG = "HistoryAdapter"

        private fun normalizeEpisodeTitle(title: String): String {
            val number = Regex("\\d+").find(title)?.value?.toIntOrNull()
            return if (number != null && title.contains("集")) {
                "第${number}集"
            } else {
                title
            }
        }
    }
}
