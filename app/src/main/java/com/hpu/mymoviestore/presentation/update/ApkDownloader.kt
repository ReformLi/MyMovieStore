package com.hpu.mymoviestore.presentation.update

import android.content.Context
import android.util.Log
import com.hpu.mymoviestore.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * APK 更新包下载器。
 *
 * - OkHttp 下载到 cacheDir/update/ 目录
 * - 流式写盘 + 实时进度回调（供 BottomSheet 进度条展示）
 * - 下载完成后返回本地文件路径，由调用方通过 FileProvider 发起安装
 */
class ApkDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ApkDownloader"
        private const val UPDATE_DIR = "update"
    }

    /** 下载进度回调：downloadedBytes / totalBytes（totalBytes 未知时为 -1） */
    fun interface ProgressListener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
    }

    /**
     * 下载 APK。
     *
     * @param url APK 下载地址
     * @param listener 进度回调（在 IO 线程回调，调用方自行切主线程更新 UI）
     * @return 下载完成的本地文件
     * @throws IOException 网络失败 / 响应异常 / 中断
     */
    suspend fun download(url: String, listener: ProgressListener): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val outputFile = File(updateDir, "update_${System.currentTimeMillis()}.apk")
        // 清理历史残留的 APK，避免缓存堆积
        updateDir.listFiles()?.forEach { if (it != outputFile) it.delete() }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile)")
            .build()

        HttpClientProvider.downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载请求失败: code=${response.code}")
            }
            val body = response.body ?: throw IOException("下载响应体为空")
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var downloaded: Long = 0
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
            Log.d(TAG, "APK 下载完成: ${outputFile.absolutePath}, size=${outputFile.length()}")
            outputFile
        }
    }
}
