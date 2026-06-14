package com.hpu.mymoviestore.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity

/**
 * 播放历史 DAO
 *
 * 去重策略：
 * - getHistoryByVideoId(videoId) 先查询是否存在
 * - 存在则通过 updateHistoryByVideoId 更新 lastPlayTime / 进度
 * - 不存在则通过 addHistory 新插入
 */
@Dao
interface PlayHistoryDao {

    /** 查询全部历史，按最后播放时间倒序 */
    @Query("SELECT * FROM play_history ORDER BY lastPlayTime DESC")
    fun getAllHistory(): LiveData<List<PlayHistoryEntity>>

    /** 根据 videoId 查询是否已存在（用于去重） */
    @Query("SELECT * FROM play_history WHERE videoId = :videoId")
    suspend fun getHistoryByVideoId(videoId: Long): PlayHistoryEntity?

    /** 查询某个详情页最近一次播放记录，用于详情页默认定位上次播放集数 */
    @Query(
        "SELECT * FROM play_history " +
            "WHERE detailUrl = :detailUrl " +
            "ORDER BY lastPlayTime DESC " +
            "LIMIT 1"
    )
    suspend fun getLatestHistoryByDetailUrl(detailUrl: String): PlayHistoryEntity?

    /** 插入新记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addHistory(history: PlayHistoryEntity): Long

    /**
     * 更新已有记录（不刷新进度）：刷新 lastPlayTime / title / coverUrl / category / playUrl
     * 供首次进入播放或未初始化 ExoPlayer 时使用。
     */
    @Query(
        "UPDATE play_history " +
            "SET lastPlayTime = :newTime, " +
            "title = :title, " +
            "coverUrl = :coverUrl, " +
            "category = :category, " +
            "playUrl = :playUrl, " +
            "detailUrl = :detailUrl, " +
            "playPageUrl = :playPageUrl, " +
            "episodeTitle = :episodeTitle " +
            "WHERE videoId = :videoId"
    )
    suspend fun updateHistoryByVideoId(
        videoId: Long,
        title: String,
        coverUrl: String,
        category: String,
        playUrl: String,
        detailUrl: String,
        playPageUrl: String,
        episodeTitle: String,
        newTime: Long
    ): Int

    /**
     * 更新播放进度 + 最后播放时间
     * 由 PlayerActivity 在播放过程中/暂停/退出时调用，每秒一次（由 ExoPlayer 监听器触发）。
     */
    @Query(
        "UPDATE play_history " +
            "SET playProgressSeconds = :progressSeconds, " +
            "durationSeconds = :durationSeconds, " +
            "lastPlayTime = :newTime " +
            "WHERE videoId = :videoId"
    )
    suspend fun updateProgressByVideoId(
        videoId: Long,
        progressSeconds: Long,
        durationSeconds: Long,
        newTime: Long
    ): Int

    /** 清空全部历史（同时删除播放进度，因为它们在同一张表） */
    @Query("DELETE FROM play_history")
    suspend fun clearAllHistory()

    /** 按 videoId 删除单条历史（长按删除扩展用） */
    @Query("DELETE FROM play_history WHERE videoId = :videoId")
    suspend fun deleteHistoryByVideoId(videoId: Long): Int
}
