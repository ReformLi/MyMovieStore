package com.hpu.mymoviestore.presentation.update

import android.content.Context
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

    /** 下载状态。Completed 携带 url，用于判断是否与当前检查到的更新包一致 */
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val downloaded: Long, val total: Long) : DownloadState()
        data class Completed(val apk: File, val url: String) : DownloadState()
        data class Failed(val message: String) : DownloadState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    /** 是否有下载任务正在进行 */
    val isDownloading: Boolean get() = _state.value is DownloadState.Downloading

    /** 发起（或继续）下载。已在下载中时忽略重复触发。 */
    fun start(context: Context, url: String, remoteSha256: String? = null) {
        if (isDownloading) return
        val appContext = context.applicationContext
        _state.value = DownloadState.Downloading(0L, -1L)
        val downloader = ApkDownloader(appContext)
        scope.launch {
            try {
                val apk = downloader.download(url) { downloaded, total ->
                    _state.value = DownloadState.Downloading(downloaded, total)
                }
                // 下载完成 → 安装前双重校验（签名证书 + 文件 SHA-256，见 ApkVerifier）
                val error = withContext(Dispatchers.IO) {
                    ApkVerifier.verify(appContext, apk, remoteSha256)
                }
                if (error != null) {
                    // 校验失败：作废下载文件，避免被安装
                    withContext(Dispatchers.IO) { apk.delete() }
                    _state.value = DownloadState.Failed(error)
                    return@launch
                }
                _state.value = DownloadState.Completed(apk, url)
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
