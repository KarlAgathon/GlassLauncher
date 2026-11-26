package com.glass.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/** Where the screensaver pictures come from. */
object SaverPictures {
    const val OWN = 0; const val FOLDER = 1; const val REDDIT = 2; const val BING = 3; const val DEFAULT = 4; const val LOCAL = 5

    val REDDITS = listOf("EarthPorn", "wallpapers", "SpacePorn", "CityPorn", "SkyPorn", "AnimalPorn")

    /** list of picture addresses (files or https links); call off the main thread */
    internal fun list(p: Prefs, requests: Http.Session): List<String> = runCatching {
        when (p.saverSrc) {
            FOLDER -> (File(p.saverFolder).listFiles() ?: emptyArray())
                .filter { it.isFile && ImageScanner.isImage(it) }.map { it.absolutePath }.shuffled()
            REDDIT -> reddit(p.saverReddit, requests)
            BING -> bing(requests)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** big landscape pictures from a subreddit (top of the month, no adult posts) */
    private fun reddit(sub: String, requests: Http.Session): List<String> {
        val j = JSONObject(requests.text("https://www.reddit.com/r/$sub/top.json?t=month&limit=80&raw_json=1",
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
    private fun bing(requests: Http.Session): List<String> {
        val out = ArrayList<String>()
        for (idx in listOf(0, 8)) runCatching {
            val arr = JSONObject(requests.text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=en-US")).getJSONArray("images")
            for (i in 0 until arr.length()) out.add("https://www.bing.com" + arr.getJSONObject(i).getString("urlbase") + "_1920x1080.jpg")
        }
        return out.distinct().shuffled()
    }
}

/** Shared renderer for Android's dream and LEAD's idle and preview activity. */
class SaverView(ctx: Context) : FrameLayout(ctx) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val images = List(2) { ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
    private val dashboard = Dashboard(ctx)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private val speeds = AmbientSpeedStore(ctx)
    private var requests: Http.Session? = null
    private var workers: ExecutorService? = null
    internal val isRunning: Boolean get() = workers != null
    private var session = 0
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var pictures = emptyList<String>()
    private var pictureIndex = 0
    private var front = 0
    private var forecast: AmbientForecast? = null
    private var cached = false
    private var time = ""
    private var period = ""
    private var weekday = ""
    private var date = ""
    private var memory: AmbientMemory? = null
    private var network = AmbientNetwork(null, "Unavailable")
    private var thermal: String? = null
    private var cpuTemperature: Float? = null
    private var vpnSpeed: AmbientSpeed? = null
    private var directSpeed: AmbientSpeed? = null
    private var mode = AmbientMode.BALANCED
    private var metrics = emptyList<AmbientMetric>()

    init {
        T.init(prefs.lang)
        setBackgroundColor(Color.BLACK)
        images.forEach { addView(it, LayoutParams(-1, -1)) }
        addView(dashboard, LayoutParams(-1, -1))
    }

    private fun periodic(task: () -> Long) = object : Runnable {
        override fun run() {
            if (!isRunning) return
            main.postDelayed(this, task())
        }
    }

    private val clockTick = periodic {
        val now = Date()
        val is24 = when (prefs.saverClockFormat) {
            0 -> android.text.format.DateFormat.is24HourFormat(context)
            1 -> true
            2 -> false
            else -> ClockFmt.is24(context, prefs)
        }
        val pattern = ambientClockPattern(is24, prefs.saverSeconds)
        time = SimpleDateFormat(pattern, T.locale).format(now)
        period = if (is24) "" else SimpleDateFormat("a", T.locale).format(now)
        weekday = SimpleDateFormat("EEEE", T.locale).format(now).replaceFirstChar { it.uppercase() }
        date = SimpleDateFormat("d MMMM yyyy", T.locale).format(now)
        dashboard.invalidate()
        val interval = if (prefs.saverSeconds) 1_000L else 60_000L
        interval - now.time.mod(interval)
    }

    private val ramTick = periodic {
        memory = AmbientMemory.read(context)
        dashboard.invalidate()
        20_000L
    }
    private val thermalTick = periodic {
        work({ ambientCpuTemperature(context) to ambientThermalStatus(context) }) { (temperature, status) ->
            cpuTemperature = temperature
            thermal = status
            dashboard.invalidate()
        }
        60_000L
    }
    private val driftTick = periodic {
        val scale = minOf(width / 1920f, height / 1080f)
        dashboard.animate().translationX(Random.nextInt(-8, 9) * scale)
            .translationY(Random.nextInt(-6, 7) * scale).setDuration(4_000).start()
        90_000L
    }
    private val weatherTick = periodic { loadWeather(); 30 * 60_000L }

    private fun <T> work(task: () -> T, apply: (T) -> Unit) {
        val generation = session
        workers?.submit {
            val result = runCatching(task)
            if (!Thread.currentThread().isInterrupted) main.post {
                if (isRunning && generation == session) result.onSuccess(apply)
            }
        }
    }

    fun start() {
        if (isRunning) return
        val http = Http.Session()
        requests = http
        workers = Executors.newFixedThreadPool(2)
        mode = prefs.ambientMode
        foreground = if (prefs.saverDim) ColorDrawable(Color.argb(66, 0, 0, 0)) else null
        metrics = AmbientMetric.entries.filter {
            prefs.ambientMetric(it) && when (mode) {
                AmbientMode.MINIMAL -> false
                AmbientMode.BALANCED -> it in setOf(AmbientMetric.VPN, AmbientMetric.RAM, AmbientMetric.TEMPERATURE)
                AmbientMode.FULL -> true
            }
        }
        vpnSpeed = if (AmbientMetric.VPN_SPEED in metrics) speeds.latest(AmbientRoute.VPN) else null
        directSpeed = if (AmbientMetric.DIRECT_SPEED in metrics) speeds.latest(AmbientRoute.DIRECT) else null
        val targetWidth = resources.displayMetrics.widthPixels.coerceIn(1, 1920)
        val targetHeight = resources.displayMetrics.heightPixels.coerceIn(1, 1080)
        work({ loadOwnPicture(targetWidth, targetHeight) }) { bitmap ->
            if (bitmap != null) showPicture(bitmap)
            work({ SaverPictures.list(prefs, http) }) { addresses ->
                pictures = addresses
                pictureIndex = 0
                if (pictures.isNotEmpty()) nextPicture()
            }
        }
        clockTick.run()
        weatherTick.run()
        if (AmbientMetric.RAM in metrics) ramTick.run()
        if (AmbientMetric.TEMPERATURE in metrics) thermalTick.run()
        if (AmbientMetric.VPN in metrics || AmbientMetric.CONNECTION in metrics) observeNetwork()
        if (prefs.saverMotion) main.postDelayed(driftTick, 90_000L)
    }

    private fun observeNetwork() {
        fun update() {
            network = AmbientNetwork.read(context)
            dashboard.invalidate()
        }
        update()
        val generation = session
        val updateStatus = Runnable { if (session == generation && isRunning) update() }
        fun listener() = object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                main.removeCallbacks(updateStatus)
                main.post(updateStatus)
            }

            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        }
        if (AmbientMetric.VPN in metrics) {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            val vpnListener = listener()
            runCatching { connectivity.registerNetworkCallback(request, vpnListener) }
                .onSuccess { callbacks.add(vpnListener) }
        }
        if (AmbientMetric.CONNECTION in metrics) {
            val connectionListener = listener()
            runCatching { connectivity.registerDefaultNetworkCallback(connectionListener) }
                .onSuccess { callbacks.add(connectionListener) }
        }
    }

    fun stop() {
        if (!isRunning) return
        session++
        requests?.cancel()
        requests = null
        workers?.shutdownNow()
        workers = null
        main.removeCallbacksAndMessages(null)
        callbacks.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callbacks.clear()
        pictures = emptyList()
        dashboard.animate().cancel()
        dashboard.translationX = 0f
        dashboard.translationY = 0f
        images.forEach {
            it.animate().cancel()
            it.setImageDrawable(null)
            it.alpha = 0f
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun loadWeather() {
        val http = requests ?: return
        val lat = prefs.lat
        val lon = prefs.lon
        val fahrenheit = prefs.weatherF
        if (forecast == null) {
            forecast = SaverWeather.cached(context, lat, lon, fahrenheit)
            cached = forecast != null
            dashboard.invalidate()
        }
        if (!SaverWeather.needsRefresh(forecast, System.currentTimeMillis())) return
        work({ SaverWeather.refresh(context, http, lat, lon, fahrenheit) }) { result ->
            if (result != null) forecast = result
            cached = result == null && forecast != null
            dashboard.invalidate()
        }
    }

    private fun loadOwnPicture(targetWidth: Int, targetHeight: Int): Bitmap? {
        val own = if (prefs.saverSrc == SaverPictures.LOCAL) File(context.filesDir, "ambient_${prefs.profile}.img")
            else File(context.filesDir, Profiles.wallpaperName(prefs.profile))
        val local = if (prefs.saverSrc != SaverPictures.DEFAULT && own.isFile)
            runCatching { decodeAmbientFile(own, targetWidth, targetHeight) }.getOrNull() else null
        return local ?: Bitmaps.decodeResScaled(context,
            Bitmaps.leadWallpaper(if (prefs.saverSrc == SaverPictures.DEFAULT) 0 else prefs.leadWp),
            targetWidth, targetHeight)
    }

    private fun nextPicture() {
        val http = requests ?: return
        val targetWidth = resources.displayMetrics.widthPixels.coerceIn(1, 1920)
        val targetHeight = resources.displayMetrics.heightPixels.coerceIn(1, 1080)
        val address = pictures[pictureIndex++ % pictures.size]
        work({
            runCatching {
                if (address.startsWith("https://")) {
                    val tmp = File.createTempFile("ambient", ".img", context.cacheDir)
                    try {
                        http.download(address, tmp)
                        decodeAmbientFile(tmp, targetWidth, targetHeight)
                    } finally { tmp.delete() }
                } else decodeAmbientFile(File(address), targetWidth, targetHeight)
            }.getOrNull()
        }) { bitmap ->
            if (bitmap != null) showPicture(bitmap)
            if (pictures.size > 1 || bitmap == null)
                main.postDelayed({ nextPicture() }, prefs.saverInterval.coerceAtLeast(10) * 1_000L)
        }
    }

    private fun showPicture(bitmap: Bitmap) {
        val outgoing = images[front]
        front = 1 - front
        val incoming = images[front]
        incoming.bringToFront()
        dashboard.bringToFront()
        incoming.setImageBitmap(bitmap)
        if (!prefs.saverMotion || outgoing.drawable == null) {
            incoming.alpha = 1f
            outgoing.setImageDrawable(null)
            outgoing.alpha = 0f
            return
        }
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(1_200).withEndAction {
            outgoing.setImageDrawable(null)
            outgoing.alpha = 0f
        }.start()
    }

    private inner class Dashboard(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val regular = Fonts.regular(context)
        private val light = Fonts.light(context)
        private val medium = Fonts.medium(context)
        private val warm = Color.rgb(247, 239, 228)
        private val muted = Color.rgb(219, 207, 192)
        private val champagne = Color.rgb(206, 176, 141)
        private val icons = mutableMapOf<Int, Drawable>()
        private val dayFormat by lazy { DateTimeFormatter.ofPattern("EEE", T.locale) }

        private fun label(c: Canvas, value: String, x: Float, y: Float, size: Float,
                          color: Int = warm, font: Typeface? = regular,
                          align: Paint.Align = Paint.Align.LEFT,
                          maxWidth: Float = Float.MAX_VALUE) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = size
            paint.typeface = font
            paint.textAlign = align
            val measured = paint.measureText(value)
            if (measured > maxWidth) paint.textSize *= maxWidth / measured
            c.drawText(value, x, y, paint)
        }

        private fun glass(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(171, 37, 32, 29)
            c.drawRoundRect(left, top, right, bottom, 25f, 25f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(83, 228, 210, 192)
            c.drawRoundRect(left, top, right, bottom, 25f, 25f, paint)
            paint.style = Paint.Style.FILL
        }

        private fun icon(c: Canvas, id: Int, x: Int, y: Int, size: Int) {
            val drawable = icons[id] ?: context.getDrawable(id)?.mutate()?.apply { setTint(champagne) }
                ?.also { icons[id] = it } ?: return
            drawable.setBounds(x, y, x + size, y + size)
            drawable.draw(c)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val scale = minOf(width / 1920f, height / 1080f)
            if (scale <= 0f) return
            c.save()
            c.translate((width - 1920 * scale) / 2, (height - 1080 * scale) / 2)
            c.scale(scale, scale)
            weather(c)
            val end = Paint.Align.RIGHT
            paint.setShadowLayer(12f, 0f, 2f, Color.BLACK)
            label(c, time, 1810f, 480f, 174f, font = light, align = end, maxWidth = 1030f)
            label(c, period, 1810f, 520f, 28f, champagne, medium, end)
            label(c, weekday, 1810f, 580f, 48f, font = medium, align = end, maxWidth = 980f)
            label(c, date, 1810f, 628f, 35f, muted, regular, end, 980f)
            paint.clearShadowLayer()
            if (metrics.isNotEmpty()) system(c)
            c.restore()
        }

        private fun weather(c: Canvas) {
            val data = forecast
            val now = data?.current
            glass(c, 104f, 70f, 674f, if (now != null) 392f else 208f)
            label(c, prefs.city, 134f, 116f, 32f, font = medium, maxWidth = 510f)
            if (data == null || now == null) {
                label(c, T.s("Weather unavailable", "Vrijeme nije dostupno", "Wetter nicht verfügbar", "Погода недоступна"), 134f, 169f, 26f, muted)
                return
            }
            val unit = if (prefs.weatherF) "F" else "C"
            if (now.code >= 0) icon(c, SaverWeather.icon(now.code, now.day), 138, 143, 82)
            label(c, "${now.temp}°$unit", if (now.code >= 0) 249f else 134f, 220f, 94f, font = light)
            label(c, SaverWeather.text(now.code), 134f, 265f, 30f, maxWidth = 510f)
            val week = data.week()
            val today = week[0]
            val temperatureDetails = listOfNotNull(
                today?.let { "↑ ${it.max}°   ↓ ${it.min}°" },
                now.feels?.let { T.s("Feels like $it°", "Osjećaj $it°", "Gefühlt $it°", "Ощущается как $it°") }
            )
            label(c, temperatureDetails.joinToString("   ·   "), 134f, 295f, 23f, muted, maxWidth = 510f)
            val currentDetails = listOfNotNull(
                now.humidity?.let { T.s("Humidity $it%", "Vlažnost $it%", "Feuchte $it%", "Влажность $it%") },
                now.wind?.let { T.s("Wind", "Vjetar", "Wind", "Ветер") + " $it ${now.windUnit}" },
                today?.rain?.let { T.s("Precip. $it%", "Padavine $it%", "Niederschl. $it%", "Осадки $it%") }
            )
            label(c, currentDetails.joinToString("   ·   "), 134f, 322f, 23f, muted, maxWidth = 510f)
            val sunlight = listOfNotNull(
                today?.sunrise?.let { T.s("Sunrise $it", "Izlazak $it", "Aufgang $it", "Восход $it") },
                today?.sunset?.let { T.s("Sunset $it", "Zalazak $it", "Untergang $it", "Закат $it") }
            )
            label(c, sunlight.joinToString("   ·   "), 134f, 349f, 23f, muted, maxWidth = 510f)
            val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT,
                DateFormat.SHORT, T.locale).format(Date(data.updatedAt))
            label(c, if (cached) T.s("Cached", "Sačuvano", "Gespeichert", "Из кэша") + " · $stamp"
                else T.s("Updated", "Ažurirano", "Aktualisiert", "Обновлено") + " · $stamp", 134f, 376f, 21f, champagne, maxWidth = 510f)
            if (mode == AmbientMode.MINIMAL) return
            glass(c, 104f, 410f, 674f, 774f)
            val valid = week.filterNotNull()
            val low = valid.minOfOrNull { it.min } ?: 0
            val high = valid.maxOfOrNull { it.max } ?: low
            val todayDate = LocalDate.now(data.timeZone)
            week.forEachIndexed { i, day ->
                val y = 430f + 47f * i
                if (i == 0) {
                    paint.color = Color.argb(43, 219, 184, 146)
                    c.drawRoundRect(121f, y, 657f, y + 42, 10f, 10f, paint)
                }
                val name = if (i == 0) T.s("Today", "Danas", "Heute", "Сегодня") else dayFormat.format(todayDate.plusDays(i.toLong()))
                label(c, name, 137f, y + 29, 25f, maxWidth = 86f)
                if (day == null) {
                    label(c, T.s("Unavailable", "Nedostupno", "Nicht verfügbar", "Недоступно"), 247f, y + 29, 23f, muted)
                } else {
                    if (day.code >= 0) icon(c, SaverWeather.icon(day.code), 233, y.toInt() + 7, 27)
                    label(c, "${day.min}°", 329f, y + 29, 24f, muted, align = Paint.Align.RIGHT)
                    val span = (high - low).coerceAtLeast(1).toFloat()
                    val start = ((day.min - low) / span).coerceIn(0f, 1f)
                    val end = ((day.max - low) / span).coerceIn(0f, 1f)
                    bar(c, 349f, y + 19, 222f, start, end)
                    label(c, "${day.max}°", 590f, y + 29, 24f)
                }
            }
        }

        private fun bar(c: Canvas, left: Float, top: Float, width: Float, from: Float, to: Float) {
            paint.color = Color.argb(65, 223, 203, 182)
            c.drawRoundRect(left, top, left + width, top + 5, 3f, 3f, paint)
            if (to < 0f) return
            val a = (left + width * from).coerceAtMost(left + width - 5)
            val b = (left + width * to).coerceIn(a + 5, left + width)
            paint.color = champagne
            c.drawRoundRect(a, top, b, top + 5, 3f, 3f, paint)
        }

        private fun system(c: Canvas) {
            val width = 1712f / metrics.size
            glass(c, 104f, 822f, 1816f, 1004f)
            metrics.forEachIndexed { index, metric ->
                val left = 104f + width * index
                icon(c, metric.icon, left.toInt() + 22, 843, 25)
                label(c, metric.title(), left + 57, 864f, 24f, champagne, medium, maxWidth = width - 76)
                val lines = when (metric) {
                    AmbientMetric.VPN -> listOfNotNull(when (network.vpn) {
                        true -> T.s("Connected", "Povezan", "Verbunden", "Подключён")
                        false -> T.s("Disconnected", "Odspojen", "Getrennt", "Отключён")
                        null -> T.s("Unknown", "Nepoznato", "Unbekannt", "Неизвестно")
                    }, network.vpnName)
                    AmbientMetric.CONNECTION -> network.connection.split(" · ")
                    AmbientMetric.RAM -> memory?.let {
                        val gib = 1_073_741_824.0
                        listOf(String.format(T.locale, "%.1f / %.1f GiB", it.used / gib, it.total / gib), "${it.percent}%")
                    } ?: listOf(T.s("Unavailable", "Nedostupno", "Nicht verfügbar", "Недоступно"))
                    AmbientMetric.TEMPERATURE -> listOfNotNull(
                        cpuTemperature?.let { String.format(T.locale, "CPU %.0f °C", it) } ?: T.s("Temperature unavailable", "Temperatura nedostupna", "Temperatur nicht verfügbar", "Температура недоступна"),
                        thermal?.let { T.s("Thermal status", "Termalno stanje", "Wärmestatus", "Тепловой статус") + " · $it" }
                    )
                    AmbientMetric.VPN_SPEED -> speedLines(vpnSpeed)
                    AmbientMetric.DIRECT_SPEED -> speedLines(directSpeed)
                }
                lines.forEachIndexed { i, line ->
                    label(c, line, left + 22, 904f + 31 * i, if (i == 0) 26f else 22f,
                        if (i == 0) warm else muted, maxWidth = width - 44)
                }
                if (metric == AmbientMetric.RAM) memory?.let {
                    bar(c, left + 22, 962f, minOf(230f, width - 44), 0f, if (it.percent > 0) it.percent / 100f else -1f)
                }
            }
        }

        private fun speedLines(record: AmbientSpeed?): List<String> {
            if (record == null) return listOf(T.s("Not measured", "Nije izmjereno", "Nicht gemessen", "Нет замера"))
            val format = DateFormat.getTimeInstance(DateFormat.SHORT, T.locale)
            val stamp = format.format(Date(record.time))
            val age = if (record.isStale(System.currentTimeMillis()))
                " · " + T.s("Stale", "Zastarjelo", "Veraltet", "Устарело") else ""
            val tested = T.s("Last tested", "Zadnji test", "Letzter Test", "Последний замер")
            return listOf(String.format(T.locale, "↓ %.1f Mbps", record.download),
                String.format(T.locale, "↑ %.1f Mbps", record.upload), "$tested $stamp$age")
        }
    }
}

internal fun ambientSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while ((width / sample).toLong() * (height / sample) > 4_150_000L ||
        width / sample > 4096 || height / sample > 4096 ||
        (width / sample / 2 >= targetWidth && height / sample / 2 >= targetHeight)) sample *= 2
    return sample
}

private fun decodeAmbientFile(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = ambientSampleSize(options.outWidth, options.outHeight, targetWidth, targetHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.absolutePath, options)
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
