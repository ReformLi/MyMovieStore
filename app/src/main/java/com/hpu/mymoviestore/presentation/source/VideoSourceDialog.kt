package com.hpu.mymoviestore.presentation.source

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.source.VideoSource

/**
 * 视频源管理（居中卡片 Dialog）。
 *
 * - RecyclerView 列表展示所有视频源，勾选启用状态
 * - 顶部「全选 / 全不选」切换按钮 + 已选计数
 * - 确定时校验至少保留一个源，保存启用状态到 SharedPreferences
 */
class VideoSourceDialog : DialogFragment() {

    companion object {
        private const val PREFS_NAME = "video_sources"

        fun newInstance(): VideoSourceDialog = VideoSourceDialog()
    }

    private lateinit var tvSelectAll: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var rvSources: RecyclerView
    private lateinit var btnConfirm: MaterialButton
    private lateinit var btnCancel: MaterialButton

    /** 源列表（来自 MovieApplication，保持与全局一致） */
    private val sources: List<VideoSource> = MovieApplication.get().allVideoSources.toList()

    /** 勾选状态（对齐 sources 顺序） */
    private val checked: MutableList<Boolean> = sources.map { it.enabled }.toMutableList()

    private lateinit var adapter: SourceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_video_source, container, false)
        tvSelectAll = view.findViewById(R.id.tvSelectAll)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        rvSources = view.findViewById(R.id.rvSources)
        btnConfirm = view.findViewById(R.id.btnConfirm)
        btnCancel = view.findViewById(R.id.btnCancel)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SourceAdapter()
        rvSources.layoutManager = LinearLayoutManager(requireContext())
        rvSources.adapter = adapter

        tvSelectAll.setOnClickListener { toggleSelectAll() }
        btnConfirm.setOnClickListener { onConfirm() }
        btnCancel.setOnClickListener { dismiss() }

        updateSelectAllText()
    }

    override fun onStart() {
        super.onStart()
        // 居中卡片：透明背景 + 屏宽 85%
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        // 限制列表最大高度为屏高 45%（源多时可滚动）
        rvSources.post {
            if (!isAdded) return@post
            val maxHeight = (resources.displayMetrics.heightPixels * 0.45).toInt()
            if (rvSources.height > maxHeight) {
                rvSources.layoutParams = rvSources.layoutParams.apply {
                    height = maxHeight
                }
            }
        }
    }

    /** 切换单个源：勾选 → 更新全选按钮与计数 */
    private fun toggleItem(position: Int) {
        if (position !in checked.indices) return
        checked[position] = !checked[position]
        adapter.notifyItemChanged(position)
        updateSelectAllText()
    }

    /** 全选 / 全不选（按当前状态取反） */
    private fun toggleSelectAll() {
        val allChecked = checked.all { it }
        val target = !allChecked
        for (i in checked.indices) checked[i] = target
        adapter.notifyDataSetChanged()
        updateSelectAllText()
    }

    /** 更新「全选/全不选」文字与已选计数 */
    private fun updateSelectAllText() {
        val allChecked = checked.all { it }
        tvSelectAll.text = if (allChecked) "全不选" else "全选"
        tvSelectedCount.text = "已选 ${checked.count { it }}/${checked.size}"
    }

    /** 确定：校验至少一个源，保存启用状态并关闭 */
    private fun onConfirm() {
        if (checked.none { it }) {
            Toast.makeText(requireContext(), "至少需要选择一个视频源", Toast.LENGTH_SHORT).show()
            return
        }
        // 更新全局源对象
        sources.forEachIndexed { index, source ->
            source.enabled = checked[index]
        }
        // 保存到 SharedPreferences
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            sources.forEachIndexed { index, source ->
                putBoolean("enabled_${source.sourceId}", checked[index])
            }
            apply()
        }
        dismiss()
    }

    /** 源列表适配器 */
    private inner class SourceAdapter : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSourceName)
            val tvCheck: TextView = view.findViewById(R.id.tvCheck)

            init {
                // 整行可点击切换勾选状态
                view.setOnClickListener { toggleItem(bindingAdapterPosition) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tvName.text = sources[position].sourceName
            bindCheckState(holder.tvCheck, checked[position])
        }

        override fun getItemCount(): Int = sources.size
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
            tvCheck.setTextColor(resources.getColor(R.color.colorOnPrimary, null))
        } else {
            tvCheck.setBackgroundResource(R.drawable.bg_check_unselected)
            tvCheck.text = ""
        }
    }
}
