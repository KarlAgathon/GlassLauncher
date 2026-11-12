package com.glass.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Simple, ad-free file browser. Can also be opened just to pick a picture. */
class FileBrowserActivity : Activity() {

    companion object {
        const val EXTRA_PICK = "pick"   // "image" or "backup" = pick a file and return its path
        const val EXTRA_PATH = "path"
        const val EXTRA_START = "start"
        private const val REQ_PERM = 21

        /** file waiting to be moved / copied (survives leaving and re-opening folders) */
        private var clip: File? = null
        private var clipMove = true
    }

    private class Entry(val file: File?, val name: String, val icon: Int, val info: String, val up: Boolean = false,
                        val choose: Boolean = false, val action: String? = null)

    private val entries = ArrayList<Entry>()
    private var dir: File? = null
    private var pickMode: String? = null
    private val picking get() = pickMode != null
    private var askedPermission = false
    private var started = false
    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var pathView: TextView
    private lateinit var empty: TextView

    private val videoExt = MediaViewerActivity.VIDEO
    private val audioExt = MediaViewerActivity.AUDIO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(Prefs(this).lang)
        setContentView(R.layout.activity_files)
        pickMode = intent.getStringExtra(EXTRA_PICK)
        title = findViewById(R.id.title)
        pathView = findViewById(R.id.path)
        empty = findViewById(R.id.empty)
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
            show(dir)
            started = true
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
        if (requestCode == REQ_PERM && hasAccess()) show(dir)
    }

    // ------------------------------------------------------------------ listing

    private fun roots(): List<File> {
        val out = mutableListOf(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) out.add(it)
        }
        return out
    }

    private fun isRoot(f: File) = roots().any { it.absolutePath == f.absolutePath }

    private fun show(d: File?) {
        dir = d
        entries.clear()
        if (d == null) {
            title.text = T.s("Files", "Fajlovi", "Dateien")
            pathView.text = when (pickMode) {
                "image" -> T.s("Choose a picture", "Izaberi sliku", "Bild auswählen")
                "backup" -> T.s("Choose a backup (.json)", "Izaberi backup (.json)", "Backup wählen (.json)")
                "folder" -> T.s("Open the folder and choose \"Use this folder\"", "Otvori folder i izaberi \"Koristi ovaj folder\"", "Ordner öffnen und \"Diesen Ordner verwenden\" wählen")
                else -> ""
            }
            for ((i, r) in roots().withIndex()) {
                val free = runCatching { android.os.StatFs(r.absolutePath) }.getOrNull()
                val info = if (free != null) T.s("free ", "slobodno ", "frei ") + Formatter.formatShortFileSize(this, free.availableBytes) +
                    " / " + Formatter.formatShortFileSize(this, free.totalBytes) else ""
                if (i == 0) entries.add(Entry(r, T.s("Internal storage", "Interna memorija", "Interner Speicher"), R.drawable.lc_hard_drive, info))
                else entries.add(Entry(r, "USB / SD  (${r.name})", R.drawable.lc_usb, info))
            }
            if (!picking) for ((i, r) in roots().withIndex()) {
                val n = if (i == 0) T.s("internal", "interna", "intern") else r.name
                entries.add(Entry(r, T.s("What fills the storage", "Šta zauzima memoriju", "Was belegt den Speicher") + " – $n",
                    R.drawable.lc_layers, T.s("chart by file type", "grafikon po vrsti fajlova", "Diagramm nach Dateityp"), action = "storage"))
            }
        } else {
            title.text = if (isRoot(d)) T.s("Internal storage", "Interna memorija", "Interner Speicher") else d.name
            pathView.text = d.absolutePath
            entries.add(Entry(null, T.s("Back", "Nazad", "Zurück"), R.drawable.lc_arrow_up, "", up = true))
            if (pickMode == "folder") entries.add(Entry(d, T.s("✓ Use this folder", "✓ Koristi ovaj folder", "✓ Diesen Ordner verwenden"), R.drawable.lc_check, "", choose = true))
            val c = clip
            if (c != null && !picking) {
                val verb = if (clipMove) T.s("Move here", "Premjesti ovdje", "Hierher verschieben") else T.s("Copy here", "Kopiraj ovdje", "Hierher kopieren")
                entries.add(Entry(d, "$verb:  ${c.name}", if (clipMove) R.drawable.lc_move else R.drawable.lc_check, "", action = "paste"))
                entries.add(Entry(d, T.s("Cancel", "Odustani", "Abbrechen") + " (" + (if (clipMove) T.s("move", "premještanje", "Verschieben") else T.s("copy", "kopiranje", "Kopieren")) + ")",
                    R.drawable.lc_x, "", action = "cancelclip"))
            }
            val files = (d.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .filter { f -> when (pickMode) {
                    "image" -> f.isDirectory || ImageScanner.isImage(f)
                    "backup" -> f.isDirectory || f.extension.lowercase() == "json"
                    "folder" -> f.isDirectory || ImageScanner.isImage(f)
                    else -> true
                } }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            val df = DateFormat.getDateInstance(DateFormat.SHORT, T.locale)
            for (f in files) {
                val info = if (f.isDirectory) "" else "${Formatter.formatShortFileSize(this, f.length())}  ·  ${df.format(Date(f.lastModified()))}"
                entries.add(Entry(f, f.name, iconFor(f), info))
            }
        }
        empty.visibility = if (entries.size <= 1 && d != null) View.VISIBLE else View.GONE
        empty.text = T.s("This folder is empty", "Ovaj folder je prazan", "Dieser Ordner ist leer")
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun iconFor(f: File): Int {
        if (f.isDirectory) return R.drawable.lc_folder
        val e = f.extension.lowercase()
        return when {
            ImageScanner.isImage(f) -> R.drawable.lc_file_image
            e in videoExt -> R.drawable.lc_file_video
            e in audioExt -> R.drawable.lc_file_music
            e == "apk" -> R.drawable.lc_package
            else -> R.drawable.lc_file
        }
    }

    private fun goUp() {
        val d = dir ?: run { finish(); return }
        show(if (isRoot(d)) null else d.parentFile)
    }

    private fun onEntry(e: Entry) {
        if (e.up) { goUp(); return }
        when (e.action) {
            "storage" -> { e.file?.let { startActivity(Intent(this, StorageActivity::class.java).putExtra(StorageActivity.EXTRA_ROOT, it.absolutePath)) }; return }
            "paste" -> { paste(); return }
            "cancelclip" -> { clip = null; show(dir); return }
        }
        val f = e.file ?: return
        if (e.choose) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath))
            finish()
            return
        }
        if (f.isDirectory) { show(f); return }
        if (picking) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath))
            finish()
            return
        }
        open(f)
    }

    private fun open(f: File) {
        val ext = f.extension.lowercase()
        if (ext == "apk") { Updater.install(this, f); return }
        if (MediaViewerActivity.kind(f) != null) {
            startActivity(Intent(this, MediaViewerActivity::class.java).putExtra(MediaViewerActivity.EXTRA_PATH, f.absolutePath))
            return
        }
        MediaViewerActivity.openWith(this, f)
    }

    // ------------------------------------------------------------------ long press: move, copy, rename, delete …

    private fun onLong(e: Entry) {
        val f = e.file ?: return
        if (e.up || dir == null || e.action != null || e.choose) return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (f.isFile) actions.add(T.s("Open with…", "Otvori s…", "Öffnen mit…") to { MediaViewerActivity.openWith(this, f) })
        actions.add(T.s("Move", "Premjesti", "Verschieben") to { startClip(f, true) })
        actions.add(T.s("Copy", "Kopiraj", "Kopieren") to { startClip(f, false) })
        actions.add(T.s("Rename", "Preimenuj", "Umbenennen") to { rename(f) })
        actions.add(T.s("Delete", "Obriši", "Löschen") to { confirmDelete(f) })
        actions.add(T.s("Details", "Detalji", "Details") to { details(f) })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(f.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun startClip(f: File, move: Boolean) {
        clip = f
        clipMove = move
        toast(T.s("Now open the target folder and choose \"", "Sad otvori ciljni folder i izaberi \"", "Jetzt den Zielordner öffnen und \"") +
            (if (move) T.s("Move here", "Premjesti ovdje", "Hierher verschieben") else T.s("Copy here", "Kopiraj ovdje", "Hierher kopieren")) + "\"")
        show(dir)
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
        val src = clip ?: return
        val d = dir ?: return
        if (d.absolutePath == src.absolutePath || d.absolutePath.startsWith(src.absolutePath + "/")) {
            toast(T.s("Can't put a folder into itself", "Folder se ne može staviti u samog sebe", "Ordner kann nicht in sich selbst"))
            return
        }
        val move = clipMove
        val target = freeName(File(d, src.name))
        toast(if (move) T.s("Moving…", "Premještam…", "Verschiebe…") else T.s("Copying…", "Kopiram…", "Kopiere…"))
        Thread {
            val ok = runCatching {
                if (move && src.renameTo(target)) true
                else {
                    val copied = src.copyRecursively(target, overwrite = false)
                    if (copied && move) src.deleteRecursively() else copied
                }
            }.getOrDefault(false)
            runOnUiThread {
                clip = null
                toast(if (ok) T.s("Done", "Gotovo", "Fertig") else T.s("Didn't work (no access or not enough space?)",
                    "Nije uspjelo (nema pristupa ili nema mjesta?)", "Fehlgeschlagen (kein Zugriff oder kein Platz?)"))
                show(dir)
            }
        }.start()
    }

    private fun rename(f: File) {
        val input = EditText(this).apply { setText(f.name); setSelection(0, f.nameWithoutExtension.length.coerceAtMost(f.name.length)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(T.s("Rename", "Preimenuj", "Umbenennen"))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n.contains("/")) return@setPositiveButton
                val t = File(f.parentFile, n)
                if (t.exists()) toast(T.s("That name already exists", "To ime već postoji", "Name existiert schon"))
                else if (f.renameTo(t)) show(dir) else toast(T.s("Couldn't rename", "Preimenovanje nije uspjelo", "Umbenennen fehlgeschlagen"))
            }
            .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null)
            .show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(f.name)
            .setMessage(T.s("Delete this?", "Obrisati ovo?", "Wirklich löschen?"))
            .setPositiveButton(T.s("Delete", "Obriši", "Löschen")) { _, _ ->
                if (f.deleteRecursively()) show(dir)
                else toast(T.s("Couldn't delete", "Brisanje nije uspjelo", "Löschen fehlgeschlagen"))
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
                "\n" + f.absolutePath
            runOnUiThread {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(f.name).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dir == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else goUp()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ list adapter

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
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
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = entries[position]
            h.icon.setImageResource(e.icon)
            h.name.text = e.name
            h.info.text = e.info
        }
    }
}
