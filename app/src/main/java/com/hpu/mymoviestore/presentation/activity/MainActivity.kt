package com.hpu.mymoviestore.presentation.activity

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.databinding.ActivityMainBinding
import com.hpu.mymoviestore.presentation.fragment.HomeFragment
import com.hpu.mymoviestore.presentation.fragment.ProfileFragment
import com.hpu.mymoviestore.presentation.fragment.SearchFragment

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

    /**
     * 从 ViewPager2 获取搜索 Fragment 实例
     * FragmentStateAdapter 的 tag 格式为 "f{position}"
     */
    private fun getSearchFragment(): SearchFragment? {
        val tag = "f${tabIds.indexOf(R.id.nav_search)}"
        return supportFragmentManager.findFragmentByTag(tag) as? SearchFragment
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
