package com.glass.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * Installs the apps of a full backup one after another (also apps made of several APK files).
 * Android shows its own "install?" question for every app; the next one starts after the answer.
 */
object AppInstaller {
    private val queue = ArrayDeque<Pair<String, List<File>>>()
    @Volatile private var total = 0
    @Volatile private var done = 0

    fun start(ctx: Context, apps: List<Pair<String, List<File>>>) {
        if (apps.isEmpty()) return
        queue.clear(); queue.addAll(apps)
        total = apps.size; done = 0
        next(ctx.applicationContext)
    }

    fun next(ctx: Context) {
        val item = queue.removeFirstOrNull() ?: run {
            if (total > 0) toast(ctx, T.s("Apps installed: ", "Instalirano aplikacija: ", "Apps installiert: ") + "$done / $total")
            total = 0
            return
        }
        Thread {
            // with Shizuku: install quietly in the background, no "Install?" question
            if (item.second.size == 1 && item.first != ctx.packageName && silent(item.second[0])) {
                done++
                next(ctx)
                return@Thread
            }
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(item.first)
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    for (f in item.second) {
                        f.inputStream().use { input ->
                            s.openWrite(f.name, 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) }
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java)
                        .putExtra("pkg", item.first), flags)
                    s.commit(cb.intentSender)
                }
            } catch (e: Exception) {
                toast(ctx, T.s("Couldn't install ", "Ne mogu instalirati ", "Kann nicht installieren: ") + item.first)
                next(ctx)
            }
        }.start()
    }

    /** "pm install" through Shizuku (shell rights), the APK is fed in through stdin */
    private fun silent(f: File): Boolean {
        if (!ShizukuCpu.permitted()) return false
        return runCatching {
            val p = ShizukuCpu.process("pm", "install", "-r", "-S", f.length().toString()) ?: return false
            p.outputStream.use { out -> f.inputStream().use { it.copyTo(out) } }
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            txt.contains("Success")
        }.getOrDefault(false)
    }

    fun finished(ctx: Context, ok: Boolean) {
        if (ok) done++
        next(ctx)
    }

    private fun toast(ctx: Context, s: String) =
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppInstaller.finished(ctx, true)
            else -> AppInstaller.finished(ctx, false)
        }
    }
}
