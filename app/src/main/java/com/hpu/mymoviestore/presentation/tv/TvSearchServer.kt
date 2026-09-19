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
 * - POST /api/search → 接收手机提交的搜索关键词，存入 SharedPreferences
 * - GET /api/last_query → 返回最新搜索关键词（供轮询）
 *
 * 端口：8234（固定，避免与常用端口冲突）
 */
class TvSearchServer(
    private val context: Context,
    private val onSearchReceived: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var isRunning = false

    val port: Int = 8234

    /** 最新收到的搜索关键词，供轮询读取 */
    @Volatile
    var lastQuery: String = ""
        private set

    /** 上次查询的时间戳，用于判断是否有新查询 */
    @Volatile
    var lastQueryTime: Long = 0L
        private set

    fun start() {
        if (isRunning) return
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)
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
