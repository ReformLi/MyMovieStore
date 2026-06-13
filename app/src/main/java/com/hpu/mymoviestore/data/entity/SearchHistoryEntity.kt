package com.hpu.mymoviestore.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜索历史 —— Room 持久化
 *
 * 设计：
 * - keyword：搜索词，UNIQUE 约束；重复搜索同关键词时只更新 lastSearchTime 和 searchCount
 * - searchCount：搜索次数，便于后续做"热门搜索"排序（目前以 lastSearchTime 倒序）
 * - lastSearchTime：最后搜索时间（毫秒），用于排序和展示
 *
 * 使用场景：
 * - SearchFragment 输入框下方展示最近搜索词，点击可一键填入并搜索
 * - 点击某条搜索结果的删除图标（或长按）删除单条
 * - 一键清空搜索历史
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    val searchCount: Int = 1,
    val lastSearchTime: Long = System.currentTimeMillis()
)
