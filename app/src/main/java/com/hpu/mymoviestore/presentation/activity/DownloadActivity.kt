package com.hpu.mymoviestore.presentation.activity

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.databinding.ActivityDownloadBinding
import com.hpu.mymoviestore.presentation.adapter.CompletedAdapter
import com.hpu.mymoviestore.presentation.adapter.DownloadPagerAdapter
import com.hpu.mymoviestore.presentation.adapter.DownloadingAdapter
import com.hpu.mymoviestore.presentation.viewmodel.DownloadViewModel

/**
 * 下载管理页面
 *
 * 功能：
 * - 两个标签页：下载中 / 已完成（TabLayout + ViewPager2）
 * - 顶部显示总占用空间和剩余存储空间
 * - 下载中标签页：RecyclerView 列表，支持暂停/继续/取消/重试操作，弹幕状态管理
 * - 已完成标签页：RecyclerView 列表，支持播放/删除，长按进入多选批量删除
 * - 使用 DownloadViewModel 观察数据变化
 */
class DownloadActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DownloadActivity"
    }

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var viewModel: DownloadViewModel

    // 下载中适配器
    private val downloadingAdapter: DownloadingAdapter by lazy {
        DownloadingAdapter(
            onPauseResume = { task -> onTaskPauseResume(task) },
            onCancel = { task -> onTaskCancel(task) },
            onRetry = { task -> onTaskRetry(task) },
            onDanmakuRetry = { task -> onDanmakuRetry(task) }
        )
    }

    // 已完成适配器
    private val completedAdapter: CompletedAdapter by lazy {
        CompletedAdapter(
            onPlay = { task -> onTaskPlay(task) },
            onDelete = { task -> onTaskDelete(task) },
            onSelectionChanged = { selectedIds -> onSelectionChanged(selectedIds) }
        )
    }

    // 多选模式状态
    private var isInMultiSelectMode = false
    private var hasCheckedInitialTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[DownloadViewModel::class.java]

        setupToolbar()
        setupViewPager()
        setupObservers()
        refreshStorage()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (binding.viewPager.currentItem == DownloadPagerAdapter.PAGE_DOWNLOADING) {
            menuInflater.inflate(R.menu.menu_download_toolbar, menu)
        } else if (isInMultiSelectMode) {
            menuInflater.inflate(R.menu.menu_download_batch_delete, menu)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (isInMultiSelectMode) {
                    exitMultiSelectMode()
                } else {
                    finish()
                }
                true
            }
            R.id.action_pause_all -> {
                viewModel.pauseAll()
                Toast.makeText(this, "已暂停全部下载", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_resume_all -> {
                viewModel.resumeAll()
                Toast.makeText(this, "已恢复全部下载", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_batch_delete -> {
                val selectedIds = completedAdapter.getSelectedIds()
                if (selectedIds.isEmpty()) {
                    Toast.makeText(this, "未选择任何项目", Toast.LENGTH_SHORT).show()
                    return true
                }
                AlertDialog.Builder(this)
                    .setTitle("批量删除")
                    .setMessage("确定要删除选中的 ${selectedIds.size} 个下载任务吗？")
                    .setPositiveButton("删除") { _, _ ->
                        val ids = completedAdapter.deleteSelected()
                        viewModel.deleteTasks(ids)
                        exitMultiSelectMode()
                        Toast.makeText(this, "已删除 ${ids.size} 个任务", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (isInMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }

    // ======================== 初始化 ========================

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "下载管理"
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = DownloadPagerAdapter(downloadingAdapter, completedAdapter)

        // TabLayout 与 ViewPager2 关联
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "下载中"
                1 -> "已完成"
                else -> ""
            }
        }.attach()

        // 页面切换时刷新菜单
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                invalidateOptionsMenu()
                if (position == DownloadPagerAdapter.PAGE_COMPLETED && isInMultiSelectMode) {
                    invalidateOptionsMenu()
                }
            }
        })
    }

    private fun setupObservers() {
        // 观察下载中的任务列表
        viewModel.downloadingTasks.observe(this) { tasks ->
            Log.d(TAG, "下载中任务更新: ${tasks.size} 条")
            downloadingAdapter.submitList(tasks)
            // 首次数据到达时，如果下载中列表为空，自动切换到已完成标签页
            if (!hasCheckedInitialTab) {
                hasCheckedInitialTab = true
                if (tasks.isEmpty()) {
                    binding.viewPager.currentItem = DownloadPagerAdapter.PAGE_COMPLETED
                }
            }
        }

        // 观察已完成的任务列表
        viewModel.completedTasks.observe(this) { tasks ->
            Log.d(TAG, "已完成任务更新: ${tasks.size} 条")
            completedAdapter.submitList(tasks)
        }

        // 观察存储信息
        viewModel.totalStorageSize.observe(this) { size ->
            binding.tvTotalStorage.text = "总占用：$size"
        }

        viewModel.freeStorageSize.observe(this) { size ->
            binding.tvFreeStorage.text = "剩余：$size"
        }
    }

    private fun refreshStorage() {
        viewModel.refreshStorageInfo()
    }

    // ======================== 下载中操作 ========================

    private fun onTaskPauseResume(task: DownloadTaskEntity) {
        when (task.status) {
            DownloadTaskEntity.STATUS_DOWNLOADING,
            DownloadTaskEntity.STATUS_PENDING -> {
                viewModel.pauseTask(task.taskId)
                Log.d(TAG, "暂停任务: ${task.taskId}")
            }
            DownloadTaskEntity.STATUS_PAUSED -> {
                viewModel.resumeTask(task.taskId)
                Log.d(TAG, "恢复任务: ${task.taskId}")
            }
        }
    }

    private fun onTaskCancel(task: DownloadTaskEntity) {
        AlertDialog.Builder(this)
            .setTitle("取消下载")
            .setMessage("确定要取消「${task.title} - ${task.episodeTitle}」的下载吗？")
            .setPositiveButton("取消下载") { _, _ ->
                viewModel.cancelTask(task.taskId)
                Log.d(TAG, "取消任务: ${task.taskId}")
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun onTaskRetry(task: DownloadTaskEntity) {
        viewModel.retryTask(task.taskId)
        Log.d(TAG, "重试任务: ${task.taskId}")
    }

    private fun onDanmakuRetry(task: DownloadTaskEntity) {
        // 弹幕重试：通过 repository 的 retryDanmaku 方法
        viewModel.retryTask(task.taskId)
        Log.d(TAG, "弹幕重试: ${task.taskId}")
    }

    // ======================== 已完成操作 ========================

    private fun onTaskPlay(task: DownloadTaskEntity) {
        // 跳转到播放器播放本地文件（使用离线播放专用 Intent）
        Log.d(TAG, "播放本地文件: ${task.localFilePath}, taskId=${task.taskId}")
        startActivity(
            PlayerActivity.newIntent(
                context = this,
                localFilePath = task.localFilePath,
                danmakuFilePath = task.danmakuFilePath.ifEmpty { null },
                title = task.title,
                episodeTitle = task.episodeTitle
            ).apply {
                putExtra("extra_offline_task_id", task.taskId)
            }
        )
    }

    private fun onTaskDelete(task: DownloadTaskEntity) {
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除「${task.title} - ${task.episodeTitle}」吗？\n下载的文件也将被删除。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteTask(task.taskId)
                Log.d(TAG, "删除任务: ${task.taskId}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onSelectionChanged(selectedIds: Set<String>) {
        isInMultiSelectMode = selectedIds.isNotEmpty()
        invalidateOptionsMenu()
        if (selectedIds.isNotEmpty()) {
            supportActionBar?.title = "已选择 ${selectedIds.size} 项"
        } else {
            supportActionBar?.title = "下载管理"
        }
    }

    private fun exitMultiSelectMode() {
        completedAdapter.exitMultiSelectMode()
        isInMultiSelectMode = false
        supportActionBar?.title = "下载管理"
        invalidateOptionsMenu()
    }
}
