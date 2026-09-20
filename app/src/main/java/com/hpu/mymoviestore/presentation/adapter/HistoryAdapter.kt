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
import com.hpu.mymoviestore.presentation.tv.TvFocus
import java.util.Calendar

/**
 * 播放历史适配器
 *
 * - 展示：封面、标题、分类、最后播放时间、播放源（右下角）
 * - 点击：跳转到详情页（携带 videoId/title/coverUrl/category/playUrl）
 *
 * 两种形态共用同一个 Adapter 与同一套 id：
 * - 竖屏（gridMode=false）：横向行卡片，展示标题 / 分类 / 播放时间 / 播放至第N集 / 播放源
 * - 横屏/TV（gridMode=true）：网格卡片，展示封面 / 标题 / 播放历史（第N集）/ 播放源，与首页一致
 */
class HistoryAdapter(
    /** 网格形态（横屏/TV）：封面占满卡宽、内容精简为四要素，与首页卡片同一套视觉 */
    private val gridMode: Boolean = false,
    private val onItemClick: (PlayHistoryEntity) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private var items: List<PlayHistoryEntity> = emptyList()
    private var spanCount: Int = 3

    /**
     * 网格形态下按列数重算封面高度（基准：3 列 150dp，按列数反比缩放）。
     * 与 [VideoAdapter.setSpanCount] 同算法，保证历史页与首页卡片大小一致。
     */
    fun setSpanCount(span: Int) {
        if (spanCount == span) return
        spanCount = span
        notifyDataSetChanged()
    }

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
        return HistoryViewHolder(binding).apply {
            // TV 适配：历史条目可遥控器聚焦 + 获焦放大（列表卡片绕开贴边误判，见 TvFocus.alwaysScale）
            TvFocus.applyTo(binding.root, scale = 1.05f, alwaysScale = true)
        }
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(history: PlayHistoryEntity) {
            // TV 适配：复用项恢复原状
            TvFocus.resetAppearance(binding.root)
            binding.tvTitle.text = history.title

            // 标题右侧的源标签（保留但默认隐藏，用底部标签替代）
            binding.tvSource.visibility = View.GONE

            val episodeText = if (history.episodeTitle.isNotBlank()) {
                normalizeEpisodeTitle(history.episodeTitle)
            } else {
                ""
            }

            if (gridMode) {
                // 网格形态：封面 + 标题 + 播放历史（第N集）+ 播放源，与首页卡片一致
                binding.ivCover.layoutParams = binding.ivCover.layoutParams.apply {
                    height = coverHeightPx()
                }
                binding.tvCategory.visibility = View.GONE
                binding.tvPlayTime.visibility = View.GONE
                binding.tvEpisode.text = episodeText
                binding.tvEpisode.visibility =
                    if (episodeText.isEmpty()) View.GONE else View.VISIBLE
            } else {
                // 行卡片形态：分类 + 播放时间 + 播放至第N集（高度由文字行数决定）
                binding.tvCategory.text = history.category

                val calendar = Calendar.getInstance()
                calendar.timeInMillis = history.lastPlayTime
                val timeStr = DateFormat.format("yyyy-MM-dd HH:mm", calendar).toString()
                binding.tvPlayTime.text = "播放时间：$timeStr"

                binding.tvEpisode.text = "播放至 $episodeText"
                binding.tvEpisode.visibility =
                    if (episodeText.isEmpty()) View.GONE else View.VISIBLE
            }

            // 播放源：两种形态都在同一行右侧
            if (history.sourceName.isNotBlank()) {
                binding.tvSourceBottom.text = history.sourceName
                binding.tvSourceBottom.visibility = View.VISIBLE
            } else {
                binding.tvSourceBottom.visibility = View.GONE
            }

            if (history.coverUrl.isNotEmpty()) {
                binding.ivCover.load(history.coverUrl)
            } else {
                // 复用项必须清空，否则会残留上一部影片的封面
                binding.ivCover.setImageDrawable(null)
            }

            binding.root.setOnClickListener {
                onItemClick(history)
            }
        }

        /** 网格形态封面高度：基准 3 列 150dp，按列数反比缩放（80~200dp），与首页一致 */
        private fun coverHeightPx(): Int {
            val density = binding.root.resources.displayMetrics.density
            val heightDp = (150f * 3f / spanCount).coerceIn(80f, 200f)
            return (heightDp * density + 0.5f).toInt()
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
