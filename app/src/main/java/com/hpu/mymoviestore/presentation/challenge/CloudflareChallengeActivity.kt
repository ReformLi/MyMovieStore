package com.hpu.mymoviestore.presentation.challenge

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hpu.mymoviestore.data.CloudflareBypassManager
import com.hpu.mymoviestore.presentation.tv.TvFocus

/**
 * Cloudflare 人工验证兜底窗口。
 *
 * 自动过盾失败时由 [CloudflareBypassManager] 拉起：全屏遮罩 + 暗色圆角卡片
 * 内嵌真实 WebView，用户手动完成挑战（通常点一下勾选框）。
 * CookieManager 轮询到 `cf_clearance` 后自动回传结果。
 *
 * **串行队列**：多个域名需要验证时不叠加弹窗——完成当前域名后自动从
 * [CloudflareBypassManager.nextChallengeUrl] 取下一个排队 URL，同一窗口
 * 原地切换继续验证，队列耗尽才关闭。
 *
 * 布局为程序化构建（仅此一处使用，无需资源文件）；主题为半透明透明背景
 * （Theme.MyMovieStore.Challenge），透出下层页面，视觉上是"浮层"而非跳页。
 */
class CloudflareChallengeActivity : AppCompatActivity() {

    /** TV 适配：电视端放大 UI 密度（10-foot UI），手机端原样返回 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hpu.mymoviestore.presentation.tv.TvUiSupport.wrapContext(newBase))
    }


    /** 当前验证的 URL（串行切换域名时更新；默认空串防 EXTRA_URL 缺失时 onDestroy 崩溃） */
    private var targetUrl: String = ""
    private lateinit var statusText: TextView
    private var webView: WebView? = null

    /** 「取消」按钮（buildLayout 程序化构建时留存引用，供 TV 焦点装配定位） */
    private var cancelView: TextView? = null

    /** 是否电视形态：onCreate 一次判定，焦点装配与按键拦截共用同一门控 */
    private var isTv = false

    /** WebView 中心键 DOWN 是否已手动转发（保证 UP 配对送达，见 dispatchKeyEvent） */
    private var tvClickDownForwarded = false

    private val handler = Handler(Looper.getMainLooper())

    /** 是否已回传结果（通过/取消/超时），切换下一域名时重置 */
    private var completed = false

    /** 当前域名的 Cookie 轮询已等待时长（切换下一域名时重置） */
    private var elapsedMs = 0L

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (completed) return
            val cookie = CookieManager.getInstance().getCookie(targetUrl)
            if (cookie != null && cookie.contains("cf_clearance")) {
                CookieManager.getInstance().flush()
                Log.d(TAG, "人工验证：已获取 cf_clearance，关闭窗口")
                finishWithResult(cookie)
                return
            }
            elapsedMs += POLL_INTERVAL_MS
            if (elapsedMs >= MAX_WAIT_MS) {
                Log.w(TAG, "人工验证超时（${MAX_WAIT_MS / 1000}s）: $targetUrl")
                finishWithResult(null)
                return
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 形态定方向：手机竖屏、电视横屏（基线是清单写死 portrait，现改为按形态运行时设置）
        isTv = com.hpu.mymoviestore.presentation.tv.TvUiSupport.isTelevision(this)
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        targetUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }

        setContentView(buildLayout())
        setupWebView()
        attachTvFocusSupport()

        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    /** 程序化构建：全屏半透明遮罩 → 垂直卡片（标题 + 说明 + 状态 + WebView + 取消） */
    private fun buildLayout(): ViewGroup {
        val dp = resources.displayMetrics.density

        fun dp(v: Int) = (v * dp).toInt()

        fun dpF(v: Float) = v * dp

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt()) // 70% 黑遮罩，透出下层页面
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF262220.toInt()) // 与弹框背景 #332C29 同族的暗色
                cornerRadius = dpF(24f)
            }
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "需要安全验证"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(
            TextView(this).apply {
                text = "该播放源启用了 Cloudflare 人机验证。请点击下方按钮开始验证，若出现勾选框请手动勾选，通过后本窗口会自动关闭。"
                setTextColor(0xFFB0AFAE.toInt())
                textSize = 13f
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(8), 0, dp(12))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        statusText = TextView(this).apply {
            text = "等待验证完成…"
            setTextColor(0xFFFF6B35.toInt())
            textSize = 12f
        }
        card.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val webContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFF5F4F3.toInt())
                cornerRadius = dpF(12f)
            }
            clipToOutline = true
        }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)
            )
            // 半透明 Activity 上 WebView 默认透明背景有白屏/不渲染的已知问题，显式设白底
            setBackgroundColor(Color.WHITE)
        }
        webContainer.addView(webView)
        card.addView(
            webContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        val cancel = TextView(this).apply {
            text = "取消"
            setTextColor(0xFF9C9A98.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener {
                Log.d(TAG, "人工验证：用户取消")
                finishWithResult(null)
            }
        }
        cancelView = cancel
        card.addView(
            cancel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = webView ?: return
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 与 OkHttp 爬虫请求一致的 UA（cf_clearance 绑定 UA；用设备真实 UA）
            userAgentString = CloudflareBypassManager.userAgent()
            // 概览模式：页面按桌面宽度渲染后整体缩小到容器一屏内展示，
            // 验证按钮一出来就可见，无需手动缩小或上下滑动找按钮；支持双指放大点击
            useWideViewPort = true
            loadWithOverviewMode = true
            supportZoom()
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        var retried = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                statusText.text = "正在加载验证页…"
                Log.d(TAG, "人工验证 onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val title = view?.title.orEmpty()
                statusText.text = if (title.isNotBlank()) "已加载：$title" else "验证页已加载，请完成勾选…"
                Log.d(TAG, "人工验证 onPageFinished: title='$title', url=$url")
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                statusText.text = "加载失败：$description"
                Log.w(TAG, "人工验证 onReceivedError: code=$errorCode, desc=$description, url=$failingUrl")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // 主文档的 HTTP 错误才提示（子资源错误不影响挑战页展示）
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "人工验证主文档 HTTP 错误: ${errorResponse?.statusCode} ${request.url}")
                }
            }
        }

        // console 透传：诊断 CF 挑战脚本异常；错误直接显示到状态行（免 logcat 也能看根因）
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                val level = consoleMessage?.messageLevel()
                Log.d(
                    TAG,
                    "人工验证 console: [$level] ${consoleMessage?.message()} @ ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}"
                )
                if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    statusText.text = "脚本错误: ${consoleMessage?.message()?.take(80)}"
                }
                return true
            }
        }

        // 布局完成后再加载（避免视图未挂载时加载导致白屏）；8 秒进度仍为 0 时重试一次
        wv.post {
            Log.d(TAG, "人工验证开始加载: $targetUrl")
            wv.loadUrl(targetUrl)
        }
        handler.postDelayed({
            if (!completed && wv.progress == 0 && !retried) {
                retried = true
                Log.w(TAG, "人工验证 8 秒无进度，重试加载")
                statusText.text = "加载缓慢，正在重试…"
                wv.reload()
            }
        }, 8_000L)

        // 页面加载完成后探测正文内容长度：为 0 说明挑战脚本没渲染出来（引擎兼容问题），
        // 结果直接显示在状态行，免 logcat 也能定位
        handler.postDelayed({
            if (!completed) {
                wv.evaluateJavascript("(document.body ? document.body.innerText.length : -1)") { len ->
                    Log.d(TAG, "人工验证正文长度: $len")
                    if (len == "0") {
                        statusText.text = "页面已加载但正文为空（挑战脚本未执行成功）"
                    }
                }
            }
        }, 12_000L)
    }

    /**
     * TV 焦点装配 —— 本窗口布局是程序化构建的，此前没有任何 TvFocus 处理：
     * 电视上「取消」TextView 默认不可聚焦（遥控器焦点落不到、无法按 OK 键取消），
     * WebView 也未拿到 View 焦点（D-pad 中心键无法向页面投递点击）。这里补齐两端：
     *
     * - 「取消」：用 [TvFocus.applyToDialogButtons] 遍历补环 —— 它只处理「可点击」控件，
     *   取消有 OnClickListener（isClickable=true）会被覆盖到，挂上自绘橙焦点环；
     *   整行满宽控件在获焦瞬间被 canScaleUp 的满宽规则拦下，只显环不放大。
     *   挂环后遥控器能停靠、OK 键触发 performClick → 取消。
     * - WebView：显式 focusable + focusableInTouchMode，使 D-pad 能把 View 焦点移入它，
     *   配合 [dispatchKeyEvent] 把中心键转成点击。
     * - 初始焦点落在「取消」：一进入窗口遥控器就有可见落点（WebView 内容此时多半未加载完，
     *   先停靠取消更符合直觉；用户按上键可把焦点移进 WebView 操作勾选框）。
     *
     * 手机端：[isTv] 为 false 直接 return，触摸交互完全不受影响（焦点环/初始焦点均不介入）。
     */
    private fun attachTvFocusSupport() {
        if (!isTv) return
        // 取消按钮：补自绘焦点环 + 可遥控器聚焦（幂等，已处理控件带标记会跳过）
        cancelView?.let { TvFocus.applyToDialogButtons(it) }
        // WebView：参与 View 焦点链（覆盖式设置，不动触摸点击逻辑）
        webView?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // 初始焦点落「取消」，避免电视进入后焦点悬空
        cancelView?.let { TvFocus.requestInitialFocus(it) }
    }

    /**
     * 电视端把方向键中心键（DPAD_CENTER）/ Enter 转成 WebView 的 DOM 点击。
     *
     * 焦点在 WebView 上时，D-pad 中心键默认也只会派发给 WebView 本身；个别 TV ROM 会在
     * ViewGroup 层把 CENTER 提前吃掉做焦点搜索，页面内的勾选框就收不到点击。
     * 这里手动把一次中心键「DOWN+UP」成对转发给 WebView（Chromium 对 focused DOM 节点
     * 映射为 click），并消费掉避免二次处理——无论 WebView 是否吃掉 DOWN，默认派发路径
     * （焦点视图就是 WebView）能做到的我们也已等价做到，不存在额外吞键。
     * 手机端 [isTv] 为 false，不进入本分支。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv) {
            val wv = webView
            val isClickKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER
            if (wv != null && isClickKey && wv.hasFocus()) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        wv.requestFocus()
                        tvClickDownForwarded = wv.dispatchKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, event.keyCode)
                        )
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        if (tvClickDownForwarded) {
                            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, event.keyCode))
                            tvClickDownForwarded = false
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 当前域名验证结束（通过/取消/超时）：
     * 回传结果 → 从串行队列取下一个待验证域名，有则原窗口切换继续，无则关闭。
     */
    private fun finishWithResult(cookie: String?) {
        if (completed) return
        completed = true
        handler.removeCallbacks(cookiePollRunnable)
        CloudflareBypassManager.completeInteractive(targetUrl, cookie)

        val next = CloudflareBypassManager.nextChallengeUrl()
        if (next != null) {
            Log.d(TAG, "切换到下一个待验证域名: $next")
            targetUrl = next
            completed = false
            elapsedMs = 0
            statusText.text = "正在加载验证页…"
            webView?.let {
                it.stopLoading()
                it.loadUrl(next)
            }
            handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 用户按返回键退出等未回传场景：补发当前域名 null 结果（已回传时为 no-op）
        if (!completed && targetUrl.isNotBlank()) {
            completed = true
            CloudflareBypassManager.completeInteractive(targetUrl, null)
        }
        // 通知管理器窗口已销毁（队列仍有待验证项时由管理器重拉窗口）
        CloudflareBypassManager.onChallengeActivityDestroyed()
        webView?.apply {
            runCatching { stopLoading() }
            runCatching { destroy() }
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CloudflareBypass"

        const val EXTRA_URL = "extra_url"

        /** Cookie 轮询间隔 */
        private const val POLL_INTERVAL_MS = 2_000L

        /** 人工验证最长等待 */
        private const val MAX_WAIT_MS = 180_000L
    }
}
