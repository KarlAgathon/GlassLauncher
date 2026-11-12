package com.glass.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Ad-free file manager.
 *  - start screen: storage overview (donut chart by kind of file, click a kind to see its files), drives, recycle bin
 *  - folders: sort by name / size / type / changed / arrived on the box
 *  - long press: open with, select several, move, copy, rename, delete (recycle bin or for good), details
 * Can also be opened just to pick a picture, a backup or a folder.
 */
class FileBrowserActivity : Activity() {

    companion object {
        const val EXTRA_PICK = "pick"   // "image", "backup" or "folder" = pick and return its path
        const val EXTRA_PATH = "path"
        const val EXTRA_START = "start"
        private const val REQ_PERM = 21

        /** files waiting to be moved / copied (survive leaving and re-opening folders) */
        private var clip: List<File> = emptyList()
        private var clipMove = true
    }

    private class Entry(
        val file: File?, val name: String, val icon: Int, val info: String,
        val up: Boolean = false, val choose: Boolean = false, val action: String? = null,
        val trash: Trash.Item? = null,
    )

    private val entries = ArrayList<Entry>()
    private var dir: File? = null
    /** null = normal folders, "trash" = recycle bin, "cat" = all files of one kind */
    private var virtual: String? = null
    private var catKey = ""
    private var pickMode: String? = null
    private val picking get() = pickMode != null
    private var askedPermission = false
    private var selectMode = false
    private val selected = LinkedHashSet<String>()
    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var pathView: TextView
    private lateinit var empty: TextView
    private lateinit var dash: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var side: LinearLayout

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        T.init(prefs.lang)
        setContentView(R.layout.activity_files)
        pickMode = intent.getStringExtra(EXTRA_PICK)
        title = findViewById(R.id.title)
        pathView = findViewById(R.id.path)
        empty = findViewById(R.id.empty)
        dash = findViewById(R.id.dash)
        selBar = findViewById(R.id.selBar)
        side = findViewById(R.id.side)
        selBar.background = GradientDrawable().apply {
            cornerRadius = dp(22f); setColor(Color.argb(150, 12, 14, 26)); setStroke(dp(1f).toInt(), Color.argb(90, 255, 255, 255))
        }
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val wp = findViewById<ImageView>(R.id.wallpaper)
        val bd = Backdrop.blurred
        if (bd != null) wp.setImageBitmap(bd) else wp.setImageResource(R.drawable.wallpaper_default)
        intent.getStringExtra(EXTRA_START)?.let { val f = File(it); if (f.isDirectory) dir = f }
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            show()
        } else if (!askedPermission) {
            askedPermission = true
            requestAccess()
        } else {
            title.text = T.s("Files", "Fajlovi", "Dateien")
            empty.visibility = View.VISIBLE
            empty.text = T.s("Storage access was not allowed.", "Pristup memoriji nije dozvoljen.", "Speicherzugriff wurde nicht erlaubt.")
        }
    }

    // ------------------------------------------------------------------ permission

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && hasAccess()) show()
    }

    // ------------------------------------------------------------------ helpers

    private fun roots(): List<File> = Trash.roots()

    private fun isRoot(f: File) = roots().any { it.absolutePath == f.absolutePath }

    private fun iconFor(f: File): Int {
        if (f.isDirectory) return R.drawable.lc_folder
        return when (StorageScan.keyOf(f)) {
            "image" -> R.drawable.lc_file_image
            "video" -> R.drawable.lc_file_video
            "audio" -> R.drawable.lc_file_music
            "apk" -> R.drawable.lc_package
            "arch" -> R.drawable.lc_archive
            else -> R.drawable.lc_file
        }
    }

    /** When the file arrived on the box (creation time where the file system keeps it). */
    private fun added(f: File): Long =
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                .creationTime().toMillis()
        }.getOrDefault(f.lastModified()) else f.lastModified()

    private val sortNames get() = listOf(
        T.s("Name", "Naziv", "Name"), T.s("Size", "Veličina", "Größe"), T.s("Type (extension)", "Vrsta (ekstenzija)", "Typ (Endung)"),
        T.s("Date changed", "Datum izmjene", "Änderungsdatum"), T.s("Date arrived on the box", "Datum kad je stigao na box", "Datum auf die Box gekommen"))

    private fun sorted(files: List<File>, foldersFirst: Boolean = true): List<File> {
        val sizeCache = HashMap<File, Long>()
        fun size(f: File) = sizeCache.getOrPut(f) { if (f.isDirectory) 0L else f.length() }
        val addedCache = HashMap<File, Long>()
        val cmp: Comparator<File> = when (prefs.fileSort) {
            1 -> compareBy<File> { size(it) }
            2 -> compareBy<File>({ it.extension.lowercase() }, { it.name.lowercase() })
            3 -> compareBy<File> { it.lastModified() }
            4 -> compareBy<File> { addedCache.getOrPut(it) { added(it) } }
            else -> compareBy<File> { it.name.lowercase() }
        }
        val c = if (prefs.fileSortDesc) cmp.reversed() else cmp
        return if (foldersFirst) files.sortedWith(compareBy<File> { !it.isDirectory }.then(c)) else files.sortedWith(c)
    }

    private fun infoFor(f: File, withPath: Boolean = false): String {
        val df = DateFormat.getDateInstance(DateFormat.SHORT, T.locale)
        val date = if (prefs.fileSort == 4) added(f) else f.lastModified()
        val base = if (f.isDirectory) df.format(Date(date))
        else "${Formatter.formatShortFileSize(this, f.length())}  ·  ${df.format(Date(date))}"
        return if (withPath) (f.parentFile?.name ?: "") + "  ·  " + base else base
    }

    private fun fileEntry(f: File, withPath: Boolean = false): Entry {
        val sel = f.absolutePath in selected
        return Entry(f, f.name, if (sel) R.drawable.lc_check else iconFor(f), infoFor(f, withPath))
    }

    /** action rows at the top of a list: selection, paste, sort */
    private fun addTopActions(canPaste: Boolean) {
        if (picking) return
        if (!selectMode) entries.add(Entry(null, T.s("Select several files", "Označi više fajlova", "Mehrere Dateien auswählen"),
            R.drawable.lc_check, T.s("then OK on each file", "pa OK na svaki fajl", "dann OK auf jede Datei"), action = "selStart"))
        if (selectMode) {
            entries.add(Entry(null, T.s("Selected: ", "Označeno: ", "Ausgewählt: ") + selected.size + "   –   " +
                T.s("what to do…", "šta uraditi…", "was tun…"), R.drawable.lc_check, "", action = "selActions"))
            entries.add(Entry(null, T.s("Select all", "Označi sve", "Alle auswählen"), R.drawable.lc_layout_grid, "", action = "selAll"))
            entries.add(Entry(null, T.s("Stop selecting", "Završi označavanje", "Auswahl beenden"), R.drawable.lc_x, "", action = "selEnd"))
        }
        if (canPaste && clip.isNotEmpty()) {
            val what = if (clip.size == 1) clip[0].name else "${clip.size} " + T.s("items", "stavki", "Elemente")
            val verb = if (clipMove) T.s("Move here", "Premjesti ovdje", "Hierher verschieben") else T.s("Copy here", "Kopiraj ovdje", "Hierher kopieren")
            entries.add(Entry(null, "$verb:  $what", if (clipMove) R.drawable.lc_move else R.drawable.lc_check, "", action = "paste"))
            entries.add(Entry(null, T.s("Cancel", "Odustani", "Abbrechen") + " (" +
                (if (clipMove) T.s("move", "premještanje", "Verschieben") else T.s("copy", "kopiranje", "Kopieren")) + ")",
                R.drawable.lc_x, "", action = "cancelclip"))
        }
        entries.add(Entry(null, T.s("Sort: ", "Sortiraj: ", "Sortieren: ") + sortNames[prefs.fileSort.coerceIn(0, 4)] +
            if (prefs.fileSortDesc) "  ↓" else "  ↑", R.drawable.lc_columns_3, "", action = "sort"))
    }

    // ------------------------------------------------------------------ listing

    private fun show() {
        entries.clear()
        dash.visibility = View.GONE
        when {
            virtual == "trash" -> showTrash()
            virtual == "cat" -> showCategory()
            dir == null -> showRoots()
            else -> showFolder(dir!!)
        }
        adapter.notifyDataSetChanged()
        updateSelBar()
        buildSide()
        list.scrollToPosition(0)
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    /** Left side bar: the important places, always one click away. Content opens on the right. */
    private fun buildSide() {
        side.removeAllViews()
        val d = resources.displayMetrics.density
        val ext = Environment.getExternalStorageDirectory()
        val places = ArrayList<Triple<String, Int, () -> Unit>>()
        val here = dir?.absolutePath
        fun folder(name: String, icon: Int, f: File) {
            if (f.isDirectory) places.add(Triple(name, icon) { dir = f; virtual = null; selectMode = false; selected.clear(); show() })
        }
        if (!picking) places.add(Triple(T.s("Overview", "Pregled", "Übersicht"), R.drawable.lc_layers) {
            dir = null; virtual = null; selectMode = false; selected.clear(); show()
        })
        folder(T.s("Internal storage", "Interna memorija", "Interner Speicher"), R.drawable.lc_hard_drive, ext)
        folder("Download", R.drawable.lc_download, File(ext, Environment.DIRECTORY_DOWNLOADS))
        folder(T.s("Movies", "Filmovi", "Filme"), R.drawable.lc_file_video, File(ext, Environment.DIRECTORY_MOVIES))
        folder(T.s("Music", "Muzika", "Musik"), R.drawable.lc_file_music, File(ext, Environment.DIRECTORY_MUSIC))
        folder(T.s("Pictures", "Slike", "Bilder"), R.drawable.lc_file_image, File(ext, Environment.DIRECTORY_PICTURES))
        folder("DCIM", R.drawable.lc_image, File(ext, Environment.DIRECTORY_DCIM))
        folder(T.s("Documents", "Dokumenti", "Dokumente"), R.drawable.lc_file, File(ext, "Documents"))
        for (r in roots().drop(1)) folder("USB  ${r.name}", R.drawable.lc_usb, r)
        if (!picking) places.add(Triple(T.s("Recycle bin", "Koš za smeće", "Papierkorb"), R.drawable.lc_trash_2) {
            virtual = "trash"; selectMode = false; selected.clear(); show()
        })
        for ((name, icon, act) in places) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
                isFocusable = true; isClickable = true
            }
            v.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()))
            v.addView(TextView(this).apply {
                text = name; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Fonts.regular(this@FileBrowserActivity); isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f).toInt() })
            // the place that is open now is marked
            val current = when {
                name == T.s("Recycle bin", "Koš za smeće", "Papierkorb") -> virtual == "trash"
                name == T.s("Overview", "Pregled", "Übersicht") -> dir == null && virtual == null
                else -> false
            }
            val bgOff: android.graphics.drawable.Drawable? = if (current) GradientDrawable().apply {
                cornerRadius = dp(12f); setColor(Color.argb(45, 255, 255, 255)) } else null
            v.background = bgOff
            v.setOnFocusChangeListener { view, has -> view.background = if (has) GlassDrawable(d, 12, true) else bgOff }
            v.setOnClickListener { act() }
            side.addView(v)
        }
        if (here != null) for (i in 0 until side.childCount) { /* folders keep plain look; the path line shows where you are */ }
    }

    /**
     * Bar under the list while selecting: count + Delete, Move, Copy, Cut, Share, Rename, Properties
     * (Restore / Delete for good in the recycle bin). Always on screen, press DOWN to reach it.
     */
    private fun updateSelBar() {
        selBar.removeAllViews()
        if (!selectMode || picking) { selBar.visibility = View.GONE; return }
        selBar.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        selBar.addView(TextView(this).apply {
            text = T.s("Selected: ", "Označeno: ", "Ausgewählt: ") + selected.size
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Fonts.medium(this@FileBrowserActivity)
            setPadding(dp(6f).toInt(), 0, dp(18f).toInt(), 0)
        })
        val files = selectedFiles()
        val acts: List<Pair<String, () -> Unit>> = if (virtual == "trash") {
            val items = Trash.list().filter { it.file.absolutePath in selected }
            listOf(
                T.s("Restore", "Vrati", "Wiederherstellen") to { runBusy({ items.count { Trash.restore(it) } }) { selectMode = false; selected.clear() } },
                T.s("Delete for good", "Obriši trajno", "Endgültig löschen") to { confirmPurge(items) },
            )
        } else fileActions(files)
        for ((label, act) in acts) {
            val b = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.regular(this@FileBrowserActivity)
                setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
                isFocusable = true; isClickable = true
                alpha = if (files.isEmpty() && virtual != "trash") 0.45f else 1f
                setOnFocusChangeListener { v, has -> v.background = if (has) GlassDrawable(d, 14, true) else null }
                setOnClickListener {
                    if (selected.isEmpty()) toast(T.s("Nothing selected – press OK on files", "Ništa nije označeno – pritisni OK na fajlove", "Nichts ausgewählt – OK auf Dateien drücken"))
                    else act()
                }
            }
            selBar.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4f).toInt() })
        }
    }

    private fun showRoots() {
        title.text = T.s("Files", "Fajlovi", "Dateien")
        pathView.text = when (pickMode) {
            "image" -> T.s("Choose a picture", "Izaberi sliku", "Bild auswählen")
            "backup" -> T.s("Choose a backup (.json)", "Izaberi backup (.json)", "Backup wählen (.json)")
            "folder" -> T.s("Open the folder and choose \"Use this folder\"", "Otvori folder i izaberi \"Koristi ovaj folder\"", "Ordner öffnen und \"Diesen Ordner verwenden\" wählen")
            else -> ""
        }
        for ((i, r) in roots().withIndex()) {
            val st = runCatching { android.os.StatFs(r.absolutePath) }.getOrNull()
            val info = if (st != null) T.s("free ", "slobodno ", "frei ") + Formatter.formatShortFileSize(this, st.availableBytes) +
                " / " + Formatter.formatShortFileSize(this, st.totalBytes) else ""
            if (i == 0) entries.add(Entry(r, T.s("Internal storage", "Interna memorija", "Interner Speicher"), R.drawable.lc_hard_drive, info))
            else {
                entries.add(Entry(r, "USB / SD  (${r.name})", R.drawable.lc_usb, info))
                if (!picking) entries.add(Entry(r, T.s("What fills ", "Šta zauzima ", "Was belegt ") + r.name, R.drawable.lc_layers,
                    T.s("chart by file type", "grafikon po vrsti fajlova", "Diagramm nach Dateityp"), action = "storage"))
            }
        }
        if (!picking) {
            val bin = Trash.list()
            entries.add(Entry(null, T.s("Recycle bin", "Koš za smeće", "Papierkorb") + "  (${bin.size})", R.drawable.lc_trash_2,
                if (bin.isEmpty()) T.s("empty", "prazan", "leer") else "", action = "trash"))
            showDashboard()
        }
        empty.visibility = View.GONE
    }

    private fun showFolder(d: File) {
        title.text = if (isRoot(d)) T.s("Internal storage", "Interna memorija", "Interner Speicher") else d.name
        pathView.text = d.absolutePath
        entries.add(Entry(null, T.s("Back", "Nazad", "Zurück"), R.drawable.lc_arrow_up, "", up = true))
        if (pickMode == "folder") entries.add(Entry(d, T.s("✓ Use this folder", "✓ Koristi ovaj folder", "✓ Diesen Ordner verwenden"), R.drawable.lc_check, "", choose = true))
        addTopActions(canPaste = true)
        val files = (d.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .filter { f -> when (pickMode) {
                "image" -> f.isDirectory || ImageScanner.isImage(f)
                "backup" -> f.isDirectory || f.extension.lowercase() == "json"
                "folder" -> f.isDirectory || ImageScanner.isImage(f)
                else -> true
            } }
        for (f in sorted(files)) entries.add(fileEntry(f))
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        empty.text = T.s("This folder is empty", "Ovaj folder je prazan", "Dieser Ordner ist leer")
    }

    private fun showCategory() {
        val r = StorageScan.last
        val c = r?.cats?.firstOrNull { it.key == catKey }
        title.text = c?.name ?: T.s("Files", "Fajlovi", "Dateien")
        pathView.text = if (c != null) "${c.count} " + T.s("files", "fajlova", "Dateien") + "  ·  " + Formatter.formatShortFileSize(this, c.bytes) else ""
        entries.add(Entry(null, T.s("Back", "Nazad", "Zurück"), R.drawable.lc_arrow_up, "", up = true))
        addTopActions(canPaste = false)
        val files = c?.files?.filter { it.exists() } ?: emptyList()
        for (f in sorted(files, foldersFirst = false)) entries.add(fileEntry(f, withPath = true))
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        empty.text = T.s("No files of this kind", "Nema fajlova ove vrste", "Keine Dateien dieser Art")
    }

    private fun showTrash() {
        val bin = Trash.list()
        title.text = T.s("Recycle bin", "Koš za smeće", "Papierkorb")
        pathView.text = "${bin.size} " + T.s("items", "stavki", "Elemente")
        entries.add(Entry(null, T.s("Back", "Nazad", "Zurück"), R.drawable.lc_arrow_up, "", up = true))
        if (selectMode) {
            entries.add(Entry(null, T.s("Selected: ", "Označeno: ", "Ausgewählt: ") + selected.size + "   –   " +
                T.s("restore or delete…", "vrati ili obriši…", "wiederherstellen oder löschen…"), R.drawable.lc_check, "", action = "selActions"))
            entries.add(Entry(null, T.s("Select all", "Označi sve", "Alle auswählen"), R.drawable.lc_layout_grid, "", action = "selAll"))
            entries.add(Entry(null, T.s("Stop selecting", "Završi označavanje", "Auswahl beenden"), R.drawable.lc_x, "", action = "selEnd"))
        }
        if (bin.isNotEmpty()) entries.add(Entry(null, T.s("Empty the recycle bin", "Isprazni koš", "Papierkorb leeren"), R.drawable.lc_trash_2,
            "", action = "emptyTrash"))
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, T.locale)
        for (it in bin) {
            val sel = it.file.absolutePath in selected
            val size = if (it.file.isDirectory) "" else Formatter.formatShortFileSize(this, it.file.length()) + "  ·  "
            entries.add(Entry(it.file, it.name, if (sel) R.drawable.lc_check else iconFor(it.file),
                size + T.s("deleted ", "obrisano ", "gelöscht ") + df.format(Date(it.deletedAt)), trash = it))
        }
        empty.visibility = if (bin.isEmpty()) View.VISIBLE else View.GONE
        empty.text = T.s("The recycle bin is empty", "Koš je prazan", "Der Papierkorb ist leer")
    }

    // ------------------------------------------------------------------ storage overview on the start screen

    private var scanning = false

    private fun showDashboard() {
        val root = roots()[0]
        val r = StorageScan.last?.takeIf { it.root.absolutePath == root.absolutePath }
        buildDashboard(r)
        if (!scanning) {
            scanning = true
            Thread {
                val res = runCatching { StorageScan.scan(root) }.getOrNull()
                runOnUiThread {
                    scanning = false
                    if (res != null && dir == null && virtual == null && !picking) buildDashboard(res)
                }
            }.start()
        }
    }

    private fun buildDashboard(r: StorageScan.Result?) {
        dash.removeAllViews()
        dash.visibility = View.VISIBLE
        val pie = StorageActivity.PieView(this)
        dash.addView(pie, LinearLayout.LayoutParams(dp(190f).toInt(), dp(190f).toInt()))
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(20f); setColor(Color.argb(45, 255, 255, 255)); setStroke(dp(1f).toInt(), Color.argb(60, 255, 255, 255))
            }
        }
        dash.addView(legend, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(28f).toInt() })
        if (r == null) {
            pie.set(emptyList(), "…", T.s("counting files", "brojim fajlove", "zähle Dateien"))
            legend.addView(TextView(this).apply {
                text = T.s("Counting files…", "Brojim fajlove…", "Zähle Dateien…")
                setTextColor(Color.argb(190, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            return
        }
        val freeColor = Color.argb(70, 255, 255, 255)
        val sysColor = Color.rgb(110, 115, 140)
        val parts = r.cats.filter { it.bytes > 0 }.map { it.bytes.toFloat() to it.color } +
            listOf(r.system.toFloat() to sysColor, r.free.toFloat() to freeColor)
        pie.set(parts, Formatter.formatShortFileSize(this, r.used),
            T.s("used of ", "zauzeto od ", "belegt von ") + Formatter.formatShortFileSize(this, r.total))

        fun row(color: Int, name: String, bytes: Long, click: (() -> Unit)?) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f).toInt(), dp(5f).toInt(), dp(10f).toInt(), dp(5f).toInt())
            }
            v.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } },
                LinearLayout.LayoutParams(dp(12f).toInt(), dp(12f).toInt()).apply { marginEnd = dp(12f).toInt() })
            v.addView(TextView(this).apply {
                text = name; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.regular(this@FileBrowserActivity)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val pct = if (r.total > 0) bytes * 100.0 / r.total else 0.0
            v.addView(TextView(this).apply {
                text = Formatter.formatShortFileSize(this@FileBrowserActivity, bytes) + String.format(java.util.Locale.US, "   %.1f %%", pct)
                setTextColor(Color.argb(210, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Fonts.medium(this@FileBrowserActivity)
            })
            if (click != null) {
                v.isFocusable = true; v.isClickable = true
                val d = resources.displayMetrics.density
                v.setOnFocusChangeListener { view, has -> view.background = if (has) GlassDrawable(d, 12, true) else null }
                v.setOnClickListener { click() }
            }
            legend.addView(v)
        }
        for (c in r.cats.filter { it.bytes > 0 }.sortedByDescending { it.bytes }) {
            row(c.color, "${c.name}  (${c.count})", c.bytes) {
                virtual = "cat"; catKey = c.key; selectMode = false; selected.clear()
                if (prefs.fileSort == 0) { prefs.fileSort = 1; prefs.fileSortDesc = true }   // biggest first
                show()
            }
        }
        row(sysColor, T.s("System & installed apps", "Sistem i instalirane aplikacije", "System & installierte Apps"), r.system, null)
        row(freeColor, T.s("Free", "Slobodno", "Frei"), r.free, null)
    }

    // ------------------------------------------------------------------ clicks

    private fun goUp() {
        if (virtual != null) { virtual = null; selectMode = false; selected.clear(); show(); return }
        val d = dir ?: run { finish(); return }
        selectMode = false; selected.clear()
        dir = if (isRoot(d)) null else d.parentFile
        show()
    }

    private fun onEntry(e: Entry) {
        if (e.up) { goUp(); return }
        when (e.action) {
            "storage" -> { e.file?.let { startActivity(Intent(this, StorageActivity::class.java).putExtra(StorageActivity.EXTRA_ROOT, it.absolutePath)) }; return }
            "trash" -> { virtual = "trash"; selectMode = false; selected.clear(); show(); return }
            "emptyTrash" -> { confirmEmptyTrash(); return }
            "paste" -> { paste(); return }
            "cancelclip" -> { clip = emptyList(); show(); return }
            "sort" -> { sortMenu(); return }
            "selAll" -> { entries.forEach { en -> if (en.action == null && !en.up && !en.choose) en.file?.let { selected.add(it.absolutePath) } }; show(); return }
            "selEnd" -> { selectMode = false; selected.clear(); show(); return }
            "selStart" -> { selectMode = true; selected.clear(); show2()
                toast(T.s("Press OK on the files you want", "Pritisni OK na fajlove koje želiš", "OK auf die gewünschten Dateien drücken")); return }
            "selActions" -> { selectionMenu(); return }
        }
        val f = e.file ?: return
        if (selectMode && !e.choose) { toggle(f); return }
        if (e.trash != null) { trashItemMenu(e.trash); return }
        if (e.choose) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath))
            finish()
            return
        }
        if (f.isDirectory) { dir = f; virtual = null; show(); return }
        if (picking) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath))
            finish()
            return
        }
        open(f)
    }

    private fun toggle(f: File) {
        val p = f.absolutePath
        if (!selected.remove(p)) selected.add(p)
        val pos = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        val focus = list.focusedChild?.let { list.getChildAdapterPosition(it) } ?: -1
        show2()
        list.scrollToPosition(pos.coerceAtLeast(0))
        if (focus >= 0) list.post { list.findViewHolderForAdapterPosition(focus)?.itemView?.requestFocus() }
    }

    /** refresh the list without jumping to the top */
    private fun show2() {
        entries.clear()
        when {
            virtual == "trash" -> showTrash()
            virtual == "cat" -> showCategory()
            dir == null -> showRoots()
            else -> showFolder(dir!!)
        }
        adapter.notifyDataSetChanged()
        updateSelBar()
        buildSide()
    }

    private fun open(f: File) {
        val ext = f.extension.lowercase()
        if (ext == "apk") { Updater.install(this, f); return }
        MediaViewerActivity.openDefault(this, f)
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun pickList(title: String, items: List<Pair<String, () -> Unit>>) {
        dialog().setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun sortMenu() {
        val items = sortNames.mapIndexed { i, n -> ((if (i == prefs.fileSort) "●  " else "     ") + n) to { prefs.fileSort = i; show() } } +
            ((if (prefs.fileSortDesc) T.s("Order: biggest / newest / Z first  ↓", "Redoslijed: najveće / najnovije / Ž prvo  ↓", "Reihenfolge: größte / neueste / Z zuerst  ↓")
              else T.s("Order: smallest / oldest / A first  ↑", "Redoslijed: najmanje / najstarije / A prvo  ↑", "Reihenfolge: kleinste / älteste / A zuerst  ↑")) to
                { prefs.fileSortDesc = !prefs.fileSortDesc; show() })
        pickList(T.s("Sort by", "Sortiraj po", "Sortieren nach"), items)
    }

    // ------------------------------------------------------------------ long press

    private fun onLong(e: Entry) {
        val f = e.file ?: return
        if (e.up || e.action != null || e.choose || picking) return
        if (e.trash != null) { trashItemMenu(e.trash); return }
        if (selectMode) { toggle(f); return }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(T.s("Select several", "Označi više", "Mehrere auswählen") to { selectMode = true; selected.clear(); selected.add(f.absolutePath); show2() })
        actions.addAll(fileActions(listOf(f)))
        pickList(f.name, actions)
    }

    private fun selectedFiles() = selected.map { File(it) }.filter { it.exists() }

    /** Delete, Move, Share, Copy, Cut, Rename, Properties – for one file or a selection. */
    private fun fileActions(files: List<File>): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        out.add(T.s("Delete", "Obriši", "Löschen") to { deleteDialog(files) })
        out.add(T.s("Move", "Premjesti", "Verschieben") to { startClip(files, true) })
        if (files.any { it.isFile }) out.add(T.s("Share", "Podijeli", "Teilen") to { MediaViewerActivity.share(this, files) })
        out.add(T.s("Copy", "Kopiraj", "Kopieren") to { startClip(files, false) })
        out.add(T.s("Cut", "Izreži", "Ausschneiden") to { startClip(files, true) })
        if (files.size == 1) out.add(T.s("Rename", "Preimenuj", "Umbenennen") to { rename(files[0]) })
        out.add(T.s("Properties", "Svojstva", "Eigenschaften") to { if (files.size == 1) details(files[0]) else detailsMany(files) })
        if (files.size == 1 && files[0].isFile) out.add(T.s("Open with…", "Otvori s…", "Öffnen mit…") to { MediaViewerActivity.openWith(this, files[0]) })
        return out
    }

    private fun detailsMany(files: List<File>) {
        Thread {
            var size = 0L; var count = 0
            for (f in files) f.walkTopDown().filter { it.isFile }.forEach { size += it.length(); count++ }
            val msg = T.s("Items: ", "Stavki: ", "Elemente: ") + files.size +
                "\n" + T.s("Files inside: ", "Fajlova ukupno: ", "Dateien insgesamt: ") + count +
                "\n" + T.s("Size: ", "Veličina: ", "Größe: ") + Formatter.formatFileSize(this, size) +
                "\n" + (files.first().parentFile?.absolutePath ?: "")
            runOnUiThread { dialog().setTitle(T.s("Properties", "Svojstva", "Eigenschaften")).setMessage(msg).setPositiveButton("OK", null).show() }
        }.start()
    }

    private fun selectionMenu() {
        val files = selectedFiles()
        if (files.isEmpty()) { toast(T.s("Nothing selected – press OK on files", "Ništa nije označeno – pritisni OK na fajlove", "Nichts ausgewählt – OK auf Dateien drücken")); return }
        val n = "${files.size}"
        if (virtual == "trash") {
            val items = Trash.list().filter { it.file.absolutePath in selected }
            pickList(T.s("Selected: ", "Označeno: ", "Ausgewählt: ") + n, listOf(
                T.s("Restore", "Vrati", "Wiederherstellen") to { runBusy({ items.count { Trash.restore(it) } }) { selectMode = false; selected.clear() } },
                T.s("Delete for good", "Obriši trajno", "Endgültig löschen") to { confirmPurge(items) },
            ))
            return
        }
        val total = files.sumOf { if (it.isFile) it.length() else 0L }
        pickList(T.s("Selected: ", "Označeno: ", "Ausgewählt: ") + n + "  (" + Formatter.formatShortFileSize(this, total) + ")", fileActions(files))
    }

    // ------------------------------------------------------------------ delete: recycle bin or for good

    private fun deleteDialog(files: List<File>) {
        val what = if (files.size == 1) files[0].name else "${files.size} " + T.s("items", "stavki", "Elemente")
        if (files.any { Trash.isTrash(it) }) {
            dialog().setTitle(what).setMessage(T.s("Delete for good?", "Obrisati trajno?", "Endgültig löschen?"))
                .setPositiveButton(T.s("Delete for good", "Obriši trajno", "Endgültig löschen")) { _, _ -> purgeFiles(files) }
                .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null).show()
            return
        }
        dialog().setTitle(what)
            .setMessage(T.s("Recycle bin = you can get it back later.\nFor good = gone, frees the space right away.",
                "Koš za smeće = možeš ga kasnije vratiti.\nTrajno = nestaje, mjesto se odmah oslobađa.",
                "Papierkorb = später wiederherstellbar.\nEndgültig = weg, der Platz wird sofort frei."))
            .setPositiveButton(T.s("To the recycle bin", "U koš za smeće", "In den Papierkorb")) { _, _ ->
                runBusy({ files.count { Trash.move(it) } }) { selectMode = false; selected.clear() }
            }
            .setNeutralButton(T.s("Delete for good", "Obriši trajno", "Endgültig löschen")) { _, _ -> purgeFiles(files) }
            .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null)
            .show()
    }

    private fun purgeFiles(files: List<File>) =
        runBusy({ files.count { it.deleteRecursively() } }) { selectMode = false; selected.clear() }

    private fun confirmPurge(items: List<Trash.Item>) {
        dialog().setTitle("${items.size} " + T.s("items", "stavki", "Elemente"))
            .setMessage(T.s("Delete for good?", "Obrisati trajno?", "Endgültig löschen?"))
            .setPositiveButton(T.s("Delete for good", "Obriši trajno", "Endgültig löschen")) { _, _ ->
                runBusy({ items.count { Trash.purge(it) } }) { selectMode = false; selected.clear() }
            }
            .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null).show()
    }

    private fun confirmEmptyTrash() {
        dialog().setTitle(T.s("Empty the recycle bin", "Isprazni koš", "Papierkorb leeren"))
            .setMessage(T.s("Everything in the bin will be deleted for good.", "Sve iz koša će biti trajno obrisano.", "Alles im Papierkorb wird endgültig gelöscht."))
            .setPositiveButton(T.s("Empty", "Isprazni", "Leeren")) { _, _ -> runBusy({ Trash.empty() }) {} }
            .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null).show()
    }

    private fun trashItemMenu(it: Trash.Item) {
        if (selectMode) { toggle(it.file); return }
        pickList(it.name, listOf(
            T.s("Restore to ", "Vrati u ", "Wiederherstellen nach ") + (File(it.original).parentFile?.name ?: "") to
                { runBusy({ if (Trash.restore(it)) 1 else 0 }) {} },
            T.s("Delete for good", "Obriši trajno", "Endgültig löschen") to { confirmPurge(listOf(it)) },
            T.s("Select several", "Označi više", "Mehrere auswählen") to { selectMode = true; selected.clear(); selected.add(it.file.absolutePath); show2() },
        ))
    }

    /** Runs a file job in the background, then reports how many items worked and refreshes. */
    private fun runBusy(job: () -> Int, after: () -> Unit) {
        toast(T.s("Working…", "Radim…", "Arbeite…"))
        Thread {
            val n = runCatching { job() }.getOrDefault(0)
            runOnUiThread {
                StorageScan.last = null
                after()
                toast(T.s("Done: ", "Gotovo: ", "Fertig: ") + n)
                show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ move / copy / rename / details

    private fun startClip(files: List<File>, move: Boolean) {
        clip = files
        clipMove = move
        selectMode = false; selected.clear()
        if (virtual != null) { virtual = null; dir = null }
        toast(T.s("Now open the target folder and choose \"", "Sad otvori ciljni folder i izaberi \"", "Jetzt den Zielordner öffnen und \"") +
            (if (move) T.s("Move here", "Premjesti ovdje", "Hierher verschieben") else T.s("Copy here", "Kopiraj ovdje", "Hierher kopieren")) + "\"")
        show()
    }

    private fun freeName(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = if (target.isDirectory || target.extension.isEmpty()) "" else "." + target.extension
        var i = 1
        while (true) {
            val t = File(target.parentFile, "$base ($i)$ext")
            if (!t.exists()) return t
            i++
        }
    }

    private fun paste() {
        val d = dir ?: return
        val items = clip
        if (items.any { d.absolutePath == it.absolutePath || d.absolutePath.startsWith(it.absolutePath + "/") }) {
            toast(T.s("Can't put a folder into itself", "Folder se ne može staviti u samog sebe", "Ordner kann nicht in sich selbst"))
            return
        }
        val move = clipMove
        runBusy({
            items.count { src ->
                val target = freeName(File(d, src.name))
                runCatching {
                    if (move && src.renameTo(target)) true
                    else {
                        val copied = src.copyRecursively(target, overwrite = false)
                        if (copied && move) src.deleteRecursively() else copied
                    }
                }.getOrDefault(false)
            }
        }) { clip = emptyList() }
    }

    private fun rename(f: File) {
        val input = EditText(this).apply { setText(f.name); setSelection(0, f.nameWithoutExtension.length.coerceAtMost(f.name.length)) }
        dialog().setTitle(T.s("Rename", "Preimenuj", "Umbenennen"))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n.contains("/")) return@setPositiveButton
                val t = File(f.parentFile, n)
                if (t.exists()) toast(T.s("That name already exists", "To ime već postoji", "Name existiert schon"))
                else if (f.renameTo(t)) show2() else toast(T.s("Couldn't rename", "Preimenovanje nije uspjelo", "Umbenennen fehlgeschlagen"))
            }
            .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null)
            .show()
    }

    private fun details(f: File) {
        Thread {
            val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            val count = if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, T.locale)
            val msg = T.s("Size: ", "Veličina: ", "Größe: ") + Formatter.formatFileSize(this, size) +
                (if (f.isDirectory) "\n" + T.s("Files: ", "Fajlova: ", "Dateien: ") + count else "") +
                "\n" + T.s("Changed: ", "Promijenjeno: ", "Geändert: ") + df.format(Date(f.lastModified())) +
                "\n" + T.s("Arrived on the box: ", "Stiglo na box: ", "Auf die Box gekommen: ") + df.format(Date(added(f))) +
                "\n" + f.absolutePath
            runOnUiThread { dialog().setTitle(f.name).setMessage(msg).setPositiveButton("OK", null).show() }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selectMode -> { selectMode = false; selected.clear(); show2() }
            virtual != null -> goUp()
            dir == null -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            else -> goUp()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ list adapter

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: ImageView = v.findViewById(R.id.check)
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            val h = VH(v)
            val d = resources.displayMetrics.density
            v.setOnFocusChangeListener { view, has -> view.background = if (has) GlassDrawable(d, 12, true) else null }
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onEntry(entries[p])
            }
            v.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onLong(entries[p])
                true
            }
            // the little box on the left: press LEFT on a file, then OK to tick it (selects several files)
            h.check.setOnFocusChangeListener { view, has -> view.alpha = if (has) 1f else 0.85f
                view.scaleX = if (has) 1.25f else 1f; view.scaleY = view.scaleX }
            h.check.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                val f = entries[p].file ?: return@setOnClickListener
                val path = f.absolutePath
                if (!selected.remove(path)) selected.add(path)
                selectMode = selected.isNotEmpty()
                show2()
                list.post { list.findViewHolderForAdapterPosition(p)?.itemView?.findViewById<View>(R.id.check)?.requestFocus() }
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = entries[position]
            val checkable = e.file != null && e.action == null && !e.up && !e.choose && !picking && (dir != null || virtual != null)
            h.check.visibility = if (checkable) View.VISIBLE else View.GONE
            if (checkable) {
                val on = e.file!!.absolutePath in selected
                h.check.setImageResource(if (on) R.drawable.lc_check else 0)
                h.check.background = GradientDrawable().apply {
                    cornerRadius = dp(6f)
                    setStroke(dp(2f).toInt(), Color.argb(if (on) 255 else 170, 255, 255, 255))
                    setColor(if (on) Color.argb(200, 60, 140, 255) else Color.TRANSPARENT)
                }
            }
            h.icon.setImageResource(if (e.icon == R.drawable.lc_check && checkable) iconFor(e.file!!) else e.icon)
            h.name.text = e.name
            h.info.text = e.info
        }
    }
}
