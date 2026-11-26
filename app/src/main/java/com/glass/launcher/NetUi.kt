package com.glass.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

/**
 * Network & Connect inside the file manager: Phone Connect (QR), SMB / Windows shares, FTP / FTPS / SFTP,
 * saved locations and discovery. One screen at a time on its own layer; Back goes one step back.
 */
object NetUi {
    private const val TAG = "netui"
    private val h = Handler(Looper.getMainLooper())

    private sealed class Scr {
        object Home : Scr()
        object Phone : Scr()
        class Remote(val conn: Conn, val fs: RemoteFs, val path: String) : Scr()
    }

    private var act: Activity? = null
    private var root: FrameLayout? = null
    private var layer: FrameLayout? = null
    private val stack = ArrayList<Scr>()
    private val focusAt = HashMap<Int, Int>()
    /** local files waiting to be uploaded ("Upload here" on a remote folder) */
    private var pending: List<File> = emptyList()
    private var afterUpload: () -> Unit = {}
    private var phone: PhoneConnect? = null

    fun isOpen(r: ViewGroup) = (0 until r.childCount).any { r.getChildAt(it).tag == TAG }

    fun open(a: Activity, r: FrameLayout) {
        act = a; root = r
        stack.clear(); stack.add(Scr.Home)
        // one screen at a time: the file manager underneath is hidden completely while Network & Connect is open
        r.findViewWithTag<View>("fmcontent")?.visibility = View.INVISIBLE
        val l = FrameLayout(a).apply { tag = TAG; isClickable = true; setBackgroundColor(Color.argb(60, 24, 21, 19)) }
        // keys of this layer: Back = one step back
        l.isFocusable = false
        r.addView(l, FrameLayout.LayoutParams(-1, -1))
        layer = l
        render()
    }

    /** straight to Phone Connect (after "Send › To a phone") */
    fun phone(a: Activity) { if (layer != null) push(Scr.Phone) }

    /** from the file manager: Move/Copy › Network location – pick a place, then "Upload here" */
    fun uploadTo(a: Activity, r: FrameLayout, files: List<File>, done: () -> Unit) {
        pending = files; afterUpload = done
        open(a, r)
        savedMenu()
    }

    /** the file manager is closing (Home, Back): stop Phone Connect and close connections */
    fun shutdown() { if (layer != null || phone != null) runCatching { close() } }

    private fun close() {
        stack.filterIsInstance<Scr.Remote>().map { it.fs }.distinct().forEach { fs -> Thread { runCatching { fs.close() } }.start() }
        stack.clear()
        phone?.stop(); phone = null
        layer?.let { root?.removeView(it) }
        layer = null
        root?.findViewWithTag<View>("fmcontent")?.visibility = View.VISIBLE
        pending = emptyList()
        (act as? FileBrowserActivity)?.refreshFromNet()
    }

    /** called by the activity for every key while the layer is open; true = handled */
    fun key(ev: KeyEvent): Boolean {
        val r = root ?: return false
        if (V10Dialog.isOpen(r) || V10List.isOpen(r)) return false
        if (ev.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (ev.action == KeyEvent.ACTION_UP) back()
        return true
    }

    private fun back() {
        val top = stack.removeLastOrNull()
        if (top is Scr.Phone) { phone?.stop(); phone = null }
        if (top is Scr.Remote && stack.none { it is Scr.Remote && it.fs === top.fs }) Thread { runCatching { top.fs.close() } }.start()
        if (stack.isEmpty()) close() else render()
    }

    private fun push(s: Scr) {
        saveFocus()
        stack.add(s); render()
    }

    private fun saveFocus() {
        val l = layer ?: return
        val all = ArrayList<View>(); l.addFocusables(all, View.FOCUS_DOWN)
        focusAt[stack.size] = all.indexOf(act?.currentFocus).coerceAtLeast(0)
    }

    private fun toast(s: String) { act?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() } }

    private fun render() {
        val a = act ?: return; val l = layer ?: return
        l.removeAllViews()
        when (val s = stack.last()) {
            Scr.Home -> home(a, l)
            Scr.Phone -> phoneScreen(a, l)
            is Scr.Remote -> remote(a, l, s)
        }
        l.post {
            val all = ArrayList<View>(); l.addFocusables(all, View.FOCUS_DOWN)
            (all.getOrNull(focusAt[stack.size] ?: 0) ?: all.firstOrNull())?.requestFocus()
        }
    }

    private fun page(a: Activity, title: String, sub: String): LinearLayout {
        val sw = a.resources.displayMetrics.widthPixels; val sh = a.resources.displayMetrics.heightPixels
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding((sw * 0.099f).toInt(), (sh * 0.08f).toInt(), (sw * 0.09f).toInt(), (sh * 0.03f).toInt())
            addView(Taupe.text(a, title, 40f))
            addView(Taupe.text(a, sub, 16f, Taupe.SUB), LinearLayout.LayoutParams(-2, -2).apply { topMargin = (sh * 0.01f).toInt() })
        }
    }

    // ------------------------------------------------------------------ home

    private fun home(a: Activity, l: FrameLayout) {
        val col = page(a, T.s("Network & Connect", "Mreža i povezivanje", "Netzwerk & Verbinden", "Сеть и подключение"),
            T.s("Phone, computer, NAS and servers", "Telefon, računar, NAS i serveri", "Handy, Computer, NAS und Server", "Телефон, компьютер, NAS"))
        val tiles = listOf(
            V10Tile(T.s("Phone Connect", "Poveži telefon", "Handy verbinden", "Подключить телефон"), R.drawable.lc_qr_code,
                T.s("QR code, no app needed", "QR kod, bez aplikacije", "QR-Code, keine App nötig", "QR-код")) { push(Scr.Phone) },
            V10Tile(T.s("SMB / Windows Shares", "SMB / Windows dijeljenja", "SMB / Windows-Freigaben", "SMB / Windows"), R.drawable.lc_server, "SMB 2 / 3") { newConn("smb") },
            V10Tile("FTP / FTPS / SFTP", R.drawable.lc_globe, T.s("Servers", "Serveri", "Server", "Серверы")) {
                V10List.show(root!!, "FTP / FTPS / SFTP", listOf(
                    V10List.Entry("SFTP (SSH)", R.drawable.lc_lock, T.s("encrypted", "šifrirano", "verschlüsselt", "шифруется")) { newConn("sftp") },
                    V10List.Entry("FTPS", R.drawable.lc_lock, T.s("encrypted", "šifrirano", "verschlüsselt", "шифруется")) { newConn("ftps") },
                    V10List.Entry("FTP", R.drawable.lc_globe, T.s("not encrypted", "nije šifrirano", "unverschlüsselt", "без шифрования")) { newConn("ftp") },
                ))
            },
            V10Tile(T.s("Saved Locations", "Spremljene lokacije", "Gespeicherte Orte", "Сохранённые"), R.drawable.lc_star,
                Conns.all(a).size.let { T.s("$it saved", "$it spremljeno", "$it gespeichert", "сохранено: $it") }) { savedMenu() },
            V10Tile(T.s("Network Discovery", "Pronađi na mreži", "Im Netzwerk suchen", "Поиск в сети"), R.drawable.lc_radar,
                T.s("Find shares nearby", "Pronađi dijeljenja", "Freigaben finden", "Найти ресурсы")) { discover() },
        )
        val cw = Taupe.cardW(a); val ch = Taupe.cardH(a); val gap = Taupe.gap(a)
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tiles.chunked(3).forEachIndexed { i, chunk ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            chunk.forEach { t -> row.addView(V10Page.tile(a, t, cw, ch), LinearLayout.LayoutParams(cw, (ch * 0.86f).toInt()).apply { marginStart = gap / 2; marginEnd = gap / 2 }) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) 0 else gap * 2 })
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (a.resources.displayMetrics.heightPixels * 0.05f).toInt() })
        col.addView(Taupe.text(a, "OK " + T.s("Select", "Odaberi", "Auswählen", "Выбрать") + "     ←  " + T.s("Back", "Nazad", "Zurück", "Назад"), 13f, Taupe.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        l.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ Phone Connect

    private fun phoneScreen(a: Activity, l: FrameLayout) {
        val sw = a.resources.displayMetrics.widthPixels
        val col = page(a, T.s("Phone Connect", "Poveži telefon", "Handy verbinden", "Подключить телефон"),
            T.s("Scan the code with the phone's camera", "Skeniraj kod kamerom telefona", "Code mit der Handykamera scannen", "Отсканируйте код камерой"))
        if (PhoneConnect.ip() == null) {
            col.addView(Taupe.text(a, T.s("The TV is not on a home network.", "TV nije na kućnoj mreži.", "Der Fernseher ist in keinem Heimnetz.", "Нет домашней сети."), 18f),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (sw * 0.03f).toInt() })
            l.addView(col); return
        }
        val pc = phone ?: runCatching { PhoneConnect({ who, answer ->
            h.post {
                val activeRoot = root ?: return@post
                if (stack.lastOrNull() !is Scr.Phone) return@post
                V10Dialog.show(activeRoot, T.s("Allow this device?", "Dozvoliti ovaj uređaj?", "Dieses Gerät erlauben?", "Разрешить устройство?"),
                    T.s("$who wants to exchange files with this TV.", "$who želi razmjenjivati fajlove s ovim TV-om.",
                        "$who möchte Dateien mit diesem Fernseher austauschen.", "$who хочет обмениваться файлами."),
                    listOf(T.s("Deny", "Odbij", "Ablehnen", "Запретить") to { answer(false) }, T.s("Allow", "Dozvoli", "Erlauben", "Разрешить") to { answer(true) }))
            }
        }, { e ->
            h.post {
                if (e.startsWith("received:")) { toast(T.s("Received", "Primljeno", "Empfangen", "Получено") + ": " + e.removePrefix("received:")); if (stack.lastOrNull() is Scr.Phone) render() }
                if (e == "expired") { toast(T.s("Phone Connect stopped (unused)", "Povezivanje zaustavljeno (nekorišteno)", "Verbindung beendet (unbenutzt)", "Остановлено")); if (stack.lastOrNull() is Scr.Phone) back() }
            }
        }).also { it.start(); phone = it } }.getOrElse {
            col.addView(Taupe.text(a, T.s("Could not start Phone Connect", "Povezivanje telefona nije pokrenuto",
                "Handy-Verbindung konnte nicht starten", "Не удалось запустить подключение телефона"), 18f))
            l.addView(col)
            return
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val q = (sw * 0.17f).toInt()
        row.addView(ImageView(a).apply {
            setImageBitmap(PhoneConnect.qr(pc.url(), 480))
            background = Taupe.panel(a, 16f); val p = (q * 0.06f).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams(q, q))
        val info = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun line(s: String, c: Int = Taupe.TEXT, size: Float = 15.5f) = info.addView(Taupe.text(a, s, size, c), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (sw * 0.008f).toInt() })
        line(T.s("1  Open the phone camera and point it at the code", "1  Otvori kameru telefona i usmjeri je na kod", "1  Handykamera öffnen und auf den Code richten", "1  Наведите камеру на код"))
        line(T.s("2  Open the link – the TV asks you to allow it", "2  Otvori link – TV pita za dozvolu", "2  Link öffnen – der Fernseher fragt nach Erlaubnis", "2  Откройте ссылку и разрешите"))
        line(T.s("3  Send files, or download the ones offered here", "3  Šalji fajlove ili preuzmi ponuđene", "3  Dateien senden oder angebotene laden", "3  Отправляйте или скачивайте файлы"))
        line(pc.url(), Taupe.SUB, 13f)
        line(T.s("Also works on a computer browser. Trusted local network only · unencrypted · stops when you leave this screen.",
            "Radi i u browseru računara. Samo pouzdana lokalna mreža · nije šifrirano · prestaje kad izađeš s ovog ekrana.",
            "Geht auch im Browser am Computer. Nur vertrauenswürdiges lokales Netz · unverschlüsselt · endet beim Verlassen dieses Bildschirms.",
            "Работает и в браузере компьютера. Только доверенная локальная сеть · без шифрования."), Taupe.MUTED, 12.5f)
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (sw * 0.03f).toInt() })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (sw * 0.025f).toInt() })
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, ic: Int, act2: () -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Taupe.pill(a, true); isFocusable = true; isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            val p = (sw * 0.008f).toInt(); setPadding(p * 2, p, p * 2, p)
            addView(Taupe.icon(a, ic, (sw * 0.015f).toInt()), LinearLayout.LayoutParams((sw * 0.015f).toInt(), (sw * 0.015f).toInt()).apply { marginEnd = p })
            addView(Taupe.text(a, t, 14f)); setOnClickListener { act2() }
            btns.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = p * 2 })
        }
        btn(T.s("Received files (${pc.received})", "Primljeni fajlovi (${pc.received})", "Empfangene Dateien (${pc.received})", "Полученные (${pc.received})"), R.drawable.lc_folder_down) {
            (a as? FileBrowserActivity)?.let { close(); it.startActivity(android.content.Intent(it, FileBrowserActivity::class.java)
                .putExtra(FileBrowserActivity.EXTRA_START, PhoneConnect.inbox().absolutePath)) }
        }
        btn(T.s("Offered to the phone: ${PhoneConnect.offered.size}", "Ponuđeno telefonu: ${PhoneConnect.offered.size}", "Dem Handy angeboten: ${PhoneConnect.offered.size}", "Предложено: ${PhoneConnect.offered.size}"), R.drawable.lc_share) {
            V10List.show(root!!, T.s("Offered to the phone", "Ponuđeno telefonu", "Dem Handy angeboten", "Предложено"),
                PhoneConnect.offered.map { f -> V10List.Entry(f.name, R.drawable.lc_x, T.s("remove", "ukloni", "entfernen", "убрать")) { PhoneConnect.offered.remove(f); render() } } +
                    V10List.Entry(T.s("To offer files: select them in Files › Send › Phone Connect", "Da ponudiš fajlove: označi ih u Fajlovi › Pošalji › Poveži telefon",
                        "Zum Anbieten: in Dateien auswählen › Senden › Handy verbinden", "Выберите файлы › Отправить"), R.drawable.lc_info) { })
        }
        col.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (sw * 0.02f).toInt() })
        l.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ connections

    private fun newConn(proto: String, host: String = "", port: Int = 0, name: String = "") {
        val r = root ?: return
        fun ask(title: String, v: String, pw: Boolean = false, next: (String) -> Unit) = V10Input.show(r, title, v, pw, next)
        ask(T.s("Server address (IP or name)", "Adresa servera (IP ili ime)", "Serveradresse (IP oder Name)", "Адрес сервера"), host) { hst ->
            if (hst.isBlank()) return@ask
            ask(if (proto == "smb") T.s("Share name (e.g. Movies)", "Ime dijeljenja (npr. Filmovi)", "Freigabename (z. B. Filme)", "Имя ресурса")
                else T.s("Start folder (empty = /)", "Početni folder (prazno = /)", "Startordner (leer = /)", "Начальная папка"), "") { share ->
                ask(T.s("User name (empty = guest)", "Korisničko ime (prazno = gost)", "Benutzername (leer = Gast)", "Имя пользователя"), "") { user ->
                    ask(T.s("Password", "Lozinka", "Passwort", "Пароль"), "", true) { pass ->
                        val c = Conn(System.currentTimeMillis().toString(), name.ifBlank { hst.trim() + if (share.isNotBlank()) " / " + share.trim() else "" },
                            proto, hst.trim(), if (port > 0) port else Conn.defaultPort(proto), user.trim(), share.trim())
                        connect(c, pass) { ok ->
                            if (!ok) return@connect
                            V10Dialog.show(r, T.s("Save this location?", "Spremiti ovu lokaciju?", "Diesen Ort speichern?", "Сохранить?"), c.label(), listOf(
                                T.s("No", "Ne", "Nein", "Нет") to {},
                                T.s("Save without password", "Spremi bez lozinke", "Ohne Passwort speichern", "Без пароля") to { Conns.save(act!!, c) },
                                T.s("Save with password", "Spremi s lozinkom", "Mit Passwort speichern", "С паролем") to { Conns.save(act!!, c.copy(secret = Conns.encrypt(pass))) }), focusIndex = 1)
                        }
                    }
                }
            }
        }
    }

    private fun savedMenu() {
        val a = act ?: return; val r = root ?: return
        val all = Conns.all(a)
        if (all.isEmpty()) {
            V10List.show(r, T.s("Saved Locations", "Spremljene lokacije", "Gespeicherte Orte", "Сохранённые"), listOf(
                V10List.Entry("SMB / Windows", R.drawable.lc_server) { newConn("smb") },
                V10List.Entry("SFTP", R.drawable.lc_lock) { newConn("sftp") },
                V10List.Entry("FTPS", R.drawable.lc_lock) { newConn("ftps") },
                V10List.Entry("FTP", R.drawable.lc_globe) { newConn("ftp") },
            ), onClose = { if (pending.isNotEmpty() && stack.size <= 1) close() })
            return
        }
        V10List.show(r, T.s("Saved Locations", "Spremljene lokacije", "Gespeicherte Orte", "Сохранённые"), all.map { c ->
            V10List.Entry(c.label(), if (c.proto == "smb") R.drawable.lc_server else R.drawable.lc_globe, c.proto.uppercase() + if (c.secret.isNotBlank()) " · 🔒" else "") {
                V10List.show(r, c.label(), listOf(
                    V10List.Entry(T.s("Open", "Otvori", "Öffnen", "Открыть"), R.drawable.lc_folder) { openSaved(c) },
                    V10List.Entry(T.s("Forget password", "Zaboravi lozinku", "Passwort vergessen", "Забыть пароль"), R.drawable.lc_key_round) { Conns.save(a, c.copy(secret = "")) },
                    V10List.Entry(T.s("Delete location", "Obriši lokaciju", "Ort löschen", "Удалить"), R.drawable.lc_trash_2) { Conns.delete(a, c.id); render() },
                ))
            }
        } + V10List.Entry(T.s("New location…", "Nova lokacija…", "Neuer Ort…", "Новое место…"), R.drawable.lc_plus) {
            V10List.show(r, T.s("New location", "Nova lokacija", "Neuer Ort", "Новое место"), listOf(
                V10List.Entry("SMB / Windows", R.drawable.lc_server) { newConn("smb") },
                V10List.Entry("SFTP", R.drawable.lc_lock) { newConn("sftp") },
                V10List.Entry("FTPS", R.drawable.lc_lock) { newConn("ftps") },
                V10List.Entry("FTP", R.drawable.lc_globe) { newConn("ftp") }))
        }, onClose = { if (pending.isNotEmpty() && stack.size <= 1) close() })
    }

    private fun openSaved(c: Conn) {
        val pw = Conns.decrypt(c.secret)
        if (pw != null || c.user.isBlank()) connect(c, pw ?: "") {}
        else V10Input.show(root!!, T.s("Password for ${c.label()}", "Lozinka za ${c.label()}", "Passwort für ${c.label()}", "Пароль"), "", true) { connect(c, it) {} }
    }

    private fun connect(c: Conn, password: String, done: (Boolean) -> Unit) {
        if (c.proto == "sftp" && Build.VERSION.SDK_INT < 26) {
            toast(T.s("SFTP needs Android 8 or newer", "SFTP treba Android 8 ili noviji", "SFTP braucht Android 8 oder neuer", "SFTP требует Android 8+")); done(false); return
        }
        toast(T.s("Connecting…", "Povezujem…", "Verbinde…", "Подключение…"))
        Thread {
            val res = runCatching { Conns.open(c, password) }
            h.post {
                res.onSuccess { fs -> done(true); push(Scr.Remote(c, fs, fs.root)) }
                res.onFailure { e ->
                    done(false)
                    V10Dialog.show(root!!, T.s("Could not connect", "Povezivanje nije uspjelo", "Verbindung fehlgeschlagen", "Не удалось подключиться"),
                        c.proto.uppercase() + " · " + c.host + "\n" + (e.message ?: e.javaClass.simpleName).take(160), listOf("OK" to {}))
                }
            }
        }.start()
    }

    private fun discover() {
        val a = act ?: return; val r = root ?: return
        // "proto|host" → (name, port)
        val found = LinkedHashMap<String, Pair<String, Int>>()
        val d = Discovery(a) { name, host, port, proto -> synchronized(found) { found["$proto|$host"] = name to port } }
        toast(T.s("Searching the home network for 6 seconds…", "Tražim na kućnoj mreži 6 sekundi…", "Suche 6 Sekunden im Heimnetz…", "Поиск 6 секунд…"))
        d.start()
        h.postDelayed({
            d.stop()
            val list = synchronized(found) { found.toList() }
            if (list.isEmpty()) {
                V10Dialog.show(r, T.s("Nothing found", "Ništa nije pronađeno", "Nichts gefunden", "Ничего не найдено"),
                    T.s("Not every computer or NAS announces itself. Enter the address by hand instead.",
                        "Ne javlja se svaki računar ili NAS. Unesi adresu ručno.",
                        "Nicht jeder Computer oder NAS meldet sich. Adresse lieber von Hand eingeben.", "Введите адрес вручную."),
                    listOf(T.s("Close", "Zatvori", "Schließen", "Закрыть") to {}, T.s("Enter address", "Unesi adresu", "Adresse eingeben", "Ввести адрес") to { newConn("smb") }))
                return@postDelayed
            }
            V10List.show(r, T.s("Found on the network", "Pronađeno na mreži", "Im Netzwerk gefunden", "Найдено"), list.map { (k, v) ->
                val proto = k.substringBefore('|'); val host = k.substringAfter('|')
                V10List.Entry(v.first, if (proto == "smb") R.drawable.lc_server else R.drawable.lc_globe, proto.uppercase() + " · " + host) {
                    newConn(proto, host, v.second, v.first)
                }
            })
        }, 6000)
    }

    // ------------------------------------------------------------------ remote folder

    private fun remote(a: Activity, l: FrameLayout, s: Scr.Remote) {
        val sw = a.resources.displayMetrics.widthPixels; val sh = a.resources.displayMetrics.heightPixels
        val col = page(a, s.path.trimEnd('/').substringAfterLast('/').ifBlank { s.conn.label() },
            s.conn.proto.uppercase() + "  ·  " + s.conn.host + s.path.ifBlank { "/" } + if (s.conn.proto == "ftp") "  ·  " + T.s("not encrypted", "nije šifrirano", "unverschlüsselt", "без шифрования") else "")
        val inner = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipChildren = true; clipToPadding = true; addView(inner) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (sh * 0.02f).toInt() })
        val loading = Taupe.text(a, T.s("Loading…", "Učitavam…", "Lade…", "Загрузка…"), 15f, Taupe.MUTED)
        inner.addView(loading)
        if (pending.isNotEmpty()) {
            val bar = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Taupe.panel(a, 18f); val p = (sw * 0.006f).toInt(); setPadding(p * 2, p, p * 2, p)
            }
            fun pb(t: String, go: () -> Unit) = Taupe.text(a, t, 14f).apply {
                background = Taupe.pill(a); isFocusable = true; isClickable = true
                val p = (sw * 0.007f).toInt(); setPadding(p * 2, p, p * 2, p); setOnClickListener { go() }
                bar.addView(this)
            }
            bar.addView(Taupe.text(a, T.s("Upload ${pending.size}", "Pošalji ${pending.size}", "${pending.size} hochladen", "Загрузить ${pending.size}"), 14f, Taupe.SUB).apply { setPadding((sw * 0.01f).toInt(), 0, (sw * 0.01f).toInt(), 0) })
            pb(T.s("Upload Here", "Pošalji ovdje", "Hier hochladen", "Загрузить сюда")) { upload(s) }
            pb(T.s("Cancel", "Otkaži", "Abbrechen", "Отмена")) { pending = emptyList(); render() }
            col.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (sh * 0.01f).toInt() })
        }
        col.addView(Taupe.text(a, T.s("OK Open / Download     ←  Back", "OK Otvori / preuzmi     ←  Nazad", "OK Öffnen / Laden     ←  Zurück", "OK Открыть     ←  Назад"), 13f, Taupe.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (sh * 0.01f).toInt() })
        l.addView(col, FrameLayout.LayoutParams(-1, -1))
        Thread {
            val res = runCatching { s.fs.list(s.path).sortedWith(compareBy<RFile> { !it.isDir }.thenBy { it.name.lowercase() }) }
            h.post {
                if (stack.lastOrNull() !== s) return@post
                inner.removeAllViews()
                res.onFailure { e -> inner.addView(Taupe.text(a, (e.message ?: "?").take(200), 15f, Taupe.MUTED)) }
                res.onSuccess { files ->
                    if (files.isEmpty()) inner.addView(Taupe.text(a, T.s("This folder is empty", "Ovaj folder je prazan", "Dieser Ordner ist leer", "Папка пуста"), 15f, Taupe.MUTED))
                    files.forEach { f -> inner.addView(row(a, f) { onRemote(s, f) }, LinearLayout.LayoutParams(-1, (sh * 0.085f).toInt()).apply { bottomMargin = (sh * 0.01f).toInt() }) }
                    val all = ArrayList<View>(); inner.addFocusables(all, View.FOCUS_DOWN)
                    (all.getOrNull(focusAt[stack.size] ?: 0) ?: all.firstOrNull() ?: l.findFocus())?.requestFocus()
                }
            }
        }.start()
    }

    private fun row(a: Activity, f: RFile, click: () -> Unit) = LinearLayout(a).apply {
        val sw = a.resources.displayMetrics.widthPixels
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Taupe.row(a); isFocusable = true; isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setPadding((sw * 0.018f).toInt(), 0, (sw * 0.018f).toInt(), 0)
        val ic = (sw * 0.02f).toInt()
        addView(Taupe.icon(a, if (f.isDir) R.drawable.lc_folder else R.drawable.lc_file, ic), LinearLayout.LayoutParams(ic, ic).apply { marginEnd = (sw * 0.016f).toInt() })
        addView(Taupe.text(a, f.name, 17f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Taupe.text(a, if (f.isDir) T.s("Folder", "Folder", "Ordner", "Папка") else Formatter.formatShortFileSize(a, f.size), 14f, Taupe.MUTED))
        setOnClickListener { click() }
    }

    private fun onRemote(s: Scr.Remote, f: RFile) {
        if (f.isDir) { push(Scr.Remote(s.conn, s.fs, f.path)); return }
        val r = root ?: return
        val dl = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "LEAD Network").apply { mkdirs() }
        V10List.show(r, f.name, listOf(
            V10List.Entry(T.s("Open (download first)", "Otvori (prvo preuzmi)", "Öffnen (erst laden)", "Открыть"), R.drawable.lc_play, Formatter.formatShortFileSize(r.context, f.size)) {
                download(s, f, dl) { file -> act?.let { Destinations.opened(it, file); MediaViewerActivity.openDefault(it, file) } }
            },
            V10List.Entry(T.s("Download to the TV", "Preuzmi na TV", "Auf den Fernseher laden", "Скачать на ТВ"), R.drawable.lc_download, "Download/LEAD Network") {
                download(s, f, dl) { toast(T.s("Saved in Download/LEAD Network", "Spremljeno u Download/LEAD Network", "In Download/LEAD Network gespeichert", "Сохранено")) }
            },
        ))
    }

    private fun progress(a: Activity, text: String): Pair<FrameLayout, android.widget.TextView> {
        val shade = FrameLayout(a).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)); isClickable = true; tag = "v10busy" }
        val t = Taupe.text(a, text, 15f).apply {
            background = Taupe.panel(a, 18f)
            val p = (a.resources.displayMetrics.widthPixels * 0.015f).toInt(); setPadding(p * 2, p, p * 2, p)
        }
        shade.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root?.addView(shade, FrameLayout.LayoutParams(-1, -1))
        return shade to t
    }

    private fun download(s: Scr.Remote, f: RFile, dir: File, done: (File) -> Unit) {
        val a = act ?: return
        val activeRoot = root ?: return
        if (f.name.isBlank() || f.name == "." || f.name == ".." || f.name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            toast(T.s("Invalid file name", "Neispravan naziv fajla", "Ungültiger Dateiname", "Недопустимое имя файла"))
            return
        }
        val tmp = File(dir, ".part_" + System.nanoTime())
        val (shade, label) = progress(a, T.s("Downloading…", "Preuzimam…", "Lade…", "Загрузка…"))
        var last = 0L
        Thread {
            val res = runCatching { s.fs.download(f.path, tmp) { n ->
                val now = System.currentTimeMillis(); if (now - last > 250) { last = now; h.post { label.text = Formatter.formatShortFileSize(a, n) + " / " + Formatter.formatShortFileSize(a, f.size) } }
            } }
            val result = runCatching {
                res.getOrThrow()
                check(tmp.isFile && (f.size < 0 || tmp.length() == f.size))
                val target = File(dir, FileOps.uniqueName(dir, f.name))
                check(FileOps.transfer(tmp, target, move = true, replace = false))
                target
            }
            tmp.delete()
            h.post {
                if (root !== activeRoot || a.isDestroyed) return@post
                activeRoot.removeView(shade)
                result.onSuccess(done).onFailure {
                    V10Dialog.show(activeRoot, T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen", "Ошибка загрузки"),
                        f.name + "\n" + (res.exceptionOrNull()?.message ?: T.s("Incomplete or could not save",
                            "Nepotpuno ili se ne može sačuvati", "Unvollständig oder nicht speicherbar", "Неполный файл или ошибка сохранения")).take(160),
                        listOf("OK" to {}))
                }
            }
        }.start()
    }

    private fun upload(s: Scr.Remote) {
        val a = act ?: return
        val activeRoot = root ?: return
        val files = pending.filter { it.isFile }
        val skippedDirs = pending.size - files.size
        val (shade, label) = progress(a, T.s("Uploading…", "Šaljem…", "Lade hoch…", "Загрузка…"))
        Thread {
            val failed = ArrayList<String>(); var done = 0; var skipped = 0
            files.forEachIndexed { i, f ->
                // never overwrite on the server without asking: existing names are skipped and reported
                if (s.fs.exists(s.path, f.name)) { skipped++; return@forEachIndexed }
                h.post { label.text = "${i + 1} / ${files.size}  ·  ${f.name}" }
                if (runCatching { s.fs.upload(f, s.path) { } }.isSuccess) done++ else failed.add(f.name)
            }
            h.post {
                if (root !== activeRoot || a.isDestroyed) return@post
                activeRoot.removeView(shade)
                pending = emptyList(); afterUpload()
                render()
                val extra = listOfNotNull(
                    if (skipped > 0) T.s("$skipped already there (skipped)", "$skipped već postoji (preskočeno)", "$skipped schon vorhanden (übersprungen)", "пропущено: $skipped") else null,
                    if (skippedDirs > 0) T.s("$skippedDirs folders not uploaded", "$skippedDirs foldera nije poslano", "$skippedDirs Ordner nicht hochgeladen", "папок пропущено: $skippedDirs") else null,
                ) + failed
                if (extra.isEmpty()) toast(T.s("$done uploaded", "$done poslano", "$done hochgeladen", "загружено: $done"))
                else V10Dialog.show(root!!, T.s("$done uploaded", "$done poslano", "$done hochgeladen", "загружено: $done"), extra.take(8).joinToString("\n"), listOf("OK" to {}))
            }
        }.start()
    }
}
