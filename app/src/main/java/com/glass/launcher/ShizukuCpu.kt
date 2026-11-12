package com.glass.launcher

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * CPU load through Shizuku (only used where Android hides it from apps, e.g. Google TV Streamer).
 * Shizuku runs with ADB "shell" rights; through it the launcher reads /proc/stat like `top` does.
 */
object ShizukuCpu {
    const val REQ = 4711
    private var lastTotal = 0L
    private var lastIdle = 0L

    /** Shizuku is running on this box */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permitted(): Boolean = running() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Asks Shizuku (once) to let LEAD Launcher use it. */
    fun ask() {
        if (!running() || permitted()) return
        runCatching { if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQ) }
    }

    /** CPU % or null. Blocking (a few ms). */
    fun percent(): Int? {
        if (!permitted()) return null
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("head", "-1", "/proc/stat"), null, null) as Process
            val line = p.inputStream.bufferedReader().use { it.readLine() } ?: return null
            p.waitFor()
            val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            val idle = parts[3] + parts.getOrElse(4) { 0L }
            val total = parts.sum()
            val dt = total - lastTotal
            val di = idle - lastIdle
            val first = lastTotal == 0L
            lastTotal = total; lastIdle = idle
            if (first || dt <= 0) null else (100 * (dt - di) / dt).toInt().coerceIn(0, 100)
        }.getOrNull()
    }
}
