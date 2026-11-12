package com.glass.launcher

import android.os.Environment
import java.io.File

/**
 * Recycle bin of the file manager. Each drive gets its own hidden folder ".LEADTrash",
 * so moving a file into it is instant (no copying between drives).
 * index.txt keeps: stored name <TAB> original path <TAB> time of deletion.
 */
object Trash {
    private const val DIR = ".LEADTrash"
    private const val INDEX = "index.txt"

    data class Item(val file: File, val original: String, val deletedAt: Long) {
        val name get() = File(original).name
    }

    fun roots(): List<File> {
        val out = mutableListOf(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) out.add(it)
        }
        return out
    }

    private fun rootOf(f: File): File {
        val p = f.absolutePath
        return roots().filter { p.startsWith(it.absolutePath + "/") }.maxByOrNull { it.absolutePath.length }
            ?: Environment.getExternalStorageDirectory()
    }

    private fun dirFor(f: File) = File(rootOf(f), DIR)

    fun isTrash(f: File) = f.absolutePath.contains("/$DIR")

    private fun readIndex(dir: File): MutableList<Triple<String, String, Long>> {
        val idx = File(dir, INDEX)
        if (!idx.isFile) return mutableListOf()
        return idx.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size >= 3) Triple(p[0], p[1], p[2].toLongOrNull() ?: 0L) else null
        }.toMutableList()
    }

    private fun writeIndex(dir: File, items: List<Triple<String, String, Long>>) {
        File(dir, INDEX).writeText(items.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" })
    }

    @Synchronized
    fun move(f: File): Boolean {
        val dir = dirFor(f)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        runCatching { File(dir, ".nomedia").createNewFile() }
        val now = System.currentTimeMillis()
        val stored = File(dir, "${now}_${f.name}")
        val ok = f.renameTo(stored) || (runCatching { f.copyRecursively(stored, false) && f.deleteRecursively() }.getOrDefault(false))
        if (!ok) return false
        val idx = readIndex(dir)
        idx.add(Triple(stored.name, f.absolutePath, now))
        writeIndex(dir, idx)
        return true
    }

    @Synchronized
    fun list(): List<Item> {
        val out = ArrayList<Item>()
        for (r in roots()) {
            val dir = File(r, DIR)
            if (!dir.isDirectory) continue
            val idx = readIndex(dir)
            val known = idx.map { it.first }.toSet()
            for ((name, orig, t) in idx) {
                val f = File(dir, name)
                if (f.exists()) out.add(Item(f, orig, t))
            }
            // files in the bin without an index line (e.g. index lost): still show them
            dir.listFiles()?.forEach { f ->
                if (f.name != INDEX && f.name != ".nomedia" && f.name !in known)
                    out.add(Item(f, File(r, f.name.substringAfter('_')).absolutePath, f.lastModified()))
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    fun size(): Long = list().sumOf { if (it.file.isDirectory) it.file.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.file.length() }

    private fun dropFromIndex(item: Item) {
        val dir = item.file.parentFile ?: return
        writeIndex(dir, readIndex(dir).filter { it.first != item.file.name })
    }

    @Synchronized
    fun restore(item: Item): Boolean {
        var target = File(item.original)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            val base = target.nameWithoutExtension
            val ext = if (target.extension.isEmpty()) "" else "." + target.extension
            var i = 1
            while (target.exists()) { target = File(target.parentFile, "$base ($i)$ext"); i++ }
        }
        val ok = item.file.renameTo(target) ||
            (runCatching { item.file.copyRecursively(target, false) && item.file.deleteRecursively() }.getOrDefault(false))
        if (ok) dropFromIndex(item)
        return ok
    }

    @Synchronized
    fun purge(item: Item): Boolean {
        val ok = item.file.deleteRecursively()
        if (ok) dropFromIndex(item)
        return ok
    }

    fun empty(): Int = list().count { purge(it) }
}
