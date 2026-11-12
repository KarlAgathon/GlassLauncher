package com.glass.launcher

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* ================================================================ settings */

/** Launcher profiles (each family member can have own apps, wallpaper and settings). */
object Profiles {
    const val MAIN = "main"
    private fun g(ctx: Context) = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)

    fun current(ctx: Context): String = g(ctx).getString("profile", MAIN) ?: MAIN
    fun setCurrent(ctx: Context, id: String) = g(ctx).edit().putString("profile", id).apply()

    /** id -> name, main profile first */
    fun list(ctx: Context): List<Pair<String, String>> {
        val ids = (g(ctx).getString("ids", "") ?: "").split(",").filter { it.isNotEmpty() }
        return listOf(MAIN to name(ctx, MAIN)) + ids.map { it to name(ctx, it) }
    }

    fun name(ctx: Context, id: String): String =
        g(ctx).getString("name_$id", null) ?: if (id == MAIN) T.s("Main", "Glavni", "Haupt") else id

    fun rename(ctx: Context, id: String, name: String) = g(ctx).edit().putString("name_$id", name).apply()

    fun add(ctx: Context, name: String): String {
        val id = "p" + System.currentTimeMillis().toString(36)
        val ids = (g(ctx).getString("ids", "") ?: "").split(",").filter { it.isNotEmpty() } + id
        g(ctx).edit().putString("ids", ids.joinToString(",")).putString("name_$id", name).apply()
        return id
    }

    fun delete(ctx: Context, id: String) {
        if (id == MAIN) return
        val ids = (g(ctx).getString("ids", "") ?: "").split(",").filter { it.isNotEmpty() && it != id }
        g(ctx).edit().putString("ids", ids.joinToString(",")).remove("name_$id").apply()
        ctx.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE).edit().clear().apply()
        File(ctx.filesDir, wallpaperName(id)).delete()
        if (current(ctx) == id) setCurrent(ctx, MAIN)
    }

    fun prefsName(id: String) = if (id == MAIN) "glass" else "glass_$id"
    fun wallpaperName(id: String) = if (id == MAIN) "wallpaper" else "wallpaper_$id"

    fun savedAccount(ctx: Context): String? = g(ctx).getString("google", null)
    fun saveAccount(ctx: Context, name: String) = g(ctx).edit().putString("google", name).apply()
}

class Prefs(ctx: Context) {
    val profile: String = Profiles.current(ctx)
    private val sp = ctx.getSharedPreferences(Profiles.prefsName(profile), Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()

    /** Apps on the home screen, in order. Starts empty: only the "+" tile is shown. */
    var pinned: List<String>
        get() = str("pinned", "").split(",").filter { it.isNotEmpty() }
        set(v) = putStr("pinned", v.joinToString(","))

    fun label(pkg: String): String? = sp.getString("label_$pkg", null)
    fun setLabel(pkg: String, v: String?) {
        if (v.isNullOrBlank()) sp.edit().remove("label_$pkg").apply() else putStr("label_$pkg", v)
    }

    var lang: String?
        get() = sp.getString("lang", null)
        set(v) = putStr("lang", v ?: "en")

    /** 0..100 milkiness of the glass */
    var glassAlpha: Int get() = int("glassAlpha2", 10); set(v) = putInt("glassAlpha2", v)
    /** 0..100 darkening of the wallpaper */
    var dim: Int get() = int("dim2", 10); set(v) = putInt("dim2", v)
    /** 0 original, 1 transparent (no background), 2 white glass */
    var iconStyle: Int get() = int("iconStyle2", 0); set(v) = putInt("iconStyle2", v)
    var columns: Int get() = int("columns2", 7); set(v) = putInt("columns2", v)
    /** 0 top, 1 middle, 2 bottom */
    var gridPosition: Int get() = int("gridPos", 2); set(v) = putInt("gridPos", v)
    var showLabels: Boolean get() = bool("labels", true); set(v) = putBool("labels", v)
    var blurWallpaper: Boolean get() = bool("blur", false); set(v) = putBool("blur", v)

    var showClock: Boolean get() = bool("sClock", true); set(v) = putBool("sClock", v)
    var showDate: Boolean get() = bool("sDate", true); set(v) = putBool("sDate", v)
    var showWeather: Boolean get() = bool("sWeather", true); set(v) = putBool("sWeather", v)
    var showNetwork: Boolean get() = bool("sNet", true); set(v) = putBool("sNet", v)
    var showVpn: Boolean get() = bool("sVpn", true); set(v) = putBool("sVpn", v)
    var showNotifs: Boolean get() = bool("sNotif", true); set(v) = putBool("sNotif", v)
    var showCpu: Boolean get() = bool("sCpu", true); set(v) = putBool("sCpu", v)
    var showRam: Boolean get() = bool("sRam", true); set(v) = putBool("sRam", v)

    var city: String get() = str("city", "Neumünster"); set(v) = putStr("city", v)
    var lat: Float get() = sp.getFloat("lat", 54.0717f); set(v) = sp.edit().putFloat("lat", v).apply()
    var lon: Float get() = sp.getFloat("lon", 9.9900f); set(v) = sp.edit().putFloat("lon", v).apply()

    /** URL of update.json (see README) */
    var updateUrl: String get() = str("updateUrl", ""); set(v) = putStr("updateUrl", v)
    var lastUpdateCheck: Long get() = sp.getLong("lastUpd", 0L); set(v) = sp.edit().putLong("lastUpd", v).apply()

    var blockedAutostart: Set<String>
        get() = HashSet(sp.getStringSet("blockedAuto", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("blockedAuto", HashSet(v)).apply()

    var askedLocation: Boolean get() = bool("askedLoc", false); set(v) = putBool("askedLoc", v)

    /** master switch for the status bar on the home screen */
    var showStatusBar: Boolean get() = bool("sBar", true); set(v) = putBool("sBar", v)

    /** 0 = like Android, 1 = 24h, 2 = 12h */
    var clockFormat: Int get() = int("clockFmt", 0); set(v) = putInt("clockFmt", v)
    var clockSeconds: Boolean get() = bool("clockSec", false); set(v) = putBool("clockSec", v)

    /** small corner overlay while a film / other app is open (needs the accessibility service) */
    var overlayVpn: Boolean get() = bool("ovVpn", true); set(v) = putBool("ovVpn", v)
    var overlayClock: Boolean get() = bool("ovClock", false); set(v) = putBool("ovClock", v)
    /** Home button of the remote opens Glass Launcher (needs the accessibility service) */
    var homeRedirect: Boolean get() = bool("homeRedir", true); set(v) = putBool("homeRedir", v)

    /** what to do when the home screen is idle: 0 nothing, 1 screensaver, 2 sleep */
    var idleAction: Int get() = int("idleAct", 0); set(v) = putInt("idleAct", v)
    var idleMinutes: Int get() = int("idleMin", 15); set(v) = putInt("idleMin", v)
}

object ClockFmt {
    fun pattern(ctx: Context, p: Prefs): String {
        val is24 = when (p.clockFormat) {
            1 -> true
            2 -> false
            else -> android.text.format.DateFormat.is24HourFormat(ctx)
        }
        val base = if (is24) "HH:mm" else "h:mm"
        return base + (if (p.clockSeconds) ":ss" else "") + (if (is24) "" else " a")
    }
}

/* ================================================================ apps */

data class AppEntry(val pkg: String, val label: String, val icon: Bitmap?) {
    val isAdd get() = pkg == ADD
    val isFiles get() = pkg == FILES

    companion object {
        const val ADD = "__add__"
        const val FILES = "__files__"
    }
}

object AppLoader {
    /** All launchable apps: package -> ResolveInfo (leanback entry preferred). */
    fun launchable(ctx: Context): LinkedHashMap<String, ResolveInfo> {
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (!map.containsKey(p)) map[p] = ri
            }
        }
        map.remove(ctx.packageName)
        return map
    }

    fun iconSize(ctx: Context) = (64 * ctx.resources.displayMetrics.density).toInt()

    fun filesIcon(ctx: Context, size: Int): Bitmap? =
        ctx.getDrawable(R.drawable.lc_folder)?.let { IconRender.tinted(it, size, Color.rgb(255, 197, 61)) }

    /** Entries for the home screen: pinned apps in order. */
    fun pinnedEntries(ctx: Context, prefs: Prefs): List<AppEntry> {
        val pm = ctx.packageManager
        val all = launchable(ctx)
        val size = iconSize(ctx)
        val style = prefs.iconStyle
        return prefs.pinned.mapNotNull { p ->
            if (p == AppEntry.FILES) {
                val icon = IconStore.load(ctx, p, size) ?: filesIcon(ctx, size)
                return@mapNotNull AppEntry(p, prefs.label(p) ?: T.s("Files", "Fajlovi", "Dateien"), icon)
            }
            val ri = all[p] ?: return@mapNotNull null
            val label = prefs.label(p) ?: ri.loadLabel(pm).toString()
            val icon = IconStore.load(ctx, p, size)
                ?: runCatching { IconRender.render(ri.loadIcon(pm), size, style) }.getOrNull()
            AppEntry(p, label, icon)
        }
    }

    /** All apps (plus the built-in file browser) for the "choose apps" list, sorted by name. */
    fun choosable(ctx: Context): List<Triple<String, String, Drawable?>> {
        val pm = ctx.packageManager
        val list = launchable(ctx).map { (p, ri) ->
            Triple(p, ri.loadLabel(pm).toString(), runCatching { ri.loadIcon(pm) }.getOrNull())
        }.sortedBy { it.second.lowercase() }
        return listOf(Triple(AppEntry.FILES, T.s("Files", "Fajlovi", "Dateien"),
            ctx.getDrawable(R.drawable.lc_folder))) + list
    }

    /** Installed user apps that start themselves when the box boots. */
    fun autostartApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val pkgs = pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), 0)
            .map { it.activityInfo.packageName }.toSet()
        return pkgs.mapNotNull { p ->
            if (p == ctx.packageName) return@mapNotNull null
            val ai = runCatching { pm.getApplicationInfo(p, 0) }.getOrNull() ?: return@mapNotNull null
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            p to pm.getApplicationLabel(ai).toString()
        }.sortedBy { it.second.lowercase() }
    }
}

/** Stops the background processes of apps the user blocked from auto-starting. */
object Autostart {
    fun apply(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (p in Prefs(ctx).blockedAutostart) runCatching { am.killBackgroundProcesses(p) }
    }
}

/* ================================================================ icons & bitmaps */

object IconRender {
    fun render(d: Drawable, size: Int, style: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fg = if (style >= 1 && Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) d.foreground else null
        if (fg != null) {
            val big = (size * 1.5f).toInt()
            val off = (size - big) / 2
            fg.setBounds(off, off, off + big, off + big)
            fg.draw(c)
        } else {
            d.setBounds(0, 0, size, size)
            d.draw(c)
        }
        return if (style == 2) whiten(bmp) else bmp
    }

    fun tinted(d: Drawable, size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val m = d.mutate()
        m.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        m.setBounds(0, 0, size, size)
        m.draw(Canvas(bmp))
        return bmp
    }

    fun whiten(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = PorterDuffColorFilter(Color.argb(235, 255, 255, 255), PorterDuff.Mode.SRC_IN)
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }
}

object IconStore {
    fun file(ctx: Context, pkg: String): File = File(File(ctx.filesDir, "icons").apply { mkdirs() }, pkg)

    fun load(ctx: Context, pkg: String, size: Int): Bitmap? {
        val f = file(ctx, pkg)
        if (!f.exists()) return null
        val b = Bitmaps.decodeScaled(f, size, size) ?: return null
        return Bitmaps.fitSquare(b, size)
    }
}

object Bitmaps {
    fun decodeScaled(f: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun fitSquare(b: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = minOf(size.toFloat() / b.width, size.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        val dst = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
        Canvas(out).drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Scales and crops the picture to exactly fill w x h (like centerCrop). */
    fun centerCrop(b: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val sw = (w / scale).toInt().coerceAtMost(b.width)
        val sh = (h / scale).toInt().coerceAtMost(b.height)
        val sx = (b.width - sw) / 2
        val sy = (b.height - sh) / 2
        Canvas(out).drawBitmap(b, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Small, strongly blurred copy (used behind the glass). */
    fun frosted(b: Bitmap): Bitmap {
        val w = maxOf(1, b.width / 8)
        val h = maxOf(1, b.height / 8)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        return boxBlur(small, 5, 3)
    }

    fun boxBlur(src: Bitmap, r: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val t = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurPass(a, t, w, h, r, true)
            blurPass(t, a, w, h, r, false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val n = 2 * r + 1
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) src[o * w + j] else src[j * w + o]
                    sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                val v = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                if (horizontal) dst[o * w + i] = v else dst[i * w + o] = v
            }
        }
    }

    fun softBlur(b: Bitmap): Bitmap {
        val w = maxOf(1, b.width / 14)
        val h = maxOf(1, b.height / 14)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        return Bitmap.createScaledBitmap(small, w * 4, h * 4, true)
    }
}

object ImageScanner {
    private val exts = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun isImage(f: File) = f.extension.lowercase() in exts

    /** All pictures on internal storage / USB, newest first. */
    fun scan(): List<File> {
        val out = ArrayList<File>()
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self") roots.add(it)
        }
        for (r in roots) walk(r, 0, out)
        return out.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    private fun walk(dir: File, depth: Int, out: MutableList<File>) {
        if (out.size > 1000) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < 5 && f.name != "Android") walk(f, depth + 1, out)
            } else if (isImage(f)) out.add(f)
        }
    }
}

/** Simple rounded glass shape, used as focus highlight for menu rows. */
class GlassDrawable(
    density: Float,
    alphaPct: Int,
    private val focused: Boolean = false,
    private val radiusDp: Float = 16f,
) : Drawable() {
    private val a = alphaPct / 100f
    private val d = density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (if (focused) 2f else 1f) * density
    }

    private fun white(f: Float) = Color.argb((f.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)

    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        b.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f)
        val r = radiusDp * d
        fill.shader = LinearGradient(0f, b.top, 0f, b.bottom, white(a * 1.6f), white(a * 0.7f), Shader.TileMode.CLAMP)
        c.drawRoundRect(b, r, r, fill)
        rim.shader = LinearGradient(b.left, b.top, b.right, b.bottom,
            white(if (focused) 0.95f else 0.5f), white(if (focused) 0.35f else 0.1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(b, r, r, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/* ================================================================ network, CPU, RAM */

data class NetState(val wifi: Boolean, val eth: Boolean, val vpn: Boolean, val ssid: String?)

object NetInfo {
    @Suppress("DEPRECATION")
    fun read(ctx: Context): NetState {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var wifi = false; var eth = false; var vpn = false
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = true
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
        }
        var ssid: String? = null
        if (wifi && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ssid = runCatching {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.connectionInfo?.ssid?.trim('"')
            }.getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
        }
        return NetState(wifi, eth, vpn, ssid)
    }
}

object SysStats {
    private var lastTotal = 0L
    private var lastIdle = 0L

    /** CPU load in %. Real value from /proc/stat if readable, otherwise estimated from CPU speed. */
    fun cpuPercent(): Int? {
        val real = runCatching {
            val parts = File("/proc/stat").bufferedReader().use { it.readLine() }
                .trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            val idle = parts[3] + parts.getOrElse(4) { 0L }
            val total = parts.sum()
            val dt = total - lastTotal
            val di = idle - lastIdle
            val first = lastTotal == 0L
            lastTotal = total; lastIdle = idle
            if (first || dt <= 0) null else (100 * (dt - di) / dt).toInt()
        }.getOrNull()
        if (real != null) return real.coerceIn(0, 100)
        return runCatching {
            val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return null
            val ratios = cpus.mapNotNull { c ->
                val cur = File(c, "cpufreq/scaling_cur_freq").readText().trim().toLongOrNull()
                val max = File(c, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
                if (cur != null && max != null && max > 0) cur.toDouble() / max else null
            }
            if (ratios.isEmpty()) null else (ratios.average() * 100).toInt().coerceIn(0, 100)
        }.getOrNull()
    }

    fun ramPercent(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem <= 0) return 0
        return ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt()
    }
}

/* ================================================================ internet */

object Http {
    fun open(url: String): HttpURLConnection {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 GlassLauncher")
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        var hops = 0
        while (conn.responseCode in 301..308 && hops++ < 6) {
            val loc = conn.getHeaderField("Location") ?: break
            val next = URL(conn.url, loc)
            conn = next.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 GlassLauncher")
            conn.connectTimeout = 15000; conn.readTimeout = 30000
        }
        return conn
    }

    fun text(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    /** Blocking download. Call off the main thread. */
    fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        open(url).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
    }
}

/* ================================================================ weather (Open-Meteo, no key) */

object Weather {
    /** (icon, "7°") or null. Call off the main thread. */
    fun current(lat: Float, lon: Float): Pair<Int, String>? = runCatching {
        val json = JSONObject(Http.text("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"))
        val cur = json.getJSONObject("current")
        icon(cur.getInt("weather_code")) to "${Math.round(cur.getDouble("temperature_2m"))}°"
    }.getOrNull()

    fun findCity(name: String): Triple<String, Float, Float>? = runCatching {
        val q = URLEncoder.encode(name, "UTF-8")
        val res = JSONObject(Http.text("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1"))
            .getJSONArray("results").getJSONObject(0)
        Triple(res.getString("name"), res.getDouble("latitude").toFloat(), res.getDouble("longitude").toFloat())
    }.getOrNull()

    private fun icon(code: Int) = when (code) {
        0 -> R.drawable.lc_sun
        1, 2 -> R.drawable.lc_cloud_sun
        3 -> R.drawable.lc_cloud
        45, 48 -> R.drawable.lc_cloud_fog
        in 51..67, in 80..82 -> R.drawable.lc_cloud_rain
        in 71..77, 85, 86 -> R.drawable.lc_cloud_snow
        in 95..99 -> R.drawable.lc_cloud_lightning
        else -> R.drawable.lc_cloud
    }
}
