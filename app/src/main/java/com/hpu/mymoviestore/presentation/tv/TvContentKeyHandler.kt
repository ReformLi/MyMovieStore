package com.hpu.mymoviestore.presentation.tv

import android.view.View

/**
 * 内容页对「方向键」的兜底接管（电视端）。
 *
 * 为什么需要它：`RecyclerView` 的 `focusSearch()` 把候选搜索范围限制在**自身子树**内，
 * 所以「网格最后一行按下键」时它既找不到候选、也无法把焦点送到网格**外面**的兄弟视图
 * （例如结果网格下方的分页栏），表现就是「按了没反应」。这类「翻出容器」的移动必须由页面自己接管。
 *
 * 约定：实现方必须在按键时**实时**判断焦点位置，禁止缓存（见 TvInitialFocusProvider 的同类教训）。
 */
interface TvContentKeyHandler {

    /**
     * @param direction [View.FOCUS_UP] / [View.FOCUS_DOWN] / [View.FOCUS_LEFT] / [View.FOCUS_RIGHT]
     * @return true 表示已消费该按键（不要再交给系统）
     */
    fun onContentDirectionKey(direction: Int): Boolean
}
