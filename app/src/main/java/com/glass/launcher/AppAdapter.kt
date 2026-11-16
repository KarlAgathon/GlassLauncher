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
        // blur only when asked for; otherwise clear glass where the wallpaper stays sharp
        h.tile.backdrop = prefs.tileFrost && prefs.tileAlpha > 0
        h.tile.liquid = !prefs.tileFrost && prefs.tileAlpha > 0
        h.tile.rimMode = prefs.tileBorder
        h.tile.plain = prefs.tileAlpha == 0 && prefs.tileBorder == 0
        h.tile.clean = prefs.tileClean
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

        // Light look: the logo's mirror picture under it; apps that are not installed yet are dimmed with a ⬇
        if (prefs.reflect && e.icon != null && !e.isAdd) {
            val rh = (hPx * 0.42f).toInt()
            val key = "${System.identityHashCode(e.icon)}:$wPx:$hPx:${prefs.logoFit}"
            val m = mirrors.getOrPut(key) {
                // kept on disk too: after a restart the mirrors are there at once
                val stamp = IconStore.file(ctx, e.pkg).lastModified()
                val f = java.io.File(java.io.File(ctx.cacheDir, "mirrors").apply { mkdirs() },
                    "${e.pkg}_${wPx}_${hPx}_${prefs.logoFit}_$stamp.png")
                (if (f.exists() && !e.missing) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null)
                    ?: Bitmaps.mirror(e.icon, wPx, hPx, h.icon.paddingLeft, h.icon.paddingTop, rh, stretch).also { b ->
                        if (!e.missing && stamp > 0) Thread { runCatching { f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } } }.start()
                    }
            }
            h.reflect.setImageBitmap(m)
            h.reflect.layoutParams = (h.reflect.layoutParams as LinearLayout.LayoutParams).also { it.width = wPx; it.height = rh; it.topMargin = (2 * d).toInt() }
            h.reflect.visibility = View.VISIBLE
        } else h.reflect.visibility = View.GONE
        h.icon.alpha = if (e.missing) 0.45f else 1f
        h.reflect.alpha = if (e.missing) 0.3f else 1f
        h.badge.visibility = if (e.missing) View.VISIBLE else View.GONE

        h.label.text = e.label
        // names off: the selected app's name still shows softly under its logo
        h.label.visibility = if (prefs.showLabels || prefs.nameOnFocus) View.VISIBLE else View.GONE
        (h.label.layoutParams as LinearLayout.LayoutParams).width = wPx
        style(h, h.itemView.hasFocus())
    }

    private fun style(h: VH, focused: Boolean) {
        val moving = h.bindingAdapterPosition == movingPos && movingPos >= 0
        // Light look: no frame at all – the selected logo just grows and its mirror shines
        h.tile.focusedLook = focused && prefs.focusFrame
        // without a frame there is also no dark shadow box behind the selected icon – it only grows
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val sh = if (prefs.focusFrame) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
            for (v in listOf(h.tile, h.itemView)) { v.outlineAmbientShadowColor = sh; v.outlineSpotShadowColor = sh }
        }
        h.tile.accent = moving
        val s = if (focused || moving) 1f + prefs.focusScale / 100f else 1f
        h.tile.animate().scaleX(s).scaleY(s).setDuration(if (Backdrop.lite) 0L else 150L).withEndAction { h.tile.invalidate() }.start()
        // older Android cannot hide the shadow: no lift there when the frame is off
        val z = if (focused && (prefs.focusFrame || android.os.Build.VERSION.SDK_INT >= 28)) 10f else 0f
        h.tile.translationZ = z
        h.itemView.translationZ = z
        h.label.typeface = if (focused) Fonts.medium(h.itemView.context) else Fonts.regular(h.itemView.context)
        h.label.setTextColor(if (focused) Color.WHITE else Color.argb(215, 255, 255, 255))
        if (!prefs.showLabels && prefs.nameOnFocus)
            h.label.animate().alpha(if (focused) 1f else 0f).setDuration(if (Backdrop.lite) 0L else 150L).start()
        else h.label.alpha = 1f
        h.label.translationY = if (focused) h.tile.height * prefs.focusScale / 200f else 0f
        if (h.reflect.visibility == View.VISIBLE) {
            // the selected logo shines more in its mirror and moves down with the zoom
            val base = if (items.getOrNull(h.bindingAdapterPosition)?.missing == true) 0.3f else 1f
            h.reflect.animate().alpha(base * if (focused) 1f else 0.55f)
                .translationY(if (focused) h.tile.height * prefs.focusScale / 200f else 0f)
                .scaleX(s).scaleY(s).setDuration(if (Backdrop.lite) 0L else 150L).start()
        }
    }
}
