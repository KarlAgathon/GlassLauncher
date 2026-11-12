package com.glass.launcher

import android.graphics.Color
import android.os.StatFs
import java.io.File

/** Counts what fills a drive, by kind of file, and remembers the files of each kind (for "show me the videos"). */
object StorageScan {
    data class Cat(val key: String, val name: String, val color: Int, var bytes: Long = 0L, val files: ArrayList<File> = ArrayList()) {
        val count get() = files.size
    }

    class Result(val root: File, val cats: List<Cat>, val total: Long, val free: Long) {
        val used get() = (total - free).coerceAtLeast(0L)
        val counted get() = cats.sumOf { it.bytes }
        /** used, but not in visible files: Android, installed apps and their data */
        val system get() = (used - counted).coerceAtLeast(0L)
    }

    @Volatile var last: Result? = null

    fun keyOf(f: File): String {
        val e = f.extension.lowercase()
        return when {
            e in MediaViewerActivity.VIDEO -> "video"
            e in MediaViewerActivity.AUDIO -> "audio"
            e in MediaViewerActivity.IMAGE -> "image"
            e in StorageActivity.DOCS -> "doc"
            e == "apk" -> "apk"
            e in StorageActivity.ARCH -> "arch"
            else -> "other"
        }
    }

    fun newCats() = listOf(
        Cat("video", T.s("Videos", "Video", "Videos"), Color.rgb(255, 99, 132)),
        Cat("audio", T.s("Music & audio", "Muzika i zvuk", "Musik & Audio"), Color.rgb(255, 176, 59)),
        Cat("image", T.s("Pictures", "Slike", "Bilder"), Color.rgb(80, 220, 160)),
        Cat("doc", T.s("Documents", "Dokumenti", "Dokumente"), Color.rgb(90, 170, 255)),
        Cat("apk", T.s("Apps (APK)", "Aplikacije (APK)", "Apps (APK)"), Color.rgb(170, 120, 255)),
        Cat("arch", T.s("Archives", "Arhive", "Archive"), Color.rgb(120, 230, 240)),
        Cat("other", T.s("Other files", "Ostali fajlovi", "Andere Dateien"), Color.rgb(180, 185, 200)),
    )

    /** Blocking. Skips the recycle bin and Android's private app folders. */
    fun scan(root: File): Result {
        val cats = newCats()
        val byKey = cats.associateBy { it.key }
        fun walk(d: File, depth: Int) {
            if (depth > 40) return
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (f.name == ".LEADTrash") continue
                    walk(f, depth + 1); continue
                }
                val c = byKey.getValue(keyOf(f))
                c.bytes += f.length()
                c.files.add(f)
            }
        }
        walk(root, 0)
        val st = runCatching { StatFs(root.absolutePath) }.getOrNull()
        val r = Result(root, cats, st?.totalBytes ?: 0L, st?.availableBytes ?: 0L)
        last = r
        return r
    }
}
