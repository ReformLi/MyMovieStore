package com.hpu.mymoviestore.presentation.update

import android.content.Context
import com.hpu.mymoviestore.data.repository.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 应用级 APK 下载管理器（单例）。
 *
 * 下载任务不绑定任何页面生命周期——「关于」弹窗关闭后下载在后台继续，
 * 重新打开弹窗时通过 [state] 恢复进度/完成态展示。
 * 下载失败时本地保留断点（见 [ApkDownloader]），重试自动续传。
 */
object ApkDownloadManager {

    /**
     * 下载状态。Completed 携带 url 和 sha256，用于判断是否与当前检查到的更新包一致：
     * 远程配置了 sha256 时以 sha256 为锚点（URL 不变只换内容的发布流也可识别换包），
     * 未配置时退回 URL 比对。
     */
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val downloaded: Long, val total: Long) : DownloadState()
        data class Completed(val apk: File, val url: String, val sha256: String?) : DownloadState()
        data class Failed(val message: String) : DownloadState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    /** 是否有下载任务正在进行 */
    val isDownloading: Boolean get() = _state.value is DownloadState.Downloading

    /**
     * 最近一次检查到的更新信息（全局持有，弹窗实例字段随弹窗销毁丢失）。
     * 「关于」弹窗重开时据此直接恢复更新卡片展示（下载中显示进度、已完成显示安装按钮）。
     * 由 AboutDialog.checkUpdate() 写入（无更新时置 null）；应用重启自然清零。
     */
    var lastUpdateInfo: UpdateInfo? = null

    /**
     * 发起（或继续）下载。已在下载中时忽略重复触发。
     *
     * sha256 锚定复用：本地已有完整 APK 且其下载时锚定的 sha256 与当前远程一致时，
     * 零流量直接进入安装校验（典型场景：进程重启后重新点「立即更新」，
     * 不再全量重下之前已下载完成的同一个包）。
     */
    fun start(context: Context, url: String, remoteSha256: String? = null) {
        if (isDownloading) return
        val appContext = context.applicationContext
        val downloader = ApkDownloader(appContext)
        scope.launch {
            try {
                // ① 复用检查：锚点一致 → 跳过下载直接进入校验
                val apk = withContext(Dispatchers.IO) {
                    downloader.reuseCompletedApk(remoteSha256)
                } ?: run {
                    // ② 无可复用文件 → 正常下载（带断点续传）
                    _state.value = DownloadState.Downloading(0L, -1L)
                    downloader.download(url, remoteSha256) { downloaded, total ->
                        _state.value = DownloadState.Downloading(downloaded, total)
                    }
                }
                // ③ 安装前双重校验（签名证书 + 文件 SHA-256，见 ApkVerifier）；
                //    复用路径同样校验，防本地文件被篡改/损坏
                val error = withContext(Dispatchers.IO) {
                    ApkVerifier.verify(appContext, apk, remoteSha256)
                }
                if (error != null) {
                    // 校验失败：作废下载文件和锚点，避免被安装/误复用
                    withContext(Dispatchers.IO) {
                        apk.delete()
                        downloader.invalidateShaMeta()
                    }
                    _state.value = DownloadState.Failed(error)
                    return@launch
                }
                _state.value = DownloadState.Completed(apk, url, remoteSha256)
            } catch (e: IOException) {
                _state.value = DownloadState.Failed(e.message ?: "未知错误")
            } catch (e: Exception) {
                _state.value = DownloadState.Failed(e.message ?: "未知错误")
            }
        }
    }

    /** 安装包失效（如 cacheDir 被系统清理）时重置状态，允许重新下载 */
    fun resetToIdle() {
        if (!isDownloading) _state.value = DownloadState.Idle
    }
}
