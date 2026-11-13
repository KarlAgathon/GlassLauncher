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

    private fun shell(vararg cmd: String): Process? = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(null, arrayOf(*cmd), null, null) as Process
    }.getOrNull()

    /** a shell process through Shizuku (null without permission) */
    fun process(vararg cmd: String): Process? = if (!permitted()) null else shell(*cmd)

    /** Runs a shell command through Shizuku; true if it could be started and ended with 0. */
    fun run(vararg cmd: String): Boolean {
        if (!permitted()) return false
        return runCatching { shell(*cmd)?.waitFor() == 0 }.getOrDefault(false)
    }

    /** Output of a shell command through Shizuku, or null. Blocking. */
    fun output(vararg cmd: String): String? {
        if (!permitted()) return null
        return runCatching {
            val p = shell(*cmd) ?: return null
            val t = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(); t
        }.getOrNull()
    }

    /** Force stop through Shizuku (apps can't do it themselves). */
    fun forceStop(pkg: String): Boolean {
        if (!permitted()) return false
        return runCatching { shell("am", "force-stop", pkg)?.waitFor() == 0 }.getOrDefault(false)
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
