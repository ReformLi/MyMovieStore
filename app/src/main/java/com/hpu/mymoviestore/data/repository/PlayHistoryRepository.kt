package com.hpu.mymoviestore.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.hpu.mymoviestore.data.dao.PlayHistoryDao
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity

/**
 * 播放历史（含续播进度）仓库
 *
 * 核心功能：
 * - getAllHistory()                全部历史，按时间倒序（LiveData）
 * - addOrUpdateHistory(videoId,…) 去重写入：若存在则更新时间/标题等，否则新增
 * - updateProgress(videoId,…)      独立更新播放进度（由 PlayerActivity 定期调用）
 * - getHistoryByVideoId(videoId)   读取某视频的播放进度，用于续播
 * - clearAllHistory()              清空（同时清空进度，因为进度是 play_history 表的字段）
 */
class PlayHistoryRepository(private val historyDao: PlayHistoryDao) {

    companion object {
        private const val TAG = "PlayHistoryRepo"
    }

    fun getAllHistory(): LiveData<List<PlayHistoryEntity>> = historyDao.getAllHistory()

    suspend fun getHistoryByVideoId(videoId: Long): PlayHistoryEntity? = historyDao.getHistoryByVideoId(videoId)

    /**
     * 去重写入播放历史
     * - 若 videoId 已存在 → 更新 lastPlayTime 以及冗余信息
     * - 若 videoId 不存在 → 插入新记录（playProgressSeconds = 0，未开始）
     * @return 最终记录的主键 id
     */
    suspend fun addOrUpdateHistory(
        videoId: Long,
        title: String,
        coverUrl: String,
        category: String,
        playUrl: String
    ): Long {
        val existing = historyDao.getHistoryByVideoId(videoId)
        val now = System.currentTimeMillis()

        return if (existing != null) {
            // 已存在：保留原 progress / duration，刷新 lastPlayTime
            historyDao.updateHistoryByVideoId(
                videoId = videoId,
                title = title,
                coverUrl = coverUrl,
                category = category,
                playUrl = playUrl,
                newTime = now
            )
            Log.d(
                TAG,
                "更新播放历史: videoId=$videoId, title=$title, " +
                    "lastPlayTime=$now, progressSec=${existing.playProgressSeconds}, " +
                    "durationSec=${existing.durationSeconds}"
            )
            existing.id
        } else {
            val id = historyDao.addHistory(
                PlayHistoryEntity(
                    videoId = videoId,
                    title = title,
                    coverUrl = coverUrl,
                    category = category,
                    playUrl = playUrl,
                    playProgressSeconds = 0,
                    durationSeconds = 0,
                    lastPlayTime = now
                )
            )
            Log.d(
                TAG,
                "新增播放历史: videoId=$videoId, title=$title, " +
                    "playUrl=$playUrl, rowId=$id"
            )
            id
        }
    }

    /**
     * 更新播放进度（秒）+ 最后播放时间
     * PlayerActivity 在播放期间会定期调用（例如每秒一次）。
     */
    suspend fun updateProgress(
        videoId: Long,
        progressSeconds: Long,
        durationSeconds: Long
    ) {
        val rows = historyDao.updateProgressByVideoId(
            videoId = videoId,
            progressSeconds = progressSeconds,
            durationSeconds = durationSeconds,
            newTime = System.currentTimeMillis()
        )
        Log.d(
            TAG,
            "写入播放进度: videoId=$videoId, progress=${progressSeconds}s, " +
                "duration=${durationSeconds}s, updatedRows=$rows"
        )
    }

    suspend fun deleteByVideoId(videoId: Long) {
        val rows = historyDao.deleteHistoryByVideoId(videoId)
        Log.d(TAG, "删除播放历史: videoId=$videoId, 删除 $rows 行")
    }

    suspend fun clearAllHistory() {
        Log.d(TAG, "清空全部播放历史（含续播进度）")
        historyDao.clearAllHistory()
    }
}
