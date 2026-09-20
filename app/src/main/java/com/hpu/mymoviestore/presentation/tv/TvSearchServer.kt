package com.hpu.mymoviestore.presentation.tv

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface

/**
 * TV 搜索 HTTP 服务器 —— 轻量级，纯 ServerSocket 实现
 *
 * 功能：
 * - GET /search.html → 返回手机端搜索网页
 * - POST /api/search → 接收手机提交的搜索关键词，回调 [onSearchReceived]
 * - GET /api/last_query → 返回最新搜索关键词（供轮询）
 *
 * 端口：[PORT]（固定 8234，避免与常用端口冲突）。
 *
 * **生命周期由调用方控制**：只在搜索页二维码可见期间运行（见 SearchFragment
 * updateSearchServerState）；stop() 后端口关闭，手机连接直接拒绝。
 */
class TvSearchServer(
    private val context: Context,
    private val onSearchReceived: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var isRunning = false

    val port: Int = PORT

    /** 最新收到的搜索关键词，供轮询读取 */
    @Volatile
    var lastQuery: String = ""
        private set

    /** 上次查询的时间戳，用于判断是否有新查询 */
    @Volatile
    var lastQueryTime: Long = 0L
        private set

    /** favicon 字节缓存（962B 的小 PNG，读一次常驻内存，避免每个请求都开 assets） */
    @Volatile
    private var faviconBytes: ByteArray? = null

    fun start() {
        if (isRunning) return
        serverThread = Thread {
            try {
                // 显式 bind + SO_REUSEADDR：二维码可见性会多次触发 start/stop，
                // 若沿用 ServerSocket(port)，快速重启可能撞上上一实例的 TIME_WAIT 绑定失败
                val socket = ServerSocket().apply { reuseAddress = true }
                socket.bind(java.net.InetSocketAddress(port))
                serverSocket = socket
                isRunning = true
                Log.d(TAG, "搜索服务器启动: port=$port")
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        handleRequest(client)
                    } catch (e: Exception) {
                        if (isRunning) Log.w(TAG, "处理请求异常: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务器启动失败: ${e.message}")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        Log.d(TAG, "搜索服务器已停止")
    }

    private fun handleRequest(client: Socket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)

                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread

                val method = parts[0]
                val path = parts[1]

                // 读取请求头
                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrEmpty()) break
                    if (line!!.lowercase().startsWith("content-length:")) {
                        contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                when {
                    method == "GET" && path == "/search.html" -> {
                        serveSearchHtml(writer)
                    }
                    // 浏览器标签页图标：自动请求 /favicon.ico，HTML 里也有显式 link
                    method == "GET" && (path == "/favicon.ico" || path == "/favicon.png") -> {
                        serveFavicon(client.getOutputStream())
                    }
                    method == "GET" && path == "/api/last_query" -> {
                        serveLastQuery(writer)
                    }
                    method == "POST" && path == "/api/search" -> {
                        val body = if (contentLength > 0) {
                            val chars = CharArray(contentLength)
                            reader.read(chars, 0, contentLength)
                            String(chars)
                        } else ""
                        handleSearchPost(body, writer)
                    }
                    else -> {
                        writer.println("HTTP/1.1 404 Not Found")
                        writer.println("Connection: close")
                        writer.println()
                    }
                }
                writer.flush()
                client.close()
            } catch (e: Exception) {
                Log.w(TAG, "处理连接异常: ${e.message}")
                try { client.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun serveSearchHtml(writer: PrintWriter) {
        val html = context.assets.open("search.html").bufferedReader().readText()
        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: text/html; charset=UTF-8")
        writer.println("Content-Length: ${html.toByteArray().size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(html)
    }

    /**
     * 返回标签页图标（assets/search_favicon.png，PNG 内容挂在 /favicon.ico 路径上，
     * 浏览器两者都认）。首次读取后常驻 [faviconBytes] 缓存；读不到时回 404，
     * 浏览器只会安静地用默认图标，不影响页面功能。
     * 走原始 OutputStream 写：二进制体不能过 PrintWriter。
     */
    private fun serveFavicon(out: java.io.OutputStream) {
        val bytes = faviconBytes ?: runCatching {
            context.assets.open(FAVICON_ASSET).use { it.readBytes() }
                .also { faviconBytes = it }
        }.getOrNull()
        val response = if (bytes == null) {
            "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray()
        } else {
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: max-age=86400\r\n" +
                "Connection: close\r\n\r\n"
            header.toByteArray() + bytes
        }
        runCatching { out.write(response); out.flush() }
    }

    private fun serveLastQuery(writer: PrintWriter) {
        val json = """{"query":"${lastQuery.replace("\"", "\\\"")}","timestamp":$lastQueryTime}"""
        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: application/json; charset=UTF-8")
        writer.println("Content-Length: ${json.toByteArray().size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(json)
    }

    private fun handleSearchPost(body: String, writer: PrintWriter) {
        // 解析 JSON: {"query":"关键词"}
        val query = extractQuery(body)
        if (query.isNotBlank()) {
            lastQuery = query
            lastQueryTime = System.currentTimeMillis()
            Log.d(TAG, "收到搜索请求: '$query'")
            onSearchReceived(query)
        }
        val resp = """{"ok":true}"""
        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: application/json; charset=UTF-8")
        writer.println("Content-Length: ${resp.toByteArray().size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(resp)
    }

    private fun extractQuery(body: String): String {
        // 简单解析 "query":"xxx"
        val key = "\"query\""
        val idx = body.indexOf(key)
        if (idx < 0) return ""
        val after = body.substring(idx + key.length).trimStart(' ', ':', '"')
        val end = after.indexOf('"')
        return if (end > 0) after.substring(0, end) else after
    }

    companion object {
        private const val TAG = "TvSearchServer"

        /** 固定服务端口：QR 码 URL 与服务器共用，不依赖实例存活状态 */
        const val PORT = 8234

        /** 标签页图标资源（assets 下文件名） */
        private const val FAVICON_ASSET = "search_favicon.png"

        /**
         * 获取设备局域网 IP 地址
         * @return IP 字符串，失败返回 null
         */
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}
