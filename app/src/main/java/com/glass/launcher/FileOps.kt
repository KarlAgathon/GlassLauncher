package com.glass.launcher

import android.content.Context
import java.io.File

/**
 * Safe file operations for the v10 file manager.
 *  - never overwrites silently (conflicts are decided before anything runs)
 *  - a folder can't be moved/copied into itself
 *  - a move between drives = copy, check the size, only then delete the original
 *  - partial failures are reported exactly
 */
object FileOps {
    enum class Clash { SKIP, KEEP_BOTH, REPLACE }

    data class Report(val done: Int, val skipped: Int, val failed: List<String>)

    fun volumeOf(f: File): String {
        val p = f.absolutePath
        return Trash.roots().map { it.absolutePath }.filter { p == it || p.startsWith("$it/") }.maxByOrNull { it.length } ?: "/"
    }

    fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath; val p = parent.canonicalPath
        return c == p || c.startsWith("$p/")
    }

    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (File(dir, "$base ($i)$ext").exists()) i++
        return "$base ($i)$ext"
    }

    fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /** copies [src] (file or folder) to [dst]; [progress] gets the bytes written so far; false on any error */
    private fun copyTree(src: File, dst: File, progress: (Long) -> Unit, cancel: () -> Boolean): Boolean {
        if (cancel()) return false
        if (src.isDirectory) {
            if (!dst.isDirectory && !dst.mkdirs()) return false
            for (c in src.listFiles() ?: return false) if (!copyTree(c, File(dst, c.name), progress, cancel)) return false
            return true
        }
        return runCatching {
            src.inputStream().use { inp ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancel()) return false
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); progress(n.toLong())
                    }
                }
            }
            dst.setLastModified(src.lastModified())
            dst.length() == src.length()
        }.getOrDefault(false)
    }

    /**
     * Copies or moves [files] into [dest]. [clash] says what to do with each name that already exists there.
     * [progress] (file index, bytes) is called from this (background) thread.
     */
    fun run(files: List<File>, dest: File, move: Boolean, clash: (File) -> Clash,
            progress: (Int, Long) -> Unit, cancel: () -> Boolean): Report {
        var done = 0; var skipped = 0
        val failed = ArrayList<String>()
        var bytes = 0L
        if (!dest.isDirectory || !dest.canWrite()) return Report(0, 0, files.map { it.name })
        files.forEachIndexed { i, src ->
            if (cancel()) { failed.add(src.name); return@forEachIndexed }
            progress(i, bytes)
            if (src.isDirectory && isInside(dest, src)) { failed.add(src.name + " ⟲"); return@forEachIndexed }
            if (src.parentFile?.canonicalPath == dest.canonicalPath && move) { skipped++; return@forEachIndexed }
            var name = src.name
            var replacing: File? = null
            if (File(dest, name).exists()) {
                when (clash(src)) {
                    Clash.SKIP -> { skipped++; return@forEachIndexed }
                    Clash.KEEP_BOTH -> name = uniqueName(dest, name)
                    Clash.REPLACE -> replacing = File(dest, name)
                }
            }
            // into a temporary name first; the old file is only replaced when the new one is complete
            val target = if (replacing != null) File(dest, ".lead_tmp_" + System.nanoTime() + "_" + name) else File(dest, name)
            val sameVol = volumeOf(src) == volumeOf(dest)
            val ok = if (move && sameVol) src.renameTo(target)
                else copyTree(src, target, { n -> bytes += n; progress(i, bytes) }, cancel).also { okCopy ->
                    if (!okCopy) target.deleteRecursively()
                    // cross-drive move: original deleted only after the copy is complete and the same size
                    else if (move && sizeOf(target) == sizeOf(src)) src.deleteRecursively()
                }
            if (!ok) { failed.add(src.name); return@forEachIndexed }
            if (replacing != null) {
                replacing.deleteRecursively()
                if (!target.renameTo(File(dest, name))) { failed.add(src.name); return@forEachIndexed }
            }
            done++
        }
        return Report(done, skipped, failed)
    }
}

/** Remembered places for Move / Copy (kept on the box only). */
object Destinations {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("fm_places", Context.MODE_PRIVATE)

    /** path → (times used, last used) */
    private fun used(ctx: Context): MutableMap<String, Pair<Int, Long>> =
        (sp(ctx).getString("used", "") ?: "").split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split('\t'); if (p.size < 3) null else p[0] to ((p[1].toIntOrNull() ?: 1) to (p[2].toLongOrNull() ?: 0L))
        }.toMap().toMutableMap()

    fun remember(ctx: Context, dir: File) {
        val m = used(ctx)
        val old = m[dir.absolutePath]
        m[dir.absolutePath] = ((old?.first ?: 0) + 1) to System.currentTimeMillis()
        val keep = m.entries.sortedByDescending { it.value.second }.take(20)
        sp(ctx).edit().putString("used", keep.joinToString("\n") { "${it.key}\t${it.value.first}\t${it.value.second}" })
            .putString("last", dir.absolutePath).apply()
    }

    fun last(ctx: Context): File? = sp(ctx).getString("last", null)?.let { File(it) }?.takeIf { it.isDirectory }

    /** recent and often used first (recency counts most), only folders that still exist */
    fun recent(ctx: Context, n: Int = 5): List<File> {
        val now = System.currentTimeMillis()
        return used(ctx).entries.sortedByDescending { (_, v) -> v.first * 2.0 - (now - v.second) / 86_400_000.0 }
            .map { File(it.key) }.filter { it.isDirectory }.take(n)
    }

    fun favorites(ctx: Context): List<File> =
        (sp(ctx).getString("fav", "") ?: "").split('\n').filter { it.isNotBlank() }.map { File(it) }.filter { it.isDirectory }

    fun isFavorite(ctx: Context, dir: File) = favorites(ctx).any { it.absolutePath == dir.absolutePath }

    fun toggleFavorite(ctx: Context, dir: File) {
        val l = favorites(ctx).map { it.absolutePath }.toMutableList()
        if (!l.remove(dir.absolutePath)) l.add(dir.absolutePath)
        sp(ctx).edit().putString("fav", l.joinToString("\n")).apply()
    }

    /** files opened in the file manager, newest first */
    fun opened(ctx: Context, f: File) {
        val l = recentFiles(ctx).map { it.absolutePath }.toMutableList()
        l.remove(f.absolutePath); l.add(0, f.absolutePath)
        sp(ctx).edit().putString("files", l.take(30).joinToString("\n")).apply()
    }

    fun recentFiles(ctx: Context): List<File> =
        (sp(ctx).getString("files", "") ?: "").split('\n').filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }
}
