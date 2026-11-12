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
    }

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
        val (tw, th) = prefs.tileDp()
        val wPx = (tw * d).toInt()
        val hPx = (th * d).toInt()
        val wide = prefs.tileShape == 0

        // tile size and look
        val lp = h.tile.layoutParams
        lp.width = wPx
        lp.height = hPx
        h.tile.layoutParams = lp
        h.tile.radiusPx = hPx * prefs.tileCorner / 100f
        h.tile.tint = prefs.tileAlpha / 100f * 0.45f
        h.tile.backdrop = prefs.tileAlpha > 0
        h.tile.rimMode = prefs.tileBorder
        h.tile.plain = prefs.tileAlpha == 0 && prefs.tileBorder == 0
        h.tile.refreshShape()

        // gap + room for the focus zoom
        val gapPx = (prefs.tileGap * d).toInt()
        val grow = (hPx * prefs.focusScale / 100f / 2f).toInt()
        h.itemView.setPadding(gapPx / 2, grow + (4 * d).toInt(), gapPx / 2, grow)

        // picture
        h.icon.setImageBitmap(e.icon)
        val ilp = h.icon.layoutParams as FrameLayout.LayoutParams
        val stretch = prefs.logoFit == 1
        if (wide) {
            ilp.width = ViewGroup.LayoutParams.MATCH_PARENT
            ilp.height = ViewGroup.LayoutParams.MATCH_PARENT
            val pad = if (stretch) 0 else (hPx * 0.14f).toInt()
            h.icon.setPadding(pad * 2, pad, pad * 2, pad)
            h.icon.scaleType = if (stretch) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        } else {
            val s = if (stretch) hPx else (hPx * if (e.isAdd || e.isFiles) 0.48f else 0.66f).toInt()
            ilp.width = s
            ilp.height = s
            h.icon.setPadding(0, 0, 0, 0)
            h.icon.scaleType = if (stretch) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        if (e.isAdd && wide) {
            val pad = (hPx * 0.3f).toInt()
            h.icon.setPadding(pad, pad, pad, pad)
            h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        h.icon.layoutParams = ilp

        h.label.text = e.label
        h.label.visibility = if (prefs.showLabels) View.VISIBLE else View.GONE
        (h.label.layoutParams as LinearLayout.LayoutParams).width = wPx
        style(h, h.itemView.hasFocus())
    }

    private fun style(h: VH, focused: Boolean) {
        val moving = h.bindingAdapterPosition == movingPos && movingPos >= 0
        h.tile.focusedLook = focused
        h.tile.accent = moving
        val s = if (focused || moving) 1f + prefs.focusScale / 100f else 1f
        h.tile.animate().scaleX(s).scaleY(s).setDuration(150).withEndAction { h.tile.invalidate() }.start()
        h.tile.translationZ = if (focused) 10f else 0f
        h.itemView.translationZ = if (focused) 10f else 0f
        h.label.typeface = if (focused) Fonts.medium(h.itemView.context) else Fonts.regular(h.itemView.context)
        h.label.setTextColor(if (focused) Color.WHITE else Color.argb(215, 255, 255, 255))
        h.label.translationY = if (focused) h.tile.height * prefs.focusScale / 200f else 0f
    }
}
