package com.glass.launcher

import android.app.Activity
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Icons that come with LEAD: put on the matching apps automatically (once – your own changes stay). */
object BuiltinIcons {
    private val MAP = mapOf(
        "ar.tvplayer.tv" to R.drawable.builtin_tivimate,
        "com.google.android.youtube.tv" to R.drawable.builtin_youtube,
        "io.gh.reisxd.tizentube.cobalt" to R.drawable.builtin_youtube_premium,
        "com.disney.disneyplus" to R.drawable.builtin_disney,
        "com.nuvio.tv" to R.drawable.builtin_nuvio,
        "org.siloserver.silo" to R.drawable.builtin_silo,
    )

    /** Copies each built-in icon to the app once, unless you already set your own. */
    fun seed(ctx: Context): Boolean {
        val g = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        val done = (g.getString("seededIcons", "") ?: "").split(',').filter { it.isNotBlank() }.toMutableSet()
        var changed = false
        for ((pkg, res) in MAP) {
            if (pkg in done) continue
            val f = IconStore.file(ctx, pkg)
            if (!f.exists()) runCatching {
                ctx.resources.openRawResource(res).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                changed = true
            }
            done.add(pkg)
        }
        g.edit().putString("seededIcons", done.joinToString(",")).apply()
        return changed
    }

    /** puts the built-in icon back on one app (e.g. after you tried another one) */
    fun restore(ctx: Context, pkg: String): Boolean {
        val res = MAP[pkg] ?: return false
        return runCatching { ctx.resources.openRawResource(res).use { inp -> IconStore.file(ctx, pkg).outputStream().use { inp.copyTo(it) } }; true }.getOrDefault(false)
    }

    fun has(pkg: String) = pkg in MAP
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
                val real = url ?: if (pkg == SHIZUKU) AppUpdates.github("thedjchi/Shizuku")?.second else null
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
