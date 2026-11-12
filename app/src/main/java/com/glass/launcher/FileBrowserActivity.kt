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
    }

    private class Entry(val file: File?, val name: String, val icon: Int, val info: String, val up: Boolean = false)

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

    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "wmv", "flv")
    private val audioExt = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus")

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
                else -> ""
            }
            for ((i, r) in roots().withIndex()) {
                if (i == 0) entries.add(Entry(r, T.s("Internal storage", "Interna memorija", "Interner Speicher"), R.drawable.lc_hard_drive, ""))
                else entries.add(Entry(r, "USB / SD  (${r.name})", R.drawable.lc_usb, ""))
            }
        } else {
            title.text = if (isRoot(d)) T.s("Internal storage", "Interna memorija", "Interner Speicher") else d.name
            pathView.text = d.absolutePath
            entries.add(Entry(null, T.s("Back", "Nazad", "Zurück"), R.drawable.lc_arrow_up, "", up = true))
            val files = (d.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .filter { f -> when (pickMode) {
                    "image" -> f.isDirectory || ImageScanner.isImage(f)
                    "backup" -> f.isDirectory || f.extension.lowercase() == "json"
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
        val f = e.file ?: return
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
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            toast(T.s("No app can open this file", "Nijedna aplikacija ne može otvoriti ovaj fajl", "Keine App kann diese Datei öffnen"))
        } catch (e: Exception) {
            toast(T.s("Can't open this file", "Ne mogu otvoriti ovaj fajl", "Datei kann nicht geöffnet werden"))
        }
    }

    private fun onLong(e: Entry) {
        val f = e.file ?: return
        if (e.up || dir == null) return
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
