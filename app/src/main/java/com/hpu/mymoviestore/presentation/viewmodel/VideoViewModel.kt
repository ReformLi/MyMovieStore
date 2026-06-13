package com.hpu.mymoviestore.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频 ViewModel —— 负责从 VideoRepository（JSON 挡板）获取视频列表
 *
 * 数据流程：
 * - loadAllVideos()        异步加载 → _allVideos LiveData
 * - loadVideosByCategory(category) 按分类过滤
 * - searchVideos(keyword)  关键字搜索
 * - getVideoById(id)       详情页异步获取单条视频
 */
class VideoViewModel : ViewModel() {

    private val repository = MovieApplication.get().videoRepository

    private val _allVideos = MutableLiveData<List<VideoItem>>()
    val allVideos: LiveData<List<VideoItem>> = _allVideos

    private val _filterVideos = MutableLiveData<List<VideoItem>>()
    val filterVideos: LiveData<List<VideoItem>> = _filterVideos

    private val _searchVideos = MutableLiveData<List<VideoItem>>()
    val searchVideos: LiveData<List<VideoItem>> = _searchVideos

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    /**
     * 加载全量视频列表（来自 JSON 挡板）
     * 第一次会从 assets JSON 解析并缓存到内存。
     */
    fun loadAllVideos() {
        Log.d(TAG, "loadAllVideos()")
        viewModelScope.launch {
            _loading.postValue(true)
            val list = repository.getAllVideos()
            Log.d(TAG, "拿到全量视频列表: ${list.size} 条")
            _allVideos.postValue(list)
            _loading.postValue(false)
        }
    }

    /** 按分类过滤视频列表 */
    fun loadVideosByCategory(category: String) {
        Log.d(TAG, "loadVideosByCategory(category=$category)")
        viewModelScope.launch {
            _loading.postValue(true)
            val list = repository.getVideosByCategory(category)
            _filterVideos.postValue(list)
            _loading.postValue(false)
        }
    }

    /** 关键字搜索视频列表 */
    fun searchVideos(keyword: String) {
        Log.d(TAG, "searchVideos(keyword=$keyword)")
        viewModelScope.launch {
            _loading.postValue(true)
            val list = repository.searchVideos(keyword)
            _searchVideos.postValue(list)
            _loading.postValue(false)
        }
    }

    /** 异步根据 id 获取视频信息，回调中返回 */
    fun getVideoById(id: Long, onResult: (VideoItem?) -> Unit) {
        Log.d(TAG, "getVideoById(id=$id)")
        viewModelScope.launch {
            val item = repository.getVideoById(id)
            withContext(Dispatchers.Main) { onResult(item) }
        }
    }

    companion object {
        private const val TAG = "VideoViewModel"
    }
}
