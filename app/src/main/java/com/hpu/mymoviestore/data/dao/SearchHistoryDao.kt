package com.hpu.mymoviestore.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity

/**
 * 搜索历史 DAO
 *
 * 去重策略：
 * - 先查询 keyword 是否已存在
 * - 存在 → updateKeywordSearch（searchCount + 1，lastSearchTime = now）
 * - 不存在 → addKeyword 插入新记录
 */
@Dao
interface SearchHistoryDao {

    /** 查询全部搜索历史，按最后搜索时间倒序 */
    @Query("SELECT * FROM search_history ORDER BY lastSearchTime DESC")
    fun getAllHistory(): LiveData<List<SearchHistoryEntity>>

    /** 根据 keyword 查询（去重用） */
    @Query("SELECT * FROM search_history WHERE keyword = :keyword LIMIT 1")
    suspend fun getHistoryByKeyword(keyword: String): SearchHistoryEntity?

    /** 插入新记录（若冲突则替换，兜底用） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addKeyword(history: SearchHistoryEntity): Long

    /**
     * 更新已有关键词记录：搜索次数 +1，最后搜索时间 = 当前时间
     */
    @Query(
        "UPDATE search_history " +
            "SET searchCount = searchCount + 1, " +
            "lastSearchTime = :newTime " +
            "WHERE keyword = :keyword"
    )
    suspend fun updateKeywordSearch(keyword: String, newTime: Long): Int

    /** 按 keyword 删除单条（用户手动清空某条） */
    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun deleteByKeyword(keyword: String): Int

    /** 清空全部搜索历史 */
    @Query("DELETE FROM search_history")
    suspend fun clearAllHistory()
}
