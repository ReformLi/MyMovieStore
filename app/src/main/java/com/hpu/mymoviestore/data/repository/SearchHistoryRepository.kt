package com.hpu.mymoviestore.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.hpu.mymoviestore.data.dao.SearchHistoryDao
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity

/**
 * 搜索历史仓库
 *
 * - 写入：keyword 已存在 → searchCount + 1，刷新 lastSearchTime；不存在 → 插入
 * - 删除：按 keyword 删除单条，或清空全部
 * - 查询：LiveData 自动刷新 UI
 */
class SearchHistoryRepository(private val dao: SearchHistoryDao) {

    companion object {
        private const val TAG = "SearchHistoryRepo"
    }

    fun getAllHistory(): LiveData<List<SearchHistoryEntity>> = dao.getAllHistory()

    /**
     * 新增或刷新一次搜索记录（去重 + 计数）
     */
    suspend fun addOrUpdateKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return

        val existing = dao.getHistoryByKeyword(trimmed)
        if (existing != null) {
            val now = System.currentTimeMillis()
            dao.updateKeywordSearch(trimmed, now)
            Log.d(TAG, "刷新搜索历史: '$trimmed', count=${existing.searchCount + 1}, time=$now")
        } else {
            dao.addKeyword(
                SearchHistoryEntity(
                    keyword = trimmed,
                    searchCount = 1,
                    lastSearchTime = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "新增搜索历史: '$trimmed'")
        }
    }

    suspend fun deleteByKeyword(keyword: String) {
        val rows = dao.deleteByKeyword(keyword)
        Log.d(TAG, "删除搜索历史: '$keyword', 删除 $rows 行")
    }

    suspend fun clearAllHistory() {
        Log.d(TAG, "清空全部搜索历史")
        dao.clearAllHistory()
    }
}
