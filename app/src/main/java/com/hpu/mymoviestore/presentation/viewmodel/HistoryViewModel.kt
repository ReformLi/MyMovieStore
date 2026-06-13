package com.hpu.mymoviestore.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.data.entity.PlayHistoryEntity
import kotlinx.coroutines.launch

/**
 * 播放历史 ViewModel
 *
 * - 获取全部历史：getAllHistory()（LiveData，自动刷新）
 * - 写入/更新播放历史：addOrUpdateHistory(...)
 * - 清空：clearAllHistory()
 */
class HistoryViewModel : ViewModel() {

    private val repository = MovieApplication.get().playHistoryRepository

    private val _clearStatus = MutableLiveData<Boolean>()
    val clearStatus: LiveData<Boolean> = _clearStatus

    fun getAllHistory(): LiveData<List<PlayHistoryEntity>> = repository.getAllHistory()

    fun addOrUpdateHistory(
        videoId: Long,
        title: String,
        coverUrl: String,
        category: String,
        playUrl: String
    ) {
        viewModelScope.launch {
            val id = repository.addOrUpdateHistory(
                videoId = videoId,
                title = title,
                coverUrl = coverUrl,
                category = category,
                playUrl = playUrl
            )
            Log.d(TAG, "addOrUpdateHistory: videoId=$videoId, title=$title, historyRowId=$id")
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            Log.d(TAG, "clearAllHistory: 清空完成")
            _clearStatus.postValue(true)
        }
    }

    companion object {
        private const val TAG = "HistoryViewModel"
    }
}
