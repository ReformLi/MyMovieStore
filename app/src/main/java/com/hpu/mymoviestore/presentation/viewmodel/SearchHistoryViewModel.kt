package com.hpu.mymoviestore.presentation.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.mymoviestore.data.entity.SearchHistoryEntity
import com.hpu.mymoviestore.data.repository.SearchHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 搜索历史 ViewModel
 *
 * - searchHistory：来自 Room 的 LiveData，自动驱动 UI
 * - addKeyword(keyword)：写入/刷新一条搜索记录（SearchFragment 搜索时调用）
 * - deleteKeyword(keyword)：单条删除（用户点搜索记录右侧 "x"）
 * - clearAll()：清空全部（用户点"清空搜索历史"按钮）
 */
class SearchHistoryViewModel(private val repository: SearchHistoryRepository) : ViewModel() {

    private val _searchHistory: LiveData<List<SearchHistoryEntity>> = repository.getAllHistory()
    val searchHistory: LiveData<List<SearchHistoryEntity>> = _searchHistory

    private val _toast = MutableLiveData<String>()
    val toast: LiveData<String> = _toast

    fun addKeyword(keyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addOrUpdateKeyword(keyword)
        }
    }

    fun deleteKeyword(keyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteByKeyword(keyword)
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllHistory()
            _toast.postValue("已清空搜索历史")
        }
    }
}
