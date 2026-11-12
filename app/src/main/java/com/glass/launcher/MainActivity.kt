package com.glass.launcher

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView
    private lateinit var dim: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var statusPill: GlassFrame
    private lateinit var profileBtn: GlassFrame
    private lateinit var settingsBtn: GlassFrame
    private lateinit var profileLetter: TextView
    private var tickCount = 0
    private var lastInput = SystemClock.elapsedRealtime()
    private lateinit var powerBtn: GlassFrame
    private lateinit var grid: RecyclerView
    private lateinit var hint: TextView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: AppAdapter
    private lateinit var menu: GlassMenu

    private val apps = mutableListOf<AppEntry>()
    private val handler = Handler(Looper.getMainLooper())
    private var movingPos = -1
    private var lastPos = 0
    private var firstLoad = true
    private var lastWeather = 0L
    private var pendingPermission: (() -> Unit)? = null
    private var pendingPick: ((File) -> Unit)? = null

    private val wallpaperFile get() = File(filesDir, Profiles.wallpaperName(prefs.profile))

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = reloadApps()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            if (tickCount++ % 5 == 0) {
                updateStatus()
                if (System.currentTimeMillis() - lastWeather > 30 * 60 * 1000L) updateWeather()
                if (SystemClock.elapsedRealtime() < 3 * 60 * 1000L) Autostart.apply(this@MainActivity)
            }
            checkIdle()
            handler.postDelayed(this, 1_000)
        }
    }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        T.init(prefs.lang)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        wallpaper = findViewById(R.id.wallpaper)
        dim = findViewById(R.id.dim)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        statusPill = findViewById(R.id.statusPill)
        grid = findViewById(R.id.grid)
        hint = findViewById(R.id.hint)
        profileBtn = findViewById(R.id.profileBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        powerBtn = findViewById(R.id.powerBtn)
        profileLetter = findViewById(R.id.profileLetter)

        menu = GlassMenu(this, root) { open ->
            grid.descendantFocusability =
                if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            profileBtn.isFocusable = !open
            settingsBtn.isFocusable = !open
            powerBtn.isFocusable = !open
            if (!open) focusTile(lastPos)
        }

        for (b in listOf(profileBtn, settingsBtn, powerBtn)) {
            b.radiusDp = 21f
            b.setOnFocusChangeListener { v, has ->
                (v as GlassFrame).focusedLook = has
                val sc = if (has) 1.15f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).withEndAction { v.invalidate() }.start()
            }
        }
        profileBtn.setOnClickListener { profileMenu() }
        settingsBtn.setOnClickListener { showSettings() }
        powerBtn.setOnClickListener { powerMenu() }

        statusPill.radiusDp = 24f
        adapter = AppAdapter(apps, prefs, ::onAppClick, ::onAppLongClick)
        layoutManager = GridLayoutManager(this, prefs.columns)
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        (grid.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = invalidateGlass()
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)

        NotifListener.onChange = { runOnUiThread { updateStatus() } }

        applyLook()
        reloadApps()
        askLocationOnce()
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        val day = 24 * 60 * 60 * 1000L
        if (prefs.updateUrl.isNotBlank() && System.currentTimeMillis() - prefs.lastUpdateCheck > day) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            checkUpdate(manual = false)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(packageReceiver) }
        NotifListener.onChange = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while we are already open
        menu.close()
        if (movingPos >= 0) finishMove()
        focusTile(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.isOpen) menu.close()
        // This is the home screen: Back does nothing else.
    }

    // ================================================================== look

    private fun invalidateGlass() {
        statusPill.invalidate()
        for (i in 0 until grid.childCount) grid.getChildAt(i).findViewById<View>(R.id.tile)?.invalidate()
    }

    private fun applyLook() {
        dim.setBackgroundColor(Color.argb(prefs.dim * 255 / 100, 0, 0, 0))
        statusPill.tint = prefs.glassAlpha / 100f
        profileBtn.tint = prefs.glassAlpha / 100f
        settingsBtn.tint = prefs.glassAlpha / 100f
        powerBtn.tint = prefs.glassAlpha / 100f
        profileLetter.text = Profiles.name(this, prefs.profile).take(1).uppercase()
        (grid.layoutParams as FrameLayout.LayoutParams).gravity = when (prefs.gridPosition) {
            0 -> Gravity.TOP
            1 -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        }
        (hint.layoutParams as FrameLayout.LayoutParams).gravity = when (prefs.gridPosition) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.CENTER_VERTICAL or Gravity.START
            else -> Gravity.BOTTOM or Gravity.START
        }
        grid.requestLayout()
        layoutManager.spanCount = prefs.columns
        adapter.notifyDataSetChanged()
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        hint.text = T.s("Press OK to add your apps", "Pritisni OK da dodaš svoje aplikacije", "OK drücken, um deine Apps hinzuzufügen")
        loadWallpaper()
        updateClock()
        updateStatus()
    }

    private fun loadWallpaper() {
        val f = wallpaperFile
        val blur = prefs.blurWallpaper
        val dm = resources.displayMetrics
        val w = if (root.width > 0) root.width else dm.widthPixels
        val h = if (root.height > 0) root.height else dm.heightPixels
        Thread {
            val src: Bitmap? = runCatching {
                if (f.exists()) Bitmaps.decodeScaled(f, w, h)
                else BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default)
            }.getOrNull()
            if (src == null) return@Thread
            val crop = Bitmaps.centerCrop(src, w, h)
            val frosted = Bitmaps.frosted(crop)
            val shown = if (blur) Bitmaps.softBlur(crop) else crop
            handler.post {
                Backdrop.blurred = frosted
                Backdrop.w = w
                Backdrop.h = h
                wallpaper.setImageBitmap(shown)
                invalidateGlass()
            }
        }.start()
    }

    // ================================================================== status bar

    private fun updateClock() {
        clock.text = SimpleDateFormat(ClockFmt.pattern(this, prefs), T.locale).format(Date())
        val d = SimpleDateFormat("EEEE, d. MMMM", T.locale).format(Date())
        date.text = d.replaceFirstChar { it.uppercase() }
    }

    private fun updateStatus() {
        val net = NetInfo.read(this)
        val netBox = findViewById<View>(R.id.netBox)
        val netIcon = findViewById<ImageView>(R.id.netIcon)
        val ssid = findViewById<TextView>(R.id.ssidText)
        netBox.visibility = if (prefs.showNetwork) View.VISIBLE else View.GONE
        when {
            net.eth -> { netIcon.setImageResource(R.drawable.lc_ethernet_port); netIcon.alpha = 1f }
            net.wifi -> { netIcon.setImageResource(R.drawable.lc_wifi); netIcon.alpha = 1f }
            else -> { netIcon.setImageResource(R.drawable.lc_wifi_off); netIcon.alpha = 0.5f }
        }
        val showSsid = net.wifi && !net.eth && net.ssid != null
        ssid.visibility = if (showSsid) View.VISIBLE else View.GONE
        if (showSsid) ssid.text = net.ssid

        findViewById<View>(R.id.vpnBox).visibility = if (prefs.showVpn) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.vpnIcon).setColorFilter(
            if (net.vpn) Color.rgb(52, 212, 122) else Color.rgb(255, 80, 80))

        findViewById<View>(R.id.notifBox).visibility = if (prefs.showNotifs) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.notifCount).text =
            if (NotifListener.connected) NotifListener.current().size.toString() else "–"

        findViewById<View>(R.id.cpuBox).visibility = if (prefs.showCpu) View.VISIBLE else View.GONE
        if (prefs.showCpu) findViewById<TextView>(R.id.cpuText).text = SysStats.cpuPercent()?.let { "$it%" } ?: "–"
        findViewById<View>(R.id.ramBox).visibility = if (prefs.showRam) View.VISIBLE else View.GONE
        if (prefs.showRam) findViewById<TextView>(R.id.ramText).text = "${SysStats.ramPercent(this)}%"

        findViewById<View>(R.id.weatherBox).visibility = if (prefs.showWeather) View.VISIBLE else View.GONE

        val left = prefs.showWeather || prefs.showNetwork || prefs.showVpn || prefs.showNotifs
        val right = prefs.showCpu || prefs.showRam
        findViewById<View>(R.id.divider).visibility = if (left && right) View.VISIBLE else View.GONE
        // first visible group must not have a left margin
        val groups = listOf(R.id.weatherBox, R.id.netBox, R.id.vpnBox, R.id.notifBox, R.id.cpuBox, R.id.ramBox)
            .map { findViewById<View>(it) }
        val firstVisible = groups.firstOrNull { it.visibility == View.VISIBLE }
        val m = (18 * resources.displayMetrics.density).toInt()
        for (g in groups) {
            val lp = g.layoutParams as ViewGroup.MarginLayoutParams
            val want = if (g == firstVisible) 0 else m
            if (lp.marginStart != want) { lp.marginStart = want; g.layoutParams = lp }
        }
        statusPill.visibility = if (prefs.showStatusBar && (left || right)) View.VISIBLE else View.GONE
    }

    private fun updateWeather() {
        if (!prefs.showWeather) return
        lastWeather = System.currentTimeMillis()
        val lat = prefs.lat
        val lon = prefs.lon
        Thread {
            val w = Weather.current(lat, lon)
            handler.post {
                if (w != null) {
                    findViewById<ImageView>(R.id.weatherIcon).setImageResource(w.first)
                    findViewById<TextView>(R.id.weatherText).text = w.second
                } else {
                    lastWeather = System.currentTimeMillis() - 25 * 60 * 1000L // retry in ~5 min
                }
            }
        }.start()
    }

    private fun askLocationOnce() {
        // Android only tells apps the Wi-Fi name (SSID) with location permission
        if (prefs.askedLocation || !prefs.showNetwork) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        prefs.askedLocation = true
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
    }

    // ================================================================== apps on the home screen

    private fun reloadApps() {
        Thread {
            val list = AppLoader.pinnedEntries(this, prefs)
            val size = AppLoader.iconSize(this)
            val plus = getDrawable(R.drawable.lc_plus)?.let { IconRender.tinted(it, size, Color.WHITE) }
            handler.post {
                apps.clear()
                apps.addAll(list)
                apps.add(AppEntry(AppEntry.ADD, T.s("Add", "Dodaj", "Hinzufügen"), plus))
                adapter.notifyDataSetChanged()
                hint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (firstLoad || list.isEmpty()) {
                    firstLoad = false
                    focusTile(0)
                }
            }
        }.start()
    }

    private fun focusTile(pos: Int) {
        grid.post {
            if (apps.isEmpty()) return@post
            val p = pos.coerceIn(0, apps.size - 1)
            grid.scrollToPosition(p)
            grid.post { grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
        }
    }

    private fun onAppClick(pos: Int) {
        lastPos = pos
        if (movingPos >= 0) { finishMove(); return }
        val e = apps[pos]
        when {
            e.isAdd -> showAddMenu()
            e.isFiles -> startActivity(Intent(this, FileBrowserActivity::class.java))
            else -> launch(e.pkg, e.label)
        }
    }

    private fun launch(pkg: String, label: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast(T.s("Can't open $label", "Ne mogu otvoriti $label", "$label lässt sich nicht öffnen")); return }
        try { startActivity(i) } catch (ex: Exception) { toast(T.s("Can't open $label", "Ne mogu otvoriti $label", "$label lässt sich nicht öffnen")) }
    }

    private fun onAppLongClick(pos: Int) {
        lastPos = pos
        val e = apps[pos]
        if (e.isAdd) { showSettings(); return }
        val items = mutableListOf(
            GlassMenu.Item(T.s("Change icon – own picture", "Promijeni ikonu – vlastita slika", "Symbol ändern – eigenes Bild"), R.drawable.lc_image) {
                pickImage { f -> f.copyTo(IconStore.file(this, e.pkg), true); reloadApps() }
            },
            GlassMenu.Item(T.s("Change icon – from the internet", "Promijeni ikonu – s interneta", "Symbol ändern – aus dem Internet"), R.drawable.lc_link) {
                askText(T.s("Icon image URL", "Link slike za ikonu", "Bild-URL für das Symbol"), "https://") { url ->
                    download(url, IconStore.file(this, e.pkg)) { reloadApps() }
                }
            },
            GlassMenu.Item(T.s("Original icon", "Vrati originalnu ikonu", "Originalsymbol"), R.drawable.lc_rotate_ccw) {
                IconStore.file(this, e.pkg).delete(); reloadApps()
            },
            GlassMenu.Item(T.s("Move", "Pomjeri", "Verschieben"), R.drawable.lc_move) { startMove(pos) },
            GlassMenu.Item(T.s("Rename", "Preimenuj", "Umbenennen"), R.drawable.lc_pencil) {
                askText(T.s("Rename", "Preimenuj", "Umbenennen"), e.label) { prefs.setLabel(e.pkg, it); reloadApps() }
            },
            GlassMenu.Item(T.s("Remove from home screen", "Ukloni s početnog ekrana", "Vom Startbildschirm entfernen"), R.drawable.lc_trash_2) {
                prefs.pinned = prefs.pinned - e.pkg; lastPos = 0; reloadApps()
            },
        )
        if (!e.isFiles) {
            items.add(GlassMenu.Item(T.s("App info", "Informacije o aplikaciji", "App-Info"), R.drawable.lc_info) {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.pkg, null)))
            })
        }
        menu.show(e.label, null, items)
    }

    // ------------------------------------------------------------------ "+" menu

    private fun showAddMenu() {
        menu.show(T.s("Add apps", "Dodaj aplikacije", "Apps hinzufügen"), R.drawable.lc_plus, listOf(
            GlassMenu.Item(T.s("Choose from installed apps", "Izaberi iz instaliranih aplikacija", "Aus installierten Apps wählen"), R.drawable.lc_layout_grid) { chooseApps() },
            GlassMenu.Item(T.s("Open Google Play", "Otvori Google Play", "Google Play öffnen"), R.drawable.lc_shopping_bag) { openPlay() },
            GlassMenu.Item(T.s("Install from your phone", "Instaliraj s telefona", "Vom Handy installieren"), R.drawable.lc_smartphone) { phoneHelp() },
            GlassMenu.Item(T.s("Install APK from files", "Instaliraj APK iz fajlova", "APK aus Dateien installieren"), R.drawable.lc_package) {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                startActivity(Intent(this, FileBrowserActivity::class.java).putExtra(FileBrowserActivity.EXTRA_START, dl.absolutePath))
            },
            GlassMenu.Item(T.s("Launcher settings", "Postavke launchera", "Launcher-Einstellungen"), R.drawable.lc_settings) { showSettings() },
        ))
    }

    private fun chooseApps() {
        Thread {
            val all = AppLoader.choosable(this)
            handler.post {
                val pinned = prefs.pinned
                val items = all.map { (pkg, label, icon) ->
                    GlassMenu.Item(label, drawable = icon, checked = pkg in pinned, toggle = true) { item ->
                        val cur = prefs.pinned
                        prefs.pinned = if (item.checked) (if (pkg in cur) cur else cur + pkg) else cur - pkg
                        reloadApps()
                    }
                } + GlassMenu.Item(T.s("Done", "Gotovo", "Fertig"), R.drawable.lc_check)
                menu.show(T.s("Apps on the home screen", "Aplikacije na početnom ekranu", "Apps auf dem Startbildschirm"),
                    R.drawable.lc_layout_grid, items)
            }
        }.start()
    }

    private fun openPlay() {
        val p = "com.android.vending"
        val i = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)
        if (i != null) { safeStart(i); return }
        menu.message("Google Play", R.drawable.lc_shopping_bag, T.s(
            "Google Play isn't installed on this box.",
            "Google Play nije instaliran na ovom boxu.",
            "Google Play ist auf dieser Box nicht installiert."))
    }

    private fun phoneHelp() {
        menu.message(T.s("Install from your phone", "Instaliraj s telefona", "Vom Handy installieren"), R.drawable.lc_smartphone, T.s(
            "1. On your phone open play.google.com in the browser.\n" +
                "2. Sign in with the same Google account as on this box.\n" +
                "3. Pick an app, press Install and choose this TV box.\n" +
                "4. The app installs on the box by itself. Then add it here with +.\n\n" +
                "The Google Home app on your phone works as a remote and keyboard for the box.",
            "1. Na telefonu u browseru otvori play.google.com.\n" +
                "2. Prijavi se istim Google računom kao na ovom boxu.\n" +
                "3. Izaberi aplikaciju, klikni Instaliraj i izaberi ovaj TV box.\n" +
                "4. Aplikacija se sama instalira na box. Onda je dodaj ovdje preko +.\n\n" +
                "Google Home aplikacija na telefonu služi kao daljinski i tastatura za box.",
            "1. Öffne auf dem Handy play.google.com im Browser.\n" +
                "2. Melde dich mit demselben Google-Konto wie auf dieser Box an.\n" +
                "3. App auswählen, Installieren drücken und diese TV-Box wählen.\n" +
                "4. Die App wird automatisch auf der Box installiert. Dann hier mit + hinzufügen.\n\n" +
                "Die Google Home App auf dem Handy dient als Fernbedienung und Tastatur."))
    }

    // ------------------------------------------------------------------ moving tiles

    private fun startMove(pos: Int) {
        movingPos = pos
        adapter.movingPos = pos
        adapter.notifyItemChanged(pos)
        focusTile(pos)
        toast(T.s("Move with the arrows, press OK when done", "Pomjeraj strelicama, pritisni OK kad završiš", "Mit den Pfeiltasten verschieben, OK zum Ablegen"))
    }

    private fun finishMove() {
        val p = movingPos
        movingPos = -1
        adapter.movingPos = -1
        if (p >= 0) adapter.notifyItemChanged(p)
        prefs.pinned = apps.filter { !it.isAdd }.map { it.pkg }
    }

    private fun moveBy(delta: Int) {
        val last = apps.size - 2 // "+" stays at the end
        val target = (movingPos + delta).coerceIn(0, maxOf(0, last))
        if (target == movingPos) return
        val item = apps.removeAt(movingPos)
        apps.add(target, item)
        adapter.notifyItemMoved(movingPos, target)
        movingPos = target
        adapter.movingPos = target
        lastPos = target
        grid.scrollToPosition(target)
        grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        val k = event.keyCode
        if (menu.isOpen && k == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) menu.close()
            return true
        }
        if (movingPos >= 0 && !menu.isOpen) {
            val cols = prefs.columns
            val delta = when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -cols
                KeyEvent.KEYCODE_DPAD_DOWN -> cols
                else -> 0
            }
            val done = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK
            if (delta != 0 || done) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (done) finishMove() else moveBy(delta)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menu.isOpen) { menu.close(); return true }
            val v = grid.focusedChild
            val pos = if (v != null) grid.getChildAdapterPosition(v) else RecyclerView.NO_POSITION
            if (pos != RecyclerView.NO_POSITION) onAppLongClick(pos) else showSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ================================================================== settings

    private fun onOff(b: Boolean) = if (b) T.s("on", "uključeno", "an") else T.s("off", "isključeno", "aus")

    private fun hasSecure() =
        checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun showSettings() {
        val langName = T.languages.firstOrNull { it.first == T.lang }?.second ?: "English"
        menu.show(T.s("Settings", "Postavke", "Einstellungen"), R.drawable.lc_settings, listOf(
            GlassMenu.Item(T.s("Android settings of the box", "Android postavke boxa", "Android-Einstellungen der Box"), R.drawable.lc_settings) {
                safeStart(Intent(Settings.ACTION_SETTINGS))
            },
            GlassMenu.Item(T.s("Language", "Jezik", "Sprache"), R.drawable.lc_languages, value = langName) { languageMenu() },
            GlassMenu.Item(T.s("Profiles and Google account", "Profili i Google račun", "Profile und Google-Konto"), R.drawable.lc_user,
                value = Profiles.name(this, prefs.profile)) { profileMenu() },
            GlassMenu.Item(T.s("Wallpaper", "Pozadina", "Hintergrund"), R.drawable.lc_wallpaper) { wallpaperMenu() },
            GlassMenu.Item(T.s("Look", "Izgled", "Aussehen"), R.drawable.lc_layers) { lookMenu() },
            GlassMenu.Item(T.s("Status bar", "Statusna traka", "Statusleiste"), R.drawable.lc_bell_ring) { statusMenu() },
            GlassMenu.Item(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock) { clockMenu() },
            GlassMenu.Item(T.s("While watching (corner)", "Dok gledaš (u ćošku)", "Beim Schauen (Ecke)"), R.drawable.lc_tv) { overlayMenu() },
            GlassMenu.Item(T.s("Weather city", "Grad za vrijeme", "Wetter-Stadt"), R.drawable.lc_map_pin, value = prefs.city) { setCity() },
            GlassMenu.Item(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell) { showNotifications() },
            GlassMenu.Item(T.s("Default launcher (replace current)", "Zadani launcher (zamijeni trenutni)", "Standard-Launcher (aktuellen ersetzen)"), R.drawable.lc_house) { defaultLauncherMenu() },
            GlassMenu.Item(T.s("Screensaver and idle", "Screensaver i mirovanje", "Bildschirmschoner und Leerlauf"), R.drawable.lc_monitor) { screensaverMenu() },
            GlassMenu.Item(T.s("Power", "Napajanje", "Energie"), R.drawable.lc_power) { powerMenu() },
            GlassMenu.Item("Private DNS", R.drawable.lc_shield_check, value = dnsLabel()) { dnsMenu() },
            GlassMenu.Item(T.s("Auto-start apps", "Auto-start aplikacije", "Autostart-Apps"), R.drawable.lc_rocket) { autostartMenu() },
            GlassMenu.Item(T.s("Backup and restore", "Backup i vraćanje", "Sichern und wiederherstellen"), R.drawable.lc_archive) { backupMenu() },
            GlassMenu.Item(T.s("Update", "Ažuriranje", "Update"), R.drawable.lc_refresh_cw, value = "v" + Updater.currentName(this)) { updateMenu() },
            GlassMenu.Item(T.s("File browser", "File browser", "Dateimanager"), R.drawable.lc_folder) {
                startActivity(Intent(this, FileBrowserActivity::class.java))
            },
        ))
    }

    private fun languageMenu() {
        menu.show("Jezik · Language · Sprache", R.drawable.lc_languages, T.languages.map { (code, name, flagRes) ->
            GlassMenu.Item(name, flagRes, checked = code == T.lang, flag = true) {
                prefs.lang = code
                T.init(code)
                applyLook()
                reloadApps()
            }
        })
    }

    private fun choose(title: String, icon: Int, options: List<Pair<String, Int>>, current: Int, set: (Int) -> Unit) {
        menu.show(title, icon, options.map { (name, v) ->
            GlassMenu.Item(name, checked = v == current) { set(v) }
        })
    }

    private fun wallpaperMenu() {
        menu.show(T.s("Wallpaper", "Pozadina", "Hintergrund"), R.drawable.lc_wallpaper, listOf(
            GlassMenu.Item(T.s("Choose a picture", "Izaberi sliku", "Bild auswählen"), R.drawable.lc_image) { pickImage { setWallpaper(it) } },
            GlassMenu.Item(T.s("From the internet (URL)", "S interneta (link)", "Aus dem Internet (URL)"), R.drawable.lc_link) {
                askText(T.s("Wallpaper image URL", "Link slike za pozadinu", "Bild-URL für den Hintergrund"), "https://") { url ->
                    download(url, wallpaperFile) { applyLook() }
                }
            },
            GlassMenu.Item(T.s("Samurai (default)", "Samuraj (zadana)", "Samurai (Standard)"), R.drawable.lc_rotate_ccw) {
                wallpaperFile.delete(); applyLook()
            },
            GlassMenu.Item(T.s("Blur wallpaper", "Zamuti pozadinu", "Hintergrund weichzeichnen"), R.drawable.lc_eye,
                checked = prefs.blurWallpaper, toggle = true) { prefs.blurWallpaper = it.checked; loadWallpaper() },
            GlassMenu.Item(T.s("Darken", "Zatamnjenje", "Abdunkeln"), R.drawable.lc_sun_dim, value = "${prefs.dim}%") {
                choose(T.s("Darken", "Zatamnjenje", "Abdunkeln"), R.drawable.lc_sun_dim,
                    listOf("0%" to 0, "10%" to 10, "25%" to 25, "40%" to 40, "55%" to 55), prefs.dim) {
                    prefs.dim = it; applyLook()
                }
            },
        ))
    }

    private fun lookMenu() {
        menu.show(T.s("Look", "Izgled", "Aussehen"), R.drawable.lc_layers, listOf(
            GlassMenu.Item(T.s("Glass", "Staklo", "Glas"), R.drawable.lc_layers, value = "${prefs.glassAlpha}%") {
                choose(T.s("Glass", "Staklo", "Glas"), R.drawable.lc_layers, listOf(
                    T.s("Crystal clear", "Kristalno providno", "Kristallklar") to 4,
                    T.s("Clear", "Providno", "Klar") to 10,
                    T.s("Frosted", "Mat", "Milchglas") to 18,
                    T.s("Milky", "Mliječno", "Milchig") to 28), prefs.glassAlpha) {
                    prefs.glassAlpha = it; applyLook()
                }
            },
            GlassMenu.Item(T.s("Icon style", "Stil ikona", "Symbolstil"), R.drawable.lc_image) {
                choose(T.s("Icon style", "Stil ikona", "Symbolstil"), R.drawable.lc_image, listOf(
                    T.s("Original", "Original", "Original") to 0,
                    T.s("Transparent (no background)", "Providne (bez pozadine)", "Transparent (ohne Hintergrund)") to 1,
                    T.s("White glass", "Bijelo staklo", "Weißes Glas") to 2), prefs.iconStyle) {
                    prefs.iconStyle = it; reloadApps()
                }
            },
            GlassMenu.Item(T.s("Columns", "Broj kolona", "Spalten"), R.drawable.lc_columns_3, value = prefs.columns.toString()) {
                choose(T.s("Columns", "Broj kolona", "Spalten"), R.drawable.lc_columns_3,
                    (5..9).map { it.toString() to it }, prefs.columns) { prefs.columns = it; applyLook() }
            },
            GlassMenu.Item(T.s("Apps position", "Pozicija aplikacija", "Position der Apps"), R.drawable.lc_layout_grid) {
                choose(T.s("Apps position", "Pozicija aplikacija", "Position der Apps"), R.drawable.lc_layout_grid, listOf(
                    T.s("Top", "Gore", "Oben") to 0, T.s("Middle", "Sredina", "Mitte") to 1, T.s("Bottom", "Dolje", "Unten") to 2),
                    prefs.gridPosition) { prefs.gridPosition = it; applyLook() }
            },
            GlassMenu.Item(T.s("App names", "Nazivi aplikacija", "App-Namen"), R.drawable.lc_type,
                checked = prefs.showLabels, toggle = true) { prefs.showLabels = it.checked; applyLook() },
        ))
    }

    private fun statusMenu() {
        fun t(text: String, icon: Int, get: Boolean, set: (Boolean) -> Unit) =
            GlassMenu.Item(text, icon, checked = get, toggle = true) { set(it.checked); applyLook() }
        menu.show(T.s("Status bar", "Statusna traka", "Statusleiste"), R.drawable.lc_bell_ring, listOf(
            t(T.s("Show status bar", "Prikaži statusnu traku", "Statusleiste anzeigen"), R.drawable.lc_eye, prefs.showStatusBar) { prefs.showStatusBar = it },
            t(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock, prefs.showClock) { prefs.showClock = it },
            t(T.s("Date", "Datum", "Datum"), R.drawable.lc_type, prefs.showDate) { prefs.showDate = it },
            t(T.s("Weather", "Vrijeme", "Wetter"), R.drawable.lc_cloud_sun, prefs.showWeather) { prefs.showWeather = it; lastWeather = 0 },
            t("Wi-Fi / LAN", R.drawable.lc_wifi, prefs.showNetwork) { prefs.showNetwork = it },
            t("VPN", R.drawable.lc_key_round, prefs.showVpn) { prefs.showVpn = it },
            t(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell, prefs.showNotifs) { prefs.showNotifs = it },
            t("CPU %", R.drawable.lc_cpu, prefs.showCpu) { prefs.showCpu = it },
            t("RAM %", R.drawable.lc_memory_stick, prefs.showRam) { prefs.showRam = it },
        ))
    }

    private fun setCity() {
        askText(T.s("Weather city", "Grad za vrijeme", "Wetter-Stadt"), prefs.city) { name ->
            Thread {
                val r = Weather.findCity(name)
                handler.post {
                    if (r == null) toast(T.s("City not found", "Grad nije pronađen", "Stadt nicht gefunden"))
                    else {
                        prefs.city = r.first; prefs.lat = r.second; prefs.lon = r.third
                        toast(r.first)
                        lastWeather = 0
                        updateWeather()
                    }
                }
            }.start()
        }
    }

    private fun showNotifications() {
        if (!NotifListener.connected) { notificationAccess(); return }
        val list = NotifListener.current()
        if (list.isEmpty()) { toast(T.s("No notifications", "Nema obavještenja", "Keine Benachrichtigungen")); return }
        menu.show(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell, list.map { sbn ->
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE) ?: sbn.packageName
            val txt = ex.getCharSequence(Notification.EXTRA_TEXT)
            GlassMenu.Item(if (txt != null) "$title – $txt" else title.toString(), R.drawable.lc_bell) {
                runCatching { sbn.notification.contentIntent?.send() }
            }
        })
    }

    private fun notificationAccess() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            menu.message(T.s("Notification access", "Pristup obavještenjima", "Benachrichtigungszugriff"), R.drawable.lc_bell, T.s(
                "This box has no settings page for it. Run once from a computer:",
                "Ovaj box nema stranicu za to u postavkama. Pokreni jednom s računara:",
                "Diese Box hat dafür keine Einstellungsseite. Einmal am Computer ausführen:") +
                "\n\nadb shell cmd notification allow_listener $packageName/$packageName.NotifListener")
        }
    }

    // ------------------------------------------------------------------ default launcher

    private fun adbGrant() = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

    private fun defaultLauncherMenu() {
        menu.show(T.s("Default launcher", "Zadani launcher", "Standard-Launcher"), R.drawable.lc_house, listOf(
            GlassMenu.Item(T.s("Choose home app (Android)", "Izaberi početnu aplikaciju (Android)", "Start-App wählen (Android)"), R.drawable.lc_house) {
                try {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                } catch (e: Exception) {
                    menu.message(T.s("Default launcher", "Zadani launcher", "Standard-Launcher"), R.drawable.lc_house, T.s(
                        "This box doesn't offer a choice. Use \"Home button opens Glass Launcher\".",
                        "Ovaj box ne nudi izbor. Koristi \"Dugme Home otvara Glass Launcher\".",
                        "Diese Box bietet keine Auswahl. Nutze \"Home-Taste öffnet Glass Launcher\"."))
                }
            },
            GlassMenu.Item(T.s("Home button opens Glass Launcher", "Dugme Home otvara Glass Launcher", "Home-Taste öffnet Glass Launcher"),
                R.drawable.lc_house, value = onOff(HomeService.isEnabled(this) && prefs.homeRedirect)) {
                if (HomeService.isEnabled(this) && prefs.homeRedirect) {
                    prefs.homeRedirect = false
                    toast(T.s("Home button: Android home screen", "Dugme Home: Android početni ekran", "Home-Taste: Android-Startbildschirm"))
                } else enableHomeButton()
            },
            GlassMenu.Item(T.s("Instructions (computer / ADB)", "Upute (računar / ADB)", "Anleitung (Computer / ADB)"), R.drawable.lc_info) { adbHelp() },
        ))
    }

    private fun enableHomeButton() {
        prefs.homeRedirect = true
        enableService()
    }

    /** Turns on the accessibility service (Home button + corner icon while watching). */
    private fun enableService() {
        if (HomeService.isEnabled(this)) {
            toast(T.s("Already on", "Već je uključeno", "Bereits an")); return
        }
        if (hasSecure() && HomeService.enableWithSecureSettings(this)) {
            toast(T.s("Switched on", "Uključeno", "Eingeschaltet"))
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast(T.s("Turn on \"Glass Launcher\" here", "Ovdje uključi \"Glass Launcher\"", "Hier \"Glass Launcher\" einschalten"))
        } catch (e: Exception) {
            adbHelp()
        }
    }

    private fun adbHelp() {
        menu.message("ADB", R.drawable.lc_info, T.s(
            "On a computer in the same network (enable Developer options → Network/USB debugging on the box):",
            "Na računaru u istoj mreži (na boxu uključi Opcije za programere → mrežno/USB otklanjanje grešaka):",
            "Am Computer im selben Netzwerk (auf der Box Entwickleroptionen → Netzwerk/USB-Debugging aktivieren):") +
            "\n\nadb connect <IP>\n${adbGrant()}\n\n" +
            T.s("After this, Glass Launcher can turn on the Home button and Private DNS by itself.\n\nTo switch off the Google home screen completely:",
                "Poslije toga Glass Launcher sam uključuje dugme Home i Private DNS.\n\nDa potpuno isključiš Google početni ekran:",
                "Danach kann Glass Launcher die Home-Taste und Private DNS selbst einschalten.\n\nUm den Google-Startbildschirm ganz abzuschalten:") +
            "\nadb shell pm disable-user --user 0 com.google.android.tvlauncher\n" +
            T.s("(undo: adb shell pm enable com.google.android.tvlauncher)",
                "(vraćanje: adb shell pm enable com.google.android.tvlauncher)",
                "(rückgängig: adb shell pm enable com.google.android.tvlauncher)"))
    }

    // ------------------------------------------------------------------ Private DNS

    private fun dnsMode(): String? = runCatching { Settings.Global.getString(contentResolver, "private_dns_mode") }.getOrNull()
    private fun dnsHost(): String? = runCatching { Settings.Global.getString(contentResolver, "private_dns_specifier") }.getOrNull()

    private fun dnsLabel(): String = when (dnsMode()) {
        "hostname" -> dnsHost() ?: "?"
        "opportunistic" -> T.s("automatic", "automatski", "automatisch")
        "off" -> onOff(false)
        else -> "–"
    }

    private fun dnsMenu() {
        if (Build.VERSION.SDK_INT < 28) {
            menu.message("Private DNS", R.drawable.lc_shield_check, T.s(
                "Private DNS needs Android 9 or newer.", "Private DNS traži Android 9 ili noviji.", "Private DNS braucht Android 9 oder neuer."))
            return
        }
        if (!hasSecure()) {
            menu.message("Private DNS", R.drawable.lc_shield_check, T.s(
                "Android lets apps change Private DNS only after this one-time command from a computer (ADB):",
                "Android dozvoljava promjenu Private DNS-a tek nakon ove jednokratne komande s računara (ADB):",
                "Android erlaubt das Ändern von Private DNS erst nach diesem einmaligen Befehl am Computer (ADB):") +
                "\n\n${adbGrant()}\n\n" + T.s("Then open this menu again.", "Onda ponovo otvori ovaj meni.", "Dann dieses Menü erneut öffnen."))
            return
        }
        val mode = dnsMode()
        val host = dnsHost()
        fun hostItem(name: String, h: String) =
            GlassMenu.Item(name, R.drawable.lc_shield_check, checked = mode == "hostname" && host == h, value = h) { setDns("hostname", h) }
        menu.show("Private DNS", R.drawable.lc_shield_check, listOf(
            GlassMenu.Item(T.s("Off", "Isključeno", "Aus"), R.drawable.lc_x, checked = mode == "off") { setDns("off", null) },
            GlassMenu.Item(T.s("Automatic", "Automatski", "Automatisch"), R.drawable.lc_refresh_cw, checked = mode == "opportunistic") { setDns("opportunistic", null) },
            hostItem(T.s("AdGuard (blocks ads)", "AdGuard (blokira reklame)", "AdGuard (blockiert Werbung)"), "dns.adguard-dns.com"),
            hostItem("Cloudflare", "one.one.one.one"),
            hostItem("Google", "dns.google"),
            hostItem("Quad9", "dns.quad9.net"),
            GlassMenu.Item(T.s("Own server…", "Vlastiti server…", "Eigener Server…"), R.drawable.lc_pencil) {
                askText("Private DNS", host ?: "") { h -> if (h.isNotBlank()) setDns("hostname", h) }
            },
        ))
    }

    private fun setDns(mode: String, host: String?) {
        val ok = runCatching {
            if (host != null) Settings.Global.putString(contentResolver, "private_dns_specifier", host)
            Settings.Global.putString(contentResolver, "private_dns_mode", mode)
        }.getOrDefault(false)
        toast(if (ok) "Private DNS: " + dnsLabel() else T.s("Couldn't change Private DNS", "Promjena Private DNS-a nije uspjela", "Private DNS konnte nicht geändert werden"))
    }

    // ------------------------------------------------------------------ auto-start

    private fun autostartMenu() {
        Thread {
            val list = AppLoader.autostartApps(this)
            handler.post {
                val title = T.s("Auto-start apps", "Auto-start aplikacije", "Autostart-Apps")
                if (list.isEmpty()) {
                    menu.message(title, R.drawable.lc_rocket, T.s(
                        "No installed apps start themselves with the box.",
                        "Nijedna instalirana aplikacija se ne pokreće sama s boxom.",
                        "Keine installierte App startet von selbst mit der Box."))
                    return@post
                }
                val blocked = prefs.blockedAutostart
                val items = list.map { (pkg, label) ->
                    GlassMenu.Item(label, R.drawable.lc_rocket, checked = pkg !in blocked, toggle = true) { item ->
                        prefs.blockedAutostart = if (item.checked) prefs.blockedAutostart - pkg else prefs.blockedAutostart + pkg
                    }
                } + GlassMenu.Item(T.s("Block permanently (ADB)…", "Trajno blokiraj (ADB)…", "Dauerhaft blockieren (ADB)…"), R.drawable.lc_info) {
                    val cmds = prefs.blockedAutostart.joinToString("\n") { "adb shell cmd appops set $it RUN_IN_BACKGROUND ignore" }
                    menu.message("ADB", R.drawable.lc_info, if (cmds.isEmpty()) T.s(
                        "First switch off some apps in the list.", "Prvo isključi neke aplikacije u listi.", "Zuerst einige Apps in der Liste ausschalten.")
                    else T.s("Run these once from a computer:", "Pokreni ovo jednom s računara:", "Einmal am Computer ausführen:") + "\n\n" + cmds)
                }
                menu.show(title, R.drawable.lc_rocket, items, T.s(
                    "Switched off = Glass Launcher stops the app in the background after the box starts.",
                    "Isključeno = Glass Launcher zaustavlja aplikaciju u pozadini nakon paljenja boxa.",
                    "Aus = Glass Launcher stoppt die App im Hintergrund nach dem Start der Box."))
            }
        }.start()
    }

    // ------------------------------------------------------------------ profiles & Google account

    private fun profileMenu() {
        val cur = prefs.profile
        val items = mutableListOf<GlassMenu.Item>()
        for ((id, name) in Profiles.list(this)) {
            items.add(GlassMenu.Item(name, R.drawable.lc_user, checked = id == cur) {
                if (id != cur) { Profiles.setCurrent(this, id); recreate() }
            })
        }
        items.add(GlassMenu.Item(T.s("New profile…", "Novi profil…", "Neues Profil…"), R.drawable.lc_user_plus) {
            askText(T.s("Profile name", "Ime profila", "Profilname"), "") { n ->
                if (n.isNotBlank()) { Profiles.setCurrent(this, Profiles.add(this, n)); recreate() }
            }
        })
        items.add(GlassMenu.Item(T.s("Rename this profile", "Preimenuj ovaj profil", "Dieses Profil umbenennen"), R.drawable.lc_pencil) {
            askText(T.s("Profile name", "Ime profila", "Profilname"), Profiles.name(this, cur)) { n ->
                if (n.isNotBlank()) { Profiles.rename(this, cur, n); applyLook() }
            }
        })
        if (cur != Profiles.MAIN) {
            items.add(GlassMenu.Item(T.s("Delete this profile", "Obriši ovaj profil", "Dieses Profil löschen"), R.drawable.lc_trash_2) {
                Profiles.delete(this, cur); recreate()
            })
        }
        items.add(GlassMenu.Item(T.s("Google account: sign in / out / switch", "Google račun: prijava / odjava / promjena",
            "Google-Konto: an-/abmelden / wechseln"), R.drawable.lc_log_in) { openAccounts() })
        items.add(GlassMenu.Item(T.s("Show which Google account is used", "Prikaži koji Google račun je prijavljen",
            "Angemeldetes Google-Konto anzeigen"), R.drawable.lc_info) { chooseGoogleAccount() })
        val acc = Profiles.savedAccount(this)
        menu.show(T.s("Profile", "Profil", "Profil") + ": " + Profiles.name(this, cur), R.drawable.lc_user, items,
            if (acc != null) "Google: $acc" else null)
    }

    private fun openAccounts() {
        val tries = listOf(
            Intent(Settings.ACTION_SYNC_SETTINGS),
            Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (e: Exception) { }
        }
    }

    private fun chooseGoogleAccount() {
        val i = if (Build.VERSION.SDK_INT >= 26) {
            AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        } else {
            @Suppress("DEPRECATION")
            AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), false, null, null, null, null)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_ACCOUNT)
        } catch (e: Exception) {
            toast(T.s("Not available on this device", "Nije dostupno na ovom uređaju", "Auf diesem Gerät nicht verfügbar"))
        }
    }

    // ------------------------------------------------------------------ clock & corner overlay

    private fun clockMenu() {
        val fmt = listOf(T.s("Like Android", "Kao Android", "Wie Android") to 0, "24h" to 1, "12h (AM/PM)" to 2)
        menu.show(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock, listOf(
            GlassMenu.Item(T.s("Format", "Format", "Format"), R.drawable.lc_clock, value = fmt.first { it.second == prefs.clockFormat }.first) {
                choose(T.s("Format", "Format", "Format"), R.drawable.lc_clock, fmt, prefs.clockFormat) { prefs.clockFormat = it; updateClock() }
            },
            GlassMenu.Item(T.s("Show seconds", "Prikaži sekunde", "Sekunden anzeigen"), R.drawable.lc_clock,
                checked = prefs.clockSeconds, toggle = true) { prefs.clockSeconds = it.checked; updateClock() },
            GlassMenu.Item(T.s("Clock always visible (also in films/apps)", "Sat uvijek vidljiv (i u filmu/aplikacijama)",
                "Uhr immer sichtbar (auch in Filmen/Apps)"), R.drawable.lc_tv, checked = prefs.overlayClock, toggle = true) {
                prefs.overlayClock = it.checked
                if (it.checked && !HomeService.isEnabled(this)) serviceNeeded()
            },
            GlassMenu.Item(T.s("Time zone automatically", "Vremenska zona automatski", "Zeitzone automatisch"), R.drawable.lc_globe,
                value = onOff(autoZone())) { toggleAutoZone() },
        ))
    }

    private fun autoZone(): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME_ZONE) == 1 }.getOrDefault(false)

    private fun toggleAutoZone() {
        val ok = hasSecure() && runCatching {
            Settings.Global.putInt(contentResolver, Settings.Global.AUTO_TIME_ZONE, if (autoZone()) 0 else 1)
            Settings.Global.putInt(contentResolver, Settings.Global.AUTO_TIME, 1)
        }.isSuccess
        if (ok) { toast(T.s("Automatic time zone: ", "Automatska zona: ", "Automatische Zeitzone: ") + onOff(autoZone())); updateClock() }
        else safeStart(Intent(Settings.ACTION_DATE_SETTINGS))
    }

    private fun overlayMenu() {
        val on = HomeService.isEnabled(this)
        menu.show(T.s("While watching (corner)", "Dok gledaš (u ćošku)", "Beim Schauen (Ecke)"), R.drawable.lc_tv, listOf(
            GlassMenu.Item(T.s("VPN key in the corner", "VPN ključ u ćošku", "VPN-Schlüssel in der Ecke"), R.drawable.lc_key_round,
                checked = prefs.overlayVpn, toggle = true) {
                prefs.overlayVpn = it.checked
                if (it.checked && !HomeService.isEnabled(this)) serviceNeeded()
            },
            GlassMenu.Item(T.s("Clock in the corner", "Sat u ćošku", "Uhr in der Ecke"), R.drawable.lc_clock,
                checked = prefs.overlayClock, toggle = true) {
                prefs.overlayClock = it.checked
                if (it.checked && !HomeService.isEnabled(this)) serviceNeeded()
            },
            GlassMenu.Item(T.s("Switch on the helper service", "Uključi pomoćni servis", "Hilfsdienst einschalten"), R.drawable.lc_shield_check,
                value = onOff(on)) { enableService() },
        ), T.s("On the home screen the full status bar is shown. In films and other apps only these small icons stay in the top-right corner.",
            "Na početnom ekranu vidi se cijela statusna traka. U filmovima i drugim aplikacijama u gornjem desnom ćošku ostaju samo ove male ikone.",
            "Auf dem Startbildschirm siehst du die ganze Statusleiste. In Filmen und anderen Apps bleiben nur diese kleinen Symbole oben rechts."))
    }

    private fun serviceNeeded() {
        toast(T.s("Needs the helper service – switch it on below", "Treba pomoćni servis – uključi ga ispod",
            "Braucht den Hilfsdienst – unten einschalten"))
    }

    // ------------------------------------------------------------------ backup

    private fun backupMenu() {
        menu.show(T.s("Backup and restore", "Backup i vraćanje", "Sichern und wiederherstellen"), R.drawable.lc_archive, listOf(
            GlassMenu.Item(T.s("Make backup now", "Napravi backup sada", "Jetzt sichern"), R.drawable.lc_download) {
                withWriteStorage {
                    Thread {
                        val r = runCatching { Backup.create(this) }
                        handler.post {
                            r.onSuccess { f -> menu.message("Backup", R.drawable.lc_check, T.s("Saved:", "Sačuvano:", "Gespeichert:") + "\n" + f.absolutePath +
                                "\n\n" + T.s("Copy this file to a USB stick or your phone to keep it safe.",
                                "Kopiraj ovaj fajl na USB ili telefon da bude siguran.",
                                "Kopiere die Datei auf einen USB-Stick oder dein Handy.")) }
                            r.onFailure { toast(T.s("Backup failed", "Backup nije uspio", "Sicherung fehlgeschlagen")) }
                        }
                    }.start()
                }
            },
            GlassMenu.Item(T.s("Restore from backup", "Vrati iz backupa", "Aus Sicherung wiederherstellen"), R.drawable.lc_rotate_ccw) {
                pendingPick = { f -> restoreBackup(f) }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, FileBrowserActivity::class.java)
                    .putExtra(FileBrowserActivity.EXTRA_PICK, "backup")
                    .putExtra(FileBrowserActivity.EXTRA_START, Backup.folder().absolutePath), REQ_BACKUP)
            },
        ), T.s("Saves all profiles, apps, names, own icons, wallpapers and settings. Google logins and passwords are kept by Android itself (Google backup), no app can save them.",
            "Čuva sve profile, aplikacije, nazive, vlastite ikone, pozadine i postavke. Google prijave i lozinke čuva sam Android (Google backup), nijedna aplikacija ih ne može sačuvati.",
            "Sichert alle Profile, Apps, Namen, eigene Symbole, Hintergründe und Einstellungen. Google-Anmeldungen und Passwörter sichert Android selbst (Google-Backup), keine App kann das."))
    }

    private fun restoreBackup(f: File) {
        Thread {
            val r = runCatching { Backup.restore(this, f) }
            handler.post {
                if (r.isSuccess) { toast(T.s("Restored", "Vraćeno", "Wiederhergestellt")); recreate() }
                else toast(T.s("This is not a valid backup", "Ovo nije ispravan backup", "Keine gültige Sicherung"))
            }
        }.start()
    }

    private fun withWriteStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) { action(); return }
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingPermission = action; requestPermissions(arrayOf(perm), REQ_PERM) }
    }

    // ------------------------------------------------------------------ power, screensaver, idle

    private fun powerMenu() {
        menu.show(T.s("Power", "Napajanje", "Energie"), R.drawable.lc_power, listOf(
            GlassMenu.Item(T.s("Sleep (standby)", "Spavanje (standby)", "Ruhemodus (Standby)"), R.drawable.lc_moon) { goSleep() },
            GlassMenu.Item(T.s("Restart / turn off…", "Restart / ugasi…", "Neustart / ausschalten…"), R.drawable.lc_power) {
                if (!HomeService.powerMenu()) needServiceFor()
            },
            GlassMenu.Item(T.s("Start screensaver", "Pokreni screensaver", "Bildschirmschoner starten"), R.drawable.lc_monitor) { startSaver() },
        ))
    }

    private fun goSleep() {
        if (!HomeService.sleep()) needServiceFor()
    }

    private fun needServiceFor() {
        menu.show(T.s("Power", "Napajanje", "Energie"), R.drawable.lc_power, listOf(
            GlassMenu.Item(T.s("Switch on the helper service", "Uključi pomoćni servis", "Hilfsdienst einschalten"), R.drawable.lc_shield_check) { enableService() },
        ), T.s("Android lets apps put the box to sleep or open the power menu only through the Glass Launcher helper service (Accessibility). Sleep needs Android 9 or newer.",
            "Android dozvoljava gašenje i spavanje boxa samo preko pomoćnog servisa Glass Launchera (Pristupačnost). Spavanje traži Android 9 ili noviji.",
            "Android erlaubt Ruhemodus und Power-Menü nur über den Glass-Launcher-Hilfsdienst (Bedienungshilfen). Ruhemodus braucht Android 9 oder neuer."))
    }

    private fun startSaver() {
        startActivity(Intent(this, ScreensaverActivity::class.java))
    }

    private fun screensaverMenu() {
        val actions = listOf(
            T.s("Nothing (Android decides)", "Ništa (odlučuje Android)", "Nichts (Android entscheidet)") to 0,
            T.s("Glass screensaver", "Glass screensaver", "Glass-Bildschirmschoner") to 1,
            T.s("Sleep (standby)", "Spavanje (standby)", "Ruhemodus (Standby)") to 2)
        menu.show(T.s("Screensaver and idle", "Screensaver i mirovanje", "Bildschirmschoner und Leerlauf"), R.drawable.lc_monitor, listOf(
            GlassMenu.Item(T.s("When the home screen is idle", "Kad početni ekran miruje", "Wenn der Startbildschirm ruht"), R.drawable.lc_moon,
                value = actions.first { it.second == prefs.idleAction }.first) {
                choose(T.s("When idle", "Kad miruje", "Im Leerlauf"), R.drawable.lc_moon, actions, prefs.idleAction) {
                    prefs.idleAction = it
                    if (it == 2 && !HomeService.isEnabled(this)) needServiceFor()
                }
            },
            GlassMenu.Item(T.s("After", "Nakon", "Nach"), R.drawable.lc_clock, value = "${prefs.idleMinutes} min") {
                choose(T.s("After", "Nakon", "Nach"), R.drawable.lc_clock,
                    listOf(2, 5, 10, 15, 30, 60, 120).map { "$it min" to it }, prefs.idleMinutes) { prefs.idleMinutes = it }
            },
            GlassMenu.Item(T.s("Start screensaver now", "Pokreni screensaver sada", "Bildschirmschoner jetzt starten"), R.drawable.lc_monitor) { startSaver() },
            GlassMenu.Item(T.s("Android screensaver settings", "Android postavke screensavera", "Android-Bildschirmschoner-Einstellungen"), R.drawable.lc_settings) {
                safeStart(Intent(Settings.ACTION_DREAM_SETTINGS))
            },
        ), T.s("Tip: in the Android screensaver settings you can also choose \"Glass Launcher\" – then the samurai clock shows in every app.",
            "Savjet: u Android postavkama screensavera možeš izabrati i \"Glass Launcher\" – tada se samuraj sat prikazuje u svakoj aplikaciji.",
            "Tipp: In den Android-Einstellungen kannst du auch \"Glass Launcher\" als Bildschirmschoner wählen."))
    }

    private fun checkIdle() {
        val act = prefs.idleAction
        if (act == 0 || menu.isOpen || movingPos >= 0 || !hasWindowFocus()) return
        if (SystemClock.elapsedRealtime() - lastInput < prefs.idleMinutes * 60_000L) return
        lastInput = SystemClock.elapsedRealtime()
        if (act == 1) startSaver() else goSleep()
    }

    // ------------------------------------------------------------------ update

    private fun updateMenu() {
        val url = prefs.updateUrl
        menu.show(T.s("Update", "Ažuriranje", "Update"), R.drawable.lc_refresh_cw, listOf(
            GlassMenu.Item(T.s("Check now", "Provjeri sada", "Jetzt prüfen"), R.drawable.lc_refresh_cw) { checkUpdate(manual = true) },
            GlassMenu.Item(T.s("Update address (URL)", "Adresa za update (link)", "Update-Adresse (URL)"), R.drawable.lc_link,
                value = if (url.isBlank()) "–" else url.substringAfter("://").take(28)) {
                askText(T.s("Link to update.json", "Link do update.json", "Link zu update.json"), url.ifBlank { "https://" }) {
                    prefs.updateUrl = it.trim()
                }
            },
        ), T.s("Installed version: ", "Instalirana verzija: ", "Installierte Version: ") + Updater.currentName(this))
    }

    private fun checkUpdate(manual: Boolean) {
        val url = prefs.updateUrl
        if (url.isBlank()) {
            if (manual) toast(T.s("Set the update address first", "Prvo upiši adresu za update", "Zuerst die Update-Adresse eintragen"))
            return
        }
        if (manual) toast(T.s("Checking…", "Provjeravam…", "Prüfe…"))
        Thread {
            val info = Updater.check(this, url)
            handler.post {
                if (info == null) {
                    if (manual) toast(T.s("You have the latest version", "Imaš najnoviju verziju", "Du hast die neueste Version"))
                    return@post
                }
                menu.show(T.s("New version ", "Nova verzija ", "Neue Version ") + info.name, R.drawable.lc_download, listOf(
                    GlassMenu.Item(T.s("Install now", "Instaliraj sada", "Jetzt installieren"), R.drawable.lc_download) { installUpdate(info) },
                    GlassMenu.Item(T.s("Later", "Kasnije", "Später"), R.drawable.lc_x),
                ), info.notes.ifBlank { null })
            }
        }.start()
    }

    private fun installUpdate(info: Updater.Info) {
        toast(T.s("Downloading…", "Preuzimam…", "Lade herunter…"))
        Thread {
            try {
                val f = Updater.download(this, info)
                handler.post { Updater.install(this, f) }
            } catch (e: Exception) {
                handler.post { toast(T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen")) }
            }
        }.start()
    }

    // ================================================================== pictures

    private fun setWallpaper(f: File) {
        try { f.copyTo(wallpaperFile, true); applyLook() }
        catch (e: Exception) { toast(T.s("Can't read this file", "Ne mogu pročitati ovaj fajl", "Datei nicht lesbar")) }
    }

    private fun pickImage(onPicked: (File) -> Unit) {
        menu.show(T.s("Choose a picture", "Izaberi sliku", "Bild auswählen"), R.drawable.lc_image, listOf(
            GlassMenu.Item(T.s("Pictures on this box (newest first)", "Slike na boxu (najnovije prve)", "Bilder auf der Box (neueste zuerst)"), R.drawable.lc_file_image) {
                withStorage { listDeviceImages(onPicked) }
            },
            GlassMenu.Item(T.s("File browser", "File browser", "Dateimanager"), R.drawable.lc_folder) {
                pendingPick = onPicked
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, FileBrowserActivity::class.java)
                    .putExtra(FileBrowserActivity.EXTRA_PICK, "image"), REQ_FILES)
            },
            GlassMenu.Item(T.s("System file picker", "Sistemski izbor fajla", "System-Dateiauswahl"), R.drawable.lc_file) { systemPicker(onPicked) },
        ))
    }

    private fun listDeviceImages(onPicked: (File) -> Unit) {
        toast(T.s("Searching pictures…", "Tražim slike…", "Suche Bilder…"))
        Thread {
            val files = runCatching { ImageScanner.scan() }.getOrDefault(emptyList()).take(300)
            handler.post {
                if (files.isEmpty()) { toast(T.s("No pictures found", "Nema pronađenih slika", "Keine Bilder gefunden")); return@post }
                menu.show(T.s("Pictures", "Slike", "Bilder"), R.drawable.lc_image, files.map { f ->
                    GlassMenu.Item(f.name, R.drawable.lc_file_image, value = f.parentFile?.name) {
                        try { onPicked(f) } catch (e: Exception) { toast(T.s("Can't read this file", "Ne mogu pročitati ovaj fajl", "Datei nicht lesbar")) }
                    }
                })
            }
        }.start()
    }

    private fun systemPicker(onPicked: (File) -> Unit) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            pendingPick = onPicked
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            pendingPick = null
            toast(T.s("No system file picker – use the file browser", "Nema sistemskog izbora – koristi file browser", "Keine System-Auswahl – Dateimanager verwenden"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ACCOUNT) {
            val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (resultCode == RESULT_OK && name != null) { Profiles.saveAccount(this, name); toast(name) }
            return
        }
        val cb = pendingPick
        pendingPick = null
        if (resultCode != RESULT_OK || data == null || cb == null) return
        try {
            when (requestCode) {
                REQ_FILES, REQ_BACKUP -> data.getStringExtra(FileBrowserActivity.EXTRA_PATH)?.let { cb(File(it)) }
                REQ_PICK -> {
                    val uri = data.data ?: return
                    val tmp = File(cacheDir, "picked")
                    contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    cb(tmp)
                }
            }
        } catch (e: Exception) {
            toast(T.s("Can't read this file", "Ne mogu pročitati ovaj fajl", "Datei nicht lesbar"))
        }
    }

    private fun withStorage(action: () -> Unit) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingPermission = action; requestPermissions(arrayOf(perm), REQ_PERM) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERM -> {
                val a = pendingPermission
                pendingPermission = null
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) a?.invoke()
                else toast(T.s("Storage permission is needed", "Potrebna je dozvola za memoriju", "Speicherberechtigung wird benötigt"))
            }
            REQ_LOC -> updateStatus()
        }
    }

    private fun download(url: String, dest: File, done: () -> Unit) {
        toast(T.s("Downloading…", "Preuzimam…", "Lade herunter…"))
        Thread {
            try {
                val tmp = File(cacheDir, "dl")
                Http.download(url, tmp)
                if (Bitmaps.decodeScaled(tmp, 64, 64) == null) throw IllegalStateException("not an image")
                tmp.copyTo(dest, true)
                handler.post { done() }
            } catch (e: Exception) {
                handler.post { toast(T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen")) }
            }
        }.start()
    }

    // ================================================================== helpers

    private fun askText(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton(T.s("Cancel", "Odustani", "Abbrechen"), null)
            .show()
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            toast(T.s("Not available on this device", "Nije dostupno na ovom uređaju", "Auf diesem Gerät nicht verfügbar"))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERM = 11
        private const val REQ_PICK = 12
        private const val REQ_FILES = 13
        private const val REQ_LOC = 14
        private const val REQ_ACCOUNT = 15
        private const val REQ_BACKUP = 16
    }
}
