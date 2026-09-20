package com.hpu.mymoviestore.presentation.activity

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import com.hpu.mymoviestore.presentation.dialog.DialogSizing
import com.hpu.mymoviestore.presentation.fragment.HomeFragment
import com.hpu.mymoviestore.presentation.fragment.ProfileFragment
import com.hpu.mymoviestore.presentation.fragment.SearchFragment
import com.hpu.mymoviestore.presentation.tv.TvContentKeyHandler
import com.hpu.mymoviestore.presentation.tv.TvFocus
import com.hpu.mymoviestore.presentation.tv.TvInitialFocusProvider
import com.hpu.mymoviestore.presentation.tv.TvUiSupport
import com.hpu.mymoviestore.presentation.update.UpdatePrefs
import kotlinx.coroutines.launch
import android.content.pm.ActivityInfo
import android.graphics.Rect

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
    private var isTv: Boolean = false
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
        isTv = TvUiSupport.isTelevision(this)
        applyOrientation()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        setupViewPager()
        setupBottomNavigation()
        applyUiVisibility(isTv)
        setupBackPressed()
        checkUpdateOnLaunch()
    }

    /**
     * 屏幕方向：电视恒横屏（10-foot UI），手机恒竖屏。
     *
     * 不做动态切换、没有用户开关 —— 一套代码双形态，形态由 [TvUiSupport.isTelevision] 在启动时定死。
     *
     * 必须在 inflate 之前调用：方向决定 `layout/` 还是 `layout-land/` 被选中
     * （电视要拿到含 tvNavBar 的横屏版导航栏，手机要拿到 BottomNavigationView）。
     */
    private fun applyOrientation() {
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * 双形态 UI 显隐：TV 端隐藏手机专属导航（底部 BottomNavigationView），显示电视页签簇；
     * 手机端相反。tvNavBar 用空安全调用 —— 竖屏布局里没有该控件（电视端恒走 layout-land）。
     */
    private fun applyUiVisibility(tv: Boolean) {
        if (tv) {
            binding.bottomNavigation.visibility = View.GONE
            binding.tvNavBar?.visibility = View.VISIBLE
        } else {
            binding.bottomNavigation.visibility = View.VISIBLE
            binding.tvNavBar?.visibility = View.GONE
        }
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
        dialog.setCancelable(true)
        dialog.show()
        // 与其他弹框统一：透明背景 + 以屏宽短边为准的宽度（横屏不再被拉成超宽扁条）
        DialogSizing.applyCenteredCard(dialog, this)
        // 更新说明来自远程配置、长度不可控：限高可滚动，避免在电视矮屏上顶出屏幕
        DialogSizing.limitContentHeight(
            view.findViewById(R.id.scrollUpdateContent),
            DialogSizing.contentMaxHeightPx(this, 0.35f)
        )
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

        // TV 适配：电视端用紧凑的「药丸」页签簇替代铺满整行的 BottomNavigationView
        if (isTv) {
            setupTvNavBar()
        }
    }

    /**
     * TV 适配：用紧凑的页签簇替代铺满整行的 BottomNavigationView。
     *
     * 每个页签：图标 + 文字水平居中，底部有品牌橙指示条（选中时显示）。
     * 焦点态用柔和的白色半透明底色（bg_tv_nav_focus），整体更贴合 10-foot UI。
     *
     * 选中逻辑仍统一走 [binding.viewPager]，与手机端完全一致；焦点行为见 [syncNavFocusability]。
     */
    private fun setupTvNavBar() {
        // 空安全：tvNavBar 只存在于 layout-land（横屏 / 电视版），竖屏布局没有该控件，
        // 生成绑定字段为 Nullable。取不到就整个放弃 TV 页签簇，交给 BottomNavigationView。
        // 显隐由 applyUiVisibility(isTv) 统一处理（onCreate 中调用）。
        val navBar = binding.tvNavBar ?: return

        val entries = listOf(
            Triple(R.id.nav_home, R.drawable.ic_home, R.string.home),
            Triple(R.id.nav_search, R.drawable.ic_search, R.string.search),
            Triple(R.id.nav_profile, R.drawable.ic_profile, R.string.profile),
        )
        val items = entries.map { (id, icon, label) ->
            val item = layoutInflater.inflate(R.layout.item_tv_nav, navBar, false) as android.widget.LinearLayout
            item.id = id
            item.findViewById<android.widget.ImageView>(R.id.ivNavIcon).setImageResource(icon)
            item.findViewById<android.widget.TextView>(R.id.tvNavLabel).setText(label)
            item.setOnClickListener {
                val idx = tabIds.indexOf(id)
                if (idx >= 0 && binding.viewPager.currentItem != idx) {
                    if (id == R.id.nav_search && pendingSearchKeyword == null) {
                        searchEntryMode = SearchEntryMode.MANUAL
                        resetSearchOnNextShow = true
                    }
                    binding.viewPager.currentItem = idx
                }
            }
            navBar.addView(item)
            item
        }
        navItemViews = items
        navItemViews.forEach {
            TvFocus.applyTo(it, scale = 1f, ringRes = R.drawable.bg_tv_nav_focus)
        }
        syncNavFocusability()
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
        // 导航栏焦点下的「下键」：把焦点移交给当前内容页首个可聚焦控件（如搜索框），避免焦点丢失
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && isFocusInNavBar()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val target = currentContentInitialFocus()
                if (target != null) {
                    target.requestFocus()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        val isHorizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val isVertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (!isHorizontal && !isVertical) {
            return super.dispatchKeyEvent(event)
        }
        // 焦点不在导航栏上时，方向键都是内容区的（页内纵向列表、横向 chip 列表等）
        if (!isFocusInNavBar()) {
            // 边界保护：ViewPager2 预加载了所有页面（offscreenPageLimit = tabIds.size），
            // 焦点查找会越过页面边界，把焦点交给相邻（不可见）页的控件，
            // 表现为「按方向键焦点消失、反方向键也找不回」。此情形吃掉按键，让焦点原地不动。
            if (event.action == KeyEvent.ACTION_DOWN) {
                val direction = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    else -> View.FOCUS_DOWN
                }
                // 先给当前内容页一次「兜底接管」机会（结果网格最后一行按下键 -> 掀起分页栏）。
                // 原因：RecyclerView.focusSearch 只在自身子树内找候选，「翻出容器」的移动系统做不到。
                if (currentContentKeyHandler()?.onContentDirectionKey(direction) == true) return true
                when (classifyFocusMove(direction)) {
                    FocusMove.ESCAPE_EAT -> return true
                    FocusMove.UP_HANDLED -> {
                        handleUpKey()
                        return true
                    }
                    FocusMove.ALLOW -> Unit
                }
            }
            return super.dispatchKeyEvent(event)
        }
        // 焦点在导航栏：只有左右键用于切换页签
        if (!isHorizontal) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            moveNavFocus(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
        }
        // 导航栏同时只有一个页签可聚焦，左右键一律吃掉，不让焦点系统再去找邻居
        return true
    }

    /**
     * 当前内容页向导航栏暴露的「下键」首焦点控件（电视端）。
     * 实时向当前可见的 Fragment 查询，避免 ViewPager2 复用 Fragment 时缓存过期。
     */
    private fun currentContentInitialFocus(): View? {
        val provider = supportFragmentManager.fragments.firstOrNull {
            it is TvInitialFocusProvider && it.isResumed
        } as? TvInitialFocusProvider ?: return null
        return provider.tvInitialFocusView()
    }

    /**
     * 当前内容页的方向键兜底接管者（电视端）。
     * 同样实时向可见 Fragment 查询，避免 ViewPager2 复用 Fragment 时缓存过期。
     */
    private fun currentContentKeyHandler(): TvContentKeyHandler? {
        return supportFragmentManager.fragments.firstOrNull {
            it is TvContentKeyHandler && it.isResumed
        } as? TvContentKeyHandler
    }

    /** 内容区方向键的处理结论（见 [classifyFocusMove]） */
    private enum class FocusMove {
        /** 正常放行，交给系统移动焦点 */
        ALLOW,

        /** 目标会逃出当前页 -> 吃掉按键，焦点原地不动（即「按了没反应」） */
        ESCAPE_EAT,

        /** 上键：交给 [handleUpKey] 显式处理（先页内上一层，页内确实没有才回导航栏） */
        UP_HANDLED,
    }

    /**
     * 内容区方向键的「边界保护」：判断按该方向键时焦点的去向。
     *
     * ViewPager2 预加载了所有页面（offscreenPageLimit = tabIds.size），焦点查找会越过页面
     * 边界落到相邻（不可见）页的控件上，导致「焦点消失、反方向键也找不回」。因此：
     * - 目标在**其他页**内、或页内已无候选 -> 吃掉按键（上键例外，见下）；
     * - 目标在 ViewPager 之外（顶部导航栏）-> 左右下放行，上键改走 [handleUpKey]；
     * - 目标在同一页内 -> 正常放行（保留列表滚动等系统行为）。
     *
     * 上键为什么要显式接管：系统焦点搜索会把「页内候选」和「页外导航项」放在一起做几何比较，
     * 而顶部导航项横跨很宽、水平区间几乎覆盖任意页内控件，容易被判为更优 -> 上键一下跳到标签栏，
     * 观感就是「跳过了一层」。所以上键一律先在本页内找「上一层」，页内确实没有才回导航栏。
     */
    private fun classifyFocusMove(direction: Int): FocusMove {
        val focused = currentFocus ?: return FocusMove.ALLOW
        val fromPage = pageRootOf(focused) ?: return FocusMove.ALLOW   // 焦点不在页内，不干预
        val next = focused.focusSearch(direction)
        if (next == null || next === focused) {
            // 页内已无候选：上键走「页内上一层 / 导航栏」兜底，其余方向原地不动
            return if (direction == View.FOCUS_UP) FocusMove.UP_HANDLED else FocusMove.ESCAPE_EAT
        }
        val toPage = pageRootOf(next)
        if (toPage == null) {
            // 目标在 ViewPager 之外（顶部导航栏）：上键不直接跳过去，先试页内上一层
            return if (direction == View.FOCUS_UP) FocusMove.UP_HANDLED else FocusMove.ALLOW
        }
        if (fromPage === toPage) return FocusMove.ALLOW                // 同页内正常移动
        // 跨页：会逃到不可见的相邻页
        return if (direction == View.FOCUS_UP) FocusMove.UP_HANDLED else FocusMove.ESCAPE_EAT
    }

    /** 上键：先在当前页内找「上一层」的可聚焦控件（只跳一级），页内确实没有才回顶部导航栏 */
    private fun handleUpKey(): Boolean {
        val focused = currentFocus
        val page = focused?.let { pageRootOf(it) }
        if (focused != null && page != null) {
            val target = nearestFocusableAbove(focused, page)
            if (target != null) {
                target.requestFocus()
                return true
            }
        }
        return focusCurrentNavItem()
    }

    /**
     * 在当前页子树内找「位于 [focused] 上方、且实际可见」的可聚焦控件：
     * 竖直距离最近者优先，距离相同时取水平最贴近的（同列优先）。
     *
     * 只认 [isOnScreen] 通过的候选：被祖先裁掉（溢出容器边界）的视图虽然可聚焦，
     * 但用户看不见，把焦点交给它就会表现为「焦点消失且找不回」。
     */
    private fun nearestFocusableAbove(focused: View, page: View): View? {
        val pageGroup = page as? ViewGroup ?: return null
        val views = arrayListOf<View>()
        pageGroup.addFocusables(views, View.FOCUS_UP)
        val src = Rect()
        focused.getDrawingRect(src)
        pageGroup.offsetDescendantRectToMyCoords(focused, src)
        var best: View? = null
        var bestScore = Int.MAX_VALUE
        for (v in views) {
            if (v === focused || v === page || !isOnScreen(v)) continue
            val r = Rect()
            v.getDrawingRect(r)
            pageGroup.offsetDescendantRectToMyCoords(v, r)
            if (r.bottom > src.top) continue                       // 不在上方
            val vertical = (src.top - r.bottom).coerceAtLeast(0)
            val horizontal = when {
                r.right < src.left -> src.left - r.right
                r.left > src.right -> r.left - src.right
                else -> 0                                          // 水平有重叠 -> 同列优先
            }
            val score = vertical * 1000 + horizontal
            if (score < bestScore) {
                bestScore = score
                best = v
            }
        }
        return best
    }

    /** 视图是否真的显示在屏幕上（被祖先裁掉的「溢出」子视图不算，避免把焦点交给看不见的控件） */
    private fun isOnScreen(v: View): Boolean {
        if (!v.isShown) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0
    }

    /**
     * 把焦点交回顶部导航栏的当前选中页签。
     *
     * 既是本类上键的兜底路径，也开放给内容页调用 —— 左右分栏的页面（如「我的」）
     * 中，焦点从右侧菜单按上键时，系统的几何搜索会在页内兜圈而落不到导航栏。
     */
    internal fun focusCurrentNavItem(): Boolean {
        return navItemViews.getOrNull(binding.viewPager.currentItem)?.requestFocus() ?: false
    }

    /** 向上找到 ViewPager2 内部 RecyclerView 的直接子（即某一页的根视图） */
    private fun pageRootOf(view: View): View? {
        val pagerRecycler = binding.viewPager.getChildAt(0) ?: return null
        var cur: View? = view
        while (cur != null) {
            if (cur.parent === pagerRecycler) return cur
            cur = cur.parent as? View
        }
        return null
    }

    /** 当前焦点是否落在顶部导航栏内（手机 = BottomNavigationView，电视 = tvNavBar） */
    private fun isFocusInNavBar(): Boolean {
        var v: View? = currentFocus
        while (v != null) {
            if (v === binding.bottomNavigation || v === binding.tvNavBar) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * 只让当前选中页签可聚焦（原因见 [setupTvNavBar]），并同步选中态视觉。
     * 电视端选中项即 [binding.viewPager] 当前页，比 BottomNavigationView 的 selectedItemId 更可靠。
     */
    private fun syncNavFocusability() {
        if (navItemViews.isEmpty()) return
        val selectedIndex = binding.viewPager.currentItem
            .takeIf { it in tabIds.indices } ?: 0
        applyNavSelectedVisual(selectedIndex)
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

    /** 选中态视觉：选中项用品牌橙图标 + 文字 + 底部指示条，未选中用次级灰 */
    private fun applyNavSelectedVisual(selectedIndex: Int) {
        if (navItemViews.isEmpty()) return
        val primary = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary)
        val secondary = androidx.core.content.ContextCompat.getColor(this, R.color.colorOnSurfaceSecondary)
        navItemViews.forEachIndexed { index, view ->
            val selected = index == selectedIndex
            view.isSelected = selected
            val color = if (selected) primary else secondary
            view.findViewById<android.widget.ImageView>(R.id.ivNavIcon)
                ?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            view.findViewById<android.widget.TextView>(R.id.tvNavLabel)?.setTextColor(color)
            // 底部指示条：选中时可见
            view.findViewById<View>(R.id.navIndicator)?.visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
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
        val current = binding.viewPager.currentItem
        val target = current + delta
        if (target !in tabIds.indices) return false
        val tabId = tabIds[target]
        if (binding.viewPager.currentItem != target) {
            // 复用现有切页链路：自动带上「进入搜索页需重置」等逻辑
            if (tabId == R.id.nav_search && pendingSearchKeyword == null) {
                searchEntryMode = SearchEntryMode.MANUAL
                resetSearchOnNextShow = true
            }
            binding.viewPager.currentItem = target
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
