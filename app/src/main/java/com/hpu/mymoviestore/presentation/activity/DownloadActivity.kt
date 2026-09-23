package com.hpu.mymoviestore.presentation.activity

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.entity.DownloadTaskEntity
import com.hpu.mymoviestore.databinding.ActivityDownloadBinding
import com.hpu.mymoviestore.presentation.adapter.CompletedAdapter
import com.hpu.mymoviestore.presentation.adapter.DownloadPagerAdapter
import com.hpu.mymoviestore.presentation.adapter.DownloadingAdapter
import com.hpu.mymoviestore.presentation.dialog.ConfirmDialog
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.viewmodel.AppViewModelFactory
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

    /** TV 适配：电视端放大 UI 密度（10-foot UI），手机端原样返回 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hpu.mymoviestore.presentation.tv.TvUiSupport.wrapContext(newBase))
    }


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
            onDanmakuRetry = { task -> onDanmakuRetry(task) },
            onDeleteFailed = { task -> onTaskDelete(task) }
        )
    }

    // 已完成适配器
    private val completedAdapter: CompletedAdapter by lazy {
        CompletedAdapter(
            onPlay = { task -> onTaskPlay(task) },
            onDelete = { task -> onTaskDelete(task) },
            onDanmakuDownload = { task -> onTaskDanmakuDownload(task) },
            onSelectionChanged = { selectedIds -> onSelectionChanged(selectedIds) }
        )
    }

    // 多选模式状态
    private var isInMultiSelectMode = false
    private var hasCheckedInitialTab = false
    /** TV 适配：是否已完成首次焦点定位（避免从弹窗返回时打断用户当前位置） */
    private var hasSetInitialFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 形态定方向（必须在 inflate 之前）：手机恒竖屏、电视恒横屏
        requestedOrientation =
            if (com.hpu.mymoviestore.presentation.tv.TvUiSupport.isTelevision(this)) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            AppViewModelFactory(application as MovieApplication)
        )[DownloadViewModel::class.java]

        setupToolbar()
        setupViewPager()
        setupObservers()
        refreshStorage()
        applySystemBarInsets()

    }

    /** TV 适配：首次进入页面时把焦点交给当前页列表首项（后续返回不打断用户当前位置） */
    override fun onResume() {
        super.onResume()
        if (!hasSetInitialFocus) {
            hasSetInitialFocus = true
            focusCurrentPageList()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (binding.viewPager.currentItem == DownloadPagerAdapter.PAGE_DOWNLOADING) {
            menuInflater.inflate(R.menu.menu_download_toolbar, menu)
        } else if (isInMultiSelectMode) {
            menuInflater.inflate(R.menu.menu_download_batch_delete, menu)
        }
        // TV 适配：多选模式会重建工具栏菜单项（批量删除），补一次焦点遍历
        binding.toolbar.post { TvFocus.applyToClickables(binding.toolbar) }
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
                ConfirmDialog.show(
                    context = this,
                    title = "批量删除",
                    message = "确定要删除选中的 ${selectedIds.size} 个下载任务吗？",
                    positiveText = "删除",
                    negativeText = "取消"
                ) {
                    val ids = completedAdapter.deleteSelected()
                    viewModel.deleteTasks(ids)
                    exitMultiSelectMode()
                    Toast.makeText(this, "已删除 ${ids.size} 个任务", Toast.LENGTH_SHORT).show()
                }
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

    /**
     * 消费系统栏 insets：**叠加**到布局声明的 padding 之上，而不是替代它。
     *
     * 基准 padding 只在挂监听前取一次，此后恒为「XML 值 + insets」。
     * 旧实现 `setPadding(0, top, 0, bottom)` 会把左右 padding 整段清零 ——
     * 这正是 layout-land 的 Activity 根布局一直无法声明 TV overscan 安全边距的原因：
     * 写在根上的 paddingStart/End 会被这里抹掉，只能一层层挂到子容器上。
     *
     * insets 取 systemBars ∪ displayCutout：刘海屏下两者的安全区不总是相等，
     * getInsets 传并集即为「逐边取大」。
     */
    private fun applySystemBarInsets() {
        val root = binding.root
        val baseStart = root.paddingStart
        val baseTop = root.paddingTop
        val baseEnd = root.paddingEnd
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPaddingRelative(
                baseStart + bars.left,
                baseTop + bars.top,
                baseEnd + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "下载管理"
        // TV 适配：工具栏的返回键 / 菜单项（全部暂停 / 全部继续）由 AppCompat 动态创建，
        // 布局完成后用通用遍历给它们补上焦点环；手机端整体 no-op（TvFocus 入口带形态门控）。
        binding.toolbar.doOnPreDraw { TvFocus.applyToClickables(binding.toolbar) }
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
                // TV 适配：切换标签后焦点落到该页列表首项
                focusCurrentPageList()
            }
        })

        // TV 适配：顶部「下载中 / 已完成」标签可遥控器聚焦
        for (i in 0 until binding.tabLayout.tabCount) {
            binding.tabLayout.getTabAt(i)?.view?.let { TvFocus.applyFocusableOnly(it) }
        }
    }

    /**
     * TV 适配：把焦点交给当前页列表的首项（电视进入页面/切换标签时的默认落点）。
     */
    private fun focusCurrentPageList() {
        if (!TvUiSupport.isTelevision(this)) return
        binding.viewPager.post {
            val inner = (binding.viewPager.getChildAt(0)
                as? androidx.recyclerview.widget.RecyclerView) ?: return@post
            val holder = inner.findViewHolderForAdapterPosition(binding.viewPager.currentItem)
                ?: return@post
            (holder.itemView as? androidx.recyclerview.widget.RecyclerView)?.let {
                TvFocus.focusFirstItem(it)
            }
        }
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
        ConfirmDialog.show(
            context = this,
            title = "删除任务",
            message = "确定要删除「${task.title} - ${task.episodeTitle}」的下载任务吗？已下载的分片也会被清除。",
            positiveText = "删除",
            negativeText = "返回"
        ) {
            viewModel.cancelTask(task.taskId)
            Log.d(TAG, "取消并删除任务: ${task.taskId}")
        }
    }

    private fun onTaskRetry(task: DownloadTaskEntity) {
        viewModel.retryTask(task.taskId)
        Log.d(TAG, "重试任务: ${task.taskId}")
    }

    private fun onDanmakuRetry(task: DownloadTaskEntity) {
        // 弹幕重试：仅重试弹幕下载，不重新下载视频
        viewModel.retryDanmaku(task)
        Toast.makeText(this, "正在重试弹幕下载", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "弹幕重试: ${task.taskId}")
    }

    private fun onTaskDanmakuDownload(task: DownloadTaskEntity) {
        // 弹幕下载：重试弹幕下载
        viewModel.retryDanmaku(task)
        Toast.makeText(this, "正在下载弹幕", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "弹幕下载: ${task.taskId}")
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
        ConfirmDialog.show(
            context = this,
            title = "删除任务",
            message = "确定要删除「${task.title} - ${task.episodeTitle}」吗？\n下载的文件也将被删除。",
            positiveText = "删除",
            negativeText = "取消"
        ) {
            viewModel.deleteTask(task.taskId)
            Log.d(TAG, "删除任务: ${task.taskId}")
        }
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
