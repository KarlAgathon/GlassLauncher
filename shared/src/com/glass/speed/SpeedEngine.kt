package com.glass.speed

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The measuring part of the LEAD speed test, shared by LEAD Launcher and the small "LEAD VPN test" helper app.
 * Every transfer uses the same explicitly selected Android network.
 */
class SpeedEngine(private val manager: ConnectivityManager, private val net: Network, private val vpn: Boolean) {
    @Volatile var stop = false

    fun routeValid(): Boolean {
        if (stop) return false
        if (!matchesRoute(manager, net, vpn)) stop = true
        return !stop
    }

    data class Where(val ip: String, val country: String)

    private fun open(url: String): HttpURLConnection {
        check(routeValid()) { "Test network unavailable" }
        val c = net.openConnection(URL(url), Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "LEAD-Speedtest")
        return c
    }

    /** public IPv4 address and country the traffic comes out from */
    fun where(): Where? = runCatching {
        // 1.1.1.1 = IPv4 only, so the address shown is the one the VPN changes
        val c = open("https://1.1.1.1/cdn-cgi/trace")
        val t = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val m = t.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        Where(m["ip"] ?: "?", m["loc"] ?: "")
    }.getOrNull()

    fun ping(): Int? {
        val times = mutableListOf<Long>()
        repeat(6) {
            runCatching {
                val t0 = SystemClock.elapsedRealtime()
                val c = open("https://speed.cloudflare.com/__down?bytes=0")
                try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                times.add(SystemClock.elapsedRealtime() - t0)
            }
        }
        if (times.isEmpty()) return null
        return times.drop(1).ifEmpty { times }.min().toInt()
    }

    /** 4 parallel downloads for ~10 s → Mbit/s; [sample] gets the live speed 4× per second */
    fun download(sample: (Float) -> Unit): Double {
        val bytes = AtomicLong(0); val done = AtomicBoolean(false)
        val t0 = SystemClock.elapsedRealtime()
        // Cloudflare limits big downloads per address after several tests (answers 429):
        // then the other servers take over
        val src = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = (1..4).map {
            Thread {
                while (!done.get() && !stop) {
                    val i = src.get()
                    runCatching {
                        val c = open(DOWN_URLS[i % DOWN_URLS.size])
                        try {
                            check(c.responseCode in 200..299) { "Download rejected" }
                            c.inputStream.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (!done.get() && !stop) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes.addAndGet(count.toLong())
                                }
                            }
                        } finally { c.disconnect() }
                    }.onFailure { src.compareAndSet(i, i + 1); Thread.sleep(150) }
                }
            }.apply { start() }
        }
        val r = measure(bytes, t0, 10, sample)
        done.set(true); workers.forEach { it.join(2000) }
        return r
    }

    /** 3 parallel uploads for ~8 s → Mbit/s */
    fun upload(sample: (Float) -> Unit): Double {
        val bytes = AtomicLong(0); val done = AtomicBoolean(false)
        val chunk = ByteArray(2 * 1024 * 1024)
        val t0 = SystemClock.elapsedRealtime()
        val workers = (1..3).map {
            Thread {
                while (!done.get() && !stop) {
                    runCatching {
                        val c = open("https://speed.cloudflare.com/__up")
                        try {
                            c.doOutput = true
                            c.requestMethod = "POST"
                            c.setFixedLengthStreamingMode(chunk.size)
                            c.outputStream.use { output ->
                                var offset = 0
                                while (offset < chunk.size && !done.get() && !stop) {
                                    val count = minOf(64 * 1024, chunk.size - offset)
                                    output.write(chunk, offset, count)
                                    offset += count
                                }
                            }
                            if (c.responseCode in 200..299) bytes.addAndGet(chunk.size.toLong())
                        } finally { c.disconnect() }
                    }.onFailure { if (bytes.get() == 0L) Thread.sleep(300) }
                }
            }.apply { start() }
        }
        val r = measure(bytes, t0, 8, sample)
        done.set(true); workers.forEach { it.join(2000) }
        return r
    }

    private fun measure(bytes: AtomicLong, t0: Long, seconds: Int, sample: (Float) -> Unit): Double {
        var lastB = 0L; var lastT = t0; var warmB = 0L; var warmT = 0L; var firstT = 0L
        while (routeValid()) {
            Thread.sleep(250)
            val now = SystemClock.elapsedRealtime(); val b = bytes.get()
            sample(((b - lastB) * 8.0 / ((now - lastT).coerceAtLeast(1) / 1000.0) / 1_000_000.0).toFloat())
            lastB = b; lastT = now
            // the clock starts with the first data (a server switch at the start doesn't count)
            if (firstT == 0L && b > 0) firstT = now
            if (firstT != 0L && warmT == 0L && now - firstT >= 1500) { warmT = now; warmB = b }
            if (firstT != 0L && now - firstT >= seconds * 1000L) break
            if (firstT == 0L && now - t0 >= 8000L) break   // nothing comes at all
        }
        val end = SystemClock.elapsedRealtime(); val b = bytes.get()
        return if (warmT == 0L || end <= warmT) b * 8.0 / ((end - t0).coerceAtLeast(1) / 1000.0) / 1_000_000.0
        else (b - warmB) * 8.0 / ((end - warmT) / 1000.0) / 1_000_000.0
    }

    companion object {
        fun matchesRoute(manager: ConnectivityManager, network: Network, vpn: Boolean): Boolean = runCatching {
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == vpn &&
                (if (vpn) manager.activeNetwork == network
                 else caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        }.getOrDefault(false)


        /** download sources in order: Cloudflare, Hetzner (Falkenstein, Nuremberg), OVH */
        val DOWN_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=50000000",
            "https://fsn1-speed.hetzner.com/1GB.bin",
            "https://nbg1-speed.hetzner.com/1GB.bin",
            "https://proof.ovh.net/files/1Gb.dat",
        )

        /** "DE" → 🇩🇪 */
        fun flag(cc: String): String =
            if (cc.length != 2) "🌐" else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

        // messages between LEAD and the helper app
        const val MSG_START = 1
        const val MSG_PHASE = 2      // arg1: 0 where, 1 ping, 2 download, 3 upload ; obj bundle
        const val MSG_SAMPLE = 3     // data "v" float
        const val MSG_RESULT = 4     // data: ip, cc, ping, down, up, vpn
        const val MSG_ERROR = 5
        const val MSG_NO_VPN_ROUTE = 6
        const val VERIFIED_ROUTE = "verifiedRouteV1"
        const val PROBE_PKG = "com.glass.launcher.vpntest"
        const val PROBE_SERVICE = "com.glass.launcher.vpntest.ProbeService"
    }
}
