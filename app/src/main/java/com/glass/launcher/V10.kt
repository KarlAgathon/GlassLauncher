package com.glass.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * LEAD v10 – "Modern Taupe & Charcoal".
 * Colours, the smoky-glass card, the top navigation, full-screen category pages, the Undo note and small dialogs.
 * One menu at a time: a page replaces Home completely; Back goes exactly one level back.
 */
object Taupe {
    val TEXT = Color.rgb(246, 241, 234)          // warm white
    val MUTED = Color.rgb(200, 190, 180)         // secondary text
    val CHAMPAGNE = Color.rgb(224, 201, 164)     // focus + accents
    val SUB = Color.rgb(226, 206, 178)           // champagne subtitle
    val ICON = Color.rgb(232, 222, 210)

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun glass(ctx: Context, r: Float, focused: Boolean): Drawable {
        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (focused) intArrayOf(Color.argb(92, 178, 166, 154), Color.argb(70, 132, 121, 112))
            else intArrayOf(Color.argb(70, 160, 149, 139), Color.argb(50, 118, 108, 100))).apply {
            cornerRadius = r
            setStroke(if (focused) dp(ctx, 2f).toInt() else dp(ctx, 1f).toInt(),
                if (focused) Color.argb(235, 224, 201, 164) else Color.argb(46, 255, 244, 232))
        }
        if (!focused) return fill
        // soft inner glow just inside the champagne line
        val glow = GradientDrawable().apply {
            cornerRadius = r - dp(ctx, 2f)
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 4f).toInt(), Color.argb(38, 224, 201, 164))
        }
        return LayerDrawable(arrayOf(fill, glow)).apply { val i = dp(ctx, 2f).toInt(); setLayerInset(1, i, i, i, i) }
    }

    /** the smoky glass card: thin warm line, champagne line when focused (no zoom, the shape never changes) */
    fun card(ctx: Context, radiusDp: Float = 18f): Drawable {
        val r = dp(ctx, radiusDp)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glass(ctx, r, true))
            addState(intArrayOf(android.R.attr.state_selected), glass(ctx, r, true))
            addState(intArrayOf(), glass(ctx, r, false))
        }
    }

    /** rows in lists (files, recycle bin): darker, champagne line when focused */
    fun row(ctx: Context): Drawable {
        val r = dp(ctx, 14f)
        fun one(f: Boolean) = GradientDrawable().apply {
            cornerRadius = r
            setColor(if (f) Color.argb(118, 52, 47, 44) else Color.argb(84, 40, 36, 34))
            setStroke(dp(ctx, if (f) 2f else 1f).toInt(), if (f) Color.argb(230, 224, 201, 164) else Color.argb(26, 255, 244, 232))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), one(true))
            addState(intArrayOf(), one(false))
        }
    }

    /** small pill buttons (action bar, dialogs) */
    fun pill(ctx: Context, strong: Boolean = false): Drawable {
        val r = dp(ctx, 12f)
        fun one(f: Boolean) = GradientDrawable().apply {
            cornerRadius = r
            setColor(if (f) Color.argb(70, 224, 201, 164) else if (strong) Color.argb(60, 90, 82, 76) else Color.TRANSPARENT)
            setStroke(dp(ctx, if (f) 2f else 1f).toInt(), if (f) Color.argb(240, 224, 201, 164) else if (strong) Color.argb(40, 255, 244, 232) else Color.TRANSPARENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), one(true))
            addState(intArrayOf(), one(false))
        }
    }

    /** dark smoky panel (action bar, dialogs, undo note) */
    fun panel(ctx: Context, radiusDp: Float = 18f) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp)
        setColor(Color.argb(205, 46, 42, 39))
        setStroke(dp(ctx, 1f).toInt(), Color.argb(48, 255, 244, 232))
    }

    /** [size] in thousandths of the screen width – the same proportions on every TV (from the approved design) */
    fun text(ctx: Context, s: String, size: Float, color: Int = TEXT, medium: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ctx.resources.displayMetrics.widthPixels * size / 1000f)
        typeface = if (medium) Fonts.medium(ctx) else Fonts.regular(ctx)
        setShadowLayer(dp(ctx, 4f), 0f, dp(ctx, 1f), Color.argb(110, 0, 0, 0))
        includeFontPadding = false
    }

    fun icon(ctx: Context, res: Int, sizePx: Int, color: Int = ICON) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** card sizes measured from the screen (the approved design: four cards across, 3:4 high) */
    fun cardW(ctx: Context) = (ctx.resources.displayMetrics.widthPixels * 0.192f).toInt()
    fun cardH(ctx: Context) = (cardW(ctx) * 0.757f).toInt()
    fun gap(ctx: Context) = (ctx.resources.displayMetrics.widthPixels * 0.0155f).toInt()
}

/** One tile of a category page. */
data class V10Tile(val title: String, val icon: Int, val sub: String? = null, val action: () -> Unit)

/** The top navigation: Apps · Personalize · System · Diagnostics · About */
class V10Nav(private val ctx: Context, private val onPick: (Int) -> Unit) {
    val view = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
    private val items = ArrayList<View>()
    private val lines = ArrayList<View>()
    private val badges = ArrayList<TextView>()
    var selected = 0; private set

    init {
        val names = listOf(
            T.s("Apps", "Aplikacije", "Apps", "Приложения") to R.drawable.lc_layout_grid,
            T.s("Personalize", "Prilagodi", "Anpassen", "Персонализация") to R.drawable.lc_palette,
            T.s("System", "Sistem", "System", "Система") to R.drawable.lc_settings,
            T.s("Diagnostics", "Dijagnostika", "Diagnose", "Диагностика") to R.drawable.lc_activity,
            T.s("About", "O LEAD-u", "Über", "О LEAD") to R.drawable.lc_info,
        )
        val d = Taupe.dp(ctx, 1f)
        names.forEachIndexed { i, (name, ic) ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                background = Taupe.pill(ctx)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                defaultFocusHighlightEnabledCompat()
                setOnClickListener { onPick(i) }
            }
            row.addView(Taupe.icon(ctx, ic, (ctx.resources.displayMetrics.widthPixels * 0.024f).toInt(), Taupe.MUTED))
            val t = Taupe.text(ctx, name, 15.5f, Taupe.MUTED)
            row.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * d).toInt() })
            val badge = Taupe.text(ctx, "", 9f, Color.rgb(40, 34, 30), medium = true).apply {
                background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(Taupe.CHAMPAGNE) }
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
                visibility = View.GONE
            }
            row.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * d).toInt() })
            row.setOnFocusChangeListener { _, has ->
                t.setTextColor(if (has || i == selected) Taupe.TEXT else Taupe.MUTED)
                (row.getChildAt(0) as ImageView).setColorFilter(if (has || i == selected) Taupe.TEXT else Taupe.MUTED)
            }
            col.addView(row)
            val line = View(ctx).apply { setBackgroundColor(Color.argb(220, 236, 226, 214)) }
            col.addView(line, LinearLayout.LayoutParams((ctx.resources.displayMetrics.widthPixels * 0.085f).toInt(), (3 * d).toInt()).apply { topMargin = (6 * d).toInt() })
            view.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (22 * d).toInt(); marginEnd = (22 * d).toInt() })
            items.add(row); lines.add(line); badges.add(badge)
        }
        select(0)
    }

    private fun View.defaultFocusHighlightEnabledCompat() { if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false }

    fun select(i: Int) {
        selected = i
        lines.forEachIndexed { k, l -> l.visibility = if (k == i) View.VISIBLE else View.INVISIBLE }
        items.forEachIndexed { k, v ->
            val c = if (k == i || v.hasFocus()) Taupe.TEXT else Taupe.MUTED
            ((v as LinearLayout).getChildAt(1) as TextView).setTextColor(c)
            (v.getChildAt(0) as ImageView).setColorFilter(c)
        }
    }

    fun badge(i: Int, n: Int) { badges[i].text = n.toString(); badges[i].visibility = if (n > 0) View.VISIBLE else View.GONE }

    fun focus(i: Int = selected) = items[i].requestFocus()
    fun has(v: View?): Boolean { var p: View? = v; while (p != null) { if (p === view) return true; p = p.parent as? View }; return false }
    fun item(i: Int): View = items[i]
}

/** A full-screen category page: big title, champagne subtitle, centred card rows, "← Back" at the bottom. */
object V10Page {
    fun build(ctx: Context, title: String, sub: String, tiles: List<V10Tile>): View {
        val d = Taupe.dp(ctx, 1f)
        val sw = ctx.resources.displayMetrics.widthPixels
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding((sw * 0.099f).toInt(), (118 * d).toInt(), (sw * 0.09f).toInt(), (20 * d).toInt())
        }
        col.addView(Taupe.text(ctx, title, 43f, Taupe.TEXT).apply { typeface = Fonts.regular(ctx) })
        col.addView(Taupe.text(ctx, sub, 17.5f, Taupe.SUB), LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * d).toInt() })
        val cw = Taupe.cardW(ctx); val ch = Taupe.cardH(ctx); val gap = Taupe.gap(ctx)
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        // up to four per row; later rows are centred under the first (like the approved design)
        tiles.chunked(4).forEachIndexed { ri, chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
            for (t in chunk) row.addView(tile(ctx, t, cw, ch), LinearLayout.LayoutParams(cw, ch).apply { marginEnd = gap / 2; marginStart = gap / 2 })
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (ri == 0) 0 else gap * 2 })
        }
        val scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFillViewport = false }
        scroll.addView(grid)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (34 * d).toInt() })
        col.addView(Taupe.text(ctx, "←  " + T.s("Back", "Nazad", "Zurück", "Назад"), 13f, Taupe.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (10 * d).toInt() })
        return col
    }

    fun tile(ctx: Context, t: V10Tile, cw: Int, ch: Int): View {
        val d = Taupe.dp(ctx, 1f)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            background = Taupe.card(ctx)
            addView(Taupe.icon(ctx, t.icon, (ch * 0.30f).toInt()))
            addView(Taupe.text(ctx, t.title, 18.5f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (ch * 0.10f).toInt() })
            if (t.sub != null) addView(Taupe.text(ctx, t.sub, 12f, Taupe.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() })
            setOnClickListener { t.action() }
        }
    }
}

/** "Changed · Undo" note at the bottom centre for 10 seconds, with a thin line counting down. */
object V10Undo {
    private var current: View? = null

    fun show(root: FrameLayout, message: String, undo: () -> Unit) {
        val ctx = root.context
        val d = Taupe.dp(ctx, 1f)
        current?.let { root.removeView(it) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Taupe.panel(ctx, 16f)
            setPadding((22 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Taupe.text(ctx, message, 13f))
        val btn = Taupe.text(ctx, T.s("Undo", "Poništi", "Rückgängig", "Отменить"), 13f, Taupe.CHAMPAGNE, medium = true).apply {
            background = Taupe.pill(ctx); isFocusable = true; isClickable = true
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        }
        row.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (18 * d).toInt() })
        box.addView(row)
        val bar = View(ctx).apply { setBackgroundColor(Taupe.CHAMPAGNE); pivotX = 0f }
        box.addView(bar, LinearLayout.LayoutParams(-1, (2 * d).toInt()).apply { topMargin = (8 * d).toInt() })
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (36 * d).toInt() }
        root.addView(box, lp)
        current = box
        val gone = Runnable { if (current === box) { root.removeView(box); current = null } }
        btn.setOnClickListener { root.removeCallbacks(gone); root.removeView(box); current = null; undo() }
        bar.animate().scaleX(0f).setDuration(10_000L).setInterpolator(android.view.animation.LinearInterpolator()).start()
        root.postDelayed(gone, 10_000L)
    }

    /** the Undo button, so the remote can reach it (Down from anywhere goes there while it shows) */
    fun button(): View? = (current as? LinearLayout)?.getChildAt(0)?.let { (it as LinearLayout).getChildAt(1) }
}

/** Small centred dialog: title, explanation, buttons in one row (last one gets focus). Back = cancel. */
object V10Dialog {
    fun show(root: FrameLayout, title: String, message: String?, buttons: List<Pair<String, () -> Unit>>, focusIndex: Int = buttons.size - 1) {
        val ctx = root.context
        val d = Taupe.dp(ctx, 1f)
        val shade = FrameLayout(ctx).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)); isClickable = true; tag = TAG }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Taupe.panel(ctx, 20f)
            setPadding((34 * d).toInt(), (28 * d).toInt(), (34 * d).toInt(), (24 * d).toInt())
        }
        box.addView(Taupe.text(ctx, title, 20f).apply { gravity = Gravity.CENTER })
        if (message != null) box.addView(Taupe.text(ctx, message, 14.5f, Taupe.SUB).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * d).toInt() })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun close() { root.removeView(shade) }
        val views = buttons.mapIndexed { i, (label, act) ->
            Taupe.text(ctx, label, 15f, if (i == buttons.size - 1) Taupe.CHAMPAGNE else Taupe.TEXT).apply {
                background = Taupe.pill(ctx, strong = true); isFocusable = true; isClickable = true; gravity = Gravity.CENTER
                setPadding((26 * d).toInt(), (12 * d).toInt(), (26 * d).toInt(), (12 * d).toInt())
                setOnClickListener { close(); act() }
                row.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
            }
        }
        box.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (22 * d).toInt() })
        shade.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { width = minOf((720 * d).toInt(), (root.width * 0.8f).toInt().coerceAtLeast((400 * d).toInt())) })
        shade.isFocusable = false
        shade.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { close(); true } else code == KeyEvent.KEYCODE_BACK }
        // Back on any button closes the dialog too
        views.forEach { v -> v.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) close(); true } else false } }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        views.getOrNull(focusIndex)?.requestFocus()
    }

    private const val TAG = "v10dialog"
    fun isOpen(root: ViewGroup) = (0 until root.childCount).any { root.getChildAt(it).tag == TAG }
}

/** Compact vertical action list in a small panel (contextual actions, destination picker). Back = close. */
object V10List {
    private const val TAG = "v10list"
    data class Entry(val text: String, val icon: Int? = null, val value: String? = null, val action: () -> Unit)

    fun isOpen(root: ViewGroup) = (0 until root.childCount).any { root.getChildAt(it).tag == TAG }
    fun close(root: ViewGroup) { (0 until root.childCount).map { root.getChildAt(it) }.filter { it.tag == TAG }.forEach { root.removeView(it) } }

    fun show(root: FrameLayout, title: String, entries: List<Entry>, onClose: () -> Unit = {}) {
        val ctx = root.context
        val d = Taupe.dp(ctx, 1f)
        val sw = ctx.resources.displayMetrics.widthPixels
        close(root)
        val shade = FrameLayout(ctx).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)); isClickable = true; tag = TAG }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Taupe.panel(ctx, 20f)
            setPadding((18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
        }
        box.addView(Taupe.text(ctx, title, 16f, Taupe.SUB), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (10 * d).toInt(); marginStart = (10 * d).toInt() })
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun done() { root.removeView(shade); onClose() }
        val views = entries.map { e ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Taupe.pill(ctx); isFocusable = true; isClickable = true
                if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                setPadding((14 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                if (e.icon != null) addView(Taupe.icon(ctx, e.icon, (sw * 0.017f).toInt()), LinearLayout.LayoutParams((sw * 0.017f).toInt(), (sw * 0.017f).toInt()).apply { marginEnd = (14 * d).toInt() })
                addView(Taupe.text(ctx, e.text, 14.5f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                if (e.value != null) addView(Taupe.text(ctx, e.value, 12f, Taupe.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = (14 * d).toInt() })
                setOnClickListener { root.removeView(shade); e.action() }
                setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) done(); true } else false }
                inner.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (2 * d).toInt() })
            }
        }
        val scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(inner) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt()
        shade.addView(box, FrameLayout.LayoutParams((sw * 0.36f).toInt(), -2, Gravity.CENTER))
        box.post { if (box.height > maxH) box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).also { it.height = maxH } }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        views.firstOrNull()?.requestFocus()
    }
}

/** One line of text input in the same style (rename, server address …). */
object V10Input {
    fun show(root: FrameLayout, title: String, value: String, password: Boolean = false, done: (String) -> Unit) {
        val ctx = root.context
        val d = Taupe.dp(ctx, 1f)
        val sw = ctx.resources.displayMetrics.widthPixels
        val shade = FrameLayout(ctx).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)); isClickable = true; tag = "v10dialog" }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Taupe.panel(ctx, 20f)
            setPadding((28 * d).toInt(), (24 * d).toInt(), (28 * d).toInt(), (20 * d).toInt())
        }
        box.addView(Taupe.text(ctx, title, 18f))
        val edit = android.widget.EditText(ctx).apply {
            setText(value); setSelection(value.length); setSingleLine()
            setTextColor(Taupe.TEXT); setHintTextColor(Taupe.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sw * 0.016f)
            background = Taupe.row(ctx)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * d).toInt() })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun close() = root.removeView(shade)
        fun btn(t: String, strong: Boolean, act: () -> Unit) = Taupe.text(ctx, t, 14.5f, if (strong) Taupe.CHAMPAGNE else Taupe.TEXT).apply {
            background = Taupe.pill(ctx, true); isFocusable = true; isClickable = true
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
            setOnClickListener { act() }
            setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) close(); true } else false }
            row.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (10 * d).toInt() })
        }
        btn(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена"), false) { close() }
        val ok = btn("OK", true) { close(); done(edit.text.toString()) }
        edit.setOnEditorActionListener { _, _, _ -> ok.requestFocus(); true }
        edit.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) close(); true } else false }
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * d).toInt() })
        shade.addView(box, FrameLayout.LayoutParams((sw * 0.42f).toInt(), -2, Gravity.CENTER))
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        edit.requestFocus()
    }
}
