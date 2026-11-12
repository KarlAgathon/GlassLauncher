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
    fun download(ctx: Context, info: Info): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
        val f = File(dir, "GlassLauncher-update.apk")
        Http.download(info.apk, f)
        return f
    }

    /** Opens the Android installer for an APK file. */
    fun install(act: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, T.s(
                "Allow installing apps, then try again",
                "Dozvoli instaliranje aplikacija pa pokušaj ponovo",
                "Installieren von Apps erlauben und erneut versuchen"), Toast.LENGTH_LONG).show()
            runCatching {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
            act.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(act, T.s("Can't open the installer", "Ne mogu otvoriti instalaciju", "Installer lässt sich nicht öffnen"),
                Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ "new version" notification

    private const val CHANNEL = "updates"

    fun notify(ctx: Context, info: Info) {
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
            .setContentTitle(T.s("New version available", "Nova verzija dostupna", "Neue Version verfügbar") + " – Glass Launcher " + info.name)
            .setContentText(info.notes.ifBlank { T.s("Open to install", "Otvori za instalaciju", "Zum Installieren öffnen") })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(77, n) }
    }

    /** Checks for a new version every 6 hours, also when the launcher is not open. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, UpdateCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 15 * 60 * 1000L,
            AlarmManager.INTERVAL_HOUR * 6, pi)
    }
}

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        T.init(prefs.lang)
        val url = prefs.updateUrl
        if (url.isBlank()) return
        val pending = goAsync()
        Thread {
            try {
                Updater.check(app, url)?.let { Updater.notify(app, it) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
