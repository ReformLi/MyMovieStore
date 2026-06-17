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

    // 去重：已添加过的弹幕文本哈希（避免同一时刻重复添加）
    private val addedIds: HashSet<Int> = HashSet()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 行高（动态计算）
    private var rowHeightPx: Float = 40f
    private var lastDrawWallMs: Long = System.currentTimeMillis()

    // 滚动：10 秒内从屏幕右侧滚到左侧
    private val scrollDurationMs: Long = 10_000L
    // 固定弹幕：显示 4 秒后消失
    private val fixedDurationMs: Long = 4_000L

    // 已扫描到的弹幕索引（避免每帧都从头遍历）
    private var scanIndex: Int = 0

    // ================== 对外 API ==================

    fun loadDanmakuComments(comments: List<DanmakuComment>?) {
        prepared = false
        activeScroll.clear()
        activeTop.clear()
        activeBottom.clear()
        addedIds.clear()
        scanIndex = 0

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
     * - 正常播放时由 progressSyncRunnable 每秒调用，校准时间基准
     * - 用户 seek（跳转）时也会调用，此时 reset=true 清空活跃弹幕
     */
    fun syncTo(positionMs: Long, reset: Boolean = false) {
        videoTimeMs = positionMs
        wallClockBase = System.currentTimeMillis()

        if (reset) {
            // seek 跳转：清空所有活跃弹幕，重新扫描
            activeScroll.clear()
            activeTop.clear()
            activeBottom.clear()
            addedIds.clear()
            scanIndex = 0
            Log.d(TAG, "syncTo: seek to ${positionMs}ms, 清空活跃弹幕")
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
            paused = true
            // 冻结当前视频时间
            pausedVideoTimeMs = getCurrentVideoMs()
            Log.d(TAG, "pause at ${pausedVideoTimeMs}ms")
        } else {
            paused = false
            // 恢复：以冻结的视频时间为基准，重新设置 wallClockBase
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
        addedIds.clear()
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

        return DanmakuItem(timeSec, type, size, color, text)
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

        // 动态计算最大行数和行高
        val maxRows = max(4, (viewHeight / 40f).toInt())
        rowHeightPx = viewHeight.toFloat() / maxRows
        val defaultTextSize = rowHeightPx * 0.7f

        // 帧间隔（真实时间）
        val nowWallMs = System.currentTimeMillis()
        val frameMs = (nowWallMs - lastDrawWallMs).coerceIn(0L, 100L)
        lastDrawWallMs = nowWallMs

        // 当前视频时间（自驱动，不依赖外部 syncTo）
        val currentVideoMs = getCurrentVideoMs()
        val nowSec = currentVideoMs / 1000.0f

        // 滚动速度：每毫秒移动 viewWidth / scrollDurationMs 像素
        val scrollSpeedPxPerMs = viewWidth.toFloat() / scrollDurationMs

        // ========== 1. 移除已过期弹幕 ==========
        // 滚动：完全移出屏幕左侧（x + textWidth < 0）时移除
        val itrScroll = activeScroll.iterator()
        while (itrScroll.hasNext()) {
            val ad = itrScroll.next()
            if (ad.x + ad.textWidth < 0) {
                itrScroll.remove()
                addedIds.remove(ad.item.text.hashCode())
            }
        }
        // 固定弹幕：超时后移除
        val expireBeforeMs = currentVideoMs - fixedDurationMs
        activeTop.removeAll { it.startAtMs < expireBeforeMs }
        activeBottom.removeAll { it.startAtMs < expireBeforeMs }

        // ========== 2. 将新弹幕加入活跃列表 ==========
        // 回退 scanIndex（如果 seek 导致时间回退）
        val windowStartSec = (currentVideoMs - scrollDurationMs) / 1000.0f
        if (scanIndex > 0 && scanIndex < danmakuList.size && danmakuList[scanIndex].timeSec > nowSec) {
            scanIndex = binaryFindFirst(danmakuList, windowStartSec)
        }

        var addedThisFrame = 0
        while (scanIndex < danmakuList.size && addedThisFrame < 30) {
            val item = danmakuList[scanIndex]
            if (item.timeSec > nowSec) break

            val itemId = item.text.hashCode()
            if (addedIds.contains(itemId)) {
                scanIndex++
                continue
            }

            // 预计算文本宽度
            paint.textSize = item.textSizePx.coerceIn(14f, 60f)
            val tw = paint.measureText(item.text)
            paint.textSize = defaultTextSize

            when (item.type) {
                in listOf(1, 2, 3, 6) -> {
                    val row = findScrollRow(tw, viewWidth, maxRows)
                    if (row >= 0) {
                        // 计算弹幕已经"飞行"了多久（当前视频时间 - 弹幕出现时间）
                        val elapsedMs = currentVideoMs - (item.timeSec * 1000f).toLong()
                        // 初始 x = viewWidth + tw，每毫秒移动 scrollSpeedPxPerMs
                        val initialX = viewWidth.toFloat() + tw
                        val x = initialX - scrollSpeedPxPerMs * elapsedMs
                        if (x + tw > 0) {  // 还没完全移出屏幕才添加
                            activeScroll.add(ActiveDanmaku(item, x, row, (item.timeSec * 1000f).toLong(), tw))
                            addedIds.add(itemId)
                            addedThisFrame++
                        }
                    }
                }
                in listOf(5, 7) -> {
                    val row = findFreeRow(activeTop, maxRows)
                    if (row >= 0) {
                        activeTop.add(ActiveDanmaku(item, 0f, row, (item.timeSec * 1000f).toLong(), tw))
                        addedIds.add(itemId)
                        addedThisFrame++
                    }
                }
                in listOf(4, 8) -> {
                    val row = findFreeRow(activeBottom, maxRows)
                    if (row >= 0) {
                        activeBottom.add(ActiveDanmaku(item, 0f, row, (item.timeSec * 1000f).toLong(), tw))
                        addedIds.add(itemId)
                        addedThisFrame++
                    }
                }
                else -> {
                    val row = findScrollRow(tw, viewWidth, maxRows)
                    if (row >= 0) {
                        val elapsedMs = currentVideoMs - (item.timeSec * 1000f).toLong()
                        val initialX = viewWidth.toFloat()
                        val x = initialX - scrollSpeedPxPerMs * elapsedMs
                        if (x + tw > 0) {
                            activeScroll.add(ActiveDanmaku(item, x, row, (item.timeSec * 1000f).toLong(), tw))
                            addedIds.add(itemId)
                            addedThisFrame++
                        }
                    }
                }
            }
            scanIndex++
        }

        // ========== 3. 更新滚动弹幕位置 ==========
        val deltaX = scrollSpeedPxPerMs * frameMs
        for (ad in activeScroll) {
            ad.x -= deltaX
        }

        // ========== 4. 绘制 ==========
        paint.setShadowLayer(2f, 1f, 1f, Color.argb(180, 0, 0, 0))

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
            val displayRow = activeBottom.size - 1 - ad.row
            val y = bottomBaseY - displayRow * rowHeightPx - rowHeightPx * 0.2f
            if (y > 0) canvas.drawText(ad.item.text, x, y, paint)
        }

        // 请求下一帧
        postInvalidateDelayed(FRAME_INTERVAL_MS)
    }

    // ================== 辅助方法 ==================

    /**
     * 查找可放置的滚动行：遍历所有行，找到该行末尾弹幕已移出 70% 屏幕的行
     */
    private fun findScrollRow(textWidth: Float, screenWidth: Int, maxRows: Int): Int {
        for (row in 0 until maxRows) {
            val lastInRow = activeScroll.filter { it.row == row }.maxByOrNull { it.x }
            if (lastInRow == null || lastInRow.x + lastInRow.textWidth < screenWidth * 0.65f) {
                return row
            }
        }
        return 0  // 满了强制放第一行
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
        private const val FRAME_INTERVAL_MS: Long = 33L  // ~30fps
    }
}
