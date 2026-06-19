package com.hpu.mymoviestore.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.data.download.DownloadEngine
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.data.model.PlayEpisode
import kotlinx.coroutines.Dispatchers
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
        }
        // 恢复后重新提交到 DownloadEngine
        DownloadEngine.getInstance(app).resumeTask(taskId)
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
                DownloadEngine.getInstance(app).submitTask(
                    m3u8Url = entity.playUrl,
                    videoTitle = entity.title,
                    episodeTitle = entity.episodeTitle,
                    taskId = entity.taskId,
                    callback = null
                )
                Log.d(TAG, "重试任务已重新提交到 DownloadEngine: $taskId")
            } else {
                Log.w(TAG, "重试失败：找不到任务 $taskId")
            }
        }
    }

    // ======================== 删除 ========================

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
        // 中断 DownloadEngine 中所有活跃任务
        DownloadEngine.getInstance(app).getAllTasks().forEach {
            DownloadEngine.getInstance(app).pauseTask(it.taskId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.pauseAll()
        }
    }

    /** 恢复所有暂停的任务 */
    fun resumeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resumeAll()
        }
        // 恢复 DownloadEngine 中的任务
        DownloadEngine.getInstance(app).getAllTasks().forEach {
            DownloadEngine.getInstance(app).resumeTask(it.taskId)
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
}
