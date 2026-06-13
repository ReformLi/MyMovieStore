package com.hpu.mymoviestore.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import com.hpu.mymoviestore.data.model.VideoItem
import kotlinx.coroutines.launch

/**
 * 播放器 ViewModel
 *
 * 负责：
 * - 启动播放器时去重写入播放历史
 * - 提供视频信息缓存
 */
class PlayerViewModel : ViewModel() {

    private val playHistoryRepository = MovieApplication.get().playHistoryRepository
    private val videoRepository = MovieApplication.get().videoRepository

    private val _videoUrl = MutableLiveData<String>()
    val videoUrl: LiveData<String> = _videoUrl

    private val _videoTitle = MutableLiveData<String>()
    val videoTitle: LiveData<String> = _videoTitle

    private val _playStatus = MutableLiveData<String>()
    val playStatus: LiveData<String> = _playStatus

    /**
     * 设置视频信息：写入播放历史
     * 注意：videoId / title / coverUrl / category / playUrl 都必须提供
     */
    fun setVideoInfo(
        videoId: Long,
        title: String,
        coverUrl: String,
        category: String,
        playUrl: String,
        onReady: () -> Unit = {}
    ) {
        Log.d(TAG, "setVideoInfo: videoId=$videoId, title=$title")
        _videoTitle.postValue(title)
        _videoUrl.postValue(playUrl)

        // 去重写入或更新播放历史
        viewModelScope.launch {
            val historyId = playHistoryRepository.addOrUpdateHistory(
                videoId = videoId,
                title = title,
                coverUrl = coverUrl,
                category = category,
                playUrl = playUrl
            )
            Log.d(TAG, "播放历史写入完成: historyId=$historyId")
            onReady()
        }
    }

    /**
     * 更新播放进度（秒）+ 总时长
     * 由 PlayerActivity 在播放过程中定期调用
     */
    fun updateProgress(videoId: Long, progressSeconds: Long, durationSeconds: Long) {
        viewModelScope.launch {
            playHistoryRepository.updateProgress(
                videoId = videoId,
                progressSeconds = progressSeconds,
                durationSeconds = durationSeconds
            )
        }
    }

    /**
     * 根据 videoId 读取播放历史记录（含进度），用于启动时续播
     */
    fun getHistoryByVideoId(videoId: Long, onResult: (PlayHistoryEntity?) -> Unit) {
        viewModelScope.launch {
            val history = playHistoryRepository.getHistoryByVideoId(videoId)
            onResult(history)
        }
    }

    /** 根据 id 获取视频信息（供详情页或播放器使用） */
    fun getVideoInfoById(videoId: Long, onResult: (VideoItem?) -> Unit) {
        viewModelScope.launch {
            val item = videoRepository.getVideoById(videoId)
            Log.d(TAG, "getVideoInfoById: videoId=$videoId, item=${item?.title}")
            onResult(item)
        }
    }

    /** 播放状态更新（从 Activity 回调） */
    fun updatePlayStatus(status: String) {
        _playStatus.postValue(status)
    }

    companion object {
        private const val TAG = "PlayerViewModel"
    }
}
