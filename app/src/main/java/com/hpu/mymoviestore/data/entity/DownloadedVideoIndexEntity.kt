package com.hpu.mymoviestore.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已下载视频索引实体
 *
 * 用于快速判断某视频/剧集是否已下载，避免重复下载。
 * 每条记录对应一个已下载的剧集文件。
 */
@Entity(tableName = "downloaded_video_index")
data class DownloadedVideoIndexEntity(
    /** 关联的视频 ID */
    @PrimaryKey
    val videoId: Long,

    /** 剧集标识（如 "1"、"2" 等，用于区分同一视频的不同集数） */
    val episodeId: String,

    /** 本地文件存储路径 */
    val localPath: String,

    /** 下载完成时间（毫秒时间戳） */
    val downloadTime: Long
)
