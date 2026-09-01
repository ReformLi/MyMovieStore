package com.hpu.mymoviestore.presentation.update

import android.content.Context
import android.util.Log
import com.hpu.mymoviestore.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * APK 更新包下载器。
 *
 * - OkHttp 下载到 cacheDir/update/ 目录（固定文件名 update.apk）
 * - 断点续传：通过 Range 请求头从已下载字节处继续（206）；
 *   服务器不支持 Range 返回 200 时整体重下；断点越界(416)时作废重下
 * - 通过 sidecar 文件 update.url 记录下载地址，URL 变化（远程换了更新包）时自动作废旧断点
 * - 流式写盘 + 实时进度回调（供 UI 进度条展示）
 */
class ApkDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ApkDownloader"
        private const val UPDATE_DIR = "update"
        private const val APK_FILE = "update.apk"
        private const val URL_META_FILE = "update.url"
    }

    /** 下载进度回调：downloadedBytes / totalBytes（totalBytes 未知时为 -1） */
    fun interface ProgressListener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
    }

    /**
     * 下载 APK（本地存在同 URL 断点时自动续传）。
     *
     * @param url APK 下载地址
     * @param listener 进度回调（在 IO 线程回调，调用方自行切主线程更新 UI）
     * @return 下载完成的本地文件
     * @throws IOException 网络失败 / 响应异常 / 断点越界
     */
    suspend fun download(url: String, listener: ProgressListener): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val outputFile = File(updateDir, APK_FILE)
        val urlMeta = File(updateDir, URL_META_FILE)

        // 清理历史残留（旧版本按时间戳命名的 APK 等其他文件）
        updateDir.listFiles()?.forEach { if (it != outputFile && it != urlMeta) it.delete() }

        // 断点判断：本地已有部分文件且 URL 未变 → 续传
        var resumeFrom = 0L
        if (outputFile.exists() && urlMeta.exists()) {
            val savedUrl = runCatching { urlMeta.readText() }.getOrNull()
            if (savedUrl == url) {
                resumeFrom = outputFile.length()
                if (resumeFrom > 0L) Log.d(TAG, "断点续传: 本地已有 $resumeFrom 字节")
            } else {
                // URL 变了，旧断点无效
                Log.d(TAG, "下载地址已变化，作废旧断点")
                outputFile.delete()
                urlMeta.delete()
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile)")
        if (resumeFrom > 0L) requestBuilder.header("Range", "bytes=$resumeFrom-")

        HttpClientProvider.downloadClient.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                // Range 越界：本地文件可能已损坏或不完整 → 作废断点，下次整体重下
                Log.w(TAG, "Range 越界(416)，作废断点")
                outputFile.delete()
                urlMeta.delete()
                throw IOException("断点失效已重置，请重新下载")
            }
            if (!response.isSuccessful) {
                throw IOException("下载请求失败: code=${response.code}")
            }
            val body = response.body ?: throw IOException("下载响应体为空")

            // 服务器忽略 Range 返回 200（全量内容）→ 不能续传，从头写
            val resumed = resumeFrom > 0L && response.code == 206
            if (resumeFrom > 0L && !resumed) {
                Log.w(TAG, "服务器不支持断点续传(非206)，整体重下")
                resumeFrom = 0L
            }

            // 记录 URL，供下次断点判断（失败中断时保留）
            urlMeta.writeText(url)

            val contentLen = body.contentLength()
            val totalBytes = if (contentLen > 0) resumeFrom + contentLen else -1L

            body.byteStream().use { input ->
                FileOutputStream(outputFile, resumed).use { output ->
                    val buffer = ByteArray(65536)
                    var downloaded = resumeFrom
                    if (downloaded > 0L) listener.onProgress(downloaded, totalBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        listener.onProgress(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }

            if (outputFile.length() == 0L) {
                throw IOException("下载完成但文件为空")
            }
            // 下载完成：断点已无用，删除 meta；保留 APK 供安装
            urlMeta.delete()
            Log.d(TAG, "APK 下载完成: ${outputFile.absolutePath}, size=${outputFile.length()}")
            outputFile
        }
    }
}
