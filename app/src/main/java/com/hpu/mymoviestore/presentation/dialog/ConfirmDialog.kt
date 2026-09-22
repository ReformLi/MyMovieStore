package com.hpu.mymoviestore.presentation.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.databinding.DialogConfirmBinding
import com.hpu.mymoviestore.presentation.tv.TvFocus

/**
 * 通用确认弹窗（居中卡片风格，见《UI 视觉统一规范文档》5.3.1）。
 *
 * 用于替代系统原生 `AlertDialog.Builder().setTitle().setMessage().setPositiveButton()`：
 * 视觉与帮助/关于/视频源/清理缓存等卡片弹窗保持一致，交互语义与原确认框完全等价。
 *
 * 交互约定（与原原生弹窗一致）：
 * - 主按钮：先关闭弹窗，再执行 [onConfirm]
 * - 次按钮：仅关闭，不执行回调
 * - 返回键 / 点击遮罩：可取消，等同次按钮
 */
object ConfirmDialog {

    /**
     * @param message 传 null 或空串时隐藏正文，只保留标题 + 按钮
     * @param positiveText 主按钮文案（默认「确定」，删除类场景传「删除」）
     * @param negativeText 次按钮文案（默认「取消」）
     */
    fun show(
        context: Context,
        title: String,
        message: String? = null,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onConfirm: () -> Unit
    ): android.app.Dialog {
        val binding = DialogConfirmBinding.inflate(LayoutInflater.from(context))

        binding.tvConfirmTitle.text = title
        if (message.isNullOrEmpty()) {
            binding.tvConfirmMessage.visibility = View.GONE
        } else {
            binding.tvConfirmMessage.visibility = View.VISIBLE
            binding.tvConfirmMessage.text = message
        }
        binding.btnConfirm.text = positiveText
        binding.btnCancel.text = negativeText

        // 用普通 Dialog 承载自定义卡片，绕开 appcompat AlertDialog 的 subdecor +
        // AlertDialogLayout 两趟布局（真机栈采样显示这是弹窗首帧固定开销、会冻结被点控件的
        // 按压水波纹）。本弹窗是整块自定义卡片，不使用 AlertDialog 的标题/正文/按钮区，可安全替换。
        val dialog = android.app.Dialog(context, R.style.CardDialog)
        dialog.setContentView(binding.root)

        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
        // TV 适配：确定 / 取消 按钮可遥控器聚焦
        TvFocus.applyToDialogButtons(binding.root)
        // 卡片本身负责圆角与背景：窗口透明 + 以屏宽短边为准的宽度
        DialogSizing.applyCenteredCard(dialog, context)

        return dialog
    }
}
