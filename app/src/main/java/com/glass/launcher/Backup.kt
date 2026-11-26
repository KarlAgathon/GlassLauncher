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
            val obj = encode(ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all, excludeSecrets = true)
            prefsJson.put(n, obj)
        }
        root.put("prefs", prefsJson)

        val files = JSONObject()
        var bytes = 0L
        for (asset in assetFiles(ctx.filesDir)) {
            val relative = asset.relativeTo(ctx.filesDir).path
            check(asset.canonicalFile == File(ctx.filesDir.canonicalFile, relative))
            val size = asset.length()
            bytes += size
            require(size in 1..MAX_ASSET && bytes <= backupLimit() / 2)
            files.put(relative, Base64.encodeToString(asset.readBytes(), Base64.NO_WRAP))
        }
        root.put("files", files)

        val dir = (target ?: folder()).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val out = File(dir, if (target != null) "backup.json" else "backup_$stamp.json")
        val encoded = root.toString().toByteArray()
        require(encoded.size <= backupLimit())
        val staged = File.createTempFile(".lead-backup-", ".part", dir)
        try {
            java.io.FileOutputStream(staged).use { stream ->
                stream.write(encoded)
                stream.fd.sync()
            }
            check(staged.renameTo(out))
        } finally {
            staged.delete()
        }
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

    private const val MAX_BACKUP = 48L * 1024 * 1024
    private const val MAX_ASSET = 16L * 1024 * 1024
    private val namespace = Regex("glass(?:_global|_p[a-z0-9]+)?")
    private val iconPath = Regex("icons/[A-Za-z0-9_.-]+")
    private val wallpaperPath = Regex("wallpaper(?:_p[a-z0-9]+|_before_v10)?")

    private fun isAsset(path: String): Boolean =
        !path.contains("..") && (iconPath.matches(path) || wallpaperPath.matches(path))

    private fun encode(values: Map<String, *>, excludeSecrets: Boolean): JSONObject {
        val obj = JSONObject()
        for ((k, v) in values) {
            // the GitHub token never leaves this box (not in backups, not in a transfer to another box)
            if (excludeSecrets && k == "ghToken") continue
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
        return obj
    }

    private fun backupLimit() = minOf(MAX_BACKUP, Runtime.getRuntime().maxMemory() / 6)

    private fun readBounded(file: File): JSONObject {
        val limit = backupLimit()
        require(file.isFile && file.length() in 1..limit)
        val bytes = file.inputStream().use { input ->
            val data = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(data.size().toLong() + n <= limit)
                data.write(buffer, 0, n)
            }
            data.toByteArray()
        }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun values(obj: JSONObject): Map<String, Any> = obj.keys().asSequence().associateWith { key ->
        require(key.length in 1..512)
        val entry = obj.getJSONObject(key)
        when (entry.getString("t")) {
            "s" -> entry.get("v") as String
            "i" -> (entry.get("v") as? Int) ?: error("Invalid integer preference")
            "l" -> (entry.get("v") as Number).let { require(it is Int || it is Long); it.toLong() }
            "f" -> (entry.get("v") as Number).toFloat().also { require(it.isFinite()) }
            "b" -> entry.get("v") as Boolean
            "set" -> entry.getJSONArray("v").let { array -> (0 until array.length()).map { array.get(it) as String }.toSet() }
            else -> error("Unsupported preference type")
        }
    }

    private fun commit(ctx: Context, name: String, values: Map<String, Any>) {
        val editor = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for ((key, value) in values) when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        check(editor.commit()) { "Could not save launcher settings" }
    }

    private fun journal(ctx: Context) = File(ctx.filesDir, ".restore-journal")

    private fun assetFiles(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && isAsset(it.name) } +
            File(dir, "icons").listFiles().orEmpty().filter { it.isFile && isAsset("icons/${it.name}") }

    private fun installAssets(from: File, to: File) {
        val sources = assetFiles(from).associateBy { it.relativeTo(from).path }
        for (existing in assetFiles(to)) {
            if (existing.relativeTo(to).path !in sources) check(existing.delete())
        }
        for ((relative, source) in sources) {
            val target = File(to, relative)
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            check(FileOps.transfer(source, target, move = false, replace = true))
        }
    }

    /** Recover an interrupted preferences/assets commit before Home loads. */
    @Synchronized
    fun recover(ctx: Context) {
        val dir = journal(ctx)
        val marker = File(dir, "rollback.json")
        if (!marker.isFile) return
        val snapshot = JSONObject(marker.readText())
        installAssets(File(dir, "previous"), ctx.filesDir)
        for (name in snapshot.keys()) commit(ctx, name, values(snapshot.getJSONObject(name)))
        check(marker.delete())
        dir.deleteRecursively()
    }

    @Synchronized
    fun restore(ctx: Context, file: File) {
        recover(ctx)
        val root = readBounded(file)
        require(root.optInt("format", -1) == 1)
        val prefs = root.getJSONObject("prefs")
        require(prefs.has("glass_global") && prefs.has("glass"))
        val decoded = prefs.keys().asSequence().associateWith { name ->
            require(namespace.matches(name))
            values(prefs.getJSONObject(name)).filterKeys { it != "ghToken" }
        }
        val files = if (root.has("files")) root.getJSONObject("files") else JSONObject()
        for (path in files.keys()) {
            require(isAsset(path))
            require(files.getString(path).length.toLong() <= MAX_ASSET * 4 / 3 + 4)
            val target = File(ctx.filesDir, path)
            require(target.canonicalFile == File(ctx.filesDir.canonicalFile, path))
        }
        val dir = journal(ctx)
        check(!dir.exists() || dir.deleteRecursively())
        check(dir.mkdir())
        val previous = File(dir, "previous")
        val staged = File(dir, "staged")
        val marker = File(dir, "rollback.json")
        try {
            for (path in files.keys()) {
                val bytes = Base64.decode(files.getString(path), Base64.NO_WRAP)
                require(bytes.isNotEmpty() && bytes.size <= MAX_ASSET)
                val target = File(staged, path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            for (asset in assetFiles(ctx.filesDir)) {
                check(asset.canonicalFile == File(ctx.filesDir.canonicalFile, asset.relativeTo(ctx.filesDir).path))
                asset.copyTo(File(previous, asset.relativeTo(ctx.filesDir).path))
            }
            val snapshot = JSONObject()
            for (name in decoded.keys) {
                snapshot.put(name, encode(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all, excludeSecrets = false))
            }
            val ready = File(dir, "snapshot.tmp")
            java.io.FileOutputStream(ready).use { stream ->
                stream.write(snapshot.toString().toByteArray())
                stream.fd.sync()
            }
            check(ready.renameTo(marker))
            installAssets(staged, ctx.filesDir)
            for ((name, restored) in decoded) {
                val secret = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all["ghToken"]
                commit(ctx, name, if (secret is String) restored + ("ghToken" to secret) else restored)
            }
            check(marker.delete())
        } catch (failure: Exception) {
            if (marker.isFile) recover(ctx)
            throw failure
        } finally {
            if (!marker.exists()) dir.deleteRecursively()
        }
    }
}
