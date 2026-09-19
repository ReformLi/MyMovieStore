package com.hpu.mymoviestore.presentation.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Android TV（电视/盒子）运行时适配工具 —— 纯 UI 层，不触碰任何业务逻辑。
 *
 * 设计原则：
 * - **双形态共存**：同一 APK 在手机与电视上都能跑；手机端行为完全不变（本类所有方法
 *   在非电视设备上均为 no-op / 原样返回）。
 * - **10-foot UI 靠密度实现**：电视上把 densityDpi 放大 [TV_DENSITY_SCALE] 倍，
 *   dp 画布随之变小，等于把布局内所有 dp/sp 尺寸（字体、按钮、间距、卡片）等比放大，
 *   无需逐个改写布局文件，也就不会改坏手机端布局。
 * - 电视实测：1080p 电视 densityDpi≈320 → dp 画布 960dp；放大 1.45 倍后 ≈662dp，
 *   正文 14sp 渲染约 41px，符合 3 米观看距离的可读性要求。
 */
object TvUiSupport {

    /** 电视端 UI 整体放大倍数（10-foot UI） */
    private const val TV_DENSITY_SCALE = 1.45f

    /**
     * 当前设备是否为电视形态。
     * 判定依据 UiModeManager 的 UI_MODE_TYPE_TELEVISION（Android TV / 大多数盒子标准上报）。
     *
     * 注意：本方法可能在 `attachBaseContext` 阶段被调用（此时 Context 尚未完全就绪），
     * 因此全程兜底，任何异常都视为「非电视」——保证手机端绝不会因适配逻辑而崩溃。
     */
    fun isTelevision(context: Context): Boolean = runCatching {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }.getOrDefault(false)

    /**
     * 电视端放大 UI 密度，手机端原样返回。
     * 用法：在各 Activity 的 `attachBaseContext(newBase)` 中调用。
     *
     * 同时按新密度重算 screenWidthDp / screenHeightDp / smallestScreenWidthDp：
     * Configuration 里的 dp 尺寸是根据 densityDpi 推导出来的，只改 densityDpi 会留下
     * 「物理 1080p、密度 464、却仍声称宽 960dp」这类自相矛盾的配置，
     * 任何读取 `configuration.screenWidthDp` 的代码（含资源限定符匹配）都会拿到错值。
     */
    fun wrapContext(base: Context): Context {
        if (!isTelevision(base)) return base
        return runCatching {
            val dm = base.resources.displayMetrics
            val config = Configuration(base.resources.configuration)
            config.densityDpi = (config.densityDpi * TV_DENSITY_SCALE).toInt()
            val newDensity = config.densityDpi / 160f
            if (newDensity > 0f) {
                config.screenWidthDp = (dm.widthPixels / newDensity).toInt()
                config.screenHeightDp = (dm.heightPixels / newDensity).toInt()
                config.smallestScreenWidthDp =
                    minOf(config.screenWidthDp, config.screenHeightDp)
            }
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }

    /**
     * 电视端是否启用「强制横屏」相关行为（本 App 已在清单统一锁定 landscape，
     * 此处仅用于需要在代码里区分电视表现的场景，如沉浸式全屏、焦点初始定位）。
     */
    fun isTvUiEnabled(context: Context): Boolean = isTelevision(context)
}
