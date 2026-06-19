package com.hpu.mymoviestore.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 下载任务实体
 *
 * 对应数据库表 download_task，记录每个视频下载任务的完整状态信息。
 * 支持分片下载进度追踪和弹幕独立下载状态管理。
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    /** 任务唯一标识，UUID 格式 */
    @PrimaryKey
    val taskId: String,

    /** 关联的视频 ID */
    val videoId: Long,

    /** 视频标题 */
    val title: String,

    /** 剧集标题（如 "第1集"） */
    val episodeTitle: String,

    /** 封面图片 URL */
    val coverUrl: String,

    /** 原始 m3u8 播放地址 */
    val playUrl: String,

    /** 合并后的本地 mp4 文件路径 */
    val localFilePath: String,

    /** 总分片数 */
    val totalSegments: Int,

    /** 已下载分片数 */
    val downloadedSegments: Int,

    /** 文件总大小（字节） */
    val fileSize: Long,

    /**
     * 下载状态
     * - [STATUS_PENDING]     = 0 等待下载
     * - [STATUS_DOWNLOADING] = 1 下载中
     * - [STATUS_PAUSED]     = 2 已暂停
     * - [STATUS_COMPLETED]  = 3 已完成
     * - [STATUS_FAILED]     = 4 失败
     */
    val status: Int,

    /** 错误信息（失败时记录原因） */
    val errorMsg: String,

    /** 任务创建时间（毫秒时间戳） */
    val createTime: Long,

    /** 最后更新时间（毫秒时间戳） */
    val updateTime: Long,

    /** 视频来源名称 */
    val sourceName: String,

    /** 弹幕文件本地路径 */
    val danmakuFilePath: String,

    /**
     * 弹幕下载状态
     * - [DANMAKU_NOT_DOWNLOADED] = 0 未下载
     * - [DANMAKU_DOWNLOADING]    = 1 下载中
     * - [DANMAKU_COMPLETED]      = 2 已完成
     * - [DANMAKU_FAILED]         = 3 失败
     * - [DANMAKU_RETRYING]       = 4 重试中
     */
    val danmakuStatus: Int,

    /** 弹幕下载重试次数 */
    val danmakuRetryCount: Int,

    /** 弹幕下载错误信息 */
    val danmakuError: String,

    /** 离线播放进度百分比（0~100，-1 表示未观看） */
    val playProgressPercent: Int = -1,

    /** 离线播放位置（毫秒） */
    val playPositionMs: Long = 0L,

    /** 离线播放总时长（毫秒） */
    val playDurationMs: Long = 0L
) {
    companion object {
        // ========== 下载状态常量 ==========

        /** 等待下载 */
        const val STATUS_PENDING = 0

        /** 下载中 */
        const val STATUS_DOWNLOADING = 1

        /** 已暂停 */
        const val STATUS_PAUSED = 2

        /** 已完成 */
        const val STATUS_COMPLETED = 3

        /** 失败 */
        const val STATUS_FAILED = 4

        // ========== 弹幕状态常量 ==========

        /** 未下载 */
        const val DANMAKU_NOT_DOWNLOADED = 0

        /** 下载中 */
        const val DANMAKU_DOWNLOADING = 1

        /** 已完成 */
        const val DANMAKU_COMPLETED = 2

        /** 失败 */
        const val DANMAKU_FAILED = 3

        /** 重试中 */
        const val DANMAKU_RETRYING = 4

        // ========== 状态转换方法 ==========

        /**
         * 将状态码转换为可读的中文描述
         */
        fun statusToText(status: Int): String = when (status) {
            STATUS_PENDING -> "等待下载"
            STATUS_DOWNLOADING -> "下载中"
            STATUS_PAUSED -> "已暂停"
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> "下载失败"
            else -> "未知状态"
        }

        /**
         * 将弹幕状态码转换为可读的中文描述
         */
        fun danmakuStatusToText(status: Int): String = when (status) {
            DANMAKU_NOT_DOWNLOADED -> "未下载"
            DANMAKU_DOWNLOADING -> "下载中"
            DANMAKU_COMPLETED -> "已完成"
            DANMAKU_FAILED -> "下载失败"
            DANMAKU_RETRYING -> "重试中"
            else -> "未知状态"
        }

        /**
         * 判断当前状态是否为活跃状态（可恢复下载）
         */
        fun isActiveStatus(status: Int): Boolean {
            return status in listOf(STATUS_PENDING, STATUS_DOWNLOADING, STATUS_PAUSED, STATUS_FAILED)
        }

        /**
         * 判断当前状态是否为终态（不可再变更）
         */
        fun isTerminalStatus(status: Int): Boolean {
            return status == STATUS_COMPLETED
        }

        /**
         * 判断弹幕是否需要下载
         */
        fun needsDanmakuDownload(status: Int): Boolean {
            return status in listOf(DANMAKU_NOT_DOWNLOADED, DANMAKU_FAILED, DANMAKU_RETRYING)
        }

        /**
         * 将播放进度百分比转换为显示文字
         */
        fun progressToText(percent: Int): String = when {
            percent < 0 -> "未观看"
            percent >= 100 -> "已看完"
            else -> "已观看${percent}%"
        }
    }
}
