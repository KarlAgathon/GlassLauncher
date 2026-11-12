package com.glass.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat

/** The blurred wallpaper that glass panels show through themselves. */
object Backdrop {
    @Volatile var blurred: Bitmap? = null
    @Volatile var w = 0
    @Volatile var h = 0
}

object Fonts {
    private val cache = HashMap<Int, Typeface?>()

    fun get(ctx: Context, id: Int): Typeface? =
        cache.getOrPut(id) { runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull() }

    fun regular(ctx: Context) = get(ctx, R.font.inter_regular)
    fun medium(ctx: Context) = get(ctx, R.font.inter_medium)
    fun light(ctx: Context) = get(ctx, R.font.inter_light)
}

/**
 * "Liquid glass" container: shows the blurred wallpaper behind it, a milky tint,
 * a shine on the top half and a bright rim. Works on every Android version.
 */
class GlassFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var radiusDp = 26f
    var tint = 0.10f
    var darken = 0f
    var focusedLook = false
        set(v) { field = v; invalidate() }
    var accent = false
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val path = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val loc = IntArray(2)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { setWillNotDraw(false) }

    private fun white(f: Float) = Color.argb((f.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)

    override fun dispatchDraw(canvas: Canvas) {
        drawGlass(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawGlass(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = minOf(radiusDp * density, h / 2f, w / 2f)
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)
        val bd = Backdrop.blurred
        if (bd != null && Backdrop.w > 0) {
            getLocationInWindow(loc)
            val s = if (scaleX > 0.01f) scaleX else 1f
            dst.set(-loc[0] / s, -loc[1] / s, (Backdrop.w - loc[0]) / s, (Backdrop.h - loc[1]) / s)
            canvas.drawBitmap(bd, null, dst, bmpPaint)
        }
        fill.shader = null
        fill.color = white(tint + if (focusedLook) 0.14f else 0f)
        canvas.drawRect(rect, fill)
        if (darken > 0f) {
            fill.color = Color.argb((darken * 255).toInt(), 0, 0, 0)
            canvas.drawRect(rect, fill)
        }
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, 0f, 0f, h * 0.55f,
            white(if (focusedLook) 0.30f else 0.16f), white(0f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * 0.55f, fill)
        canvas.restore()

        val sw = (if (focusedLook || accent) 2.5f else 1.2f) * density
        rim.strokeWidth = sw
        val ra = if (focusedLook) 0.95f else 0.55f
        rim.shader = if (accent) {
            LinearGradient(0f, 0f, w, h, Color.rgb(120, 220, 255), Color.argb(150, 120, 220, 255), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w, h, intArrayOf(white(ra), white(ra * 0.25f), white(ra * 0.6f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        rect.inset(sw / 2f, sw / 2f)
        canvas.drawRoundRect(rect, r, r, rim)
    }
}
