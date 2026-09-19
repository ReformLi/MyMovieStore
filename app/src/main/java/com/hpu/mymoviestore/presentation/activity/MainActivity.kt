package com.hpu.mymoviestore.presentation.activity

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.update.UpdatePrefs
import kotlinx.coroutines.launch

/**
 * 应用主页面 —— 顶部导航（首页 / 搜索 / 我的） + ViewPager2 承载
 *
 * ViewPager2 提供丝滑的左右滑动切换效果，与顶部导航栏双向同步。
 * （导航栏由布局约束固定在顶部；控件 id 沿用历史命名 `bottomNavigation`）
 */
class MainActivity : AppCompatActivity() {

    /** TV 适配：电视端放大 UI 密度（10-foot UI），手机端原样返回 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hpu.mymoviestore.presentation.tv.TvUiSupport.wrapContext(newBase))
    }


    private lateinit var binding: ActivityMainBinding
    private var lastBackPressedTime: Long = 0L
    private var pendingSearchKeyword: String? = null
    private var resetSearchOnNextShow: Boolean = false

    /** 顶部导航 Tab 顺序，与 ViewPager2 页面索引一一对应 */
    private val tabIds = listOf(R.id.nav_home, R.id.nav_search, R.id.nav_profile)

    /** TV 适配：导航项视图（顺序与 [tabIds] 一致）；仅用于电视端「焦点即选中」 */
    private var navItemViews: List<View> = emptyList()

    /** ViewPager2 适配器 */
    private lateinit var pagerAdapter: MainPagerAdapter

    /**
     * 搜索页的进入方式：
     *  - MANUAL：从顶部导航栏点击进入，初始展示搜索原页面
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
        // TV 适配：「知道了 / 今天不再提醒」可遥控器聚焦
        com.hpu.mymoviestore.presentation.tv.TvFocus.applyToDialogButtons(view)
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

        // ViewPager2 页面切换 → 同步顶部导航 + 处理搜索页逻辑
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 同步顶部导航栏选中状态
                if (position < tabIds.size) {
                    val tabId = tabIds[position]
                    if (binding.bottomNavigation.selectedItemId != tabId) {
                        binding.bottomNavigation.selectedItemId = tabId
                    }
                }
                // TV 适配：页签的可聚焦状态跟随选中项（如首页跳搜索时同步）
                syncNavFocusability()

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

    // ======================== 顶部导航 ========================

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

        // TV 适配：电视端导航栏「焦点即选中」，见 setupTvNavFocus
        setupTvNavFocus()
    }

    /**
     * TV 适配：导航栏「焦点即选中」—— 焦点停到哪个页签就立刻切到对应子页面，无需再按确定键。
     *
     * 这里刻意只让 **当前选中页签** 可聚焦：
     * 内容区宽度铺满整屏，若三个页签都能聚焦，从内容区按「上」时焦点会按几何就近原则
     * 落在中间那个页签（多半是「搜索」）上，页面会莫名其妙乱跳。
     * 只留当前页签可聚焦后，按「上」必定回到当前页；左右切换由 [dispatchKeyEvent] 接管，
     * 焦点与选中项始终一一对应。
     */
    private fun setupTvNavFocus() {
        if (!TvUiSupport.isTelevision(this)) return
        binding.bottomNavigation.post {
            val items = TvFocus.collectClickableViews(binding.bottomNavigation)
            // 优先按控件 id（= menu item id）精确对应页签；取不到时退回遍历顺序
            val byId = tabIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
            navItemViews = if (byId.size == tabIds.size) byId else items.take(tabIds.size)
            if (navItemViews.size != tabIds.size) {
                // 兜底：页签视图没按预期取到（Material 内部结构差异）→ 退回「全部可聚焦」。
                // 宁可牺牲焦点落点的确定性，也不能让导航栏彻底动不了。
                Log.w(TAG, "TV 导航项数量异常：期望 ${tabIds.size}，实际 ${navItemViews.size}，回退为全部可聚焦")
                navItemViews = emptyList()
                TvFocus.applyToClickables(binding.bottomNavigation)
                return@post
            }
            // 挂焦点框：导航项占满 1/3 屏宽，用带内缩的「药丸」焦点态，贴边描边会变成大方框
            navItemViews.forEach {
                TvFocus.applyTo(it, scale = 1f, ringRes = R.drawable.bg_tv_nav_focus)
            }
            syncNavFocusability()
        }
    }

    /**
     * TV 适配：导航栏左右键切换页签。
     *
     * **必须放在 Activity 的 dispatchKeyEvent**：遥控器按键只投递给「持有焦点的那个视图」，
     * 不会冒泡到它的父容器，所以挂在 `bottomNavigation.setOnKeyListener` 上根本收不到事件
     * （表现为：导航栏看起来完全动不了 / 选不中）。
     * Activity.dispatchKeyEvent 是所有按键的第一站，放这里才可靠。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (navItemViews.isEmpty()) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return super.dispatchKeyEvent(event)
        }
        // 焦点不在导航栏上时，左右键仍是内容区的（例如横向 chip 列表）
        if (!isFocusInNavBar()) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            moveNavFocus(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
        }
        // 导航栏同时只有一个页签可聚焦，左右键一律吃掉，不让焦点系统再去找邻居
        return true
    }

    /** 当前焦点是否落在顶部导航栏内 */
    private fun isFocusInNavBar(): Boolean {
        var v: View? = currentFocus
        while (v != null) {
            if (v === binding.bottomNavigation) return true
            v = v.parent as? View
        }
        return false
    }

    /** 只让当前选中页签可聚焦（原因见 [setupTvNavFocus]） */
    private fun syncNavFocusability() {
        if (navItemViews.isEmpty()) return
        // 选中项理论上必是三个页签之一；真取不到就退回第一个，避免全部不可聚焦把导航栏锁死
        val selectedIndex = tabIds.indexOf(binding.bottomNavigation.selectedItemId)
            .takeIf { it >= 0 } ?: 0
        navItemViews.forEachIndexed { index, item ->
            val focusable = index == selectedIndex
            item.isFocusable = focusable
            item.isFocusableInTouchMode = focusable
        }
        // 焦点若滞留在已失活的页签上（例如从别处改了选中项），把焦点交回当前页签
        val focusedIndex = navItemViews.indexOfFirst { it.isFocused }
        if (focusedIndex >= 0 && focusedIndex != selectedIndex) {
            navItemViews.getOrNull(selectedIndex)?.requestFocus()
        }
    }

    /**
     * 遥控器左右键：选中页签移动一格（焦点跟手切页）。
     *
     * @return 是否真的移动了页签。已在首个/最后一个页签时返回 false —— 调用方仍会吃掉
     *         这个按键（避免焦点系统跑去找邻居），只是不切页。
     */
    private fun moveNavFocus(delta: Int): Boolean {
        if (navItemViews.isEmpty()) return false
        val current = tabIds.indexOf(binding.bottomNavigation.selectedItemId)
        if (current < 0) return false
        val target = current + delta
        if (target !in tabIds.indices) return false
        val tabId = tabIds[target]
        if (binding.bottomNavigation.selectedItemId != tabId) {
            // 复用既有选中链路：自动带上「进入搜索页需重置」等逻辑
            binding.bottomNavigation.selectedItemId = tabId
        }
        syncNavFocusability()
        navItemViews.getOrNull(target)?.requestFocus()
        return true
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
