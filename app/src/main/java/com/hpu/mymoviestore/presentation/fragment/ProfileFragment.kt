package com.hpu.mymoviestore.presentation.fragment

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.cache.DanmakuCache
import com.hpu.mymoviestore.databinding.DialogClearCacheBinding
import com.hpu.mymoviestore.databinding.FragmentProfileBinding
import com.hpu.mymoviestore.presentation.activity.DownloadActivity
import com.hpu.mymoviestore.presentation.activity.HistoryActivity
import com.hpu.mymoviestore.presentation.activity.MainActivity
import com.hpu.mymoviestore.presentation.danmaku.DanmakuPrefs
import com.hpu.mymoviestore.presentation.dialog.DialogSizing
import com.hpu.mymoviestore.presentation.help.HelpDialog
import com.hpu.mymoviestore.presentation.settings.ThemeManager
import com.hpu.mymoviestore.presentation.source.VideoSourceDialog
import com.hpu.mymoviestore.presentation.tv.TvContentKeyHandler
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvInitialFocusProvider
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.update.AboutDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "我的" 页面 —— 个人中心，包含视频源管理、弹幕开关、历史记录、下载管理、
 * 清理缓存、帮助和关于等入口。
 */
class ProfileFragment : Fragment(), TvInitialFocusProvider, TvContentKeyHandler {

    /** 电视端判定：启动时定死，形态不做动态切换（与 MainActivity 一致） */
    private var isTv: Boolean = false

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isTv = TvUiSupport.isTelevision(requireContext())
        restoreSourceEnabledStates()
        setupThemeToggle()
        setupClickListeners()
        setupTvFocus()
    }

    /**
     * TV 适配（横屏两栏）：右栏设置菜单每张卡片 + 左栏主题切换按钮需可被遥控器聚焦。
     *
     * 两处电视端专属处理：
     *  - 隐藏「下载管理」：文件落在电视本机没有意义（与详情页隐藏下载入口一致）。
     *    只置 GONE 不改布局 —— 两份布局的 id 必须一致，否则 ViewBinding 生成字段会变成可空。
     *  - 弹幕行做成「整行一个焦点」：`SwitchMaterial` 自身可聚焦，会让同一行出现两个焦点停靠点，
     *    上下键会在「卡片 <-> 开关」之间乱跳。改为开关不可聚焦，焦点停在卡片上，
     *    按确定键（DPAD_CENTER -> performClick）切换开关。
     */
    private fun setupTvFocus() {
        if (isTv) {
            binding.cardDownload.visibility = View.GONE
            binding.switchDanmu.isFocusable = false
            binding.cardDanmu.setOnClickListener { binding.switchDanmu.toggle() }
        }
        tvMenuCards().forEach { TvFocus.applyTo(it, scale = 1.02f) }
        TvFocus.applyTo(binding.btnThemeToggle, scale = 1.1f)
    }

    /**
     * 右栏设置菜单的功能卡片（顺序 = 视觉顺序）。
     * 电视端「下载管理」被隐藏，不计入 —— 焦点不会落到看不见的控件上。
     */
    private fun tvMenuCards(): List<View> = listOfNotNull(
        binding.cardVideoSource,
        binding.cardDanmu,
        binding.cardHistory,
        if (isTv) null else binding.cardDownload,
        binding.cardClearCache,
        binding.cardHelp,
        binding.cardAbout
    )

    /**
     * 电视端：顶部标签栏按「下键」进入本页时的落点 —— 设置菜单第一项。
     * 必须实时返回（ViewPager2 会复用 Fragment，onViewCreated 只跑一次，缓存会过期）。
     */
    override fun tvInitialFocusView(): View? {
        if (!isTv) return null
        return tvMenuCards().firstOrNull()
    }

    /**
     * 电视端方向键兜底接管。
     *
     * 为什么必须接管：左右分栏下左栏只有「主题切换按钮」一个可聚焦控件（在图片右上角），
     * 它与右栏菜单之间**没有稳定的几何上下关系** ——
     *  · 焦点在按钮上按「下键」：系统在按钮正下方找不到候选（菜单在右侧），要么不动、要么乱跳；
     *  · 焦点在按钮上按「上键」：左栏已无更高候选，系统的几何搜索会兜到右栏菜单，
     *    表现为「按钮 <-> 菜单」来回跳、永远回不到标签栏。
     * 所以两栏之间的移动在这里显式规定：**上键（按钮处、或菜单已到第一项）一律交回
     * 顶部标签栏的当前页签**；菜单内部的上键逐项上移。其余方向交回系统（返回 false）。
     */
    override fun onContentDirectionKey(direction: Int): Boolean {
        if (!isTv) return false
        val focused = activity?.currentFocus ?: binding.root.findFocus() ?: return false
        val cards = tvMenuCards()
        val firstCard = cards.firstOrNull() ?: return false

        // 主题切换按钮：上键 -> 回顶部标签栏；右键 / 下键 -> 进入设置菜单第一项
        if (focused === binding.btnThemeToggle) {
            if (direction == View.FOCUS_UP) return focusTopNavBar()
            return (direction == View.FOCUS_RIGHT || direction == View.FOCUS_DOWN) &&
                firstCard.requestFocus()
        }

        if (cards.none { it === focused }) return false   // 焦点不在菜单里，不干预
        // 左键 -> 回主题切换按钮
        if (direction == View.FOCUS_LEFT) return binding.btnThemeToggle.requestFocus()
        // 上键：菜单内部逐项上移；第一项已到顶 -> 回顶部标签栏
        if (direction == View.FOCUS_UP) {
            val index = cards.indexOfFirst { it === focused }
            if (index <= 0) return focusTopNavBar()
            return cards[index - 1].requestFocus()
        }
        return false
    }

    /** 把焦点交回顶部标签栏的当前页签（本页即「我的」） */
    private fun focusTopNavBar(): Boolean =
        (activity as? MainActivity)?.focusCurrentNavItem() == true

    /** 主题切换：按当前模式渲染按钮图标与头部背景图；点击切换后 Activity 自动重建，本方法随之再次刷新 */
    private fun setupThemeToggle() {
        applyThemeUi()
        binding.btnThemeToggle.setOnClickListener {
            ThemeManager.toggle(requireContext())
            // setDefaultNightMode 触发 Activity 重建 → onViewCreated → applyThemeUi() 刷新图标与背景图
        }
    }

    /** 深色：背景 movie_background + 太阳图标（点击切浅色）；浅色：movie_background_light + 月亮图标（点击切深色） */
    private fun applyThemeUi() {
        val light = ThemeManager.isLightMode(requireContext())
        binding.ivHeaderBackground.setImageResource(
            if (light) R.drawable.movie_background_light else R.drawable.movie_background
        )
        binding.btnThemeToggle.setImageResource(
            if (light) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun
        )
    }

    private fun setupClickListeners() {
        // 视频源管理 —— 居中卡片弹框（多选开关，全选/全不选，至少选一个）
        binding.cardVideoSource.setOnClickListener {
            VideoSourceDialog.newInstance()
                .show(parentFragmentManager, "VideoSourceDialog")
        }

        // 弹幕 —— 滑动开关，默认开启（持久化到 SharedPreferences）
        val prefs = DanmakuPrefs(requireContext())
        binding.switchDanmu.isChecked = prefs.isMasterEnabled()
        binding.switchDanmu.setOnCheckedChangeListener { _, isChecked ->
            prefs.setMasterEnabled(isChecked)
            Toast.makeText(
                requireContext(),
                "弹幕${if (isChecked) "已开启" else "已关闭"}",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 历史记录 —— 跳转到现有历史页面
        binding.cardHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }

        // 下载管理 —— 跳转到下载管理页面
        binding.cardDownload.setOnClickListener {
            startActivity(Intent(requireContext(), DownloadActivity::class.java))
        }

        // 清理缓存 —— 弹框，选择性清理
        binding.cardClearCache.setOnClickListener {
            showClearCacheDialog()
        }

        // 帮助
        binding.cardHelp.setOnClickListener {
            HelpDialog.newInstance()
                .show(parentFragmentManager, "HelpDialog")
        }

        // 关于
        binding.cardAbout.setOnClickListener {
            AboutDialog.newInstance()
                .show(parentFragmentManager, "AboutDialog")
        }
    }

    /**
     * 从 SharedPreferences 恢复视频源启用状态。
     */
    private fun restoreSourceEnabledStates() {
        val app = MovieApplication.get()
        val sources = app.allVideoSources
        val prefs = requireContext().getSharedPreferences("video_sources", Context.MODE_PRIVATE)
        sources.forEach { source ->
            val enabled = prefs.getBoolean("enabled_${source.sourceId}", true)
            source.enabled = enabled
        }
    }

    // ================== 清理缓存 ==================

    /**
     * 清理缓存弹框（美观自定义 UI）
     */
    private fun showClearCacheDialog() {
        val dialogBinding = DialogClearCacheBinding.inflate(layoutInflater)

        val dialog = AlertDialog.Builder(requireContext(), R.style.ClearCacheDialog)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.6f)
        }

        // 计算并显示缓存大小
        lifecycleScope.launch(Dispatchers.IO) {
            val totalSize = calculateTotalCacheSize()
            withContext(Dispatchers.Main) {
                dialogBinding.tvCacheSize.text = "缓存大小：${formatSize(totalSize)}"
            }
        }

        // 缓存项配置：(标题, 描述, 图标, 是否默认选中)
        // 默认选中：搜索缓存、详情页缓存、播放地址缓存（常用且重建成本低）
        // 首页缓存与弹幕缓存不默认选中（弹幕缓存清掉后需重新拉取，代价较高）
        val cacheItems = listOf(
            CacheItem("清理搜索缓存", "删除所有搜索相关的缓存记录", R.drawable.ic_player_search, true),
            CacheItem("清理首页缓存", "删除首页列表缓存数据", R.drawable.ic_player_home, false),
            CacheItem("清理详情页缓存", "删除所有详情页元数据", R.drawable.ic_player_detail, true),
            CacheItem("清理播放地址缓存", "删除所有缓存的 m3u8 地址", R.drawable.ic_player_play, true),
            CacheItem("清理弹幕缓存", "删除所有本地弹幕 JSON 文件及弹幕源选择记录", R.drawable.ic_player_danmaku, false),
            CacheItem("清理全部缓存", "删除以上所有内容", R.drawable.ic_player_clear_all, false)
        )

        val selectedItems = mutableSetOf<Int>()
        cacheItems.forEachIndexed { index, item ->
            if (item.checkedByDefault) selectedItems.add(index)
            val itemView = createCacheItemView(item, index in selectedItems) { isChecked ->
                if (isChecked) selectedItems.add(index) else selectedItems.remove(index)
                // 如果选了"全部"，自动选中其他项；如果取消"全部"，不影响其他项
                if (index == 5 && isChecked) {
                    (0..4).forEach { selectedItems.add(it) }
                    refreshAllItems(dialogBinding.container, cacheItems, selectedItems)
                }
            }
            dialogBinding.container.addView(itemView)
        }

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirm.setOnClickListener {
            if (selectedItems.isEmpty()) {
                Toast.makeText(requireContext(), "未选择任何缓存项", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performClearCache(selectedItems, cacheItems) { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
        // 与其他弹窗统一：以屏宽短边为准的居中卡片宽度
        DialogSizing.applyCenteredCard(dialog, requireContext())
        DialogSizing.limitContentHeight(
            dialogBinding.scrollContent,
            DialogSizing.contentMaxHeightPx(requireContext())
        )
        // TV 适配：缓存条目行 / 取消 / 清理 按钮可遥控器聚焦。
        // 本弹窗此前**完全没有任何 TvFocus 调用** —— 条目行靠 layout-land/item_clear_cache.xml 里的
        // focusable="true" 能停焦点，但没人给它挂焦点环，系统默认高亮又被关掉，
        // 于是「取消 / 清理」按下去只有焦点在动、屏幕上毫无变化。
        TvFocus.applyToDialogButtons(dialogBinding.root)
    }

    private fun createCacheItemView(
        item: CacheItem,
        isChecked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_clear_cache, null)

        val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvDesc = view.findViewById<TextView>(R.id.tvDesc)
        val ivCheck = view.findViewById<ImageView>(R.id.ivCheck)

        ivIcon.setImageResource(item.iconRes)
        tvTitle.text = item.title
        tvDesc.text = item.desc
        updateCheckState(ivCheck, isChecked)

        view.setOnClickListener {
            val newChecked = ivCheck.tag != true
            ivCheck.tag = newChecked
            updateCheckState(ivCheck, newChecked)
            onCheckedChange(newChecked)
        }

        // TV 适配：挂环放在**创建处**而不是弹窗那一次遍历里 —— 勾选「清理全部缓存」会
        // refreshAllItems() 把条目整批 removeAllViews + 重建，那批新视图赶不上首次遍历，
        // 会变成「焦点能停上去但一点高亮都没有」。scale 传 1f：整行控件放大必溢出。
        TvFocus.applyTo(view, 1f)

        return view
    }

    private fun updateCheckState(ivCheck: ImageView, checked: Boolean) {
        ivCheck.tag = checked
        ivCheck.setImageResource(
            if (checked) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
        )
        ivCheck.alpha = if (checked) 1.0f else 0.4f
    }

    private fun refreshAllItems(
        container: LinearLayout,
        items: List<CacheItem>,
        selectedItems: MutableSet<Int>
    ) {
        container.removeAllViews()
        items.forEachIndexed { index, item ->
            val itemView = createCacheItemView(item, index in selectedItems) { isChecked ->
                if (isChecked) selectedItems.add(index) else selectedItems.remove(index)
                // 如果选了"全部"，自动选中其他项；如果取消"全部"，不影响其他项
                if (index == 5 && isChecked) {
                    (0..4).forEach { selectedItems.add(it) }
                    refreshAllItems(container, items, selectedItems)
                }
            }
            container.addView(itemView)
        }
    }

    private fun performClearCache(
        selectedItems: Set<Int>,
        cacheItems: List<CacheItem>,
        onComplete: (String) -> Unit
    ) {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val app = MovieApplication.get()
            val cacheRepo = app.apiCacheRepository
            val results = mutableListOf<String>()

            // 1. 清理搜索缓存（所有源的爬虫搜索 + 本地搜索历史）
            // 通配符模式匹配所有源的搜索缓存键，新增源无需修改此处
            if (5 in selectedItems || 0 in selectedItems) {
                val rows = cacheRepo.deleteByPattern("%:search:v3:%")
                // 同时清理本地搜索历史
                app.searchHistoryRepository.clearAllHistory()
                results.add("搜索缓存 (${rows}条)")
            }

            // 2. 清理首页缓存
            if (5 in selectedItems || 1 in selectedItems) {
                val rows = cacheRepo.deleteByPrefix("home:tab:")
                results.add("首页缓存 (${rows}条)")
            }

            // 3. 清理详情页缓存（通配符匹配，兼容所有源前缀）
            if (5 in selectedItems || 2 in selectedItems) {
                val rows = cacheRepo.deleteByPattern("%:detail:meta%")
                results.add("详情页缓存 (${rows}条)")
            }

            // 4. 清理播放地址缓存（通配符匹配，兼容所有源前缀）
            if (5 in selectedItems || 3 in selectedItems) {
                val rows1 = cacheRepo.deleteByPattern("%:play:real_url%")
                val rows2 = cacheRepo.deleteByPattern("%:detail:first_play_page%")
                results.add("播放地址缓存 (${rows1 + rows2}条)")
            }

            // 5. 清理弹幕缓存（包括弹幕源选择记录 + 本地弹幕 JSON 文件）
            if (5 in selectedItems || 4 in selectedItems) {
                DanmakuCache(ctx).clearAll()
                DanmakuPrefs(ctx).clearSavedAnimeChoices()
                // 删除 filesDir/Danmaku/ 下的弹幕 JSON 文件，但保留下载任务引用的弹幕文件（离线播放依赖）
                var deletedDanmakuFiles = 0
                val danmakuDir = java.io.File(ctx.filesDir, "Danmaku")
                if (danmakuDir.exists() && danmakuDir.isDirectory) {
                    val keptPaths = try {
                        app.downloadRepository.getAllTaskDanmakuFilePaths().toSet()
                    } catch (t: Throwable) {
                        Log.w("ProfileFragment", "查询任务弹幕路径失败，跳过文件删除: ${t.message}")
                        null
                    }
                    if (keptPaths != null) {
                        danmakuDir.listFiles()?.forEach { f ->
                            if (f.isFile && f.absolutePath !in keptPaths && f.delete()) {
                                deletedDanmakuFiles++
                            }
                        }
                    }
                }
                results.add(
                    if (deletedDanmakuFiles > 0) "弹幕缓存及弹幕源选择记录 (${deletedDanmakuFiles}个文件)"
                    else "弹幕缓存及弹幕源选择记录"
                )
            }

            // 6. 清理 Coil 图片磁盘缓存（cacheDir/image_cache，LRU 缓存，删除后自动重建）
            if (5 in selectedItems) {
                try {
                    coil.Coil.imageLoader(ctx).diskCache?.clear()
                    results.add("图片缓存")
                } catch (t: Throwable) {
                    Log.w("ProfileFragment", "清理图片缓存失败: ${t.message}")
                }
            }

            withContext(Dispatchers.Main) {
                val message = if (results.isEmpty()) "未清理任何缓存" else "已清理: ${results.joinToString(", ")}"
                onComplete(message)
            }
        }
    }

    data class CacheItem(
        val title: String,
        val desc: String,
        val iconRes: Int,
        val checkedByDefault: Boolean
    )

    // ================== 缓存大小计算 ==================

    /**
     * 计算所有缓存的总大小（Room 数据库 + SharedPreferences + 图片缓存）
     */
    private suspend fun calculateTotalCacheSize(): Long {
        var total = 0L
        val ctx = requireContext()
        withContext(Dispatchers.IO) {
            // 1. Room 数据库文件大小
            val dbFile = ctx.getDatabasePath("movie_database")
            if (dbFile.exists()) total += dbFile.length()

            // 2. SharedPreferences 文件大小（弹幕缓存等）
            val prefsDir = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    total += file.length()
                }
            }

            // 3. cacheDir 整体（Coil 图片缓存 + WebView 缓存 + 更新安装包等），已覆盖其全部子目录
            val cacheDir = ctx.cacheDir
            if (cacheDir.exists() && cacheDir.isDirectory) {
                total += calculateDirSize(cacheDir)
            }
        }
        return total
    }

    private fun calculateDirSize(dir: java.io.File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    // ================== 帮助 & 关于 ==================

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
