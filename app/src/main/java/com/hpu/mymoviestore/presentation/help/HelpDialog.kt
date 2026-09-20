package com.hpu.mymoviestore.presentation.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.presentation.dialog.DialogSizing
import com.hpu.mymoviestore.presentation.tv.TvFocus

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
        // TV 适配：关闭按钮可遥控器聚焦（弹窗内无可聚焦控件时遥控器会"失去落点"）
        TvFocus.applyToDialogButtons(view)
        // 横屏分栏版（layout-land）的滚动焦点由**每一行功能卡**承接：行在 HelpFeatureRow
        // 样式里 focusable + 自绘焦点环，只读无点击行为，获焦行自动滚入可视区。
        // 因此**不再**给 scrollContent 补焦点（applyFocusableOnly 会把容器重新置为可聚焦，
        // 多出一个无高亮的停靠点，与逐行模型冲突）；竖屏版只跑触屏，滚动行为不受影响。
    }

    override fun onStart() {
        super.onStart()
        val ctx = context ?: return
        // 居中卡片：透明背景 + 以屏宽短边为准的宽度（横屏不再被拉成超宽扁条）。
        // split = true：横屏下是 layout-land 的「左指引 + 右按钮栏」分栏布局，需要更宽的卡片。
        DialogSizing.applyCenteredCard(dialog, ctx, split = true)
        // 条目较多：内容区限高并可滚动（分栏后按钮移到右栏，横屏可给内容多留一截高度）
        DialogSizing.limitContentHeight(
            view?.findViewById(R.id.scrollContent),
            DialogSizing.splitContentMaxHeightPx(ctx)
        )
    }
}
