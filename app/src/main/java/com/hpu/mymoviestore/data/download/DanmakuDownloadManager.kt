package com.hpu.mymoviestore.data.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hpu.mymoviestore.data.dao.DownloadTaskDao
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import com.hpu.mymoviestore.data.repository.DanmakuRepository
import com.hpu.mymoviestore.presentation.danmaku.DanmakuPrefs
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 弹幕下载管理器
 *
 * 功能：
 * - 单例模式，通过 getInstance(context) 获取
 * - 使用 CoroutineScope(SupervisorJob() + Dispatchers.IO) 管理协程
 * - 下载弹幕流程：搜索候选 -> 获取分集 -> 获取弹幕 -> 序列化保存 -> 更新数据库
 * - 自动重试策略：首次失败后自动重试，最多5次，固定间隔（1min）
 * - 手动重试：retryDanmaku() 不计入自动重试次数
 */
class DanmakuDownloadManager private constructor(context: Context) {

    companion object {
        private const val TAG = "DanmakuDownloadMgr"

        /** 自动重试最大次数 */
        private const val MAX_AUTO_RETRY = 5

        /** 自动重试固定间隔（毫秒）：1min */
        private const val BASE_RETRY_DELAY_MS = 60_000L

        @Volatile
        private var instance: DanmakuDownloadManager? = null

        fun getInstance(context: Context): DanmakuDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DanmakuDownloadManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext: Context = context.applicationContext

    /** 协程作用域，使用 SupervisorJob 管理弹幕下载任务生命周期 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repository = DanmakuRepository(context = appContext)

    /** Moshi 实例，用于序列化弹幕列表为 JSON */
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val danmakuListAdapter = moshi.adapter<List<DanmakuComment>>(
        List::class.java as Class<List<DanmakuComment>>
    )

    /** 弹幕文件保存目录 */
    private val danmakuDir: File
        get() = File(appContext.filesDir, "Danmaku").also { it.mkdirs() }

    /** 记录每个任务的自动重试次数 */
    private val retryCountMap = ConcurrentHashMap<String, AtomicInteger>()

    /** 记录每个任务的协程 Job，用于取消 */
    private val jobMap = ConcurrentHashMap<String, Job>()

    /** 回调列表（支持多个监听者） */
    private val callbacks = mutableListOf<DanmakuDownloadCallback>()

    // ======================== 回调管理 ========================

    /**
     * 注册弹幕下载状态回调
     */
    fun addCallback(callback: DanmakuDownloadCallback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    /**
     * 移除弹幕下载状态回调
     */
    fun removeCallback(callback: DanmakuDownloadCallback) {
        callbacks.remove(callback)
    }

    /**
     * 通知所有回调监听者
     */
    private fun notifyStatusChanged(taskId: String, status: Int, error: String?) {
        callbacks.forEach { callback ->
            try {
                callback.onDanmakuStatusChanged(taskId, status, error)
            } catch (e: Exception) {
                Log.w(TAG, "回调通知异常: ${e.message}")
            }
        }
    }

    // ======================== 公开方法 ========================

    /**
     * 开始下载弹幕
     *
     * @param taskId 下载任务 ID
     * @param title 视频标题（用于搜索弹幕源）
     * @param episodeTitle 集数标题（如 "第3集"，用于匹配集数）
     * @param dao DownloadTaskDao，用于更新数据库状态
     */
    fun startDanmakuDownload(
        taskId: String,
        title: String,
        episodeTitle: String,
        dao: DownloadTaskDao
    ) {
        // 取消该任务之前可能存在的下载协程
        jobMap[taskId]?.cancel()

        Log.d(TAG, "开始下载弹幕: taskId=$taskId, title=$title, episode=$episodeTitle")

        val job = scope.launch {
            downloadDanmakuWithRetry(taskId, title, episodeTitle, dao, isManualRetry = false)
        }
        jobMap[taskId] = job
    }

    /**
     * 手动重试弹幕下载
     *
     * 不计入自动重试次数，直接执行一次完整下载流程。
     *
     * @param taskId 下载任务 ID
     * @param title 视频标题
     * @param episodeTitle 集数标题
     * @param dao DownloadTaskDao
     */
    fun retryDanmaku(
        taskId: String,
        title: String,
        episodeTitle: String,
        dao: DownloadTaskDao
    ) {
        // 取消该任务之前可能存在的下载协程
        jobMap[taskId]?.cancel()
        // 重置自动重试计数，让手动重试后的后续自动重试从 0 开始
        retryCountMap.remove(taskId)

        Log.d(TAG, "手动重试弹幕下载: taskId=$taskId, title=$title, episode=$episodeTitle")

        val job = scope.launch {
            downloadDanmakuWithRetry(taskId, title, episodeTitle, dao, isManualRetry = true)
        }
        jobMap[taskId] = job
    }

    /**
     * 取消指定任务的弹幕下载
     */
    fun cancelDanmakuDownload(taskId: String) {
        jobMap[taskId]?.cancel()
        jobMap.remove(taskId)
        retryCountMap.remove(taskId)
        Log.d(TAG, "已取消弹幕下载: taskId=$taskId")
    }

    // ======================== 核心下载流程 ========================

    /**
     * 带重试的弹幕下载
     *
     * @param isManualRetry 是否为手动重试（手动重试不计入自动重试次数）
     */
    private suspend fun downloadDanmakuWithRetry(
        taskId: String,
        title: String,
        episodeTitle: String,
        dao: DownloadTaskDao,
        isManualRetry: Boolean
    ) {
        try {
            // 更新状态为下载中
            dao.updateDanmakuStatus(
                taskId = taskId,
                danmakuStatus = DownloadTaskEntity.DANMAKU_DOWNLOADING,
                danmakuFilePath = "",
                danmakuError = ""
            )
            notifyStatusChanged(taskId, DownloadTaskEntity.DANMAKU_DOWNLOADING, null)

            // 执行下载
            val result = executeDanmakuDownload(taskId, title, episodeTitle, dao)

            if (result.isSuccess) {
                // 下载成功
                dao.updateDanmakuStatus(
                    taskId = taskId,
                    danmakuStatus = DownloadTaskEntity.DANMAKU_COMPLETED,
                    danmakuFilePath = result.filePath,
                    danmakuError = ""
                )
                notifyStatusChanged(taskId, DownloadTaskEntity.DANMAKU_COMPLETED, null)
                retryCountMap.remove(taskId)
                jobMap.remove(taskId)
                Log.d(TAG, "弹幕下载成功: taskId=$taskId, path=${result.filePath}")
            } else {
                // 下载失败，处理重试逻辑
                handleDownloadFailure(taskId, title, episodeTitle, dao, result.error, isManualRetry)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "弹幕下载被取消: taskId=$taskId")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "弹幕下载异常: taskId=$taskId, error=${e.message}", e)
            handleDownloadFailure(taskId, title, episodeTitle, dao, e.message ?: "未知异常", isManualRetry)
        }
    }

    /**
     * 执行单次弹幕下载流程
     *
     * 流程：
     * 1. searchCandidates(title) 搜索弹幕源
     * 2. fetchBangumi(animeId) 获取分集
     * 3. fetchDanmakuComments(bangumi, episodeNumber) 获取弹幕列表
     * 4. 序列化为 JSON，保存到 Danmaku/{taskId}.json
     *
     * @return DownloadResult 包含成功/失败状态、文件路径或错误信息
     */
    private suspend fun executeDanmakuDownload(
        taskId: String,
        title: String,
        episodeTitle: String,
        dao: DownloadTaskDao
    ): DownloadResult {
        // 1. 搜索弹幕候选源
        Log.d(TAG, "[$taskId] 搜索弹幕源: $title")
        val candidates = repository.searchCandidates(title)
        if (candidates.isEmpty()) {
            val error = "未找到弹幕源: $title"
            Log.w(TAG, "[$taskId] $error")
            return DownloadResult(error = error)
        }

        // 优先使用保存的弹幕源偏好
        val videoIdFromTask = taskId.substringBefore('_').toLongOrNull() ?: 0L
        val savedAnimeId = if (videoIdFromTask > 0L) {
            DanmakuPrefs(appContext).getSavedAnimeId(videoIdFromTask)
        } else {
            0L
        }

        val anime = if (savedAnimeId > 0L) {
            candidates.find { it.animeId == savedAnimeId }
        } else {
            null
        } ?: candidates.first()

        if (savedAnimeId > 0L && anime.animeId == savedAnimeId) {
            Log.d(TAG, "[$taskId] 使用保存的弹幕源: animeId=${anime.animeId}, title=${anime.animeTitle}")
        } else {
            Log.d(TAG, "[$taskId] 使用默认弹幕源: animeId=${anime.animeId}, title=${anime.animeTitle}")
        }

        // 2. 获取分集信息
        Log.d(TAG, "[$taskId] 获取分集信息: animeId=${anime.animeId}")
        val bangumi = repository.fetchBangumi(anime.animeId, keyword = title)
        if (bangumi == null) {
            val error = "获取分集信息失败: animeId=${anime.animeId}"
            Log.w(TAG, "[$taskId] $error")
            return DownloadResult(error = error)
        }

        // 3. 获取弹幕列表
        Log.d(TAG, "[$taskId] 获取弹幕列表: episodeTitle=$episodeTitle")
        val comments = repository.fetchDanmakuComments(
            bangumi = bangumi,
            preferredEpisodeNumber = episodeTitle,
            keyword = title
        )
        if (comments.isEmpty()) {
            val error = "未获取到弹幕数据: ${anime.animeTitle} $episodeTitle"
            Log.w(TAG, "[$taskId] $error")
            return DownloadResult(error = error)
        }

        Log.d(TAG, "[$taskId] 获取到 ${comments.size} 条弹幕")

        // 4. 序列化为 JSON 并保存到文件
        val json = serializeDanmakuToJson(comments)
        val danmakuFile = File(danmakuDir, "$taskId.json")
        danmakuFile.writeText(json)

        Log.d(TAG, "[$taskId] 弹幕已保存: ${danmakuFile.absolutePath}, size=${danmakuFile.length()}")

        return DownloadResult(isSuccess = true, filePath = danmakuFile.absolutePath)
    }

    /**
     * 处理下载失败，决定是否自动重试
     */
    private suspend fun handleDownloadFailure(
        taskId: String,
        title: String,
        episodeTitle: String,
        dao: DownloadTaskDao,
        error: String,
        isManualRetry: Boolean
    ) {
        if (isManualRetry) {
            // 手动重试失败，提示用户
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "当前弹幕源没有该集数据，请手动选择其他源", Toast.LENGTH_LONG).show()
            }
            Log.w(TAG, "手动重试失败: taskId=$taskId, error=$error")
            dao.updateDanmakuStatus(
                taskId = taskId,
                danmakuStatus = DownloadTaskEntity.DANMAKU_FAILED,
                danmakuFilePath = "",
                danmakuError = error
            )
            notifyStatusChanged(taskId, DownloadTaskEntity.DANMAKU_FAILED, error)
            jobMap.remove(taskId)
            return
        }

        // 自动重试逻辑
        val countHolder = retryCountMap.getOrPut(taskId) { AtomicInteger(0) }
        val currentRetry = countHolder.getAndIncrement()

        if (currentRetry < MAX_AUTO_RETRY) {
            // 还有重试机会，固定间隔重试
            val delayMs = BASE_RETRY_DELAY_MS // 固定 1min
            Log.d(TAG, "弹幕下载失败，将在 ${delayMs / 1000}s 后进行第 ${currentRetry + 1} 次自动重试: taskId=$taskId, error=$error")

            // 更新状态为重试中
            dao.updateDanmakuStatus(
                taskId = taskId,
                danmakuStatus = DownloadTaskEntity.DANMAKU_RETRYING,
                danmakuFilePath = "",
                danmakuError = "第${currentRetry + 1}次重试中，${delayMs / 1000}s后执行"
            )
            notifyStatusChanged(taskId, DownloadTaskEntity.DANMAKU_RETRYING, null)

            // 等待退避间隔
            delay(delayMs)

            // 再次尝试下载（递归调用，isManualRetry=false 继续计入重试次数）
            downloadDanmakuWithRetry(taskId, title, episodeTitle, dao, isManualRetry = false)
        } else {
            // 重试次数耗尽，标记为最终失败
            Log.e(TAG, "弹幕下载失败，已耗尽 $MAX_AUTO_RETRY 次自动重试: taskId=$taskId, error=$error")
            dao.updateDanmakuStatus(
                taskId = taskId,
                danmakuStatus = DownloadTaskEntity.DANMAKU_FAILED,
                danmakuFilePath = "",
                danmakuError = "重试${MAX_AUTO_RETRY}次后仍失败: $error"
            )
            notifyStatusChanged(taskId, DownloadTaskEntity.DANMAKU_FAILED, "重试${MAX_AUTO_RETRY}次后仍失败: $error")
            retryCountMap.remove(taskId)
            jobMap.remove(taskId)
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 将弹幕列表序列化为 JSON 字符串
     */
    private fun serializeDanmakuToJson(comments: List<DanmakuComment>): String {
        return danmakuListAdapter.toJson(comments)
    }

    /**
     * 下载结果封装
     */
    private data class DownloadResult(
        val isSuccess: Boolean = false,
        val filePath: String = "",
        val error: String = ""
    )

    // ======================== 回调接口 ========================

    /**
     * 弹幕下载状态回调接口
     */
    interface DanmakuDownloadCallback {
        /**
         * 弹幕下载状态变更通知
         *
         * @param taskId 下载任务 ID
         * @param status 弹幕状态码
         *   - 0: 未下载
         *   - 1: 下载中
         *   - 2: 已完成
         *   - 3: 失败
         *   - 4: 重试中
         * @param error 错误信息（成功时为 null）
         */
        fun onDanmakuStatusChanged(taskId: String, status: Int, error: String?)
    }
}
