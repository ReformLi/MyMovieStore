package com.hpu.mymoviestore.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * - v1: videos / favorites / play_history / video_sources / categories
 * - v2: play_history 表扩展 playUrl 字段
 * - v3: 移除 favorites / videos / video_sources / categories；play_history 为主表
 * - v4: ① play_history 扩展 playProgressSeconds / durationSeconds（续播）
 *       ② 新增 search_history（搜索历史）
 *       ③ 新增 api_cache（爬虫源响应缓存，TTL 自动过期）
 * - v5: play_history 扩展 detailUrl / playPageUrl / episodeTitle，
 *       用于从历史记录回到完整详情页并定位上次播放集数
 * - v6: play_history 扩展 sourceName，标识视频来源
 * - v7: ① 新增 download_task（下载任务管理，支持分片下载进度与弹幕独立状态）
 *       ② 新增 downloaded_video_index（已下载视频索引，快速判断是否已下载）
 *
 * 配合 fallbackToDestructiveMigration 使用：升级时重建数据库，避免 schema hash 校验失败。
 */
@Database(
    entities = [
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        ApiCacheEntity::class,
        DownloadTaskEntity::class,
        DownloadedVideoIndexEntity::class
    ],
    version = 7,
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

        /** v5 -> v6: play_history 新增 sourceName 列 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN sourceName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6 -> v7: 新增 download_task 和 downloaded_video_index 表 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 创建下载任务表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_task (
                        taskId TEXT NOT NULL PRIMARY KEY,
                        videoId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        episodeTitle TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        playUrl TEXT NOT NULL,
                        localFilePath TEXT NOT NULL,
                        totalSegments INTEGER NOT NULL,
                        downloadedSegments INTEGER NOT NULL,
                        fileSize INTEGER NOT NULL,
                        status INTEGER NOT NULL,
                        errorMsg TEXT NOT NULL,
                        createTime INTEGER NOT NULL,
                        updateTime INTEGER NOT NULL,
                        sourceName TEXT NOT NULL,
                        danmakuFilePath TEXT NOT NULL,
                        danmakuStatus INTEGER NOT NULL,
                        danmakuRetryCount INTEGER NOT NULL,
                        danmakuError TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                // 创建已下载视频索引表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_video_index (
                        videoId INTEGER NOT NULL PRIMARY KEY,
                        episodeId TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        downloadTime INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): MovieDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MovieDatabase::class.java,
                    "movie_database"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
