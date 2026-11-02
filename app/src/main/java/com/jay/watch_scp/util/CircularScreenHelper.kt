package com.jay.watch_scp.util

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.View
import androidx.wear.widget.BoxInsetLayout

object CircularScreenHelper {
    fun isRoundScreen(context: Context): Boolean {
        return context.resources.configuration.isScreenRound
    }

    fun getScreenWidthPx(context: Context): Int {
        return context.resources.displayMetrics.widthPixels
    }

    fun getScreenHeightPx(context: Context): Int {
        return context.resources.displayMetrics.heightPixels
    }

    fun applyCircularPadding(view: View) {
        if (view is BoxInsetLayout) {
            view.setPadding(0, 0, 0, 0)
        }
    }
}