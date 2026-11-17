package com.glass.launcher

import android.app.Activity
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Icons that come with LEAD: put on the matching apps automatically (once – your own changes stay). */
object BuiltinIcons {
    private val MAP = mapOf(
        DefaultApps.PRETORIAN to R.drawable.builtin_pretorian_iptv,
        DefaultApps.NETFLIX to R.drawable.builtin_netflix,
        "ar.tvplayer.tv" to R.drawable.builtin_tivimate,
        "com.google.android.youtube.tv" to R.drawable.builtin_youtube,
        "com.teamsmart.videomanager.tv" to R.drawable.builtin_youtube,
        "com.liskovsoft.smarttubetv" to R.drawable.builtin_youtube,
        "com.liskovsoft.smarttubetv.beta" to R.drawable.builtin_youtube,
        "io.gh.reisxd.tizentube.cobalt" to R.drawable.builtin_youtube_premium,
        "com.disney.disneyplus" to R.drawable.builtin_disney,
        "com.nuvio.tv" to R.drawable.builtin_nuvio,
        "org.siloserver.silo" to R.drawable.builtin_silo,
        "com.nordvpn.android" to R.drawable.builtin_nordvpn,
    )
    /** raise when the pictures in the app change: the new ones then replace the old built-in ones once */
    private const val VERSION = 2

    /** Copies each built-in icon to the app (new pictures replace older built-in ones). */
    fun seed(ctx: Context): Boolean {
        val g = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        val key = "seededIcons$VERSION"
        val done = (g.getString(key, "") ?: "").split(',').filter { it.isNotBlank() }.toMutableSet()
        var changed = false
        for ((pkg, res) in MAP) {
            if (pkg in done) continue
            runCatching {
                ctx.resources.openRawResource(res).use { inp -> IconStore.file(ctx, pkg).outputStream().use { inp.copyTo(it) } }
                changed = true
            }
            done.add(pkg)
        }
        // new PRETORIAN logo (blue / silver, no red): replace the old one once on every box
        if (!g.getBoolean("pretorianLogo2", false)) {
            if (restore(ctx, DefaultApps.PRETORIAN)) changed = true
            g.edit().putBoolean("pretorianLogo2", true).apply()
        }
        g.edit().putString(key, done.joinToString(",")).apply()
        return changed
    }

    /** puts the built-in icon back on one app (e.g. after you tried another one) */
    fun restore(ctx: Context, pkg: String): Boolean {
        val res = MAP[pkg] ?: return false
        return runCatching { ctx.resources.openRawResource(res).use { inp -> IconStore.file(ctx, pkg).outputStream().use { inp.copyTo(it) } }; true }.getOrDefault(false)
    }

    fun has(pkg: String) = pkg in MAP

    /** the built-in picture itself (for apps that are not installed yet) */
    fun bitmap(ctx: Context, pkg: String, w: Int, h: Int): android.graphics.Bitmap? {
        val f = IconStore.file(ctx, pkg)
        if (f.exists()) Bitmaps.decodeScaled(f, w, h)?.let { return it }
        val res = MAP[pkg] ?: return null
        return runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, res) }.getOrNull()
    }
}

/** The four apps every fresh LEAD starts with. Missing ones keep their place: a click installs them from the official source. */
object DefaultApps {
    const val PRETORIAN = "com.pretorian.tv"
    const val PRETORIAN_REPO = "KarlAgathon/PRETORIAN"
    const val NETFLIX = "com.netflix.ninja"
    const val DISNEY = "com.disney.disneyplus"
    /** the YouTube of LEAD: TizenTube (no ads), always with the YouTube Premium icon */
    const val TIZENTUBE = "io.gh.reisxd.tizentube.cobalt"
    const val TIZENTUBE_REPO = "reisxd/TizenTubeCobalt"
    const val YOUTUBE_OFFICIAL = "com.google.android.youtube.tv"

    fun isDefault(pkg: String) = pkg == PRETORIAN || pkg == NETFLIX || pkg == DISNEY || pkg == TIZENTUBE

    fun name(pkg: String) = when (pkg) {
        PRETORIAN -> "PRETORIAN IPTV"; NETFLIX -> "Netflix"; DISNEY -> "Disney+"; TIZENTUBE -> "Premium"; else -> "YouTube"
    }

    /** GitHub repo a default app is installed from (newest stable release), or null = Google Play */
    fun repo(pkg: String) = when (pkg) { PRETORIAN -> PRETORIAN_REPO; TIZENTUBE -> TIZENTUBE_REPO; else -> null }

    @Suppress("UNUSED_PARAMETER")
    fun list(ctx: Context) = listOf(PRETORIAN, TIZENTUBE, DISNEY, NETFLIX)

    /**
     * TizenTube is LEAD's YouTube in every look: it takes the official YouTube's place on the home screen
     * (the official one is only taken off the home screen – you can add it again any time).
     */
    fun putTizenTube(pinned: List<String>): List<String> {
        val l = pinned.toMutableList()
        val at = l.indexOf(YOUTUBE_OFFICIAL)
        l.remove(YOUTUBE_OFFICIAL)
        if (TIZENTUBE !in l) l.add(if (at >= 0) at.coerceAtMost(l.size) else 1.coerceAtMost(l.size), TIZENTUBE)
        return l
    }
}

/** Add-ons LEAD can fetch and install by itself (from their official GitHub releases). */
object Extras {
    const val SOFADROP = "com.rfmarcos.sofadrop"
    const val SOFADROP_APK = "https://github.com/rfmarcos/sofadrop/releases/latest/download/sofadrop.apk"
    const val SHIZUKU = "moe.shizuku.privileged.api"

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** downloads [url] (following GitHub's redirects) and starts the install; [msg] gets progress text */
    fun install(act: Activity, pkg: String, url: String?, msg: (String) -> Unit) {
        Thread {
            try {
                val real = url ?: when (pkg) {
                    SHIZUKU -> AppUpdates.github("thedjchi/Shizuku")?.second
                    // PRETORIAN / TizenTube: always the newest STABLE release (GitHub's "latest" never points to a beta)
                    else -> DefaultApps.repo(pkg)?.let { AppUpdates.github(it)?.second }
                }
                if (real.isNullOrBlank()) throw IllegalStateException()
                val f = File(act.cacheDir, "$pkg.apk")
                var u = URL(real); var hops = 0
                var c: HttpURLConnection
                while (true) {
                    c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
                    if (c.responseCode in 300..399 && hops++ < 6) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
                    break
                }
                val total = c.contentLengthLong
                var got = 0L; var last = -1
                c.inputStream.use { inp -> f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0) { val p = (got * 100 / total).toInt(); if (p != last) { last = p; act.runOnUiThread { msg("$p %") } } }
                    }
                } }
                c.disconnect()
                act.runOnUiThread { AppInstaller.start(act, listOf(pkg to listOf(f))) }
            } catch (e: Exception) {
                act.runOnUiThread { msg(T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen")) }
            }
        }.start()
    }
}
