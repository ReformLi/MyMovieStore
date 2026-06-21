package com.hpu.mymoviestore.data.download

import android.content.Context
import android.os.StatFs
import android.util.Log
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

        /** 每个任务最大并发分片数（降低并发以保护 CDN） */
        private const val MAX_CONCURRENT_SEGMENTS = 3

        /** 分片下载最大重试次数 */
        private const val MAX_SEGMENT_RETRIES = 3

        /** 分片间延迟（毫秒），降低瞬间峰值带宽 */
        private const val SEGMENT_GAP_MS = 2000L

        /** 下载速度限制（字节/秒），约 2MB/s */
        private const val MAX_DOWNLOAD_SPEED_BPS = 2L * 1024 * 1024

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

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

        // 如果引擎中已存在同 ID 的旧任务，先取消其协程 Job，防止重复执行
        tasks[finalTaskId]?.let { oldTask ->
            Log.w(TAG, "提交任务时发现旧任务仍存在，先取消: taskId=$finalTaskId, oldStatus=${oldTask.status}")
            oldTask.job?.cancel()
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
        tasks[finalTaskId] = task

        Log.d(TAG, "提交下载任务: taskId=$finalTaskId, video=$videoTitle, episode=$episodeTitle")

        task.job = scope.launch {
            executeTask(task)
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
                val segments = m3u8Parser.parse(task.m3u8Url)
                if (segments.isNullOrEmpty()) {
                    updateStatus(task, DownloadStatus.FAILED, "m3u8 解析失败，未找到有效分片")
                    return
                }
                task.segmentUrls = segments
                Log.d(TAG, "m3u8 解析完成，共 ${segments.size} 个分片")
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

            // 7. 完成
            val fileSize = outputFile.length()
            task.status = DownloadStatus.COMPLETED
            task.callback?.onCompleted(task.taskId, outputFile.absolutePath, fileSize)
            Log.d(TAG, "下载完成: taskId=${task.taskId}, file=${outputFile.absolutePath}, size=$fileSize")

            // 8. 清理临时文件
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
        val existingBytes = task.segmentDownloadedBytes[index] ?: 0L

        // 如果文件已存在且大小匹配，说明已完成
        if (segmentFile.exists() && segmentFile.length() == existingBytes && existingBytes > 0
            && task.segmentCompleted[index] == true
        ) {
            return
        }

        var lastRetry = 0

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
            } catch (e: IOException) {
                // OkHttp Call 被 cancel() 后会抛 IOException("Canceled")，不重试
                if (task.isPaused.get() || task.isCancelled.get()) {
                    Log.d(TAG, "分片 $index 下载被中断（暂停/取消）")
                    return
                }
                lastRetry = retry
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
     * 执行单次分片下载（支持 Range 断点续传）。
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

            // 断点续传：设置 Range header
            if (startByte > 0 && segmentFile.exists()) {
                requestBuilder.header("Range", "bytes=$startByte-")
                Log.d(TAG, "断点续传: index=$index, from=$startByte")
            }

            val request = requestBuilder.build()
            val call = okHttpClient.newCall(request)
            task.activeCalls[index] = call
            val response = call.execute()
            task.activeCalls.remove(index)

            try {
                if (!response.isSuccessful && response.code != 206) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }

                val body = response.body ?: throw IOException("响应体为空")
                val inputStream: InputStream = body.byteStream()

                // 追加写入（断点续传时 append=true）
                val outputStream = FileOutputStream(segmentFile, startByte > 0 && segmentFile.exists())
                val buffer = ByteArray(8192)

                try {
                    var bytesRead: Int
                    var bytesSinceLastThrottle: Long = 0
                    var throttleStartMs = System.currentTimeMillis()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        // 检查中断状态
                        if (task.isCancelled.get() || task.isPaused.get()) {
                            throw CancellationException("下载被中断")
                        }

                        outputStream.write(buffer, 0, bytesRead)

                        // 速度限制：每读取约 64KB 检查一次是否需要限速
                        bytesSinceLastThrottle += bytesRead
                        if (bytesSinceLastThrottle >= 65536) {
                            val elapsedMs = System.currentTimeMillis() - throttleStartMs
                            val expectedMs = bytesSinceLastThrottle * 1000 / MAX_DOWNLOAD_SPEED_BPS
                            if (elapsedMs < expectedMs) {
                                Thread.sleep(expectedMs - elapsedMs)
                            }
                            bytesSinceLastThrottle = 0
                            throttleStartMs = System.currentTimeMillis()
                        }

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
        }
    }

    // ======================== 合并与清理 ========================

    /**
     * 合并所有分片为最终的 mp4 文件。
     */
    private suspend fun mergeSegments(task: DownloadTask, taskTempDir: File): File {
        return withContext(Dispatchers.IO) {
            val fileName = sanitizeFileName("${task.videoTitle}_${task.episodeTitle}.mp4")
            val outputFile = File(downloadDir, fileName)

            // 如果目标文件已存在，先删除
            if (outputFile.exists()) {
                outputFile.delete()
            }

            Log.d(TAG, "开始合并分片: task=${task.taskId}, segments=${task.segmentUrls.size}, " +
                    "output=${outputFile.absolutePath}")

            FileOutputStream(outputFile).use { fos ->
                for (i in task.segmentUrls.indices) {
                    val segmentFile = File(taskTempDir, String.format("%05d.ts", i))
                    if (!segmentFile.exists()) {
                        Log.w(TAG, "合并时发现缺失分片: index=$i, file=${segmentFile.absolutePath}")
                        continue
                    }

                    segmentFile.inputStream().use { sis ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (sis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }

                    Log.d(TAG, "合并分片: $i/${task.segmentUrls.size}")
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
