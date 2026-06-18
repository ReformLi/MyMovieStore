package com.hpu.mymoviestore.presentation.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.mymoviestore.R

/**
 * 下载管理页 ViewPager2 适配器
 *
 * 为 ViewPager2 提供两个页面：下载中 / 已完成。
 * 每个页面包含一个 RecyclerView，分别绑定 DownloadingAdapter 和 CompletedAdapter。
 */
class DownloadPagerAdapter(
    private val downloadingAdapter: DownloadingAdapter,
    private val completedAdapter: CompletedAdapter
) : RecyclerView.Adapter<DownloadPagerAdapter.PageViewHolder>() {

    override fun getItemCount(): Int = PAGE_COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val recyclerView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_page, parent, false) as RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(parent.context)
        return PageViewHolder(recyclerView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        super.onBindViewHolder(holder, position, payloads)
    }

    inner class PageViewHolder(
        private val recyclerView: RecyclerView
    ) : RecyclerView.ViewHolder(recyclerView) {

        fun bind(position: Int) {
            recyclerView.adapter = when (position) {
                PAGE_DOWNLOADING -> downloadingAdapter
                PAGE_COMPLETED -> completedAdapter
                else -> throw IllegalArgumentException("Invalid page position: $position")
            }
        }
    }

    companion object {
        const val PAGE_COUNT = 2
        const val PAGE_DOWNLOADING = 0
        const val PAGE_COMPLETED = 1
    }
}
