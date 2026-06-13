package com.hpu.mymoviestore.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hpu.mymoviestore.data.dao.PlayHistoryDao
import com.hpu.mymoviestore.data.dao.SearchHistoryDao
import com.hpu.mymoviestore.data.dao.ApiCacheDao
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.entity.ApiCacheEntity

/**
 * Room 数据库入口
 *
 * 版本管理：
 * - v1: videos / favorites / play_history / video_sources / categories
 * - v2: play_history 表扩展 playUrl 字段
 * - v3: 移除 favorites / videos / video_sources / categories；play_history 为主表
 * - v4: ① play_history 扩展 playProgressSeconds / durationSeconds（续播）
 *       ② 新增 search_history（搜索历史）
 *       ③ 新增 api_cache（爬虫源响应缓存，TTL 自动过期）
 *
 * 配合 fallbackToDestructiveMigration 使用：升级时重建数据库，避免 schema hash 校验失败。
 */
@Database(
    entities = [
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        ApiCacheEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MovieDatabase : RoomDatabase() {

    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun apiCacheDao(): ApiCacheDao

    companion object {
        @Volatile
        private var INSTANCE: MovieDatabase? = null

        fun getInstance(context: Context): MovieDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MovieDatabase::class.java,
                    "movie_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
