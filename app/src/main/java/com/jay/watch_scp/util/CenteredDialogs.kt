package com.jay.watch_scp.util

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.jay.watch_scp.R

/**
 * 手表圆形屏幕统一使用的居中对话框。
 * 系统 AlertDialog 在小屏上边距过大且浅色主题下文字与半透明背景对比度不足，
 * 这里统一改为全屏透明底 + 居中面板，文字固定白色，选项列表可滚动。
 */
object CenteredDialogs {

    private const val TEXT_COLOR = Color.WHITE
    private val HINT_COLOR = 0x99FFFFFF.toInt()
    private val DIVIDER_COLOR = 0x33FFFFFF

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 面板背景：半透明圆角卡片，保证文字可读同时能透出底层内容 */
    private fun panelBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(0xA6101010.toInt())
            cornerRadius = dp(context, 18).toFloat()
            setStroke(dp(context, 1), 0x33FFFFFF)
        }

    /**
     * 统一把窗口配置成真正透明：窗口背景透明、不调暗底层内容。
     * 必须在 show() 之前调用，否则会出现「先透明、后变纯黑」的闪烁。
     */
    private fun configureTransparentWindow(dialog: Dialog) {
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.decorView.setBackgroundColor(Color.TRANSPARENT)
            // 彻底关闭窗口遮罩（部分 ROM 只认 attributes 里的 dimAmount）
            val attrs = win.attributes
            attrs.dimAmount = 0f
            attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            win.attributes = attrs
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            win.setGravity(Gravity.CENTER)
        }
    }

    /** show() 之后系统仍可能补一层遮罩，延迟再清一次确保底层保持原亮度 */
    private fun applyTransparentAfterShow(dialog: Dialog) {
        configureTransparentWindow(dialog)
        dialog.window?.decorView?.post { configureTransparentWindow(dialog) }
    }

    /** 居中选项列表（可滚动），点击后自动关闭 */
    fun showOptions(
        context: Context,
        title: String,
        items: Array<String>,
        onClick: (Int) -> Unit
    ) {
        val dialog = Dialog(context, R.style.Theme_WatchScp_TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val screenHeight = context.resources.displayMetrics.heightPixels
        val root = FrameLayout(context).apply {
            // 整屏背景保持透明，只显示中间的面板卡片
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 点击面板外的空白区域关闭
            setOnClickListener { dialog.dismiss() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = panelBackground(context)
            isClickable = true
            setOnClickListener { /* 消费面板内点击，避免误关闭 */ }
            // 高度按内容自适应，避免整块黑色面板盖满屏幕
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                val margin = dp(context, 12)
                leftMargin = margin
                rightMargin = margin
            }
        }

        panel.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 8))
        })

        panel.addView(View(context).apply {
            setBackgroundColor(DIVIDER_COLOR)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
            ).apply {
                leftMargin = dp(context, 24)
                rightMargin = dp(context, 24)
            }
        })

        panel.addView(ListView(context).apply {
            val listAdapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_list_item_1, items
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(TEXT_COLOR)
                    view.textSize = 14f
                    view.gravity = Gravity.CENTER
                    view.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
                    return view
                }
            }
            adapter = listAdapter
            val itemHeight = dp(context, 40)
            val maxListHeight = (screenHeight * 0.62f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(items.size * itemHeight, maxListHeight)
            )
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            setOnItemClickListener { _, _, which, _ ->
                dialog.dismiss()
                onClick(which)
            }
        })

        root.addView(panel)
        dialog.setContentView(root)
        configureTransparentWindow(dialog)
        dialog.show()
        // show() 后系统可能重新加上遮罩，再清一次确保底层完全透明
        applyTransparentAfterShow(dialog)
    }

    /** 居中文本输入对话框 */
    fun showInput(
        context: Context,
        title: String,
        initial: String?,
        hint: String?,
        positiveText: String,
        onSubmit: (String) -> Unit
    ) {
        val dialog = Dialog(context, R.style.Theme_WatchScp_TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(context).apply {
            // 整屏背景保持透明，只显示中间的面板卡片
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dialog.dismiss() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(context)
            isClickable = true
            setOnClickListener { /* 消费面板内点击，避免误关闭 */ }
            val margin = dp(context, 12)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = margin
                rightMargin = margin
            }
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 12))
        }

        panel.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            setSingleLine()
            setPadding(0, 0, 0, dp(context, 10))
        })

        val input = EditText(context).apply {
            setTextColor(TEXT_COLOR)
            setHintTextColor(HINT_COLOR)
            textSize = 14f
            this.hint = hint ?: ""
            setSingleLine()
            if (!initial.isNullOrEmpty()) {
                setText(initial)
                setSelection(initial.length)
            }
        }
        panel.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 12), 0, 0)
        }
        fun makeButton(label: String, onTap: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonRow.addView(makeButton(context.getString(android.R.string.cancel)) { dialog.dismiss() })
        buttonRow.addView(makeButton(positiveText) {
            val value = input.text.toString()
            dialog.dismiss()
            onSubmit(value)
        })
        panel.addView(buttonRow)

        root.addView(panel)
        dialog.setContentView(root)
        configureTransparentWindow(dialog)
        dialog.show()
        applyTransparentAfterShow(dialog)
        input.requestFocus()
    }

    /** 居中确认对话框 */
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .setNegativeButton(cancelText, null)
            .create()
        dialog.show()
        dialog.window?.let { win ->
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.CENTER)
        }
    }
}
