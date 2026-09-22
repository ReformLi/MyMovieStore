package com.hpu.mymoviestore.presentation.tv

import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.hpu.mymoviestore.R

/**
 * 遥控器（D-pad）焦点支持工具 —— 仅作用于 UI 层。
 *
 * 电视端没有触摸屏，所有交互依赖方向键 + 确定键，因此：
 * 1. 可点击的视图必须 **可聚焦**（`focusable`），否则遥控器无法选中；
 * 2. 获焦时必须有 **明显的视觉反馈**（描边焦点框 + 轻微放大），否则用户不知道当前在哪；
 * 3. 失焦/复用时必须 **恢复原状**，避免 RecyclerView 复用导致「多个项同时放大」。
 *
 * **形态门控（重要）**：本类的一切效果只在电视端生效 —— 每个公开方法入口都用 [isActive]
 * 拦一道。原因：[View.setFocusableInTouchMode] 置真后**触屏点击同样会拿到焦点**，且自绘的
 * 焦点框（foreground）会一直挂着，于是手机端会出现「点一下卡片就冒出橙色描边、焦点乱跳」
 * 这种明显不属于触屏交互的怪异表现。门控放在这里而不是各个调用点，是为了让 40+ 处调用
 * 一处覆盖、永不遗漏（曾经就是把 TV 逻辑直接写在手机分支里，导致竖屏全面回归）。
 */
object TvFocus {

    private const val TV_UNKNOWN = 0
    private const val TV_YES = 1
    private const val TV_NO = 2

    /** 进程内缓存设备形态：形态在运行期不会改变，避免每次列表 bind 都走 getSystemService */
    @Volatile
    private var tvState: Int = TV_UNKNOWN

    /** 电视端才生效；手机端所有方法直接返回，行为与适配前完全一致 */
    private fun isActive(view: View): Boolean {
        val cached = tvState
        if (cached != TV_UNKNOWN) return cached == TV_YES
        val tv = TvUiSupport.isTelevision(view.context.applicationContext)
        tvState = if (tv) TV_YES else TV_NO
        return tv
    }

    /** 获焦放大倍数（1.0 表示不放大） */
    const val FOCUS_SCALE = 1.08f

    /** 焦点动画时长（毫秒） */
    private const val ANIM_MS = 140L

    /**
     * 让视图支持遥控器聚焦，并在获焦时放大 + 抬升。
     *
     * @param scale 获焦放大倍数，传 1f 表示不放大（仅保留焦点框）
     * @param scrollContainer 非空时，获焦后自动把该视图滚动到容器可视区域
     *                        （横向列表用；与放大动画共用同一个焦点监听，避免互相覆盖）
     * @param ringRes 焦点框 drawable；默认是贴边描边，占满整屏宽度的控件
     *                （如顶部导航项）应改用带内缩的 [R.drawable.bg_tv_nav_focus]
     * @param alwaysScale 跳过 [canScaleUp] 贴边检查，获焦一律放大。
     *                专治列表卡片的「下滑动后放大消失」：焦点监听在 requestFocus 当下同步触发，
     *                而容器把部分可见项滚入可视区发生在**之后** —— 此刻贴边项的 gap 必为负，
     *                被 canScaleUp 误判成「放大就会被裁」而跳过，滚动完成后无人重新评估。
     *                网格/行卡片四周 margin 充足、列表容器也已 clipChildren=false，
     *                该检查对它们是误伤（详情页 chip、弹窗按钮等静态场景保留原检查）。
     */
    fun applyTo(
        view: View,
        scale: Float = FOCUS_SCALE,
        scrollContainer: View? = null,
        @DrawableRes ringRes: Int = R.drawable.bg_tv_focus_ring,
        alwaysScale: Boolean = false
    ) {
        if (!isActive(view)) return
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        neutralizeCardFocusStroke(view)
        // API 26+ 系统默认焦点高亮关掉（自绘焦点框，避免双重描边）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        markHandled(view)
        attachFocusRing(view, ringFor(view, ringRes))
        view.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, hasFocus, scale, alwaysScale)
            if (hasFocus && scrollContainer != null) scrollIntoView(v, scrollContainer)
        }
        // 回收复用时可能残留放大状态，重置一次
        resetAppearance(view)
    }

    /** 仅让视图可聚焦（不放大动画），适用于已自带焦点态背景的控件 */
    fun applyFocusableOnly(view: View, @DrawableRes ringRes: Int = R.drawable.bg_tv_focus_ring) {
        if (!isActive(view)) return
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        neutralizeCardFocusStroke(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        markHandled(view)
        attachFocusRing(view, ringFor(view, ringRes))
    }

    /**
     * 按显隐 / 可用状态切换「遥控器可聚焦」能力 —— **电视端专用**，手机端整体 no-op。
     *
     * 为什么必须走本方法、而不是在调用点直接写 `view.isFocusable(InTouchMode) = x`：
     * `setFocusableInTouchMode(true)` 的语义是「触摸时也把焦点交给该控件」，而触摸模式的
     * 规则是**取焦点的这一次点击不触发 click**（焦点先落上去，再点一次才响应）——
     * 手机端表现就是「按钮要点两下才生效」。又因为 `res/layout-land/` 同时是**手机横屏**
     * 布局（不是 TV 专属目录），任何绕过本类的直接赋值都会立刻污染触屏。
     *
     * 列表适配器每次 bind 都要按显隐重设聚焦能力（电视端隐藏按钮不能让遥控器停上去），
     * 那是真实需求 —— 用本方法表达即可，两端都不丢。
     *
     * @param enabled true = 可聚焦（补焦点环 + 获焦放大）；false = 取消聚焦能力并恢复原状
     */
    fun setFocusable(view: View, enabled: Boolean, scale: Float = FOCUS_SCALE) {
        if (!isActive(view)) return
        applyTo(view, scale)
        if (enabled) return
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        resetAppearance(view)
    }

    /**
     * 焦点环按「控件自身底色」自动挑选，避免同一圈颜色在同类底色上看不见。
     *
     * 目前只有一种反转：**品牌橙底 -> 白环**。橙环压在橙底上对比度约 1.1:1 等于没有，
     * 典型场景是弹窗主按钮（`backgroundTint=colorPrimary`）。
     * 仅在调用方**没有显式指定** ringRes（仍为默认值）时才自动改，不覆盖刻意选择。
     */
    private fun ringFor(view: View, fallback: Int): Int =
        if (fallback == R.drawable.bg_tv_focus_ring && isBrandFilled(view)) {
            R.drawable.bg_tv_focus_ring_light
        } else {
            fallback
        }

    /** 控件是否为品牌橙填充（按钮 backgroundTint 等于品牌主色） */
    private fun isBrandFilled(view: View): Boolean {
        val tint = (view as? android.widget.Button)?.backgroundTintList ?: return false
        return tint.defaultColor == ContextCompat.getColor(view.context, R.color.colorPrimary)
    }

    /**
     * 中和 MaterialCardView **自带的获焦描边**（TV 端最隐蔽的一处「白边」来源）。
     *
     * Material3 主题把 `materialCardViewStyle` 默认指向 **Outlined 卡片**
     * （`Theme.Material3.*` 里 `materialCardViewStyle = ?attr/materialCardViewOutlinedStyle`），
     * 该样式 `strokeWidth = 1dp`、`strokeColor = @color/m3_card_stroke_color`，
     * 而这个颜色状态列表里有一条：
     *       <item android:state_focused="true" android:color="?attr/colorOnSurface"/>
     * 深色模式下 `colorOnSurface` 就是 **纯白 #FFFFFF** ——
     * 于是卡片一获焦，组件自己会沿边缘描一条 1dp 白线，与本类的橙色焦点环叠在一起。
     * 用户看到的正是「聚焦的高亮框里有白边 / 红框里有个白框」，且只在卡片类控件上出现
     * （按钮没有状态描边，所以「标签栏」「按钮」看着正常）。
     *
     * 处理：把描边色**钉死成它自己的默认色**（非获焦态 = `colorOutlineVariant` 的浅灰）。
     * 单色 [ColorStateList] 不随状态变化 → **常态外观一字不变**，只是获焦时不再变白。
     * 刻意不把 `strokeWidth` 置 0：那会连卡片常态的那道细描边一起抹掉，属于越界改动。
     *
     * 顺带置空 `stateListAnimator`：M3 卡片自带 `m3_card_state_list_anim`，
     * 会在任意状态变化时把 `translationZ` 动画回 0dp，与本类的「获焦抬升 8dp」互相打架
     * （表现为卡片抬起来又被拽回去）。焦点动效统一由本类负责。
     */
    private fun neutralizeCardFocusStroke(view: View) {
        if (view !is MaterialCardView) return
        // 注意：`MaterialCardView` 同时有 getStrokeColor():Int 与 getStrokeColorStateList():ColorStateList，
        // setStrokeColor 也有 int / ColorStateList 两个重载 —— 用 Kotlin 合成属性 `strokeColor`
        // 会撞歧义（编译报 "actual type is ColorStateList, but Int was expected"）。
        // 读状态列表必须走 getStrokeColorStateList()，写则显式传 ColorStateList 定住重载。
        val stroke = view.strokeColorStateList
        if (stroke != null && view.strokeWidth > 0) {
            view.setStrokeColor(ColorStateList.valueOf(stroke.defaultColor))
        }
        view.stateListAnimator = null
    }

    /**
     * 挂载焦点描边（作为 foreground，不覆盖视图自身 background）。
     * 描边由 StateListDrawable 按 state_focused 自动切换，无需手动控制。
     *
     * 若视图已有 foreground（如点击涟漪），则用 LayerDrawable 叠加：
     * 原效果在下、焦点描边在上，两者都不丢失。
     */
    fun attachFocusRing(view: View, @DrawableRes ringRes: Int = R.drawable.bg_tv_focus_ring) {
        if (!isActive(view)) return
        val ring = ContextCompat.getDrawable(view.context, ringRes) ?: return
        val existing = view.foreground
        when {
            existing == null -> view.foreground = ring
            existing.constantState == ring.constantState -> Unit // 布局 XML 已设置同款焦点框
            existing is android.graphics.drawable.LayerDrawable -> {
                // 已是叠加层：按「层里有没有本款焦点环」判重，而不是见了 LayerDrawable 就整批放行。
                // 旧实现这里直接 Unit（当作"已叠加过"），于是**别人**挂的 layer-list 前景
                // 会让焦点环被静默吞掉（`?attr/selectableItemBackground` 是平台 RippleDrawable，
                // 自带焦点/按压高亮，走下面的 else 分支被整份包一层）。
                // ⚠️ 归因更正：它**不是** AppCompat 的 Holo 遗留选择器 —— 那套只存在于
                // API < 21 的 Base.V7.Theme.AppCompat；本项目 minSdk = 24 走 Base.V21，
                // 解析到的是 <ripple android:color="?attr/colorControlHighlight">。
                // 控件能获得焦点、系统默认高亮又被关掉了，用户看到的就是"完全没有任何高亮"。
                if (!existing.hasLayer(ring.constantState)) {
                    val layers = Array(existing.numberOfLayers + 1) { i ->
                        if (i < existing.numberOfLayers) existing.getDrawable(i) else ring
                    }
                    view.foreground = android.graphics.drawable.LayerDrawable(layers)
                }
            }
            else -> view.foreground = android.graphics.drawable.LayerDrawable(arrayOf(existing, ring))
        }
    }

    /** LayerDrawable 里是否已包含指定外观的某一层（焦点环判重用，避免无限套娃） */
    private fun android.graphics.drawable.LayerDrawable.hasLayer(
        state: android.graphics.drawable.Drawable.ConstantState?
    ): Boolean {
        if (state == null) return false
        for (i in 0 until numberOfLayers) {
            if (getDrawable(i)?.constantState == state) return true
        }
        return false
    }

    /** 恢复未聚焦外观（RecyclerView 复用项绑定时调用，防止残留放大）；正在聚焦的项不动 */
    fun resetAppearance(view: View) {
        if (!isActive(view)) return
        if (view.isFocused) return
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f
    }

    private fun animateFocus(view: View, hasFocus: Boolean, scale: Float, alwaysScale: Boolean = false) {
        val target = if (hasFocus && (alwaysScale || canScaleUp(view, scale))) scale else 1f
        view.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(ANIM_MS)
            .start()
        // 获焦项抬高，视觉上浮于相邻项之上（避免放大后被邻居遮挡）
        view.translationZ = if (hasFocus) dp(view, 8f) else 0f
    }

    /**
     * 判断该控件获焦时**能否安全放大**。
     *
     * 放大会以中心为基准向四周外扩 `尺寸 × (scale-1) / 2`。父容器的 `clipChildren` 默认是 true，
     * 所以只要外扩量超过它与父容器可绘制边界的间隙，超出部分就会被裁掉 ——
     * 表现为「聚焦时该条左右鼓出来／边缘被切平」，比不放大更难看。
     *
     * 两类控件一定命中，直接退化为「只显示焦点环，不放大」：
     * 1. **满宽项**（宽度 ≥ 父容器可用宽度的 90%）：列表行、「我的」页设置菜单卡片。
     *    它们左右几乎没有余量，放大 2% 就会比邻居宽出一圈。
     * 2. **贴边项**：放大后外扩量大于四周间隙的控件，典型是弹窗里并排的
     *    「取消 / 确定」按钮（`weight=1`，两端紧贴父容器）。
     *
     * 反例（应当保留放大）：首页/历史页的网格卡片、详情页的线路与选集 chip ——
     * 它们本身尺寸小、四周有余量，放大才是应有的焦点反馈。
     *
     * 在焦点变化时求值（此时布局已完成，宽高与坐标可用）；否则返回 false（宁可不放大）。
     */
    private fun canScaleUp(view: View, scale: Float): Boolean {
        if (scale <= 1f) return false
        val parent = view.parent as? android.view.ViewGroup ?: return false
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0 || parent.width <= 0 || parent.height <= 0) return false

        // 1. 满宽项：左右无余量
        val availW = parent.width - parent.paddingLeft - parent.paddingRight
        if (availW > 0 && w >= availW * 0.9f) return false

        // 2. 贴边项：外扩量放不下
        val dx = w * (scale - 1f) / 2f
        val dy = h * (scale - 1f) / 2f
        val leftGap = (view.left - parent.paddingLeft).toFloat()
        val rightGap = (parent.width - parent.paddingRight - view.right).toFloat()
        val topGap = (view.top - parent.paddingTop).toFloat()
        val bottomGap = (parent.height - parent.paddingBottom - view.bottom).toFloat()

        // 容忍 2dp：详情页的线路/选集 chip 本身就贴着滚动容器左边界，但体积小、外扩量只有 1~2dp，
        // 被裁掉一两像素看不出来；若做成 0 容忍，这些 chip 会集体失去放大反馈。
        // 弹窗按钮那种 8dp 级别的外扩量远超容忍值，仍然会被拦下。
        val tol = dp(view, 2f)
        return dx <= leftGap + tol && dx <= rightGap + tol &&
            dy <= topGap + tol && dy <= bottomGap + tol
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    /**
     * 请求初始焦点：布局/数据就绪后调用，保证遥控器一进入页面就有落点
     * （否则用户需先按方向键才能"唤醒"焦点）。
     */
    fun requestInitialFocus(view: View) {
        if (!isActive(view)) return
        view.post {
            if (!view.isFocused && view.isShown) view.requestFocus()
        }
    }

    /**
     * 让 RecyclerView 的首个可见项获得焦点（电视进入列表页时的默认落点）。
     * 列表尚未完成布局时先滚动到顶部再取焦点。
     */
    fun focusFirstItem(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        if (!isActive(recyclerView)) return
        recyclerView.post {
            val lm = recyclerView.layoutManager ?: return@post
            val first = lm.findViewByPosition(0)
            if (first != null) {
                if (!first.isFocused) first.requestFocus()
            } else {
                recyclerView.scrollToPosition(0)
                recyclerView.post {
                    recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus()
                }
            }
        }
    }

    /**
     * 列表项自动滚动到焦点位置：横向容器（如首页子分类）在获焦时滚动使该项可见。
     *
     * 注意：这会覆盖视图已有的 OnFocusChangeListener。若视图同时需要放大动画，
     * 请改用 `applyTo(view, scale, scrollContainer)`，两个效果共用一个监听。
     */
    fun scrollIntoViewOnFocus(view: View, container: View) {
        if (!isActive(view)) return
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) scrollIntoView(v, container)
        }
    }

    /** 把视图滚动到容器可视区域（横向容器居中显示） */
    private fun scrollIntoView(v: View, container: View) {
        container.post {
            val rect = android.graphics.Rect()
            v.getDrawingRect(rect)
            (container as? android.widget.HorizontalScrollView)?.let { hsv ->
                hsv.offsetDescendantRectToMyCoords(v, rect)
                val target = (rect.left - (hsv.width - rect.width()) / 2)
                    .coerceAtLeast(0)
                hsv.smoothScrollTo(target, 0)
            }
        }
    }

    /**
     * 递归为 root 内所有「可点击」的控件挂上焦点支持。
     *
     * 通用场景：控件结构不确定、或列表/菜单项由框架动态创建（弹窗按钮、底部/顶部导航项等），
     * 逐个布局文件手改容易遗漏。
     *
     * **补环判据**：控件「可点击」或「可聚焦」即补焦点环；带本类标记的（[applyTo] /
     * [applyFocusableOnly] 处理过，如列表适配器在 onCreateViewHolder 里装配好的行）
     * 一律跳过 —— 既不重复包环，也不会用这里的 scale 顶掉适配器自己挑的缩放。
     * ViewGroup 形式的点击目标只加焦点框、不做放大（避免整行容器放大后溢出边界）。
     *
     * 弹窗本体（root 自身）刻意不作为焦点落点：它是整块卡片，被描边很难看，
     * 还会在方向键搜索时把「上」这类按键从内部控件手里截走。
     *
     * @param root 容器根视图（弹窗根视图、导航栏等）
     * @param scale 叶子控件的获焦放大倍数
     */
    fun applyToClickables(root: View, scale: Float = FOCUS_SCALE) {
        if (!isActive(root)) return
        root.post {
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) walkClickable(root.getChildAt(i), scale)
            } else {
                walkClickable(root, scale)
            }
        }
    }

    /** 语义化别名：[applyToClickables] 用于弹窗场景 */
    fun applyToDialogButtons(root: View, scale: Float = FOCUS_SCALE) =
        applyToClickables(root, scale)

    /**
     * 按（深度优先、同层从左到右）顺序收集 root 内所有「可点击」控件。
     *
     * 适用场景：控件由框架动态创建、拿不到稳定 id，且需要「按下标绑定行为」时
     * （如顶部导航项 —— 第 N 个控件对应第 N 个页签），靠遍历顺序定位最省事。
     *
     * 纯查询、无副作用，故不做形态门控（调用方自己判断形态）。
     */
    fun collectClickableViews(root: View): List<View> {
        val out = ArrayList<View>()
        collect(view = root, out = out)
        return out
    }

    private fun collect(view: View, out: MutableList<View>) {
        if (view.visibility != View.VISIBLE) return
        if (view.isClickable) out.add(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }

    private fun walkClickable(view: View, scale: Float) {
        if (view.visibility != View.VISIBLE) return

        if (view is android.view.ViewGroup) {
            // 容器本身也是点击目标（「检查更新」整行、弹窗条目行）→ 只加焦点框，不放大（避免整行溢出）。
            // 注意：容器带标记只表示**它自己**处理过了，仍然要继续向下走 ——
            // 列表行容器（如视频源条目）会被适配器先行标记，若在这里连子树一起跳过，
            // 行内后来才出现的可交互控件就再也没机会补环了。
            if (!isHandled(view) && view.isClickable) applyRingAndFocus(view, 1f)
            for (i in 0 until view.childCount) walkClickable(view.getChildAt(i), scale)
            return
        }
        // 叶子：已处理过、或既不可点也不可聚焦的纯展示控件（标题、描述文案）直接跳过
        if (isHandled(view) || (!view.isClickable && !view.isFocusable)) return
        applyRingAndFocus(view, scale)
    }

    /**
     * 给「弹窗里本就该被遥控器操作的控件」补上焦点视觉。原则是**能不越界就不越界**：
     *
     * - **已可聚焦** → 只挂焦点环 + 关掉系统默认高亮，**不动**它的聚焦能力、缩放与
     *   OnFocusChangeListener。命中者：框架 Button / MaterialButton（默认 focusable=true）、
     *   XML 里显式写了 `android:focusable="true"` 的条目行（如 layout-land 下的
     *   item_clear_cache / item_video_source）。
     *
     *   上一轮的漏洞正在这里：老判据「已是可聚焦就跳过」会把这些控件**整批漏掉**，
     *   而 `defaultFocusHighlightEnabled` 又被我们关成了 false，于是焦点停上去后
     *   **一点高亮都没有** —— 视频源管理的「全选 / 全不选」、关于页的按钮、
     *   清理缓存的条目行都是这个症状。
     * - **可点击但不可聚焦**（普通 TextView 形式的按钮、LinearLayout 行）→ 走完整 [applyTo]，
     *   补聚焦能力 + 焦点环 + 放大动画。
     */
    private fun applyRingAndFocus(view: View, scale: Float) {
        if (view.isFocusable || view.isFocusableInTouchMode) {
            applyFocusableOnly(view)
        } else {
            applyTo(view, scale)
        }
    }

    /**
     * 标记 / 查询「该视图已由 TvFocus 处理过」（tag key 见 `values/ids.xml`）。
     *
     * 焦点环与焦点监听的装配本身幂等，但**重复装配会用新的参考值覆盖旧的**：弹窗遍历的
     * scale=1.08 会把列表适配器精心挑好的 1.02 冲掉，并顶掉别人装的 OnFocusChangeListener。
     * 有了标记，弹窗遍历就只处理「没人管过的控件」—— 这正是敢于删掉
     * 「已是可聚焦就跳过」那条老判据的前提（老判据在修一批的同时漏另一批）。
     */
    private fun isHandled(view: View): Boolean =
        view.getTag(R.id.tag_tv_focus_handled) == true

    private fun markHandled(view: View) {
        view.setTag(R.id.tag_tv_focus_handled, true)
    }
}
