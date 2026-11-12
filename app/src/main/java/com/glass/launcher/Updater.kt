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
    data class Info(val code: Long, val name: String, val apk: String, val notes: String)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Blocking. Returns the newer version, or null if there is none / on error. */
    fun check(ctx: Context, url: String): Info? = runCatching {
        val j = JSONObject(Http.text(url))
        val info = Info(j.getLong("versionCode"), j.optString("versionName", "?"),
            j.getString("apk"), j.optString("notes", ""))
        if (info.code > currentCode(ctx)) info else null
    }.getOrNull()

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

    private const val CHANNEL = "updates"

    /** Remembers the newest version found, so the home screen can show the "new version" chip. */
    fun remember(ctx: Context, info: Info) {
        ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE).edit()
            .putLong("pendingCode", info.code).putString("pendingName", info.name)
            .putString("pendingApk", info.apk).putString("pendingNotes", info.notes).apply()
    }

    /** The remembered newer version, or null when the installed one is up to date. */
    fun pending(ctx: Context): Info? {
        val g = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        val code = g.getLong("pendingCode", 0L)
        if (code <= currentCode(ctx)) return null
        return Info(code, g.getString("pendingName", "?") ?: "?", g.getString("pendingApk", "") ?: "", g.getString("pendingNotes", "") ?: "")
    }

    fun notify(ctx: Context, info: Info) {
        remember(ctx, info)
        val g = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        if (g.getLong("notifiedCode", 0L) >= info.code) return
        g.edit().putLong("notifiedCode", info.code).apply()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                T.s("Updates", "Ažuriranja", "Updates"), NotificationManager.IMPORTANCE_HIGH))
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
        // also pop up a small banner on top of whatever is on screen (films, other apps)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            HomeService.instance?.showBanner(
                T.s("New version", "Nova verzija", "Neue Version") + " – LEAD Launcher " + info.name)
        }
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
            } finally {
                pending.finish()
            }
        }.start()
    }
}
