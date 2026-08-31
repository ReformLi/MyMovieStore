package com.hpu.mymoviestore.presentation.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.databinding.DialogConfirmBinding

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
    ): AlertDialog {
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

        val dialog = AlertDialog.Builder(context, R.style.CardDialog)
            .setView(binding.root)
            .create()

        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
        // 卡片本身负责圆角与背景，窗口透明 + 屏宽 85%
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.6f)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        return dialog
    }
}
