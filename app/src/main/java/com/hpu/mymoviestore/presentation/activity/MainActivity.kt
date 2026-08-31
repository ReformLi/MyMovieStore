package com.hpu.mymoviestore.presentation.activity

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.hpu.mymoviestore.BuildConfig
import com.hpu.mymoviestore.MovieApplication
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.data.repository.PermissionConfigRepository
import com.hpu.mymoviestore.databinding.ActivityMainBinding
import com.hpu.mymoviestore.presentation.fragment.HomeFragment
import com.hpu.mymoviestore.presentation.fragment.ProfileFragment
import com.hpu.mymoviestore.presentation.fragment.SearchFragment
import com.hpu.mymoviestore.presentation.update.UpdatePrefs
import kotlinx.coroutines.launch

/**
 * 应用主页面 —— 底部导航 + ViewPager2 承载
 * Tab 列表：首页 / 搜索 / 我的
 *
 * ViewPager2 提供丝滑的左右滑动切换效果，与底部导航栏双向同步。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastBackPressedTime: Long = 0L
    private var pendingSearchKeyword: String? = null
    private var resetSearchOnNextShow: Boolean = false

    /** 底部导航 Tab 顺序，与 ViewPager2 页面索引一一对应 */
    private val tabIds = listOf(R.id.nav_home, R.id.nav_search, R.id.nav_profile)

    /** ViewPager2 适配器 */
    private lateinit var pagerAdapter: MainPagerAdapter

    /**
     * 搜索页的进入方式：
     *  - MANUAL：从底部导航栏点击进入，初始展示搜索原页面
     *  - EXTERNAL：从首页点击影视跳转进入，自动按片名搜索
     * 用于区分系统返回键的处理策略。
     */
    private enum class SearchEntryMode { MANUAL, EXTERNAL }
    private var searchEntryMode: SearchEntryMode = SearchEntryMode.MANUAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        setupViewPager()
        setupBottomNavigation()
        setupBackPressed()
        checkUpdateOnLaunch()
    }

    /**
     * 启动时检查更新（纯提示，不跳转）。
     *
     * - 有新版本且今日未跳过 → 弹窗告知「设置 → 关于 → 检查更新」
     * - 用户可选「下次再说」（下次启动再弹）或「今天不再提醒」（当天不再弹）
     */
    private fun checkUpdateOnLaunch() {
        lifecycleScope.launch {
            try {
                val updateInfo = MovieApplication.get().permissionConfigRepository.checkUpdate()
                    ?: return@launch
                if (!UpdatePrefs(this@MainActivity).shouldShowToday()) {
                    Log.d(TAG, "更新提示：用户已选择今天不再提醒，跳过弹窗")
                    return@launch
                }
                showUpdateTipDialog(updateInfo.latestVersion, updateInfo.details)
            } catch (e: Exception) {
                Log.w(TAG, "更新检查异常: ${e.message}")
            }
        }
    }

    /**
     * 更新提示弹窗（纯提示，无跳转按钮）。
     * 用户按引导自行前往「我的 → 关于」检查更新。
     */
    private fun showUpdateTipDialog(latestVersion: String, details: String?) {
        val dialog = Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_update_tip, null)
        view.findViewById<TextView>(R.id.tvBadgeVersion).text = "v$latestVersion"

        val tvUpdateContent = view.findViewById<TextView>(R.id.tvUpdateContent)
        val cardUpdateContent = view.findViewById<View>(R.id.cardUpdateContent)
        if (details.isNullOrEmpty()) {
            cardUpdateContent.visibility = View.GONE
        } else {
            tvUpdateContent.text = details
        }

        // 主按钮：关闭弹窗（下次启动再提示）
        view.findViewById<MaterialButton>(R.id.btnGotIt).setOnClickListener {
            Log.d(TAG, "更新提示：用户选择知道了（下次再说）")
            dialog.dismiss()
        }
        // 次按钮：今天不再提醒
        view.findViewById<TextView>(R.id.tvSkipToday).setOnClickListener {
            UpdatePrefs(this).markSkipToday()
            Log.d(TAG, "更新提示：用户选择今天不再提醒")
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.setCancelable(true)
        dialog.show()
    }

    // ======================== ViewPager2 ========================

    private fun setupViewPager() {
        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        // 预加载所有页面，避免切换时重建 Fragment
        binding.viewPager.offscreenPageLimit = tabIds.size
        // 禁止超出边界的回弹效果
        (binding.viewPager.getChildAt(0) as? android.view.ViewGroup)?.let {
            it.getChildAt(0)?.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        }

        // ViewPager2 页面切换 → 同步底部导航 + 处理搜索页逻辑
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 同步底部导航栏选中状态
                if (position < tabIds.size) {
                    val tabId = tabIds[position]
                    if (binding.bottomNavigation.selectedItemId != tabId) {
                        binding.bottomNavigation.selectedItemId = tabId
                    }
                }

                // 搜索页可见时，处理待搜索关键词或重置
                if (tabIds.getOrNull(position) == R.id.nav_search) {
                    binding.viewPager.post {
                        if (pendingSearchKeyword != null) {
                            deliverPendingSearchKeyword()
                        } else {
                            resetSearchIfNeeded()
                        }
                    }
                }
            }
        })
    }

    // ======================== 底部导航 ========================

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val index = tabIds.indexOf(item.itemId)
            if (index >= 0) {
                // 仅在页面实际需要切换时设置标志（滑动触发的不重复设置）
                if (binding.viewPager.currentItem != index) {
                    if (item.itemId == R.id.nav_search && pendingSearchKeyword == null) {
                        searchEntryMode = SearchEntryMode.MANUAL
                        resetSearchOnNextShow = true
                    }
                    binding.viewPager.currentItem = index
                }
            }
            true
        }

        binding.bottomNavigation.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_search && pendingSearchKeyword == null) {
                Log.d(TAG, "重新点击搜索导航，重置搜索页")
                resetSearchOnNextShow = true
                resetSearchIfNeeded()
            }
        }
    }

    // ======================== 搜索相关 ========================

    /**
     * 从首页内容跳转到搜索页，携带搜索关键词
     */
    fun navigateToSearchWithKeyword(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return
        Log.d(TAG, "首页内容发现跳转搜索: keyword=$cleanKeyword")
        pendingSearchKeyword = cleanKeyword
        searchEntryMode = SearchEntryMode.EXTERNAL
        val searchIndex = tabIds.indexOf(R.id.nav_search)
        if (binding.viewPager.currentItem == searchIndex) {
            // 已在搜索页，直接投递关键词
            deliverPendingSearchKeyword()
        } else {
            // 切换到搜索页，onPageSelected 会投递关键词
            binding.viewPager.currentItem = searchIndex
        }
    }

    private fun getSearchFragment(): SearchFragment? {
        return supportFragmentManager.fragments
            .filterIsInstance<SearchFragment>()
            .firstOrNull()
    }

    private fun deliverPendingSearchKeyword() {
        val keyword = pendingSearchKeyword ?: return
        val fragment = getSearchFragment() ?: return
        if (!fragment.isAdded) return
        pendingSearchKeyword = null
        resetSearchOnNextShow = false
        fragment.searchFromExternal(keyword)
    }

    private fun resetSearchIfNeeded() {
        if (!resetSearchOnNextShow || pendingSearchKeyword != null) return
        val fragment = getSearchFragment() ?: return
        if (!fragment.isAdded) return
        resetSearchOnNextShow = false
        fragment.resetToInitialState()
    }

    // ======================== 系统适配 ========================

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentNav = tabIds.getOrNull(binding.viewPager.currentItem) ?: R.id.nav_home

                // 在搜索页：手动进入 + 已经展示搜索结果时，先回到搜索原页面
                if (currentNav == R.id.nav_search &&
                    searchEntryMode == SearchEntryMode.MANUAL &&
                    getSearchFragment()?.isShowingSearchResult() == true
                ) {
                    Log.d(TAG, "搜索结果页返回 → 回到搜索原页面")
                    getSearchFragment()?.resetToInitialState()
                    return
                }

                if (currentNav != R.id.nav_home) {
                    binding.viewPager.currentItem = 0
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastBackPressedTime <= EXIT_INTERVAL_MS) {
                    finish()
                } else {
                    lastBackPressedTime = now
                    Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val EXIT_INTERVAL_MS = 2_000L
    }
}

/**
 * 主页面 ViewPager2 适配器，管理三个 Fragment
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()
            1 -> SearchFragment()
            2 -> ProfileFragment()
            else -> HomeFragment()
        }
    }
}
