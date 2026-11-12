package com.glass.launcher

import android.app.Activity
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
}
