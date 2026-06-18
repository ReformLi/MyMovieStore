package com.hpu.mymoviestore.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hpu.mymoviestore.R
import java.util.Locale

/**
 * 下载通知管理器
 *
 * 职责：
 * - 创建通知渠道（Android 8.0+）
 * - 更新下载进度通知（百分比、速度、任务数量）
 * - 显示单个任务下载完成通知
 * - 显示所有下载完成通知
 * - 取消通知
 *
 * 通知渠道 ID：download_channel
 * 通知 ID 常量：NOTIFICATION_ID = 1001
 */
class DownloadNotificationManager(private val context: Context) {

    companion object {
        /** 通知渠道 ID */
        const val CHANNEL_ID = "download_channel"

        /** 下载进度通知 ID（前台服务使用） */
        const val NOTIFICATION_ID = 1001

        /** 下载完成通知 ID 起始值（每个任务 +1） */
        private const val COMPLETED_NOTIFICATION_ID_START = 2001

        /** 所有下载完成通知 ID */
        const val ALL_COMPLETED_NOTIFICATION_ID = 3001

        /** 通知渠道名称 */
        private const val CHANNEL_NAME = "下载管理"

        /** 通知渠道描述 */
        private const val CHANNEL_DESCRIPTION = "显示视频下载进度和完成状态"

        /** 暂停全部 action 标识 */
        const val ACTION_PAUSE_ALL = "com.hpu.mymoviestore.download.ACTION_PAUSE_ALL"

        /** 继续全部 action 标识 */
        const val ACTION_RESUME_ALL = "com.hpu.mymoviestore.download.ACTION_RESUME_ALL"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建下载进度通知
     *
     * @param progress       下载进度百分比（0 ~ 100）
     * @param speed          当前下载速度（格式化字符串，如 "1.2 MB/s"）
     * @param taskCount      当前活跃任务数量
     * @param isDownloading  是否有任务正在下载（true = 显示暂停按钮，false = 显示继续按钮）
     * @return 构建好的 Notification 对象
     */
    fun buildProgressNotification(
        progress: Int,
        speed: String,
        taskCount: Int,
        isDownloading: Boolean
    ): Notification {
        val contentText = buildProgressContentText(progress, speed, taskCount)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                context.resources,
                R.mipmap.ic_launcher
            ))
            .setContentTitle("正在下载")
            .setContentText(contentText)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(createMainActivityIntent())

        // 添加操作按钮：暂停全部 或 继续全部
        if (isDownloading) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "暂停全部",
                createPauseAllPendingIntent()
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "继续全部",
                createResumeAllPendingIntent()
            )
        }

        return builder.build()
    }

    /**
     * 更新下载进度通知
     */
    fun updateProgressNotification(
        progress: Int,
        speed: String,
        taskCount: Int,
        isDownloading: Boolean
    ) {
        val notification = buildProgressNotification(progress, speed, taskCount, isDownloading)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 显示单个任务下载完成通知
     *
     * @param title    视频标题
     * @param episode  集数标题
     * @param taskId   任务 ID（用于生成唯一通知 ID）
     */
    fun showDownloadCompletedNotification(title: String, episode: String, taskId: String) {
        val contentText = if (episode.isNotEmpty()) {
            "$title - $episode 下载完成"
        } else {
            "$title 下载完成"
        }

        val notificationId = generateCompletedNotificationId(taskId)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                context.resources,
                R.mipmap.ic_launcher
            ))
            .setContentTitle("下载完成")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(createMainActivityIntent())
            .build()

        notificationManager.notify(notificationId, notification)
    }

    /**
     * 显示所有下载完成通知
     *
     * @param completedCount 完成的任务数量
     */
    fun showAllDownloadsCompletedNotification(completedCount: Int) {
        val contentText = "全部 $completedCount 个任务下载完成"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                context.resources,
                R.mipmap.ic_launcher
            ))
            .setContentTitle("下载全部完成")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(createMainActivityIntent())
            .build()

        notificationManager.notify(ALL_COMPLETED_NOTIFICATION_ID, notification)
    }

    /**
     * 取消指定 ID 的通知
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * 取消下载进度通知（前台服务停止时调用）
     */
    fun cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    // ======================== 私有方法 ========================

    /**
     * 构建进度通知的内容文本
     */
    private fun buildProgressContentText(progress: Int, speed: String, taskCount: Int): String {
        return String.format(Locale.getDefault(), "%d%% | %s | %d个任务", progress, speed, taskCount)
    }

    /**
     * 创建点击通知后打开 MainActivity 的 PendingIntent
     */
    private fun createMainActivityIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return PendingIntent.getActivity(
                context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
            )

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 创建"暂停全部"按钮的 PendingIntent
     */
    private fun createPauseAllPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_PAUSE_ALL).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 创建"继续全部"按钮的 PendingIntent
     */
    private fun createResumeAllPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_RESUME_ALL).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 根据 taskId 生成唯一的完成通知 ID
     */
    private fun generateCompletedNotificationId(taskId: String): Int {
        // 使用 taskId 的 hashCode，确保范围在合理区间
        val hash = taskId.hashCode()
        return COMPLETED_NOTIFICATION_ID_START + (hash and 0x7FFFFFFF) % 500
    }
}
