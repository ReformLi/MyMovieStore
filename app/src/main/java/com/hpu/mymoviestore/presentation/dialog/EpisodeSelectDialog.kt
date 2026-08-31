package com.hpu.mymoviestore.presentation.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.databinding.DialogEpisodeSelectBinding
import com.hpu.mymoviestore.databinding.ItemEpisodeSelectBinding

/**
 * 选择下载集数（居中卡片 Dialog，UI 规范 5.3.1）。
 *
 * 替代原生 `setMultiChoiceItems`，视觉与视频源管理弹窗同构，并新增「全选 / 全不选」：
 * - 默认勾选当前播放集 + 已在下载列表中的集
 * - 已在下载列表中的集**锁定勾选**：整行置灰、不响应点击，全选/全不选也会跳过它们
 * - 全部（含锁定项）勾选时按钮显示「全不选」，点击只取消未锁定项；否则显示「全选」
 * - 全部集数都已添加时隐藏「全选」按钮（无可操作项，点了不会有反馈）
 * - 确定：回传本次新增勾选的集数（不含锁定项）；为空时 Toast「没有新集需要下载」并关闭
 *   （原生弹窗点确定同样会关闭，此处保持行为一致）
 */
class EpisodeSelectDialog private constructor(
    private val context: Context,
    private val episodes: List<PlayEpisode>,
    /** 已在下载任务中的 playPageUrl（锁定勾选） */
    private val existingUrls: Set<String>,
    /** 当前播放集的 playPageUrl（默认勾选，可为 null） */
    private val selectedUrl: String?
) {

    /** 锁定项：已在下载列表中，恒为勾选 */
    private val locked: List<Boolean> = episodes.map { it.playPageUrl in existingUrls }

    /** 勾选状态（对齐 episodes 顺序） */
    private val checked: MutableList<Boolean> = episodes.map {
        it.playPageUrl in existingUrls || it.playPageUrl == selectedUrl
    }.toMutableList()

    private val binding = DialogEpisodeSelectBinding.inflate(LayoutInflater.from(context))

    private lateinit var adapter: EpisodeAdapter
    private lateinit var dialog: AlertDialog

    companion object {
        /** 与原调用方式对齐：构建后立即显示，确认时回传新增集数 */
        fun show(
            context: Context,
            episodes: List<PlayEpisode>,
            existingUrls: Set<String>,
            selectedUrl: String?,
            onConfirm: (List<PlayEpisode>) -> Unit
        ) {
            EpisodeSelectDialog(context, episodes, existingUrls, selectedUrl).show(onConfirm)
        }
    }

    private fun show(onConfirm: (List<PlayEpisode>) -> Unit) {
        adapter = EpisodeAdapter()
        binding.rvEpisodes.layoutManager = LinearLayoutManager(context)
        binding.rvEpisodes.adapter = adapter

        binding.tvSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnConfirm.setOnClickListener {
            val newEpisodes = episodes.filterIndexed { index, _ -> checked[index] && !locked[index] }
            dialog.dismiss()
            if (newEpisodes.isEmpty()) {
                Toast.makeText(context, "没有新集需要下载", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onConfirm(newEpisodes)
        }

        dialog = AlertDialog.Builder(context, R.style.CardDialog)
            .setView(binding.root)
            .create()
        dialog.show()

        // 卡片自身负责圆角与背景，窗口透明 + 屏宽 85%
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.6f)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.90).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        // 集数很多时列表最大高度限制为屏高 45%，超出滚动
        binding.rvEpisodes.post {
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.45).toInt()
            if (binding.rvEpisodes.height > maxHeight) {
                binding.rvEpisodes.layoutParams = binding.rvEpisodes.layoutParams.apply { height = maxHeight }
                binding.rvEpisodes.requestLayout()
            }
        }

        updateHeader()
    }

    /** 点击单行切换勾选：锁定项直接忽略 */
    private fun toggleItem(position: Int) {
        if (position !in checked.indices) return
        if (locked[position]) return
        checked[position] = !checked[position]
        adapter.notifyItemChanged(position)
        updateHeader()
    }

    /** 全选 ⇄ 全不选：按当前是否已全勾选取反，锁定项始终保留勾选 */
    private fun toggleSelectAll() {
        val target = !checked.all { it }
        for (i in checked.indices) {
            if (!locked[i]) checked[i] = target
        }
        adapter.notifyDataSetChanged()
        updateHeader()
    }

    /** 更新「全选/全不选」文案与已选计数 */
    private fun updateHeader() {
        binding.tvSelectedCount.text = "已选 ${checked.count { it }}/${episodes.size}"
        if (locked.all { it }) {
            // 没有可操作项（全部已添加）
            binding.tvSelectAll.visibility = View.GONE
        } else {
            binding.tvSelectAll.visibility = View.VISIBLE
            binding.tvSelectAll.text = if (checked.all { it }) "全不选" else "全选"
        }
    }

    /** 集数列表适配器 */
    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemEpisodeSelectBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            init {
                // 整行可点击；CheckBox 关闭自身点击，统一走行点击避免状态冲突
                itemBinding.root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) toggleItem(position)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemEpisodeSelectBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val isLocked = locked[position]
            holder.itemBinding.tvEpisodeName.text =
                if (isLocked) "${episodes[position].title}（已添加）" else episodes[position].title
            holder.itemBinding.cbEpisode.isChecked = checked[position]
            // 锁定项置灰且不可交互（对应原生 isEnabled = false + alpha 0.5f）
            holder.itemBinding.root.isEnabled = !isLocked
            holder.itemBinding.cbEpisode.isEnabled = !isLocked
            holder.itemBinding.root.alpha = if (isLocked) 0.5f else 1.0f
        }

        override fun getItemCount(): Int = episodes.size
    }
}
