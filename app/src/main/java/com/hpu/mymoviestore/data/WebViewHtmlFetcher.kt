package com.hpu.mymoviestore.data

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hpu.mymoviestore.MovieApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

/**
 * 后台 WebView HTML 抓取器（TLS 指纹拦截降级方案）。
 *
 * ## 使用场景
 * 少数站点 WAF 按 TLS 指纹（JA3）拦截 OkHttp 等非浏览器客户端——连接在
 * TLS 握手阶段就被 reset，UA/请求头怎么改都没用（拦截发生在加密协商阶段）。
 * 而 WebView 使用浏览器的 TLS 栈，指纹与真实浏览器一致，可以正常建立连接。
 *
 * ## 工作方式
 * 后台创建不挂载界面的 WebView 加载目标 URL，onPageFinished 后通过
 * `document.documentElement.outerHTML` 提取完整 HTML 字符串返回，
 * 交由 Jsoup 解析。与 [CloudflareBypassManager] 共用同一套后台 WebView 基建
 * （虚拟视口、UA 统一、主线程约束）。
 *
 * ## 适用性
 * - 仅作为 OkHttp 握手失败（[javax.net.ssl.SSLException]）时的降级路径，
 *   正常请求不走此通道（WebView 抓取比 OkHttp 慢且耗资源）；
 * - 不依赖挑战脚本，老引擎设备同样可用；
 * - 页面内 JS 会照常执行（真实浏览器环境），对动态渲染页反而更稳。
 */
object WebViewHtmlFetcher {

    private const val TAG = "WebViewHtmlFetcher"

    /** 抓取超时 */
    private const val FETCH_TIMEOUT_MS = 25_000L

    /** onPageFinished 后等待 JS 渲染的缓冲 */
    private const val RENDER_SETTLE_MS = 500L

    /**
     * 后台 WebView 抓取页面 HTML。
     *
     * @return HTML 字符串；失败/超时返回 null
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()

        val webView = WebView(MovieApplication.get()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 与 OkHttp 爬虫相同的 UA（本场景 UA 不参与 TLS 拦截，但保持一致便于 Cookie 复用）
            settings.userAgentString = HttpClientProvider.crawlerUserAgent()
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
        }

        // 后台 WebView 默认 0×0 视口会被部分站点判定异常，给真实屏幕尺寸
        val dm = MovieApplication.get().resources.displayMetrics
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, dm.widthPixels, dm.heightPixels)

        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            private var handled = false

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                if (handled || view == null) return
                handled = true

                Log.d(TAG, "页面加载完成，提取 HTML: title='${view.title}', url=$finishedUrl")
                // 留 500ms 给 JS 收尾渲染，然后提取完整 DOM
                view.postDelayed({
                    if (deferred.isActive) {
                        view.evaluateJavascript(
                            "(document.documentElement ? document.documentElement.outerHTML : '')"
                        ) { json ->
                            deferred.complete(unescape(json))
                        }
                    }
                }, RENDER_SETTLE_MS)
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                // 子资源（图片/css）失败不影响主文档抓取，只记日志
                Log.w(TAG, "WebView 资源加载错误（不中断抓取）: code=$errorCode, url=$failingUrl")
            }
        }

        webView.loadUrl(url)

        try {
            val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) { deferred.await() }
            if (html.isNullOrBlank()) {
                Log.w(TAG, "WebView 抓取失败（超时或空 HTML）: $url")
            } else {
                Log.i(TAG, "WebView 抓取成功: url=$url, htmlLength=${html.length}")
            }
            html
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /** evaluateJavascript 返回 JSON 字符串字面量，还原为原始字符串 */
    private fun unescape(json: String?): String? = runCatching {
        val raw = json?.trim()?.takeIf { it != "null" } ?: return null
        JSONTokener(raw).nextValue() as? String
    }.getOrNull()
}
