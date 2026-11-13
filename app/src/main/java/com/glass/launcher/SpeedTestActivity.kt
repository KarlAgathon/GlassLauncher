package com.glass.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Speed test with two cards:
 *  - VPN (green key): measures through the VPN that is active on the box
 *  - Without VPN (red box): measures straight over Wi-Fi / Ethernet, past the VPN
 * Each card shows ping, download, upload, the public address it came out from and a live graph.
 * Uses the Cloudflare speed test servers.
 */
class SpeedTestActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var stop = false

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private inner class Card(val viaVpn: Boolean) {
        val color = if (viaVpn) Color.rgb(70, 220, 130) else Color.rgb(255, 90, 90)
        val frame = GlassFrame(this@SpeedTestActivity).apply {
            radiusDp = 28f; tint = 0.07f; darken = 0.35f; clean = true
            isFocusable = true; isClickable = true
        }
        val status = text(15f, Color.argb(220, 255, 255, 255))
        val big = text(54f, Color.WHITE, medium = true)
        val unit = text(16f, Color.argb(200, 255, 255, 255))
        val ping = text(16f, Color.argb(230, 255, 255, 255))
        val down = text(16f, Color.argb(230, 255, 255, 255))
        val up = text(16f, Color.argb(230, 255, 255, 255))
        val where = text(13f, Color.argb(170, 255, 255, 255))
        val graph = Graph(this@SpeedTestActivity, color)

        init {
            val col = LinearLayout(this@SpeedTestActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28f).toInt(), dp(24f).toInt(), dp(28f).toInt(), dp(22f).toInt())
            }
            val head = LinearLayout(this@SpeedTestActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ImageView(this@SpeedTestActivity).apply {
                setImageResource(if (viaVpn) R.drawable.lc_key_round else R.drawable.lc_monitor)
                setColorFilter(color)
            }, LinearLayout.LayoutParams(dp(30f).toInt(), dp(30f).toInt()).apply { marginEnd = dp(12f).toInt() })
            head.addView(text(22f, Color.WHITE, medium = true).apply {
                text = if (viaVpn) T.s("Through VPN", "Kroz VPN", "Über VPN") else T.s("Without VPN", "Bez VPN-a", "Ohne VPN")
            })
            col.addView(head)
            col.addView(status.apply { setPadding(0, dp(6f).toInt(), 0, dp(4f).toInt()) })
            val numRow = LinearLayout(this@SpeedTestActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            numRow.addView(big.apply { text = "–" })
            numRow.addView(unit.apply { text = " Mbit/s"; setPadding(0, 0, 0, dp(12f).toInt()) })
            col.addView(numRow)
            col.addView(graph, LinearLayout.LayoutParams(-1, dp(110f).toInt()).apply { topMargin = dp(6f).toInt(); bottomMargin = dp(10f).toInt() })
            col.addView(ping); col.addView(down); col.addView(up)
            col.addView(where.apply { setPadding(0, dp(8f).toInt(), 0, 0) })
            frame.addView(col)
            frame.setOnFocusChangeListener { _, has -> frame.focusedLook = has; frame.invalidate() }
            frame.setOnClickListener { start(this) }
            reset()
        }

        fun reset() {
            status.text = T.s("OK = start", "OK = pokreni", "OK = starten")
            big.text = "–"
            ping.text = T.s("Ping", "Ping", "Ping") + ": –"
            down.text = "↓ Download: –"
            up.text = "↑ Upload: –"
            where.text = ""
            graph.clear()
        }
    }

    private fun text(sp: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (medium) Fonts.medium(this@SpeedTestActivity) else Fonts.regular(this@SpeedTestActivity)
    }

    private lateinit var vpnCard: Card
    private lateinit var directCard: Card
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(Prefs(this).lang)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(11, 10, 22)) }
        Backdrop.blurred?.let { bd -> root.addView(ImageView(this).apply {
            setImageBitmap(bd); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.35f }, FrameLayout.LayoutParams(-1, -1)) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56f).toInt(), dp(32f).toInt(), dp(56f).toInt(), dp(24f).toInt())
        }
        col.addView(text(26f, Color.WHITE, medium = true).apply { text = T.s("Speed test", "Test brzine", "Speedtest") })
        hint = text(15f, Color.argb(200, 255, 255, 255)).apply { setPadding(0, dp(4f).toInt(), 0, dp(20f).toInt()) }
        col.addView(hint)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vpnCard = Card(true); directCard = Card(false)
        row.addView(vpnCard.frame, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14f).toInt() })
        row.addView(directCard.frame, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14f).toInt() })
        col.addView(row)
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        updateHint()
        vpnCard.frame.post { vpnCard.frame.requestFocus() }
    }

    override fun onDestroy() { stop = true; super.onDestroy() }

    private fun cm() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun vpnActive(): Boolean = runCatching {
        cm().getNetworkCapabilities(cm().activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /** Wi-Fi / Ethernet network underneath the VPN */
    @Suppress("DEPRECATION")
    private fun directNetwork(): Network? = runCatching {
        cm().allNetworks.firstOrNull { n ->
            val c = cm().getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrNull()

    private fun updateHint() {
        hint.text = if (vpnActive()) T.s("VPN is on. Choose a card and press OK.", "VPN je uključen. Izaberi karticu i pritisni OK.",
            "VPN ist an. Karte wählen und OK drücken.")
        else T.s("VPN is off right now – both cards measure the same line.", "VPN trenutno nije uključen – obje kartice mjere istu vezu.",
            "VPN ist gerade aus – beide Karten messen dieselbe Leitung.")
    }

    // ------------------------------------------------------------------ the test

    private fun start(card: Card) {
        if (!running.compareAndSet(false, true)) return
        updateHint()
        card.reset()
        val net: Network? = if (card.viaVpn) null else directNetwork()
        if (card.viaVpn && !vpnActive()) card.status.text = T.s("VPN is not on – measuring the normal line", "VPN nije uključen – mjerim običnu vezu", "VPN ist nicht an – messe die normale Leitung")
        Thread {
            try {
                ui { if (!card.viaVpn || vpnActive()) card.status.text = T.s("Measuring ping…", "Mjerim ping…", "Messe Ping…") }
                val info = trace(net)
                ui { card.where.text = info }
                val p = ping(net)
                ui { card.ping.text = T.s("Ping", "Ping", "Ping") + ": ${p ?: "–"} ms" }
                if (p == null) throw IllegalStateException("no connection")

                ui { card.status.text = T.s("Download…", "Preuzimanje…", "Download…") }
                val d = download(net, card)
                ui { card.down.text = "↓ Download: %.1f Mbit/s".format(d); card.big.text = "%.0f".format(d) }

                ui { card.status.text = T.s("Upload…", "Slanje…", "Upload…"); card.graph.mark() }
                val u = upload(net, card)
                ui {
                    card.up.text = "↑ Upload: %.1f Mbit/s".format(u)
                    card.status.text = T.s("Done · OK = again", "Gotovo · OK = ponovo", "Fertig · OK = nochmal")
                    card.big.text = "%.0f".format(d)
                }
            } catch (e: Exception) {
                ui {
                    card.status.text = if (!card.viaVpn && vpnActive())
                        T.s("The VPN does not let traffic past it. Switch the VPN off, or add LEAD to the VPN's split tunneling.",
                            "VPN ne pušta saobraćaj mimo sebe. Ugasi VPN ili dodaj LEAD u split tunneling u VPN aplikaciji.",
                            "Das VPN lässt nichts an sich vorbei. VPN ausschalten oder LEAD im Split-Tunneling des VPN eintragen.")
                    else T.s("No connection to the test server", "Nema veze sa test serverom", "Keine Verbindung zum Testserver")
                }
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun ui(f: () -> Unit) { h.post { if (!isFinishing) f() } }

    private fun open(net: Network?, url: String): HttpURLConnection {
        val u = URL(url)
        val c = (if (net != null) net.openConnection(u) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "LEAD-Launcher-Speedtest")
        return c
    }

    /** where the test came out: public IP and location (Cloudflare trace) */
    private fun trace(net: Network?): String = runCatching {
        val c = open(net, "https://speed.cloudflare.com/cdn-cgi/trace")
        val t = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect()
        val m = t.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        listOfNotNull(m["ip"], m["loc"], m["colo"]?.let { "server $it" }).joinToString("  ·  ")
    }.getOrDefault("")

    private fun ping(net: Network?): Int? {
        val times = mutableListOf<Long>()
        repeat(6) {
            runCatching {
                val t0 = SystemClock.elapsedRealtime()
                val c = open(net, "https://speed.cloudflare.com/__down?bytes=0")
                c.inputStream.use { it.readBytes() }; c.disconnect()
                times.add(SystemClock.elapsedRealtime() - t0)
            }
        }
        if (times.isEmpty()) return null
        return times.drop(1).ifEmpty { times }.min().toInt()
    }

    /** 4 parallel downloads for ~10 s; returns Mbit/s and feeds the graph */
    private fun download(net: Network?, card: Card): Double {
        val bytes = AtomicLong(0); val done = AtomicBoolean(false)
        val t0 = SystemClock.elapsedRealtime()
        val workers = (1..4).map {
            Thread {
                while (!done.get() && !stop) {
                    runCatching {
                        val c = open(net, "https://speed.cloudflare.com/__down?bytes=50000000")
                        c.inputStream.use { inp ->
                            val buf = ByteArray(64 * 1024)
                            while (!done.get() && !stop) { val n = inp.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong()) }
                        }
                        c.disconnect()
                    }.onFailure { if (bytes.get() == 0L) Thread.sleep(300) }
                }
            }.apply { start() }
        }
        val mbps = sample(bytes, t0, card)
        done.set(true); workers.forEach { it.join(2000) }
        return mbps
    }

    /** 3 parallel uploads for ~8 s */
    private fun upload(net: Network?, card: Card): Double {
        val bytes = AtomicLong(0); val done = AtomicBoolean(false)
        val chunk = ByteArray(2 * 1024 * 1024)
        val t0 = SystemClock.elapsedRealtime()
        val workers = (1..3).map {
            Thread {
                while (!done.get() && !stop) {
                    runCatching {
                        val c = open(net, "https://speed.cloudflare.com/__up")
                        c.doOutput = true; c.requestMethod = "POST"
                        c.setFixedLengthStreamingMode(chunk.size)
                        c.outputStream.use { out ->
                            var off = 0
                            while (off < chunk.size && !done.get()) {
                                val n = minOf(64 * 1024, chunk.size - off)
                                out.write(chunk, off, n); off += n; bytes.addAndGet(n.toLong())
                            }
                        }
                        runCatching { c.inputStream.use { it.readBytes() } }
                        c.disconnect()
                    }.onFailure { if (bytes.get() == 0L) Thread.sleep(300) }
                }
            }.apply { start() }
        }
        val mbps = sample(bytes, t0, card, seconds = 8)
        done.set(true); workers.forEach { it.join(2000) }
        return mbps
    }

    /** every 250 ms: current speed into the graph + number; result = average after a short warm-up */
    private fun sample(bytes: AtomicLong, t0: Long, card: Card, seconds: Int = 10): Double {
        var lastB = 0L; var lastT = t0
        var warmB = 0L; var warmT = 0L
        while (!stop) {
            Thread.sleep(250)
            val now = SystemClock.elapsedRealtime(); val b = bytes.get()
            val cur = (b - lastB) * 8.0 / ((now - lastT).coerceAtLeast(1) / 1000.0) / 1_000_000.0
            lastB = b; lastT = now
            ui { card.graph.add(cur.toFloat()); card.big.text = "%.0f".format(cur) }
            if (warmT == 0L && now - t0 >= 1500) { warmT = now; warmB = b }
            if (now - t0 >= seconds * 1000L) break
        }
        val end = SystemClock.elapsedRealtime(); val b = bytes.get()
        return if (warmT == 0L || end <= warmT) b * 8.0 / ((end - t0).coerceAtLeast(1) / 1000.0) / 1_000_000.0
        else (b - warmB) * 8.0 / ((end - warmT) / 1000.0) / 1_000_000.0
    }

    /** small live line graph (download, then upload after a divider) */
    class Graph(ctx: Context, private val color: Int) : View(ctx) {
        private val pts = ArrayList<Float>()
        private var markAt = -1
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; this.color = color; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val grid = Paint().apply { this.color = Color.argb(40, 255, 255, 255); strokeWidth = 2f }

        fun clear() { pts.clear(); markAt = -1; invalidate() }
        fun add(v: Float) { pts.add(v); invalidate() }
        fun mark() { markAt = pts.size }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val hh = height.toFloat()
            for (i in 1..3) c.drawLine(0f, hh * i / 4, w, hh * i / 4, grid)
            if (pts.size < 2) return
            val max = (pts.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
            val total = 74   // ~10 s download + ~8 s upload at 4 per second
            val step = w / total
            if (markAt > 0) c.drawLine(markAt * step, 0f, markAt * step, hh, grid)
            val p = Path()
            pts.forEachIndexed { i, v -> val x = i * step; val y = hh - v / max * hh; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            val area = Path(p).apply { lineTo((pts.size - 1) * step, hh); lineTo(0f, hh); close() }
            fill.shader = LinearGradient(0f, 0f, 0f, hh, Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            c.drawPath(p, line)
        }
    }
}
