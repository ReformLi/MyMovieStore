package com.hpu.mymoviestore.presentation.fragment

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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
import com.hpu.mymoviestore.presentation.danmaku.DanmakuPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "我的" 页面 —— 个人中心，包含视频源管理、弹幕开关、历史记录、下载管理、
 * 清理缓存、帮助和关于等入口。
 */
class ProfileFragment : Fragment() {

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
        restoreSourceEnabledStates()
        setupClickListeners()
    }

    private fun setupClickListeners() {
        // 视频源管理 —— 弹框（多选开关，至少选一个，默认全选）
        binding.cardVideoSource.setOnClickListener {
            showVideoSourceDialog()
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
            showHelpDialog()
        }

        // 关于
        binding.cardAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    /**
     * 视频源管理弹框
     * 使用真实的 VideoSource 列表，从 MovieApplication 获取。
     * 保存启用状态到 SharedPreferences。
     */
    private fun showVideoSourceDialog() {
        val app = MovieApplication.get()
        val sources = app.allVideoSources
        if (sources.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可用的视频源", Toast.LENGTH_SHORT).show()
            return
        }

        val sourceNames = sources.map { it.sourceName }.toTypedArray()
        val checked = sources.map { it.enabled }.toBooleanArray()

        AlertDialog.Builder(requireContext(), R.style.RoundedDialog)
            .setTitle("视频源管理")
            .setMultiChoiceItems(sourceNames, checked) { _, which, isChecked ->
                checked[which] = isChecked
                // 确保至少选一个
                if (!checked.any { it }) {
                    checked[which] = true
                    Toast.makeText(requireContext(), "至少需要选择一个视频源", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("确定") { _, _ ->
                // 更新源的启用状态
                sources.forEachIndexed { index, source ->
                    source.enabled = checked[index]
                }
                // 保存到 SharedPreferences
                saveSourceEnabledStates(checked)
                val selected = sources.filterIndexed { index, _ -> checked[index] }
                Toast.makeText(
                    requireContext(),
                    "已选择: ${selected.joinToString(", ") { it.sourceName }}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 保存视频源启用状态到 SharedPreferences。
     */
    private fun saveSourceEnabledStates(checked: BooleanArray) {
        val app = MovieApplication.get()
        val sources = app.allVideoSources
        val prefs = requireContext().getSharedPreferences("video_sources", Context.MODE_PRIVATE)
        prefs.edit().apply {
            sources.forEachIndexed { index, source ->
                putBoolean("enabled_${source.sourceId}", checked[index])
            }
            apply()
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
        val cacheItems = listOf(
            CacheItem("清理搜索缓存", "删除所有搜索相关的缓存记录", R.drawable.ic_player_search, true),
            CacheItem("清理首页缓存", "删除首页列表缓存数据", R.drawable.ic_player_home, true),
            CacheItem("清理详情页缓存", "删除所有详情页元数据", R.drawable.ic_player_detail, true),
            CacheItem("清理播放地址缓存", "删除所有缓存的 m3u8 地址", R.drawable.ic_player_play, true),
            CacheItem("清理弹幕缓存", "删除所有本地弹幕 JSON 文件及弹幕源选择记录", R.drawable.ic_player_danmaku, true),
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

            // 3. 清理详情页缓存
            if (5 in selectedItems || 2 in selectedItems) {
                val rows = cacheRepo.deleteByPrefix(":detail:meta")
                results.add("详情页缓存 (${rows}条)")
            }

            // 4. 清理播放地址缓存
            if (5 in selectedItems || 3 in selectedItems) {
                val rows1 = cacheRepo.deleteByPrefix(":play:real_url")
                val rows2 = cacheRepo.deleteByPrefix(":detail:first_play_page")
                results.add("播放地址缓存 (${rows1 + rows2}条)")
            }

            // 5. 清理弹幕缓存（包括弹幕源选择记录）
            if (5 in selectedItems || 4 in selectedItems) {
                DanmakuCache(requireContext()).clearAll()
                DanmakuPrefs(requireContext()).clearSavedAnimeChoices()
                results.add("弹幕缓存及弹幕源选择记录")
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
        withContext(Dispatchers.IO) {
            // 1. Room 数据库文件大小
            val dbFile = requireContext().getDatabasePath("movie_database")
            if (dbFile.exists()) total += dbFile.length()

            // 2. SharedPreferences 文件大小（弹幕缓存等）
            val prefsDir = java.io.File(requireContext().applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    total += file.length()
                }
            }

            // 3. Coil 图片缓存
            val cacheDir = requireContext().cacheDir
            if (cacheDir.exists() && cacheDir.isDirectory) {
                total += calculateDirSize(cacheDir)
            }

            // 4. WebView 缓存
            val webViewCache = java.io.File(requireContext().cacheDir, "WebView")
            if (webViewCache.exists()) {
                total += calculateDirSize(webViewCache)
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

    /**
     * 帮助弹框
     */
    private fun showHelpDialog() {
        AlertDialog.Builder(requireContext(), R.style.RoundedDialog)
            .setTitle("帮助")
            .setMessage(
                "欢迎使用我的影视！\n\n" +
                    "1. 首页发现：浏览豆瓣热门影视内容\n" +
                    "2. 搜索播放：输入片名搜索可播放资源\n" +
                    "3. 播放历史：自动保存观看记录，支持续播\n" +
                    "4. 视频源：可在我的页面中管理播放源\n\n" +
                    "如遇问题，请检查网络连接后重试。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 关于弹框
     */
    private fun showAboutDialog() {
        AlertDialog.Builder(requireContext(), R.style.RoundedDialog)
            .setTitle("关于")
            .setMessage(
                "我的影视\n" +
                    "版本: 1.0\n\n" +
                    "一款简洁的影视浏览与播放应用，\n" +
                    "聚合豆瓣内容发现与在线播放资源。\n\n" +
                    "仅供学习交流使用。"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
