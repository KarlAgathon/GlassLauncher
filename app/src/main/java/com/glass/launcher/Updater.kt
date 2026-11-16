package com.glass.launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Update over the internet. The update URL points to a small JSON file:
 * { "versionCode": 2, "versionName": "1.1", "apk": "https://.../GlassLauncher.apk", "notes": "..." }
 */
object Updater {
    data class Info(val code: Long, val name: String, val apk: String, val notes: String, val sha256: String = "")

    /** Beta test versions: the pre-release "beta" on GitHub. Only boxes on the Beta channel look there. */
    const val BETA_URL = "https://github.com/KarlAgathon/GlassLauncher/releases/download/beta/update.json"

    private fun g(ctx: Context) = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)

    /** true = this box also gets Beta versions (every new version starts as Beta) */
    fun beta(ctx: Context): Boolean {
        // a box that was pointed to the beta address by hand (to get the first Beta) joins the Beta channel
        val p = Prefs(ctx)
        if ("/download/beta/" in p.updateUrl) {
            p.updateUrl = "https://github.com/KarlAgathon/GlassLauncher/releases/latest/download/update.json"
            g(ctx).edit().putBoolean("updBeta", true).putBoolean("updDev", true).apply()
        }
        return g(ctx).getBoolean("updBeta", false)
    }
    fun setBeta(ctx: Context, on: Boolean) = g(ctx).edit().putBoolean("updBeta", on).also { if (on) it.putBoolean("updDev", true) }.apply()

    /**
     * Only Svire's own boxes see the Beta options. A box becomes one by pressing "Installed version" 7 times,
     * or because it already gets Betas. Every other box only ever gets Stable versions.
     */
    fun devBox(ctx: Context): Boolean {
        if (g(ctx).getBoolean("updDev", false)) return true
        // a box that already gets Betas stays a Beta box, also after Beta is switched off
        if (beta(ctx)) { setDevBox(ctx, true); return true }
        return false
    }
    fun setDevBox(ctx: Context, on: Boolean) = g(ctx).edit().putBoolean("updDev", on).apply()

    /** forget a found Beta (after switching Beta off) */
    fun forgetPending(ctx: Context) {
        val p = pending(ctx) ?: return
        if (p.code > stableCode(ctx)) g(ctx).edit().remove("pendingCode").apply()
    }

    /** last known Stable version (versionCode, name) */
    fun stableCode(ctx: Context) = g(ctx).getLong("stableCode", 0L)
    fun stableName(ctx: Context) = g(ctx).getString("stableName", "") ?: ""

    /** the installed version is newer than the Stable one = it is a Beta */
    fun installedIsBeta(ctx: Context): Boolean = stableCode(ctx) in 1 until currentCode(ctx)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Blocking. Returns the newer version, or null if there is none / on error. */
    fun check(ctx: Context, url: String): Info? = runCatching {
        fun read(u: String): Info { val j = JSONObject(Http.text(u))
            return Info(j.getLong("versionCode"), j.optString("versionName", "?"), j.getString("apk"),
                j.optString("notes", ""), j.optString("sha256", "")) }
        val stable = read(url)
        g(ctx).edit().putLong("stableCode", stable.code).putString("stableName", stable.name).apply()
        // Beta channel: the test version when it is newer than the Stable one
        val best = if (beta(ctx)) runCatching { read(BETA_URL) }.getOrNull()?.takeIf { it.code > stable.code } ?: stable else stable
        if (best.code > currentCode(ctx)) best else null
    }.getOrNull()

    /**
     * Mark as Stable from the TV: the beta branch becomes master on GitHub, GitHub then builds the Stable release
     * for every box. Needs a GitHub token (saved once). Returns null when it worked, otherwise the reason.
     */
    fun promote(ctx: Context): String? {
        val tok = (g(ctx).getString("ghToken", "") ?: "").trim()
        if (tok.isBlank()) return T.s("No GitHub token", "Nema GitHub tokena", "Kein GitHub-Token")
        fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
            val c = java.net.URL("https://api.github.com/repos/KarlAgathon/GlassLauncher$path").openConnection() as java.net.HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $tok"); c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" }.getOrDefault("")
            c.disconnect(); return code to text
        }
        return try {
            val (c1, t1) = call("GET", "/git/ref/heads/beta")
            if (c1 != 200) return "GitHub: $c1"
            val sha = JSONObject(t1).getJSONObject("object").getString("sha")
            val (c2, t2) = call("PATCH", "/git/refs/heads/master", JSONObject().put("sha", sha).put("force", false).toString())
            if (c2 !in 200..299) "GitHub: $c2 " + runCatching { JSONObject(t2).optString("message") }.getOrDefault("") else null
        } catch (e: Exception) { e.message ?: "?" }
    }
    /** updates (LEAD + apps) show at the bell / as notifications – can be switched off */
    fun notifyOn(ctx: Context) = g(ctx).getBoolean("updNotif", true)
    fun setNotifyOn(ctx: Context, on: Boolean) = g(ctx).edit().putBoolean("updNotif", on).apply()

    fun hasToken(ctx: Context) = !(g(ctx).getString("ghToken", "") ?: "").isBlank()
    fun setToken(ctx: Context, t: String) = g(ctx).edit().putString("ghToken", t.trim()).apply()

    /** SHA-256 of a file as hex */
    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val buf = ByteArray(64 * 1024); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Blocking download of the new APK. */
    /** Where the APK of [info] is kept once downloaded. */
    fun apkFile(ctx: Context, info: Info): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
        return File(dir, "LEAD-update-${info.code}.apk")
    }

    /** The already downloaded APK of [info], or null. */
    fun ready(ctx: Context, info: Info): File? = apkFile(ctx, info).takeIf { it.isFile && it.length() > 100_000 }

    /** Blocking download of the new APK (skipped when it is already there). Old update files are removed. */
    fun download(ctx: Context, info: Info, progress: ((Int) -> Unit)? = null): File {
        ready(ctx, info)?.let { return it }
        val f = apkFile(ctx, info)
        f.parentFile?.listFiles()?.forEach { if (it.name.startsWith("LEAD-update-") || it.name.startsWith("GlassLauncher-update")) it.delete() }
        val tmp = File(f.parentFile, f.name + ".part")
        val c = Http.open(info.apk)
        val total = c.contentLength.toLong()
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0 && progress != null) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
        }
        // the file must be exactly the one GitHub built (SHA-256 from update.json) – otherwise it is thrown away
        if (info.sha256.isNotBlank() && !sha256(tmp).equals(info.sha256, ignoreCase = true)) {
            tmp.delete()
            throw java.io.IOException("SHA-256 mismatch")
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        return f
    }

    /** Opens the Android installer for an APK file. */
    fun install(act: Activity, f: File) {
        // Android 13+: an app installed through the old "open APK" way is marked "restricted" and its
        // helper service (VPN key, Home button) gets switched off. Installing through a PackageInstaller
        // session (like an app store does) avoids that – so use it whenever possible.
        val pkg = runCatching { act.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName }.getOrNull()
        if (pkg != null && (Build.VERSION.SDK_INT < 26 || act.packageManager.canRequestPackageInstalls())) {
            AppInstaller.start(act, listOf(pkg to listOf(f)))
            return
        }
        // open the Android installer right away; if this app may not install yet,
        // Android itself shows the "allow" switch on the way
        try {
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
            act.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(act, T.s(
                    "Allow installing apps, then try again",
                    "Dozvoli instaliranje aplikacija pa pokušaj ponovo",
                    "Installieren von Apps erlauben und erneut versuchen"), Toast.LENGTH_LONG).show()
                runCatching {
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
            } else {
                Toast.makeText(act, T.s("Can't open the installer", "Ne mogu otvoriti instalaciju", "Installer lässt sich nicht öffnen"),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ "new version" notification

    private const val CHANNEL = "updates_quiet"

    /** Remembers the newest version found, so the home screen can show the "new version" chip. */
    fun remember(ctx: Context, info: Info) {
        ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE).edit()
            .putLong("pendingCode", info.code).putString("pendingName", info.name)
            .putString("pendingApk", info.apk).putString("pendingNotes", info.notes).putString("pendingSha", info.sha256).apply()
    }

    /** The remembered newer version, or null when the installed one is up to date. */
    fun pending(ctx: Context): Info? {
        val g = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        val code = g.getLong("pendingCode", 0L)
        if (code <= currentCode(ctx)) return null
        return Info(code, g.getString("pendingName", "?") ?: "?", g.getString("pendingApk", "") ?: "", g.getString("pendingNotes", "") ?: "",
            g.getString("pendingSha", "") ?: "")
    }

    fun notify(ctx: Context, info: Info) {
        remember(ctx, info)
        if (!notifyOn(ctx)) return
        val g = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        if (g.getLong("notifiedCode", 0L) >= info.code) return
        g.edit().putLong("notifiedCode", info.code).apply()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                T.s("Updates", "Ažuriranja", "Updates"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL)
        else android.app.Notification.Builder(ctx).setPriority(android.app.Notification.PRIORITY_HIGH)
        val n = b.setSmallIcon(R.drawable.lc_download)
            .setContentTitle(T.s("New version available", "Nova verzija dostupna", "Neue Version verfügbar") + " – LEAD Launcher " + info.name)
            .setContentText(info.notes.ifBlank { T.s("Open to install", "Otvori za instalaciju", "Zum Installieren öffnen") })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(77, n) }
    }

    /** Checks for a new version every hour, also when the launcher is not open. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, UpdateCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 5 * 60 * 1000L,
            AlarmManager.INTERVAL_HOUR, pi)
    }
}

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        T.init(prefs.lang)
        val url = prefs.updateUrl
        val pending = goAsync()
        Thread {
            try {
                if (url.isNotBlank()) runCatching {
                    Updater.check(app, url)?.let { info ->
                        // download right away, so one click later only asks "install?"
                        runCatching { Updater.download(app, info) }
                        Updater.notify(app, info)
                    }
                }
                // the other apps on the box (low priority, so the remote stays quick)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runCatching { AppUpdates.checkIfDue(app) }
                runCatching { AppUpdates.nightUpdate(app) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
