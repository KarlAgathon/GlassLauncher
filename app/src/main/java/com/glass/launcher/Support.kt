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
    var showLabels: Boolean get() = bool("labels3", false); set(v) = putBool("labels3", v)

    // ---- tiles (v2.1)
    /** 0 wide 16:9 (banner), 1 square */
    var tileShape: Int get() = int("tShape", 0); set(v) = putInt("tShape", v)
    /** 0 original colours, 1 white logos ("white snow") */
    var logoWhite: Boolean get() = bool("tWhite", false); set(v) = putBool("tWhite", v)
    /** remove the flat background behind a logo/banner */
    var logoNoBg: Boolean get() = bool("tNoBg", true); set(v) = putBool("tNoBg", v)
    /** 0..100 tile background */
    var tileAlpha: Int get() = int("tAlpha", 6); set(v) = putInt("tAlpha", v)
    /** frosted (blurred) wallpaper inside tiles; off = clear liquid glass */
    var tileFrost: Boolean get() = bool("tFrost", false); set(v) = putBool("tFrost", v)
    /** 0 none, 1 thin line, 2 glass rim */
    var tileBorder: Int get() = int("tBorder", 0); set(v) = putInt("tBorder", v)
    /** corner radius in % of tile height: 0 = square corners */
    var tileCorner: Int get() = int("tCorner", 16); set(v) = putInt("tCorner", v)
    /** 0 S, 1 M, 2 L, 3 XL */
    var tileSize: Int get() = int("tSize2", 2); set(v) = putInt("tSize2", v)
    /** gap between tiles in dp */
    var tileGap: Int get() = int("tGap", 12); set(v) = putInt("tGap", v)
    /** 0 FIT, 1 STRETCH */
    var logoFit: Int get() = int("tFit", 0); set(v) = putInt("tFit", v)
    /** focused tile grows by this many % */
    var focusScale: Int get() = int("tFocus", 30); set(v) = putInt("tFocus", v)
    /** 0 left, 1 center, 2 right */
    var gridAlign: Int get() = int("gridAlign", 0); set(v) = putInt("gridAlign", v)
    /** all tiles sit in one big glass bar (Apple TV look) */
    var dock: Boolean get() = bool("tDock", false); set(v) = putBool("tDock", v)
    /** apps always in one row that scrolls left/right */
    var singleRow: Boolean get() = bool("oneRow2", true); set(v) = putBool("oneRow2", v)
    /** show the "+" tile at the end of the row (always shown while the home screen is empty) */
    var showAddTile: Boolean get() = bool("addTileEnd", true); set(v) = putBool("addTileEnd", v)
    /** status bar glass adapts to the brightness of the wallpaper behind it */
    var statusAdaptive: Boolean get() = bool("sAdaptive", true); set(v) = putBool("sAdaptive", v)

    /** tile size in dp: (width, height) */
    fun tileDp(): Pair<Int, Int> {
        val wide = listOf(150 to 84, 184 to 104, 220 to 124, 260 to 146)
        val sq = listOf(88, 108, 128, 152)
        val i = tileSize.coerceIn(0, 3)
        return if (tileShape == 0) wide[i] else sq[i] to sq[i]
    }

    // ---- changing wallpaper
    /** 0 one picture, 1 slideshow from a folder, 2 films & series (TMDB) */
    var wpMode: Int get() = int("wpMode", 0); set(v) = putInt("wpMode", v)
    var wpFolder: String get() = str("wpFolder", ""); set(v) = putStr("wpFolder", v)
    /** seconds between pictures */
    var wpInterval: Int get() = int("wpInt", 60); set(v) = putInt("wpInt", v)
    var tmdbKey: String get() = str("tmdbKey", ""); set(v) = putStr("tmdbKey", v)
    /** "all", "movie" or "tv" */
    var tmdbType: String get() = str("tmdbType", "all"); set(v) = putStr("tmdbType", v)
    var showMovieInfo: Boolean get() = bool("movieInfo", true); set(v) = putBool("movieInfo", v)

    fun applyPreset(p: Int) {
        when (p) {
            0 -> { tileShape = 0; logoWhite = false; logoNoBg = true; tileAlpha = 22; tileBorder = 0; dock = false; showLabels = false }
            1 -> { tileShape = 0; logoWhite = true; logoNoBg = true; tileAlpha = 12; tileBorder = 1; dock = true; showLabels = false }
            2 -> { tileShape = 0; logoWhite = true; logoNoBg = true; tileAlpha = 0; tileBorder = 0; dock = false; showLabels = false }
            3 -> { tileShape = 1; logoWhite = false; logoNoBg = false; tileAlpha = 18; tileBorder = 2; dock = false; showLabels = true }
        }
    }
    var blurWallpaper: Boolean get() = bool("blur", false); set(v) = putBool("blur", v)

    var showClock: Boolean get() = bool("sClock", true); set(v) = putBool("sClock", v)
    var showDate: Boolean get() = bool("sDate", true); set(v) = putBool("sDate", v)
    var showWeather: Boolean get() = bool("sWeather", true); set(v) = putBool("sWeather", v)
    var showNetwork: Boolean get() = bool("sNet", true); set(v) = putBool("sNet", v)
    var showVpn: Boolean get() = bool("sVpn", true); set(v) = putBool("sVpn", v)
    var showBluetooth: Boolean get() = bool("sBt", true); set(v) = putBool("sBt", v)
    var showNotifs: Boolean get() = bool("sNotif", true); set(v) = putBool("sNotif", v)
    var showCpu: Boolean get() = bool("sCpu", true); set(v) = putBool("sCpu", v)
    var showRam: Boolean get() = bool("sRam", true); set(v) = putBool("sRam", v)

    var city: String get() = str("city", "Neumünster"); set(v) = putStr("city", v)
    var lat: Float get() = sp.getFloat("lat", 54.0717f); set(v) = sp.edit().putFloat("lat", v).apply()
    var lon: Float get() = sp.getFloat("lon", 9.9900f); set(v) = sp.edit().putFloat("lon", v).apply()

    /** URL of update.json (see README) */
    var updateUrl: String get() = str("updateUrl", "https://github.com/KarlAgathon/GlassLauncher/releases/latest/download/update.json"); set(v) = putStr("updateUrl", v)
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
        val d = ctx.resources.displayMetrics.density
        val (tw, th) = prefs.tileDp()
        val wPx = (tw * d).toInt()
        val hPx = (th * d).toInt()
        val size = maxOf(iconSize(ctx), (th * d * 1.4f).toInt())
        val style = prefs.iconStyle
        val wide = prefs.tileShape == 0
        return prefs.pinned.mapNotNull { p ->
            val own = IconStore.file(ctx, p).takeIf { it.exists() }?.let { Bitmaps.decodeScaled(it, wPx * 2, hPx * 2) }
            if (p == AppEntry.FILES) {
                val name = prefs.label(p) ?: T.s("Files", "Fajlovi", "Dateien")
                val icon = own ?: if (wide) {
                    val art = TileArt.iconAndName(ctx, ctx.getDrawable(R.drawable.lc_folder)?.let { dr ->
                        android.graphics.drawable.BitmapDrawable(ctx.resources, IconRender.tinted(dr, size, Color.rgb(255, 197, 61)))
                    }, name, wPx, hPx, prefs)
                    if (prefs.logoWhite) IconRender.whiten(art) else art
                } else filesIcon(ctx, size)
                return@mapNotNull AppEntry(p, name, icon)
            }
            val ri = all[p] ?: return@mapNotNull null
            val label = prefs.label(p) ?: ri.loadLabel(pm).toString()
            val icon = own?.let { if (prefs.logoWhite) IconRender.whiten(it) else it }
                ?: runCatching {
                    if (wide) TileArt.wide(ctx, ri, label, null, wPx, hPx, prefs)
                    else IconRender.render(ri.loadIcon(pm), size, if (prefs.logoWhite) 2 else style)
                }.getOrNull()
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
    /** Average brightness 0..1 of a region (fractions of the picture). */
    fun brightness(b: Bitmap, l: Float, t: Float, r: Float, btm: Float): Float {
        val x0 = (b.width * l).toInt(); val x1 = (b.width * r).toInt().coerceAtMost(b.width)
        val y0 = (b.height * t).toInt(); val y1 = (b.height * btm).toInt().coerceAtMost(b.height)
        var sum = 0.0; var n = 0
        val stepX = maxOf(1, (x1 - x0) / 40); val stepY = maxOf(1, (y1 - y0) / 20)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = b.getPixel(x, y)
                sum += (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)) / 255.0
                n++; x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0.3f else (sum / n).toFloat()
    }

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

/** Builds the picture shown inside a tile (banner, logo, or icon + name). */
object TileArt {
    fun drawableToBitmap(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(maxOf(1, w), maxOf(1, h), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, b.width, b.height)
        d.draw(Canvas(b))
        return b
    }

    /** Makes the flat background of a logo transparent (colour taken from the corners). */
    fun removeBackground(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        if (corners.any { (it ushr 24) < 200 }) return src          // already transparent
        val r0 = corners.map { (it shr 16) and 0xFF }.average()
        val g0 = corners.map { (it shr 8) and 0xFF }.average()
        val b0 = corners.map { it and 0xFF }.average()
        val spread = corners.maxOf { Math.abs(((it shr 16) and 0xFF) - r0) + Math.abs(((it shr 8) and 0xFF) - g0) + Math.abs((it and 0xFF) - b0) }
        if (spread > 60) return src                                    // not a flat background
        for (i in px.indices) {
            val c = px[i]
            val d = Math.abs(((c shr 16) and 0xFF) - r0) + Math.abs(((c shr 8) and 0xFF) - g0) + Math.abs((c and 0xFF) - b0)
            val a = ((d - 40) * 4).coerceIn(0.0, 255.0).toInt()
            val na = (a * (c ushr 24)) / 255
            px[i] = (na shl 24) or (c and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Wide tile picture for an app. */
    fun wide(ctx: Context, ri: ResolveInfo?, label: String, fallbackIcon: Drawable?, wPx: Int, hPx: Int, p: Prefs): Bitmap {
        val pm = ctx.packageManager
        val banner: Drawable? = ri?.let {
            runCatching { it.activityInfo.loadBanner(pm) ?: it.activityInfo.applicationInfo.loadBanner(pm) }.getOrNull()
        }
        var out: Bitmap
        if (banner != null) {
            val iw = if (banner.intrinsicWidth > 0) banner.intrinsicWidth else 320
            val ih = if (banner.intrinsicHeight > 0) banner.intrinsicHeight else 180
            val scale = maxOf(1f, (wPx * 1.4f) / iw)
            out = drawableToBitmap(banner, (iw * scale).toInt(), (ih * scale).toInt())
            if (p.logoNoBg || p.logoWhite) out = removeBackground(out)
        } else {
            out = iconAndName(ctx, fallbackIcon ?: ri?.let { runCatching { it.loadIcon(pm) }.getOrNull() }, label, wPx, hPx, p)
        }
        return if (p.logoWhite) IconRender.whiten(out) else out
    }

    /** For apps without a TV banner: icon with the app name next to it. */
    fun iconAndName(ctx: Context, icon: Drawable?, label: String, wPx: Int, hPx: Int, p: Prefs): Bitmap {
        val W = (wPx * 1.4f).toInt()
        val H = (hPx * 1.4f).toInt()
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val iconS = (H * 0.46f).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Fonts.medium(ctx)
            textSize = H * 0.20f
        }
        var tw = paint.measureText(label)
        val maxText = W * 0.92f - iconS - H * 0.08f
        while (tw > maxText && paint.textSize > H * 0.11f) { paint.textSize *= 0.92f; tw = paint.measureText(label) }
        val shown = if (tw > maxText) label.take(12) + "…" else label
        tw = paint.measureText(shown)
        val total = iconS + H * 0.08f + tw
        val x0 = (W - total) / 2f
        if (icon != null) {
            val ib = IconRender.render(icon, iconS, p.iconStyle)
            c.drawBitmap(ib, x0, (H - iconS) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val fm = paint.fontMetrics
        c.drawText(shown, x0 + iconS + H * 0.08f, H / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return b
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

object NetDetails {
    /** Lines like "IP: 192.168.1.50", "Signal: 78%". */
    @Suppress("DEPRECATION")
    fun lines(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        lp?.linkAddresses?.map { it.address }?.filter { it is java.net.Inet4Address }?.forEach { out.add("IP: ${it.hostAddress}") }
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress?.let { out.add("Gateway: $it") }
        lp?.dnsServers?.take(2)?.forEach { out.add("DNS: ${it.hostAddress}") }
        val st = NetInfo.read(ctx)
        if (st.wifi && !st.eth) {
            runCatching {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                val pct = WifiManager.calculateSignalLevel(info.rssi, 101)
                out.add("Signal: $pct%  (${info.rssi} dBm)")
                out.add("${T.s("Speed", "Brzina", "Geschwindigkeit")}: ${info.linkSpeed} Mbps")
            }
        }
        return out
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

    fun text(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 GlassLauncher")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (headers.isEmpty()) return open(url).inputStream.bufferedReader().use { it.readText() }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

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

    /** Next days: (day name, icon, "max° / min°"). Call off the main thread. */
    fun forecast(lat: Float, lon: Float): List<Triple<String, Int, String>> = runCatching {
        val j = JSONObject(Http.text("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5"))
            .getJSONObject("daily")
        val days = j.getJSONArray("time"); val codes = j.getJSONArray("weather_code")
        val mx = j.getJSONArray("temperature_2m_max"); val mn = j.getJSONArray("temperature_2m_min")
        val inFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val outFmt = java.text.SimpleDateFormat("EEEE", T.locale)
        (0 until days.length()).map { i ->
            val name = outFmt.format(inFmt.parse(days.getString(i))!!).replaceFirstChar { it.uppercase() }
            Triple(name, icon(codes.getInt(i)), "${Math.round(mx.getDouble(i))}° / ${Math.round(mn.getDouble(i))}°")
        }
    }.getOrDefault(emptyList())

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


/* ================================================================ films & series wallpaper (TMDB) */

data class Movie(val title: String, val year: String, val rating: String, val kind: String, val overview: String, val image: String)

object Movies {
    /** Trending films/series. key = TMDB API key (v3) or read-access token (v4). Call off the main thread. */
    fun trending(key: String, type: String): List<Movie> = runCatching {
        val lang = when (T.lang) { "bs" -> "bs-BA"; "de" -> "de-DE"; else -> "en-US" }
        val t = if (type == "movie" || type == "tv") type else "all"
        val v4 = key.length > 40
        val url = "https://api.themoviedb.org/3/trending/$t/week?language=$lang" + if (v4) "" else "&api_key=$key"
        val headers = if (v4) mapOf("Authorization" to "Bearer $key", "accept" to "application/json") else emptyMap()
        val res = JSONObject(Http.text(url, headers)).getJSONArray("results")
        (0 until res.length()).mapNotNull { i ->
            val o = res.getJSONObject(i)
            val bd = o.optString("backdrop_path", "")
            if (bd.isBlank() || bd == "null") return@mapNotNull null
            val isTv = o.optString("media_type") == "tv" || (t == "tv")
            val title = o.optString(if (isTv) "name" else "title", o.optString("title", o.optString("name", "")))
            val date = o.optString(if (isTv) "first_air_date" else "release_date", "")
            val rating = o.optDouble("vote_average", 0.0)
            Movie(title, date.take(4), if (rating > 0) String.format(java.util.Locale.US, "%.1f", rating) else "",
                if (isTv) T.s("Series", "Serija", "Serie") else T.s("Movie", "Film", "Film"),
                o.optString("overview", ""), "https://image.tmdb.org/t/p/w1280$bd")
        }
    }.getOrDefault(emptyList())
}


/** Bluetooth state for the status bar: on/off, paired devices and which of them are connected. */
data class BtDevice(val name: String, val connected: Boolean, val device: android.bluetooth.BluetoothDevice)
data class BtState(val available: Boolean, val on: Boolean, val devices: List<BtDevice>) {
    val connectedCount get() = devices.count { it.connected }
}

object BtInfo {
    @Suppress("DEPRECATION", "MissingPermission")
    fun adapter(ctx: android.content.Context): android.bluetooth.BluetoothAdapter? =
        runCatching { (ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter }
            .getOrNull() ?: runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    @Suppress("MissingPermission")
    fun read(ctx: android.content.Context): BtState {
        val a = adapter(ctx) ?: return BtState(false, false, emptyList())
        val on = runCatching { a.isEnabled }.getOrDefault(false)
        val devs = if (!on) emptyList() else runCatching {
            a.bondedDevices.orEmpty().map { d ->
                // isConnected is hidden but readable on Android TV boxes; ignore if it is blocked
                val c = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
                BtDevice(runCatching { d.name }.getOrNull() ?: d.address, c, d)
            }.sortedWith(compareBy({ !it.connected }, { it.name.lowercase() }))
        }.getOrDefault(emptyList())
        return BtState(true, on, devs)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun setEnabled(ctx: android.content.Context, enable: Boolean): Boolean {
        val a = adapter(ctx) ?: return false
        return runCatching { if (enable) a.enable() else a.disable() }.getOrDefault(false)
    }
}
