package com.hpu.mymoviestore.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 爬虫源响应缓存 —— Room 持久化
 *
 * 设计：
 * - cacheKey：缓存键（例如 "json:assets/sample_video_source.json" 或 "http:https://api.example.com/videos"），UNIQUE
 * - jsonPayload：序列化后的视频列表 JSON 字符串（由 VideoSourceManager 写入/读取）
 * - ttlSeconds：本次写入的 TTL（秒），默认 1 天 = 86400s
 * - createdAt：写入时间（毫秒），用于判断是否过期
 * - expiredAt：过期时间 = createdAt + ttlSeconds * 1000，便于 SQL 直接判断过期
 *
 * 过期策略：
 * - 读取时先比较 System.currentTimeMillis() 与 expiredAt；过期返回 null，触发重新抓取
 * - VideoSourceManager 启动或定时可调用 cleanExpired() 清理过期记录（不强制，存储占用小）
 */
@Entity(tableName = "api_cache")
data class ApiCacheEntity(
    @PrimaryKey
    val cacheKey: String,         // 直接用 cacheKey 作为主键，保证唯一性，便于 @Insert(onConflict=REPLACE) 实现 upsert
    val jsonPayload: String,
    val ttlSeconds: Long = TTL_ONE_DAY,
    val createdAt: Long = System.currentTimeMillis(),
    val expiredAt: Long = System.currentTimeMillis() + ttlSeconds * 1000
) {
    init {
        require(expiredAt == createdAt + ttlSeconds * 1000) {
            "expiredAt($expiredAt) != createdAt($createdAt) + ttlSeconds($ttlSeconds) * 1000"
        }
    }

    companion object {
        /** 常用 TTL：1 天 */
        const val TTL_ONE_DAY: Long = 86400
        /** 常用 TTL：1 小时 */
        const val TTL_ONE_HOUR: Long = 3600
        /** 真实播放地址常用 TTL：30 分钟，避免缓存过期 token 过久 */
        const val TTL_THIRTY_MINUTES: Long = 1800
        /** 测试用 TTL：10 秒 */
        const val TTL_TEN_SECONDS: Long = 10
    }
}
