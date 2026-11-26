package com.glass.launcher

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * LEAD Phone Connect – files between the TV and a phone or computer on the same home network.
 *
 * The TV shows a QR code with a one-time address (random port + random 128-bit key). The phone opens it in its
 * browser; the TV then asks "Allow this phone?". Only after Allow can that phone send files (they land in
 * Download/LEAD Transfer) or fetch the files the TV user picked to send. Nothing else on the TV is reachable.
 * The key expires after 10 minutes unused; the whole service stops when you leave the screen or after 15 minutes idle.
 * Note: the transfer itself is plain HTTP inside the home network (no encryption) – said on the screen.
 */
class PhoneConnect internal constructor(
    private val ask: (String, (Boolean) -> Unit) -> Unit,
    private val event: (String) -> Unit,
    private val address: java.net.InetAddress = java.net.InetAddress.getByName(requireNotNull(ip())),
    private val uploadDirectory: () -> File = { inbox() },
    private val time: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        /** files the TV user chose to offer to the phone */
        val offered = java.util.concurrent.CopyOnWriteArrayList<File>()
        var running: PhoneConnect? = null

        fun inbox(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LEAD Transfer").apply { mkdirs() }

        fun ip(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        fun qr(text: String, size: Int): Bitmap {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.rgb(32, 28, 26) else Color.rgb(246, 241, 234)
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }

    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 8, address)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 0L,
        java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.SynchronousQueue())
    private var watchdog: Thread? = null
    val port get() = server.localPort
    private val allowed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val denied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val asking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val started = time()
    @Volatile private var lastUse = time()
    @Volatile private var stopped = false
    var received = 0; private set

    fun url() = "http://${address.hostAddress}:$port/t/$token/"

    fun start() {
        running = this
        Thread {
            while (!stopped) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(s)
                try {
                    workers.execute {
                        try { if (!stopped) handle(s) } catch (_: Exception) {
                            // A disconnected client does not end the pairing session.
                        } finally {
                            sockets.remove(s)
                            runCatching { s.close() }
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(s)
                    runCatching { s.close() }
                }
            }
        }.start()
        // watchdog: unused key 10 min, idle session 15 min
        watchdog = Thread {
            while (!stopped) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
                if (expired()) {
                    stop(); event("expired")
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        stopped = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        watchdog?.interrupt()
        allowed.clear()
        asking.clear()
        denied.clear()
        if (running === this) running = null
    }

    private fun expired(): Boolean {
        val now = time()
        return (allowed.isEmpty() && now - started >= 10 * 60_000L) || now - lastUse >= 15 * 60_000L
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val inp = BufferedInputStream(s.getInputStream())
        val deadline = time() + 15_000
        val line = readLine(inp, deadline) ?: return
        val headers = HashMap<String, String>()
        var headerCount = 0
        var headerSize = 0
        while (true) {
            val h = readLine(inp, deadline) ?: return
            if (h.isEmpty()) break
            headerSize += h.length
            if (++headerCount > 48 || headerSize > 16_384) return
            val i = h.indexOf(':')
            if (i <= 0) return
            val key = h.substring(0, i).trim().lowercase(java.util.Locale.ROOT)
            if (headers.put(key, h.substring(i + 1).trim()) != null) return
        }
        val (method, target) = line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val out = s.getOutputStream()
        val prefix = "/t/$token/"
        if (stopped || expired() || !target.startsWith(prefix)) { send(out, 404, "text/plain", "Not found".toByteArray()); return }
        if (headers.containsKey("transfer-encoding")) {
            send(out, 400, "text/plain", "Unsupported transfer encoding".toByteArray()); return
        }
        val who = s.inetAddress.hostAddress ?: "?"
        val rest = target.removePrefix(prefix)
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        val expected = if (path == "up") "POST" else "GET"
        if (method != expected) { send(out, 405, "text/plain", "Method not allowed".toByteArray()); return }
        if (who in allowed) lastUse = time()
        when {
            path.isEmpty() -> send(out, 200, "text/html; charset=utf-8", page().toByteArray())
            path == "status" -> {
                if (allowed.size + denied.size + asking.size < 16 && who !in allowed && who !in denied && asking.add(who)) {
                    ask(who) { ok -> asking.remove(who); if (!stopped) { if (ok) allowed.add(who) else denied.add(who) } }
                }
                val state = when (who) { in allowed -> "ok"; in denied -> "denied"; else -> "wait" }
                val files = if (state == "ok") offered.mapIndexed { i, f -> """{"i":$i,"name":${json(f.name)},"size":${f.length()}}""" }.joinToString(",") else ""
                send(out, 200, "application/json", """{"state":"$state","files":[$files]}""".toByteArray())
            }
            who !in allowed -> send(out, 403, "text/plain", "Not allowed".toByteArray())
            path == "up" && method == "POST" -> {
                val name = URLDecoder.decode(query.substringAfter("name=").substringBefore('&'), "UTF-8")
                    .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("")
                    .trim().take(180).takeUnless { it.isBlank() || it.startsWith('.') } ?: "file"
                val len = headers["content-length"]?.toLongOrNull() ?: -1L
                if (len < 0) { send(out, 411, "text/plain", "Length required".toByteArray()); return }
                val dir = uploadDirectory()
                if (len > 4L * 1024 * 1024 * 1024 || len > dir.usableSpace) {
                    send(out, 413, "text/plain", "File too large or insufficient space".toByteArray()); return
                }
                val tmp = File.createTempFile(".lead-upload-", ".part", dir)
                try {
                    var left = len
                    java.io.FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (left > 0 && !stopped) {
                            val count = inp.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            left -= count
                            lastUse = time()
                        }
                        output.fd.sync()
                    }
                    if (left != 0L || stopped) {
                        send(out, 400, "text/plain", "Incomplete".toByteArray()); return
                    }
                    val dst = synchronized(this) {
                        File(dir, FileOps.uniqueName(dir, name)).takeIf { !stopped && tmp.renameTo(it) }
                    }
                    if (dst == null) { send(out, 500, "text/plain", "Could not save file".toByteArray()); return }
                    synchronized(this) { received++ }
                    event("received:" + dst.name)
                    send(out, 200, "text/plain", "OK".toByteArray())
                } finally {
                    tmp.delete()
                }
            }
            path.startsWith("dl/") -> {
                val f = offered.getOrNull(path.removePrefix("dl/").toIntOrNull() ?: -1)
                if (f == null || !f.isFile) { send(out, 404, "text/plain", "Not found".toByteArray()); return }
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${f.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"${f.name.filterNot { it == '"' || it.isISOControl() }}\"\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray())
                f.inputStream().use { it.copyTo(out, 256 * 1024) }
                out.flush()
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun readLine(i: BufferedInputStream, deadline: Long): String? {
        val sb = StringBuilder()
        while (true) {
            if (time() >= deadline) return null
            val c = i.read(); if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar()); if (sb.length > 8192) return null
        }
    }

    private fun send(o: OutputStream, code: Int, type: String, body: ByteArray) {
        o.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(body); o.flush()
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun page() = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LEAD Phone Connect</title><style>
body{margin:0;font-family:system-ui,sans-serif;background:#2b2724;color:#f6f1ea}
.w{max-width:560px;margin:0 auto;padding:28px 20px}h1{font-weight:400;margin:0 0 4px}p{color:#c8beb4}
.c{background:rgba(160,149,139,.18);border:1px solid rgba(255,244,232,.15);border-radius:16px;padding:18px;margin:16px 0}
label.b,button{display:inline-block;background:#e0c9a4;color:#2b2724;border:0;border-radius:12px;padding:12px 18px;font-size:16px}
.f{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid rgba(255,244,232,.1)}a{color:#e0c9a4}
.bar{height:4px;background:#e0c9a4;width:0;border-radius:2px;transition:width .2s}.m{color:#c8beb4;font-size:14px}
</style></head><body><div class="w"><h1>LEAD Phone Connect</h1><p id="s">Waiting for approval on the TV…</p>
<div id="ok" style="display:none"><div class="c"><b>Send to the TV</b><p class="m">Files go to Download/LEAD Transfer on the TV.</p>
<label class="b">Choose files<input id="pick" type="file" multiple style="display:none"></label><div id="up"></div></div>
<div class="c"><b>Files from the TV</b><div id="files"><p class="m">Nothing offered yet.</p></div></div>
<p class="m">Home network only · not encrypted · this link stops when you leave the screen on the TV.</p></div></div>
<script>
const B=location.pathname;function sz(n){return n>1e9?(n/1e9).toFixed(1)+' GB':n>1e6?(n/1e6).toFixed(1)+' MB':Math.round(n/1e3)+' KB'}
async function poll(){try{const r=await fetch(B+'status');const j=await r.json();
if(j.state=='ok'){s.textContent='Connected';ok.style.display='block';
files.innerHTML=j.files.length?j.files.map(f=>'<div class="f"><a href="'+B+'dl/'+f.i+'">'+f.name.replace(/</g,'&lt;')+'</a><span class="m">'+sz(f.size)+'</span></div>').join(''):'<p class="m">Nothing offered yet.</p>'}
else if(j.state=='denied'){s.textContent='The TV did not allow this device.';return}}catch(e){s.textContent='The TV is not reachable any more.';return}setTimeout(poll,2000)}
poll();
pick.onchange=()=>{[...pick.files].forEach(f=>{const d=document.createElement('div');d.className='f';d.innerHTML='<span>'+f.name.replace(/</g,'&lt;')+'</span><span class="m">0 %</span>';
const bar=document.createElement('div');bar.className='bar';up.appendChild(d);up.appendChild(bar);
const x=new XMLHttpRequest();x.open('POST',B+'up?name='+encodeURIComponent(f.name));
x.upload.onprogress=e=>{const p=Math.round(e.loaded*100/e.total);d.lastChild.textContent=p+' %';bar.style.width=p+'%'};
x.onload=()=>{d.lastChild.textContent=x.status==200?'✓ sent':'failed'};x.onerror=()=>{d.lastChild.textContent='failed'};x.send(f)});pick.value=''};
</script></body></html>"""
}
