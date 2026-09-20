package com.hpu.mymoviestore.presentation.dialog

import android.content.Context
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
 * ### 高度
 *
 * 变窄必然变高，所以配套给滚动 / 列表区一个上限（[contentMaxHeightPx]）：
 * 内容不足时保持 `wrap_content`，超出才压到上限并可滚动，避免顶出屏幕。
 */
object DialogSizing {

    /** 宽度上限（dp）：超大屏 / 4K 电视下避免弹框被拉得过宽 */
    private const val MAX_WIDTH_DP = 460f

    /** 内容滚动区默认最大高度（占屏高比例） */
    private const val CONTENT_HEIGHT_RATIO = 0.50f

    /** 列表型内容（视频源 / 选集）的最大高度比例：上下还有标题与按钮等固定行 */
    const val LIST_HEIGHT_RATIO = 0.45f

    /**
     * 弹窗宽度（px）：以屏幕短边为基准，横竖屏都得到合适的卡片宽高比。
     */
    fun widthPx(context: Context): Int {
        val dm = context.resources.displayMetrics
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
     * 居中卡片窗口：透明背景 + 统一宽度，高度由内容决定。
     * 圆角与背景色由布局里的 MaterialCardView 自行承担。
     *
     * 必须在 `dialog.show()` 之后调用 —— 窗口未 attach 时 `setDimAmount` 不生效。
     */
    fun applyCenteredCard(dialog: android.app.Dialog?, context: Context) {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.6f)
            setLayout(widthPx(context), WindowManager.LayoutParams.WRAP_CONTENT)
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
