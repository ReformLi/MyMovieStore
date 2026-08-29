package com.hpu.mymoviestore.presentation.update

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.hpu.mymoviestore.BuildConfig
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.repository.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 关于页 BottomSheet。
 *
 * 内容：
 * - App 信息（名称、版本号从 BuildConfig 读取）
 * - 检查更新入口：点击后调用 PermissionConfigRepository.checkUpdate()
 * - 发现新版本：展示更新详情卡片（版本号 + update_details + 立即更新按钮）
 * - 下载中：进度条实时刷新
 * - 下载完成：跳转系统安装器
 */
class AboutBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "AboutBottomSheet"

        fun newInstance(): AboutBottomSheet = AboutBottomSheet()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvVersion: TextView
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

    /** 当前检查到的更新信息（null = 未发现更新） */
    private var updateInfo: UpdateInfo? = null

    /** 下载完成的 APK 文件 */
    private var downloadedApk: File? = null

    /** 是否正在下载（防止重复触发） */
    private var downloading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.about_bottom_sheet, container, false)
        tvVersion = view.findViewById(R.id.tvVersion)
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
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvVersion.text = "版本 ${BuildConfig.VERSION_NAME}"

        layoutCheckUpdate.setOnClickListener { checkUpdate() }
        btnUpdate.setOnClickListener { onUpdateButtonClick() }
    }

    /** 检查更新（复用远程配置仓库的 checkUpdate，缓存命中时不联网） */
    private fun checkUpdate() {
        if (downloading) return
        tvUpdateTitle.text = "检查更新"
        tvUpdateStatus.text = "正在检查..."
        tvUpdateStatus.visibility = View.VISIBLE
        progressCheck.visibility = View.VISIBLE
        layoutUpdateCard.visibility = View.GONE

        scope.launch {
            try {
                val info = MovieApplication.get().permissionConfigRepository.checkUpdate()
                progressCheck.visibility = View.GONE
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
        btnUpdate.text = "立即更新"
        btnUpdate.isEnabled = true
        progressDownload.visibility = View.GONE
        tvDownloadProgress.visibility = View.GONE
    }

    /** 「立即更新 / 安装更新」按钮点击 */
    private fun onUpdateButtonClick() {
        val apk = downloadedApk
        if (apk != null) {
            // 已下载完成 → 发起安装
            installApk(apk)
            return
        }
        if (downloading) return
        val info = updateInfo ?: return
        startDownload(info)
    }

    /** 下载 APK 并实时更新进度条 */
    private fun startDownload(info: UpdateInfo) {
        downloading = true
        btnUpdate.text = "下载中..."
        btnUpdate.isEnabled = false
        progressDownload.visibility = View.VISIBLE
        progressDownload.isIndeterminate = true
        tvDownloadProgress.visibility = View.VISIBLE
        tvDownloadProgress.text = "正在连接服务器..."
        tvUpdateStatus.visibility = View.GONE

        val context = context ?: return
        val downloader = ApkDownloader(context.applicationContext)

        scope.launch {
            try {
                val apk = downloader.download(info.downloadUrl) { downloaded, total ->
                    // IO 线程回调，切主线程更新 UI
                    scope.launch(Dispatchers.Main) {
                        if (total > 0) {
                            progressDownload.isIndeterminate = false
                            progressDownload.max = 100
                            progressDownload.progress = ((downloaded * 100) / total).toInt()
                            tvDownloadProgress.text = formatBytes(downloaded) + " / " + formatBytes(total)
                        } else {
                            tvDownloadProgress.text = "已下载 " + formatBytes(downloaded)
                        }
                    }
                }
                downloadedApk = apk
                downloading = false
                // 下载完成 → 切换为安装状态
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                btnUpdate.text = "安装更新"
                btnUpdate.isEnabled = true
                Toast.makeText(context, "下载完成", Toast.LENGTH_SHORT).show()
                installApk(apk)
            } catch (e: IOException) {
                Log.e(TAG, "APK 下载失败: ${e.message}", e)
                downloading = false
                progressDownload.visibility = View.GONE
                tvDownloadProgress.visibility = View.GONE
                btnUpdate.text = "重新下载"
                btnUpdate.isEnabled = true
                Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 发起安装（处理 Android 8.0+ 安装未知应用权限） */
    private fun installApk(apk: File) {
        val context = context ?: return
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

    override fun onDestroyView() {
        super.onDestroyView()
        // BottomSheet 关闭即取消协程（下载中的任务随生命周期终止）
        scope.cancel()
    }
}
