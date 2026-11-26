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

    private fun fireTvCommand(script: String, vararg args: String): String? = runCatching {
        // Fire OS 7 supplies toybox timeout. The shell merges stderr and emits only filtered output.
        val process = shell("timeout", "-k", "0.1s", "1s", "sh", "-c", script, "lead", *args)
            ?: return null
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.takeIf { process.waitFor() == 0 }
        } finally {
            process.destroy()
        }
    }.getOrNull()

    fun startFireTvHome(stockHome: String, packageName: String): String? = fireTvCommand(
        """
        focus=$(timeout 0.55s dumpsys window visible 2>/dev/null | grep -m 1 mCurrentFocus=)
        case "${'$'}focus" in
          *" ${'$'}1}") exec cmd activity start-activity -n "${'$'}2" 2>&1 ;;
          *) printf 'Foreground changed: %s\n' "${'$'}focus" ;;
        esac
        """.trimIndent(),
        stockHome,
        "$packageName/.MainActivity"
    )?.trim()

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
