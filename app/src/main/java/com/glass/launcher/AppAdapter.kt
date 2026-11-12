package com.glass.launcher

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        h.icon.setImageBitmap(e.icon)
        // own icons, the "+" and the file browser fill the tile a bit less
        val big = !e.isAdd && !e.isFiles
        val s = (h.itemView.resources.displayMetrics.density * if (big) 58 else 44).toInt()
        h.icon.layoutParams.width = s
        h.icon.layoutParams.height = s
        h.icon.requestLayout()
        h.label.text = e.label
        h.label.visibility = if (prefs.showLabels) View.VISIBLE else View.INVISIBLE
        h.tile.tint = prefs.glassAlpha / 100f
        style(h, h.itemView.hasFocus())
    }

    private fun style(h: VH, focused: Boolean) {
        val moving = h.bindingAdapterPosition == movingPos && movingPos >= 0
        h.tile.focusedLook = focused
        h.tile.accent = moving
        val s = if (focused || moving) 1.14f else 1f
        h.tile.animate().scaleX(s).scaleY(s).setDuration(140).withEndAction { h.tile.invalidate() }.start()
        h.label.typeface = if (focused) Fonts.medium(h.itemView.context) else Fonts.regular(h.itemView.context)
        h.label.setTextColor(if (focused) Color.WHITE else Color.argb(215, 255, 255, 255))
        h.label.translationY = if (focused) 6f * h.itemView.resources.displayMetrics.density else 0f
    }
}
