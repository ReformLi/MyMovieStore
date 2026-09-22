package com.hpu.mymoviestore.presentation.activity

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import com.hpu.mymoviestore.R
import com.hpu.mymoviestore.databinding.ActivityHistoryBinding
import com.hpu.mymoviestore.presentation.fragment.HistoryFragment
import com.hpu.mymoviestore.presentation.tv.TvFocus

/**
 * 历史记录页面 —— 承载 HistoryFragment 的独立 Activity。
 * 从"我的"页面点击"历史记录"后跳转至此。
 */
class HistoryActivity : AppCompatActivity() {

    /** TV 适配：电视端放大 UI 密度（10-foot UI），手机端原样返回 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hpu.mymoviestore.presentation.tv.TvUiSupport.wrapContext(newBase))
    }


    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 形态定方向（必须在 inflate 之前）：手机恒竖屏、电视恒横屏
        requestedOrientation =
            if (com.hpu.mymoviestore.presentation.tv.TvUiSupport.isTelevision(this)) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "历史记录"
        // TV 适配：工具栏的返回键 / 菜单项由 AppCompat 动态创建（没有资源 id 可引用），
        // 布局完成后用通用遍历给它们补上焦点环；手机端整体 no-op（TvFocus 入口带形态门控）。
        binding.toolbar.doOnPreDraw { TvFocus.applyToClickables(binding.toolbar) }

        applySystemBarInsets()

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
}
