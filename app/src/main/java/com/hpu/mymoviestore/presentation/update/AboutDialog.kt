package com.hpu.mymoviestore.presentation.update

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hpu.mymoviestore.BuildConfig
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.repository.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File

/**
 * 关于页（居中卡片 Dialog）。
 *
 * 采用与更新提示弹窗统一的居中卡片风格——关于页是纯信息展示，
 * 居中 Dialog 比底部 BottomSheet 更符合用户预期。
 *
 * 内容：
 * - App 信息（名称、版本号从 BuildConfig 读取）
 * - 检查更新入口：点击后调用 PermissionConfigRepository.checkUpdate()
 * - 发现新版本：展示更新详情卡片（版本号 + update_details + 立即更新按钮）
 * - 下载中：进度条实时刷新（下载由 [ApkDownloadManager] 全局持有，
 *   关闭弹窗不中断，重开弹窗自动恢复进度/完成态展示）
 * - 下载完成：跳转系统安装器
 */
class AboutDialog : DialogFragment() {

    companion object {
        private const val TAG = "AboutDialog"

        fun newInstance(): AboutDialog = AboutDialog()
    }

    private lateinit var tvVersion: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var layoutCheckUpdate: LinearLayout
    private lateinit var tvUpdateTitle: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var progressCheck: ProgressBar
    private lateinit var layoutUpdateCard: LinearLayout
    private lateinit var tvNewVersion: TextView
    private lateinit var tvUpdateDetails: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvDownloadProgress: TextView
    private lateinit var btnUpdate: MaterialButton
    private lateinit var tvClose: TextView

    /** 当前检查到的更新信息（null = 未发现更新） */
    private var updateInfo: UpdateInfo? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_about, container, false)
        tvVersion = view.findViewById(R.id.tvVersion)
        scrollContent = view.findViewById(R.id.scrollContent)
        layoutCheckUpdate = view.findViewById(R.id.layoutCheckUpdate)
        tvUpdateTitle = view.findViewById(R.id.tvUpdateTitle)
        tvUpdateStatus = view.findViewById(R.id.tvUpdateStatus)
        progressCheck = view.findViewById(R.id.progressCheck)
        layoutUpdateCard = view.findViewById(R.id.layoutUpdateCard)
        tvNewVersion = view.findViewById(R.id.tvNewVersion)
        tvUpdateDetails = view.findViewById(R.id.tvUpdateDetails)
        progressDownload = view.findViewById(R.id.progressDownload)
        tvDownloadProgress = view.findViewById(R.id.tvDownloadProgress)
        btnUpdate = view.findViewById(R.id.btnUpdate)
        tvClose = view.findViewById(R.id.tvClose)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        layoutCheckUpdate.setOnClickListener { checkUpdate() }
        btnUpdate.setOnClickListener { onUpdateButtonClick() }
        tvClose.setOnClickListener { dismiss() }

        // 恢复全局持有的更新信息：弹窗重开时直接展开更新卡片
        // （下载中显示进度、已完成显示「安装更新」、失败显示「重新下载」），
        // 而不是回到「检查更新」初始态导致状态显示回退
        ApkDownloadManager.lastUpdateInfo?.let { info ->
            updateInfo = info
            showUpdateCard(info)
        }

        // 订阅全局下载状态：弹窗关闭不中断下载，重开弹窗恢复进度展示
        viewLifecycleOwner.lifecycleScope.launch {
            var last: ApkDownloadManager.DownloadState? = null
            ApkDownloadManager.state.collect { st ->
                renderDownloadState(st, isFreshTransition = last != null)
                // 仅在「下载中 → 完成」的瞬间自动拉起安装器；
                // 重新打开弹窗（初始即 Completed）不重复拉起
                if (st is ApkDownloadManager.DownloadState.Completed
                    && last is ApkDownloadManager.DownloadState.Downloading
                ) {
                    installApk(st.apk)
                }
                last = st
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 居中卡片：透明背景 + 屏宽 85%，内容超长时限制内容区高度可滚动
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        limitContentHeight()
    }

    /**
     * 限制内容区最大高度为屏高 65%（更新说明文案较长时可滚动，
     * 避免 Dialog 撑满屏幕）。
     */
    private fun limitContentHeight() {
        scrollContent.post {
            if (!isAdded) return@post
            val maxHeight = (resources.displayMetrics.heightPixels * 0.65).toInt()
            if (scrollContent.height > maxHeight) {
                scrollContent.layoutParams = scrollContent.layoutParams.apply {
                    height = maxHeight
                }
            }
        }
    }

    /** 检查更新（复用远程配置仓库的 checkUpdate，缓存命中时不联网） */
    private fun checkUpdate() {
        if (ApkDownloadManager.isDownloading) {
            // 下载中拦截：给出明确反馈，避免点击「无反应」的困惑
            tvUpdateStatus.text = "更新包正在下载中，请稍候"
            tvUpdateStatus.visibility = View.VISIBLE
            Toast.makeText(context, "更新包正在下载中，可在下方查看进度", Toast.LENGTH_SHORT).show()
            return
        }
        tvUpdateTitle.text = "检查更新"
        tvUpdateStatus.text = "正在检查..."
        tvUpdateStatus.visibility = View.VISIBLE
        progressCheck.visibility = View.VISIBLE
        layoutUpdateCard.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = MovieApplication.get().permissionConfigRepository.checkUpdate()
                progressCheck.visibility = View.GONE
                // 全局记录检查结果：弹窗关闭后重开时据此恢复卡片展示
                ApkDownloadManager.lastUpdateInfo = info
                if (info != null) {
                    updateInfo = info
                    showUpdateCard(info)
                } else {
                    tvUpdateStatus.text = "当前已是最新版本"
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查更新失败: ${e.message}")
                progressCheck.visibility = View.GONE
                tvUpdateStatus.text = "检查失败，请稍后重试"
                Toast.makeText(context, "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 展示更新详情卡片 */
    private fun showUpdateCard(info: UpdateInfo) {
        layoutUpdateCard.visibility = View.VISIBLE
        tvNewVersion.text = "发现新版本 v${info.latestVersion}"
        tvUpdateDetails.text = info.details ?: "优化使用体验，修复已知问题"
        tvUpdateDetails.visibility = if (info.details.isNullOrEmpty()) View.GONE else View.VISIBLE
        // 按当前全局下载状态渲染按钮（如弹窗重开时已是下载中/已完成）
        renderDownloadState(ApkDownloadManager.state.value, isFreshTransition = false)
        // 卡片展开后重新计算高度限制
        limitContentHeight()
    }

    /** 「立即更新 / 下载中 / 安装更新 / 重新下载」按钮点击 */
    private fun onUpdateButtonClick() {
        val st = ApkDownloadManager.state.value
        if (st is ApkDownloadManager.DownloadState.Completed && matchesCurrentUpdate(st)) {
            installApk(st.apk)
            return
        }
        if (ApkDownloadManager.isDownloading) return
        val info = updateInfo ?: return
        ApkDownloadManager.start(requireContext(), info.downloadUrl, info.sha256)
    }

    /**
     * 判断已完成的下载包是否就是当前检查到的更新包。
     * 远程配置了 sha256 时以 sha256 为锚点（URL 不变只换内容的发布流也能识别换包），
     * 未配置时退回 URL 比对。
     */
    private fun matchesCurrentUpdate(st: ApkDownloadManager.DownloadState.Completed): Boolean {
        val info = updateInfo ?: return false
        return if (!info.sha256.isNullOrBlank()) {
            st.sha256?.equals(info.sha256.trim(), ignoreCase = true) == true
        } else {
            st.url == info.downloadUrl
        }
    }

    /** 按全局下载状态渲染下载区 UI（进度条 / 按钮文案） */
    private fun renderDownloadState(st: ApkDownloadManager.DownloadState, isFreshTransition: Boolean) {
        when (st) {
            is ApkDownloadManager.DownloadState.Idle -> {
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                btnUpdate.text = "立即更新"
                btnUpdate.isEnabled = true
            }
            is ApkDownloadManager.DownloadState.Downloading -> {
                progressDownload.visibility = View.VISIBLE
                tvDownloadProgress.visibility = View.VISIBLE
                tvUpdateStatus.visibility = View.GONE
                btnUpdate.text = "下载中..."
                btnUpdate.isEnabled = false
                if (st.total > 0) {
                    progressDownload.isIndeterminate = false
                    progressDownload.max = 100
                    progressDownload.progress = ((st.downloaded * 100) / st.total).toInt()
                    tvDownloadProgress.text = formatBytes(st.downloaded) + " / " + formatBytes(st.total)
                } else {
                    progressDownload.isIndeterminate = true
                    tvDownloadProgress.text = "已下载 " + formatBytes(st.downloaded)
                }
            }
            is ApkDownloadManager.DownloadState.Completed -> {
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                if (matchesCurrentUpdate(st)) {
                    btnUpdate.text = "安装更新"
                    btnUpdate.isEnabled = true
                } else {
                    // 已完成的包与当前检查到的更新包不一致（远程换了新包）→ 允许重新下载
                    btnUpdate.text = "立即更新"
                    btnUpdate.isEnabled = true
                }
            }
            is ApkDownloadManager.DownloadState.Failed -> {
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                btnUpdate.text = "重新下载"
                btnUpdate.isEnabled = true
                // 仅新失败时弹 Toast（弹窗重开恢复 Failed 状态不重复弹）
                if (isFreshTransition && isAdded) {
                    Toast.makeText(context, "下载失败：${st.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 发起安装（处理 Android 8.0+ 安装未知应用权限） */
    private fun installApk(apk: File) {
        val context = context ?: return
        if (!apk.exists()) {
            // cacheDir 可能被系统清理（存储紧张时随时可能发生）
            Toast.makeText(context, "安装包已被系统清理，请重新下载", Toast.LENGTH_LONG).show()
            ApkDownloadManager.resetToIdle()
            return
        }
        if (!ApkInstaller.canInstall(context)) {
            // 未授予「安装未知应用」权限 → 跳转系统设置，用户授权后回来再点「安装更新」
            Toast.makeText(context, "请先允许安装未知应用，授权后重试", Toast.LENGTH_LONG).show()
            ApkInstaller.requestInstallPermission(context)
            return
        }
        try {
            ApkInstaller.install(context, apk)
        } catch (e: Exception) {
            Log.e(TAG, "发起安装失败: ${e.message}", e)
            Toast.makeText(context, "安装失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
