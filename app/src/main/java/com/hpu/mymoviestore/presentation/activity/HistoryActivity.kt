package com.hpu.mymoviestore.presentation.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.databinding.ActivityHistoryBinding
import com.hpu.mymoviestore.presentation.fragment.HistoryFragment

/**
 * 历史记录页面 —— 承载 HistoryFragment 的独立 Activity。
 * 从"我的"页面点击"历史记录"后跳转至此。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "历史记录"

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, HistoryFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
