package com.hpu.mymoviestore.presentation.dialog

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager

/**
 * 居中卡片弹窗的统一尺寸策略。
 *
 * ### 为什么不按屏宽百分比定宽
 *
 * 横屏（电视 / 手机横屏）的屏幕「很宽很矮」——电视可用画布约 662×372dp、
 * 手机横屏约 640×360dp。按 `屏宽 × 90%` 定宽会得到 **596dp 宽的扁条**，
 * 宽度接近屏宽的 90%、高度却受屏高限制，宽高比彻底失衡：
 * 这就是「横屏弹框全都太宽、不好看」的唯一根因。
 *
 * 改为**以短边为基准**：
 *
 * ```
 * 宽度 = min(宽 × 0.88, 高 × 0.90, 460dp)
 * ```
 *
 * 落点：
 *
 * | 形态 | 屏幕 px / 密度 | 弹框宽度 | 占屏宽 |
 * |---|---|---|---|
 * | 竖屏手机 | 1080×2400 @3x | 317dp | 88%（与旧行为基本一致，不回归） |
 * | 电视 | 1920×1080 @2.9x | 335dp | 51%（回到正常卡片） |
 * | 手机横屏 | 2400×1080 @3x | 324dp | 40% |
 *
 * ### 横屏分栏弹窗（`split = true`）
 *
 * 视频源管理 / 清理缓存 / 帮助三个弹窗在横屏下改为「左侧内容区 + 右侧按钮栏」的
 * 左右分栏（布局见 `layout-land/dialog_*.xml`），多出来的那条竖向按钮栏要额外占宽，
 * 若仍按上面 335dp 算，左列只剩 160dp 完全不够用，因此单独放宽：
 *
 * ```
 * 宽度 = min(宽 × 0.80, 高 × 1.55, 620dp)
 * ```
 *
 * 这里第二个约束由「屏高的 0.90」换成「屏高的 1.55」——它**不是**高度约束，
 * 而是限死**宽高比**：分栏后弹窗可以更扁，但宽度超过屏高 1.55 倍时高度必然顶出屏幕。
 * 电视落点 530dp（占屏宽 80%，左列仍有 332dp 可用），手机横屏受宽高比约束落在 558dp。
 * 首个系数刻意从 0.88 收到 0.80：分栏弹窗高度已经接近满屏，宽度再铺满会显得「顶满屏幕」。
 *
 * ⚠️ 只在**横屏**生效：竖屏下 `split` 参数被忽略，宽度算法与 [widthPx] 原逻辑一字不差。
 */
object DialogSizing {

    /** 宽度上限（dp）：超大屏 / 4K 电视下避免弹框被拉得过宽 */
    private const val MAX_WIDTH_DP = 460f

    /** 内容滚动区默认最大高度（占屏高比例） */
    private const val CONTENT_HEIGHT_RATIO = 0.50f

    /** 列表型内容（视频源 / 选集）的最大高度比例：上下还有标题与按钮等固定行 */
    const val LIST_HEIGHT_RATIO = 0.45f

    /** 分栏弹窗宽度上限（dp） */
    private const val SPLIT_MAX_WIDTH_DP = 620f

    /** 分栏弹窗宽度 / 屏高 的比值上限（限死宽高比，防止分栏弹窗纵向顶出屏幕） */
    private const val SPLIT_ASPECT = 1.55f

    /**
     * 分栏弹窗内容区最大高度比例（**比竖排版更小**，不是更大）。
     *
     * 竖排版内容区只有列表本身在上面、按钮在下面；分栏后按钮移到右栏，左列除了列表
     * 还会多出「全选行 / 缓存大小 / 底部提示」这类固定行，弹框总高 = 固定行 + 内容区，
     * 若沿用 0.50 会把 1080p 电视（可用画布约 372dp）的弹框顶到 90% 屏高以上。
     * 0.44 落在：视频源 ≈ 326dp、缓存 ≈ 273dp、帮助 ≈ 282dp（屏高的 73%~88%），
     * 超出部分由滚动承载。
     */
    private const val SPLIT_CONTENT_HEIGHT_RATIO = 0.44f

    /** 当前是否横屏（电视恒为 true；`layout-land/` 只在横屏生效，两者一一对应） */
    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 弹窗宽度（px）：以屏幕短边为基准，横竖屏都得到合适的卡片宽高比。
     *
     * @param split true = 横屏分栏弹窗（左内容 + 右按钮栏），宽度单独放宽；
     *              竖屏下该参数无效，等同 false
     */
    fun widthPx(context: Context, split: Boolean = false): Int {
        val dm = context.resources.displayMetrics
        if (split && isLandscape(context)) {
            val byWidth = dm.widthPixels * 0.80f
            val byAspect = dm.heightPixels * SPLIT_ASPECT
            val cap = SPLIT_MAX_WIDTH_DP * dm.density
            return minOf(byWidth, byAspect, cap).toInt()
        }
        val byWidth = dm.widthPixels * 0.88f
        val byHeight = dm.heightPixels * 0.90f
        val cap = MAX_WIDTH_DP * dm.density
        return minOf(byWidth, byHeight, cap).toInt()
    }

    /**
     * 内容滚动区最大高度（px）。
     *
     * @param ratio 占屏高比例，默认 [CONTENT_HEIGHT_RATIO]；列表型传 [LIST_HEIGHT_RATIO]
     */
    fun contentMaxHeightPx(context: Context, ratio: Float = CONTENT_HEIGHT_RATIO): Int {
        return (context.resources.displayMetrics.heightPixels * ratio).toInt()
    }

    /**
     * 分栏弹窗的内容区最大高度（px）。
     *
     * 横屏用 [SPLIT_CONTENT_HEIGHT_RATIO]，竖屏回退 [portraitRatio] ——
     * 竖屏布局仍是「按钮在底部」的竖排结构，可用高度与改动前完全一致（零回归）。
     *
     * @param portraitRatio 竖屏时的比例，列表型传 [LIST_HEIGHT_RATIO]
     */
    fun splitContentMaxHeightPx(
        context: Context,
        portraitRatio: Float = CONTENT_HEIGHT_RATIO
    ): Int = contentMaxHeightPx(
        context,
        if (isLandscape(context)) SPLIT_CONTENT_HEIGHT_RATIO else portraitRatio
    )

    /**
     * 居中卡片窗口：透明背景 + 统一宽度，高度由内容决定。
     * 圆角与背景色由布局里的 MaterialCardView 自行承担。
     *
     * 必须在 `dialog.show()` 之后调用 —— 窗口未 attach 时 `setDimAmount` 不生效。
     *
     * @param split true = 横屏分栏弹窗（见类注释），仅横屏生效
     */
    fun applyCenteredCard(dialog: android.app.Dialog?, context: Context, split: Boolean = false) {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.6f)
            setLayout(widthPx(context, split), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 把滚动 / 列表区压到 [maxHeightPx] 以内（内容不足时保持 `wrap_content`）。
     * 需要在布局完成后再判断，所以内部走 `post`。
     */
    fun limitContentHeight(view: View?, maxHeightPx: Int) {
        if (view == null || maxHeightPx <= 0) return
        view.post {
            if (view.height > maxHeightPx) {
                view.layoutParams = view.layoutParams.apply { height = maxHeightPx }
                view.requestLayout()
            }
        }
    }
}
