package com.hpu.mymoviestore.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.hpu.mymoviestore.data.dao.DownloadedVideoIndexDao
import com.hpu.mymoviestore.data.dao.DownloadTaskDao
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import java.io.File

/**
 * 下载管理仓库
 *
 * 封装 DownloadTaskDao 和 DownloadedVideoIndexDao，
 * 对上层提供下载任务的创建、查询、控制和删除功能。
 */
class DownloadRepository(
    private val taskDao: DownloadTaskDao,
    private val indexDao: DownloadedVideoIndexDao
) {

    companion object {
        private const val TAG = "DownloadRepository"
    }

    // ======================== 查询 ========================

    /** 下载中的任务（PENDING / DOWNLOADING / PAUSED / FAILED） */
    fun getDownloadingTasks(): LiveData<List<DownloadTaskEntity>> =
        taskDao.getDownloading().asLiveData()

    /** 已完成的任务 */
    fun getCompletedTasks(): LiveData<List<DownloadTaskEntity>> =
        taskDao.getCompleted().asLiveData()

    /** 获取所有等待/下载中的任务列表 */
    suspend fun getActiveTasks(): List<DownloadTaskEntity> =
        taskDao.getByStatusesList(
            listOf(
                DownloadTaskEntity.STATUS_PENDING,
                DownloadTaskEntity.STATUS_DOWNLOADING
            )
        )

    /** 根据 taskId 查询单个任务 */
    suspend fun getTaskById(taskId: String): DownloadTaskEntity? =
        taskDao.getByTaskId(taskId)

    /** 检查某集是否已下载 */
    suspend fun isEpisodeDownloaded(videoId: Long, episodeIndex: Int): Boolean {
        val taskId = "${videoId}_$episodeIndex"
        return taskDao.getByTaskId(taskId)?.status == DownloadTaskEntity.STATUS_COMPLETED
    }

    /** 获取某视频的所有已下载集数 */
    suspend fun getDownloadedEpisodes(videoId: Long): List<DownloadTaskEntity> =
        taskDao.getByVideoIdAndStatus(videoId, DownloadTaskEntity.STATUS_COMPLETED)

    /** 获取某视频的所有下载任务（任意状态） */
    suspend fun getTasksByVideoId(videoId: Long): List<DownloadTaskEntity> =
        taskDao.getByVideoId(videoId)

    // ======================== 创建任务 ========================

    /**
     * 创建单个下载任务
     *
     * @return 生成的 taskId
     */
    suspend fun createTask(
        videoId: Long,
        title: String,
        coverUrl: String,
        sourceName: String,
        episodeIndex: Int,
        episodeTitle: String,
        playUrl: String
    ): String {
        val taskId = "${videoId}_$episodeIndex"
        val existing = taskDao.getByTaskId(taskId)
        if (existing != null) {
            Log.d(TAG, "任务已存在，跳过创建: taskId=$taskId, status=${existing.status}")
            return taskId
        }

        val now = System.currentTimeMillis()
        val task = DownloadTaskEntity(
            taskId = taskId,
            videoId = videoId,
            title = title,
            episodeTitle = episodeTitle,
            coverUrl = coverUrl,
            playUrl = playUrl,
            localFilePath = "",
            totalSegments = 0,
            downloadedSegments = 0,
            fileSize = 0L,
            status = DownloadTaskEntity.STATUS_PENDING,
            errorMsg = "",
            createTime = now,
            updateTime = now,
            sourceName = sourceName,
            danmakuFilePath = "",
            danmakuStatus = DownloadTaskEntity.DANMAKU_NOT_DOWNLOADED,
            danmakuRetryCount = 0,
            danmakuError = ""
        )
        taskDao.insert(task)
        Log.d(TAG, "创建下载任务: taskId=$taskId, title=$title, episode=$episodeTitle")
        return taskId
    }

    // ======================== 任务控制 ========================

    suspend fun pauseTask(taskId: String) {
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_PAUSED)
    }

    suspend fun resumeTask(taskId: String) {
        // 恢复后任务进入排队状态，等引擎真正开始执行时再通过回调改为 DOWNLOADING
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_PENDING)
    }

    suspend fun cancelTask(taskId: String) {
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_CANCELLED, "用户取消")
    }

    suspend fun retryTask(taskId: String) {
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_PENDING)
        taskDao.updateProgress(taskId, 0, 0, 0L)
        taskDao.updateDanmakuStatus(taskId, DownloadTaskEntity.DANMAKU_NOT_DOWNLOADED)
    }

    suspend fun pauseAll() {
        taskDao.updateStatuses(
            listOf(DownloadTaskEntity.STATUS_PENDING, DownloadTaskEntity.STATUS_DOWNLOADING),
            DownloadTaskEntity.STATUS_PAUSED
        )
    }

    suspend fun resumeAll() {
        // 恢复所有暂停任务：先进入排队状态，引擎会逐个执行
        taskDao.updateStatuses(
            listOf(DownloadTaskEntity.STATUS_PAUSED),
            DownloadTaskEntity.STATUS_PENDING
        )
    }

    // ======================== 进度更新 ========================

    suspend fun updateProgress(taskId: String, downloadedSegments: Int, totalSegments: Int, fileSize: Long) {
        taskDao.updateProgress(taskId, downloadedSegments, totalSegments, fileSize)
    }

    suspend fun markDownloading(taskId: String) {
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_DOWNLOADING)
    }

    suspend fun markCompleted(taskId: String, localFilePath: String, fileSize: Long) {
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_COMPLETED)
        taskDao.updateLocalFilePath(taskId, localFilePath)
        taskDao.updateProgress(taskId, downloadedSegments = 1, totalSegments = 1, fileSize = fileSize)
        Log.d(TAG, "任务下载完成: taskId=$taskId, size=${fileSize}")
    }

    suspend fun markFailed(taskId: String, errorMsg: String = "") {
        taskDao.updateStatus(taskId, DownloadTaskEntity.STATUS_FAILED, errorMsg)
    }

    /** 更新任务状态（通用方法，用于 MERGING 等自定义状态） */
    suspend fun updateStatus(taskId: String, status: Int, errorMsg: String = "") {
        taskDao.updateStatus(taskId, status, errorMsg)
    }

    // ======================== 弹幕状态 ========================

    suspend fun updateDanmakuStatus(taskId: String, danmakuStatus: Int, danmakuFilePath: String = "", danmakuError: String = "") {
        taskDao.updateDanmakuStatus(taskId, danmakuStatus, danmakuFilePath, danmakuError)
    }

    // ======================== 离线播放进度 ========================

    suspend fun updateOfflinePlayProgress(taskId: String, percent: Int, positionMs: Long, durationMs: Long) {
        taskDao.updatePlayProgress(taskId, percent, positionMs, durationMs)
    }

    // ======================== 删除 ========================

    suspend fun deleteTask(taskId: String) {
        val task = taskDao.getByTaskId(taskId)
        if (task != null) {
            deleteLocalFiles(task)
            taskDao.deleteByTaskId(taskId)
            Log.d(TAG, "删除任务: taskId=$taskId")
        }
    }

    suspend fun deleteTasks(taskIds: List<String>) {
        taskIds.forEach { taskId ->
            try { deleteTask(taskId) } catch (t: Throwable) {
                Log.w(TAG, "批量删除中某个任务失败: taskId=$taskId, error=${t.message}")
            }
        }
    }

    private fun deleteLocalFiles(task: DownloadTaskEntity) {
        try {
            if (task.localFilePath.isNotEmpty()) {
                val file = File(task.localFilePath)
                if (file.exists() && file.delete()) {
                    Log.d(TAG, "删除 mp4 文件: ${file.absolutePath}")
                }
            }
            if (task.danmakuFilePath.isNotEmpty()) {
                val file = File(task.danmakuFilePath)
                if (file.exists() && file.delete()) {
                    Log.d(TAG, "删除弹幕文件: ${file.absolutePath}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "删除本地文件失败: taskId=${task.taskId}, error=${t.message}")
        }
    }

    // ======================== 存储空间 ========================

    suspend fun getTotalStorageSize(): Long = taskDao.getTotalFileSize()

    suspend fun getTotalStorageSizeFormatted(): String = formatFileSize(getTotalStorageSize())

    fun getFreeStorageSize(): String {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Download")
        if (!dir.exists()) return "未知"
        return formatFileSize(dir.freeSpace)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return String.format("%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }
}
