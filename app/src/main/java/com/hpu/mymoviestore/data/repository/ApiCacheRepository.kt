package com.hpu.mymoviestore.data.repository

import android.util.Log
import com.hpu.mymoviestore.data.dao.ApiCacheDao
import com.hpu.mymoviestore.data.entity.ApiCacheEntity

/**
 * 爬虫源缓存仓库
 *
 * - get(cacheKey, ttl)：读取有效缓存；null 表示缓存失效或不存在，需要重新抓取
 * - put(cacheKey, payload, ttl)：写入缓存（按 cacheKey 主键冲突替换），刷新内容与 TTL
 * - invalidate(cacheKey)：强制删除某条缓存（手动刷新）
 * - cleanExpired()：清理已过期缓存（减小数据库占用，可选）
 *
 * 默认 TTL = 1 天（ApiCacheEntity.TTL_ONE_DAY）
 */
class ApiCacheRepository(private val dao: ApiCacheDao) {

    companion object {
        private const val TAG = "ApiCacheRepo"
    }

    /**
     * 读取有效缓存
     * @return 若缓存存在且未过期，返回 jsonPayload；否则返回 null
     */
    suspend fun get(cacheKey: String): String? {
        val now = System.currentTimeMillis()
        val cached = dao.getValidCache(cacheKey, now)
        return if (cached != null) {
            val remainSec = (cached.expiredAt - now) / 1000
            Log.d(
                TAG,
                "缓存命中: key='$cacheKey', 剩余有效 $remainSec 秒 " +
                    "(createdAt=${cached.createdAt}, ttl=${cached.ttlSeconds}s)"
            )
            cached.jsonPayload
        } else {
            Log.d(TAG, "缓存未命中或已过期: key='$cacheKey'（将重新读取源数据）")
            null
        }
    }

    /**
     * 获取某条有效缓存的剩余 TTL 秒数。
     *
     * 用于同一组分页缓存对齐过期时间：例如搜索结果首页缓存 30 分钟，
     * 后续页缓存时复用首页剩余秒数，让同一关键词的分页同时失效。
     */
    suspend fun getRemainingTtlSeconds(cacheKey: String): Long? {
        val now = System.currentTimeMillis()
        val cached = dao.getValidCache(cacheKey, now) ?: return null
        val remainSec = ((cached.expiredAt - now) / 1000).coerceAtLeast(0)
        Log.d(TAG, "缓存剩余 TTL: key='$cacheKey', remain=${remainSec}s")
        return remainSec
    }

    /**
     * 写入缓存（cacheKey 为主键，已存在则替换，实现 upsert）
     */
    suspend fun put(cacheKey: String, jsonPayload: String, ttlSeconds: Long = ApiCacheEntity.TTL_ONE_DAY) {
        val now = System.currentTimeMillis()
        dao.putCache(
            ApiCacheEntity(
                cacheKey = cacheKey,
                jsonPayload = jsonPayload,
                ttlSeconds = ttlSeconds,
                createdAt = now,
                expiredAt = now + ttlSeconds * 1000
            )
        )
        Log.d(
            TAG,
            "写入缓存: key='$cacheKey', payloadLength=${jsonPayload.length} 字符, " +
                "ttl=${ttlSeconds}s, expiredAt=${now + ttlSeconds * 1000}"
        )
    }

    suspend fun invalidate(cacheKey: String) {
        val rows = dao.invalidateCache(cacheKey)
        Log.d(TAG, "强制失效缓存: key='$cacheKey', 删除 $rows 行")
    }

    suspend fun cleanExpired() {
        val rows = cleanExpiredInner()
        Log.d(TAG, "清理过期缓存: 共删除 $rows 行")
    }

    /** 与 cleanExpired 等价，返回删除行数（供 MovieApplication 启动清理使用） */
    suspend fun cleanExpiredInner(): Int {
        return dao.cleanExpired(System.currentTimeMillis())
    }

    suspend fun clearAll() {
        Log.d(TAG, "清空全部缓存")
        dao.clearAllCache()
    }

    /**
     * 按前缀删除缓存（用于分类清理）
     * @return 被删除的行数
     */
    suspend fun deleteByPrefix(prefix: String): Int {
        val rows = dao.deleteByPrefix(prefix)
        Log.d(TAG, "按前缀删除缓存: prefix='$prefix', 删除 $rows 行")
        return rows
    }
}
