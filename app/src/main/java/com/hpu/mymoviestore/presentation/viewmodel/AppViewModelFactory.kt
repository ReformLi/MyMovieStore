package com.hpu.mymoviestore.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hpu.mymoviestore.MovieApplication

/**
 * 轻量级 ViewModel 工厂（DI-lite）
 *
 * 从 MovieApplication 持有的全局单例构造各 ViewModel，
 * 替代 ViewModel 内部直接 MovieApplication.get() 取依赖的服务定位器写法。
 *
 * 用法：
 * - Activity：ViewModelProvider(this, AppViewModelFactory(application as MovieApplication))[XxxViewModel::class.java]
 * - Fragment：ViewModelProvider(this, AppViewModelFactory(requireActivity().application as MovieApplication))[XxxViewModel::class.java]
 */
class AppViewModelFactory(private val app: MovieApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(VideoViewModel::class.java) ->
                VideoViewModel(app.videoRepository)

            modelClass.isAssignableFrom(PlayerViewModel::class.java) ->
                PlayerViewModel(app.playHistoryRepository, app.videoRepository)

            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(app.playHistoryRepository)

            modelClass.isAssignableFrom(DownloadViewModel::class.java) ->
                DownloadViewModel(app, app.downloadRepository)

            modelClass.isAssignableFrom(SearchHistoryViewModel::class.java) ->
                SearchHistoryViewModel(app.searchHistoryRepository)

            else -> throw IllegalArgumentException("未知的 ViewModel 类型: ${modelClass.name}")
        } as T
    }
}
