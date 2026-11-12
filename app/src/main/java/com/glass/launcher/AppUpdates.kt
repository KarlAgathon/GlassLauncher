package com.glass.launcher

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether the other apps on the box have a newer version.
 *  - apps with a GitHub source (known ones, or one the user typed in): newest release → direct download + install
 *  - everything else: version shown on the Google Play page → opens the store page (Play Store or Aurora Store)
 */
object AppUpdates {
    data class Update(
        val pkg: String, val label: String, val local: String, val remote: String,
        /** "github" = direct install, "play" = open store page */
        val source: String, val apk: String,
    )

    /** open-source apps whose releases live on GitHub (package → owner/repo) */
    private val KNOWN = mapOf(
        "com.liskovsoft.smarttubetv.beta" to "yuliskov/SmartTube",
        "com.teamsmart.videomanager.tv" to "yuliskov/SmartTube",
        "moe.shizuku.privileged.api" to "thedjchi/Shizuku",
    )

    private fun store(ctx: Context) = ctx.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ user settings

    /** GitHub source the user set for an app (owner/repo), or the built-in one */
    fun githubFor(ctx: Context, pkg: String): String? =
        store(ctx).getString("gh_$pkg", null)?.takeIf { it.isNotBlank() } ?: KNOWN[pkg]

    fun setGithub(ctx: Context, pkg: String, repo: String) {
        val clean = repo.trim().removePrefix("https://").removePrefix("github.com/").trim('/')
        store(ctx).edit().putString("gh_$pkg", clean).apply()
    }

    fun ignore(ctx: Context, u: Update) {
        store(ctx).edit().putString("ign_${u.pkg}", u.remote).apply()
        save(ctx, list(ctx).filter { it.pkg != u.pkg })
    }

    var Context.intervalHours: Int
        get() = store(this).getInt("everyH", 1)
        set(v) { store(this).edit().putInt("everyH", v).apply() }

    fun lastCheck(ctx: Context) = store(ctx).getLong("last", 0L)

    // ------------------------------------------------------------------ results

    fun list(ctx: Context): List<Update> {
        val raw = store(ctx).getString("list", "") ?: ""
        val pm = ctx.packageManager
        return raw.lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 6) return@mapNotNull null
            // already updated in the meantime?
            val now = runCatching { pm.getPackageInfo(p[0], 0).versionName }.getOrNull() ?: return@mapNotNull null
            if (!newer(p[3], now)) null else Update(p[0], p[1], now, p[3], p[4], p[5])
        }
    }

    private fun save(ctx: Context, l: List<Update>) {
        store(ctx).edit().putString("list", l.joinToString("\n") {
            listOf(it.pkg, it.label, it.local, it.remote, it.source, it.apk).joinToString("\t") { s -> s.replace('\t', ' ').replace('\n', ' ') }
        }).apply()
    }

    // ------------------------------------------------------------------ checking

    /** true when [remote] is a higher version than [local] (compares the numbers in it) */
    fun newer(remote: String, local: String): Boolean {
        if (remote.isBlank() || remote.trim() == local.trim()) return false
        val r = Regex("\\d+").findAll(remote).map { it.value.toLongOrNull() ?: 0L }.toList()
        val l = Regex("\\d+").findAll(local).map { it.value.toLongOrNull() ?: 0L }.toList()
        if (r.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0L }; val b = l.getOrElse(i) { 0L }
            if (a != b) return a > b
        }
        return false
    }

    private fun get(url: String, limit: Int = 4_000_000, stopAt: Regex? = null): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        if (c.responseCode != 200) { c.disconnect(); return null }
        val sb = StringBuilder()
        c.inputStream.bufferedReader().use { r ->
            val buf = CharArray(16 * 1024)
            while (sb.length < limit) {
                val n = r.read(buf); if (n < 0) break
                sb.append(buf, 0, n)
                // stop downloading the (big) Play page as soon as the version is in
                if (stopAt != null && sb.length > 50_000 && stopAt.containsMatchIn(sb.substring(maxOf(0, sb.length - n - 400)))) break
            }
        }
        c.disconnect()
        sb.toString()
    }.getOrNull()

    /** version text on the Play page sits in data like [[["1.2.3"]],[[[24,"7.0"]],[[[34,"14"]]]] */
    private val PLAY_VERSION = Regex("""\[\[\["([0-9][^"\]]{0,60})"]],\[\[\[\d+,""")

    private fun playVersion(pkg: String): String? {
        val html = get("https://play.google.com/store/apps/details?id=$pkg&hl=en&gl=US", stopAt = PLAY_VERSION) ?: return null
        return PLAY_VERSION.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun github(repo: String): Pair<String, String>? {
        val txt = get("https://api.github.com/repos/$repo/releases/latest", 2_000_000) ?: return null
        val j = JSONObject(txt)
        val tag = j.optString("tag_name").ifBlank { j.optString("name") }
        val assets = j.optJSONArray("assets") ?: return tag to ""
        val abi = (Build.SUPPORTED_ABIS.firstOrNull() ?: "").lowercase()
        val want = if (abi.startsWith("arm64")) listOf("arm64", "v8a") else if (abi.startsWith("armeabi")) listOf("armeabi", "v7a", "armv7", "arm32") else listOf(abi)
        var best = ""; var bestScore = Int.MIN_VALUE
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            var s = 0
            if (want.any { it in name }) s += 3
            if ("universal" in name || "all" in name) s += 2
            if (listOf("x86", "arm64", "armeabi", "v7a", "v8a").any { it in name } && want.none { it in name }) s -= 6
            if ("debug" in name) s -= 2
            if (s > bestScore) { bestScore = s; best = a.optString("browser_download_url") }
        }
        return tag to best
    }

    /** apps the user installed himself (no built-in system apps), without LEAD itself */
    private fun userApps(ctx: Context): List<Triple<String, String, String>> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if (pi.packageName == ctx.packageName) return@mapNotNull null
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
            Triple(pi.packageName, ai.loadLabel(pm).toString(), pi.versionName ?: "")
        }
    }

    /** Checks all apps (takes a while – call from a background thread). Returns the apps with a new version. */
    fun checkAll(ctx: Context): List<Update> {
        val st = store(ctx)
        val found = mutableListOf<Update>()
        for ((pkg, label, local) in userApps(ctx)) {
            val gh = githubFor(ctx, pkg)
            val u = if (gh != null) {
                github(gh)?.let { (tag, apk) -> Update(pkg, label, local, tag.removePrefix("v"), if (apk.isBlank()) "play" else "github", apk) }
            } else {
                playVersion(pkg)?.let { Update(pkg, label, local, it, "play", "") }
            } ?: continue
            if (!newer(u.remote, local)) continue
            if (st.getString("ign_$pkg", null) == u.remote) continue
            found.add(u)
        }
        save(ctx, found)
        st.edit().putLong("last", System.currentTimeMillis()).apply()
        return found
    }

    /** called by the hourly alarm: checks when it is time, then notifies about new findings */
    fun checkIfDue(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.appUpdates) return
        val every = ctx.intervalHours * 3600_000L
        if (System.currentTimeMillis() - lastCheck(ctx) < every - 5 * 60_000L) return
        val l = checkAll(ctx)
        if (l.isNotEmpty()) notify(ctx, l)
    }

    // ------------------------------------------------------------------ notification

    private const val CHANNEL = "updates"

    fun notify(ctx: Context, l: List<Update>) {
        val st = store(ctx)
        val key = l.sortedBy { it.pkg }.joinToString(",") { it.pkg + ":" + it.remote }
        if (st.getString("notified", "") == key) return   // same list already announced
        st.edit().putString("notified", key).apply()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                T.s("Updates", "Ažuriranja", "Updates"), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(ctx, 3,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_APP_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = T.s("App updates: ", "Ažuriranja aplikacija: ", "App-Updates: ") + l.size
        val text = l.joinToString(", ") { it.label + " " + it.remote }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL)
        else android.app.Notification.Builder(ctx).setPriority(android.app.Notification.PRIORITY_HIGH)
        val n = b.setSmallIcon(R.drawable.lc_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(78, n) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            HomeService.instance?.showBanner(title + " – " + l.take(3).joinToString(", ") { it.label })
        }
    }

    // ------------------------------------------------------------------ updating one app

    /** GitHub: download and install directly. Play: open the store page (Play Store / Aurora Store). */
    fun update(act: Activity, u: Update, progress: (String) -> Unit) {
        if (u.source == "github" && u.apk.isNotBlank()) {
            Thread {
                try {
                    val dir = File(act.cacheDir, "app-updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, u.pkg + ".apk")
                    val c = URL(u.apk).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 30_000
                    val total = c.contentLengthLong
                    var got = 0L; var lastPct = -1
                    c.inputStream.use { inp -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (total > 0) { val pct = (got * 100 / total).toInt(); if (pct != lastPct) { lastPct = pct; act.runOnUiThread { progress("${u.label}  $pct %") } } }
                        }
                    } }
                    c.disconnect()
                    act.runOnUiThread { progress(T.s("Installing ", "Instaliram ", "Installiere ") + u.label) }
                    AppInstaller.start(act, listOf(u.pkg to listOf(f)))
                } catch (e: Exception) {
                    act.runOnUiThread { progress(T.s("Download failed: ", "Preuzimanje nije uspjelo: ", "Download fehlgeschlagen: ") + u.label) }
                }
            }.start()
        } else {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${u.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${u.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { act.startActivity(market) }.isFailure && runCatching { act.startActivity(web) }.isFailure)
                progress(T.s("No app store installed (Play Store or Aurora Store)", "Nema prodavnice aplikacija (Play Store ili Aurora Store)",
                    "Kein App-Store installiert (Play Store oder Aurora Store)"))
        }
    }
}
