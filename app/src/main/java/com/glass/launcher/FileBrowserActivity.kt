package com.glass.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * LEAD File Manager (v10).
 *
 *  Home: six cards – Internal Storage, USB Drive, Downloads, Network & Connect, Recycle Bin, Recent Files.
 *  Folders: one clean list; OK opens, long OK starts selecting (the row is selected at once), then OK toggles.
 *  Selection: Select all, Range, Move, Copy, Share, Delete, More – long OK on a selected row opens the same actions.
 *  Move / Copy: last used, recent and favourite places first; "Browse" keeps the files and shows "Paste here".
 *  Delete: Recycle Bin (with Undo) or permanently (asked twice). Name clashes: Skip / Keep both / Replace (also for all).
 *  One screen at a time, Back = exactly one step back, focus and scroll position come back.
 *  Can also be opened just to pick a picture, a backup or a folder (EXTRA_PICK).
 */
class FileBrowserActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(FontScale.wrap(newBase))

    companion object {
        const val EXTRA_PICK = "pick"   // "image", "backup" or "folder" = pick and return its path
        const val EXTRA_PATH = "path"
        const val EXTRA_START = "start"
        private const val REQ_PERM = 21

        /** files waiting for "Paste here" (survive leaving and opening folders) */
        var clip: List<File> = emptyList()
        var clipMove = true
    }

    private enum class Kind { HOME, FOLDER, BIN, RECENT }
    private data class Screen(val kind: Kind, val dir: File? = null, var focus: Int = 0, var scrollTop: Int = 0)

    private class Row(val file: File?, val name: String, val icon: Int, val meta: String, val date: String,
                      val trash: Trash.Item? = null, val useFolder: Boolean = false)

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val stack = ArrayList<Screen>()
    private val cur get() = stack.last()
    private var pickMode: String? = null
    private var askedPermission = false

    // list screens
    private val rows = ArrayList<Row>()
    private var list: RecyclerView? = null
    private var bar: LinearLayout? = null
    private var countView: TextView? = null
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var rangeAnchor = -1

    private val sw get() = resources.displayMetrics.widthPixels
    private val sh get() = resources.displayMetrics.heightPixels
    private fun dp(v: Float) = Taupe.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        T.init(prefs.lang)
        pickMode = intent.getStringExtra(EXTRA_PICK)
        root = FrameLayout(this)
        val wp = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Thread {
            val b = Bitmaps.decodeResScaled(this, Bitmaps.leadWallpaper(prefs.leadWp), sw, sh)
            handler.post { wp.setImageBitmap(b) }
        }.start()
        root.addView(wp, FrameLayout.LayoutParams(-1, -1))
        // list screens get a darker, calmer veil (like the approved design)
        root.addView(View(this).apply { id = View.generateViewId(); tag = "veil"; setBackgroundColor(Color.argb(0, 18, 16, 14)) }, FrameLayout.LayoutParams(-1, -1))
        content = FrameLayout(this).apply { clipChildren = false }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        stack.add(Screen(Kind.HOME))
        intent.getStringExtra(EXTRA_START)?.let { val f = File(it); if (f.isDirectory) stack.add(Screen(Kind.FOLDER, f)) }
        if (pickMode != null && stack.size == 1) stack.add(Screen(Kind.FOLDER, Environment.getExternalStorageDirectory()))
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) render()
        else if (!askedPermission) { askedPermission = true; requestAccess() }
        else {
            content.removeAllViews()
            content.addView(Taupe.text(this, T.s("Storage access was not allowed.", "Pristup memoriji nije dozvoljen.",
                "Speicherzugriff wurde nicht erlaubt.", "Доступ к памяти не разрешён."), 18f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
    }

    // ------------------------------------------------------------------ permission

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && hasAccess()) render()
    }

    // ------------------------------------------------------------------ places

    private val internal: File get() = Environment.getExternalStorageDirectory()
    private val downloads: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private fun usbs(): List<File> = Trash.roots().filter { it.absolutePath != internal.absolutePath }

    private fun rootLabel(r: File) = if (r.absolutePath == internal.absolutePath) T.s("Internal Storage", "Interna memorija", "Interner Speicher", "Внутренняя память")
        else "USB · " + r.name

    private fun rootOf(f: File) = Trash.roots().filter { f.absolutePath == it.absolutePath || f.absolutePath.startsWith(it.absolutePath + "/") }
        .maxByOrNull { it.absolutePath.length }

    private fun crumbs(dir: File): String {
        val r = rootOf(dir) ?: return dir.absolutePath
        val rel = dir.absolutePath.removePrefix(r.absolutePath).trim('/')
        return (listOf(rootLabel(r)) + rel.split('/').filter { it.isNotBlank() }).joinToString("  /  ")
    }

    // ------------------------------------------------------------------ screens

    private fun render() {
        val s = cur
        content.removeAllViews()
        list = null; bar = null; countView = null
        (root.findViewWithTag<View>("veil"))?.setBackgroundColor(if (s.kind == Kind.HOME) Color.argb(0, 0, 0, 0) else Color.argb(110, 22, 20, 18))
        when (s.kind) {
            Kind.HOME -> renderHome()
            else -> renderList()
        }
    }

    private fun push(s: Screen) {
        saveFocus()
        stack.add(s)
        selecting = false; selected.clear(); rangeAnchor = -1
        render()
    }

    private fun saveFocus() {
        val s = stack.lastOrNull() ?: return
        val rv = list
        if (rv != null) {
            val f = rv.focusedChild
            s.focus = if (f != null) rv.getChildAdapterPosition(f).coerceAtLeast(0) else s.focus
        } else {
            val all = ArrayList<View>(); content.addFocusables(all, View.FOCUS_DOWN)
            s.focus = all.indexOf(currentFocus).coerceAtLeast(0)
        }
    }

    private fun renderHome() {
        val bin = Trash.list().size
        val usb = usbs()
        val tiles = listOf(
            V10Tile(T.s("Internal Storage", "Interna memorija", "Interner Speicher", "Внутренняя память"), R.drawable.lc_hard_drive,
                T.s("Browse files", "Pregledaj fajlove", "Dateien durchsuchen", "Обзор файлов")) { push(Screen(Kind.FOLDER, internal)) },
            V10Tile(T.s("USB Drive", "USB disk", "USB-Laufwerk", "USB-накопитель"), R.drawable.lc_usb,
                if (usb.isEmpty()) T.s("Not connected", "Nije priključen", "Nicht verbunden", "Не подключён") else usb.joinToString(", ") { it.name }) { openUsb() },
            V10Tile(T.s("Downloads", "Preuzimanja", "Downloads", "Загрузки"), R.drawable.lc_folder_down,
                T.s("Downloaded files", "Preuzeti fajlovi", "Heruntergeladene Dateien", "Загруженные файлы")) { push(Screen(Kind.FOLDER, downloads)) },
            V10Tile(T.s("Network & Connect", "Mreža i povezivanje", "Netzwerk & Verbinden", "Сеть и подключение"), R.drawable.lc_network,
                T.s("Phone · SMB · FTP · SFTP", "Telefon · SMB · FTP · SFTP", "Handy · SMB · FTP · SFTP", "Телефон · SMB · FTP · SFTP")) { saveFocus(); NetUi.open(this, root) },
            V10Tile(T.s("Recycle Bin", "Kanta za smeće", "Papierkorb", "Корзина"), R.drawable.lc_trash_2,
                T.s("$bin items", "$bin stavki", "$bin Elemente", "элементов: $bin")) { push(Screen(Kind.BIN)) },
            V10Tile(T.s("Recent Files", "Nedavni fajlovi", "Zuletzt geöffnet", "Недавние файлы"), R.drawable.lc_clock_history,
                Destinations.recentFiles(this).size.let { T.s("$it files", "$it fajlova", "$it Dateien", "файлов: $it") }) { push(Screen(Kind.RECENT)) },
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding((sw * 0.099f).toInt(), (sh * 0.13f).toInt(), (sw * 0.09f).toInt(), (dp(16f)).toInt())
        }
        col.addView(Taupe.text(this, if (pickMode != null) pickTitle() else T.s("Files", "Fajlovi", "Dateien", "Файлы"), 43f))
        col.addView(Taupe.text(this, T.s("LEAD File Manager", "LEAD upravitelj fajlova", "LEAD Dateimanager", "Файловый менеджер LEAD"), 17.5f, Taupe.SUB),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f).toInt() })
        val cw = Taupe.cardW(this); val ch = Taupe.cardH(this); val gap = Taupe.gap(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        tiles.chunked(3).forEachIndexed { i, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
            chunk.forEach { t -> row.addView(V10Page.tile(this, t, cw, ch), LinearLayout.LayoutParams(cw, (ch * 0.86f).toInt()).apply { marginStart = gap / 2; marginEnd = gap / 2 }) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) 0 else gap * 2 })
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (sh * 0.05f).toInt() })
        col.addView(hint(T.s("OK Select     ←  Back", "OK Odaberi     ←  Nazad", "OK Auswählen     ←  Zurück", "OK Выбрать     ←  Назад")),
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        content.post {
            val all = ArrayList<View>(); content.addFocusables(all, View.FOCUS_DOWN)
            (all.getOrNull(cur.focus) ?: all.firstOrNull())?.requestFocus()
        }
    }

    private fun pickTitle() = when (pickMode) {
        "image" -> T.s("Choose a picture", "Izaberi sliku", "Bild auswählen", "Выберите картинку")
        "backup" -> T.s("Choose a backup", "Izaberi backup", "Sicherung auswählen", "Выберите копию")
        else -> T.s("Choose a folder", "Izaberi folder", "Ordner auswählen", "Выберите папку")
    }

    private fun hint(s: String) = Taupe.text(this, s, 13f, Taupe.MUTED)

    private fun openUsb() {
        val u = usbs()
        when (u.size) {
            0 -> V10Dialog.show(root, T.s("No USB drive", "Nema USB diska", "Kein USB-Laufwerk", "Нет USB"),
                T.s("Plug in a USB drive – it appears here by itself.", "Priključi USB disk – pojavit će se ovdje sam.",
                    "Steck ein USB-Laufwerk ein – es erscheint hier von selbst.", "Подключите USB-накопитель."), listOf("OK" to {}))
            1 -> push(Screen(Kind.FOLDER, u[0]))
            else -> V10List.show(root, T.s("USB Drive", "USB disk", "USB-Laufwerk", "USB"), u.map { r -> V10List.Entry(r.name, R.drawable.lc_usb) { push(Screen(Kind.FOLDER, r)) } })
        }
    }

    // ------------------------------------------------------------------ lists (folder, recycle bin, recent)

    private fun loadRows() {
        rows.clear()
        val s = cur
        when (s.kind) {
            Kind.FOLDER -> {
                val dir = s.dir!!
                if (pickMode == "folder") rows.add(Row(dir, "✓  " + T.s("Use this folder", "Koristi ovaj folder", "Diesen Ordner verwenden", "Выбрать эту папку"),
                    R.drawable.lc_check, "", "", useFolder = true))
                val files = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
                sorted(files).forEach { f ->
                    if (pickMode == "image" && f.isFile && StorageScan.keyOf(f) != "image") return@forEach
                    if (pickMode == "folder" && f.isFile) return@forEach
                    rows.add(Row(f, f.name, iconFor(f), meta(f), date(f.lastModified())))
                }
            }
            Kind.BIN -> Trash.list().forEach { t ->
                val sz = if (t.file.isDirectory) T.s("${t.file.listFiles()?.size ?: 0} items", "${t.file.listFiles()?.size ?: 0} stavki", "${t.file.listFiles()?.size ?: 0} Elemente", "${t.file.listFiles()?.size ?: 0} эл.")
                    else Formatter.formatShortFileSize(this, t.file.length())
                rows.add(Row(t.file, t.name, iconFor(t.file), sz, deletedAgo(t.deletedAt) + "  ·  " + (File(t.original).parent ?: ""), trash = t))
            }
            Kind.RECENT -> Destinations.recentFiles(this).forEach { f -> rows.add(Row(f, f.name, iconFor(f), meta(f), crumbs(f.parentFile ?: f))) }
            else -> {}
        }
    }

    private fun sorted(files: List<File>): List<File> {
        val byName = compareBy<File> { it.name.lowercase() }
        val cmp: Comparator<File> = when (prefs.fileSort) {
            1 -> compareByDescending<File> { it.length() }.then(byName)
            2 -> compareBy<File> { it.extension.lowercase() }.then(byName)
            3 -> compareByDescending<File> { it.lastModified() }
            else -> byName
        }
        return files.filter { it.isDirectory }.sortedWith(byName) + files.filter { !it.isDirectory }.sortedWith(cmp)
    }

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

    private fun meta(f: File) = if (f.isDirectory) T.s("Folder", "Folder", "Ordner", "Папка") else Formatter.formatShortFileSize(this, f.length())

    private fun date(t: Long) = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))

    private fun deletedAgo(t: Long): String {
        val days = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
        return when (days) {
            0 -> T.s("Deleted today", "Obrisano danas", "Heute gelöscht", "Удалено сегодня")
            1 -> T.s("Deleted yesterday", "Obrisano jučer", "Gestern gelöscht", "Удалено вчера")
            else -> T.s("Deleted $days days ago", "Obrisano prije $days dana", "Vor $days Tagen gelöscht", "Удалено $days дн. назад")
        }
    }

    private fun renderList() {
        val s = cur
        loadRows()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding((sw * 0.099f).toInt(), (sh * 0.06f).toInt(), (sw * 0.09f).toInt(), dp(12f).toInt())
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (title, crumb) = when (s.kind) {
            Kind.FOLDER -> (if (rootOf(s.dir!!)?.absolutePath == s.dir.absolutePath) rootLabel(s.dir) else s.dir.name) to crumbs(s.dir)
            Kind.BIN -> T.s("Recycle Bin", "Kanta za smeće", "Papierkorb", "Корзина") to
                (T.s("Files", "Fajlovi", "Dateien", "Файлы") + "  /  " + T.s("Recycle Bin", "Kanta za smeće", "Papierkorb", "Корзина"))
            else -> T.s("Recent Files", "Nedavni fajlovi", "Zuletzt geöffnet", "Недавние") to T.s("Files", "Fajlovi", "Dateien", "Файлы")
        }
        if (s.kind == Kind.BIN) {
            titles.addView(Taupe.text(this, crumb, 14f, Taupe.MUTED))
            titles.addView(Taupe.text(this, title, 36f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f).toInt() })
            titles.addView(Taupe.text(this, T.s("${rows.size} deleted items", "${rows.size} obrisanih stavki", "${rows.size} gelöschte Elemente", "удалено: ${rows.size}"), 15f, Taupe.SUB),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f).toInt() })
        } else {
            titles.addView(Taupe.text(this, title, 36f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            titles.addView(Taupe.text(this, crumb, 15f, Taupe.SUB).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f).toInt() })
        }
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (s.kind == Kind.FOLDER && pickMode == null) head.addView(pill(T.s("⋯  Options", "⋯  Opcije", "⋯  Optionen", "⋯  Опции"), R.drawable.lc_ellipsis) { folderOptions() })
        col.addView(head)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FileBrowserActivity)
            adapter = rowAdapter
            clipToPadding = false
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
            itemAnimator = null
        }
        list = rv
        col.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14f).toInt() })
        if (rows.isEmpty()) col.addView(Taupe.text(this, when (s.kind) {
            Kind.BIN -> T.s("The Recycle Bin is empty", "Kanta je prazna", "Der Papierkorb ist leer", "Корзина пуста")
            Kind.RECENT -> T.s("Nothing opened yet", "Još ništa nije otvoreno", "Noch nichts geöffnet", "Пока ничего")
            else -> T.s("This folder is empty", "Ovaj folder je prazan", "Dieser Ordner ist leer", "Папка пуста")
        }, 16f, Taupe.MUTED), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (sh * 0.3f).toInt() })

        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Taupe.panel(this@FileBrowserActivity, 18f)
            setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
        }
        bar = b
        col.addView(b, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8f).toInt() })
        col.addView(hint(if (selecting) T.s("OK Select     Long OK Actions     ←  Done", "OK Odaberi     Dugi OK Radnje     ←  Gotovo",
            "OK Auswählen     Langes OK Aktionen     ←  Fertig", "OK Выбор     Долгий OK Действия     ←  Готово")
            else T.s("OK Open     Long OK Select     ←  Back", "OK Otvori     Dugi OK Odaberi     ←  Nazad",
            "OK Öffnen     Langes OK Auswählen     ←  Zurück", "OK Открыть     Долгий OK Выбор     ←  Назад")),
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8f).toInt() })
        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        buildBar()
        rv.post {
            val p = s.focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, s.scrollTop)
            rv.post { rv.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() ?: b.getChildAt(0)?.requestFocus() }
        }
    }

    private fun pill(text: String, icon: Int?, act: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Taupe.pill(this@FileBrowserActivity); isFocusable = true; isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        if (icon != null) addView(Taupe.icon(this@FileBrowserActivity, icon, (sw * 0.014f).toInt()), LinearLayout.LayoutParams((sw * 0.014f).toInt(), (sw * 0.014f).toInt()).apply { marginEnd = dp(8f).toInt() })
        addView(Taupe.text(this@FileBrowserActivity, text, 13.5f))
        setOnClickListener { act() }
    }

    private fun sep() = View(this).apply { setBackgroundColor(Color.argb(50, 255, 244, 232)) }

    /** bottom bar: selection actions, Paste here, or the recycle bin actions – never several bars at once */
    private fun buildBar() {
        val b = bar ?: return
        b.removeAllViews()
        countView = null
        fun add(v: View) {
            if (b.childCount > 0) b.addView(sep(), LinearLayout.LayoutParams(dp(1f).toInt(), dp(22f).toInt()).apply { marginStart = dp(4f).toInt(); marginEnd = dp(4f).toInt() })
            b.addView(v)
        }
        val s = cur
        if (selecting) {
            countView = Taupe.text(this, countText(), 13.5f, Taupe.SUB).apply { setPadding(dp(10f).toInt(), 0, dp(10f).toInt(), 0) }
            add(countView!!)
            add(pill(T.s("Select All", "Odaberi sve", "Alle", "Выбрать все"), null) { selectAll() })
            add(pill(T.s("Range", "Raspon", "Bereich", "Диапазон"), R.drawable.lc_list_checks) { startRange() })
            if (s.kind == Kind.BIN) {
                add(pill(T.s("Restore", "Vrati", "Wiederherstellen", "Восстановить"), R.drawable.lc_undo) { restore(selRows()) })
                add(pill(T.s("Delete Permanently", "Obriši trajno", "Endgültig löschen", "Удалить навсегда"), R.drawable.lc_trash_2) { purge(selRows()) })
            } else {
                add(pill(T.s("Move", "Premjesti", "Verschieben", "Переместить"), R.drawable.lc_arrow_right_to) { chooseDest(selFiles(), true) })
                add(pill(T.s("Copy", "Kopiraj", "Kopieren", "Копировать"), R.drawable.lc_copy) { chooseDest(selFiles(), false) })
                add(pill(T.s("Send", "Pošalji", "Senden", "Отправить"), R.drawable.lc_share) { sendMenu(selFiles()) })
                add(pill(T.s("Delete", "Obriši", "Löschen", "Удалить"), R.drawable.lc_trash_2) { askDelete(selFiles()) })
                add(pill(T.s("More", "Više", "Mehr", "Ещё"), R.drawable.lc_ellipsis) { moreMenu() })
            }
            add(pill(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена"), R.drawable.lc_x) { endSelection() })
        } else if (clip.isNotEmpty() && s.kind == Kind.FOLDER) {
            add(Taupe.text(this, (if (clipMove) T.s("Moving", "Premještam", "Verschieben", "Перемещение") else T.s("Copying", "Kopiram", "Kopieren", "Копирование")) +
                " · ${clip.size}", 13.5f, Taupe.SUB).apply { setPadding(dp(10f).toInt(), 0, dp(10f).toInt(), 0) })
            add(pill(T.s("Paste Here", "Zalijepi ovdje", "Hier einfügen", "Вставить сюда"), R.drawable.lc_clipboard) { pasteHere() })
            add(pill(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена"), R.drawable.lc_x) { clip = emptyList(); buildBar() })
        } else if (s.kind == Kind.BIN && rows.isNotEmpty()) {
            add(pill(T.s("Select", "Odaberi", "Auswählen", "Выбрать"), R.drawable.lc_square_check) { startSelection(focusedIndex()) })
            add(pill(T.s("Empty Recycle Bin", "Isprazni kantu", "Papierkorb leeren", "Очистить корзину"), R.drawable.lc_trash_2) { emptyBin() })
        }
        b.visibility = if (b.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun countText() = if (rangeAnchor >= 0) T.s("Range: move, then OK", "Raspon: pomjeri pa OK", "Bereich: bewegen, dann OK", "Диапазон: OK")
        else T.s("${selected.size} selected", "${selected.size} odabrano", "${selected.size} ausgewählt", "выбрано: ${selected.size}")

    private fun focusedIndex(): Int = list?.let { rv -> rv.focusedChild?.let { rv.getChildAdapterPosition(it) } } ?: 0

    private fun selRows() = rows.filter { it.file != null && it.file.absolutePath in selected }
    private fun selFiles() = selRows().mapNotNull { it.file }

    // ------------------------------------------------------------------ rows

    private val rowAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; isLongClickable = true
                if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                background = Taupe.row(ctx)
                setPadding((sw * 0.018f).toInt(), 0, (sw * 0.018f).toInt(), 0)
                layoutParams = RecyclerView.LayoutParams(-1, (sh * 0.092f).toInt()).apply { bottomMargin = (sh * 0.012f).toInt() }
                val ic = (sw * 0.021f).toInt()
                addView(ImageView(ctx).apply { tag = "check" }, LinearLayout.LayoutParams(ic, ic).apply { marginEnd = (sw * 0.012f).toInt() })
                addView(ImageView(ctx).apply { tag = "icon" }, LinearLayout.LayoutParams(ic, ic).apply { marginEnd = (sw * 0.016f).toInt() })
                addView(Taupe.text(ctx, "", 17.5f).apply { tag = "name"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Taupe.text(ctx, "", 14.5f, Taupe.MUTED).apply { tag = "meta"; gravity = Gravity.END }, LinearLayout.LayoutParams((sw * 0.11f).toInt(), -2))
                addView(Taupe.text(ctx, "", 14.5f, Taupe.SUB).apply { tag = "date"; gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START },
                    LinearLayout.LayoutParams((sw * 0.2f).toInt(), -2))
            }
            val h = object : RecyclerView.ViewHolder(v) {}
            v.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) onRow(p) }
            v.setOnLongClickListener { val p = h.bindingAdapterPosition; if (p >= 0) onRowLong(p); true }
            return h
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val v = h.itemView
            val check = v.findViewWithTag<ImageView>("check")
            val sel = r.file != null && r.file.absolutePath in selected
            check.visibility = if (selecting && !r.useFolder) View.VISIBLE else View.GONE
            check.setImageResource(if (sel) R.drawable.lc_square_check else R.drawable.lc_square)
            check.setColorFilter(if (sel) Taupe.CHAMPAGNE else Taupe.MUTED)
            v.findViewWithTag<ImageView>("icon").apply { setImageResource(r.icon); setColorFilter(if (r.useFolder) Taupe.CHAMPAGNE else Taupe.ICON) }
            v.findViewWithTag<TextView>("name").apply { text = r.name; setTextColor(if (sel) Taupe.CHAMPAGNE else Taupe.TEXT) }
            v.findViewWithTag<TextView>("meta").text = r.meta
            v.findViewWithTag<TextView>("date").text = r.date
        }
    }

    private fun onRow(p: Int) {
        val r = rows.getOrNull(p) ?: return
        if (r.useFolder) { setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, r.file!!.absolutePath)); finish(); return }
        if (selecting) {
            if (rangeAnchor >= 0) { finishRange(p); return }
            toggle(p); return
        }
        val f = r.file ?: return
        if (r.trash != null) { binItem(r); return }
        if (f.isDirectory) { cur.focus = p; push(Screen(Kind.FOLDER, f)); return }
        if (pickMode != null) { setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath)); finish(); return }
        open(f)
    }

    private fun onRowLong(p: Int) {
        val r = rows.getOrNull(p) ?: return
        if (pickMode != null || r.useFolder || r.file == null) return
        if (!selecting) { startSelection(p); return }
        // long OK in selection mode: the actions for what is selected
        if (r.file.absolutePath !in selected) toggle(p)
        actionsMenu()
    }

    private fun open(f: File) {
        Destinations.opened(this, f)
        if (f.extension.equals("apk", true)) { Updater.install(this, f); return }
        MediaViewerActivity.openDefault(this, f)
    }

    // ------------------------------------------------------------------ selection

    private fun startSelection(p: Int) {
        selecting = true; selected.clear(); rangeAnchor = -1
        rows.getOrNull(p)?.file?.takeIf { !rows[p].useFolder }?.let { selected.add(it.absolutePath) }
        refreshKeepingFocus()
    }

    private fun endSelection() {
        selecting = false; selected.clear(); rangeAnchor = -1
        refreshKeepingFocus()
    }

    private fun toggle(p: Int) {
        val f = rows.getOrNull(p)?.file ?: return
        if (!selected.remove(f.absolutePath)) selected.add(f.absolutePath)
        list?.adapter?.notifyItemChanged(p)
        countView?.text = countText()
    }

    private fun selectAll() { rows.filter { !it.useFolder }.mapNotNull { it.file }.forEach { selected.add(it.absolutePath) }; refreshKeepingFocus() }

    private fun invert() {
        val all = rows.filter { !it.useFolder }.mapNotNull { it.file?.absolutePath }
        val now = all.filter { it !in selected }
        selected.clear(); selected.addAll(now); refreshKeepingFocus()
    }

    /** Range: the focused row is the start; move to the end row and press OK – everything between is selected */
    private fun startRange() {
        rangeAnchor = focusedIndexFromList()
        countView?.text = countText()
        list?.let { rv -> rv.post { rv.findViewHolderForAdapterPosition(rangeAnchor)?.itemView?.requestFocus() } }
    }

    private var lastListFocus = 0
    private fun focusedIndexFromList(): Int = list?.focusedChild?.let { list!!.getChildAdapterPosition(it) } ?: lastListFocus

    private fun finishRange(p: Int) {
        val a = minOf(rangeAnchor, p); val b = maxOf(rangeAnchor, p)
        for (i in a..b) rows[i].takeIf { !it.useFolder }?.file?.let { selected.add(it.absolutePath) }
        rangeAnchor = -1
        refreshKeepingFocus()
    }

    /** re-read the list and keep the cursor and scroll exactly where they were */
    private fun refreshKeepingFocus() {
        val rv = list ?: return render()
        val lm = rv.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val top = rv.getChildAt(0)?.top ?: 0
        val focus = rv.focusedChild?.let { rv.getChildAdapterPosition(it) } ?: lastListFocus
        loadRows()
        rv.adapter?.notifyDataSetChanged()
        buildBar()
        if (first >= 0) lm.scrollToPositionWithOffset(first, top - rv.paddingTop)
        rv.post { rv.findViewHolderForAdapterPosition(focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.itemView?.requestFocus() }
    }

    private fun actionsMenu() {
        val files = selFiles()
        if (cur.kind == Kind.BIN) {
            V10List.show(root, countText(), listOf(
                V10List.Entry(T.s("Restore", "Vrati", "Wiederherstellen", "Восстановить"), R.drawable.lc_undo) { restore(selRows()) },
                V10List.Entry(T.s("Delete Permanently", "Obriši trajno", "Endgültig löschen", "Удалить навсегда"), R.drawable.lc_trash_2) { purge(selRows()) },
                V10List.Entry(T.s("Select All", "Odaberi sve", "Alle auswählen", "Выбрать все"), R.drawable.lc_square_check) { selectAll() },
                V10List.Entry(T.s("Clear Selection", "Poništi odabir", "Auswahl aufheben", "Снять выбор"), R.drawable.lc_x) { endSelection() },
            ))
            return
        }
        V10List.show(root, countText(), listOfNotNull(
            V10List.Entry(T.s("Move", "Premjesti", "Verschieben", "Переместить"), R.drawable.lc_arrow_right_to) { chooseDest(files, true) },
            V10List.Entry(T.s("Copy", "Kopiraj", "Kopieren", "Копировать"), R.drawable.lc_copy) { chooseDest(files, false) },
            V10List.Entry(T.s("Send / Share", "Pošalji / podijeli", "Senden / Teilen", "Отправить"), R.drawable.lc_share) { sendMenu(files) },
            V10List.Entry(T.s("Delete", "Obriši", "Löschen", "Удалить"), R.drawable.lc_trash_2) { askDelete(files) },
            if (files.size == 1) V10List.Entry(T.s("Rename", "Preimenuj", "Umbenennen", "Переименовать"), R.drawable.lc_pencil) { rename(files[0]) } else null,
            if (files.size == 1) V10List.Entry(T.s("Properties", "Svojstva", "Eigenschaften", "Свойства"), R.drawable.lc_info) { properties(files[0]) } else null,
            if (files.size == 1 && files[0].isFile) V10List.Entry(T.s("Open with…", "Otvori s…", "Öffnen mit…", "Открыть с помощью…"), R.drawable.lc_play) { MediaViewerActivity.openWith(this, files[0]) } else null,
            V10List.Entry(T.s("Select All", "Odaberi sve", "Alle auswählen", "Выбрать все"), R.drawable.lc_square_check) { selectAll() },
            V10List.Entry(T.s("Select Range", "Odaberi raspon", "Bereich auswählen", "Выбрать диапазон"), R.drawable.lc_list_checks) { startRange() },
            V10List.Entry(T.s("Invert Selection", "Obrni odabir", "Auswahl umkehren", "Инвертировать"), R.drawable.lc_rotate_ccw) { invert() },
            V10List.Entry(T.s("Clear Selection", "Poništi odabir", "Auswahl aufheben", "Снять выбор"), R.drawable.lc_x) { endSelection() },
        ))
    }

    /** Send: to a phone or computer (Phone Connect), to a network location, or to another app on the TV */
    private fun sendMenu(files: List<File>) {
        if (files.isEmpty()) return
        V10List.show(root, T.s("Send ${files.size}", "Pošalji ${files.size}", "${files.size} senden", "Отправить ${files.size}"), listOf(
            V10List.Entry(T.s("To a phone or computer (Phone Connect)", "Na telefon ili računar (Poveži telefon)", "An Handy oder Computer (Handy verbinden)", "На телефон или ПК"), R.drawable.lc_qr_code) {
                files.filter { it.isFile }.forEach { f -> if (PhoneConnect.offered.none { it.absolutePath == f.absolutePath }) PhoneConnect.offered.add(f) }
                endSelection()
                NetUi.open(this, root); NetUi.phone(this)
            },
            V10List.Entry(T.s("To a network location", "Na mrežnu lokaciju", "An einen Netzwerkort", "В сетевое место"), R.drawable.lc_network) {
                NetUi.uploadTo(this, root, files) { refreshKeepingFocus() }
            },
            V10List.Entry(T.s("To another app", "U drugu aplikaciju", "An eine andere App", "В другое приложение"), R.drawable.lc_share) { MediaViewerActivity.share(this, files) },
        ))
    }

    private fun moreMenu() {
        val files = selFiles()
        V10List.show(root, T.s("More", "Više", "Mehr", "Ещё"), listOfNotNull(
            if (files.size == 1) V10List.Entry(T.s("Rename", "Preimenuj", "Umbenennen", "Переименовать"), R.drawable.lc_pencil) { rename(files[0]) } else null,
            if (files.size == 1) V10List.Entry(T.s("Properties", "Svojstva", "Eigenschaften", "Свойства"), R.drawable.lc_info) { properties(files[0]) } else null,
            if (files.size == 1 && files[0].isFile) V10List.Entry(T.s("Open with…", "Otvori s…", "Öffnen mit…", "Открыть с помощью…"), R.drawable.lc_play) { MediaViewerActivity.openWith(this, files[0]) } else null,
            V10List.Entry(T.s("Invert Selection", "Obrni odabir", "Auswahl umkehren", "Инвертировать"), R.drawable.lc_rotate_ccw) { invert() },
            V10List.Entry(T.s("Clear Selection", "Poništi odabir", "Auswahl aufheben", "Снять выбор"), R.drawable.lc_x) { endSelection() },
        ))
    }

    private fun folderOptions() {
        val dir = cur.dir ?: return
        val fav = Destinations.isFavorite(this, dir)
        V10List.show(root, dir.name.ifBlank { "/" }, listOf(
            V10List.Entry(T.s("New folder", "Novi folder", "Neuer Ordner", "Новая папка"), R.drawable.lc_plus) {
                V10Input.show(root, T.s("New folder", "Novi folder", "Neuer Ordner", "Новая папка"), "") { n ->
                    val name = n.trim(); if (name.isBlank()) return@show
                    if (!File(dir, FileOps.uniqueName(dir, name)).mkdirs()) toast(T.s("Can't create it here", "Ne mogu ga napraviti ovdje", "Hier nicht möglich", "Нельзя создать"))
                    refreshKeepingFocus()
                }
            },
            V10List.Entry(if (fav) T.s("Remove from favorites", "Ukloni iz omiljenih", "Aus Favoriten entfernen", "Убрать из избранного")
                else T.s("Add to favorites", "Dodaj u omiljene", "Zu Favoriten", "В избранное"), R.drawable.lc_star) { Destinations.toggleFavorite(this, dir) },
            V10List.Entry(T.s("Select files", "Odaberi fajlove", "Dateien auswählen", "Выбрать файлы"), R.drawable.lc_square_check) { startSelection(focusedIndexFromList()) },
            V10List.Entry(T.s("Sort", "Sortiraj", "Sortieren", "Сортировка"), R.drawable.lc_list_checks, listOf(
                T.s("Name", "Naziv", "Name", "Имя"), T.s("Size", "Veličina", "Größe", "Размер"), T.s("Type", "Vrsta", "Typ", "Тип"),
                T.s("Date", "Datum", "Datum", "Дата"))[prefs.fileSort.coerceIn(0, 3)]) {
                V10List.show(root, T.s("Sort", "Sortiraj", "Sortieren", "Сортировка"), listOf(
                    T.s("Name", "Naziv", "Name", "Имя"), T.s("Size", "Veličina", "Größe", "Размер"), T.s("Type", "Vrsta", "Typ", "Тип"),
                    T.s("Date (newest)", "Datum (najnovije)", "Datum (neueste)", "Дата")).mapIndexed { i, n ->
                    V10List.Entry(n, if (prefs.fileSort == i) R.drawable.lc_check else null) { prefs.fileSort = i; refreshKeepingFocus() }
                })
            },
            V10List.Entry(T.s("Storage of this drive", "Prostor ovog diska", "Speicher dieses Laufwerks", "Память диска"), R.drawable.lc_hard_drive) {
                rootOf(dir)?.let { startActivity(Intent(this, StorageActivity::class.java).putExtra(StorageActivity.EXTRA_ROOT, it.absolutePath)) }
            },
        ))
    }

    private fun rename(f: File) {
        V10Input.show(root, T.s("New name", "Novo ime", "Neuer Name", "Новое имя"), f.name) { n ->
            val name = n.trim()
            if (name.isBlank() || name == f.name) return@show
            val t = File(f.parentFile, name)
            if (t.exists()) { toast(T.s("That name already exists", "To ime već postoji", "Der Name existiert schon", "Имя занято")); return@show }
            if (!f.renameTo(t)) toast(T.s("Can't rename", "Ne mogu preimenovati", "Umbenennen nicht möglich", "Не удалось"))
            selected.clear(); selected.add(t.absolutePath)
            refreshKeepingFocus()
        }
    }

    private fun properties(f: File) {
        Thread {
            val size = FileOps.sizeOf(f)
            val count = if (f.isDirectory) f.walkTopDown().count { it.isFile } else 0
            handler.post {
                V10Dialog.show(root, f.name, listOfNotNull(
                    f.parent,
                    Formatter.formatFileSize(this, size) + if (f.isDirectory) "  ·  " + T.s("$count files", "$count fajlova", "$count Dateien", "файлов: $count") else "",
                    T.s("Changed", "Izmijenjeno", "Geändert", "Изменён") + ": " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(f.lastModified())),
                ).joinToString("\n"), listOf("OK" to {}))
            }
        }.start()
    }

    // ------------------------------------------------------------------ move / copy

    private fun chooseDest(files: List<File>, move: Boolean) {
        if (files.isEmpty()) return
        val here = cur.dir
        val seen = HashSet<String>()
        fun ok(d: File) = d.isDirectory && d.absolutePath != here?.absolutePath && seen.add(d.absolutePath)
        val e = ArrayList<V10List.Entry>()
        Destinations.last(this)?.takeIf { ok(it) }?.let { d -> e.add(V10List.Entry(T.s("Last used", "Zadnje korišteno", "Zuletzt", "Последнее"), R.drawable.lc_rotate_ccw, crumbs(d)) { runOp(files, d, move) }) }
        Destinations.recent(this).filter { ok(it) }.forEach { d -> e.add(V10List.Entry(d.name.ifBlank { "/" }, R.drawable.lc_clock_history, crumbs(d)) { runOp(files, d, move) }) }
        Destinations.favorites(this).filter { ok(it) }.forEach { d -> e.add(V10List.Entry(d.name, R.drawable.lc_star, crumbs(d)) { runOp(files, d, move) }) }
        if (ok(internal)) e.add(V10List.Entry(T.s("Internal Storage", "Interna memorija", "Interner Speicher", "Внутренняя память"), R.drawable.lc_hard_drive) { runOp(files, internal, move) })
        if (ok(downloads)) e.add(V10List.Entry(T.s("Downloads", "Preuzimanja", "Downloads", "Загрузки"), R.drawable.lc_folder_down) { runOp(files, downloads, move) })
        usbs().filter { ok(it) }.forEach { u -> e.add(V10List.Entry("USB · " + u.name, R.drawable.lc_usb) { runOp(files, u, move) }) }
        e.add(V10List.Entry(T.s("Network location…", "Mrežna lokacija…", "Netzwerkort…", "Сетевое место…"), R.drawable.lc_network) {
            NetUi.uploadTo(this, root, files) { refreshKeepingFocus() }
        })
        e.add(V10List.Entry(T.s("Browse another folder…", "Drugi folder…", "Anderen Ordner wählen…", "Другая папка…"), R.drawable.lc_folder) {
            clip = files; clipMove = move
            selecting = false; selected.clear()
            toast(T.s("Open the folder, then \"Paste Here\"", "Otvori folder, pa \"Zalijepi ovdje\"", "Ordner öffnen, dann \"Hier einfügen\"", "Откройте папку и «Вставить сюда»"))
            refreshKeepingFocus()
        })
        V10List.show(root, (if (move) T.s("Move ${files.size} to…", "Premjesti ${files.size} u…", "${files.size} verschieben nach…", "Переместить ${files.size} в…")
            else T.s("Copy ${files.size} to…", "Kopiraj ${files.size} u…", "${files.size} kopieren nach…", "Копировать ${files.size} в…")), e)
    }

    private fun pasteHere() {
        val d = cur.dir ?: return
        val files = clip
        runOp(files, d, clipMove) { clip = emptyList() }
    }

    /** decides the name clashes first (Skip / Keep both / Replace, also for all), then copies or moves in the background */
    private fun runOp(files: List<File>, dest: File, move: Boolean, after: () -> Unit = {}) {
        val inside = files.firstOrNull { it.isDirectory && FileOps.isInside(dest, it) }
        if (inside != null) {
            V10Dialog.show(root, T.s("Not possible", "Nije moguće", "Nicht möglich", "Невозможно"),
                T.s("\"${inside.name}\" can't go into itself.", "\"${inside.name}\" ne može u samog sebe.", "\"${inside.name}\" kann nicht in sich selbst.", "Нельзя в саму себя."), listOf("OK" to {}))
            return
        }
        if (!dest.canWrite()) {
            V10Dialog.show(root, T.s("Can't write here", "Ne mogu pisati ovdje", "Hier kein Schreibzugriff", "Нет доступа"),
                crumbs(dest), listOf("OK" to {}))
            return
        }
        val clashes = files.filter { File(dest, it.name).exists() && it.parentFile?.absolutePath != dest.absolutePath }
        val decide = HashMap<String, FileOps.Clash>()
        fun go() {
            Destinations.remember(this, dest)
            progressRun(files, dest, move, decide) { after() }
        }
        when {
            clashes.isEmpty() -> go()
            clashes.size == 1 -> V10Dialog.show(root, T.s("\"${clashes[0].name}\" already exists", "\"${clashes[0].name}\" već postoji", "\"${clashes[0].name}\" existiert schon", "Уже существует"),
                crumbs(dest), listOf(
                    T.s("Skip", "Preskoči", "Überspringen", "Пропустить") to { decide[clashes[0].absolutePath] = FileOps.Clash.SKIP; go() },
                    T.s("Keep Both", "Zadrži oba", "Beide behalten", "Оставить оба") to { decide[clashes[0].absolutePath] = FileOps.Clash.KEEP_BOTH; go() },
                    T.s("Replace", "Zamijeni", "Ersetzen", "Заменить") to { decide[clashes[0].absolutePath] = FileOps.Clash.REPLACE; go() }), focusIndex = 1)
            else -> V10Dialog.show(root, T.s("${clashes.size} items already exist", "${clashes.size} stavki već postoji", "${clashes.size} Elemente existieren schon", "Уже существуют: ${clashes.size}"),
                T.s("Apply to all of them:", "Primijeni na sve:", "Für alle anwenden:", "Применить ко всем:"), listOf(
                    T.s("Skip All", "Preskoči sve", "Alle überspringen", "Пропустить все") to { clashes.forEach { decide[it.absolutePath] = FileOps.Clash.SKIP }; go() },
                    T.s("Keep Both", "Zadrži oba", "Beide behalten", "Оставить оба") to { clashes.forEach { decide[it.absolutePath] = FileOps.Clash.KEEP_BOTH }; go() },
                    T.s("Replace All", "Zamijeni sve", "Alle ersetzen", "Заменить все") to { clashes.forEach { decide[it.absolutePath] = FileOps.Clash.REPLACE }; go() }), focusIndex = 1)
        }
    }

    private fun progressRun(files: List<File>, dest: File, move: Boolean, decide: Map<String, FileOps.Clash>, after: () -> Unit) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Taupe.panel(this@FileBrowserActivity, 20f)
            setPadding(dp(30f).toInt(), dp(22f).toInt(), dp(30f).toInt(), dp(18f).toInt())
            tag = "v10dialog"; isClickable = true
        }
        val label = Taupe.text(this, "…", 15f)
        panel.addView(label)
        var cancelled = false
        val stop = pill(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена"), R.drawable.lc_x) { cancelled = true }
        panel.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f).toInt() })
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        stop.requestFocus()
        val verb = if (move) T.s("Moving", "Premještam", "Verschiebe", "Перемещаю") else T.s("Copying", "Kopiram", "Kopiere", "Копирую")
        var lastUi = 0L
        Thread {
            val rep = FileOps.run(files, dest, move, { decide[it.absolutePath] ?: FileOps.Clash.KEEP_BOTH }, { i, bytes ->
                val now = System.currentTimeMillis()
                if (now - lastUi > 200) { lastUi = now; handler.post { label.text = "$verb ${i + 1} / ${files.size}  ·  ${Formatter.formatShortFileSize(this, bytes)}" } }
            }, { cancelled })
            handler.post {
                root.removeView(panel)
                after()
                selecting = false; selected.clear()
                refreshKeepingFocus()
                report(rep, if (move) T.s("moved", "premješteno", "verschoben", "перемещено") else T.s("copied", "kopirano", "kopiert", "скопировано"))
            }
        }.start()
    }

    /** the exact result – partial failures are named, never hidden */
    private fun report(r: FileOps.Report, verb: String) {
        if (r.failed.isEmpty()) {
            toast("${r.done} $verb" + if (r.skipped > 0) " · ${r.skipped} " + T.s("skipped", "preskočeno", "übersprungen", "пропущено") else "")
            return
        }
        V10Dialog.show(root, T.s("${r.done} $verb, ${r.failed.size} failed", "${r.done} $verb, ${r.failed.size} nije uspjelo", "${r.done} $verb, ${r.failed.size} fehlgeschlagen", "Ошибок: ${r.failed.size}"),
            r.failed.take(6).joinToString("\n") + if (r.failed.size > 6) "\n…" else "", listOf("OK" to {}))
    }

    // ------------------------------------------------------------------ delete / recycle bin

    private fun askDelete(files: List<File>) {
        if (files.isEmpty()) return
        V10Dialog.show(root, T.s("Delete ${files.size} items?", "Obrisati ${files.size} stavki?", "${files.size} Elemente löschen?", "Удалить ${files.size}?"),
            T.s("The Recycle Bin keeps them – you can restore them later.", "Kanta ih čuva – kasnije ih možeš vratiti.",
                "Der Papierkorb behält sie – du kannst sie später wiederherstellen.", "Корзина сохранит их."), listOf(
                T.s("Cancel", "Otkaži", "Abbrechen", "Отмена") to {},
                T.s("Delete Permanently", "Obriši trajno", "Endgültig löschen", "Удалить навсегда") to { confirmPermanent(files.size) { deleteForGood(files) } },
                T.s("Move to Recycle Bin", "U kantu za smeće", "In den Papierkorb", "В корзину") to { toBin(files) }))
    }

    private fun confirmPermanent(n: Int, go: () -> Unit) {
        V10Dialog.show(root, T.s("Delete $n items permanently?", "Trajno obrisati $n stavki?", "$n Elemente endgültig löschen?", "Удалить навсегда?"),
            T.s("They can't be restored through LEAD afterwards.", "Poslije ih LEAD ne može vratiti.", "LEAD kann sie danach nicht wiederherstellen.", "LEAD не сможет их восстановить."),
            listOf(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена") to {}, T.s("Delete Permanently", "Obriši trajno", "Endgültig löschen", "Удалить навсегда") to go), focusIndex = 0)
    }

    private fun toBin(files: List<File>) {
        val start = System.currentTimeMillis()
        Thread {
            val failed = files.filter { !Trash.move(it) }.map { it.name }
            val moved = files.map { it.absolutePath }.toSet() - files.filter { it.name in failed }.map { it.absolutePath }.toSet()
            handler.post {
                selecting = false; selected.clear()
                refreshKeepingFocus()
                // a failed move to the bin is reported – never turned into a permanent delete
                if (failed.isNotEmpty()) V10Dialog.show(root, T.s("${failed.size} could not go to the Recycle Bin", "${failed.size} nije moglo u kantu", "${failed.size} konnten nicht in den Papierkorb", "Не перемещено: ${failed.size}"),
                    failed.take(6).joinToString("\n"), listOf("OK" to {}))
                if (moved.isNotEmpty()) V10Undo.show(root, T.s("${moved.size} moved to the Recycle Bin", "${moved.size} premješteno u kantu", "${moved.size} im Papierkorb", "В корзине: ${moved.size}")) {
                    Thread {
                        Trash.list().filter { it.original in moved && it.deletedAt >= start - 1000 }.forEach { Trash.restore(it) }
                        handler.post { refreshKeepingFocus() }
                    }.start()
                }
            }
        }.start()
    }

    private fun deleteForGood(files: List<File>) {
        Thread {
            val failed = files.filter { !it.deleteRecursively() }.map { it.name }
            handler.post {
                selecting = false; selected.clear(); refreshKeepingFocus()
                if (failed.isEmpty()) toast(T.s("${files.size} deleted", "${files.size} obrisano", "${files.size} gelöscht", "Удалено: ${files.size}"))
                else report(FileOps.Report(files.size - failed.size, 0, failed), T.s("deleted", "obrisano", "gelöscht", "удалено"))
            }
        }.start()
    }

    private fun binItem(r: Row) {
        V10Dialog.show(root, r.name, r.trash?.original, listOf(
            T.s("Cancel", "Otkaži", "Abbrechen", "Отмена") to {},
            T.s("Delete Permanently", "Obriši trajno", "Endgültig löschen", "Удалить навсегда") to { purge(listOf(r)) },
            T.s("Restore", "Vrati", "Wiederherstellen", "Восстановить") to { restore(listOf(r)) }))
    }

    private fun restore(list: List<Row>) {
        Thread {
            val failed = list.filter { it.trash != null && !Trash.restore(it.trash) }.map { it.name }
            handler.post {
                selecting = false; selected.clear(); refreshKeepingFocus()
                if (failed.isEmpty()) toast(T.s("${list.size} restored", "${list.size} vraćeno", "${list.size} wiederhergestellt", "Восстановлено: ${list.size}"))
                else report(FileOps.Report(list.size - failed.size, 0, failed), T.s("restored", "vraćeno", "wiederhergestellt", "восстановлено"))
            }
        }.start()
    }

    private fun purge(list: List<Row>) {
        if (list.isEmpty()) return
        confirmPermanent(list.size) {
            Thread {
                val failed = list.filter { it.trash != null && !Trash.purge(it.trash) }.map { it.name }
                handler.post {
                    selecting = false; selected.clear(); refreshKeepingFocus()
                    if (failed.isNotEmpty()) report(FileOps.Report(list.size - failed.size, 0, failed), T.s("deleted", "obrisano", "gelöscht", "удалено"))
                }
            }.start()
        }
    }

    private fun emptyBin() {
        val n = rows.size
        V10Dialog.show(root, T.s("Empty the Recycle Bin?", "Isprazniti kantu?", "Papierkorb leeren?", "Очистить корзину?"),
            T.s("All $n items will be removed permanently.", "Svih $n stavki bit će trajno uklonjeno.", "Alle $n Elemente werden endgültig entfernt.", "Все $n элементов будут удалены."),
            listOf(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена") to {}, T.s("Empty Recycle Bin", "Isprazni kantu", "Papierkorb leeren", "Очистить") to {
                Thread { Trash.empty(); handler.post { refreshKeepingFocus() } }.start()
            }), focusIndex = 0)
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        list?.focusedChild?.let { lastListFocus = list!!.getChildAdapterPosition(it) }
        // dialogs and small lists handle their own keys (Back closes them)
        if (V10Dialog.isOpen(root) || V10List.isOpen(root)) return super.dispatchKeyEvent(event)
        if (NetUi.isOpen(root)) return NetUi.key(event) || super.dispatchKeyEvent(event)
        if (k == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && list != null) {
            if (selecting) actionsMenu() else if (cur.kind == Kind.FOLDER) folderOptions()
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Back is handled in dispatchKeyEvent (one step at a time); never close the whole file manager by accident
    @Deprecated("Back is handled in dispatchKeyEvent")
    override fun onBackPressed() {}

    /** exactly one step back */
    private fun back() {
        if (rangeAnchor >= 0) { rangeAnchor = -1; countView?.text = countText(); return }
        if (selecting) { endSelection(); return }
        val s = cur
        if (s.kind == Kind.FOLDER) {
            val parent = s.dir?.parentFile
            val r = rootOf(s.dir!!)
            // inside a drive: up to the parent folder, with the cursor on the folder we came from
            if (parent != null && r != null && s.dir.absolutePath != r.absolutePath && (stack.size < 2 || stack[stack.size - 2].dir?.absolutePath != parent.absolutePath)) {
                val from = s.dir.name
                stack[stack.size - 1] = Screen(Kind.FOLDER, parent)
                loadRows()
                cur.focus = rows.indexOfFirst { it.file?.name == from }.coerceAtLeast(0)
                render(); return
            }
        }
        if (stack.size <= 1) { finish(); return }
        stack.removeAt(stack.size - 1)
        render()
    }

    fun refreshFromNet() = render()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
