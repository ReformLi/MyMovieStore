package com.hpu.mymoviestore.presentation.activity

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.databinding.ActivityMainBinding
import com.hpu.mymoviestore.presentation.fragment.HistoryFragment
import com.hpu.mymoviestore.presentation.fragment.HomeFragment
import com.hpu.mymoviestore.presentation.fragment.SearchFragment

/**
 * 应用主页面 —— 底部导航承载
 * Tab 列表：首页 / 搜索 / 历史 （收藏功能已移除）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastBackPressedTime: Long = 0L
    private var homeFragment: HomeFragment? = null
    private var searchFragment: SearchFragment? = null
    private var historyFragment: HistoryFragment? = null
    private var pendingSearchKeyword: String? = null
    private var resetSearchOnNextShow: Boolean = false

    private val onNavigationItemSelectedListener =
        BottomNavigationView.OnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    Log.d(TAG, "切换到首页")
                    showFragment(R.id.nav_home)
                    true
                }
                R.id.nav_search -> {
                    Log.d(TAG, "切换到搜索")
                    resetSearchOnNextShow = pendingSearchKeyword == null
                    showFragment(R.id.nav_search)
                    true
                }
                R.id.nav_history -> {
                    Log.d(TAG, "切换到播放历史")
                    showFragment(R.id.nav_history)
                    true
                }
                else -> false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.bottomNavigation.setOnItemSelectedListener(onNavigationItemSelectedListener)
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_search && pendingSearchKeyword == null) {
                Log.d(TAG, "重新点击搜索导航，重置搜索页")
                resetSearchOnNextShow = true
                showFragment(R.id.nav_search)
            }
        }
        setupBackPressed()

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        } else {
            restoreFragments()
            showFragment(binding.bottomNavigation.selectedItemId)
        }
    }

    fun navigateToSearchWithKeyword(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isBlank()) return
        Log.d(TAG, "首页内容发现跳转搜索: keyword=$cleanKeyword")
        pendingSearchKeyword = cleanKeyword
        if (binding.bottomNavigation.selectedItemId == R.id.nav_search) {
            showFragment(R.id.nav_search)
        } else {
            binding.bottomNavigation.selectedItemId = R.id.nav_search
        }
    }

    private fun showFragment(itemId: Int) {
        restoreFragments()
        val target = when (itemId) {
            R.id.nav_home -> homeFragment ?: HomeFragment().also { homeFragment = it }
            R.id.nav_search -> searchFragment ?: SearchFragment().also { searchFragment = it }
            R.id.nav_history -> historyFragment ?: HistoryFragment().also { historyFragment = it }
            else -> homeFragment ?: HomeFragment().also { homeFragment = it }
        }
        val targetTag = tagForItem(itemId)

        val transaction = supportFragmentManager.beginTransaction()
        listOf(homeFragment, searchFragment, historyFragment).forEach { fragment ->
            if (fragment != null && fragment.isAdded) {
                transaction.hide(fragment)
            }
        }
        if (target.isAdded) {
            transaction.show(target)
        } else {
            transaction.add(R.id.fragmentContainer, target, targetTag)
        }
        transaction.commit()

        if (itemId == R.id.nav_search) {
            binding.fragmentContainer.post {
                if (pendingSearchKeyword != null) {
                    deliverPendingSearchKeyword()
                } else {
                    resetSearchIfNeeded()
                }
            }
        }
    }

    private fun restoreFragments() {
        homeFragment = homeFragment
            ?: supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_HOME) as? HomeFragment
        searchFragment = searchFragment
            ?: supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_SEARCH) as? SearchFragment
        historyFragment = historyFragment
            ?: supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_HISTORY) as? HistoryFragment
    }

    private fun deliverPendingSearchKeyword() {
        val keyword = pendingSearchKeyword ?: return
        val fragment = searchFragment ?: return
        if (!fragment.isAdded) return
        pendingSearchKeyword = null
        resetSearchOnNextShow = false
        fragment.searchFromExternal(keyword)
    }

    private fun resetSearchIfNeeded() {
        if (!resetSearchOnNextShow || pendingSearchKeyword != null) return
        val fragment = searchFragment ?: return
        if (!fragment.isAdded) return
        resetSearchOnNextShow = false
        fragment.resetToInitialState()
    }

    private fun tagForItem(itemId: Int): String {
        return when (itemId) {
            R.id.nav_search -> FRAGMENT_TAG_SEARCH
            R.id.nav_history -> FRAGMENT_TAG_HISTORY
            else -> FRAGMENT_TAG_HOME
        }
    }

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
                if (binding.bottomNavigation.selectedItemId != R.id.nav_home) {
                    binding.bottomNavigation.selectedItemId = R.id.nav_home
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
        private const val FRAGMENT_TAG_HOME = "fragment_home"
        private const val FRAGMENT_TAG_SEARCH = "fragment_search"
        private const val FRAGMENT_TAG_HISTORY = "fragment_history"
    }
}
