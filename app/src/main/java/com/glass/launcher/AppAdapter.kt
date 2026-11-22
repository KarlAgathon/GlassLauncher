package com.glass.launcher

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val items: List<AppEntry>,
    private val prefs: Prefs,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    /** Position of the tile currently being moved, or -1. */
    var movingPos = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: GlassFrame = v.findViewById(R.id.tile)
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val reflect: ImageView = v.findViewById(R.id.reflect)
        val badge: ImageView = v.findViewById(R.id.badge)
    }

    /** mirror pictures, made once per logo and size */
    private val mirrors = HashMap<String, android.graphics.Bitmap>()
    fun clearMirrors() = mirrors.clear()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
        v.setOnFocusChangeListener { _, has -> style(h, has) }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val ctx = h.itemView.context
        val d = ctx.resources.displayMetrics.density
        // v10: every app is a smoky glass card – logo in the upper part, name below, same size for all
        val cw = Taupe.cardW(ctx); val ch = Taupe.cardH(ctx); val gap = Taupe.gap(ctx)
        h.itemView.layoutParams = (h.itemView.layoutParams ?: RecyclerView.LayoutParams(cw + gap, ch + gap)).also { it.width = cw + gap; it.height = ch + gap }
        h.itemView.background = android.graphics.drawable.InsetDrawable(Taupe.card(ctx), gap / 2)
        h.itemView.setPadding(gap / 2, gap / 2 + (ch * 0.13f).toInt(), gap / 2, gap / 2)
        if (android.os.Build.VERSION.SDK_INT >= 26) h.itemView.defaultFocusHighlightEnabled = false

        val iw = (cw * 0.70f).toInt(); val ih = (ch * 0.46f).toInt()
        h.tile.layoutParams = h.tile.layoutParams.also { it.width = iw; it.height = ih }
        h.tile.plain = true; h.tile.tint = 0f; h.tile.backdrop = false; h.tile.liquid = false; h.tile.rimMode = 0
        h.tile.focusedLook = false; h.tile.accent = false
        h.tile.refreshShape()
        h.tile.clipChildren = false; h.tile.clipToOutline = false

        h.icon.setImageBitmap(e.icon)
        val ilp = h.icon.layoutParams as FrameLayout.LayoutParams
        ilp.width = ViewGroup.LayoutParams.MATCH_PARENT; ilp.height = ViewGroup.LayoutParams.MATCH_PARENT
        h.icon.layoutParams = ilp
        val pad = if (e.isAdd || e.isFiles) (ih * 0.12f).toInt() else 0
        h.icon.setPadding(pad, pad, pad, pad)
        h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        h.reflect.visibility = View.GONE
        h.icon.alpha = if (e.missing) 0.45f else 1f
        h.badge.visibility = if (e.missing) View.VISIBLE else View.GONE

        // this icon's own size and position (long press › Change size / Change position)
        val own = prefs.iconScale(e.pkg) / 100f
        val (ox, oy) = prefs.iconOffset(e.pkg)
        h.icon.scaleX = own; h.icon.scaleY = own
        h.icon.translationX = ox * d; h.icon.translationY = oy * d

        h.label.text = e.label
        h.label.visibility = View.VISIBLE
        h.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ctx.resources.displayMetrics.widthPixels * 0.0185f)
        h.label.typeface = Fonts.regular(ctx)
        (h.label.layoutParams as LinearLayout.LayoutParams).also { it.width = (cw * 0.88f).toInt(); it.topMargin = (ch * 0.07f).toInt() }
        style(h, h.itemView.hasFocus())
    }

    private fun style(h: VH, focused: Boolean) {
        val moving = h.bindingAdapterPosition == movingPos && movingPos >= 0
        // v10: the card keeps its size and shape; focus is the thin champagne line of the card itself
        h.itemView.isSelected = moving
        h.tile.scaleX = 1f; h.tile.scaleY = 1f; h.tile.translationY = 0f
        h.tile.translationZ = 0f; h.itemView.translationZ = 0f
        h.label.alpha = 1f; h.label.translationY = 0f
        h.label.setTextColor(if (focused || moving) Taupe.TEXT else Color.argb(232, 246, 241, 234))
    }
}
