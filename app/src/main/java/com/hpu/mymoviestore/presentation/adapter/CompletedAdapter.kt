package com.hpu.mymoviestore.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.databinding.ItemCompletedBinding
import java.text.DecimalFormat

/**
 * 已完成列表适配器
 *
 * 展示已完成的下载任务列表，每个 item 显示封面、标题、集数、文件大小，
 * 以及播放和删除按钮。支持长按进入多选模式进行批量删除。
 */
class CompletedAdapter(
    private val onPlay: (DownloadTaskEntity) -> Unit,
    private val onDelete: (DownloadTaskEntity) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<CompletedAdapter.CompletedViewHolder>() {

    private var items: List<DownloadTaskEntity> = emptyList()
    private val selectedIds = mutableSetOf<String>()
    private var isMultiSelectMode = false

    fun submitList(list: List<DownloadTaskEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompletedViewHolder {
        val binding = ItemCompletedBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CompletedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CompletedViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /** 获取当前选中的任务 ID 集合 */
    fun getSelectedIds(): Set<String> = selectedIds.toSet()

    /** 退出多选模式，清空选中状态 */
    fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    /** 删除选中项并退出多选模式 */
    fun deleteSelected(): List<String> {
        val ids = selectedIds.toSet()
        selectedIds.clear()
        isMultiSelectMode = false
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
        return ids.toList()
    }

    inner class CompletedViewHolder(
        private val binding: ItemCompletedBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: DownloadTaskEntity) {
            // 封面
            if (task.coverUrl.isNotEmpty()) {
                binding.ivCover.load(task.coverUrl)
            }

            // 标题
            binding.tvTitle.text = task.title

            // 集数
            binding.tvEpisode.text = task.episodeTitle

            // 文件大小
            binding.tvFileSize.text = formatFileSize(task.fileSize)

            // 多选复选框
            binding.cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedIds.contains(task.taskId)
            binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedIds.add(task.taskId)
                } else {
                    selectedIds.remove(task.taskId)
                }
                onSelectionChanged(selectedIds.toSet())
            }

            // 播放按钮
            binding.btnPlay.setOnClickListener {
                if (isMultiSelectMode) {
                    toggleSelection(task)
                } else {
                    onPlay(task)
                }
            }

            // 删除按钮
            binding.btnDelete.setOnClickListener {
                if (isMultiSelectMode) {
                    toggleSelection(task)
                } else {
                    onDelete(task)
                }
            }

            // 长按进入多选模式
            binding.root.setOnLongClickListener {
                if (!isMultiSelectMode) {
                    isMultiSelectMode = true
                    notifyDataSetChanged()
                    toggleSelection(task)
                    true
                } else {
                    false
                }
            }

            // 多选模式下点击 item 切换选中
            binding.root.setOnClickListener {
                if (isMultiSelectMode) {
                    toggleSelection(task)
                }
            }
        }

        private fun toggleSelection(task: DownloadTaskEntity) {
            if (selectedIds.contains(task.taskId)) {
                selectedIds.remove(task.taskId)
            } else {
                selectedIds.add(task.taskId)
            }
            binding.cbSelect.isChecked = selectedIds.contains(task.taskId)
            onSelectionChanged(selectedIds.toSet())
        }
    }

    companion object {
        /** 格式化文件大小 */
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            val df = DecimalFormat("#.##")
            return "${df.format(size)} ${units[unitIndex]}"
        }
    }
}
