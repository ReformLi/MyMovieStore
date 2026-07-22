package com.hpu.mymoviestore.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hpu.mymoviestore.data.dao.PlayHistoryDao
import com.hpu.mymoviestore.data.dao.SearchHistoryDao
import com.hpu.mymoviestore.data.dao.ApiCacheDao
import com.hpu.mymoviestore.data.dao.DownloadTaskDao
import com.hpu.mymoviestore.data.dao.DownloadedVideoIndexDao
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.entity.ApiCacheEntity
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.data.entity.DownloadedVideoIndexEntity

/**
 * Room 数据库入口
 *
 * 版本管理：
 * - v1: 当前版本，包含所有表（每次安装从头创建）
 *
 * Migration 策略：
 * - 无需 Migration，用户重装 App 即可
 */
@Database(
    entities = [
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        ApiCacheEntity::class,
        DownloadTaskEntity::class,
        DownloadedVideoIndexEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MovieDatabase : RoomDatabase() {

    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun apiCacheDao(): ApiCacheDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun downloadedVideoIndexDao(): DownloadedVideoIndexDao

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
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
