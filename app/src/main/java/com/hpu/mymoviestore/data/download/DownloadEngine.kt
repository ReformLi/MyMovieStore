package com.hpu.mymoviestore.data.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.hpu.mymoviestore.data.HttpClientProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * 下载状态常量
 */
object DownloadStatus {
    const val PENDING = 0       // 等待中（在队列中排队）
    const val DOWNLOADING = 1   // 下载中
    const val PAUSED = 2        // 已暂停
    const val COMPLETED = 3     // 已完成
    const val FAILED = 4        // 失败
    const val CANCELLED = 5     // 已取消
    const val MERGING = 6       // 合并中
}

/**
 * 解密结果校验失败时抛出的异常。
 *
 * 含义：密钥或 IV 与流不匹配，解密出的明文不是合法媒体容器。
 * 重试结果必然相同，因此不参与分片重试，直接终止任务。
 */
internal class HlsDecryptException(message: String) : IOException(message)

/**
 * 下载回调接口
 */
interface DownloadCallback {
    /** 进度更新 */
    fun onProgress(taskId: String, downloadedSegments: Int, totalSegments: Int, fileSize: Long)
    /** 状态变更 */
    fun onStatusChanged(taskId: String, status: Int, errorMsg: String?)
    /** 下载完成 */
    fun onCompleted(taskId: String, localFilePath: String, fileSize: Long)
}

/**
 * 下载任务信息
 */
data class DownloadTask(
    val taskId: String,
    val m3u8Url: String,
    val videoTitle: String,
    val episodeTitle: String,
    var callback: DownloadCallback? = null,
    /** 分片 URL 列表（由 M3u8Parser 解析后填充） */
    var segmentUrls: List<String> = emptyList(),
    /** 已下载的分片数量 */
    val downloadedCount: AtomicInteger = AtomicInteger(0),
    /** 已下载的总字节数 */
    val downloadedBytes: AtomicLong = AtomicLong(0),
    /** 当前状态 */
    @Volatile var status: Int = DownloadStatus.PENDING,
    /** 是否被取消 */
    val isCancelled: AtomicBoolean = AtomicBoolean(false),
    /** 是否被暂停 */
    val isPaused: AtomicBoolean = AtomicBoolean(false),
    /** 每个分片已下载的字节数（用于断点续传） */
    val segmentDownloadedBytes: ConcurrentHashMap<Int, Long> = ConcurrentHashMap(),
    /** 每个分片是否已完成 */
    val segmentCompleted: ConcurrentHashMap<Int, Boolean> = ConcurrentHashMap(),
    /** HLS 加密信息（AES-128 时非 null，由 M3u8Parser 解析填充） */
    var encryption: HlsEncryption? = null,
    /** 已下载的解密密钥（AES-128，16 字节） */
    var encryptionKey: ByteArray? = null,
    /** 该任务的协程 Job */
    var job: Job? = null,
    /** 当前活跃的 OkHttp Call（用于暂停/取消时中断网络请求） */
    val activeCalls: ConcurrentHashMap<Int, okhttp3.Call> = ConcurrentHashMap(),
    /** 该任务是否已获取信号量 permit（用于精确释放，避免泄漏） */
    val hasPermit: AtomicBoolean = AtomicBoolean(false)
)

/**
 * 下载引擎核心
 *
 * 功能：
 * - 单例模式
 * - 使用 OkHttp 下载
 * - 管理下载任务队列（最大并发 3 个任务）
 * - 每个任务内部分片并发下载（最大 5 个分片并发）
 * - 支持暂停/恢复/取消
 * - 分片下载支持断点续传（Range header）
 * - 分片下载失败自动重试 3 次（间隔递增 5s, 15s, 30s）
 * - 支持 AES-128 加密 HLS 流（解析 key + IV，分片下载后解密再合并）
 * - 所有分片完成后合并为 mp4（二进制顺序拼接 FileOutputStream）
 * - 合并后删除临时分片文件
 * - 实时通过回调通知进度更新
 * - 下载前检查存储空间
 */
class DownloadEngine(context: Context) {

    companion object {
        private const val TAG = "DownloadEngine"

        /** 最大并发任务数 */
        private const val MAX_CONCURRENT_TASKS = 3

        /** 每个任务最大并发分片数 */
        private const val MAX_CONCURRENT_SEGMENTS = 3

        /** 分片下载最大重试次数 */
        private const val MAX_SEGMENT_RETRIES = 3

        /** 分片间延迟（毫秒） */
        private const val SEGMENT_GAP_MS = 1000L

        /** 重试间隔（毫秒）：5s, 15s, 30s */
        private val RETRY_DELAYS = longArrayOf(5000L, 15000L, 30000L)

        /** 下载超时时间（秒） */
        private const val DOWNLOAD_TIMEOUT = 30L

        /** 最小保留存储空间（500MB） */
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024

        @Volatile
        private var instance: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return instance ?: synchronized(this) {
                instance ?: DownloadEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext: Context = context.applicationContext

    private val okHttpClient: OkHttpClient = HttpClientProvider.downloadClient

    private val m3u8Parser = M3u8Parser(okHttpClient)

    /** 协程作用域，使用 SupervisorJob 管理任务生命周期 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 任务队列信号量，控制最大并发任务数 */
    private val taskSemaphore = Semaphore(MAX_CONCURRENT_TASKS)

    /** 所有活跃任务 */
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    /** 临时目录 */
    private val tempDir: File
        get() = File(appContext.filesDir, "temp").also { it.mkdirs() }

    /** 最终下载目录 */
    private val downloadDir: File
        get() = File(appContext.filesDir, "Download").also { it.mkdirs() }

    // ======================== 公开方法 ========================

    /**
     * 提交一个新的下载任务。
     *
     * @param m3u8Url m3u8 播放地址
     * @param videoTitle 视频标题
     * @param episodeTitle 集数标题
     * @param callback 下载回调
     * @return 任务 ID
     */
    fun submitTask(
        m3u8Url: String,
        videoTitle: String,
        episodeTitle: String,
        taskId: String? = null,
        callback: DownloadCallback? = null
    ): String {
        val finalTaskId = taskId ?: UUID.randomUUID().toString().replace("-", "").substring(0, 16)

        // 原子操作：检查旧任务 + 创建新任务 + 启动协程，防止并发竞态
        tasks.compute(finalTaskId) { _, existing ->
            existing?.let { oldTask ->
                Log.w(TAG, "提交任务时发现旧任务仍存在，先取消: taskId=$finalTaskId, oldStatus=${oldTask.status}")
                oldTask.job?.cancel()
                // 先取消旧任务的 OkHttp Call
                oldTask.activeCalls.values.forEach { call ->
                    try { call.cancel() } catch (_: Exception) {}
                }
                oldTask.activeCalls.clear()
                // 释放旧任务的信号量 permit（如果持有）
                if (oldTask.hasPermit.compareAndSet(true, false)) {
                    taskSemaphore.release()
                }
            }

            val task = DownloadTask(
                taskId = finalTaskId,
                m3u8Url = m3u8Url,
                videoTitle = videoTitle,
                episodeTitle = episodeTitle,
                callback = callback
            )

            Log.d(TAG, "提交下载任务: taskId=$finalTaskId, video=$videoTitle, episode=$episodeTitle")

            task.job = scope.launch {
                executeTask(task)
            }

            task
        }

        return finalTaskId
    }

    /**
     * 暂停指定任务。
     */
    fun pauseTask(taskId: String) {
        val task = tasks[taskId] ?: run {
            Log.w(TAG, "暂停失败：任务不存在 taskId=$taskId")
            return
        }
        task.isPaused.set(true)
        task.status = DownloadStatus.PAUSED
        // 取消所有活跃的 OkHttp Call，立即中断网络 I/O
        task.activeCalls.values.forEach { call ->
            try { call.cancel() } catch (_: Exception) {}
        }
        task.activeCalls.clear()
        if (task.hasPermit.compareAndSet(true, false)) {
            // 任务正在下载中（持有 permit）→ 释放信号量，下载协程会在 isPaused 检查处自然退出
            taskSemaphore.release()
        } else {
            // 任务在排队等待中（阻塞在 acquire()）→ 取消协程，避免占用等待位置
            // 否则协程会在 acquire() 处一直阻塞，直到有 permit 可用时才被唤醒，
            // 短暂"偷"走 permit 后才发现已被暂停，造成调度延迟
            task.job?.cancel()
        }
        task.callback?.onStatusChanged(taskId, DownloadStatus.PAUSED, null)
        Log.d(TAG, "任务已暂停: taskId=$taskId")
    }

    /**
     * 恢复指定任务。
     */
    fun resumeTask(taskId: String) {
        val task = tasks[taskId] ?: run {
            Log.w(TAG, "恢复失败：任务不存在 taskId=$taskId")
            return
        }
        if (task.status != DownloadStatus.PAUSED) {
            Log.w(TAG, "恢复失败：任务不在暂停状态 taskId=$taskId, status=${task.status}")
            return
        }
        // 取消旧的协程 Job，防止恢复后两个协程同时执行同一任务
        task.job?.cancel()
        task.isPaused.set(false)
        task.status = DownloadStatus.PENDING
        task.callback?.onStatusChanged(taskId, DownloadStatus.PENDING, null)
        Log.d(TAG, "任务恢复: taskId=$taskId")

        task.job = scope.launch {
            // 恢复时重新获取信号量
            taskSemaphore.acquire()
            task.hasPermit.set(true)
            try {
                executeTaskBody(task)
            } catch (e: CancellationException) {
                Log.d(TAG, "任务被取消: taskId=${task.taskId}")
            } catch (e: Exception) {
                Log.e(TAG, "任务执行异常: taskId=${task.taskId}, error=${e.message}", e)
                updateStatus(task, DownloadStatus.FAILED, e.message)
            } finally {
                if (task.hasPermit.compareAndSet(true, false)) {
                    taskSemaphore.release()
                }
            }
        }
    }

    /**
     * 取消指定任务。
     */
    fun cancelTask(taskId: String) {
        val task = tasks[taskId] ?: run {
            Log.w(TAG, "取消失败：任务不存在 taskId=$taskId")
            return
        }
        task.isCancelled.set(true)
        task.isPaused.set(false)
        task.job?.cancel()
        task.status = DownloadStatus.CANCELLED
        // 取消所有活跃的 OkHttp Call
        task.activeCalls.values.forEach { call ->
            try { call.cancel() } catch (_: Exception) {}
        }
        task.activeCalls.clear()
        // 仅在任务确实持有 permit 时才释放
        if (task.hasPermit.compareAndSet(true, false)) {
            taskSemaphore.release()
        }
        task.callback?.onStatusChanged(taskId, DownloadStatus.CANCELLED, null)
        cleanupTempFiles(taskId)
        tasks.remove(taskId)
        Log.d(TAG, "任务已取消: taskId=$taskId")
    }

    /**
     * 获取指定任务。
     */
    fun getTask(taskId: String): DownloadTask? = tasks[taskId]

    /**
     * 获取所有活跃任务。
     */
    fun getAllTasks(): List<DownloadTask> = tasks.values.toList()

    // ======================== 任务执行 ========================

    /**
     * 执行下载任务的主流程（获取信号量后调用）。
     */
    private suspend fun executeTask(task: DownloadTask) {
        taskSemaphore.acquire()
        task.hasPermit.set(true)
        try {
            executeTaskBody(task)
        } catch (e: CancellationException) {
            Log.d(TAG, "任务被取消: taskId=${task.taskId}")
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: taskId=${task.taskId}, error=${e.message}", e)
            updateStatus(task, DownloadStatus.FAILED, e.message)
        } finally {
            if (task.hasPermit.compareAndSet(true, false)) {
                taskSemaphore.release()
            }
        }
    }

    /**
     * 下载任务主体逻辑（不含信号量管理）。
     */
    private suspend fun executeTaskBody(task: DownloadTask) {
        // 检查暂停/取消状态
        if (checkInterrupted(task)) return

        // 更新状态为下载中
        updateStatus(task, DownloadStatus.DOWNLOADING)

        try {
            // 1. 解析 m3u8
            if (task.segmentUrls.isEmpty()) {
                Log.d(TAG, "开始解析 m3u8: ${task.m3u8Url}")
                val playlist = m3u8Parser.parse(task.m3u8Url)
                if (playlist == null || playlist.segments.isEmpty()) {
                    updateStatus(task, DownloadStatus.FAILED, "m3u8 解析失败，未找到有效分片")
                    return
                }
                task.segmentUrls = playlist.segments
                task.encryption = playlist.encryption
                Log.d(TAG, "m3u8 解析完成，共 ${playlist.segments.size} 个分片" +
                        (if (playlist.encryption != null) "（AES-128 加密流）" else ""))

                // 加密流：下载解密密钥
                playlist.encryption?.let { encryption ->
                    if (encryption.keyUri != null) {
                        try {
                            task.encryptionKey = downloadKey(encryption.keyUri)
                            Log.d(TAG, "加密密钥下载成功: keyUri=${encryption.keyUri}")
                        } catch (e: Exception) {
                            updateStatus(task, DownloadStatus.FAILED, "解密密钥下载失败: ${e.message}")
                            return
                        }
                    } else {
                        updateStatus(task, DownloadStatus.FAILED, "加密流缺少密钥地址，暂不支持下载")
                        return
                    }
                }
            }

            // 2. 检查存储空间
            if (!checkStorageSpace(task)) {
                updateStatus(task, DownloadStatus.FAILED, "存储空间不足")
                return
            }

            // 3. 创建任务临时目录
            val taskTempDir = File(tempDir, task.taskId)
            taskTempDir.mkdirs()

            // 4. 并发下载所有分片
            downloadSegments(task, taskTempDir)

            // 5. 检查是否全部完成
            if (task.isCancelled.get() || task.isPaused.get()) return

            if (task.downloadedCount.get() < task.segmentUrls.size) {
                updateStatus(task, DownloadStatus.FAILED, "部分分片下载失败")
                return
            }

            // 6. 合并分片
            updateStatus(task, DownloadStatus.MERGING)
            val outputFile = mergeSegments(task, taskTempDir)

            // 7. 合并后再检查一次取消状态（避免取消后被标记为完成）
            if (task.isCancelled.get() || task.isPaused.get()) return

            // 8. 完成
            val fileSize = outputFile.length()
            task.status = DownloadStatus.COMPLETED
            task.callback?.onCompleted(task.taskId, outputFile.absolutePath, fileSize)
            Log.d(TAG, "下载完成: taskId=${task.taskId}, file=${outputFile.absolutePath}, size=$fileSize")

            // 9. 清理临时文件
            cleanupTempFiles(task.taskId)
            tasks.remove(task.taskId)

        } catch (e: CancellationException) {
            Log.d(TAG, "任务被中断: taskId=${task.taskId}")
            if (task.isCancelled.get()) {
                task.status = DownloadStatus.CANCELLED
                task.callback?.onStatusChanged(task.taskId, DownloadStatus.CANCELLED, null)
            } else if (task.isPaused.get()) {
                task.status = DownloadStatus.PAUSED
                task.callback?.onStatusChanged(task.taskId, DownloadStatus.PAUSED, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: taskId=${task.taskId}, error=${e.message}", e)
            updateStatus(task, DownloadStatus.FAILED, e.message)
            cleanupTempFiles(task.taskId)
        }
    }

    /**
     * 并发下载所有分片。
     */
    private suspend fun downloadSegments(task: DownloadTask, taskTempDir: File) {
        val totalSegments = task.segmentUrls.size
        val segmentSemaphore = Semaphore(MAX_CONCURRENT_SEGMENTS)

        coroutineScope {
            task.segmentUrls.forEachIndexed { index, segmentUrl ->
                // 跳过已完成的分片（断点续传）
                if (task.segmentCompleted[index] == true) {
                    return@forEachIndexed
                }

                launch {
                    segmentSemaphore.withPermit {
                        downloadSingleSegment(task, index, segmentUrl, taskTempDir)
                    }
                }
            }
        }
    }

    /**
     * 下载单个分片，支持断点续传和自动重试。
     */
    private suspend fun downloadSingleSegment(
        task: DownloadTask,
        index: Int,
        segmentUrl: String,
        taskTempDir: File
    ) {
        val segmentFile = File(taskTempDir, String.format("%05d.ts", index))
        // 加密流的分片是密文，必须整片下载解密，无法用 Range 断点续传
        val isEncrypted = task.encryption != null
        val existingBytes = if (isEncrypted) 0L else (task.segmentDownloadedBytes[index] ?: 0L)

        // 加密流：清除残留的密文分片，从头完整下载
        if (isEncrypted && segmentFile.exists()) {
            segmentFile.delete()
        }

        // 如果文件已存在且大小匹配，说明已完成
        if (!isEncrypted && segmentFile.exists() && segmentFile.length() == existingBytes && existingBytes > 0
            && task.segmentCompleted[index] == true
        ) {
            return
        }

        repeat(MAX_SEGMENT_RETRIES + 1) { retry ->
            // 每次重试前检查中断状态
            if (task.isCancelled.get() || task.isPaused.get() || !coroutineContext.isActive) {
                return
            }

            try {
                downloadSegmentWithRetry(task, index, segmentUrl, segmentFile, existingBytes)
                // 下载成功，标记完成
                task.segmentCompleted[index] = true
                task.segmentDownloadedBytes[index] = segmentFile.length()
                task.downloadedBytes.addAndGet(segmentFile.length())
                val completedCount = task.downloadedCount.incrementAndGet()

                // 通知进度
                task.callback?.onProgress(
                    task.taskId,
                    completedCount,
                    task.segmentUrls.size,
                    task.downloadedBytes.get()
                )
                Log.d(TAG, "分片下载完成: index=$index, size=${segmentFile.length()}, " +
                        "progress=$completedCount/${task.segmentUrls.size}")

                // 分片间延迟，降低瞬间峰值带宽
                delay(SEGMENT_GAP_MS)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HlsDecryptException) {
                // 解密校验失败（key/IV 不匹配）：重试结果必然相同，直接终止整个任务
                Log.e(TAG, "分片解密校验失败，终止下载: index=$index, ${e.message}")
                throw e
            } catch (e: IOException) {
                // OkHttp Call 被 cancel() 后会抛 IOException("Canceled")，不重试
                if (task.isPaused.get() || task.isCancelled.get()) {
                    Log.d(TAG, "分片 $index 下载被中断（暂停/取消）")
                    return
                }
                Log.w(TAG, "分片下载失败 (index=$index, retry=$retry/${MAX_SEGMENT_RETRIES}): ${e.message}")

                if (retry < MAX_SEGMENT_RETRIES) {
                    // 指数退避重试
                    val delayMs = RETRY_DELAYS[retry]
                    Log.d(TAG, "分片 $index 将在 ${delayMs}ms 后重试")
                    delay(delayMs)
                }
            }
        }

        // 所有重试都失败
        Log.e(TAG, "分片下载最终失败: index=$index, url=$segmentUrl")
    }

    /**
     * 执行单次分片下载。
     *
     * - 非加密流：支持 Range 断点续传，流式写盘
     * - AES-128 加密流：整体下载后解密再写盘（不使用 Range）
     */
    private suspend fun downloadSegmentWithRetry(
        task: DownloadTask,
        index: Int,
        segmentUrl: String,
        segmentFile: File,
        startByte: Long
    ) {
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(segmentUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

            // 加密流：整体下载 → 解密 → 写盘（不发送 Range header）
            if (task.encryption != null) {
                val request = requestBuilder.build()
                val call = okHttpClient.newCall(request)
                task.activeCalls[index] = call
                try {
                    val response = call.execute()
                    try {
                        if (!response.isSuccessful && response.code != 206) {
                            throw IOException("HTTP 请求失败: ${response.code} ${response.message}")
                        }
                        val body = response.body ?: throw IOException("响应体为空")
                        val encryptedBytes = body.bytes()
                        if (task.isCancelled.get() || task.isPaused.get()) {
                            throw CancellationException("下载被中断")
                        }

                        // AES-128-CBC 解密
                        val decryptedBytes = decryptSegment(encryptedBytes, index, task)

                        // 解密后明文写盘（覆盖式，不做追加）
                        FileOutputStream(segmentFile, false).use { outputStream ->
                            outputStream.write(decryptedBytes)
                        }
                        Log.d(TAG, "分片解密完成: index=$index, encrypted=${encryptedBytes.size}B -> plain=${decryptedBytes.size}B")
                    } finally {
                        response.close()
                    }
                } finally {
                    task.activeCalls.remove(index)
                }
                return@withContext
            }

            // 非加密流：Range 断点续传 + 流式写盘（原有逻辑）
            // 断点续传：设置 Range header
            if (startByte > 0 && segmentFile.exists()) {
                requestBuilder.header("Range", "bytes=$startByte-")
                Log.d(TAG, "断点续传: index=$index, from=$startByte")
            }

            val request = requestBuilder.build()
            val call = okHttpClient.newCall(request)
            task.activeCalls[index] = call
            try {
                val response = call.execute()
                try {
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("HTTP 请求失败: ${response.code} ${response.message}")
                    }

                    val body = response.body ?: throw IOException("响应体为空")
                    val inputStream: InputStream = body.byteStream()

                    // 追加写入（断点续传时 append=true）
                    val outputStream = FileOutputStream(segmentFile, startByte > 0 && segmentFile.exists())
                    val buffer = ByteArray(65536)

                    try {
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            // 检查中断状态
                            if (task.isCancelled.get() || task.isPaused.get()) {
                                throw CancellationException("下载被中断")
                            }

                            outputStream.write(buffer, 0, bytesRead)

                            // 更新该分片的已下载字节数
                            val currentSize = segmentFile.length()
                            task.segmentDownloadedBytes[index] = currentSize
                        }

                        outputStream.flush()
                    } finally {
                        outputStream.close()
                        inputStream.close()
                    }
                } finally {
                    response.close()
                }
            } finally {
                task.activeCalls.remove(index)
            }
        }
    }

    /**
     * 下载 AES-128 解密密钥（16 字节）。
     */
    private fun downloadKey(keyUri: String): ByteArray {
        val request = Request.Builder()
            .url(keyUri)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer", keyUri.substringBeforeLast("/"))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP 请求失败: ${response.code} ${response.message}")
            }
            val bytes = response.body?.bytes() ?: throw IOException("响应体为空")
            if (bytes.size != 16) {
                throw IOException("密钥长度异常: ${bytes.size} 字节（期望 16）")
            }
            return bytes
        }
    }

    /**
     * 对单个分片执行 AES-128-CBC 解密。
     *
     * IV 优先级：显式 IV（m3u8 的 IV=0x...）> 分片序号（RFC 8216 默认值）。
     * 首分片（index=0）解密后会校验明文是否为合法媒体容器，防止 key/IV 错误时
     * 静默产出乱码文件（AES-CBC 在 key/IV 错误时通常不抛异常）。
     *
     * @throws HlsDecryptException 解密结果校验失败（密钥或 IV 与源不匹配，重试无意义）
     */
    private fun decryptSegment(encrypted: ByteArray, index: Int, task: DownloadTask): ByteArray {
        val encryption = task.encryption ?: return encrypted
        val key = task.encryptionKey ?: throw IOException("缺少解密密钥")
        val iv = encryption.iv ?: defaultIv(index)
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(encrypted)
            if (index == 0) {
                validateDecryptedContainer(decrypted)
            }
            return decrypted
        } catch (e: HlsDecryptException) {
            throw e
        } catch (e: Exception) {
            throw IOException("分片解密失败 (index=$index): ${e.message}", e)
        }
    }

    /**
     * 校验解密后的首分片是否为合法媒体容器。
     *
     * 合法情况：
     * - MPEG-TS：同步字节 0x47，且偏移 188 / 376 处重复出现（每 188 字节一个 TS 包）
     * - MP4/fMP4：偏移 4 处为 "ftyp"（box size + type）
     *
     * 均不匹配说明密钥或 IV 与流不匹配（解出乱码），抛 [HlsDecryptException]。
     *
     * 注意：分片过小（< 188 字节，不足一个 TS 包）时无法可靠校验同步字节，
     * 直接判失败，避免首字节恰好为 0x47 的小乱码文件被误判通过。
     */
    private fun validateDecryptedContainer(plain: ByteArray) {
        if (plain.isEmpty()) {
            throw HlsDecryptException("解密结果无效（密钥或 IV 与源不匹配）")
        }
        // MP4/fMP4：偏移 4 处为 "ftyp"（至少 8 字节：4 字节 size + 4 字节 "ftyp"）
        val isMp4 = plain.size >= 8 &&
            plain[4] == 'f'.code.toByte() && plain[5] == 't'.code.toByte() &&
            plain[6] == 'y'.code.toByte() && plain[7] == 'p'.code.toByte()
        if (isMp4) return

        // MPEG-TS：必须至少 188 字节（一个完整 TS 包）才能校验同步字节重复
        // 分片不足 188 字节时，单凭首字节 0x47 无法区分合法 TS 与恰好首字节匹配的乱码，判失败
        if (plain.size < 188) {
            throw HlsDecryptException("解密结果无效（密钥或 IV 与源不匹配）：分片过小（${plain.size} 字节，不足一个 TS 包）")
        }
        // 0x47 开头，且偏移 188 / 376 处仍为同步字节
        val isTs = plain[0].toInt() == 0x47 &&
            plain[188].toInt() == 0x47 &&
            (plain.size < 376 || plain[376].toInt() == 0x47)
        if (!isTs) {
            throw HlsDecryptException("解密结果无效（密钥或 IV 与源不匹配）")
        }
    }

    /**
     * 计算默认 IV：分片序号（0 起）作为 128 位大端整数（RFC 8216）。
     */
    private fun defaultIv(index: Int): ByteArray {
        val iv = ByteArray(16)
        var value = index.toLong()
        for (i in 0 until 8) {
            iv[15 - i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    // ======================== 合并与清理 ========================

    /**
     * 合并所有分片为最终的 mp4 文件。
     *
     * 任一分片缺失即抛 [IOException] 判失败，避免产出缺数据的坏文件。
     */
    private suspend fun mergeSegments(task: DownloadTask, taskTempDir: File): File {
        return withContext(Dispatchers.IO) {
            val fileName = sanitizeFileName("${task.videoTitle}_${task.episodeTitle}_${task.taskId}.mp4")
            val outputFile = File(downloadDir, fileName)

            // 如果目标文件已存在，先删除
            if (outputFile.exists()) {
                outputFile.delete()
            }

            Log.d(TAG, "开始合并分片: task=${task.taskId}, segments=${task.segmentUrls.size}, " +
                    "output=${outputFile.absolutePath}")

            // 合并前预检：任一分片缺失直接判失败，不产出坏文件
            val missingSegments = mutableListOf<Int>()
            for (i in task.segmentUrls.indices) {
                val segmentFile = File(taskTempDir, String.format("%05d.ts", i))
                if (!segmentFile.exists() || segmentFile.length() == 0L) {
                    missingSegments.add(i)
                }
            }
            if (missingSegments.isNotEmpty()) {
                throw IOException("合并失败：缺失 ${missingSegments.size} 个分片（首个缺失 index=${missingSegments.first()}），已终止合并，不产出坏文件")
            }

            FileOutputStream(outputFile).use { fos ->
                for (i in task.segmentUrls.indices) {
                    val segmentFile = File(taskTempDir, String.format("%05d.ts", i))

                    segmentFile.inputStream().use { sis ->
                        val buffer = ByteArray(524288)
                        var bytesRead: Int
                        while (sis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }

                    if (i % 20 == 0 || i == task.segmentUrls.lastIndex) {
                        Log.d(TAG, "合并分片: $i/${task.segmentUrls.size}")
                    }
                }
                fos.flush()
            }

            Log.d(TAG, "合并完成: ${outputFile.absolutePath}, size=${outputFile.length()}")
            outputFile
        }
    }

    /**
     * 清理任务的临时文件。
     */
    private fun cleanupTempFiles(taskId: String) {
        val taskTempDir = File(tempDir, taskId)
        if (taskTempDir.exists()) {
            val deleted = taskTempDir.deleteRecursively()
            Log.d(TAG, "清理临时文件: taskId=$taskId, deleted=$deleted")
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 检查任务是否被中断（暂停或取消）。
     * @return true 表示被中断
     */
    private fun checkInterrupted(task: DownloadTask): Boolean {
        if (task.isCancelled.get()) {
            task.status = DownloadStatus.CANCELLED
            task.callback?.onStatusChanged(task.taskId, DownloadStatus.CANCELLED, null)
            return true
        }
        if (task.isPaused.get()) {
            task.status = DownloadStatus.PAUSED
            task.callback?.onStatusChanged(task.taskId, DownloadStatus.PAUSED, null)
            return true
        }
        return false
    }

    /**
     * 更新任务状态并通知回调。
     */
    private fun updateStatus(task: DownloadTask, status: Int, errorMsg: String? = null) {
        task.status = status
        task.callback?.onStatusChanged(task.taskId, status, errorMsg)
        Log.d(TAG, "任务状态变更: taskId=${task.taskId}, status=$status, msg=$errorMsg")
    }

    /**
     * 检查存储空间是否足够。
     * 保守估计：每个分片约 2MB，加上 MIN_FREE_SPACE_BYTES 的缓冲。
     */
    private fun checkStorageSpace(task: DownloadTask): Boolean {
        return try {
            val stat = StatFs(appContext.filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            // 保守估计每个分片 2MB
            val estimatedSize = task.segmentUrls.size * 2L * 1024 * 1024
            val required = estimatedSize + MIN_FREE_SPACE_BYTES

            if (availableBytes < required) {
                Log.w(TAG, "存储空间不足: 可用=${availableBytes / 1024 / 1024}MB, " +
                        "需要=${required / 1024 / 1024}MB")
                false
            } else {
                Log.d(TAG, "存储空间检查通过: 可用=${availableBytes / 1024 / 1024}MB")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查存储空间异常: ${e.message}，默认允许继续")
            true
        }
    }

    /**
     * 清理文件名中的非法字符。
     */
    private fun sanitizeFileName(name: String): String {
        val illegalChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        var sanitized = name
        for (c in illegalChars) {
            sanitized = sanitized.replace(c, '_')
        }
        return sanitized.trim()
    }
}
