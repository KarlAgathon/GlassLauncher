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
    /** 0 no border, 1 thin line, 2 glass rim */
    var rimMode = 2
    /** show the blurred wallpaper inside the glass */
    var backdrop = true
    /** draw nothing unless focused (white-snow look) */
    var plain = false
    /** corner radius in px; when < 0 radiusDp is used */
    var radiusPx = -1f
    /** clear "liquid glass": no blur, the wallpaper stays sharp; light catches the edges */
    var liquid = false
    /** clean glass: no white edges or shine; only the focused one gets a thin white frame */
    var clean = false
    /** 0..1 how strongly the blurred wallpaper shows inside the glass */
    var backdropAlpha = 1f
    /** 0..1 solid dark filling (1 = glass not see-through at all) */
    var solid = 0f

    private val density = resources.displayMetrics.density
    private val path = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val loc = IntArray(2)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        setWillNotDraw(false)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, currentRadius())
            }
        }
        clipToOutline = true
    }

    private fun currentRadius(): Float {
        val r = if (radiusPx >= 0f) radiusPx else radiusDp * density
        return minOf(r, height / 2f, width / 2f).coerceAtLeast(0f)
    }

    fun refreshShape() { invalidateOutline(); invalidate() }

    private fun white(f: Float) = Color.argb((f.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)

    override fun dispatchDraw(canvas: Canvas) {
        drawGlass(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawGlass(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (plain && !focusedLook && !accent) return
        val r = currentRadius()
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)
        val bd = Backdrop.blurred
        if (backdrop && bd != null && Backdrop.w > 0) {
            getLocationInWindow(loc)
            val s = if (scaleX > 0.01f) scaleX else 1f
            dst.set(-loc[0] / s, -loc[1] / s, (Backdrop.w - loc[0]) / s, (Backdrop.h - loc[1]) / s)
            bmpPaint.alpha = (backdropAlpha.coerceIn(0f, 1f) * 255).toInt()
            if (bmpPaint.alpha > 0) canvas.drawBitmap(bd, null, dst, bmpPaint)
        }
        if (solid > 0f) {
            fill.shader = null
            fill.color = Color.argb((solid.coerceIn(0f, 1f) * 255).toInt(), 24, 30, 44)
            canvas.drawRect(rect, fill)
        }
        fill.shader = null
        fill.color = white(tint + if (focusedLook) (if (clean) 0.05f else 0.14f) else 0f)
        canvas.drawRect(rect, fill)
        if (darken > 0f) {
            fill.color = Color.argb((darken * 255).toInt(), 0, 0, 0)
            canvas.drawRect(rect, fill)
        }
        if (!clean) {
            fill.color = Color.WHITE
            fill.shader = LinearGradient(0f, 0f, 0f, h * 0.55f,
                white(if (focusedLook) 0.30f else 0.16f), white(0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * 0.55f, fill)
        }
        canvas.restore()

        if (clean && !accent) {
            if (!focusedLook) return
            val sw = 1.6f * density
            rim.strokeWidth = sw
            rim.shader = null
            rim.color = white(0.92f)
            rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
            canvas.drawRoundRect(rect, r, r, rim)
            rim.color = Color.BLACK
            return
        }

        if (liquid && !focusedLook && !accent) {
            drawLiquidEdges(canvas, w, h, r)
            if (rimMode == 0) return
        }
        val mode = if ((focusedLook || accent) && rimMode == 0) 1 else rimMode
        if (mode == 0) return
        val sw = (if (focusedLook || accent) 2.5f else if (mode == 1) 1f else 1.2f) * density
        rim.strokeWidth = sw
        val ra = if (focusedLook) 0.95f else 0.55f
        rim.shader = if (mode == 1 && !accent) {
            LinearGradient(0f, 0f, 0f, h, white(if (focusedLook) 0.75f else 0.22f), white(if (focusedLook) 0.45f else 0.12f), Shader.TileMode.CLAMP)
        } else if (accent) {
            LinearGradient(0f, 0f, w, h, Color.rgb(120, 220, 255), Color.argb(150, 120, 220, 255), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w, h, intArrayOf(white(ra), white(ra * 0.25f), white(ra * 0.6f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        rect.inset(sw / 2f, sw / 2f)
        canvas.drawRoundRect(rect, r, r, rim)
    }

    /**
     * Edge light of clear glass: a bright rim top-left fading out in the middle,
     * a weaker rim bottom-right (light bending through the thick edge) and a thin inner glow.
     */
    private fun drawLiquidEdges(canvas: Canvas, w: Float, h: Float, r: Float) {
        val sw = 1.4f * density
        rim.strokeWidth = sw
        rim.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(white(0.70f), white(0.10f), white(0.04f), white(0.38f)),
            floatArrayOf(0f, 0.40f, 0.62f, 1f), Shader.TileMode.CLAMP)
        rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
        canvas.drawRoundRect(rect, r, r, rim)
        // soft inner glow just inside the rim (thick glass edge)
        val gw = 5f * density
        rim.strokeWidth = gw
        rim.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(white(0.16f), white(0f), white(0f), white(0.08f)),
            floatArrayOf(0f, 0.35f, 0.70f, 1f), Shader.TileMode.CLAMP)
        rect.set(sw + gw / 2f, sw + gw / 2f, w - sw - gw / 2f, h - sw - gw / 2f)
        val ri = (r - sw - gw / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, ri, ri, rim)
    }
}
