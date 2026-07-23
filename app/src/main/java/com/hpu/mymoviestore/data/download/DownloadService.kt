package com.hpu.mymoviestore.data.download

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.hpu.mymoviestore.MovieApplication
import kotlinx.coroutines.*

/**
 * 下载前台服务
 *
 * 职责：
 * - 作为前台服务运行，显示下载进度通知
 * - 通过 DownloadNotificationManager 管理通知的创建和更新
 * - 监听下载引擎的进度和状态回调，实时更新通知
 * - 通知内容：下载进度百分比、速度、任务数量
 * - 通知操作按钮：暂停全部 / 继续全部
 * - 下载完成时发送完成通知
 * - 当所有任务完成时自动停止服务
 *
 * 使用方式：
 * - startService() 启动服务
 * - 服务通过 startForeground() 显示前台通知
 * - 所有任务完成后自动调用 stopForeground() + stopSelf()
 *
 * 通知渠道 ID：download_channel
 * 通知 ID：NOTIFICATION_ID = 1001
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"

        /** 通知 ID（与 DownloadNotificationManager 保持一致） */
        const val NOTIFICATION_ID = DownloadNotificationManager.NOTIFICATION_ID

        /** 进度刷新间隔（毫秒） */
        private const val PROGRESS_UPDATE_INTERVAL = 1000L

        /** 速度计算窗口（毫秒） */
        private const val SPEED_CALC_WINDOW = 3000L
    }

    /** 通知管理器 */
    private lateinit var notificationManager: DownloadNotificationManager

    /** 下载引擎 */
    private lateinit var downloadEngine: DownloadEngine

    /** CPU 唤醒锁，保证屏幕关闭后下载协程继续执行 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 协程作用域 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 进度刷新 Job */
    private var progressUpdateJob: Job? = null

    /** 上一次统计的总下载字节数（用于计算速度） */
    private var lastDownloadedBytes: Long = 0L

    /** 上一次速度计算的时间戳 */
    private var lastSpeedCalcTime: Long = 0L

    /** 当前计算出的下载速度（字节/秒） */
    private var currentSpeedBytesPerSec: Long = 0L

    /** 是否有任何任务正在下载 */
    private var isAnyDownloading: Boolean = false

    /** 已完成的任务计数 */
    private var completedTaskCount: Int = 0

    /** 上一轮检查时的活跃任务数（用于检测任务完成） */
    private var lastActiveTaskCount: Int = 0

    /** 暂停全部/继续全部 广播接收器 */
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadNotificationManager.ACTION_PAUSE_ALL -> {
                    Log.d(TAG, "收到广播：暂停全部")
                    pauseAllTasks()
                }
                DownloadNotificationManager.ACTION_RESUME_ALL -> {
                    Log.d(TAG, "收到广播：继续全部")
                    resumeAllTasks()
                }
            }
        }
    }

    // ======================== Service 生命周期 ========================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DownloadService onCreate")

        val app = application as MovieApplication
        notificationManager = DownloadNotificationManager(this)
        downloadEngine = DownloadEngine.getInstance(this)

        // 获取 CPU 唤醒锁，确保屏幕关闭/深度睡眠时下载协程仍能继续执行
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyMovieStore:DownloadWakeLock"
        ).apply { acquire(30 * 60 * 1000L) }
        Log.d(TAG, "已获取 PARTIAL_WAKE_LOCK")

        // 注册广播接收器（暂停全部 / 继续全部），Android 14+ 需要指定 RECEIVER_NOT_EXPORTED
        val filter = IntentFilter().apply {
            addAction(DownloadNotificationManager.ACTION_PAUSE_ALL)
            addAction(DownloadNotificationManager.ACTION_RESUME_ALL)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        // 启动进度刷新定时器
        startProgressUpdateLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DownloadService onStartCommand")

        val initialNotification = notificationManager.buildProgressNotification(
            progress = 0,
            speed = "0 B/s",
            taskCount = getActiveTaskCount(),
            isDownloading = true
        )
        startForeground(NOTIFICATION_ID, initialNotification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DownloadService onDestroy")

        // 取消进度刷新
        progressUpdateJob?.cancel()

        // 释放唤醒锁
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "已释放 PARTIAL_WAKE_LOCK")
            }
        }
        wakeLock = null

        // 注销广播接收器
        try {
            unregisterReceiver(actionReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "注销广播接收器失败: ${e.message}")
        }

        // 取消通知
        notificationManager.cancelProgressNotification()
    }

    // ======================== 进度更新 ========================

    /**
     * 启动进度刷新定时循环
     *
     * 每隔 PROGRESS_UPDATE_INTERVAL 毫秒检查一次所有活跃任务的进度，
     * 计算总体进度百分比和下载速度，然后更新前台通知。
     * 当所有任务完成后自动停止服务。
     */
    private fun startProgressUpdateLoop() {
        lastSpeedCalcTime = System.currentTimeMillis()
        lastDownloadedBytes = calculateTotalDownloadedBytes()

        progressUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_UPDATE_INTERVAL)

                val tasks = downloadEngine.getAllTasks()
                if (tasks.isEmpty()) {
                    // 没有活跃任务，停止服务
                    Log.d(TAG, "没有活跃任务，停止服务")
                    stopSelf()
                    break
                }

                // 计算总体进度
                val totalProgress = calculateOverallProgress(tasks)
                val taskCount = tasks.size

                // 计算下载速度
                calculateSpeed()

                // 检查是否有任务正在下载
                isAnyDownloading = tasks.any {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MERGING
                }

                // 更新前台通知
                val speedText = formatSpeed(currentSpeedBytesPerSec)
                notificationManager.updateProgressNotification(
                    progress = totalProgress,
                    speed = speedText,
                    taskCount = taskCount,
                    isDownloading = isAnyDownloading
                )

                // 检查是否所有任务都已完成（不在活跃列表中）
                checkAllTasksCompleted(tasks)
            }
        }
    }

    /**
     * 计算所有活跃任务的整体进度百分比（0 ~ 100）
     */
    private fun calculateOverallProgress(tasks: List<DownloadTask>): Int {
        if (tasks.isEmpty()) return 0

        var totalSegments = 0
        var totalDownloaded = 0

        for (task in tasks) {
            totalSegments += task.segmentUrls.size
            totalDownloaded += task.downloadedCount.get()
        }

        if (totalSegments == 0) return 0

        return ((totalDownloaded.toLong() * 100) / totalSegments).toInt().coerceIn(0, 100)
    }

    /**
     * 计算当前下载速度
     */
    private fun calculateSpeed() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSpeedCalcTime

        if (elapsed >= SPEED_CALC_WINDOW) {
            val currentBytes = calculateTotalDownloadedBytes()
            val delta = currentBytes - lastDownloadedBytes

            if (delta > 0 && elapsed > 0) {
                currentSpeedBytesPerSec = (delta * 1000) / elapsed
            } else {
                currentSpeedBytesPerSec = 0
            }

            lastDownloadedBytes = currentBytes
            lastSpeedCalcTime = now
        }
    }

    /**
     * 计算所有活跃任务的总已下载字节数
     */
    private fun calculateTotalDownloadedBytes(): Long {
        var total = 0L
        for (task in downloadEngine.getAllTasks()) {
            total += task.downloadedBytes.get()
        }
        return total
    }

    /**
     * 获取当前活跃任务数量
     */
    private fun getActiveTaskCount(): Int {
        return downloadEngine.getAllTasks().size
    }

    /**
     * 检查是否所有任务都已完成
     *
     * 如果所有任务都不在活跃列表中（已被移除），
     * 说明全部下载完成，发送完成通知并停止服务。
     */
    private fun checkAllTasksCompleted(currentTasks: List<DownloadTask>) {
        // 检测任务完成：活跃任务数减少说明有任务完成了
        if (currentTasks.size < lastActiveTaskCount) {
            val completed = lastActiveTaskCount - currentTasks.size
            completedTaskCount += completed
            Log.d(TAG, "检测到 $completed 个任务完成，累计完成: $completedTaskCount")
        }
        lastActiveTaskCount = currentTasks.size

        val hasActiveTasks = currentTasks.any {
            it.status == DownloadStatus.PENDING ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.MERGING
        }

        // 没有正在下载/等待/合并的任务时，停止前台服务
        if (!hasActiveTasks) {
            if (completedTaskCount > 0) {
                Log.d(TAG, "所有下载任务已完成，发送完成通知并停止服务")
                notificationManager.showAllDownloadsCompletedNotification(completedTaskCount)
                completedTaskCount = 0
            } else {
                Log.d(TAG, "没有活跃的下载任务，停止前台服务")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ======================== 任务控制 ========================

    /**
     * 暂停所有任务
     */
    private fun pauseAllTasks() {
        val tasks = downloadEngine.getAllTasks()
        for (task in tasks) {
            if (task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.PENDING
            ) {
                downloadEngine.pauseTask(task.taskId)
            }
        }

        // 同时通知 Repository 暂停所有
        serviceScope.launch(Dispatchers.IO) {
            try {
                val app = application as MovieApplication
                app.downloadRepository.pauseAll()
            } catch (t: Throwable) {
                Log.w(TAG, "通知 Repository 暂停全部失败: ${t.message}")
            }
        }

        // 立即更新通知
        notificationManager.updateProgressNotification(
            progress = calculateOverallProgress(downloadEngine.getAllTasks()),
            speed = "已暂停",
            taskCount = downloadEngine.getAllTasks().size,
            isDownloading = false
        )
    }

    /**
     * 恢复所有暂停的任务
     */
    private fun resumeAllTasks() {
        val tasks = downloadEngine.getAllTasks()
        for (task in tasks) {
            if (task.status == DownloadStatus.PAUSED) {
                downloadEngine.resumeTask(task.taskId)
            }
        }

        // 同时通知 Repository 恢复所有
        serviceScope.launch(Dispatchers.IO) {
            try {
                val app = application as MovieApplication
                app.downloadRepository.resumeAll()
            } catch (t: Throwable) {
                Log.w(TAG, "通知 Repository 恢复全部失败: ${t.message}")
            }
        }

        // 重置速度计算
        lastSpeedCalcTime = System.currentTimeMillis()
        lastDownloadedBytes = calculateTotalDownloadedBytes()
        currentSpeedBytesPerSec = 0L
    }

    // ======================== 工具方法 ========================

    /**
     * 格式化下载速度
     */
    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 B/s"
        return when {
            bytesPerSec < 1024 -> String.format("%d B/s", bytesPerSec)
            bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            bytesPerSec < 1024 * 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> String.format("%.2f GB/s", bytesPerSec / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
