package com.glass.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Glass pop-up menu drawn over the home screen (replaces plain Android dialogs). */
class GlassMenu(
    private val act: Activity,
    private val root: FrameLayout,
    private val onOpenChange: (Boolean) -> Unit,
) {
    class Item(
        val text: String,
        val icon: Int? = null,
        val drawable: Drawable? = null,
        var checked: Boolean = false,
        /** row with a check mark that switches on/off and keeps the menu open */
        val toggle: Boolean = false,
        val value: String? = null,
        val flag: Boolean = false,
        val tint: Int? = null,
        val action: (Item) -> Unit = {},
    )

    private var overlay: FrameLayout? = null
    val isOpen get() = overlay != null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun message(title: String, icon: Int?, text: String) =
        show(title, icon, listOf(Item("OK", R.drawable.lc_check)), text)

    fun show(title: String, icon: Int?, items: List<Item>, subtitle: String? = null) {
        removeOverlay()
        val ov = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isClickable = true
        }
        val panel = GlassFrame(act).apply { radiusDp = 30f; tint = 0.06f; darken = 0.5f }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
        }

        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), dp(12))
        }
        if (icon != null) {
            header.addView(ImageView(act).apply { setImageResource(icon) },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        }
        header.addView(TextView(act).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Fonts.medium(act)
        })
        col.addView(header)

        if (subtitle != null) {
            col.addView(TextView(act).apply {
                text = subtitle
                setTextColor(Color.argb(215, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Fonts.regular(act)
                setLineSpacing(0f, 1.25f)
                setTextIsSelectable(false)
                setPadding(dp(10), 0, dp(10), dp(14))
            })
        }

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val rows = items.map { makeRow(it) }
        rows.forEach { list.addView(it) }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }
        val maxRows = 8
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (items.size > maxRows) dp(52) * maxRows else ViewGroup.LayoutParams.WRAP_CONTENT))

        panel.addView(col)
        ov.addView(panel, FrameLayout.LayoutParams(dp(470), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = ov
        onOpenChange(true)
        rows.firstOrNull()?.post { rows.first().requestFocus() }
    }

    private fun makeRow(item: Item): View {
        val d = act.resources.displayMetrics.density
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(50)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        }
        row.setOnFocusChangeListener { v, has -> v.background = if (has) GlassDrawable(d, 12, true) else null }

        val ic: Drawable? = item.drawable ?: item.icon?.let { act.getDrawable(it) }
        if (ic != null) {
            val iv = ImageView(act).apply {
                setImageDrawable(ic)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (item.tint != null) setColorFilter(item.tint)
            }
            val lp = if (item.flag) LinearLayout.LayoutParams(dp(33), dp(22)) else LinearLayout.LayoutParams(dp(24), dp(24))
            lp.marginEnd = dp(16)
            row.addView(iv, lp)
        }
        row.addView(TextView(act).apply {
            text = item.text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.regular(act)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (item.value != null) {
            row.addView(TextView(act).apply {
                text = item.value
                setTextColor(Color.argb(160, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Fonts.regular(act)
                maxLines = 1
                setPadding(dp(10), 0, 0, 0)
            })
        }

        var check: ImageView? = null
        if (item.toggle || item.checked) {
            check = ImageView(act).apply {
                setImageResource(R.drawable.lc_check)
                setColorFilter(Color.rgb(52, 212, 122))
                visibility = if (item.checked) View.VISIBLE else View.INVISIBLE
            }
            row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) })
        }

        row.setOnClickListener {
            if (item.toggle) {
                item.checked = !item.checked
                check?.visibility = if (item.checked) View.VISIBLE else View.INVISIBLE
                item.action(item)
            } else {
                close()
                item.action(item)
            }
        }
        return row
    }

    private fun removeOverlay() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    fun close() {
        if (overlay == null) return
        removeOverlay()
        onOpenChange(false)
    }
}
