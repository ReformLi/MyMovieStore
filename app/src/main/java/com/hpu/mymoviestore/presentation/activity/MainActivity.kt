package com.hpu.mymoviestore.presentation.activity

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
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

    private val onNavigationItemSelectedListener =
        BottomNavigationView.OnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    Log.d(TAG, "切换到首页")
                    replaceFragment(HomeFragment())
                    true
                }
                R.id.nav_search -> {
                    Log.d(TAG, "切换到搜索")
                    replaceFragment(SearchFragment())
                    true
                }
                R.id.nav_history -> {
                    Log.d(TAG, "切换到播放历史")
                    replaceFragment(HistoryFragment())
                    true
                }
                else -> false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNavigation.setOnItemSelectedListener(onNavigationItemSelectedListener)

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
