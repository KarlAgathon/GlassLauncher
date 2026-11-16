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
        /** long press (or mouse right click) on the row */
        val longAction: ((Item) -> Unit)? = null,
        /** small grey group title (POČETNI EKRAN, SISTEM …) – not a button */
        val header: Boolean = false,
        val action: (Item) -> Unit = {},
    )

    companion object {
        /** a small grey group title between the rows */
        fun header(text: String) = Item(text, header = true)
    }

    private var overlay: FrameLayout? = null
    val isOpen get() = overlay != null

    /** the menu on screen, and the menus it was opened from (Back goes one step back) */
    private class Snap(val title: String, val icon: Int?, val items: List<Item>, val subtitle: String?)
    private var current: Snap? = null
    private val stack = ArrayList<Pair<Snap, Int>>()

    /** Back: to the menu this one was opened from (on the same row), or close */
    fun back() {
        if (stack.isEmpty()) { close(); return }
        val (s, i) = stack.removeAt(stack.size - 1)
        build(s.title, s.icon, s.items, s.subtitle, i)
    }

    /** runs a row's action; when it opens another menu, remember this one for Back */
    private fun runFrom(index: Int, action: () -> Unit) {
        val parent = current
        pendingParent = parent?.title
        closeKeep()
        action()
        val now = current
        if (overlay != null && parent != null && now !== parent && now?.title != parent.title) stack.add(parent to index)
        else if (overlay == null) stack.clear()
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun message(title: String, icon: Int?, text: String) =
        show(title, icon, listOf(Item("OK", R.drawable.lc_check)), text)

    fun show(title: String, icon: Int?, items: List<Item>, subtitle: String? = null) = build(title, icon, items, subtitle, 0)

    /** long text over the whole screen (What's new): Up / Down scroll, Back goes back */
    fun page(title: String, icon: Int?, text: String) {
        current = null
        removeOverlay()
        val ov = FrameLayout(act).apply { isClickable = true }
        val panel = GlassFrame(act).apply { radiusDp = 0f; tint = 0.03f; solid = 0.92f; darken = 0.2f; backdropAlpha = 0.5f }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(80), dp(44), dp(80), dp(30)) }
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(18)) }
        if (icon != null) header.addView(ImageView(act).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
        header.addView(TextView(act).apply { this.text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = Fonts.medium(act) })
        col.addView(header)
        val body = TextView(act).apply {
            this.text = text; setTextColor(Color.argb(230, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Fonts.regular(act); setLineSpacing(0f, 1.4f)
        }
        val scroll = ScrollView(act).apply {
            isFocusable = true; isVerticalScrollBarEnabled = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            addView(body)
            setOnKeyListener { _, k, e ->
                if (e.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { smoothScrollBy(0, dp(160)); true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { smoothScrollBy(0, -dp(160)); true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(TextView(act).apply {
            this.text = "▲ ▼   ·   Back"; setTextColor(Color.argb(140, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, 0)
        })
        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ov.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = ov
        onOpenChange(true)
        scroll.post { scroll.requestFocus() }
    }

    private fun build(title: String, icon: Int?, items: List<Item>, subtitle: String?, focusIndex: Int) {
        current = Snap(title, icon, items, subtitle)
        removeOverlay()
        val ov = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isClickable = true
        }
        // nearly solid dark glass: nothing of the home screen (buttons, clock, logos) shows through the menu
        val panel = GlassFrame(act).apply { radiusDp = 0f; tint = 0.04f; solid = 0.88f; darken = 0.25f; backdropAlpha = 0.6f }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(20))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Fonts.medium(act)
        })
        col.addView(header)
        // where you are: Settings › Status bar
        if (stack.isNotEmpty() || pendingParent != null) {
            val path = (stack.map { it.first.title } + listOfNotNull(pendingParent)).distinct().filter { it != title }.takeLast(2)
            if (path.isNotEmpty()) col.addView(TextView(act).apply {
                text = (path + title).joinToString(" › ")
                setTextColor(Color.argb(150, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Fonts.regular(act)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
                setPadding(dp(10), 0, dp(10), dp(12))
            })
        }

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
        val all = items.mapIndexed { i, it -> makeRow(it, i) }
        all.forEach { list.addView(it) }
        val rows = all.filterIndexed { i, _ -> !items[i].header }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // glass sheet on the right side; wider when the text is bigger (Settings › Text size)
        val width = (dp(500) * FontScale.factor(act)).toInt().coerceAtMost((act.resources.displayMetrics.widthPixels * 0.6f).toInt())
        ov.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        // a thin light edge where the sheet starts
        ov.addView(View(act).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
            FrameLayout.LayoutParams(dp(1).coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { marginEnd = width })
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = ov
        onOpenChange(true)
        pendingParent = null
        val focusRow = all.getOrNull(focusIndex)?.takeIf { !items[focusIndex].header } ?: rows.firstOrNull()
        focusRow?.post { focusRow.requestFocus() }
    }

    /** title of the menu a new one is opened from (for the "Settings › …" line) */
    private var pendingParent: String? = null

    private fun makeRow(item: Item, index: Int): View {
        val d = act.resources.displayMetrics.density
        if (item.header) return TextView(act).apply {
            text = item.text.uppercase()
            setTextColor(Color.argb(150, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Fonts.medium(act)
            letterSpacing = 0.08f
            setPadding(dp(14), dp(16), dp(14), dp(4))
            isFocusable = false
        }
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
        var sw: GreenSwitch? = null
        if (item.toggle) {
            // the same green slider as in PRETORIAN: green = on, grey = off, no "on/off" text
            sw = GreenSwitch(act).apply { on = item.checked }
            row.addView(sw, LinearLayout.LayoutParams(dp(46), dp(26)).apply { marginStart = dp(12) })
        } else if (item.checked) {
            check = ImageView(act).apply {
                setImageResource(R.drawable.lc_check)
                setColorFilter(Color.rgb(52, 212, 122))
                visibility = if (item.checked) View.VISIBLE else View.INVISIBLE
            }
            row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) })
        }

        item.longAction?.let { la ->
            row.setOnLongClickListener { runFrom(index) { la(item) }; true }
            row.setOnContextClickListener { runFrom(index) { la(item) }; true }
        }
        row.setOnClickListener {
            if (item.toggle) {
                item.checked = !item.checked
                sw?.animateTo(item.checked)
                check?.visibility = if (item.checked) View.VISIBLE else View.INVISIBLE
                item.action(item)
            } else {
                runFrom(index) { item.action(item) }
            }
        }
        return row
    }

    private fun removeOverlay() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    fun close() {
        stack.clear()
        closeKeep()
    }

    private fun closeKeep() {
        if (overlay == null) return
        removeOverlay()
        current = null
        onOpenChange(false)
    }
}

/** Pill slider: green with the knob right = on, grey with the knob left = off. */
class GreenSwitch(ctx: android.content.Context) : View(ctx) {
    var on = false
        set(v) { field = v; pos = if (v) 1f else 0f; invalidate() }
    private var pos = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    fun animateTo(v: Boolean) {
        val from = pos
        android.animation.ValueAnimator.ofFloat(from, if (v) 1f else 0f).apply {
            duration = if (Backdrop.lite) 0L else 160L
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        on = v; pos = from
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        val grey = Color.argb(90, 255, 255, 255)
        val green = Color.rgb(52, 199, 89)
        paint.color = android.animation.ArgbEvaluator().evaluate(pos, grey, green) as Int
        c.drawRoundRect(0f, 0f, w, h, r, r, paint)
        val pad = h * 0.12f
        val kr = r - pad
        paint.color = Color.WHITE
        c.drawCircle(r + (w - 2 * r) * pos, r, kr, paint)
    }
}
