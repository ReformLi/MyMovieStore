package com.hpu.mymoviestore.presentation.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.model.PlayEpisode
import com.hpu.mymoviestore.databinding.DialogEpisodeSelectBinding
import com.hpu.mymoviestore.databinding.ItemEpisodeSelectBinding
import com.hpu.mymoviestore.presentation.tv.TvFocus

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
    private lateinit var dialog: android.app.Dialog

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

        // 用普通 Dialog 承载自定义卡片，绕开 appcompat AlertDialog 的 subdecor +
        // AlertDialogLayout 两趟布局（真机栈采样显示这是弹窗首帧固定开销、会冻结被点控件的
        // 按压水波纹）。本弹窗是整块自定义卡片，不使用 AlertDialog 的标题/正文/按钮区。
        dialog = android.app.Dialog(context, R.style.CardDialog)
        dialog.setContentView(binding.root)
        dialog.show()

        // 卡片自身负责圆角与背景：窗口透明 + 以屏宽短边为准的宽度。
        // split = true：横屏下是 layout-land 的「左列表 + 右按钮栏」分栏布局，需要更宽的卡片；
        // 竖屏（layout/ 竖排版）自动忽略该参数，宽度与改动前完全一致。
        DialogSizing.applyCenteredCard(dialog, context, split = true)
        // 集数很多时列表限高并可滚动（分栏后按钮移到右栏，横屏可给列表多留一截高度）
        DialogSizing.limitContentHeight(
            binding.rvEpisodes,
            DialogSizing.splitContentMaxHeightPx(context, DialogSizing.LIST_HEIGHT_RATIO)
        )

        updateHeader()

        // TV 适配：全选 / 确定 / 取消 按钮可遥控器聚焦
        TvFocus.applyToDialogButtons(binding.root)
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
                // 整行可点击切换勾选状态
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
            TvFocus.applyTo(itemBinding.root, 1.02f)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            TvFocus.resetAppearance(holder.itemView)
            val isLocked = locked[position]
            holder.itemBinding.tvEpisodeName.text =
                if (isLocked) "${episodes[position].title}（已添加）" else episodes[position].title
            bindCheckState(holder.itemBinding.tvCheck, checked[position])
            // 锁定项置灰且不可交互（对应原生 isEnabled = false + alpha 0.5f），
            // 同时取消可聚焦，避免遥控器焦点停在不动项上
            holder.itemBinding.root.isEnabled = !isLocked
            // 聚焦能力统一交给 TvFocus（手机端 no-op）：直接写 isFocusableInTouchMode
            // 会让触屏「第一次点击只聚焦、第二次才勾选」
            TvFocus.setFocusable(holder.itemBinding.root, !isLocked, scale = 1.02f)
            holder.itemBinding.root.alpha = if (isLocked) 0.5f else 1.0f
        }

        override fun getItemCount(): Int = episodes.size
    }

    /**
     * 绑定勾选圆状态：
     * - 选中：主色实心圆 + 白色 ✓
     * - 未选中：分割线色描边圆环
     */
    private fun bindCheckState(tvCheck: TextView, isChecked: Boolean) {
        if (isChecked) {
            tvCheck.setBackgroundResource(R.drawable.bg_check_selected)
            tvCheck.text = "✓"
            tvCheck.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary))
        } else {
            tvCheck.setBackgroundResource(R.drawable.bg_check_unselected)
            tvCheck.text = ""
        }
    }
}
