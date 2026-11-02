package com.jay.watch_scp.util

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.jay.watch_scp.WatchScpApp
import com.jay.watch_scp.R
import kotlinx.coroutines.flow.first
import java.util.ArrayDeque

/**
 * 字体大小工具类，将保存的字体大小设置应用到视图树
 *
 * 性能优化策略：
 * 1. 用 TAG 标记已处理的 View 根节点，避免重复遍历同一棵视图树
 * 2. 使用迭代遍历替代递归，避免深层视图树栈溢出
 */
object FontSizeUtils {

    private var cachedFontSize: Int = 14
    private const val TAG_FONT_SIZE = "font_size_applied"

    /**
     * 获取当前缓存字体大小
     */
    fun getCachedFontSize(): Int = cachedFontSize

    /**
     * 更新缓存字体大小（在设置页面修改时同步调用）
     */
    fun updateCachedSize(size: Int) {
        cachedFontSize = size
    }

    /**
     * 从 DataStore 读取当前字体大小并更新缓存（挂起函数，不阻塞主线程）
     */
    suspend fun loadFontSize(): Int {
        try {
            cachedFontSize = WatchScpApp.instance.preferencesManager.fontSize.first()
        } catch (e: Exception) {
            // 失败时保留缓存值
        }
        return cachedFontSize
    }

    /**
     * 应用到视图树中的所有 TextView
     * 使用 TAG 标记根节点，已处理的视图树自动跳过
     * 使用迭代遍历替代递归，避免深层视图树栈溢出
     */
    fun applyToViewTree(view: View) {
        // 如果根节点已被当前字体标记，跳过整棵子树
        if (view.getTag(R.id.tag_font_size) == cachedFontSize) return
        applyToViewTreeIterative(view, cachedFontSize.toFloat())
        view.setTag(R.id.tag_font_size, cachedFontSize)
    }

    /**
     * 强制应用到视图树（忽略 TAG 缓存，用于设置页面实时预览）
     */
    fun applyToViewTreeForce(view: View) {
        applyToViewTreeIterative(view, cachedFontSize.toFloat())
        view.setTag(R.id.tag_font_size, cachedFontSize)
    }

    /**
     * 应用到单个 TextView
     */
    fun applyToTextView(textView: TextView) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedFontSize.toFloat())
    }

    /**
     * 使用显式栈进行迭代遍历，避免递归栈溢出
     */
    private fun applyToViewTreeIterative(root: View, size: Float) {
        val stack = ArrayDeque<View>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val view = stack.pop()
            if (view is TextView) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            } else if (view is ViewGroup) {
                // 逆序入栈以保持与递归相同的遍历顺序
                val group = view
                val childCount = group.childCount
                for (i in childCount - 1 downTo 0) {
                    stack.push(group.getChildAt(i))
                }
            }
        }
    }
}