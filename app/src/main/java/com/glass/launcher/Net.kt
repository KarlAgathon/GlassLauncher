package com.glass.launcher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One file or folder on a network location. */
data class RFile(val name: String, val path: String, val isDir: Boolean, val size: Long, val mtime: Long)

/** A network location LEAD can browse: list, download, upload. All calls block – use a background thread. */
interface RemoteFs : Closeable {
    val root: String
    fun list(path: String): List<RFile>
    fun download(path: String, to: File, progress: (Long) -> Unit)
    fun upload(from: File, dir: String, progress: (Long) -> Unit)
    fun exists(dir: String, name: String): Boolean = runCatching { list(dir).any { it.name == name } }.getOrDefault(false)
}

/** A saved (or one-off) connection. The password is only kept when the user allowed it, encrypted with the box's key store. */
data class Conn(
    val id: String, val name: String, val proto: String,   // "smb", "ftp", "ftps", "sftp"
    val host: String, val port: Int, val user: String, val share: String,  // share = SMB share or start folder
    val secret: String = "",                                 // encrypted password (empty = ask each time)
) {
    fun label() = name.ifBlank { "$proto://$host" }
    fun toJson() = JSONObject().put("id", id).put("name", name).put("proto", proto).put("host", host).put("port", port)
        .put("user", user).put("share", share).put("secret", secret)

    companion object {
        fun from(j: JSONObject) = Conn(j.getString("id"), j.optString("name"), j.getString("proto"), j.getString("host"),
            j.optInt("port"), j.optString("user"), j.optString("share"), j.optString("secret"))
        fun defaultPort(proto: String) = when (proto) { "smb" -> 445; "sftp" -> 22; "ftps" -> 21; else -> 21 }
    }
}

object Conns {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("net_conns", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Conn> = runCatching {
        val a = JSONArray(sp(ctx).getString("list", "[]")); (0 until a.length()).map { Conn.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, c: Conn) {
        val l = all(ctx).filter { it.id != c.id } + c
        sp(ctx).edit().putString("list", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    fun delete(ctx: Context, id: String) {
        sp(ctx).edit().putString("list", JSONArray(all(ctx).filter { it.id != id }.map { it.toJson() }).toString()).apply()
    }

    // ---- passwords: AES-GCM with a key that never leaves the Android key store (not in backups, not readable)
    private const val ALIAS = "lead_net_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun encrypt(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault("")

    fun decrypt(s: String): String? = runCatching {
        if (s.isBlank()) return null
        val (iv, data) = s.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(data))
    }.getOrNull()

    fun open(c: Conn, password: String): RemoteFs = when (c.proto) {
        "smb" -> SmbFs(c, password)
        "sftp" -> SftpFs(c, password)
        else -> FtpFs(c, password, c.proto == "ftps")
    }
}

// ---------------------------------------------------------------------------------------------- SMB 2/3

class SmbFs(c: Conn, password: String) : RemoteFs {
    private val client = com.hierynomus.smbj.SMBClient(com.hierynomus.smbj.SmbConfig.builder()
        .withTimeout(20, java.util.concurrent.TimeUnit.SECONDS).withSoTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build())
    private val conn = client.connect(c.host, if (c.port > 0) c.port else 445)
    private val session = conn.authenticate(
        if (c.user.isBlank()) com.hierynomus.smbj.auth.AuthenticationContext.anonymous()
        else com.hierynomus.smbj.auth.AuthenticationContext(c.user.substringAfter('\\'), password.toCharArray(),
            c.user.substringBefore('\\', "").ifBlank { null }))
    private val shareName = c.share.trim('/', '\\').substringBefore('/').substringBefore('\\')
    private val share = session.connectShare(shareName) as com.hierynomus.smbj.share.DiskShare
    override val root = ""

    private fun p(path: String) = path.trim('/').replace('/', '\\')

    override fun list(path: String): List<RFile> = share.list(p(path)).filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }.map {
        val dir = (it.fileAttributes and com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        RFile(it.fileName, (path.trimEnd('/') + "/" + it.fileName), dir, it.endOfFile, it.lastWriteTime.toEpochMillis())
    }

    override fun download(path: String, to: File, progress: (Long) -> Unit) {
        val f = share.openFile(p(path), setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
            setOf(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ), com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, null)
        f.use { rf -> rf.inputStream.use { inp -> to.outputStream().use { copy(inp, it, progress) } } }
    }

    override fun upload(from: File, dir: String, progress: (Long) -> Unit) {
        val f = share.openFile(p(dir.trimEnd('/') + "/" + from.name), setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE), null,
            setOf(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_WRITE), com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE, null)
        f.use { rf -> rf.outputStream.use { out -> from.inputStream().use { copy(it, out, progress) } } }
    }

    override fun close() { runCatching { share.close() }; runCatching { session.close() }; runCatching { conn.close() }; runCatching { client.close() } }
}

// ---------------------------------------------------------------------------------------------- FTP / FTPS

class FtpFs(c: Conn, password: String, tls: Boolean) : RemoteFs {
    private val ftp = (if (tls) org.apache.commons.net.ftp.FTPSClient(false) else org.apache.commons.net.ftp.FTPClient()).apply {
        connectTimeout = 15_000; defaultTimeout = 30_000
        controlEncoding = "UTF-8"
        connect(c.host, if (c.port > 0) c.port else 21)
        if (!login(c.user.ifBlank { "anonymous" }, if (c.user.isBlank()) "lead@" else password)) { runCatching { disconnect() }; error("login") }
        if (this is org.apache.commons.net.ftp.FTPSClient) { execPBSZ(0); execPROT("P") }
        enterLocalPassiveMode()
        setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
    }
    override val root = "/" + c.share.trim('/')

    override fun list(path: String): List<RFile> = ftp.listFiles(path).filter { it != null && it.name != "." && it.name != ".." && !it.name.startsWith(".") }.map {
        RFile(it.name, path.trimEnd('/') + "/" + it.name, it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0L)
    }

    override fun download(path: String, to: File, progress: (Long) -> Unit) {
        val inp = ftp.retrieveFileStream(path) ?: error("download")
        inp.use { to.outputStream().use { o -> copy(it, o, progress) } }
        check(ftp.completePendingCommand()) { "FTP transfer did not complete" }
    }

    override fun upload(from: File, dir: String, progress: (Long) -> Unit) {
        val out = ftp.storeFileStream(dir.trimEnd('/') + "/" + from.name) ?: error("upload")
        out.use { o -> from.inputStream().use { copy(it, o, progress) } }
        check(ftp.completePendingCommand()) { "FTP transfer did not complete" }
    }

    override fun close() { runCatching { ftp.logout() }; runCatching { ftp.disconnect() } }
}

// ---------------------------------------------------------------------------------------------- SFTP (SSH)

class SftpFs(c: Conn, password: String) : RemoteFs {
    private val session = com.jcraft.jsch.JSch().getSession(c.user, c.host, if (c.port > 0) c.port else 22).apply {
        setPassword(password)
        // first connection: the server's key is accepted and remembered for this session only
        setConfig("StrictHostKeyChecking", "no")
        connect(20_000)
    }
    private val ch = (session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp).apply { connect(15_000) }
    override val root = "/" + c.share.trim('/')

    override fun list(path: String): List<RFile> = ch.ls(path.ifBlank { "/" }).filterIsInstance<com.jcraft.jsch.ChannelSftp.LsEntry>()
        .filter { it.filename != "." && it.filename != ".." && !it.filename.startsWith(".") }
        .map { RFile(it.filename, path.trimEnd('/') + "/" + it.filename, it.attrs.isDir, it.attrs.size, it.attrs.mTime * 1000L) }

    override fun download(path: String, to: File, progress: (Long) -> Unit) {
        ch.get(path).use { inp -> to.outputStream().use { copy(inp, it, progress) } }
    }

    override fun upload(from: File, dir: String, progress: (Long) -> Unit) {
        ch.put(dir.trimEnd('/') + "/" + from.name).use { out -> from.inputStream().use { copy(it, out, progress) } }
    }

    override fun close() { runCatching { ch.disconnect() }; runCatching { session.disconnect() } }
}

private fun copy(inp: java.io.InputStream, out: java.io.OutputStream, progress: (Long) -> Unit) {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = inp.read(buf); if (n < 0) break
        out.write(buf, 0, n); total += n; progress(total)
    }
}

// ---------------------------------------------------------------------------------------------- discovery

/** Finds shares that announce themselves on the home network (not every NAS or PC does – manual entry always works). */
class Discovery(private val ctx: Context, private val found: (String, String, Int, String) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()

    fun start() {
        for ((type, proto) in listOf("_smb._tcp." to "smb", "_ftp._tcp." to "ftp", "_sftp-ssh._tcp." to "sftp")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String?, e: Int) {}
                override fun onStopDiscoveryFailed(t: String?, e: Int) {}
                override fun onDiscoveryStarted(t: String?) {}
                override fun onDiscoveryStopped(t: String?) {}
                override fun onServiceLost(s: NsdServiceInfo?) {}
                override fun onServiceFound(s: NsdServiceInfo) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo?, e: Int) {}
                            override fun onServiceResolved(si: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val host = si.host?.hostAddress ?: return
                                found(si.serviceName, host, si.port, proto)
                            }
                        })
                    }
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners.add(l) }
        }
    }

    fun stop() { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }; listeners.clear() }
}
