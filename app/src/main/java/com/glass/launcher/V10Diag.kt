package com.glass.launcher

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket

/** Diagnostics for v10: real measurements only, nothing runs while Diagnostics is closed. */
object NetQuality {
    data class Result(
        val target: String, val sent: Int, val ok: Int,
        val latencyMs: Int?, val jitterMs: Int?, val lossPct: Int,
        val connection: String, val viaVpn: Boolean, val score: Int?,
    )

    /** what kind of connection is active: Ethernet / Wi-Fi / … and whether a VPN carries the traffic */
    fun connection(ctx: Context): Pair<String, Boolean> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return T.s("No connection", "Nema veze", "Keine Verbindung", "Нет соединения") to false
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> T.s("Mobile", "Mobilna", "Mobil", "Мобильная")
            vpn -> "VPN"
            else -> T.s("Other", "Drugo", "Andere", "Другое")
        }
        return type to vpn
    }

    /**
     * Measures [count] TCP connections (port 443) one after another. Latency = median connect time, jitter = average
     * difference between neighbouring samples, loss = connections that did not answer within 2 s. Blocking – call
     * from a background thread.
     */
    fun measure(ctx: Context, host: String = "1.1.1.1", count: Int = 20): Result {
        val times = ArrayList<Int>()
        var fail = 0
        repeat(count) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val ok = runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, 443), 2000) } }.isSuccess
            if (ok) times.add(((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000).toInt()) else fail++
            Thread.sleep(150)
        }
        val (type, vpn) = connection(ctx)
        val lat = times.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val jit = if (times.size < 2) null else times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toInt()
        val loss = fail * 100 / count
        return Result(host, count, times.size, lat, jit, loss, type, vpn, score(lat, jit, loss))
    }

    /**
     * Score 0–100, shown together with its formula:
     * 100 − (latency above 20 ms) ÷ 4 − jitter × 1.5 − loss % × 4, never below 0.
     */
    fun score(lat: Int?, jit: Int?, loss: Int): Int? {
        if (lat == null) return null
        val s = 100 - (lat - 20).coerceAtLeast(0) / 4.0 - (jit ?: 0) * 1.5 - loss * 4.0
        return s.toInt().coerceIn(0, 100)
    }

    const val FORMULA = "100 − (latency − 20 ms) ÷ 4 − jitter × 1.5 − loss % × 4"
}

object DeviceHealth {
    /** label → value lines, measured now */
    fun lines(ctx: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        fun gb(b: Long) = String.format("%.1f GB", b / 1_073_741_824.0)
        out += T.s("Device", "Uređaj", "Gerät", "Устройство") to "${Build.MANUFACTURER} ${Build.MODEL}"
        out += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        out += T.s("Memory free", "Slobodna memorija", "Freier Speicher", "Свободная память") to "${gb(mi.availMem)} / ${gb(mi.totalMem)}" +
            if (mi.lowMemory) "  ⚠" else ""
        runCatching {
            val st = StatFs(ctx.filesDir.absolutePath)
            out += T.s("Storage free", "Slobodan prostor", "Freier Platz", "Свободно на диске") to "${gb(st.availableBytes)} / ${gb(st.totalBytes)}"
        }
        val up = SystemClock.elapsedRealtime() / 60_000
        out += T.s("Running for", "Radi već", "Läuft seit", "Работает") to "${up / 1440} d ${up / 60 % 24} h ${up % 60} min"
        ShizukuCpu.takeIf { it.permitted() }?.let { out += "CPU" to (runCatching { it.percent()?.let { u -> "$u %" } }.getOrNull() ?: "–") }
        runCatching {
            // first thermal zone that answers (not every box allows reading it)
            val t = java.io.File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toInt()
            out += T.s("Temperature", "Temperatura", "Temperatur", "Температура") to "${if (t > 1000) t / 1000 else t} °C"
        }
        out += T.s("CPU cores", "CPU jezgre", "CPU-Kerne", "Ядра CPU") to Runtime.getRuntime().availableProcessors().toString()
        return out
    }
}

/** Crash notes: the full error text of every LEAD crash, newest first (also pullable over adb). */
object CrashLog {
    fun dir(ctx: Context): java.io.File = (ctx.getExternalFilesDir("crash") ?: java.io.File(ctx.filesDir, "crash")).apply { mkdirs() }

    fun write(ctx: Context, t: Thread, e: Throwable) = runCatching {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        val f = java.io.File(dir(ctx), "crash_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt")
        f.writeText("LEAD ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\nThread: ${t.name}\n\n$sw")
        // keep the last 20
        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
    }

    fun list(ctx: Context) = dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
}
