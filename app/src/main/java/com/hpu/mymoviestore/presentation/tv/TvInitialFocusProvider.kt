package com.hpu.mymoviestore.presentation.tv

import android.view.View

/**
 * 电视端：内容页向顶部导航栏暴露「下键」时应移交焦点的首个控件。
 *
 * 背景：顶部导航栏「只留当前选中页签可聚焦」，系统默认的方向键焦点查找在导航栏
 * 找不到下游候选，会导致按「下键」时焦点丢失。由内容页实现本接口给出首焦点控件，
 * 交给 MainActivity.dispatchKeyEvent 显式移交。
 *
 * 注意：必须 **实时** 返回当前有效视图，不要在 onViewCreated 里把结果缓存起来 ——
 * ViewPager2 会复用 Fragment，onViewCreated 只在首次创建时调用一次，缓存会在切页后过期。
 */
interface TvInitialFocusProvider {
    /** 返回当前页「下键」应聚焦的控件；无合适控件时返回 null（交回系统默认处理） */
    fun tvInitialFocusView(): View?
}
