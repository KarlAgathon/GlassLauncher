package com.glass.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glass.speed.SpeedEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speed test with two cards:
 *  - Through VPN (green key): measured by the small "LEAD VPN test" helper app, which the user puts on the
 *    VPN app's list (split tunneling). LEAD itself can stay outside the VPN.
 *  - Without VPN (red box): measured by LEAD itself over Wi-Fi / Ethernet (past the VPN if LEAD is inside it).
 * Each card shows the public IPv4 address with the country flag, so you can see the two really differ.
 */
class SpeedTestActivity : Activity() {

    companion object {
        const val PROBE_APK = "https://github.com/KarlAgathon/GlassLauncher/releases/latest/download/LEAD-VPN-test.apk"
    }

    private val h = Handler(Looper.getMainLooper())
    private val directRunning = AtomicBoolean(false)
    @Volatile private var directEngine: SpeedEngine? = null
    private var probeConn: ServiceConnection? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private inner class Card(val viaVpn: Boolean) {
        val color = if (viaVpn) Color.rgb(70, 220, 130) else Color.rgb(255, 90, 90)
        val frame = GlassFrame(this@SpeedTestActivity).apply {
            radiusDp = 28f; tint = 0.07f; darken = 0.35f; clean = true
            isFocusable = true; isClickable = true
        }
        val where = text(20f, Color.WHITE, medium = true)
        val status = text(15f, Color.argb(220, 255, 255, 255))
        val big = text(54f, Color.WHITE, medium = true)
        val ping = text(16f, Color.argb(230, 255, 255, 255))
        val down = text(16f, Color.argb(230, 255, 255, 255))
        val up = text(16f, Color.argb(230, 255, 255, 255))
        val graph = Graph(this@SpeedTestActivity, color)
        var ip: String? = null
        var running = false

        init {
            val col = LinearLayout(this@SpeedTestActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28f).toInt(), dp(22f).toInt(), dp(28f).toInt(), dp(20f).toInt())
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
            // public address + flag right under the title
            col.addView(where.apply { setPadding(0, dp(8f).toInt(), 0, 0) })
            col.addView(status.apply { setPadding(0, dp(4f).toInt(), 0, dp(2f).toInt()) })
            val numRow = LinearLayout(this@SpeedTestActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            numRow.addView(big)
            numRow.addView(text(16f, Color.argb(200, 255, 255, 255)).apply { text = " Mbit/s"; setPadding(0, 0, 0, dp(12f).toInt()) })
            col.addView(numRow)
            col.addView(graph, LinearLayout.LayoutParams(-1, dp(100f).toInt()).apply { topMargin = dp(4f).toInt(); bottomMargin = dp(8f).toInt() })
            col.addView(ping); col.addView(down); col.addView(up)
            frame.addView(col)
            frame.setOnFocusChangeListener { _, has -> frame.focusedLook = has; frame.invalidate() }
            frame.setOnClickListener { if (viaVpn) startVpn() else startDirect() }
            reset()
        }

        fun reset() {
            where.text = "–"
            status.text = T.s("OK = start", "OK = pokreni", "OK = starten")
            big.text = "–"
            ping.text = "Ping: –"
            down.text = "↓ Download: –"
            up.text = "↑ Upload: –"
            graph.clear()
            ip = null
        }

        fun showWhere(ipAddr: String?, cc: String?) {
            ip = ipAddr
            val country = if (cc.isNullOrBlank()) "" else Locale("", cc).getDisplayCountry(T.locale)
            where.text = if (ipAddr == null) "?" else "${SpeedEngine.flag(cc ?: "")}  $country  ·  $ipAddr"
            compare()
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
            setPadding(dp(56f).toInt(), dp(30f).toInt(), dp(56f).toInt(), dp(20f).toInt())
        }
        col.addView(text(26f, Color.WHITE, medium = true).apply { text = T.s("Speed test", "Test brzine", "Speedtest") })
        hint = text(15f, Color.argb(210, 255, 255, 255)).apply { setPadding(0, dp(4f).toInt(), 0, dp(18f).toInt()) }
        col.addView(hint)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vpnCard = Card(true); directCard = Card(false)
        row.addView(vpnCard.frame, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14f).toInt() })
        row.addView(directCard.frame, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14f).toInt() })
        col.addView(row)
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hint.text = T.s("The VPN card measures through the small app \"LEAD VPN test\" – put it on your VPN's app list (split tunneling). LEAD itself stays outside the VPN.",
            "VPN kartica mjeri preko male aplikacije \"LEAD VPN test\" – dodaj nju u listu aplikacija u VPN-u (split tunneling). Sam LEAD ostaje izvan VPN-a.",
            "Die VPN-Karte misst über die kleine App \"LEAD VPN test\" – trag sie in der App-Liste deines VPN ein (Split-Tunneling). LEAD selbst bleibt außerhalb.")
        vpnCard.frame.post { vpnCard.frame.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (!vpnCard.running && !probeInstalled()) vpnCard.status.text = T.s("OK = install the helper \"LEAD VPN test\"",
            "OK = instaliraj pomoćnu aplikaciju \"LEAD VPN test\"", "OK = Hilfs-App \"LEAD VPN test\" installieren")
    }

    override fun onDestroy() {
        directEngine?.stop = true
        probeConn?.let { runCatching { unbindService(it) } }
        super.onDestroy()
    }

    private fun ui(f: () -> Unit) { h.post { if (!isFinishing) f() } }

    /** both addresses known: same = the VPN card did not really go through the VPN */
    private fun compare() {
        val a = vpnCard.ip; val b = directCard.ip
        if (a == null || b == null) return
        hint.text = if (a == b) T.s("⚠ Same address on both cards: the helper app is NOT going through the VPN. Add \"LEAD VPN test\" to the VPN's app list.",
            "⚠ Ista adresa na obje kartice: pomoćna aplikacija NE ide kroz VPN. Dodaj \"LEAD VPN test\" u listu aplikacija u VPN-u.",
            "⚠ Gleiche Adresse auf beiden Karten: die Hilfs-App geht NICHT über das VPN. \"LEAD VPN test\" in die App-Liste des VPN eintragen.")
        else T.s("✓ Different addresses – the VPN is working.", "✓ Različite adrese – VPN radi.", "✓ Verschiedene Adressen – das VPN funktioniert.")
    }

    // ------------------------------------------------------------------ without VPN (LEAD itself)

    private fun cm() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** if LEAD itself is inside a VPN: the Wi-Fi / Ethernet network underneath it */
    @Suppress("DEPRECATION")
    private fun directNetwork(): Network? = runCatching {
        val c = cm().getNetworkCapabilities(cm().activeNetwork)
        if (c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@runCatching null
        cm().allNetworks.firstOrNull { n ->
            val nc = cm().getNetworkCapabilities(n) ?: return@firstOrNull false
            !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrNull()

    private fun startDirect() {
        if (!directRunning.compareAndSet(false, true)) return
        val card = directCard
        card.reset()
        val e = SpeedEngine(directNetwork()); directEngine = e
        Thread {
            try {
                ui { card.status.text = T.s("Finding address…", "Tražim adresu…", "Suche Adresse…") }
                val w = e.where()
                ui { card.showWhere(w?.ip, w?.country); card.status.text = "Ping…" }
                val p = e.ping() ?: throw IllegalStateException()
                ui { card.ping.text = "Ping: $p ms"; card.status.text = T.s("Download…", "Preuzimanje…", "Download…") }
                val d = e.download { v -> ui { card.graph.add(v); card.big.text = "%.0f".format(v) } }
                ui { card.down.text = "↓ Download: %.1f Mbit/s".format(d); card.big.text = "%.0f".format(d)
                    card.graph.mark(); card.status.text = T.s("Upload…", "Slanje…", "Upload…") }
                val u = e.upload { v -> ui { card.graph.add(v) } }
                ui { card.up.text = "↑ Upload: %.1f Mbit/s".format(u); card.big.text = "%.0f".format(d)
                    card.status.text = T.s("Done · OK = again", "Gotovo · OK = ponovo", "Fertig · OK = nochmal") }
            } catch (ex: Exception) {
                ui { card.status.text = T.s("No connection to the test server", "Nema veze sa test serverom", "Keine Verbindung zum Testserver") }
            } finally { directRunning.set(false) }
        }.start()
    }

    // ------------------------------------------------------------------ through VPN (helper app)

    private fun probeInstalled() = runCatching { packageManager.getPackageInfo(SpeedEngine.PROBE_PKG, 0); true }.getOrDefault(false)

    private fun startVpn() {
        val card = vpnCard
        if (card.running) return
        if (!probeInstalled()) { installProbe(); return }
        card.reset(); card.running = true
        card.status.text = T.s("Connecting to LEAD VPN test…", "Povezujem se sa LEAD VPN test…", "Verbinde mit LEAD VPN test…")
        probeConn?.let { runCatching { unbindService(it) } }
        val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(m: Message) {
                if (isFinishing) return
                val b = m.data
                when (m.what) {
                    SpeedEngine.MSG_PHASE -> when (m.arg1) {
                        0 -> card.status.text = T.s("Finding address…", "Tražim adresu…", "Suche Adresse…")
                        1 -> card.status.text = "Ping…"
                        2 -> {
                            if (!b.getBoolean("vpn")) {
                                // VPN is off (or the helper is not on its list): don't measure, warn instead
                                probeConn?.let { runCatching { unbindService(it) } }; probeConn = null
                                card.running = false
                                card.showWhere(b.getString("ip"), b.getString("cc"))
                                card.big.text = "–"
                                card.status.text = T.s("⛔ VPN is OFF – you are not protected. Not measuring.",
                                    "⛔ VPN NIJE UKLJUČEN – nisi zaštićen. Ne mjerim.",
                                    "⛔ VPN ist AUS – du bist nicht geschützt. Keine Messung.")
                                hint.text = T.s("Switch the VPN on (and keep \"LEAD VPN test\" on its app list), then press OK again.",
                                    "Uključi VPN (i neka je \"LEAD VPN test\" na njegovoj listi aplikacija), pa ponovo pritisni OK.",
                                    "VPN einschalten (\"LEAD VPN test\" in seiner App-Liste lassen), dann erneut OK drücken.")
                                return
                            }
                            card.showWhere(b.getString("ip"), b.getString("cc"))
                            card.ping.text = "Ping: ${b.getInt("ping")} ms"
                            card.status.text = if (b.getBoolean("vpn")) T.s("Download… (VPN active ✓)", "Preuzimanje… (VPN aktivan ✓)", "Download… (VPN aktiv ✓)")
                                else T.s("Download… (⚠ this app is not in the VPN)", "Preuzimanje… (⚠ ova aplikacija nije u VPN-u)", "Download… (⚠ App ist nicht im VPN)")
                        }
                        3 -> { val d = b.getDouble("down"); card.down.text = "↓ Download: %.1f Mbit/s".format(d); card.big.text = "%.0f".format(d)
                            card.graph.mark(); card.status.text = T.s("Upload…", "Slanje…", "Upload…") }
                    }
                    SpeedEngine.MSG_SAMPLE -> { val v = b.getFloat("v"); card.graph.add(v); if (!card.graph.marked()) card.big.text = "%.0f".format(v) }
                    SpeedEngine.MSG_RESULT -> {
                        card.running = false
                        card.up.text = "↑ Upload: %.1f Mbit/s".format(b.getDouble("up"))
                        card.big.text = "%.0f".format(b.getDouble("down"))
                        card.status.text = if (b.getBoolean("vpn")) T.s("Done · VPN active ✓ · OK = again", "Gotovo · VPN aktivan ✓ · OK = ponovo", "Fertig · VPN aktiv ✓ · OK = nochmal")
                            else T.s("Done · ⚠ not through VPN · OK = again", "Gotovo · ⚠ nije kroz VPN · OK = ponovo", "Fertig · ⚠ nicht über VPN · OK = nochmal")
                    }
                    SpeedEngine.MSG_ERROR -> { card.running = false
                        card.status.text = T.s("No connection to the test server", "Nema veze sa test serverom", "Keine Verbindung zum Testserver") }
                }
            }
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching { Messenger(service).send(Message.obtain(null, SpeedEngine.MSG_START).apply { replyTo = replies }) }
                    .onFailure { card.running = false; card.status.text = T.s("Helper app not reachable", "Pomoćna aplikacija ne odgovara", "Hilfs-App nicht erreichbar") }
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (card.running) { card.running = false
                card.status.text = T.s("Helper app stopped", "Pomoćna aplikacija se ugasila", "Hilfs-App beendet") } }
        }
        probeConn = conn
        val ok = runCatching {
            bindService(Intent().setComponent(ComponentName(SpeedEngine.PROBE_PKG, SpeedEngine.PROBE_SERVICE)), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) { card.running = false; card.status.text = T.s("Helper app not reachable – reinstall it", "Pomoćna aplikacija ne odgovara – instaliraj je ponovo", "Hilfs-App nicht erreichbar – neu installieren") }
    }

    /** downloads "LEAD VPN test" from the LEAD release and installs it */
    private fun installProbe() {
        val card = vpnCard
        card.running = true
        card.status.text = T.s("Downloading LEAD VPN test…", "Skidam LEAD VPN test…", "Lade LEAD VPN test…")
        Thread {
            try {
                val f = File(cacheDir, "LEAD-VPN-test.apk")
                var url = URL(PROBE_APK)
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
                    break
                }
                c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                c.disconnect()
                ui {
                    card.running = false
                    card.status.text = T.s("Install it, then add \"LEAD VPN test\" to the VPN's app list and press OK",
                        "Instaliraj, zatim dodaj \"LEAD VPN test\" u listu aplikacija u VPN-u i pritisni OK",
                        "Installieren, dann \"LEAD VPN test\" in die App-Liste des VPN eintragen und OK drücken")
                    AppInstaller.start(this, listOf(SpeedEngine.PROBE_PKG to listOf(f)))
                }
            } catch (e: Exception) {
                ui { card.running = false; card.status.text = T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen") }
            }
        }.start()
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
        fun marked() = markAt >= 0

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val hh = height.toFloat()
            for (i in 1..3) c.drawLine(0f, hh * i / 4, w, hh * i / 4, grid)
            if (pts.size < 2) return
            val max = (pts.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
            val step = w / 74
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
