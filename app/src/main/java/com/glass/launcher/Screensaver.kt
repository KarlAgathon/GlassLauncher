package com.glass.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.random.Random

/** Where the screensaver pictures come from. */
object SaverPictures {
    const val OWN = 0; const val FOLDER = 1; const val REDDIT = 2; const val BING = 3

    val REDDITS = listOf("EarthPorn", "wallpapers", "SpacePorn", "CityPorn", "SkyPorn", "AnimalPorn")

    /** list of picture addresses (files or https links); call off the main thread */
    fun list(ctx: Context, p: Prefs): List<String> = runCatching {
        when (p.saverSrc) {
            FOLDER -> (File(p.saverFolder).listFiles() ?: emptyArray())
                .filter { it.isFile && ImageScanner.isImage(it) }.map { it.absolutePath }.shuffled()
            REDDIT -> reddit(p.saverReddit)
            BING -> bing()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** big landscape pictures from a subreddit (top of the month, no adult posts) */
    private fun reddit(sub: String): List<String> {
        val j = JSONObject(Http.text("https://www.reddit.com/r/$sub/top.json?t=month&limit=80&raw_json=1",
            mapOf("User-Agent" to "android:lead.launcher:1.0 (screensaver)")))
        val posts = j.getJSONObject("data").getJSONArray("children")
        val out = ArrayList<String>()
        for (i in 0 until posts.length()) {
            val d = posts.getJSONObject(i).getJSONObject("data")
            if (d.optBoolean("over_18")) continue
            val url = d.optString("url")
            if (!Regex("""\.(jpe?g|png)$""", RegexOption.IGNORE_CASE).containsMatchIn(url)) continue
            val src = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
            val w = src?.optInt("width") ?: 0; val h = src?.optInt("height") ?: 0
            if (w < 1600 || h <= 0 || w < h) continue   // only big landscape pictures
            out.add(url)
        }
        return out.shuffled()
    }

    /** Bing's picture of the day – the last 16 */
    private fun bing(): List<String> {
        val out = ArrayList<String>()
        for (idx in listOf(0, 8)) runCatching {
            val arr = JSONObject(Http.text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=en-US")).getJSONArray("images")
            for (i in 0 until arr.length()) out.add("https://www.bing.com" + arr.getJSONObject(i).getString("urlbase") + "_1920x1080.jpg")
        }
        return out.distinct().shuffled()
    }
}

/** Weather for the screensaver: now + the next 6 days. */
object SaverWeather {
    data class Now(val temp: Int, val code: Int, val day: Boolean, val feels: Int, val humidity: Int, val wind: Int, val max: Int, val min: Int)
    data class Day(val name: String, val code: Int, val max: Int, val min: Int)

    fun load(lat: Float, lon: Float): Pair<Now, List<Day>>? = runCatching {
        val j = JSONObject(Http.text("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"))
        val c = j.getJSONObject("current"); val d = j.getJSONObject("daily")
        val days = d.getJSONArray("time"); val codes = d.getJSONArray("weather_code")
        val mx = d.getJSONArray("temperature_2m_max"); val mn = d.getJSONArray("temperature_2m_min")
        val inFmt = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val outFmt = SimpleDateFormat("EEE", T.locale)
        val now = Now(Math.round(c.getDouble("temperature_2m")).toInt(), c.getInt("weather_code"), c.optInt("is_day", 1) == 1,
            Math.round(c.optDouble("apparent_temperature")).toInt(), c.optInt("relative_humidity_2m"),
            Math.round(c.optDouble("wind_speed_10m")).toInt(), Math.round(mx.getDouble(0)).toInt(), Math.round(mn.getDouble(0)).toInt())
        val next = (1 until days.length()).map { i ->
            Day(outFmt.format(inFmt.parse(days.getString(i))!!).trimEnd('.').replaceFirstChar { it.uppercase() },
                codes.getInt(i), Math.round(mx.getDouble(i)).toInt(), Math.round(mn.getDouble(i)).toInt())
        }
        now to next.take(6)
    }.getOrNull()

    fun icon(code: Int, day: Boolean = true) = when (code) {
        0 -> if (day) R.drawable.lc_sun else R.drawable.lc_moon
        1, 2 -> if (day) R.drawable.lc_cloud_sun else R.drawable.lc_cloud_moon
        3 -> R.drawable.lc_cloud
        45, 48 -> R.drawable.lc_cloud_fog
        in 51..67, in 80..82 -> R.drawable.lc_cloud_rain
        in 71..77, 85, 86 -> R.drawable.lc_cloud_snow
        in 95..99 -> R.drawable.lc_cloud_lightning
        else -> R.drawable.lc_cloud
    }

    /** colours like a modern weather app */
    fun color(code: Int, day: Boolean = true) = when (code) {
        0 -> if (day) Color.rgb(255, 204, 77) else Color.rgb(226, 221, 255)
        1, 2 -> if (day) Color.rgb(255, 222, 140) else Color.rgb(226, 221, 255)
        in 51..67, in 80..82 -> Color.rgb(110, 196, 255)
        in 71..77, 85, 86 -> Color.rgb(220, 242, 255)
        in 95..99 -> Color.rgb(255, 213, 74)
        else -> Color.rgb(235, 240, 248)
    }

    fun text(code: Int) = when (code) {
        0 -> T.s("Clear", "Vedro", "Klar")
        1 -> T.s("Mostly clear", "Pretežno vedro", "Überwiegend klar")
        2 -> T.s("Partly cloudy", "Djelimično oblačno", "Teilweise bewölkt")
        3 -> T.s("Cloudy", "Oblačno", "Bewölkt")
        45, 48 -> T.s("Fog", "Magla", "Nebel")
        in 51..57 -> T.s("Drizzle", "Rosulja", "Nieselregen")
        in 61..67, in 80..82 -> T.s("Rain", "Kiša", "Regen")
        in 71..77, 85, 86 -> T.s("Snow", "Snijeg", "Schnee")
        in 95..99 -> T.s("Thunderstorm", "Grmljavina", "Gewitter")
        else -> ""
    }
}

/**
 * Screensaver: changing pictures, on the left a glass weather card (now + 6 days),
 * on the right a big clock with seconds and the date. Everything drifts a little now and then (no burn-in).
 */
class SaverView(ctx: Context) : FrameLayout(ctx) {
    private val prefs = Prefs(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val imgA = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val imgB = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private var front = imgA
    private val content = FrameLayout(ctx)
    private val clock = TextView(ctx); private val secs = TextView(ctx)
    private val dayName = TextView(ctx); private val dateLine = TextView(ctx)
    private val weatherCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var pictures: List<String> = emptyList()
    private var picIndex = 0
    private var ticks = 0
    private val dm = resources.displayMetrics

    private fun dp(v: Float) = (v * dm.density).toInt()
    private fun tv(sp: Float, color: Int = Color.WHITE, font: android.graphics.Typeface? = Fonts.regular(context)) = TextView(context).apply {
        setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = font
        setShadowLayer(14f, 0f, 2f, Color.argb(130, 0, 0, 0))
    }

    private fun glass(radius: Float = 30f) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        colors = intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(34, 255, 255, 255))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(dp(1f), Color.argb(70, 255, 255, 255))
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat(if (ClockFmt.is24(context, prefs)) "HH:mm" else "h:mm", T.locale).format(now)
            secs.text = SimpleDateFormat(":ss", T.locale).format(now)
            dayName.text = SimpleDateFormat("EEEE", T.locale).format(now).replaceFirstChar { it.uppercase() }
            dateLine.text = SimpleDateFormat("d. MMMM yyyy", T.locale).format(now)
            ticks++
            if (ticks % prefs.saverInterval.coerceAtLeast(10) == 0) nextPicture()
            if (ticks % 60 == 0) drift()
            if (ticks % (20 * 60) == 0) loadWeather()
            h.postDelayed(this, 1000)
        }
    }

    init {
        T.init(prefs.lang)
        setBackgroundColor(Color.BLACK)
        addView(imgA, LayoutParams(-1, -1)); addView(imgB, LayoutParams(-1, -1))
        // darker edges so text stays readable on any picture
        addView(View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(30, 0, 0, 0), Color.argb(150, 0, 0, 0))) }, LayoutParams(-1, -1))

        // right: big clock + date
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val clockRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        clock.apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f); typeface = Fonts.light(ctx)
            setShadowLayer(18f, 0f, 3f, Color.argb(140, 0, 0, 0)); includeFontPadding = false }
        secs.apply { setTextColor(Color.argb(200, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f); typeface = Fonts.light(ctx)
            setShadowLayer(14f, 0f, 2f, Color.argb(130, 0, 0, 0)); setPadding(0, 0, 0, dp(14f)) }
        clockRow.addView(clock); clockRow.addView(secs)
        right.addView(clockRow)
        dayName.apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); typeface = Fonts.medium(ctx)
            setShadowLayer(14f, 0f, 2f, Color.argb(130, 0, 0, 0)); gravity = Gravity.END }
        dateLine.apply { setTextColor(Color.argb(225, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Fonts.regular(ctx)
            setShadowLayer(14f, 0f, 2f, Color.argb(130, 0, 0, 0)); gravity = Gravity.END }
        right.addView(dayName); right.addView(dateLine)
        content.addView(right, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(64f) })

        // left: weather
        content.addView(weatherCol, LayoutParams(dp(420f), -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(56f) })
        addView(content, LayoutParams(-1, -1))

        loadWeather()
        loadPictures()
    }

    // ------------------------------------------------------------------ pictures

    private fun loadPictures() {
        Thread {
            val list = SaverPictures.list(context, prefs)
            h.post {
                pictures = list; picIndex = 0
                if (pictures.isEmpty()) showOwn() else nextPicture()
            }
        }.start()
    }

    private fun showOwn() {
        val f = File(context.filesDir, Profiles.wallpaperName(prefs.profile))
        val last = File(context.cacheDir, "last_wallpaper.jpg")
        Thread {
            val b: Bitmap? = runCatching {
                when {
                    f.exists() -> Bitmaps.decodeScaled(f, dm.widthPixels, dm.heightPixels)
                    last.exists() -> Bitmaps.decodeScaled(last, dm.widthPixels, dm.heightPixels)
                    else -> BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default)
                }
            }.getOrNull()
            h.post { if (b != null) crossfade(b) }
        }.start()
    }

    private fun nextPicture() {
        if (pictures.isEmpty()) return
        val src = pictures[picIndex++ % pictures.size]
        Thread {
            val b: Bitmap? = runCatching {
                if (src.startsWith("http")) {
                    val tmp = File(context.cacheDir, "saver_pic.img")
                    Http.download(src, tmp)
                    Bitmaps.decodeScaled(tmp, dm.widthPixels, dm.heightPixels)
                } else Bitmaps.decodeScaled(File(src), dm.widthPixels, dm.heightPixels)
            }.getOrNull()
            h.post { if (b != null) crossfade(b) }
        }.start()
    }

    private fun crossfade(b: Bitmap) {
        val back = if (front === imgA) imgB else imgA
        back.setImageBitmap(b)
        back.scaleX = 1f; back.scaleY = 1f
        back.animate().alpha(1f).setDuration(1500).start()
        front.animate().alpha(0f).setDuration(1500).start()
        // slow zoom for a bit of life
        back.animate().scaleX(1.06f).scaleY(1.06f).setDuration(prefs.saverInterval.coerceAtLeast(10) * 1000L + 3000).start()
        front = back
    }

    // ------------------------------------------------------------------ weather

    private fun loadWeather() {
        Thread {
            val w = SaverWeather.load(prefs.lat, prefs.lon)
            h.post { if (w != null) showWeather(w.first, w.second) }
        }.start()
    }

    private fun showWeather(now: SaverWeather.Now, days: List<SaverWeather.Day>) {
        weatherCol.removeAllViews()
        // card 1: now
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = glass(); setPadding(dp(28f), dp(22f), dp(28f), dp(22f)) }
        card.addView(tv(18f, Color.argb(230, 255, 255, 255)).apply { text = "📍 " + prefs.city })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(context).apply {
            setImageResource(SaverWeather.icon(now.code, now.day)); setColorFilter(SaverWeather.color(now.code, now.day))
        }, LinearLayout.LayoutParams(dp(78f), dp(78f)).apply { marginEnd = dp(16f) })
        row.addView(tv(76f, Color.WHITE, Fonts.light(context)).apply { text = "${now.temp}°"; includeFontPadding = false })
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        card.addView(tv(22f, Color.WHITE, Fonts.medium(context)).apply { text = SaverWeather.text(now.code) })
        card.addView(tv(16f, Color.argb(210, 255, 255, 255)).apply {
            text = "↑ ${now.max}°   ↓ ${now.min}°   ·   " + T.s("Feels like", "Osjećaj", "Gefühlt") + " ${now.feels}°"
            setPadding(0, dp(6f), 0, 0)
        })
        card.addView(tv(15f, Color.argb(180, 255, 255, 255)).apply {
            text = "💧 ${now.humidity}%    💨 ${now.wind} km/h"; setPadding(0, dp(4f), 0, 0)
        })
        weatherCol.addView(card, LinearLayout.LayoutParams(-1, -2))

        // card 2: next 6 days
        if (days.isEmpty()) return
        val fc = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = glass(); setPadding(dp(24f), dp(14f), dp(24f), dp(14f)) }
        val lo = days.minOf { it.min }; val hi = days.maxOf { it.max }
        for (d in days) {
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5f), 0, dp(5f)) }
            r.addView(tv(17f).apply { text = d.name }, LinearLayout.LayoutParams(dp(64f), -2))
            r.addView(ImageView(context).apply { setImageResource(SaverWeather.icon(d.code)); setColorFilter(SaverWeather.color(d.code)) },
                LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(14f) })
            r.addView(tv(16f, Color.argb(190, 255, 255, 255)).apply { text = "${d.min}°"; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(42f), -2))
            r.addView(TempBar(context, lo, hi, d.min, d.max), LinearLayout.LayoutParams(0, dp(6f), 1f).apply { marginStart = dp(10f); marginEnd = dp(10f) })
            r.addView(tv(16f).apply { text = "${d.max}°" }, LinearLayout.LayoutParams(dp(42f), -2))
            fc.addView(r)
        }
        weatherCol.addView(fc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
    }

    /** small range bar like modern weather apps: where today's min–max sits within the week */
    private class TempBar(ctx: Context, val lo: Int, val hi: Int, val mn: Int, val mx: Int) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val hh = height.toFloat(); val r = hh / 2
            p.shader = null; p.color = Color.argb(60, 255, 255, 255)
            c.drawRoundRect(0f, 0f, w, hh, r, r, p)
            val span = (hi - lo).coerceAtLeast(1).toFloat()
            val a = (mn - lo) / span * w; val b = ((mx - lo) / span * w).coerceAtLeast(a + hh)
            p.shader = android.graphics.LinearGradient(a, 0f, b, 0f, Color.rgb(110, 196, 255), Color.rgb(255, 196, 77), android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(a, 0f, b, hh, r, r, p)
        }
    }

    /** moves everything a few pixels now and then, so nothing burns into the screen */
    private fun drift() {
        val m = dp(14f)
        content.animate().translationX(Random.nextInt(-m, m + 1).toFloat()).translationY(Random.nextInt(-m, m + 1).toFloat())
            .setDuration(4000).start()
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

    override fun onResume() { super.onResume(); HomeService.setHome(true); view.start() }
    override fun onPause() { view.stop(); HomeService.setHome(false); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean { finish(); return true }
}
