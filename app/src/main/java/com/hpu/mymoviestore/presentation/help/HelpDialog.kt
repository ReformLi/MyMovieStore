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
        /** 与 layout-land/dialog_help.xml 里 7 张功能行卡的 android:tag 一一对应 */
        private const val TAG_FEATURE_ROW = "help_feature_row"

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
        // 横屏分栏版（layout-land）的滚动焦点由**每一行功能卡**承接：只读、无点击行为，
        // 方向键逐行移动、获焦行自动滚入可视区。
        // ⚠️ 行的聚焦能力必须在这里用代码赋予，**不能**写回 HelpFeatureRow 样式：
        //   res/layout-land/ 同时是**手机横屏**布局（不是 TV 专属目录），样式里写死
        //   focusable + 焦点环会让手机横屏一进弹窗就长出橙色描边、焦点乱跳。
        // 因此**不再**给 scrollContent 补焦点（applyFocusableOnly 会把容器重新置为可聚焦，
        // 多出一个无高亮的停靠点，与逐行模型冲突）；竖屏版只跑触屏，滚动行为不受影响。
        applyFeatureRowFocus(view)
    }

    /**
     * 把横屏帮助弹窗里的 7 张只读功能行卡做成电视端的焦点停靠点。
     *
     * 行在 XML 里只打了 `android:tag="help_feature_row"`（样式 HelpFeatureRow 已不含任何焦点
     * 属性），聚焦能力与自绘焦点环在这里交给 [TvFocus] —— 它的入口带 isTelevision 门控，
     * 手机横屏拿到的就是"没有焦点属性"的那一套，触屏行为与 TV 适配前完全一致。
     *
     * 用 applyFocusableOnly 而不是 applyTo：这些行是整行满宽，获焦放大 8% 会溢出父容器边界
     * （canScaleUp 也会因此把它们拦下，不如直接不挂缩放动画）。
     */
    private fun applyFeatureRowFocus(root: View) {
        val rows = ArrayList<View>()
        fun collect(v: View) {
            if (TAG_FEATURE_ROW == v.tag) rows.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(root)
        rows.forEach { TvFocus.applyFocusableOnly(it) }
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
