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
        // TV 适配：横屏分栏版（layout-land）里内容区改成了左侧一列，左列全是纯展示文字，
        // 必须给滚动区焦点能力，否则上下键无法滚动、看不到下方条目。
        // 滚动区是**不可点击的 ViewGroup**，通用遍历只处理「可点击的容器」，覆盖不到它
        // （那会导致焦点能停上去却只有系统默认高亮，与本 App 自绘环不统一），所以这里显式补环。
        TvFocus.applyFocusableOnly(view.findViewById(R.id.scrollContent))
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
