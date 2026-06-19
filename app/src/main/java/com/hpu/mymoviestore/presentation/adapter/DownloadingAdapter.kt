package com.hpu.mymoviestore.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.databinding.ItemDownloadingBinding

/**
 * 下载中列表适配器
 *
 * 展示正在下载的任务列表，每个 item 显示封面、标题、集数、进度条、百分比、
 * 状态文字、操作按钮（暂停/继续、取消、重试）以及弹幕状态和重试按钮。
 */
class DownloadingAdapter(
    private val onPauseResume: (DownloadTaskEntity) -> Unit,
    private val onCancel: (DownloadTaskEntity) -> Unit,
    private val onRetry: (DownloadTaskEntity) -> Unit,
    private val onDanmakuRetry: (DownloadTaskEntity) -> Unit,
    private val onDeleteFailed: (DownloadTaskEntity) -> Unit
) : RecyclerView.Adapter<DownloadingAdapter.DownloadingViewHolder>() {

    private var items: List<DownloadTaskEntity> = emptyList()

    fun submitList(list: List<DownloadTaskEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadingViewHolder {
        val binding = ItemDownloadingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DownloadingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadingViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DownloadingViewHolder(
        private val binding: ItemDownloadingBinding
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

            // 进度
            val progress = if (task.totalSegments > 0) {
                (task.downloadedSegments * 100) / task.totalSegments
            } else {
                0
            }
            binding.progressBar.progress = progress
            binding.tvProgress.text = "$progress%"

            // 状态文字
            binding.tvStatus.text = DownloadTaskEntity.statusToText(task.status)

            // 根据状态显示操作按钮
            when (task.status) {
                DownloadTaskEntity.STATUS_DOWNLOADING -> {
                    // 下载中 -> 显示暂停、取消
                    binding.btnPauseResume.text = "暂停"
                    binding.btnPauseResume.visibility = android.view.View.VISIBLE
                    binding.btnCancel.visibility = android.view.View.VISIBLE
                    binding.btnRetry.visibility = android.view.View.GONE
                }
                DownloadTaskEntity.STATUS_PAUSED -> {
                    // 已暂停 -> 显示继续、取消
                    binding.btnPauseResume.text = "继续"
                    binding.btnPauseResume.visibility = android.view.View.VISIBLE
                    binding.btnCancel.visibility = android.view.View.VISIBLE
                    binding.btnRetry.visibility = android.view.View.GONE
                }
                DownloadTaskEntity.STATUS_PENDING -> {
                    // 等待下载 -> 显示暂停、取消
                    binding.btnPauseResume.text = "暂停"
                    binding.btnPauseResume.visibility = android.view.View.VISIBLE
                    binding.btnCancel.visibility = android.view.View.VISIBLE
                    binding.btnRetry.visibility = android.view.View.GONE
                }
                DownloadTaskEntity.STATUS_FAILED -> {
                    // 失败 -> 显示重试、删除
                    binding.btnPauseResume.visibility = android.view.View.GONE
                    binding.btnCancel.visibility = android.view.View.GONE
                    binding.btnRetry.visibility = android.view.View.VISIBLE
                    binding.btnDeleteFailed.visibility = android.view.View.VISIBLE
                }
                else -> {
                    binding.btnPauseResume.visibility = android.view.View.GONE
                    binding.btnCancel.visibility = android.view.View.GONE
                    binding.btnRetry.visibility = android.view.View.GONE
                    binding.btnDeleteFailed.visibility = android.view.View.GONE
                }
            }

            // 按钮点击事件
            binding.btnPauseResume.setOnClickListener {
                onPauseResume(task)
            }
            binding.btnCancel.setOnClickListener {
                onCancel(task)
            }
            binding.btnRetry.setOnClickListener {
                onRetry(task)
            }
            binding.btnDeleteFailed.setOnClickListener {
                onDeleteFailed(task)
            }

            // 弹幕状态
            binding.tvDanmakuStatus.text = "弹幕${DownloadTaskEntity.danmakuStatusToText(task.danmakuStatus)}"
            binding.btnDanmakuRetry.visibility = when (task.danmakuStatus) {
                DownloadTaskEntity.DANMAKU_FAILED -> android.view.View.VISIBLE
                else -> android.view.View.GONE
            }
            binding.btnDanmakuRetry.setOnClickListener {
                onDanmakuRetry(task)
            }
        }
    }
}
