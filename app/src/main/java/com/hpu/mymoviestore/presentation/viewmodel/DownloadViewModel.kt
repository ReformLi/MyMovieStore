package com.hpu.mymoviestore.presentation.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.data.database.MovieDatabase
import com.hpu.mymoviestore.data.download.DanmakuDownloadManager
import com.hpu.mymoviestore.data.download.DownloadCallback
import com.hpu.mymoviestore.data.download.DownloadEngine
import com.hpu.mymoviestore.data.download.DownloadService
import com.hpu.mymoviestore.data.download.DownloadStatus
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.data.model.PlayEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 下载管理 ViewModel
 *
 * 核心功能：
 * - downloadingTasks: 下载中的任务（LiveData）
 * - completedTasks: 已完成的任务（LiveData）
 * - totalStorageSize: 总占用空间（格式化字符串）
 * - freeStorageSize: 剩余空间（格式化字符串）
 * - createTasks: 创建批量下载任务
 * - pauseTask / resumeTask / cancelTask / retryTask: 单任务控制
 * - deleteTask / deleteTasks: 删除任务
 * - pauseAll / resumeAll: 批量控制
 * - refreshStorageInfo: 刷新存储信息
 */
class DownloadViewModel : ViewModel() {

    companion object {
        private const val TAG = "DownloadViewModel"
        /** 默认下载保存目录名 */
        private const val DEFAULT_SAVE_DIR = "downloads"
    }

    private val repository = MovieApplication.get().downloadRepository
    private val app = MovieApplication.get()

    // ======================== LiveData ========================

    /** 下载中的任务（PENDING / DOWNLOADING / PAUSED / FAILED） */
    val downloadingTasks: LiveData<List<DownloadTaskEntity>> =
        repository.getDownloadingTasks()

    /** 已完成的任务 */
    val completedTasks: LiveData<List<DownloadTaskEntity>> =
        repository.getCompletedTasks()

    private val _totalStorageSize = MutableLiveData("0 B")
    val totalStorageSize: LiveData<String> = _totalStorageSize

    private val _freeStorageSize = MutableLiveData("未知")
    val freeStorageSize: LiveData<String> = _freeStorageSize

    // ======================== 创建任务 ========================

    /**
     * 创建批量下载任务
     *
     * @param videoId    视频 ID
     * @param title      视频标题
     * @param coverUrl   封面 URL
     * @param sourceName 视频来源
     * @param episodes   集数列表
     */
    fun createTasks(
        videoId: Long,
        title: String,
        coverUrl: String,
        sourceName: String,
        episodes: List<PlayEpisode>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val saveDir = getSaveDir()
            episodes.forEach { episode ->
                // 使用 playPageUrl 的 hashCode 作为稳定的 episodeIndex，
                // 避免过滤后的子列表索引与原始索引不一致导致 taskId 冲突
                val stableIndex = episode.playPageUrl.hashCode()
                try {
                    repository.createTask(
                        videoId = videoId,
                        title = title,
                        coverUrl = coverUrl,
                        sourceName = sourceName,
                        episodeIndex = stableIndex,
                        episodeTitle = episode.title,
                        playUrl = episode.playPageUrl
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "创建下载任务失败: episode=${episode.title}, error=${t.message}")
                }
            }
            Log.d(TAG, "批量创建下载任务完成: videoId=$videoId, count=${episodes.size}")
            refreshStorageInfo()
        }
    }

    // ======================== 单任务控制 ========================

    /** 暂停任务 */
    fun pauseTask(taskId: String) {
        // 同时中断 DownloadEngine 中的实际下载
        DownloadEngine.getInstance(app).pauseTask(taskId)
        viewModelScope.launch(Dispatchers.IO) {
            repository.pauseTask(taskId)
        }
    }

    /** 恢复任务 */
    fun resumeTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resumeTask(taskId)
            // 如果 DownloadEngine 中没有该任务（应用重启后），需要重新提交
            if (DownloadEngine.getInstance(app).getTask(taskId) == null) {
                val entity = repository.getTaskById(taskId)
                if (entity != null) {
                    // playUrl 可能是播放页 URL（非 m3u8），需要重新解析
                    val m3u8Url = resolveM3u8Url(entity)
                    if (m3u8Url.isNullOrBlank()) {
                        Log.w(TAG, "恢复失败：无法解析 m3u8 URL, taskId=$taskId")
                        repository.markFailed(taskId, "无法解析播放地址")
                        return@launch
                    }
                    // 确保前台服务已启动（重启后服务可能已停止）
                    ensureDownloadServiceRunning()
                    // 重新提交到 DownloadEngine，并绑定完整回调以更新数据库进度和状态
                    DownloadEngine.getInstance(app).submitTask(
                        m3u8Url = m3u8Url,
                        videoTitle = entity.title,
                        episodeTitle = entity.episodeTitle,
                        taskId = entity.taskId,
                        callback = buildDownloadCallback(entity.taskId, entity.title, entity.episodeTitle)
                    )
                    Log.d(TAG, "恢复任务：重新提交到 DownloadEngine: $taskId")
                } else {
                    Log.w(TAG, "恢复失败：找不到任务 $taskId")
                }
            } else {
                DownloadEngine.getInstance(app).resumeTask(taskId)
            }
        }
    }

    /**
     * 解析真实 m3u8 URL。
     * 如果 playUrl 已经是 m3u8 格式则直接返回，否则通过 VideoSource 重新解析。
     */
    private suspend fun resolveM3u8Url(entity: DownloadTaskEntity): String? {
        val playUrl = entity.playUrl
        // 如果已经是 m3u8 地址，直接返回
        if (playUrl.contains(".m3u8")) {
            return playUrl
        }
        // 否则通过 VideoSource 重新解析
        val source = app.allVideoSources.find { it.sourceName == entity.sourceName }
        if (source == null) {
            Log.w(TAG, "找不到视频源: ${entity.sourceName}")
            return null
        }
        return source.fetchVideoUrlByPlayPageUrl(playUrl).getOrNull()
    }

    /** 取消任务（中断下载并删除任务） */
    fun cancelTask(taskId: String) {
        // 同时中断 DownloadEngine 中的实际下载
        DownloadEngine.getInstance(app).cancelTask(taskId)
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTask(taskId)
            refreshStorageInfo()
        }
    }

    /** 重试任务 */
    fun retryTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.retryTask(taskId)
            // 重新提交到 DownloadEngine 执行
            val entity = repository.getTaskById(taskId)
            if (entity != null) {
                val m3u8Url = resolveM3u8Url(entity)
                if (m3u8Url.isNullOrBlank()) {
                    Log.w(TAG, "重试失败：无法解析 m3u8 URL, taskId=$taskId")
                    repository.markFailed(taskId, "无法解析播放地址")
                    return@launch
                }
                // 确保前台服务已启动
                ensureDownloadServiceRunning()
                DownloadEngine.getInstance(app).submitTask(
                    m3u8Url = m3u8Url,
                    videoTitle = entity.title,
                    episodeTitle = entity.episodeTitle,
                    taskId = entity.taskId,
                    callback = buildDownloadCallback(entity.taskId, entity.title, entity.episodeTitle)
                )
                Log.d(TAG, "重试任务已重新提交到 DownloadEngine: $taskId")
            } else {
                Log.w(TAG, "重试失败：找不到任务 $taskId")
            }
        }
    }

    // ======================== 弹幕重试 ========================

    /**
     * 仅重试弹幕下载，不影响视频下载状态。
     * 用于视频已下载完成但弹幕下载失败的场景。
     */
    fun retryDanmaku(task: DownloadTaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DanmakuDownloadManager.getInstance(app).retryDanmaku(
                    taskId = task.taskId,
                    title = task.title,
                    episodeTitle = task.episodeTitle,
                    dao = MovieDatabase.getInstance(app).downloadTaskDao()
                )
                Log.d(TAG, "弹幕重试已触发: taskId=${task.taskId}")
            } catch (e: Exception) {
                Log.w(TAG, "弹幕重试失败: taskId=${task.taskId}, error=${e.message}")
            }
        }
    }

    // ======================== 删除 ========================

    /**
     * 应用重启后，将数据库中"下载中/等待中"的任务重置为"暂停"。
     * 因为 DownloadEngine 是内存态的，重启后任务已丢失，需要让用户手动恢复。
     */
    fun resetActiveTasksOnRestart() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.pauseAll()
            Log.d(TAG, "已将所有活跃任务重置为暂停状态")
        }
    }

    /** 删除单个任务 */
    fun deleteTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTask(taskId)
            refreshStorageInfo()
        }
    }

    /** 批量删除任务 */
    fun deleteTasks(taskIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTasks(taskIds)
            refreshStorageInfo()
        }
    }

    // ======================== 批量控制 ========================

    /** 暂停所有下载中/等待中的任务 */
    fun pauseAll() {
        // 仅暂停下载中/等待中的任务，不影响已失败的任务
        DownloadEngine.getInstance(app).getAllTasks().forEach { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) {
                DownloadEngine.getInstance(app).pauseTask(task.taskId)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.pauseAll()
        }
    }

    /** 恢复所有暂停的任务 */
    fun resumeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resumeAll()
            val engine = DownloadEngine.getInstance(app)
            // 从数据库查询所有活跃任务（状态已被 resumeAll 更新为 PENDING）
            val activeTasks = repository.getActiveTasks()
            if (activeTasks.isNotEmpty()) {
                ensureDownloadServiceRunning()
            }
            // 优先恢复引擎中已有的任务，无法找到的则重新提交
            engine.getAllTasks().forEach {
                engine.resumeTask(it.taskId)
            }
            // 处理引擎中不存在的任务（应用重启后）
            activeTasks.forEach { entity ->
                if (engine.getTask(entity.taskId) == null) {
                    val m3u8Url = resolveM3u8Url(entity)
                    if (!m3u8Url.isNullOrBlank()) {
                        engine.submitTask(
                            m3u8Url = m3u8Url,
                            videoTitle = entity.title,
                            episodeTitle = entity.episodeTitle,
                            taskId = entity.taskId,
                            callback = buildDownloadCallback(entity.taskId, entity.title, entity.episodeTitle)
                        )
                        Log.d(TAG, "resumeAll：重新提交任务到 DownloadEngine: ${entity.taskId}")
                    } else {
                        repository.markFailed(entity.taskId, "无法解析播放地址")
                        Log.w(TAG, "resumeAll：无法解析 m3u8 URL, taskId=${entity.taskId}")
                    }
                }
            }
        }
    }

    // ======================== 存储信息 ========================

    /** 刷新存储信息（总占用空间 + 剩余空间） */
    fun refreshStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val totalSize = repository.getTotalStorageSizeFormatted()
                _totalStorageSize.postValue(totalSize)
                Log.d(TAG, "刷新存储信息: totalSize=$totalSize")
            } catch (t: Throwable) {
                Log.w(TAG, "获取总占用空间失败: ${t.message}")
                _totalStorageSize.postValue("0 B")
            }

            try {
                val freeSize = repository.getFreeStorageSize()
                _freeStorageSize.postValue(freeSize)
                Log.d(TAG, "刷新存储信息: freeSize=$freeSize")
            } catch (t: Throwable) {
                Log.w(TAG, "获取剩余空间失败: ${t.message}")
                _freeStorageSize.postValue("未知")
            }
        }
    }

    // ======================== 工具方法 ========================

    /** 获取下载保存目录的绝对路径 */
    private fun getSaveDir(): String {
        val app = MovieApplication.get()
        val dir = app.getExternalFilesDir(DEFAULT_SAVE_DIR)
        return dir?.absolutePath ?: app.filesDir.resolve(DEFAULT_SAVE_DIR).absolutePath
    }

    /**
     * 构建标准的 DownloadCallback，用于恢复/重试任务时将进度和状态同步到数据库。
     *
     * 这与 DetailActivity.startDownloadForEpisodes 中的回调保持一致。
     */
    private fun buildDownloadCallback(taskId: String, videoTitle: String, episodeTitle: String): DownloadCallback {
        return object : DownloadCallback {
            override fun onProgress(taskId: String, downloadedSegments: Int, totalSegments: Int, fileSize: Long) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        repository.updateProgress(taskId, downloadedSegments, totalSegments, fileSize)
                    } catch (e: Exception) {
                        Log.w(TAG, "同步下载进度到数据库失败: ${e.message}")
                    }
                }
            }

            override fun onStatusChanged(taskId: String, status: Int, errorMsg: String?) {
                Log.d(TAG, "下载状态变更(恢复): taskId=$taskId, status=$status, error=$errorMsg")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        when (status) {
                            DownloadStatus.DOWNLOADING -> repository.markDownloading(taskId)
                            DownloadStatus.PAUSED -> repository.pauseTask(taskId)
                            DownloadStatus.FAILED -> repository.markFailed(taskId, errorMsg ?: "")
                            DownloadStatus.MERGING -> repository.updateStatus(taskId, DownloadTaskEntity.STATUS_MERGING, "合并中…")
                            else -> {}
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "同步下载状态到数据库失败: ${e.message}")
                    }
                }
            }

            override fun onCompleted(taskId: String, localFilePath: String, fileSize: Long) {
                Log.d(TAG, "下载完成(恢复): taskId=$taskId, path=$localFilePath, size=$fileSize")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        repository.markCompleted(taskId, localFilePath, fileSize)
                        // 下载完成后触发弹幕下载
                        DanmakuDownloadManager.getInstance(app).startDanmakuDownload(
                            taskId = taskId,
                            title = videoTitle,
                            episodeTitle = episodeTitle,
                            dao = MovieDatabase.getInstance(app).downloadTaskDao()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "同步下载完成到数据库失败: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 确保下载前台服务已启动。
     * 应用重启后服务会停止，在恢复/重试下载时需要重新启动。
     */
    private fun ensureDownloadServiceRunning() {
        try {
            val intent = Intent(app, DownloadService::class.java)
            app.startForegroundService(intent)
            Log.d(TAG, "已启动 DownloadService")
        } catch (e: Exception) {
            Log.w(TAG, "启动 DownloadService 失败: ${e.message}")
        }
    }
}
