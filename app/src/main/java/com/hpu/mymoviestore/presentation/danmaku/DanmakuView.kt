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
 * 支持：
 *  - 解析 JSON 格式弹幕（DanmakuComment 列表，p 字段格式同 B 站）
 *  - 三种布局：滚动（从右到左）、顶部固定、底部固定
 *  - 颜色、字体大小（从 p 字段解析）
 *  - 与播放器时间同步（seekTo 同步 positionMs）
 *  - 通过 enable/disable 开关显示/隐藏
 *  - 弹幕区域：占满容器高度（建议容器高度为屏幕 1/4）
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
        val startAtMs: Long,    // 开始显示时间（毫秒）
        val textWidth: Float    // 预计算的文本宽度
    )

    // ================== 内部状态 ==================

    private var danmakuList: List<DanmakuItem> = emptyList()  // 按 timeSec 升序
    private var currentTimeMs: Long = 0L
    private var enabled: Boolean = true
    private var prepared: Boolean = false

    // 三类活跃弹幕
    private val activeScroll: ArrayList<ActiveDanmaku> = ArrayList()
    private val activeTop: ArrayList<ActiveDanmaku> = ArrayList()
    private val activeBottom: ArrayList<ActiveDanmaku> = ArrayList()

    // 去重：已添加过的弹幕文本哈希（避免同一时刻重复添加）
    private val addedIds: HashSet<Int> = HashSet()
    private var lastAddedIdsCleanMs: Long = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 行高（动态计算）
    private var rowHeightPx: Float = 40f
    private var lastFrameMs: Long = System.currentTimeMillis()

    // 滚动：10 秒内从屏幕右侧滚到左侧
    private val scrollDurationMs: Long = 10_000L
    // 固定弹幕：显示 4 秒后消失
    private val fixedDurationMs: Long = 4_000L

    // 每帧最多添加的新弹幕数（防止一次性涌入太多）
    private var addedThisFrame: Int = 0

    // ================== 对外 API ==================

    fun loadDanmakuComments(comments: List<DanmakuComment>?) {
        prepared = false
        activeScroll.clear()
        activeTop.clear()
        activeBottom.clear()
        addedIds.clear()
        addedThisFrame = 0

        if (comments.isNullOrEmpty()) {
            danmakuList = emptyList()
            Log.d(TAG, "loadDanmakuComments: 空")
            invalidate()
            return
        }

        val items = comments.mapNotNull { parseComment(it) }
        danmakuList = items.sortedBy { it.timeSec }
        prepared = true
        lastFrameMs = System.currentTimeMillis()
        Log.d(TAG, "loadDanmakuComments: 解析到 ${danmakuList.size} 条（原始 ${comments.size} 条）")
        invalidate()
    }

    fun seekTo(positionMs: Long) {
        currentTimeMs = positionMs
        lastFrameMs = System.currentTimeMillis()
        // seek 后清空活跃弹幕，重新按时间窗口加入
        activeScroll.clear()
        activeTop.clear()
        activeBottom.clear()
        addedIds.clear()
        addedThisFrame = 0
        invalidate()
    }

    fun setDanmakuEnabled(on: Boolean) {
        enabled = on
        Log.d(TAG, "setDanmakuEnabled=$on")
        if (on) invalidate()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 临时调试：绘制半透明背景，查看实际绘制区域
//        canvas.drawColor(Color.argb(80, 255, 0, 0))
//        val paint = Paint().apply { color = Color.BLUE; style = Paint.Style.FILL }
//        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (!enabled || !prepared || danmakuList.isEmpty()) {
            postInvalidateDelayed(50L)
            return
        }
        if (activeScroll.isNotEmpty()) {
            val first = activeScroll.first()
            Log.d("DanmakuDebug", "x=${first.x}, textWidth=${first.textWidth}, sum=${first.x + first.textWidth}")
        }

        val viewHeight = height.coerceAtLeast(1)
        val viewWidth = width.coerceAtLeast(1)

        // 动态计算最大行数和行高
        val maxRows = max(4, (viewHeight / 40f).toInt())
        rowHeightPx = viewHeight.toFloat() / maxRows
        val defaultTextSize = rowHeightPx * 0.7f

        // 帧间隔
        val nowMs = System.currentTimeMillis()
        val frameMs = (nowMs - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = nowMs
        val frameSec = frameMs / 1000.0f

        // 滚动速度：每秒移动 screenWidth / scrollDuration 秒
        val scrollSpeedPxPerMs = viewWidth.toFloat() / scrollDurationMs
        val now = currentTimeMs / 1000.0f

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
        val expireBeforeMs = currentTimeMs - fixedDurationMs
        activeTop.removeAll { it.startAtMs < expireBeforeMs }
        activeBottom.removeAll { it.startAtMs < expireBeforeMs }

        // ========== 2. 将新弹幕加入活跃列表 ==========
        if (addedThisFrame > 30) addedThisFrame = 0

        // 每 5 秒清空去重集合
        if (currentTimeMs - lastAddedIdsCleanMs > 5000L) {
            addedIds.clear()
            lastAddedIdsCleanMs = currentTimeMs
        }

        // 时间窗口：[currentTimeMs - scrollDuration, currentTimeMs] 内的弹幕
        val windowStartSec = (currentTimeMs - scrollDurationMs) / 1000.0f
        var idx = binaryFindFirst(danmakuList, windowStartSec)

        while (idx < danmakuList.size && addedThisFrame < 30) {
            val item = danmakuList[idx]
            if (item.timeSec > now) break

            val itemId = item.text.hashCode()
            if (addedIds.contains(itemId)) {
                idx++; continue
            }

            // 预计算文本宽度
            paint.textSize = item.textSizePx.coerceIn(14f, 60f)
            val tw = paint.measureText(item.text)
            paint.textSize = defaultTextSize
//            Log.d("DanmakuDebug", "viewWidth=$viewWidth, textWidth=$tw, initialX=${viewWidth + tw}")
            when (item.type) {
                in listOf(1, 2, 3, 6) -> {
                    // 滚动弹幕：从右侧进入，从左到右移动
                    val row = findScrollRow(tw, viewWidth, maxRows)
                    if (row >= 0) {
                        activeScroll.add(ActiveDanmaku(item, viewWidth.toFloat() + tw, row, currentTimeMs, tw))
                        addedIds.add(itemId)
                        addedThisFrame++
                    }
                }
                in listOf(5, 7) -> {
                    // 顶部固定弹幕：居中
                    val row = findFreeRow(activeTop, maxRows)
                    if (row >= 0) {
                        activeTop.add(ActiveDanmaku(item, 0f, row, currentTimeMs, tw))
                        addedIds.add(itemId)
                        addedThisFrame++
                    }
                }
                in listOf(4, 8) -> {
                    // 底部固定弹幕：居中
                    val row = findFreeRow(activeBottom, maxRows)
                    if (row >= 0) {
                        activeBottom.add(ActiveDanmaku(item, 0f, row, currentTimeMs, tw))
                        addedIds.add(itemId)
                        addedThisFrame++
                    }
                }
                else -> {
                    // 其他类型：当作滚动处理
                    val row = findScrollRow(tw, viewWidth, maxRows)
                    if (row >= 0) {
                        activeScroll.add(ActiveDanmaku(item, viewWidth.toFloat(), row, currentTimeMs, tw))
                        addedIds.add(itemId)
                        addedThisFrame++
                    }
                }
            }
            idx++
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
