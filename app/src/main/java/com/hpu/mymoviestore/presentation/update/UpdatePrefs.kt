package com.hpu.mymoviestore.presentation.update

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 更新提示弹窗的频率控制。
 *
 * 弹窗选项与存储策略：
 * - 「下次再说」：不写入任何状态，下次启动照常检查并弹窗
 * - 「今天不再提醒」：记录当天日期（yyyy-MM-dd），同一天内后续启动不再弹窗
 */
class UpdatePrefs(context: Context) {

    companion object {
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_SKIP_DATE = "skip_date"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 今天是否应该弹窗（未跳过或跳过日期不是今天 → 弹） */
    fun shouldShowToday(): Boolean {
        val skipDate = prefs.getString(KEY_SKIP_DATE, null) ?: return true
        return skipDate != todayString()
    }

    /** 记录「今天不再提醒」 */
    fun markSkipToday() {
        prefs.edit().putString(KEY_SKIP_DATE, todayString()).apply()
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
