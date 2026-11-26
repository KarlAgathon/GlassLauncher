package com.glass.launcher

import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/**
 * Icon Adaptation Engine (LEAD v10).
 *
 * Makes every app's logo sit in LEAD's glass card like the built-in ones: the logo stays recognisable, the coloured
 * square / circle around it is left out where that can be done safely.
 *
 * Order:
 *  1. adaptive icon → its foreground layer alone (when it really is a separate logo, not a full picture)
 *  2. flat picture  → a careful background cut: only when the border is one even colour and the logo that is left
 *     is a sensible size; otherwise the original stays untouched
 *  3. transparent edges trimmed, so all logos are measured the same way
 *
 * Results are cached on disk per app version, so nothing is worked out again while you move around.
 * Manual pictures (long press › Change picture) always win – they are handled before this engine is asked.
 */
object IconAdapt {
    const val AUTO = 0
    const val ORIGINAL = 1
    const val MONO = 2

    private val mem = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val diskWrites = java.util.concurrent.ThreadPoolExecutor(0, 1, 15L,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(12),
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val diskLock = Any()
    private var generation = 0

    private fun dir(ctx: Context) = File(ctx.cacheDir, "adapted").apply { mkdirs() }

    /** forget every adapted picture (mode changed, or "Reset icons") */
    fun clear(ctx: Context) {
        synchronized(diskLock) {
            generation++
            diskWrites.queue.clear()
            mem.evictAll()
            dir(ctx).listFiles()?.forEach { it.delete() }
        }
    }

    /** the app's logo for a LEAD card, adapted per [mode]; null if the app has no icon at all */
    fun icon(ctx: Context, pkg: String, ri: ResolveInfo, size: Int, mode: Int): Bitmap? {
        val pm = ctx.packageManager
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "${pkg}_${stamp}_${mode}_$size"
        mem.get(key)?.let { return it }
        val f = File(dir(ctx), "$key.png")
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { mem.put(key, it); return it }
        val revision = synchronized(diskLock) { generation }
        val dr = runCatching { ri.loadIcon(pm) }.getOrNull() ?: return null
        var out = when (mode) {
            ORIGINAL -> render(dr, size)
            else -> adapt(dr, size)
        }
        if (mode == MONO) out = mono(out)
        out = Bitmaps.trimAlpha(out)
        val bitmap = out
        synchronized(diskLock) { if (revision == generation) mem.put(key, bitmap) }
        diskWrites.execute {
            runCatching {
                val tmp = File.createTempFile(".adapted-", ".part", f.parentFile)
                try {
                    check(tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) })
                    synchronized(diskLock) {
                        if (revision == generation && tmp.renameTo(f)) {
                            f.parentFile?.listFiles()?.filter {
                                it.name.startsWith("${pkg}_") && !it.name.startsWith("${pkg}_${stamp}_")
                            }?.forEach { it.delete() }
                        }
                    }
                } finally { tmp.delete() }
            }
        }
        return out
    }

    private fun render(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        return b
    }

    private fun adapt(dr: Drawable, size: Int): Bitmap {
        // 1. adaptive icon: the foreground layer is the logo, the background layer the colour behind it
        if (Build.VERSION.SDK_INT >= 26 && dr is AdaptiveIconDrawable) {
            val fg = dr.foreground
            if (fg != null) {
                // the layer is 108 units with the visible part in the middle 72: draw it bigger, then trim
                val big = (size * 1.5f).toInt()
                val b = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
                fg.setBounds(0, 0, big, big)
                fg.draw(Canvas(b))
                val cover = coverage(b)
                // a real logo covers part of the layer; a full picture (cover ~1) or nearly nothing is not usable alone
                if (cover in 0.03f..0.80f && contrastOk(b)) return b
            }
            return render(dr, size)
        }
        // 2. flat picture: cut away an even background colour, but only when it is safe
        val b = render(dr, size)
        return cutBackground(b) ?: b
    }

    /** share of pixels that are visible */
    private fun coverage(b: Bitmap): Float {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0
        for (p in px) if ((p ushr 24) > 40) n++
        return n.toFloat() / px.size
    }

    /** a logo made only of very dark pixels would vanish on the dark glass – then keep its background */
    private fun contrastOk(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var vis = 0; var light = 0
        for (p in px) {
            if ((p ushr 24) < 80) continue
            vis++
            val l = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            if (l > 60 || sat(p) > 0.35f) light++
        }
        return vis > 0 && light.toFloat() / vis > 0.15f
    }

    private fun sat(p: Int): Float {
        val mx = maxOf(Color.red(p), Color.green(p), Color.blue(p)); val mn = minOf(Color.red(p), Color.green(p), Color.blue(p))
        return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
    }

    private fun dist(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }

    /**
     * Careful background removal: the opaque pixels along the border must be (almost) one colour. Starting from the
     * edges, only pixels of that colour are removed (flood fill), so letters and shapes inside the logo stay whole.
     * Returns null when it is not safe.
     */
    private fun cutBackground(src: Bitmap): Bitmap? {
        val w = src.width; val h = src.height
        if (w < 16 || h < 16) return null
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        // border ring (outer 6 %), opaque pixels only
        val band = maxOf(2, w * 6 / 100)
        val ring = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= band && x < w - band && y >= band && y < h - band) continue
            val p = px[y * w + x]
            if ((p ushr 24) > 200) ring.add(p)
        }
        if (ring.size < 20) return null                  // already a shape on transparency – nothing to cut
        // median colour of the ring
        val bg = Color.rgb(ring.map { Color.red(it) }.sorted()[ring.size / 2],
            ring.map { Color.green(it) }.sorted()[ring.size / 2], ring.map { Color.blue(it) }.sorted()[ring.size / 2])
        val even = ring.count { dist(it, bg) <= 28 }.toFloat() / ring.size
        if (even < 0.9f) return null                     // border is a picture, not one colour: keep the original
        // flood fill from the edges through background-coloured or transparent pixels
        val tol = 40
        val gone = BooleanArray(w * h)
        val stack = IntArray(w * h); var sp = 0
        fun push(i: Int) {
            if (gone[i]) return
            val p = px[i]
            if ((p ushr 24) < 30 || dist(p, bg) <= tol) { gone[i] = true; stack[sp++] = i }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        var left = 0; var opaque = 0
        for (i in px.indices) {
            if ((px[i] ushr 24) > 30) opaque++
            if (!gone[i] && (px[i] ushr 24) > 30) left++
        }
        if (opaque == 0) return null
        val keep = left.toFloat() / opaque
        // too little left (logo had the background's colour) or almost nothing removed: not worth it / not safe
        if (keep < 0.04f || keep > 0.85f) return null
        val out = px.copyOf()
        for (i in out.indices) {
            if (gone[i]) { out[i] = 0; continue }
            // soft edge: pixels that are close to the background colour next to a removed pixel fade out a little
            val x = i % w; val y = i / w
            val nearGone = (x > 0 && gone[i - 1]) || (x < w - 1 && gone[i + 1]) || (y > 0 && gone[i - w]) || (y < h - 1 && gone[i + w])
            if (nearGone) {
                val dd = dist(out[i], bg)
                if (dd < tol * 2) {
                    val a = ((out[i] ushr 24) * (dd - tol).coerceAtLeast(0) / tol.toFloat()).toInt().coerceIn(0, 255)
                    out[i] = (a shl 24) or (out[i] and 0xFFFFFF)
                }
            }
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(out, 0, w, 0, 0, w, h)
        return if (contrastOk(b)) b else null
    }

    /** neutral look: every logo in the same warm white, shape kept by its transparency and brightness */
    private fun mono(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val warm = Color.rgb(244, 238, 230)
        // logos with their own background: keep brightness differences so the logo inside stays readable
        val opaqueShare = px.count { (it ushr 24) > 220 }.toFloat() / px.size
        for (i in px.indices) {
            val p = px[i]; val a = p ushr 24
            if (a == 0) continue
            val l = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
            val na = if (opaqueShare > 0.6f) (a * (0.25f + 0.75f * l / 255f)).toInt() else a
            px[i] = (na.coerceIn(0, 255) shl 24) or (warm and 0xFFFFFF)
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }
}
