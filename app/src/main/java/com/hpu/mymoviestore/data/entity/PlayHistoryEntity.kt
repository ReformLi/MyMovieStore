package com.hpu.mymoviestore.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放历史（含播放进度）—— Room 持久化
 *
 * 表结构设计：
 * - videoId：业务主键，去重判断依据；播放进度、lastPlayTime 以此为 key 更新
 * - title / coverUrl / category / playUrl：冗余字段，供 HistoryFragment 直接展示，
 *   避免跳转后必须从 JSON 挡板回查
 * - detailUrl / playPageUrl / episodeTitle：爬虫源详情页和具体集数定位信息
 * - playProgressSeconds：上次看到的位置（秒），用于"续播"
 * - durationSeconds：视频总时长（秒），0 表示未知，用于展示"已看 XX%"
 * - lastPlayTime：最后播放时间（毫秒），排序 + 时间展示
 *
 * 删除规则：
 * - 点击"清空历史"删除整条记录（play_history 表删除，播放进度自然丢失）
 * - 播放历史删除时，播放进度作为同表字段一并删除，无需额外清理
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoId: Long,
    val title: String,
    val coverUrl: String,
    val category: String,
    val playUrl: String,
    val detailUrl: String = "",
    val playPageUrl: String = "",
    val episodeTitle: String = "",
    val playProgressSeconds: Long = 0,
    val durationSeconds: Long = 0,
    val lastPlayTime: Long = System.currentTimeMillis(),
    val sourceName: String = ""
)
