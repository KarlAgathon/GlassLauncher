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
    fun create(ctx: Context, target: File? = null): File {
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

        val dir = (target ?: folder()).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val out = File(dir, if (target != null) "backup.json" else "backup_$stamp.json")
        out.writeText(root.toString())
        return out
    }

    /**
     * Full backup: launcher settings + the installed apps themselves (their APK files), in
     * Download/GlassLauncher/full_<date>/. On another box, "Restore" puts the settings back and
     * installs the missing apps (Android asks "install?" for each one).
     * App data inside the apps (logins, playlists …) can't be copied by any app without root.
     */
    fun createFull(ctx: Context, progress: (String) -> Unit): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val dir = File(folder(), "full_$stamp").apply { mkdirs() }
        val apks = File(dir, "apks").apply { mkdirs() }
        val pm = ctx.packageManager
        val list = JSONArray()
        // apps you installed yourself (not Android's own apps), without the launcher itself
        val apps = pm.getInstalledApplications(0).filter {
            it.packageName != ctx.packageName && (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }.distinctBy { it.packageName }
        for ((i, ai) in apps.withIndex()) {
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
            progress("${i + 1}/${apps.size}  $label")
            val pd = File(apks, ai.packageName).apply { mkdirs() }
            val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            var ok = true
            for (src in files) ok = ok && runCatching { File(src).copyTo(File(pd, File(src).name), true); true }.getOrDefault(false)
            if (!ok) { pd.deleteRecursively(); continue }
            val ver = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull() ?: ""
            list.put(JSONObject().put("pkg", ai.packageName).put("label", label).put("version", ver))
        }
        File(dir, "apps.json").writeText(list.toString(2))
        progress(T.s("Settings…", "Postavke…", "Einstellungen…"))
        return create(ctx, dir)
    }

    /** APK folders of a full backup that are not installed on this box: (package, its APK files). */
    fun missingApps(ctx: Context, backupFile: File): List<Pair<String, List<File>>> {
        val apks = File(backupFile.parentFile, "apks")
        if (!apks.isDirectory) return emptyList()
        return apks.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            val installed = runCatching { ctx.packageManager.getPackageInfo(d.name, 0); true }.getOrDefault(false)
            val files = d.listFiles()?.filter { it.extension == "apk" }?.sortedBy { if (it.name == "base.apk") 0 else 1 } ?: emptyList()
            if (installed || files.isEmpty()) null else d.name to files
        } ?: emptyList()
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
