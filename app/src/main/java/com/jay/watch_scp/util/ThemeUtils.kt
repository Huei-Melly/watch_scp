package com.jay.watch_scp.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * 主题颜色工具类
 *
 * 颜色完全跟随当前 Material 主题（深色 / 浅色），不再单独覆盖背景色或字体颜色。
 */
object ThemeUtils {

    /** 获取当前主题下适合正文显示的文字颜色 */
    fun getEffectiveFontColor(context: Context): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true) && tv.resourceId != 0) {
            try {
                return ContextCompat.getColor(context, tv.resourceId)
            } catch (_: Exception) {
                // 解析失败时回退到深/浅主题默认色
            }
        }
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }
}
