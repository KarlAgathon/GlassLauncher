package com.glass.launcher

import android.content.Context
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup / restore of everything LEAD Launcher knows: all profiles, their apps, names,
 * settings, own icons and wallpapers. (Passwords and Google logins belong to Android
 * and cannot be saved by any app.)
 */
object Backup {
    fun folder(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GlassLauncher")

    /** Blocking. Returns the written file. */
    fun create(ctx: Context): File {
        val root = JSONObject()
        root.put("format", 1)
        root.put("created", System.currentTimeMillis())

        val prefsJson = JSONObject()
        val names = mutableListOf("glass_global") + Profiles.list(ctx).map { Profiles.prefsName(it.first) }
        for (n in names.distinct()) {
            val obj = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all) {
                val e = JSONObject()
                when (v) {
                    is String -> { e.put("t", "s"); e.put("v", v) }
                    is Int -> { e.put("t", "i"); e.put("v", v) }
                    is Long -> { e.put("t", "l"); e.put("v", v) }
                    is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                    is Boolean -> { e.put("t", "b"); e.put("v", v) }
                    is Set<*> -> { e.put("t", "set"); e.put("v", JSONArray(v.map { it.toString() })) }
                    else -> continue
                }
                obj.put(k, e)
            }
            prefsJson.put(n, obj)
        }
        root.put("prefs", prefsJson)

        val files = JSONObject()
        ctx.filesDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(ctx.filesDir).path
            if (rel.startsWith("wallpaper") || rel.startsWith("icons")) {
                files.put(rel, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
        }
        root.put("files", files)

        val dir = folder().apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val out = File(dir, "backup_$stamp.json")
        out.writeText(root.toString())
        return out
    }

    /** Blocking. Replaces all launcher data with the backup. */
    fun restore(ctx: Context, file: File) {
        val root = JSONObject(file.readText())
        val prefsJson = root.getJSONObject("prefs")
        for (n in prefsJson.keys()) {
            val obj = prefsJson.getJSONObject(n)
            val ed = ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear()
            for (k in obj.keys()) {
                val e = obj.getJSONObject(k)
                when (e.getString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "set" -> {
                        val arr = e.getJSONArray("v")
                        ed.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toHashSet())
                    }
                }
            }
            ed.commit()
        }
        File(ctx.filesDir, "icons").deleteRecursively()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("wallpaper") }?.forEach { it.delete() }
        val files = root.optJSONObject("files") ?: JSONObject()
        for (rel in files.keys()) {
            if (rel.contains("..")) continue
            val f = File(ctx.filesDir, rel)
            f.parentFile?.mkdirs()
            f.writeBytes(Base64.decode(files.getString(rel), Base64.NO_WRAP))
        }
    }
}
