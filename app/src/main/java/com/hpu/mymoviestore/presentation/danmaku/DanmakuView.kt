package com.hpu.mymoviestore.presentation.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.View
import com.hpu.mymoviestore.data.model.danmaku.DanmakuComment
import kotlin.math.max

/**
 * 自实现轻量级弹幕组件
 *
 * 时间驱动机制：
 *  - 内部维护 videoTimeMs（视频播放位置）和 wallClockBase（对应的真实时间戳）
 *  - onDraw 每帧通过 System.currentTimeMillis() 推算当前视频时间，无需外部每秒同步
 *  - syncTo() 仅在播放器 seek（跳转）时调用，用来校准时间基准
 *  - 暂停/恢复通过 pause()/resume() 控制，内部自动处理时间偏移
 *  - 扫描游标每帧按时间窗口二分重定位，时间跳变等异常可自动恢复
 *  - 墙钟跳变防御：系统时间前跳（NTP 校时等）时跳过当帧弹幕添加，等待校准恢复
 *
 * p 字段格式（逗号分隔）：
 * - 0: 出现时间（秒）
 * - 1: 类型（1/2/3/6=滚动，4/8=底部，5/7=顶部）
 * - 2: 字号（18/25/36）
 * - 3: 颜色（十进制整数）
 * - 4~7: 其他属性
 */
class DanmakuView(context: Context) : View(context) {

    // ================== 数据模型 ==================

    data class DanmakuItem(
        val cid: Long,         // 弹幕唯一ID（用于实例去重）
        val timeSec: Float,    // 出现时间（秒）
        val type: Int,         // 1/2/3/6=滚动；4/8=底部；5/7=顶部
        val textSizePx: Float, // 文本大小（像素）
        val color: Int,        // ARGB（或 0xRRGGBB）
        val text: String       // 弹幕内容
    )

    /**
     * 正在显示的弹幕（活跃弹幕）
     * - 滚动弹幕：每帧 x 坐标递减（从右向左移动）
     * - 固定弹幕：固定位置，超时后移除
     */
    private data class ActiveDanmaku(
        val item: DanmakuItem,
        var x: Float,           // 当前 x 坐标（滚动弹幕会变化）
        val row: Int,           // 行号（0 = 最上）
        val startAtMs: Long,    // 开始显示时间（毫秒，视频时间）
        val textWidth: Float    // 预计算的文本宽度
    )

    // ================== 内部状态 ==================

    private var danmakuList: List<DanmakuItem> = emptyList()  // 按 timeSec 升序
    private var enabled: Boolean = true
    private var prepared: Boolean = false
    private var paused: Boolean = false

    // 时间驱动：videoTimeMs + wallClockBase 配对
    // 当前视频时间 = videoTimeMs + (System.currentTimeMillis() - wallClockBase)
    private var videoTimeMs: Long = 0L          // 上次同步时的视频时间
    private var wallClockBase: Long = System.currentTimeMillis()  // 上次同步时的真实时间
    private var pausedVideoTimeMs: Long = 0L    // 暂停时冻结的视频时间

    // 三类活跃弹幕
    private val activeScroll: ArrayList<ActiveDanmaku> = ArrayList()
    private val activeTop: ArrayList<ActiveDanmaku> = ArrayList()
    private val activeBottom: ArrayList<ActiveDanmaku> = ArrayList()

    // 实例去重：正在显示/排队的弹幕cid集合，防止同一条弹幕被重复添加
    private val onScreenCids: HashSet<Long> = HashSet()

    // 文本时间窗口去重：同文本在窗口内只显示一条，避免刷屏挤占真弹幕
    private val recentTextTimes: HashMap<String, Long> = HashMap()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 行高（动态计算）
    private var rowHeightPx: Float = 40f
    private var lastDrawWallMs: Long = System.currentTimeMillis()

    // 上一帧的墙钟偏移（now - wallClockBase），用于检测系统时间前跳（NTP 校时等）
    private var lastWallDeltaMs: Long = -1L

    // 滚动：10 秒内从屏幕右侧滚到左侧
    private val scrollDurationMs: Long = 10_000L
    // 固定弹幕：显示 4 秒后消失
    private val fixedDurationMs: Long = 4_000L

    // 已扫描到的弹幕索引（避免每帧都从头遍历）
    private var scanIndex: Int = 0

    // 因行满而未能放置的弹幕（每帧重试）
    private val pendingDanmaku: ArrayList<Pair<DanmakuItem, Float>> = ArrayList()

    // ================== 去重辅助 ==================

    /** 标记弹幕已添加（实例去重 + 文本时间窗口去重） */
    private fun markAdded(item: DanmakuItem, currentVideoMs: Long) {
        onScreenCids.add(item.cid)
        recentTextTimes[item.text] = currentVideoMs
    }

    /** 标记弹幕已离开屏幕（仅清除实例去重，文本窗口由时间自动过期） */
    private fun markRemoved(item: DanmakuItem) {
        onScreenCids.remove(item.cid)
    }

    // ================== 对外 API ==================

    fun loadDanmakuComments(comments: List<DanmakuComment>?) {
        prepared = false
        activeScroll.clear()
        activeTop.clear()
        activeBottom.clear()
        onScreenCids.clear()
        recentTextTimes.clear()
        scanIndex = 0
        pendingDanmaku.clear()

        if (comments.isNullOrEmpty()) {
            danmakuList = emptyList()
            Log.d(TAG, "loadDanmakuComments: 空")
            invalidate()
            return
        }

        val items = comments.mapNotNull { parseComment(it) }
        danmakuList = items.sortedBy { it.timeSec }
        prepared = true
        Log.d(TAG, "loadDanmakuComments: 解析到 ${danmakuList.size} 条（原始 ${comments.size} 条）")
        invalidate()
    }

    /**
     * 同步视频时间（毫秒）
     * - 正常播放时由 progressSyncRunnable 每秒调用，仅校准 videoTimeMs，不重置 wallClockBase
     * - 用户 seek（跳转）时也会调用，此时 reset=true 清空活跃弹幕并重置时间基准
     */
    fun syncTo(positionMs: Long, reset: Boolean = false) {
        if (reset) {
            // seek 跳转：重置时间基准，清空所有活跃弹幕，重新扫描
            videoTimeMs = positionMs
            wallClockBase = System.currentTimeMillis()
            activeScroll.clear()
            activeTop.clear()
            activeBottom.clear()
            onScreenCids.clear()
            recentTextTimes.clear()
            // 直接用二分查找定位 scanIndex，避免从 0 逐条扫描过期弹幕造成卡顿
            val windowStartSec = (positionMs - scrollDurationMs) / 1000.0f
            scanIndex = if (danmakuList.isNotEmpty()) binaryFindFirst(danmakuList, windowStartSec) else 0
            pendingDanmaku.clear()
            Log.d(TAG, "syncTo: seek to ${positionMs}ms, 清空活跃弹幕")
        } else {
            videoTimeMs = positionMs
            wallClockBase = System.currentTimeMillis()
        }
        invalidate()
    }

    fun setDanmakuEnabled(on: Boolean) {
        enabled = on
        Log.d(TAG, "setDanmakuEnabled=$on")
        if (on) invalidate()
    }

    fun setPaused(isPaused: Boolean) {
        if (isPaused) {
            if (paused) return
            paused = true
            pausedVideoTimeMs = getCurrentVideoMs()
            Log.d(TAG, "pause at ${pausedVideoTimeMs}ms")
        } else {
            if (!paused) return
            paused = false
            videoTimeMs = pausedVideoTimeMs
            wallClockBase = System.currentTimeMillis()
            Log.d(TAG, "resume from ${pausedVideoTimeMs}ms")
            invalidate()
        }
    }

    fun isDanmakuEnabled(): Boolean = enabled
    fun isPrepared(): Boolean = prepared

    fun release() {
        danmakuList = emptyList()
        activeScroll.clear()
        activeTop.clear()
        activeBottom.clear()
        onScreenCids.clear()
        recentTextTimes.clear()
        pendingDanmaku.clear()
    }

    // ================== 内部时间 ==================

    /** 获取当前视频时间（毫秒） */
    private fun getCurrentVideoMs(): Long {
        return if (paused) {
            pausedVideoTimeMs
        } else {
            videoTimeMs + (System.currentTimeMillis() - wallClockBase)
        }
    }

    // ================== 解析 ==================

    private fun parseComment(comment: DanmakuComment): DanmakuItem? {
        val p = comment.p
        val text = comment.m
        if (p.isBlank() || text.isBlank()) return null

        val fields = p.split(',')
        if (fields.size < 4) return null

        val timeSec = fields[0].toFloatOrNull() ?: return null
        val type = fields[1].toIntOrNull() ?: 1
        val size = fields[2].toFloatOrNull() ?: 25f
        val colorInt = fields[3].toLongOrNull() ?: 16777215L

        // 十进制 0xRRGGBB → 带 alpha = FF
        val color = 0xFF000000.toInt() or (colorInt.toInt() and 0xFFFFFF)

        // cid 去重键：API 返回 cid 则直接用，否则用文本+时间生成伪唯一键
        val cid = if (comment.cid > 0) comment.cid
            else (text.hashCode().toLong() and 0x7FFFFFFFL) * 1_000_000L + (timeSec.toLong() * 1000L)

        return DanmakuItem(cid, timeSec, type, size, color, text)
    }

    // ================== 绘制 ==================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "onSizeChanged: w=$w, h=$h (screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels})")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled || !prepared || danmakuList.isEmpty()) {
            postInvalidateDelayed(50L)
            return
        }

        val viewHeight = height.coerceAtLeast(1)
        val viewWidth = width.coerceAtLeast(1)
        // 根据屏幕宽度动态计算基准字体大小（分母 35 可调整，值越大字体越小）
        val baseTextSize = (viewWidth / 35f).coerceIn(18f, 50f)
        // 动态计算最大行数和行高
        val rowHeightPx = baseTextSize * 1.5f  // 行高为字体大小的1.5倍，留出间距
        val maxRows = (viewHeight / rowHeightPx).toInt().coerceAtLeast(2)
        val defaultTextSize = rowHeightPx * 0.7f

        // 帧间隔（真实时间）
        val nowWallMs = System.currentTimeMillis()
        val frameMs = (nowWallMs - lastDrawWallMs).coerceIn(0L, 100L)
        lastDrawWallMs = nowWallMs

        // 当前视频时间（自驱动，不依赖外部 syncTo）
        val currentVideoMs = getCurrentVideoMs()
        val nowSec = currentVideoMs / 1000.0f

        // 时钟跳变防御：正常播放每帧推进约 33ms，秒级校准的漂移远低于该阈值。
        // 若墙钟偏移帧间增量超过阈值，说明系统时间被前跳（NTP 校时/网络切换等），
        // 弹幕时钟已被污染——跳过本帧添加，避免按跳变后的时间烧穿弹幕列表，
        // 等待秒级校准重建基准后自动恢复
        if (!paused) {
            val wallDelta = nowWallMs - wallClockBase
            if (lastWallDeltaMs >= 0 && wallDelta - lastWallDeltaMs > MAX_CLOCK_JUMP_MS) {
                Log.w(TAG, "检测到时钟跳变 (wallDelta ${lastWallDeltaMs}ms -> ${wallDelta}ms)，跳过本帧弹幕添加")
                postInvalidateDelayed(FRAME_INTERVAL_MS)
                return
            }
            lastWallDeltaMs = wallDelta
        }

        // 滚动速度：每毫秒移动 viewWidth / scrollDurationMs 像素
        val scrollSpeedPxPerMs = viewWidth.toFloat() / scrollDurationMs

        // ========== 1. 移除已过期弹幕 ==========
        // 滚动：完全移出屏幕左侧（x + textWidth < 0）时移除
        val itrScroll = activeScroll.iterator()
        while (itrScroll.hasNext()) {
            val ad = itrScroll.next()
            if (ad.x + ad.textWidth < 0) {
                itrScroll.remove()
                markRemoved(ad.item)
            }
        }
        // 固定弹幕：超时后移除，同时清理 onScreenCids
        val expireBeforeMs = currentVideoMs - fixedDurationMs
        activeTop.removeAll { ad ->
            val expired = ad.startAtMs < expireBeforeMs
            if (expired) markRemoved(ad.item)
            expired
        }
        activeBottom.removeAll { ad ->
            val expired = ad.startAtMs < expireBeforeMs
            if (expired) markRemoved(ad.item)
            expired
        }

        // ========== 1.5 重试之前因行满未放置的弹幕 ==========
        val pendingIt = pendingDanmaku.iterator()
        while (pendingIt.hasNext()) {
            val (item, tw) = pendingIt.next()
            val elapsedMs = currentVideoMs - (item.timeSec * 1000f).toLong()
            // 超过一屏滚动时间仍未放出行，丢弃
            if (elapsedMs > scrollDurationMs) {
                pendingIt.remove()
                markRemoved(item)
                continue
            }
            val row = findScrollRow(tw, viewWidth, maxRows)
            if (row >= 0) {
                val initialX = viewWidth.toFloat() + tw
                // 限制入场延迟：延迟过久的弹幕从右边缘开始，不从中间出现
                val effectiveMs = elapsedMs.coerceAtMost(MAX_ENTRY_DELAY_MS)
                val x = initialX - scrollSpeedPxPerMs * effectiveMs
                if (x + tw > 0) {
                    activeScroll.add(ActiveDanmaku(item, x, row, (item.timeSec * 1000f).toLong(), tw))
                    pendingIt.remove()
                } else {
                    // 已经完全移出屏幕，丢弃
                    pendingIt.remove()
                    markRemoved(item)
                }
            }
        }

        // ========== 1.8 清理过期的文本去重窗口 ==========
        if (recentTextTimes.isNotEmpty()) {
            val expireTextBefore = currentVideoMs - DEDUP_WINDOW_MS
            val textItr = recentTextTimes.entries.iterator()
            while (textItr.hasNext()) {
                if (textItr.next().value < expireTextBefore) textItr.remove()
            }
        }

        // ========== 2. 将新弹幕加入活跃列表 ==========
        // 每帧按窗口起点二分重定位 scanIndex（O(log n)）：游标完全由当前时间决定。
        // 旧实现仅在 scanIndex < danmakuList.size 时才允许回退，游标一旦因时间跳变等
        // 异常被烧穿到列表末尾就永久失效，弹幕再也无法恢复；无条件重定位可自愈。
        val windowStartSec = (currentVideoMs - scrollDurationMs) / 1000.0f
        if (danmakuList.isNotEmpty()) {
            val targetIndex = binaryFindFirst(danmakuList, windowStartSec)
            if (targetIndex < scanIndex) {
                scanIndex = targetIndex
            }
        }

        var addedThisFrame = 0
        while (scanIndex < danmakuList.size && addedThisFrame < 30) {
            val item = danmakuList[scanIndex]
            if (item.timeSec > nowSec) break

            // 实例去重：同一cid已在屏/排队中，跳过
            if (onScreenCids.contains(item.cid)) {
                scanIndex++
                continue
            }
            // 文本时间窗口去重：同文本在窗口内只放一条
            val lastShownMs = recentTextTimes[item.text]
            if (lastShownMs != null && currentVideoMs - lastShownMs < DEDUP_WINDOW_MS) {
                scanIndex++
                continue
            }

            // 预计算文本宽度
            paint.textSize = baseTextSize
            val tw = paint.measureText(item.text)
            paint.textSize = defaultTextSize

            when (item.type) {
                in listOf(1, 2, 3, 6) -> {
                    val row = findScrollRow(tw, viewWidth, maxRows)
                    if (row >= 0) {
                        // 计算弹幕已经"飞行"了多久（当前视频时间 - 弹幕出现时间）
                        val elapsedMs = currentVideoMs - (item.timeSec * 1000f).toLong()
                        // 限制入场延迟：延迟过久的弹幕从右边缘开始，不从中间出现
                        val effectiveMs = elapsedMs.coerceAtMost(MAX_ENTRY_DELAY_MS)
                        // 初始 x = viewWidth + tw，每毫秒移动 scrollSpeedPxPerMs
                        val initialX = viewWidth.toFloat() + tw
                        val x = initialX - scrollSpeedPxPerMs * effectiveMs
                        if (x + tw > 0) {  // 还没完全移出屏幕才添加
                            activeScroll.add(ActiveDanmaku(item, x, row, (item.timeSec * 1000f).toLong(), tw))
                            markAdded(item, currentVideoMs)
                            addedThisFrame++
                        }
                    } else {
                        // 行满，加入重试队列（超过容量丢弃最旧的）
                        if (pendingDanmaku.size >= MAX_PENDING_DANMAKU) {
                            val evicted = pendingDanmaku.removeAt(0)
                            markRemoved(evicted.first)
                        }
                        pendingDanmaku.add(item to tw)
                        markAdded(item, currentVideoMs)
                    }
                }
                in listOf(5, 7) -> {
                    val row = findFreeRow(activeTop, maxRows)
                    if (row >= 0) {
                        activeTop.add(ActiveDanmaku(item, 0f, row, (item.timeSec * 1000f).toLong(), tw))
                        markAdded(item, currentVideoMs)
                        addedThisFrame++
                    }
                }
                in listOf(4, 8) -> {
                    val row = findFreeRow(activeBottom, maxRows)
                    if (row >= 0) {
                        activeBottom.add(ActiveDanmaku(item, 0f, row, (item.timeSec * 1000f).toLong(), tw))
                        markAdded(item, currentVideoMs)
                        addedThisFrame++
                    }
                }
                else -> {
                    val row = findScrollRow(tw, viewWidth, maxRows)
                    if (row >= 0) {
                        val elapsedMs = currentVideoMs - (item.timeSec * 1000f).toLong()
                        val effectiveMs = elapsedMs.coerceAtMost(MAX_ENTRY_DELAY_MS)
                        val initialX = viewWidth.toFloat()
                        val x = initialX - scrollSpeedPxPerMs * effectiveMs
                        if (x + tw > 0) {
                            activeScroll.add(ActiveDanmaku(item, x, row, (item.timeSec * 1000f).toLong(), tw))
                            markAdded(item, currentVideoMs)
                            addedThisFrame++
                        }
                    } else {
                        if (pendingDanmaku.size >= MAX_PENDING_DANMAKU) {
                            val evicted = pendingDanmaku.removeAt(0)
                            markRemoved(evicted.first)
                        }
                        pendingDanmaku.add(item to tw)
                        markAdded(item, currentVideoMs)
                    }
                }
            }
            scanIndex++
        }

        // ========== 3. 更新滚动弹幕位置（暂停时跳过） ==========
        if (!paused) {
            val deltaX = scrollSpeedPxPerMs * frameMs
            for (ad in activeScroll) {
                ad.x -= deltaX
            }
        }

        // ========== 4. 绘制 ==========
        paint.setShadowLayer(2f, 1f, 1f, Color.argb(180, 0, 0, 0))
        paint.textSize = baseTextSize  // 统一设置字体大小

        // 滚动弹幕：从右向左
        for (ad in activeScroll) {
            paint.color = ad.item.color
            val y = ad.row * rowHeightPx + rowHeightPx * 0.8f
            canvas.drawText(ad.item.text, ad.x, y, paint)
        }

        // 顶部弹幕：居中，从上往下排
        for (ad in activeTop) {
            paint.color = ad.item.color
            val x = (viewWidth - ad.textWidth) / 2.0f
            val y = ad.row * rowHeightPx + rowHeightPx * 0.8f
            canvas.drawText(ad.item.text, x, y, paint)
        }

        // 底部弹幕：居中，从底部往上排
        val bottomBaseY = viewHeight.toFloat()
        for (ad in activeBottom) {
            paint.color = ad.item.color
            val x = (viewWidth - ad.textWidth) / 2.0f
            val y = bottomBaseY - (ad.row + 1) * rowHeightPx + rowHeightPx * 0.2f
            if (y > 0) canvas.drawText(ad.item.text, x, y, paint)
        }

        // 请求下一帧
        postInvalidateDelayed(FRAME_INTERVAL_MS)
    }

    // ================== 辅助方法 ==================

    /**
     * 查找可放置的滚动行：
     * 遍历所有行，找到一行使得：
     *   1. 该行弹幕数 < MAX_DANMAKU_PER_ROW
     *   2. 该行最右侧尾部（含屏幕外右侧的新弹幕）+ 新弹幕宽度 <= 屏幕宽度
     * 即新弹幕进入屏幕时，不会与该行任何弹幕重叠。
     *
     * 注意：屏幕外右侧（x >= screenWidth）的弹幕也要计入占位，
     * 否则同一帧内连续添加的多条弹幕会被塞到同一行导致重叠。
     */
    private fun findScrollRow(textWidth: Float, screenWidth: Int, maxRows: Int): Int {
        val sw = screenWidth.toFloat()
        val rowTailX = FloatArray(maxRows) { -1f }
        val rowCount = IntArray(maxRows) { 0 }
        for (ad in activeScroll) {
            if (ad.row in 0 until maxRows) {
                // 所有活跃弹幕都计入尾部（含屏幕外右侧的新弹幕）
                val tail = ad.x + ad.textWidth
                if (tail > rowTailX[ad.row]) rowTailX[ad.row] = tail
                rowCount[ad.row]++
            }
        }
        var bestRow = -1
        var bestTail = Float.MAX_VALUE
        for (row in 0 until maxRows) {
            if (rowCount[row] < MAX_DANMAKU_PER_ROW && rowTailX[row] + textWidth <= sw) {
                if (rowTailX[row] < bestTail) {
                    bestTail = rowTailX[row]
                    bestRow = row
                }
            }
        }
        return bestRow
    }

    /** 查找空行（该行没有被固定弹幕占用） */
    private fun findFreeRow(list: List<ActiveDanmaku>, maxRows: Int): Int {
        for (row in 0 until maxRows) {
            if (list.none { it.row == row }) return row
        }
        return -1  // 满了不添加
    }

    /** 二分查找：第一个 timeSec >= target 的索引 */
    private fun binaryFindFirst(list: List<DanmakuItem>, targetSec: Float): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (list[mid].timeSec < targetSec) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val TAG = "DanmakuView"
        private const val FRAME_INTERVAL_MS: Long = 33L
        private const val MAX_DANMAKU_PER_ROW = 8
        private const val MAX_PENDING_DANMAKU = 200
        /** 同文本去重窗口（毫秒）：相同文本在此窗口内只显示一条 */
        private const val DEDUP_WINDOW_MS: Long = 3_000L
        /** 弹幕允许的最大入场延迟（毫秒）：超过此延迟的弹幕从右边缘开始，避免从中间出现 */
        private const val MAX_ENTRY_DELAY_MS: Long = 500L
        /** 墙钟偏移帧间增量阈值（毫秒）：超过则判定系统时间前跳，跳过本帧弹幕添加 */
        private const val MAX_CLOCK_JUMP_MS: Long = 3_000L
    }
}
