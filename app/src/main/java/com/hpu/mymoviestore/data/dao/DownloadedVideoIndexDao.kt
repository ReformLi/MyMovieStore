package com.hpu.mymoviestore.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hpu.mymoviestore.data.entity.DownloadedVideoIndexEntity

/**
 * 已下载视频索引 DAO
 *
 * 提供已下载视频索引的快速查询能力，用于判断视频是否已下载。
 */
@Dao
interface DownloadedVideoIndexDao {

    /**
     * 插入一条已下载视频索引记录
     * @return 插入行的 rowId（-1 表示冲突）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(index: DownloadedVideoIndexEntity): Long

    /**
     * 删除指定视频的已下载索引记录
     */
    @Query("DELETE FROM downloaded_video_index WHERE videoId = :videoId")
    suspend fun delete(videoId: Long)

    /**
     * 根据视频 ID 查询已下载索引
     */
    @Query("SELECT * FROM downloaded_video_index WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: Long): DownloadedVideoIndexEntity?

    /**
     * 判断指定视频是否已下载
     * @return true 表示已存在下载记录
     */
    @Query("SELECT COUNT(*) > 0 FROM downloaded_video_index WHERE videoId = :videoId")
    suspend fun exists(videoId: Long): Boolean
}
