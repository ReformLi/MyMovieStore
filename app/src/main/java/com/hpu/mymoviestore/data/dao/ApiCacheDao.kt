package com.hpu.mymoviestore.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hpu.mymoviestore.data.entity.ApiCacheEntity

/**
 * 爬虫源缓存 DAO
 *
 * cacheKey 是主键，@Insert(onConflict=REPLACE) 天然实现 upsert：
 * - 写：存在则替换（刷新内容 + TTL）
 * - 读：按 cacheKey 查询，若 expiredAt >= now 才视为有效
 * - 清理：cleanExpired 按 expiredAt 阈值删除过期缓存
 */
@Dao
interface ApiCacheDao {

    /**
     * 查询某个 cacheKey 的有效缓存（expiredAt >= nowMs 才返回）
     * @param nowMs 当前时间（毫秒），由调用方传入以便测试可控制时间
     */
    @Query(
        "SELECT * FROM api_cache " +
            "WHERE cacheKey = :cacheKey AND expiredAt >= :nowMs " +
            "LIMIT 1"
    )
    suspend fun getValidCache(cacheKey: String, nowMs: Long): ApiCacheEntity?

    /**
     * 查询某个 cacheKey 的缓存（无论过期与否，调试与强制刷新使用）
     */
    @Query("SELECT * FROM api_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getLatestCache(cacheKey: String): ApiCacheEntity?

    /**
     * 写入缓存（cacheKey 是主键，已存在则自动替换，实现"刷新内容 + TTL"）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(cache: ApiCacheEntity): Long

    /**
     * 清理所有已过期缓存（expiredAt < nowMs）
     * @return 被删除的行数
     */
    @Query("DELETE FROM api_cache WHERE expiredAt < :nowMs")
    suspend fun cleanExpired(nowMs: Long): Int

    /**
     * 按 cacheKey 删除某条缓存（强制刷新用）
     */
    @Query("DELETE FROM api_cache WHERE cacheKey = :cacheKey")
    suspend fun invalidateCache(cacheKey: String): Int

    /**
     * 清空所有缓存
     */
    @Query("DELETE FROM api_cache")
    suspend fun clearAllCache()
}
