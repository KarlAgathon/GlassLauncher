package com.glass.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wallpaper plugins made for Projectivy Launcher (e.g. SofaDrop) also work in LEAD Launcher.
 * They are found by their intent action and asked for wallpapers over the same Binder interface
 * Projectivy uses (IWallpaperProviderService.getWallpapers), so the plugin needs no changes.
 */
object Plugins {
    const val ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER"
    private const val DESCRIPTOR = "tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService"
    private const val TX_GET_WALLPAPERS = IBinder.FIRST_CALL_TRANSACTION
    private const val EVENT_TIME_ELAPSED = 1

    // wallpaper types of the plugin API
    private const val TYPE_IMAGE = 0
    private const val TYPE_ANIMATED = 2

    data class Plugin(val pkg: String, val cls: String, val name: String, val settings: String?) {
        val id get() = "$pkg/$cls"
    }

    data class Item(val uri: String, val type: Int, val title: String?, val source: String?, val author: String?)

    fun list(ctx: Context): List<Plugin> {
        val pm = ctx.packageManager
        return pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA).mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val md = si.metaData
            val name = md?.get("name")?.toString()?.takeIf { it.isNotBlank() && !it.startsWith("@") }
                ?: runCatching { si.loadLabel(pm).toString() }.getOrDefault(si.packageName)
            Plugin(si.packageName, si.name, name, md?.getString("settingsActivity"))
        }.sortedBy { it.name.lowercase() }
    }

    fun find(ctx: Context, id: String): Plugin? = list(ctx).firstOrNull { it.id == id }

    /** Opens the plugin's own settings screen. */
    fun openSettings(ctx: Context, p: Plugin): Boolean = runCatching {
        val s = p.settings
        val i = if (!s.isNullOrBlank()) {
            val cls = if (s.startsWith(".")) p.pkg + s else s
            Intent().setComponent(ComponentName(p.pkg, cls))
        } else ctx.packageManager.getLeanbackLaunchIntentForPackage(p.pkg) ?: ctx.packageManager.getLaunchIntentForPackage(p.pkg)
        ctx.startActivity(i!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Blocking: binds to the plugin and asks for wallpapers. Call off the main thread. */
    fun fetch(ctx: Context, p: Plugin, timeoutSec: Long = 20): List<Item> {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onBindingDied(name: ComponentName?) { latch.countDown() }
            override fun onNullBinding(name: ComponentName?) { latch.countDown() }
        }
        val app = ctx.applicationContext
        val intent = Intent(ACTION).setComponent(ComponentName(p.pkg, p.cls))
        val main = Handler(Looper.getMainLooper())
        var bound = false
        val bindDone = CountDownLatch(1)
        main.post {
            bound = runCatching { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            bindDone.countDown()
        }
        bindDone.await(5, TimeUnit.SECONDS)
        if (!bound) return emptyList()
        try {
            latch.await(10, TimeUnit.SECONDS)
            val b = binder ?: return emptyList()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                // Event.TimeElapsed: non-null marker, then the parcelized object (its event type = 1)
                data.writeInt(1)
                data.writeInt(EVENT_TIME_ELAPSED)
                val ok = runCatching { b.transact(TX_GET_WALLPAPERS, data, reply, 0) }.getOrDefault(false)
                if (!ok) return emptyList()
                reply.readException()
                val n = reply.readInt()
                if (n <= 0) return emptyList()
                val out = ArrayList<Item>(n)
                repeat(n) {
                    if (reply.readInt() == 0) return@repeat
                    val uri = reply.readString() ?: ""
                    val type = reply.readInt()
                    reply.readInt() // display mode
                    val title = reply.readString()
                    val source = reply.readString()
                    val author = reply.readString()
                    reply.readString() // action uri
                    if (uri.isNotBlank()) out.add(Item(uri, type, title, source, author))
                }
                return out
            } finally {
                data.recycle(); reply.recycle()
            }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            main.post { runCatching { app.unbindService(conn) } }
        }
    }

    /** Pictures only (videos and animations of other kinds are skipped), as wallpaper entries. */
    fun movies(items: List<Item>): List<Movie> = items
        .filter { it.type == TYPE_IMAGE || it.type == TYPE_ANIMATED }
        .map { Movie(it.title ?: "", "", "", it.source ?: "", it.author ?: "", it.uri) }

    /** Copies a picture from a plugin URI (http, content, file) to [dest]. Blocking. */
    fun download(ctx: Context, uri: String, dest: File) {
        when {
            uri.startsWith("http://") || uri.startsWith("https://") -> Http.download(uri, dest)
            uri.startsWith("/") -> File(uri).copyTo(dest, true)
            uri.startsWith("file://") -> File(android.net.Uri.parse(uri).path ?: "").copyTo(dest, true)
            else -> ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
