package com.hpu.mymoviestore.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务 DAO
 *
 * 提供下载任务的增删改查及状态更新操作，所有列表查询返回 Flow 以支持响应式更新。
 */
@Dao
interface DownloadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_task WHERE taskId = :taskId LIMIT 1")
    suspend fun getByTaskId(taskId: String): DownloadTaskEntity?

    /** 根据状态查询（返回 Flow，用于 UI 观察） */
    @Query("SELECT * FROM download_task WHERE status = :status ORDER BY createTime DESC")
    fun getByStatus(status: Int): Flow<List<DownloadTaskEntity>>

    /** 根据状态查询（返回 List，用于一次性读取） */
    @Query("SELECT * FROM download_task WHERE status = :status ORDER BY createTime DESC")
    suspend fun getByStatusList(status: Int): List<DownloadTaskEntity>

    /** 根据多个状态查询（返回 List） */
    @Query("SELECT * FROM download_task WHERE status IN (:statuses) ORDER BY createTime DESC")
    suspend fun getByStatusesList(statuses: List<Int>): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task ORDER BY createTime DESC")
    fun getAll(): Flow<List<DownloadTaskEntity>>

    /** 活跃任务（等待、下载中、暂停、失败、合并中） */
    @Query("SELECT * FROM download_task WHERE status IN (0, 1, 2, 4, 6) ORDER BY createTime DESC")
    fun getDownloading(): Flow<List<DownloadTaskEntity>>

    /** 已完成任务 */
    @Query("SELECT * FROM download_task WHERE status = 3 ORDER BY updateTime DESC")
    fun getCompleted(): Flow<List<DownloadTaskEntity>>

    /** 按 videoId 查询 */
    @Query("SELECT * FROM download_task WHERE videoId = :videoId ORDER BY createTime DESC")
    suspend fun getByVideoId(videoId: Long): List<DownloadTaskEntity>

    /** 按 videoId + 状态查询 */
    @Query("SELECT * FROM download_task WHERE videoId = :videoId AND status = :status ORDER BY createTime DESC")
    suspend fun getByVideoIdAndStatus(videoId: Long, status: Int): List<DownloadTaskEntity>

    @Query("DELETE FROM download_task WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("DELETE FROM download_task WHERE taskId IN (:taskIds)")
    suspend fun deleteByTaskIds(taskIds: List<String>)

    /** 更新状态（只更新 status 和 errorMsg） */
    @Query("UPDATE download_task SET status = :status, errorMsg = :errorMsg, updateTime = :updateTime WHERE taskId = :taskId")
    suspend fun updateStatus(taskId: String, status: Int, errorMsg: String = "", updateTime: Long = System.currentTimeMillis())

    /** 批量更新状态 */
    @Query("UPDATE download_task SET status = :newStatus, updateTime = :updateTime WHERE status IN (:oldStatuses)")
    suspend fun updateStatuses(oldStatuses: List<Int>, newStatus: Int, updateTime: Long = System.currentTimeMillis())

    /** 更新下载进度 */
    @Query("UPDATE download_task SET downloadedSegments = :downloadedSegments, totalSegments = :totalSegments, fileSize = :fileSize, updateTime = :updateTime WHERE taskId = :taskId")
    suspend fun updateProgress(taskId: String, downloadedSegments: Int, totalSegments: Int, fileSize: Long, updateTime: Long = System.currentTimeMillis())

    /** 更新本地文件路径 */
    @Query("UPDATE download_task SET localFilePath = :localFilePath, updateTime = :updateTime WHERE taskId = :taskId")
    suspend fun updateLocalFilePath(taskId: String, localFilePath: String, updateTime: Long = System.currentTimeMillis())

    /** 更新弹幕下载状态 */
    @Query("UPDATE download_task SET danmakuStatus = :danmakuStatus, danmakuFilePath = :danmakuFilePath, danmakuError = :danmakuError, updateTime = :updateTime WHERE taskId = :taskId")
    suspend fun updateDanmakuStatus(taskId: String, danmakuStatus: Int, danmakuFilePath: String = "", danmakuError: String = "", updateTime: Long = System.currentTimeMillis())

    /** 更新离线播放进度 */
    @Query("UPDATE download_task SET playProgressPercent = :percent, playPositionMs = :positionMs, playDurationMs = :durationMs, updateTime = :updateTime WHERE taskId = :taskId")
    suspend fun updatePlayProgress(taskId: String, percent: Int, positionMs: Long, durationMs: Long, updateTime: Long = System.currentTimeMillis())

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM download_task WHERE status = 3")
    suspend fun getTotalFileSize(): Long

    @Query("SELECT COUNT(*) FROM download_task WHERE status = 3")
    suspend fun getCompletedCount(): Int
}
