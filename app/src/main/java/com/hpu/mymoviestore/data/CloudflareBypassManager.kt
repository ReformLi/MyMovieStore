package com.hpu.mymoviestore.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hpu.mymoviestore.presentation.challenge.CloudflareChallengeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "CloudflareBypass"

/**
 * 自研过盾轮询脚本（替代 Cloudflare-Bypass 库）：
 * 每 1.5s 检查 #challenge-form——存在则尝试点击非交互挑战按钮，
 * 消失则通过 JS 桥回调 onByPass() 释放 latch。
 * 桥接接口在 loadUrl 之前注入，保证当前页面即可用
 * （库在 onPageStarted 注入会漏掉当前页，导致 "CloudFlareByPassInterface is not defined"）。
 */
private const val BYPASS_POLLING_JS = """
(function() {
    if (window.__cfBypassPolling) return;
    window.__cfBypassPolling = true;
    var ticks = 0;
    var timer = setInterval(function() {
        if (document.querySelector('#challenge-form') != null) {
            var btn = document.querySelector("#challenge-stage > div > input[type='button']");
            if (btn != null) btn.click();
            if (++ticks > 40) clearInterval(timer);
        } else {
            clearInterval(timer);
            try { CloudFlareByPassInterface.onByPass(); } catch (e) {}
        }
    }, 1500);
})()
"""

/**
 * 检测 Turnstile 挑战框位置的 JS：返回其中心点坐标（视口像素）。
 * Turnstile 的 iframe 是跨域的，无法从父页面 JS 点击（isTrusted=false 会被拒绝），
 * 必须把坐标带回 Android 侧用真实 MotionEvent 模拟触屏点击。
 */
private const val FIND_TURNSTILE_JS = """
(function() {
    var f = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
    if (!f) return null;
    var r = f.getBoundingClientRect();
    if (r.width < 10 || r.height < 10) return null;
    return JSON.stringify({x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2)});
})()
"""

/** Cloudflare 挑战页响应体特征标记（覆盖中英文文案与脚本特征） */
private val CHALLENGE_BODY_MARKERS = listOf(
    "进行安全验证",
    "验证您不是自动程序",
    "请稍候",
    "just a moment",
    "attention required",
    "challenge-platform",
    "_cf_chl",
    "cf-browser-verification"
)

/**
 * Cloudflare 人机验证绕过管理器（两阶段，纯自研，无三方库依赖）。
 *
 * ## 关键设计
 * - **真实 UA**：[effectiveUserAgent] 为设备 WebView 的真实 UA。
 *   伪装高版本 Chrome UA 会导致 CF 下发新语法脚本，老引擎直接 SyntaxError
 *   崩溃（挑战永远无法执行）；用真实 UA 时 CF 下发与引擎匹配的脚本。
 *   cf_clearance 与 UA 绑定，OkHttp 请求必须用同一 UA
 *   （见 [HttpClientProvider.crawlerUserAgent]）。
 * - **JS 桥预注入**：桥接接口在 loadUrl 前通过 addJavascriptInterface 注入，
 *   确保当前页面即可调用（修复库的注入时机缺陷）。
 *
 * ## 第一阶段：后台自动过盾
 * - 注入自研轮询脚本（#challenge-form 检测 + 自动点击 + 通过回调）；
 * - 每 3s 轮询 Turnstile 挑战框位置，用真实 MotionEvent 模拟点击；
 * - WebView 给定真实屏幕尺寸的视口（0×0 会被 CF 完整性检测判异常）；
 * - WebChromeClient 捕获挑战脚本 SyntaxError（引擎过老），提前失败转人工。
 *
 * ## 第二阶段：人工验证兜底
 * 自动过盾失败时弹出 [CloudflareChallengeActivity]，用户手动完成挑战，
 * Cookie 拿到后窗口自动关闭并缓存 30 分钟。
 */
object CloudflareBypassManager {

    /** 过盾整体超时（含挑战执行 + 页面重载） */
    private const val BYPASS_TOTAL_TIMEOUT_MS = 45_000L

    /** 挑战脚本语法错误后的宽限等待（之后判定自动过盾失败转人工） */
    private const val SYNTAX_ERROR_GRACE_MS = 12_000L

    /** 过盾 Cookie 本地缓存有效期（cf_clearance 实际时效更长，保守设置） */
    private const val COOKIE_VALIDITY_MS = 30L * 60 * 1000

    /** 人工验证界面等待时长 */
    private const val INTERACTIVE_TIMEOUT_MS = 180_000L

    private lateinit var appContext: Context

    @Volatile
    private var initialized = false

    /**
     * 设备 WebView 真实 UA（cf_clearance 与 UA 绑定，OkHttp/WebView 必须一致）。
     * 由 [init] 在主线程读取；读取失败为 null，调用方使用兜底 UA。
     */
    @Volatile
    var effectiveUserAgent: String? = null
        private set

    /**
     * WebView 引擎不兼容标记：捕获到 CF 挑战脚本 SyntaxError（引擎 < Chrome 80）后置 true。
     * 置位后所有过盾请求快速失败（不再起 WebView、不弹人工验证窗），
     * 调用方据此对源写负缓存限时跳过。设备 WebView 升级后需重启 App 重置。
     */
    @Volatile
    var engineIncompatible: Boolean = false
        private set

    /** Cookie 缓存：key = scheme://host */
    private val cookieCache = ConcurrentHashMap<String, CookieEntry>()

    /** 同域名并发去重：自动 + 人工两阶段共用同一个 Deferred */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    private data class CookieEntry(val cookie: String, val fetchedAtElapsed: Long)

    fun init(context: Context) {
        appContext = context.applicationContext
        // 读取真实 WebView UA：让 CF 下发与设备引擎匹配的挑战脚本
        effectiveUserAgent = runCatching {
            WebSettings.getDefaultUserAgent(appContext)
        }.getOrNull()
        Log.i(TAG, "设备 WebView 真实 UA: ${effectiveUserAgent?.take(90)}")
        logWebViewEngineVersion()
        initialized = true
    }

    /** 打印系统 WebView 内核包名+版本（诊断 CF 挑战脚本语法兼容性） */
    private fun logWebViewEngineVersion() {
        runCatching {
            val pm = appContext.packageManager
            val candidates = listOf(
                "com.google.android.webview",
                "com.android.webview",
                "com.huawei.webview",
                "com.huawei.hmos.webview",
                "com.mi.global.browser.webview",
                "com.samsung.android.webview"
            )
            for (pkg in candidates) {
                runCatching {
                    val info = pm.getPackageInfo(pkg, 0)
                    Log.w(TAG, "WebView 内核: $pkg v${info.versionName}")
                }
            }
            // 当前生效的 WebView 包
            Class.forName("android.webkit.WebView")
                .getMethod("getCurrentWebViewPackage")
                ?.let { method ->
                    runCatching {
                        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                        val pkgInfo = method.invoke(null) as? android.content.pm.PackageInfo
                        if (pkgInfo != null) {
                            Log.w(TAG, "当前生效 WebView 内核: ${pkgInfo.packageName} v${pkgInfo.versionName}")
                        }
                    }
                }
        }
    }

    /** 过盾用 UA：真实 UA 优先，未初始化/读取失败时兜底 */
    fun userAgent(): String = effectiveUserAgent ?: FALLBACK_USER_AGENT

    /**
     * 获取指定 URL 域名下仍在有效期内的过盾 Cookie（无网络操作）。
     * 命中时调用方可直接携带该 Cookie 发请求，避免先吃一次 403。
     */
    fun getCachedCookie(url: String): String? {
        if (!initialized) return null
        val key = domainKey(url) ?: return null
        val entry = cookieCache[key] ?: return null
        if (SystemClock.elapsedRealtime() - entry.fetchedAtElapsed > COOKIE_VALIDITY_MS) {
            cookieCache.remove(key)
            Log.d(TAG, "过盾 Cookie 已过期，清除缓存: $key")
            return null
        }
        return entry.cookie
    }

    /** 清除指定 URL 域名的过盾 Cookie 缓存（过盾后仍被拦截时调用） */
    fun invalidate(url: String) {
        domainKey(url)?.let { key ->
            cookieCache.remove(key)
            Log.d(TAG, "已清除过盾 Cookie 缓存: $key")
        }
    }

    /**
     * 确保指定 URL 已通过 Cloudflare 验证，返回该域名的过盾 Cookie。
     *
     * 流程：缓存命中 → 后台自动过盾 → 失败弹人工验证窗口 → 返回 Cookie。
     *
     * @return 过盾成功返回 Cookie 字符串；失败/超时/用户取消返回 null
     */
    suspend fun ensureBypassed(url: String): String? = withContext(Dispatchers.IO) {
        if (!initialized) {
            Log.w(TAG, "CloudflareBypassManager 未初始化，跳过过盾")
            return@withContext null
        }
        val key = domainKey(url) ?: return@withContext null

        // 0) 引擎已确认不兼容（CF 挑战脚本语法崩溃）：快速失败，不弹窗不等待，
        //    调用方据此写负缓存，搜索时限时跳过该源
        if (engineIncompatible) {
            Log.w(TAG, "WebView 引擎不兼容已确认，快速失败（升级系统 WebView 并重启 App 可恢复）: $key")
            return@withContext null
        }

        // 1) 缓存命中
        getCachedCookie(url)?.let {
            Log.d(TAG, "过盾 Cookie 缓存命中: $key")
            return@withContext it
        }

        // 2) 同域名去重：复用进行中的过盾任务
        inFlight[key]?.let {
            Log.d(TAG, "同域名过盾任务进行中，等待共享结果: $key")
            return@withContext it.await()
        }

        val deferred = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(key, deferred)
        if (existing != null) return@withContext existing.await()

        try {
            // ── 第一阶段：后台无界面自动过盾 ──
            Log.i(TAG, "启动后台 WebView 自动过盾: $url")
            var result = runWebViewBypass(url)

            // ── 第二阶段：人工验证兜底（引擎不兼容时跳过——挑战脚本跑不起来，
            //    弹窗必然白屏无意义；正常设备自动失败才弹窗，用户点一下即可）──
            if (result.isNullOrBlank()) {
                if (engineIncompatible) {
                    Log.w(TAG, "引擎不兼容，跳过人工验证（弹窗无法完成挑战）: $url")
                } else {
                    Log.w(TAG, "自动过盾失败，启动人工验证窗口: $url")
                    result = awaitInteractiveBypass(url, deferred)
                }
            }

            if (!result.isNullOrBlank()) {
                cookieCache[key] = CookieEntry(result, SystemClock.elapsedRealtime())
                Log.i(TAG, "过盾成功并缓存 Cookie: $key")
            } else {
                Log.w(TAG, "过盾最终失败（含人工验证）: $url")
            }
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            Log.e(TAG, "过盾过程异常: $url", t)
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key)
        }
    }

    /** 由 [CloudflareChallengeActivity] 回调（用户通过/取消/超时），Cookie 可为 null */
    fun completeInteractive(url: String, cookie: String?) {
        val key = domainKey(url) ?: return
        inFlight[key]?.complete(cookie)
        Log.d(TAG, "人工验证结果回调: key=$key, got=${!cookie.isNullOrBlank()}")
    }

    // ===================== 第一阶段：后台自动过盾 =====================

    /**
     * 后台 WebView 自动过盾。
     * WebView 创建/加载/销毁必须在主线程；本挂起函数挂起调用方协程直到完成或超时。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runWebViewBypass(url: String): String? = withContext(Dispatchers.Main) {
        val latch = CountDownLatch(1)
        val deferred = CompletableDeferred<String?>()

        val webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 关键：使用设备真实 UA（伪装高版本 UA 会让 CF 下发老引擎跑不动的新语法脚本）
            settings.userAgentString = userAgent()
            // 过盾只需要执行 JS，禁图省流量
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.mediaPlaybackRequiresUserGesture = false
        }

        // 关键：后台 WebView 不挂载视图树，默认 0×0 视口会被 CF 完整性检测判异常，
        // 手动 measure/layout 给它一个与真实屏幕一致的虚拟视口
        val dm = appContext.resources.displayMetrics
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, dm.widthPixels, dm.heightPixels)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // JS 桥：必须在 loadUrl 之前注入，当前页面才能调用
        // （库在 onPageStarted 注入会漏当前页 → "CloudFlareByPassInterface is not defined"）
        webView.addJavascriptInterface(BypassJsInterface(latch), "CloudFlareByPassInterface")

        webView.webViewClient = BypassWebViewClient(targetUrl = url, latch = latch) { passed ->
            if (deferred.isActive) {
                val cookie = if (passed) {
                    CookieManager.getInstance().getCookie(url).also {
                        CookieManager.getInstance().flush()
                    }
                } else {
                    null
                }
                Log.d(TAG, "自动过盾结束: passed=$passed, cookie=${cookie?.take(60)}")
                deferred.complete(cookie)
            }
        }

        // 捕获挑战脚本 SyntaxError（引擎过老跑不动 CF 脚本）：宽限后快速失败转人工
        // （仅主线程读写，无需 @Volatile）
        var syntaxErrorSeen = false
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message().orEmpty()
                if (msg.contains("SyntaxError") &&
                    consoleMessage?.sourceId().orEmpty().contains("challenge-platform")
                ) {
                    syntaxErrorSeen = true
                    // 引擎确认不兼容（CF 挑战脚本需要 Chrome 80+）：全局标记，
                    // 后续所有 CF 源快速失败 + 负缓存限时跳过，不再弹验证窗
                    engineIncompatible = true
                    Log.w(TAG, "CF 挑战脚本语法错误（WebView 引擎过老，已全局标记不兼容）: ${consoleMessage?.sourceId()}")
                }
                return true
            }
        }
        val fastFailHandler = Handler(Looper.getMainLooper())
        val fastFailRunnable = object : Runnable {
            override fun run() {
                if (syntaxErrorSeen && !deferred.isCompleted) {
                    Log.w(TAG, "挑战脚本已崩溃，自动过盾快速失败转人工")
                    deferred.complete(null)
                }
            }
        }
        fastFailHandler.postDelayed(fastFailRunnable, SYNTAX_ERROR_GRACE_MS)

        // Turnstile 真实点击轮询：检测挑战框 → 模拟触屏点击（isTrusted=true）
        val tapHandler = Handler(Looper.getMainLooper())
        var tapCount = 0
        val tapRunnable = object : Runnable {
            override fun run() {
                if (deferred.isCompleted || tapCount >= 8) return
                webView.evaluateJavascript(FIND_TURNSTILE_JS) { json ->
                    val center = parseTurnstileRect(json)
                    if (center != null) {
                        tapCount++
                        Log.d(TAG, "检测到 Turnstile 挑战框，模拟真实点击 #$tapCount: $center")
                        simulateTap(webView, center.first, center.second)
                    }
                    tapHandler.postDelayed(this, 3_000L)
                }
            }
        }

        webView.loadUrl(url)
        tapHandler.postDelayed(tapRunnable, 4_000L)

        try {
            withTimeoutOrNull(BYPASS_TOTAL_TIMEOUT_MS) { deferred.await() }
        } finally {
            fastFailHandler.removeCallbacksAndMessages(null)
            tapHandler.removeCallbacksAndMessages(null)
            // 回收 WebView（仍在主线程上下文中）
            runCatching { webView.stopLoading() }
            runCatching { webView.removeJavascriptInterface("CloudFlareByPassInterface") }
            runCatching { webView.destroy() }
        }
    }

    /** 在 WebView 上模拟一次真实触屏点击（Turnstile 只认 isTrusted=true 的事件） */
    private fun simulateTap(webView: WebView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        webView.dispatchTouchEvent(down)
        val up = MotionEvent.obtain(now, now + 120, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** 解析 evaluateJavascript 返回的 JSON 字符串为挑战框中心点坐标 */
    private fun parseTurnstileRect(json: String?): Pair<Float, Float>? = runCatching {
        val raw = json?.trim()?.takeIf { it != "null" } ?: return null
        val unwrapped = raw.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
        if (unwrapped == "null" || unwrapped.isBlank()) return null
        val obj = JSONObject(unwrapped)
        obj.getDouble("x").toFloat() to obj.getDouble("y").toFloat()
    }.getOrNull()

    // ===================== 第二阶段：人工验证兜底 =====================

    /**
     * 启动人工验证窗口并等待结果。
     * 复用 [inFlight] 中当前域名的 Deferred，[CloudflareChallengeActivity]
     * 完成后通过 [completeInteractive] 回填结果。
     */
    private suspend fun awaitInteractiveBypass(
        url: String,
        deferred: CompletableDeferred<String?>
    ): String? {
        withContext(Dispatchers.Main) {
            val intent = android.content.Intent(appContext, CloudflareChallengeActivity::class.java)
                .putExtra(CloudflareChallengeActivity.EXTRA_URL, url)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
        return withTimeoutOrNull(INTERACTIVE_TIMEOUT_MS) { deferred.await() }
    }

    /** 提取 scheme://host 作为缓存 key（忽略路径/参数差异） */
    private fun domainKey(url: String): String? = runCatching {
        val parsed = url.toHttpUrlOrNull() ?: return null
        "${parsed.scheme}://${parsed.host}"
    }.getOrNull()

    /**
     * 检测 HTTP 响应体是否为 Cloudflare 挑战页（与页面语言无关）。
     * 挑战页通常伴随 HTTP 403/503，也可能以 200 返回 JS 挑战。
     */
    fun isCloudflareChallenge(body: String): Boolean {
        if (body.isBlank()) return false
        return CHALLENGE_BODY_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    /** 兜底 UA：init 失败时使用（偏旧的通用移动 Chrome，兼容绝大多数 CF 下发脚本） */
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36"
}

/** JS 桥：轮询脚本发现挑战通过后回调，释放 latch */
private class BypassJsInterface(private val latch: CountDownLatch) {
    @JavascriptInterface
    fun onByPass() {
        latch.countDown()
    }
}

/**
 * 自动过盾 WebViewClient（纯自研，替代 Cloudflare-Bypass 库的 BypassClient）。
 *
 * 完成判定（三选一，先到先得）：
 * 1. **latch 释放**：挑战在原页面内通过，轮询脚本发现 `#challenge-form` 消失；
 * 2. **挑战页重载完成**：Cloudflare 挑战通过后通常整页 reload，重载会清掉
 *    JS 上下文导致 latch 永远不释放——此时重载后的正常页（标题不再像挑战页）
 *    加载完成即视为过盾成功；
 * 3. **Cookie 兜底**：latch 超时但 CookieManager 已出现 `cf_clearance`，仍视为成功。
 */
private class BypassWebViewClient(
    private val targetUrl: String,
    private val latch: CountDownLatch,
    private val onResult: (passed: Boolean) -> Unit
) : WebViewClient() {

    private var completed = false

    /** 是否已见过首个页面加载（用于区分"首次加载即挑战页"与"重载后的正常页"） */
    private var firstLoad = true

    private fun completeOnce(passed: Boolean, reason: String) {
        if (completed) return
        completed = true
        Log.d(TAG, "过盾判定: passed=$passed, 原因=$reason")
        onResult(passed)
    }

    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.w(TAG, "过盾 WebView 加载错误: code=$errorCode, desc=$description, url=$failingUrl")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (completed) return

        if (view == null) {
            completeOnce(false, "view 为空")
            return
        }

        val challengeLike = isChallengeTitle(view.title)
        Log.d(TAG, "页面加载完成: title='${view.title}', challengeLike=$challengeLike, url=$url")

        // 重载后的正常页：过盾成功
        if (!challengeLike && !firstLoad) {
            completeOnce(true, "挑战页重载完成，已出正常页面")
            return
        }
        firstLoad = false

        // 注入自研轮询脚本：#challenge-form 消失时通过 JS 桥回调
        view.evaluateJavascript(BYPASS_POLLING_JS, null)

        // 后台等待 latch（释放=原页内通过；超时走 Cookie 兜底判定）
        Thread {
            val released = runCatching { latch.await(40, TimeUnit.SECONDS) }.getOrDefault(false)
            android.os.Handler(Looper.getMainLooper()).post {
                if (completed) return@post
                if (released) {
                    completeOnce(true, "挑战在原页面内通过（latch 释放）")
                } else {
                    val hasClearance = CookieManager.getInstance()
                        .getCookie(targetUrl)
                        ?.contains("cf_clearance") == true
                    completeOnce(
                        hasClearance,
                        if (hasClearance) "latch 超时但已拿到 cf_clearance" else "latch 超时且无 cf_clearance"
                    )
                }
            }
        }.start()
    }
}

/** 根据页面标题粗判是否为 Cloudflare 挑战页（覆盖中英文标题） */
internal fun isChallengeTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    val t = title.trim()
    return t.contains("...") ||                 // "Just a moment..."
        t.contains("…") ||                      // "请稍候…"
        t.contains("请稍候") ||
        t.contains("进行安全验证") ||
        t.contains("Just a moment", ignoreCase = true) ||
        t.contains("Attention Required", ignoreCase = true) ||
        t.contains("Checking your browser", ignoreCase = true)
}
