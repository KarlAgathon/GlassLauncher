package com.glass.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.random.Random

/** Wallpaper with a big clock that slowly changes place (no burn-in). */
class SaverView(ctx: Context) : FrameLayout(ctx) {
    private val prefs = Prefs(ctx)
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val clock = TextView(ctx)
    private val date = TextView(ctx)
    private val h = Handler(Looper.getMainLooper())
    private var seconds = 0

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat(ClockFmt.pattern(context, prefs), T.locale).format(Date())
            date.text = SimpleDateFormat("EEEE, d. MMMM", T.locale).format(Date()).replaceFirstChar { it.uppercase() }
            if (seconds++ % 40 == 0) moveClock()
            h.postDelayed(this, 1000)
        }
    }

    init {
        T.init(prefs.lang)
        setBackgroundColor(Color.BLACK)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(ctx).apply { setBackgroundColor(Color.argb(90, 0, 0, 0)) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clock.setTextColor(Color.WHITE)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 86f)
        clock.typeface = Fonts.light(ctx)
        clock.setShadowLayer(16f, 0f, 2f, Color.argb(120, 0, 0, 0))
        date.setTextColor(Color.argb(220, 255, 255, 255))
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        date.typeface = Fonts.regular(ctx)
        box.addView(clock)
        box.addView(date)
        addView(box, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val f = File(ctx.filesDir, Profiles.wallpaperName(prefs.profile))
        val dm = resources.displayMetrics
        Thread {
            val b: Bitmap? = runCatching {
                if (f.exists()) Bitmaps.decodeScaled(f, dm.widthPixels, dm.heightPixels)
                else BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default)
            }.getOrNull()
            h.post { if (b != null) image.setImageBitmap(b) }
        }.start()
    }

    private fun moveClock() {
        val w = width - box.width
        val hh = height - box.height
        if (w <= 0 || hh <= 0) { box.post { moveClock() }; return }
        val m = (40 * resources.displayMetrics.density).toInt()
        val x = Random.nextInt(m, maxOf(m + 1, w - m))
        val y = Random.nextInt(m, maxOf(m + 1, hh - m))
        box.animate().alpha(0f).setDuration(600).withEndAction {
            box.translationX = x.toFloat()
            box.translationY = y.toFloat()
            box.animate().alpha(1f).setDuration(600).start()
        }.start()
        // slow zoom of the picture
        image.animate().scaleX(1.06f + Random.nextFloat() * 0.04f).scaleY(1.06f + Random.nextFloat() * 0.04f)
            .setDuration(38_000).start()
    }

    fun start() { h.removeCallbacks(tick); h.post(tick) }
    fun stop() { h.removeCallbacks(tick) }
}

/** Screensaver that Android can use (Settings → Screensaver → LEAD Launcher). */
class GlassDream : DreamService() {
    private var view: SaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val v = SaverView(this)
        view = v
        setContentView(v)
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); view?.start() }
    override fun onDreamingStopped() { view?.stop(); super.onDreamingStopped() }
}

/** Same screensaver, started by LEAD Launcher itself when the home screen is idle. */
class ScreensaverActivity : Activity() {
    private lateinit var view: SaverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = SaverView(this)
        setContentView(view)
    }

    override fun onResume() { super.onResume(); view.start() }
    override fun onPause() { view.stop(); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean { finish(); return true }
}
