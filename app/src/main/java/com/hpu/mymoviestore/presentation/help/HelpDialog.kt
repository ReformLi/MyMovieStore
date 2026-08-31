package com.hpu.mymoviestore.presentation.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.hpu.mymoviestore.R

/**
 * 帮助页（居中卡片 Dialog）。
 *
 * 与关于页、更新提示弹窗统一视觉风格：圆角卡片 + 主色点缀。
 * 内容：功能指引（首页发现 / 搜索播放 / 播放体验 / 离线下载 / 弹幕观看 /
 * 播放历史 / 视频源管理）+ 常见问题提示。
 */
class HelpDialog : DialogFragment() {

    companion object {
        fun newInstance(): HelpDialog = HelpDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_help, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvClose).setOnClickListener { dismiss() }
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
        // 条目较多，限制内容区最大高度为屏高 60%（超出可滚动，避免撑满屏幕）
        view?.findViewById<ScrollView>(R.id.scrollContent)?.post {
            if (!isAdded) return@post
            val maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            val scrollView = view?.findViewById<ScrollView>(R.id.scrollContent) ?: return@post
            if (scrollView.height > maxHeight) {
                scrollView.layoutParams = scrollView.layoutParams.apply {
                    height = maxHeight
                }
            }
        }
    }
}
