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
import android.graphics.Typeface
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
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(FontScale.wrap(newBase))

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView
    private lateinit var dim: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var statusPill: GlassFrame
    private lateinit var dock: GlassFrame
    private lateinit var gridArea: FrameLayout
    private lateinit var profileBtn: GlassFrame
    private lateinit var settingsBtn: GlassFrame
    private lateinit var updateBtn: GlassFrame
    private lateinit var profileLetter: TextView
    private var tickCount = 0
    private var lastInput = SystemClock.elapsedRealtime()
    private lateinit var powerBtn: GlassFrame
    private lateinit var grid: RecyclerView
    private lateinit var hint: TextView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var rowManager: LinearLayoutManager
    private var cols = 1
    private lateinit var adapter: AppAdapter
    private lateinit var menu: GlassMenu

    private val apps = mutableListOf<AppEntry>()
    private val handler = Handler(Looper.getMainLooper())
    private var movingPos = -1
    private var lastPos = 0
    private var firstLoad = true
    private var lastWeather = 0L
    private var cpuMisses = 0
    @Volatile private var cpuValue: Int? = null
    @Volatile private var cpuBusy = false
    private var pendingPermission: (() -> Unit)? = null
    private var pendingPick: ((File) -> Unit)? = null

    private val wallpaperFile get() = File(filesDir, Profiles.wallpaperName(prefs.profile))

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            reloadApps()
            // uninstalled from Android's App info page: that page stays empty with only the app's name – close it
            if (i?.action == Intent.ACTION_PACKAGE_REMOVED && i.getBooleanExtra(Intent.EXTRA_REPLACING, false) == false && !HomeService.homeVisible) {
                handler.postDelayed({
                    runCatching {
                        // started through the helper service: Android lets it open LEAD from the background
                        val c: Context = HomeService.instance ?: this@MainActivity
                        c.startActivity(Intent(c, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }, 700)
            }
            if (i?.action != Intent.ACTION_PACKAGE_REMOVED) pluginUpdated(i?.data?.schemeSpecificPart)
            // SofaDrop just installed from "Add-ons" (quietly via Shizuku): make it the wallpaper
            val pkg = i?.data?.schemeSpecificPart
            val g = getSharedPreferences("glass_global", Context.MODE_PRIVATE)
            if (pkg == Extras.SOFADROP && i?.action != Intent.ACTION_PACKAGE_REMOVED && g.getBoolean("pendingSofa", false)) {
                g.edit().remove("pendingSofa").apply()
                handler.postDelayed({ useSofadrop() }, 2000)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            if (tickCount++ % 5 == 0) {
                updateStatus()
                if (System.currentTimeMillis() - lastWeather > prefs.weatherMin * 60 * 1000L) updateWeather()
                if (SystemClock.elapsedRealtime() < 3 * 60 * 1000L) Autostart.apply(this@MainActivity)
                if ((prefs.nightDim || prefs.nightSoft) && tickCount % 60 == 1) applyDim()
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
        dock = findViewById(R.id.dock)
        gridArea = findViewById(R.id.gridArea)
        hint = findViewById(R.id.hint)
        profileBtn = findViewById(R.id.profileBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        updateBtn = findViewById(R.id.updateBtn)
        powerBtn = findViewById(R.id.powerBtn)
        profileLetter = findViewById(R.id.profileLetter)
        // a brand-new install starts in Performance mode; Normal mode later offers the add-ons
        run {
            val g = getSharedPreferences("glass_global", Context.MODE_PRIVATE)
            if (!g.contains("installSeen")) {
                val fresh = getSharedPreferences(Profiles.prefsName(prefs.profile), Context.MODE_PRIVATE).all.isEmpty()
                if (fresh) {
                    prefs.perfMode = true
                    // a fresh LEAD: Light look on the LEAD wallpaper, with PRETORIAN IPTV, YouTube, Disney+ and Netflix
                    prefs.applyVisualLook(2)
                    prefs.pinned = DefaultApps.list(this)
                    // a fresh box: look for updates of LEAD and all apps right after the first start
                    handler.postDelayed({ firstUpdateCheck() }, 20_000)
                }
                g.edit().putBoolean("installSeen", true).apply()
            }
        }
        // TizenTube is LEAD's YouTube: put it on the home screen once (official YouTube off it, can be added again)
        if (!prefs.tizenTubeSet) { prefs.pinned = DefaultApps.putTizenTube(prefs.pinned); prefs.tizenTubeSet = true }
        if (prefs.visualLook == 2 && !prefs.lightDemandSet) {
            prefs.statusMode = 2; prefs.clockMode = 2; prefs.buttonsMode = 2; prefs.fitScreen = true; prefs.lightDemandSet = true
            prefs.focusScale = 35
        }
        // Light from the first 9.0 beta grew only 15 %: now 30 % like asked
        if (prefs.visualLook == 2 && prefs.focusScale == 15) prefs.focusScale = 35
        // Light: buttons go to the far right, time and date one row lower (was next to the buttons in the first beta)
        if (prefs.visualLook == 2 && prefs.clockSide == 2 && !prefs.clockMoved) { prefs.clockSide = 1; prefs.showDate = true; prefs.clockMoved = true }
        // a crash is written down; 3 crashes within 10 minutes = safe start (see safeStart)
        CrashWatch.install(this)
        Weather.fahrenheit = prefs.weatherF
        NotifListener.mode = prefs.notifMode
        NotifListener.hideJunk = prefs.hideSysNotifs
        // icons that come with LEAD (TiviMate, YouTube, Disney+ …)
        v10Migrate()
        runCatching { BuiltinIcons.seed(this) }
        Backdrop.lite = prefs.perfMode

        menu = GlassMenu(this, root) { open ->
            if (open && menuOpener == null) menuOpener = currentFocus?.takeIf { it !== grid && !isInGrid(it) }
            grid.descendantFocusability =
                if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            profileBtn.isFocusable = !open
            settingsBtn.isFocusable = !open
            updateBtn.isFocusable = !open
            powerBtn.isFocusable = !open
            statusGroups().forEach { it.isFocusable = !open }
            // v10: nothing behind an open menu can take the focus (one menu at a time)
            if (::nav.isInitialized) {
                (nav.view as ViewGroup).descendantFocusability = if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
                pageHost.descendantFocusability = if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            if (!open) {
                val back = menuOpener
                menuOpener = null
                if (back != null && back.isShown && back.isFocusable && openPageIx < 0) { back.requestFocus(); return@GlassMenu }
                // a page is open: its small numbers (notifications, updates …) are read again, cursor back on its card
                if (openPageIx >= 0) { pageHost.post { refreshPage(back) }; return@GlassMenu }
                focusTile(lastPos)
                if (plusPeek) { handler.removeCallbacks(hidePlus); handler.postDelayed(hidePlus, 1500) }
            }
        }

        for (b in listOf(profileBtn, settingsBtn, powerBtn)) {
            b.radiusDp = 21f
            b.setOnFocusChangeListener { v, has ->
                (v as GlassFrame).focusedLook = has
                val sc = if (has) 1.15f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(if (Backdrop.lite) 0L else 140L).withEndAction { v.invalidate() }.start()
            }
        }
        updateBtn.radiusDp = 21f
        updateBtn.accent = true
        updateBtn.setOnFocusChangeListener { v, has ->
            (v as GlassFrame).focusedLook = has
            val sc = if (has) 1.12f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(if (Backdrop.lite) 0L else 140L).withEndAction { v.invalidate() }.start()
        }
        updateBtn.setOnClickListener { installPendingOrCheck() }
        profileBtn.setOnClickListener { profileMenu() }
        settingsBtn.setOnClickListener { showSettings() }
        powerBtn.setOnClickListener { powerMenu() }

        statusPill.radiusDp = 24f
        adapter = AppAdapter(apps, prefs, ::onAppClick, ::onAppLongClick)
        layoutManager = GridLayoutManager(this, 1)
        rowManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        grid.layoutManager = if (prefs.singleRow) rowManager else layoutManager
        grid.adapter = adapter
        (grid.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = invalidateGlass()
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerNetworkCallback(
                android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback)
        }

        NotifListener.muted = prefs.mutedNotifs
        NotifListener.onChange = { runOnUiThread { updateStatus() } }

        v10Setup()
        applyLook()
        reloadApps()
        askLocationOnce()
        setupStatusClicks()
        setupAutoHide()
        setupTopExtras()
        if (CrashWatch.tooMany(this)) handler.postDelayed({ safeStart() }, 1500)
        runCatching { Updater.schedule(this) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) handler.postDelayed({ installPendingOrCheck() }, 800)
        if (intent?.getBooleanExtra(EXTRA_SHOW_APP_UPDATES, false) == true) handler.postDelayed({ openAppUpdates() }, 800)
    }

    override fun onResume() {
        super.onResume()
        HomeService.setHome(true)
        // coming back to the home screen (e.g. after the box woke up): check for a new version every 30 min at most
        handler.postDelayed({ maybeCheckUpdate(30) }, 3000)
        // the helper service (VPN key, Home button) can be switched off by Android – switch it back on by itself
        if ((prefs.overlayVpn || prefs.overlayClock || prefs.homeRedirect) && !HomeService.isEnabled(this) && hasSecure())
            HomeService.enableWithSecureSettings(this)
        // newer Android: CPU % only via Shizuku – ask once for its permission when Shizuku runs
        if (prefs.showCpu && !ShizukuCpu.permitted()) handler.postDelayed({ ShizukuCpu.ask() }, 1500)
        if (prefs.wpMode == 3) runCatching {
            val pkg = prefs.wpPlugin.substringBefore('/')
            @Suppress("DEPRECATION")
            val v = packageManager.getPackageInfo(pkg, 0).let { "${it.versionCode}/${it.lastUpdateTime}" }
            val g = getSharedPreferences("glass_global", Context.MODE_PRIVATE)
            if (g.getString("pluginVer", "") != v) { g.edit().putString("pluginVer", v).apply(); if (!firstLoad) pluginUpdated(pkg) }
        }
        getSharedPreferences("glass_global", Context.MODE_PRIVATE).let { g ->
            if (g.getBoolean("pendingSofa", false) && Extras.installed(this, Extras.SOFADROP)) {
                g.edit().remove("pendingSofa").apply()
                handler.postDelayed({ useSofadrop() }, 1000)
            }
        }
        lastInput = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (prefs.wpMode != 0 && !firstLoad) {
            handler.removeCallbacks(rotate)
            handler.postDelayed(rotate, prefs.wpInterval.coerceAtLeast(10) * 1000L)
        }
        resumed = true
        showUpdateChip(animate = false)
        getSharedPreferences("glass_global", Context.MODE_PRIVATE).let { g ->
            val night = g.getString("nightDone", "") ?: ""
            if (night.isNotBlank()) { g.edit().remove("nightDone").apply()
                handler.postDelayed({ toast(T.s("Updated tonight: ", "Noćas ažurirano: ", "Heute Nacht aktualisiert: ") + night) }, 2500) }
        }
        val every = 60 * 60 * 1000L
        if (prefs.updateUrl.isNotBlank() && System.currentTimeMillis() - prefs.lastUpdateCheck > every) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            checkUpdate(manual = false)
        }
    }

    /** Looks for a new LEAD version when the last look is older than [minMinutes] (start, wake-up, back home). */
    private fun maybeCheckUpdate(minMinutes: Int) {
        if (prefs.updateUrl.isBlank()) return
        val g = getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - g.getLong("lastUpdCheck", 0L) < minMinutes * 60_000L) return
        g.edit().putLong("lastUpdCheck", now).apply()
        checkUpdate(manual = false)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        HomeService.setHome(false)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(rotate)
        chipPulse?.cancel(); chipPulse = null
    }

    /** while another app is in front (TiviMate, YouTube …) the wallpaper is not needed: free its memory */
    private var wallpaperFreed = false

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            wallpaper.setImageDrawable(null)
            lastShown = null
            wallpaperFreed = true
        }
    }

    override fun onStart() {
        super.onStart()
        if (wallpaperFreed) {
            wallpaperFreed = false
            // straight back to the last picture (saved small on disk); the slideshow simply goes on from there
            if (!showLastPicture()) loadWallpaper()
        }
    }

    private val lastPictureFile get() = File(cacheDir, "last_wallpaper.jpg")

    /** keeps a copy of the picture on screen, so coming back home never shows an empty background */
    private fun keepLastPicture(b: Bitmap) {
        Thread { runCatching { lastPictureFile.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) } } }.start()
    }

    private fun showLastPicture(): Boolean {
        val f = lastPictureFile
        if (!f.exists()) return false
        val (w, h) = screenSize()
        val b = runCatching { Bitmaps.decodeScaled(f, w, h) }.getOrNull() ?: return false
        wallpaper.setImageBitmap(b)
        lastShown = b
        adaptStatus(b)
        return true
    }

    private var pictureFails = 0

    private fun pictureFailed() {
        if (wallpaper.drawable == null && !showLastPicture()) showOwnWallpaper()
        if (prefs.wpMode !in 1..3) return
        pictureFails++
        handler.removeCallbacks(rotate)
        // next picture in 3 s; after many misses in a row ask the plugin / TMDB for a fresh list
        if (pictureFails >= 5) { pictureFails = 0; movies = emptyList(); moviesKey = ""; startRotation() }
        else handler.postDelayed(rotate, 3000)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android asks for memory: drop what can be rebuilt (icons are reloaded when needed)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !resumed) {
            wallpaper.setImageDrawable(null); lastShown = null; wallpaperFreed = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(netCallback) }
        NotifListener.onChange = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while we are already open
        menu.close()
        if (movingPos >= 0) finishMove()
        // Home = always straight back to the start: any page or small dialog closes, cursor on the first card
        V10List.close(root)
        (0 until root.childCount).map { root.getChildAt(it) }.filter { it.tag == "v10dialog" }.forEach { root.removeView(it) }
        if (::nav.isInitialized && openPageIx >= 0) {
            openPageIx = -1
            pageHost.visibility = View.GONE; pageHost.removeAllViews()
            gridArea.visibility = View.VISIBLE
            applyWidgets(); nav.select(0)
        }
        lastPos = 0
        focusTile(0)
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) handler.postDelayed({ installPendingOrCheck() }, 500)
        if (intent?.getBooleanExtra(EXTRA_SHOW_APP_UPDATES, false) == true) handler.postDelayed({ openAppUpdates() }, 500)
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
        applyDim()
        showProfilePhoto()
        adaptStatus()
        profileLetter.text = Profiles.name(this, prefs.profile).take(1).uppercase()
        (hint.layoutParams as FrameLayout.LayoutParams).gravity = when (prefs.gridPosition) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.CENTER_VERTICAL or Gravity.START
            else -> Gravity.BOTTOM or Gravity.START
        }
        layoutGrid()
        placeClock()
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        hint.text = T.s("Press OK to add your apps", "Pritisni OK da dodaš svoje aplikacije", "OK drücken, um deine Apps hinzuzufügen")
        loadWallpaper()
        updateClock()
        updateStatus()
    }

    /** Size, columns, alignment and dock of the app row(s). */
    private fun layoutGrid() {
        // v10: glass cards, a fixed number per row (4 by default), the block centred on the screen
        val cell = Taupe.cardW(this) + Taupe.gap(this)
        val avail = if (gridArea.width > 0) gridArea.width else resources.displayMetrics.widthPixels
        // always ONE row: as many cards as fit are visible, the rest scrolls sideways (wraps around at the ends)
        val perRow = prefs.v10Cols.coerceIn(3, 6).coerceAtMost(maxOf(1, avail / cell))
        val visible = maxOf(1, minOf(perRow, apps.size))
        cols = apps.size.coerceAtLeast(1)
        if (grid.layoutManager !== rowManager) grid.layoutManager = rowManager
        val glp = grid.layoutParams
        glp.width = visible * cell
        grid.layoutParams = glp
        dock.setPadding(0, 0, 0, 0)
        dock.plain = true; dock.backdrop = false; dock.tint = 0f; dock.rimMode = 0
        dock.clipToOutline = false
        (dock.layoutParams as FrameLayout.LayoutParams).let {
            it.gravity = (if (prefs.gridPosition == 0) Gravity.TOP else Gravity.CENTER_VERTICAL) or Gravity.CENTER_HORIZONTAL
            it.bottomMargin = 0
            it.topMargin = if (prefs.gridPosition == 0) (24 * resources.displayMetrics.density).toInt() else 0
        }
        dock.requestLayout()
        dock.post { applyGridOffset() }
        dock.invalidate()
        adapter.notifyDataSetChanged()
        if (gridArea.width == 0) gridArea.post { if (gridArea.width > 0) layoutGrid() }
    }

    /** fine shift (+/-) of the app row, always kept fully on the screen */
    private fun applyGridOffset() {
        val d = resources.displayMetrics.density
        var tx = prefs.gridOffX * d
        var ty = prefs.gridOffY * d
        val (sw, sh) = screenSize()
        val loc = IntArray(2)
        dock.translationX = 0f; dock.translationY = 0f
        dock.getLocationOnScreen(loc)
        val margin = 12 * d
        // keep inside the screen: not below the bottom edge, not off left / right
        val maxDown = sh - margin - (loc[1] + dock.height)
        if (ty > maxDown) ty = maxDown
        if (loc[1] + ty < margin) ty = margin - loc[1]
        if (loc[0] + tx < 0) tx = -loc[0].toFloat()
        if (loc[0] + dock.width + tx > sw) tx = (sw - dock.width - loc[0]).toFloat()
        dock.translationX = tx
        dock.translationY = ty
    }

    /** +/- steps for the app row: the menu stays open so you can press several times */
    /** size of one icon only (60–160 %) */
    private fun iconSizeMenu(e: AppEntry) {
        val cur = prefs.iconScale(e.pkg)
        fun set(n: Int) { prefs.setIconScale(e.pkg, n.coerceIn(60, 160)); adapter.notifyDataSetChanged(); iconSizeMenu(e) }
        menu.show(T.s("Size of this icon", "Veličina ove ikone", "Größe dieses Symbols") + " – " + e.label, R.drawable.lc_columns_3, listOf(
            GlassMenu.Item(T.s("Smaller", "Manja", "Kleiner"), R.drawable.lc_minus) { set(cur - 10) },
            GlassMenu.Item(T.s("Bigger", "Veća", "Größer"), R.drawable.lc_plus) { set(cur + 10) },
            GlassMenu.Item(T.s("Reset", "Vrati na 100 %", "Zurücksetzen"), R.drawable.lc_rotate_cw, value = "$cur %") { set(100) },
        ), T.s("Only this icon. All icons: Settings › Icons and tiles. Back = done.",
            "Samo ova ikona. Sve ikone: Postavke › Ikone i pločice. Back = gotovo.",
            "Nur dieses Symbol. Alle Symbole: Einstellungen › Symbole und Kacheln. Zurück = fertig."))
    }

    /** position of one icon only: left / right / up / down */
    private fun iconNudgeMenu(e: AppEntry) {
        val (x, y) = prefs.iconOffset(e.pkg)
        val step = 6
        fun set(nx: Int, ny: Int) { prefs.setIconOffset(e.pkg, nx.coerceIn(-120, 120), ny.coerceIn(-120, 120)); adapter.notifyDataSetChanged(); iconNudgeMenu(e) }
        menu.show(T.s("Move this icon", "Pomjeri ovu ikonu", "Dieses Symbol verschieben") + " – " + e.label, R.drawable.lc_move, listOf(
            GlassMenu.Item(T.s("◀ Left", "◀ Lijevo", "◀ Links"), R.drawable.lc_minus) { set(x - step, y) },
            GlassMenu.Item(T.s("▶ Right", "▶ Desno", "▶ Rechts"), R.drawable.lc_plus) { set(x + step, y) },
            GlassMenu.Item(T.s("▲ Up", "▲ Gore", "▲ Hoch"), R.drawable.lc_minus) { set(x, y - step) },
            GlassMenu.Item(T.s("▼ Down", "▼ Dolje", "▼ Runter"), R.drawable.lc_plus) { set(x, y + step) },
            GlassMenu.Item(T.s("Reset", "Vrati na 0", "Zurücksetzen"), R.drawable.lc_rotate_cw, value = "$x / $y dp") { set(0, 0) },
        ), T.s("Only this icon moves. Back = done.", "Pomjera se samo ova ikona. Back = gotovo.", "Nur dieses Symbol bewegt sich. Zurück = fertig."))
    }

    private fun nudgeMenu(vertical: Boolean) {
        val step = 8
        val v = if (vertical) prefs.gridOffY else prefs.gridOffX
        val title = if (vertical) T.s("Fine position – up / down", "Fino – gore / dole", "Feinposition – oben / unten")
            else T.s("Fine position – left / right", "Fino – lijevo / desno", "Feinposition – links / rechts")
        fun set(n: Int) { if (vertical) prefs.gridOffY = n else prefs.gridOffX = n; applyGridOffset(); placeMovieInfo(); nudgeMenu(vertical) }
        menu.show(title, R.drawable.lc_move, listOf(
            GlassMenu.Item(if (vertical) T.s("▲ Up", "▲ Gore", "▲ Hoch") else T.s("◀ Left", "◀ Lijevo", "◀ Links"), R.drawable.lc_minus) { set(v - step) },
            GlassMenu.Item(if (vertical) T.s("▼ Down", "▼ Dolje", "▼ Runter") else T.s("▶ Right", "▶ Desno", "▶ Rechts"), R.drawable.lc_plus) { set(v + step) },
            GlassMenu.Item(T.s("Reset", "Vrati na 0", "Zurücksetzen"), R.drawable.lc_rotate_cw, value = "$v dp") { set(0) },
        ), T.s("Each press moves the apps a little. Back = done.", "Svaki pritisak malo pomjeri aplikacije. Back = gotovo.",
            "Jeder Druck verschiebt die Apps ein Stück. Zurück = fertig."))
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return (if (root.width > 0) root.width else dm.widthPixels) to (if (root.height > 0) root.height else dm.heightPixels)
    }

    /** Decodes a picture off the main thread and shows it (with soft fade when [fade]). */
    private fun showPicture(load: () -> Bitmap?, fade: Boolean, movie: Movie? = null) {
        val blur = prefs.blurWallpaper
        val (w, h) = screenSize()
        Thread {
            val src = runCatching { load() }.getOrNull() ?: run {
                // picture could not be fetched (plugin busy / network): try the next one soon, never stay black
                handler.post { pictureFailed() }
                return@Thread
            }
            val crop = Bitmaps.centerCrop(src, w, h)
            if (src !== crop) runCatching { src.recycle() }   // the full-size original is not needed any more
            val frosted = if (prefs.perfMode) null else Bitmaps.frosted(crop)
            val shown = if (blur) Bitmaps.softBlur(crop) else crop
            handler.post {
                val apply = {
                    Backdrop.blurred = frosted
                    Backdrop.w = w
                    Backdrop.h = h
                    wallpaper.setImageBitmap(shown)
                    keepLastPicture(shown)
                    pictureFails = 0
                    showMovieInfo(movie)
                    adaptStatus(shown)
                    invalidateGlass()
                }
                if (fade && !prefs.perfMode) {
                    wallpaper.animate().alpha(0.15f).setDuration(450).withEndAction {
                        apply()
                        wallpaper.animate().alpha(1f).setDuration(650).start()
                    }.start()
                } else apply()
            }
        }.start()
    }

    private var lastShown: Bitmap? = null

    /** Glass of the status bar and top buttons: own transparency, and adapts to the picture behind it. */
    private fun adaptStatus(b: Bitmap? = lastShown) {
        if (b != null) lastShown = b
        val top = listOf(statusPill, updateBtn, profileBtn, settingsBtn, powerBtn)
        if (prefs.statusLikeTiles) {
            // exactly the clear glass of the app tiles
            for (g in top) {
                g.backdrop = false; g.liquid = true; g.backdropAlpha = 0f; g.solid = 0f
                g.tint = prefs.tileAlpha / 100f * 0.45f; g.darken = 0f; g.rimMode = 0
                g.invalidate()
            }
            return
        }
        // o = how solid the glass is: 1 at 0 % see-through, 0 at 100 %
        val o = 1f - prefs.statusClear.coerceIn(0, 100) / 100f
        var dark = 0f
        if (prefs.statusAdaptive && lastShown != null) {
            val lum = Bitmaps.brightness(lastShown!!, 0.35f, 0f, 1f, 0.16f)
            // bright picture -> darker glass so white text stays readable
            dark = ((lum - 0.35f) * 1.1f).coerceIn(0f, 0.55f) * maxOf(o, 0.25f)
        }
        for (g in top) {
            g.backdrop = o > 0.02f
            g.backdropAlpha = o                       // blur fades away as it gets more see-through
            g.solid = 0.88f * o * o                   // solid filling only near 0 %
            g.liquid = o < 0.5f                       // clear end: light on the edges like the tiles
            g.tint = 0.04f + 0.10f * o
            g.darken = dark
            g.rimMode = if (o < 0.5f) 0 else 2
            g.invalidate()
        }
    }


    private fun loadWallpaper() {
        if (prefs.wpMode != 0) { startRotation(); return }
        stopRotation()
        showOwnWallpaper()
    }

    /** your own picture (also the stand-in while a plugin is being updated) */
    private fun showOwnWallpaper() {
        val f = wallpaperFile
        val (w, h) = screenSize()
        showPicture({
            if (f.exists()) Bitmaps.decodeScaled(f, w, h)
            else Bitmaps.decodeResScaled(this, Bitmaps.leadWallpaper(prefs.leadWp), w, h)
        }, fade = false)
    }

    // ------------------------------------------------------------------ changing wallpaper

    private var movies: List<Movie> = emptyList()
    private var moviesKey = ""
    /** which source filled [movies]: 2 = TMDB, 3 = plugin. Only that source is ever shown. */
    private var moviesMode = 0
    private var slideFiles: List<File> = emptyList()
    private var slideIndex = 0
    private val rotate = object : Runnable {
        override fun run() {
            nextSlide()
            handler.postDelayed(this, prefs.wpInterval.coerceAtLeast(10) * 1000L)
        }
    }

    private fun stopRotation() {
        handler.removeCallbacks(rotate)
        showMovieInfo(null)
    }

    private var pluginRetries = 0
    private val pluginRetry = Runnable { if (prefs.wpMode == 3) { movies = emptyList(); moviesKey = ""; startRotation() } }

    /** tries the wallpaper plugin again: after 10 s, 20 s, 40 s … at most every 5 minutes */
    private fun retryPlugin() {
        handler.removeCallbacks(pluginRetry)
        val wait = (10_000L shl pluginRetries.coerceAtMost(5)).coerceAtMost(300_000L)
        pluginRetries++
        handler.postDelayed(pluginRetry, wait)
    }

    /** the wallpaper plugin was installed / updated: start it again by itself */
    private fun pluginUpdated(pkg: String?) {
        if (pkg == null || prefs.wpMode != 3 || !prefs.wpPlugin.startsWith("$pkg/")) return
        pluginRetries = 0
        handler.removeCallbacks(pluginRetry)
        handler.postDelayed(pluginRetry, 3000)   // give the new version a moment to start
    }

    private fun startRotation() {
        handler.removeCallbacks(rotate)
        when (prefs.wpMode) {
            1 -> {
                val dir = File(prefs.wpFolder)
                slideFiles = (dir.listFiles() ?: emptyArray()).filter { it.isFile && ImageScanner.isImage(it) }.shuffled()
                if (slideFiles.isEmpty()) {
                    toast(T.s("No pictures in the chosen folder", "Nema slika u izabranom folderu", "Keine Bilder im gewählten Ordner"))
                    prefs.wpMode = 0; loadWallpaper(); return
                }
                handler.post(rotate)
            }
            3 -> {
                val p = Plugins.find(this, prefs.wpPlugin)
                if (p == null) {
                    // plugin missing – often only for a moment while it updates. Keep the setting,
                    // show your own picture meanwhile and try again by itself.
                    if (movies.isEmpty()) showOwnWallpaper()
                    retryPlugin()
                    return
                }
                val cacheKey = "plugin:" + p.id
                if (cacheKey == moviesKey && moviesMode == 3 && movies.isNotEmpty()) { handler.post(rotate); return }
                if (moviesMode != 3) { movies = emptyList(); moviesKey = "" }
                Thread {
                    val list = Plugins.movies(Plugins.fetch(this, p)).shuffled()
                    handler.post {
                        if (prefs.wpMode != 3) return@post   // switched to something else meanwhile
                        if (list.isEmpty()) {
                            // plugin not ready (just updated / still starting): own picture for now, try again soon
                            if (movies.isEmpty()) showOwnWallpaper()
                            retryPlugin()
                            return@post
                        }
                        pluginRetries = 0
                        movies = list; moviesKey = cacheKey; moviesMode = 3; slideIndex = 0
                        handler.removeCallbacks(rotate); handler.post(rotate)
                    }
                }.start()
            }
            2 -> {
                val key = prefs.tmdbKey.trim()
                if (key.isBlank()) {
                    toast(T.s("Enter your TMDB key first", "Prvo upiši TMDB ključ", "Zuerst den TMDB-Schlüssel eintragen"))
                    prefs.wpMode = 0; loadWallpaper(); return
                }
                // the day is part of the key, so the list is fetched fresh every day
                val day = System.currentTimeMillis() / (24 * 60 * 60 * 1000L)
                val cacheKey = key + prefs.tmdbType + prefs.tmdbList + T.lang + day
                if (cacheKey == moviesKey && moviesMode == 2 && movies.isNotEmpty()) { handler.post(rotate); return }
                if (moviesMode != 2) { movies = emptyList(); moviesKey = "" }
                Thread {
                    val list = Movies.load(key, prefs.tmdbType, prefs.tmdbList).shuffled()
                    handler.post {
                        if (prefs.wpMode != 2) return@post   // switched to something else meanwhile
                        if (list.isEmpty()) {
                            toast(T.s("Couldn't load films (key or internet?)", "Filmovi se ne mogu učitati (ključ ili internet?)", "Filme nicht ladbar (Schlüssel oder Internet?)"))
                            if (movies.isEmpty()) { prefs.wpMode = 0; loadWallpaper() }
                            return@post
                        }
                        movies = list; moviesKey = cacheKey; moviesMode = 2; slideIndex = 0
                        handler.removeCallbacks(rotate); handler.post(rotate)
                    }
                }.start()
            }
        }
    }

    private fun nextSlide() {
        val (w, h) = screenSize()
        when (prefs.wpMode) {
            1 -> {
                if (slideFiles.isEmpty()) return
                val f = slideFiles[slideIndex++ % slideFiles.size]
                showPicture({ Bitmaps.decodeScaled(f, w, h) }, fade = true)
            }
            2 -> {
                if (movies.isEmpty() || moviesMode != 2) return
                val m = movies[slideIndex++ % movies.size]
                if (slideIndex % movies.size == 0) moviesKey = "" // refresh the list now and then
                showPicture({
                    val tmp = File(cacheDir, "movie_bg.jpg")
                    Http.download(m.image, tmp)
                    Bitmaps.decodeScaled(tmp, w, h)
                }, fade = true, movie = m)
            }
            3 -> {
                if (movies.isEmpty() || moviesMode != 3) return
                val m = movies[slideIndex++ % movies.size]
                if (slideIndex % movies.size == 0) moviesKey = "" // ask the plugin again after one round
                showPicture({
                    val tmp = File(cacheDir, "plugin_bg.img")
                    Plugins.download(this, m.image, tmp)
                    Bitmaps.decodeScaled(tmp, w, h)
                }, fade = true, movie = m)
            }
        }
    }

    /** Keeps the film title/description above the app row, whatever the tile size. */
    private fun placeMovieInfo() {
        val info = findViewById<View>(R.id.movieInfo)
        if (dock.height == 0 || root.height == 0) return
        val loc = IntArray(2)
        dock.getLocationInWindow(loc)
        val d = resources.displayMetrics.density
        val lp = info.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.bottomMargin = (root.height - loc[1] + 20 * d).toInt().coerceAtLeast((40 * d).toInt())
        info.layoutParams = lp
    }

    private fun showMovieInfo(m: Movie?) {
        val info = findViewById<View>(R.id.movieInfo)
        val shade = findViewById<View>(R.id.movieShade)
        // plugin pictures (SofaDrop …) already carry their own title in the picture: show only the picture
        val show = m != null && prefs.wpMode == 2 && prefs.showMovieInfo
        info.visibility = if (show) View.VISIBLE else View.GONE
        shade.visibility = if (show) View.VISIBLE else View.GONE
        if (m == null || !show) return
        findViewById<TextView>(R.id.movieTitle).text = m.title
        findViewById<TextView>(R.id.movieMeta).text =
            listOf(if (m.rating.isNotEmpty()) "★ ${m.rating}" else "", m.year, m.kind).filter { it.isNotEmpty() }.joinToString("   ·   ")
        findViewById<TextView>(R.id.movieOverview).text = m.overview
        info.alpha = 0f
        info.animate().alpha(1f).setDuration(600).start()
    }

    // ================================================================== status bar

    private fun updateClock() {
        val time = SimpleDateFormat(ClockFmt.pattern(this, prefs), T.locale).format(Date())
        val pat = when (prefs.dateStyle) { 1 -> "EEE, d. MMM"; 2 -> "dd.MM.yyyy"; else -> "EEEE, d. MMMM" }
        val d = SimpleDateFormat(pat, T.locale).format(Date()).replaceFirstChar { it.uppercase() }
        if (prefs.clockOneLine && prefs.showDate) {
            // one line: "14:02:15  Monday, 16. November" – the date smaller, on the same line
            val sp = android.text.SpannableString("$time   $d")
            sp.setSpan(android.text.style.RelativeSizeSpan(0.42f), time.length, sp.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(Color.argb(221, 255, 255, 255)), time.length, sp.length, 0)
            clock.text = sp
            date.visibility = View.GONE
        } else {
            clock.text = time
            date.text = d
            date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        }
    }

    /** font and size of the clock (Settings › Clock) */
    private fun styleClock(baseSp: Float) {
        clock.typeface = when (prefs.clockFont) {
            0 -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            2 -> Fonts.regular(this)
            3 -> Fonts.medium(this)
            4 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            5 -> Typeface.MONOSPACE
            else -> Fonts.light(this)
        }
        val f = listOf(0.75f, 1f, 1.3f, 1.6f)[prefs.clockSize.coerceIn(0, 3)]
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * f)
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * f.coerceAtMost(1.3f))
    }

    @Volatile private var statusBusy = false

    /**
     * Reads Wi-Fi, Bluetooth, RAM and storage on a background thread: when the Bluetooth stack is busy
     * (e.g. a big file transfer) these system calls can hang for seconds, and on the main thread
     * that froze the remote.
     */
    private fun updateStatus() {
        if (statusBusy) return
        statusBusy = true
        val wantBt = prefs.showBluetooth; val wantRam = prefs.showRam; val wantDisk = prefs.showDisk
        Thread {
            try {
                val net = NetInfo.read(this)
                val bt = if (wantBt) runCatching { BtInfo.read(this) }.getOrNull() else null
                val ram = if (wantRam) SysStats.ramPercent(this) else 0
                val disk = if (wantDisk) SysStats.diskPercent() else 0
                handler.post { statusBusy = false; if (!isFinishing && !isDestroyed) applyStatus(net, bt, ram, disk) }
            } catch (e: Exception) {
                statusBusy = false
            }
        }.start()
    }

    private fun applyStatus(net: NetState, btRead: BtState?, ramPct: Int, diskPct: Int) {
        val netBox = findViewById<View>(R.id.netBox)
        val netIcon = findViewById<ImageView>(R.id.netIcon)
        val ssid = findViewById<TextView>(R.id.ssidText)
        netBox.visibility = if (prefs.showNetwork) View.VISIBLE else View.GONE
        when {
            net.eth -> { netIcon.setImageResource(R.drawable.lc_ethernet_port); netIcon.alpha = 1f }
            net.wifi -> { netIcon.setImageResource(R.drawable.lc_wifi); netIcon.alpha = 1f }
            else -> { netIcon.setImageResource(R.drawable.lc_wifi_off); netIcon.alpha = 0.5f }
        }
        ssidWanted = net.wifi && !net.eth && net.ssid != null
        val showSsid = ssidWanted && !statusCompactSsid
        ssid.visibility = if (showSsid) View.VISIBLE else View.GONE
        if (ssidWanted) ssid.text = net.ssid

        // the VPN key sits next to the buttons and is always visible: green = VPN on, red = off
        findViewById<View>(R.id.vpnBox).visibility = View.GONE
        findViewById<ImageView>(R.id.vpnKey).let {
            it.setColorFilter(if (net.vpn) Color.rgb(52, 212, 122) else Color.rgb(255, 80, 80))
        }

        val btBox = findViewById<View>(R.id.btBox)
        val bt = if (prefs.showBluetooth) btRead else null
        btBox.visibility = if (bt != null && bt.available) View.VISIBLE else View.GONE
        if (bt != null && bt.available) {
            val btIcon = findViewById<ImageView>(R.id.btIcon)
            val btCount = findViewById<TextView>(R.id.btCount)
            btIcon.alpha = if (bt.on) 1f else 0.45f
            if (bt.on && bt.connectedCount > 0) btIcon.setColorFilter(Color.rgb(96, 170, 255)) else btIcon.clearColorFilter()
            btCount.visibility = if (bt.on && bt.connectedCount > 0) View.VISIBLE else View.GONE
            btCount.text = bt.connectedCount.toString()
        }

        findViewById<View>(R.id.notifBox).visibility = if (prefs.showNotifs) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.notifCount).text = bellCount().toString()
        updateBell()

        // newer Android (Google TV Streamer …) gives apps no CPU numbers at all: then the CPU field hides itself
        // CPU is measured on a background thread (it can take a moment on some boxes) – never block the remote
        val cpu = if (prefs.showCpu) cpuValue else null
        // no CPU numbers on this box (and no Shizuku): only try again once a minute
        val cpuRetry = cpuMisses < 3 || ShizukuCpu.permitted() || tickCount % 60 < 5
        if (prefs.showCpu && !cpuBusy && cpuRetry) {
            cpuBusy = true
            Thread { val v = runCatching { SysStats.cpuPercent() }.getOrNull(); handler.post { cpuValue = v; cpuBusy = false } }.start()
        }
        if (prefs.showCpu) cpuMisses = if (cpu == null) cpuMisses + 1 else 0
        val cpuShown = prefs.showCpu && (cpuMisses < 3 || ShizukuCpu.permitted())
        findViewById<View>(R.id.cpuBox).visibility = if (cpuShown) View.VISIBLE else View.GONE
        if (cpuShown) findViewById<TextView>(R.id.cpuText).text = cpu?.let { "$it%" } ?: "–"
        findViewById<View>(R.id.ramBox).visibility = if (prefs.showRam) View.VISIBLE else View.GONE
        if (prefs.showRam) findViewById<TextView>(R.id.ramText).text = "$ramPct%"
        findViewById<View>(R.id.diskBox).visibility = if (prefs.showDisk) View.VISIBLE else View.GONE
        if (prefs.showDisk) {
            val dp = diskPct
            findViewById<TextView>(R.id.diskText).text = "$dp%"
            // nearly full storage turns the icon orange / red
            findViewById<ImageView>(R.id.diskIcon).let {
                if (dp >= 95) it.setColorFilter(Color.rgb(255, 80, 80)) else if (dp >= 85) it.setColorFilter(Color.rgb(255, 170, 60)) else it.clearColorFilter()
            }
        }

        findViewById<View>(R.id.weatherBox).visibility = if (prefs.showWeather) View.VISIBLE else View.GONE

        if (prefs.statusIconsOnly) for (id in listOf(R.id.weatherText, R.id.ssidText, R.id.btCount, R.id.notifCount, R.id.cpuText, R.id.ramText, R.id.diskText))
            findViewById<View>(id).visibility = View.GONE
        else for (id in listOf(R.id.weatherText, R.id.notifCount, R.id.cpuText, R.id.ramText, R.id.diskText))
            findViewById<View>(id).visibility = View.VISIBLE
        val left = prefs.showWeather || prefs.showNetwork || prefs.showBluetooth || prefs.showNotifs
        val right = (prefs.showCpu && cpuMisses < 3) || prefs.showRam || prefs.showDisk
        findViewById<View>(R.id.divider).visibility = if (left && right) View.VISIBLE else View.GONE
        // first visible group must not have a left margin
        val groups = listOf(R.id.weatherBox, R.id.netBox, R.id.vpnBox, R.id.btBox, R.id.notifBox, R.id.cpuBox, R.id.ramBox, R.id.diskBox)
            .map { findViewById<View>(it) }
        val firstVisible = groups.firstOrNull { it.visibility == View.VISIBLE }
        setStatusGap(groups, firstVisible, statusGapDp)
        pillWanted = prefs.showStatusBar && (left || right)
        showPill()
        statusPill.post { fitStatus(groups, firstVisible) }
    }

    /** gap between the status groups and whether the Wi-Fi name is hidden – shrinks when space is short */
    private var statusGapDp = 12
    private var statusCompactSsid = false
    private var ssidWanted = false

    private fun setStatusGap(groups: List<View>, first: View?, gapDp: Int) {
        val m = (gapDp * resources.displayMetrics.density).toInt()
        for (g in groups) {
            val lp = g.layoutParams as ViewGroup.MarginLayoutParams
            val want = if (g == first) 0 else m
            if (lp.marginStart != want) { lp.marginStart = want; g.layoutParams = lp }
        }
    }

    /**
     * With many icons switched on the bar no longer fits next to the buttons and Android squeezes
     * the icons on top of each other. Then: smaller gaps first, then hide the Wi-Fi name.
     */
    private fun fitStatus(groups: List<View>, first: View?) {
        val inner = (statusPill as ViewGroup).getChildAt(0) ?: return
        val row = statusPill.parent as? LinearLayout ?: return
        if (statusPill.visibility != View.VISIBLE) return
        if (row.width <= 0) { statusPill.postDelayed({ fitStatus(groups, first) }, 300); return }
        var others = row.paddingLeft + row.paddingRight
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c === statusPill || c === clockSpacer || c.visibility == View.GONE) continue
            val lp = c.layoutParams as? ViewGroup.MarginLayoutParams
            others += c.width + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
        }
        val plp = statusPill.layoutParams as? ViewGroup.MarginLayoutParams
        val avail = row.width - others - (plp?.leftMargin ?: 0) - (plp?.rightMargin ?: 0)
        val ssid = findViewById<View>(R.id.ssidText)
        val levels = listOf(12 to false, 9 to false, 9 to true, 6 to true)
        for ((gap, hideSsid) in levels) {
            setStatusGap(groups, first, gap)
            ssid.visibility = if (ssidWanted && !hideSsid && prefs.showNetwork) View.VISIBLE else View.GONE
            inner.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            statusGapDp = gap; statusCompactSsid = hideSsid
            if (inner.measuredWidth <= avail) break
        }
        // the test measuring above must be followed by a real layout, otherwise icons keep old places (overlap)
        inner.requestLayout(); statusPill.requestLayout()
    }

    private var weatherFails = 0

    /** internet back (e.g. after switching on): refresh weather and the bar straight away */
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            handler.postDelayed({
                if (weatherFails > 0 || findViewById<TextView>(R.id.weatherText).text.isNullOrBlank()) { lastWeather = 0; updateWeather() }
                updateStatus()
                maybeCheckUpdate(5)   // box switched on / internet back: look for a new LEAD right away
            }, 1500)
        }
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
                    weatherFails = 0
                    findViewById<ImageView>(R.id.weatherIcon).let { iv ->
                        iv.setImageResource(w.first)
                        when (prefs.weatherIcons) {
                            0 -> { iv.clearColorFilter(); iv.alpha = 0.6f }
                            2 -> { iv.setColorFilter(weatherColour(w.first)); iv.alpha = 1f }
                            else -> { iv.clearColorFilter(); iv.alpha = 1f }
                        }
                    }
                    findViewById<TextView>(R.id.weatherText).text = w.second
                    updateStatus()   // the bar got wider: fit it again
                } else {
                    // right after switching on the internet is often not there yet: try again soon
                    // (20 s, 40 s, 80 s … at most 5 min)
                    weatherFails++
                    val wait = (20_000L shl (weatherFails - 1).coerceAtMost(4)).coerceAtMost(300_000L)
                    lastWeather = System.currentTimeMillis() - prefs.weatherMin * 60 * 1000L + wait
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
                adapter.clearMirrors()
                plusBitmap = plus
                plusPeek = false
                if (prefs.showAddTile || list.isEmpty()) apps.add(AppEntry(AppEntry.ADD, T.s("Add", "Dodaj", "Hinzufügen"), plus))
                layoutGrid()
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
            e.missing -> installMissing(e)
            else -> launch(e.pkg, e.label)
        }
    }

    private fun launch(pkg: String, label: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast(T.s("Can't open $label", "Ne mogu otvoriti $label", "$label lässt sich nicht öffnen", "Не удаётся открыть $label")); return }
        // the helper service counts app starts itself (also outside LEAD); without it count here
        if (HomeService.instance == null) Usage.add(this, pkg)
        try { startActivity(i) } catch (ex: Exception) { toast(T.s("Can't open $label", "Ne mogu otvoriti $label", "$label lässt sich nicht öffnen", "Не удаётся открыть $label")) }
    }

    private fun onAppLongClick(pos: Int) {
        lastPos = pos
        val e = apps[pos]
        if (e.isAdd) { showAddMenu(); return }
        val items = mutableListOf(
            GlassMenu.Item(T.s("Change position", "Promijeni položaj", "Position ändern", "Изменить положение"), R.drawable.lc_move) { startMove(pos) },
            GlassMenu.Item(T.s("Change size", "Promijeni veličinu", "Größe ändern", "Изменить размер"), R.drawable.lc_columns_3,
                value = "${prefs.iconScale(e.pkg)} %") { iconSizeMenu(e) },
            GlassMenu.Item(T.s("Change picture", "Promijeni sliku", "Bild ändern", "Изменить картинку"), R.drawable.lc_image) { iconMenu(e) },
            GlassMenu.Item(T.s("Rename", "Preimenuj", "Umbenennen", "Переименовать"), R.drawable.lc_pencil, value = e.label) {
                // empty name = the app's own name again
                askText(T.s("Name of this card", "Ime ove kartice", "Name dieser Karte", "Имя карточки"), e.label) { prefs.setLabel(e.pkg, it.trim().ifBlank { null }); reloadApps() }
            },
            GlassMenu.Item(T.s("Remove from Home", "Ukloni s početnog", "Vom Start entfernen", "Убрать с главного"), R.drawable.lc_x) {
                val before = prefs.pinned
                prefs.pinned = before - e.pkg; lastPos = (pos - 1).coerceAtLeast(0); reloadApps()
                V10Undo.show(root, T.s("${e.label} removed", "${e.label} uklonjen", "${e.label} entfernt", "${e.label} убран")) {
                    prefs.pinned = before; lastPos = pos; reloadApps(); focusTile(pos)
                }
            },
        )
        if (e.missing) items.add(0, GlassMenu.Item(T.s("Install", "Instaliraj", "Installieren", "Установить"), R.drawable.lc_download) { installMissing(e) })
        if (!e.isFiles && !e.missing) {
            items.add(GlassMenu.Item(T.s("Uninstall", "Deinstaliraj", "Deinstallieren", "Удалить"), R.drawable.lc_trash_2) {
                // Android's own removal screen – LEAD never removes apps by itself
                safeStart(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.pkg, null)))
            })
            items.add(GlassMenu.Item(T.s("App info", "Informacije o aplikaciji", "App-Info", "О приложении"), R.drawable.lc_info) {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.pkg, null)))
            })
        }
        if (prefs.iconScale(e.pkg) != 100 || prefs.iconOffset(e.pkg) != (0 to 0))
            items.add(GlassMenu.Item(T.s("Reset size and position", "Vrati veličinu i položaj", "Größe und Position zurücksetzen", "Сбросить размер"), R.drawable.lc_rotate_ccw) {
                prefs.setIconScale(e.pkg, 100); prefs.setIconOffset(e.pkg, 0, 0); adapter.notifyDataSetChanged()
            })
        menu.show(e.label, null, items)
    }

    // ------------------------------------------------------------------ "+" menu

    /** Long press on an app in "All apps": open, put on the home screen, app info, force stop, uninstall. */
    private fun appOptions(pkg: String, label: String) {
        val pinned = pkg in prefs.pinned
        val isSystem = runCatching {
            (packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
        val items = mutableListOf(
            GlassMenu.Item(T.s("Open", "Otvori", "Öffnen"), R.drawable.lc_play) { launch(pkg, label) },
            if (pinned) GlassMenu.Item(T.s("Remove from home screen", "Ukloni s početnog ekrana", "Vom Startbildschirm entfernen"), R.drawable.lc_x) {
                prefs.pinned = prefs.pinned - pkg; reloadApps()
            } else GlassMenu.Item(T.s("Add to home screen", "Dodaj na početni ekran", "Zum Startbildschirm hinzufügen"), R.drawable.lc_plus) {
                prefs.pinned = prefs.pinned + pkg; reloadApps()
                toast(T.s("Added: ", "Dodano: ", "Hinzugefügt: ") + label)
            },
            GlassMenu.Item(T.s("App info", "Informacije o aplikaciji", "App-Info"), R.drawable.lc_info) {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            },
            GlassMenu.Item(T.s("Force stop", "Prisilno zaustavi", "Beenden erzwingen"), R.drawable.lc_power) {
                if (ShizukuCpu.forceStop(pkg)) toast(T.s("Stopped: ", "Zaustavljeno: ", "Beendet: ") + label)
                else {
                    // without Shizuku Android only allows it on the app info page
                    toast(T.s("Press \"Force stop\" here", "Ovdje pritisni \"Force stop\"", "Hier \"Beenden erzwingen\" drücken"))
                    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
                }
            },
        )
        if (!isSystem) items.add(GlassMenu.Item(T.s("Update source (GitHub)", "Izvor ažuriranja (GitHub)", "Update-Quelle (GitHub)"),
            R.drawable.lc_link, value = AppUpdates.githubFor(this, pkg) ?: "Play") {
            askText(T.s("GitHub owner/repo (empty = Google Play)", "GitHub vlasnik/repo (prazno = Google Play)", "GitHub Besitzer/Repo (leer = Google Play)"),
                AppUpdates.githubFor(this, pkg) ?: "") { AppUpdates.setGithub(this, pkg, it) }
        })
        if (!isSystem) items.add(GlassMenu.Item(T.s("Uninstall", "Deinstaliraj", "Deinstallieren"), R.drawable.lc_trash_2) {
            @Suppress("DEPRECATION")
            safeStart(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null)))
        })
        menu.show(label, R.drawable.lc_package, items, pkg)
    }

    /** Every app on the box (also system apps) – just open it, nothing is added to the home screen. */
    private fun allAppsMenu() {
        Thread {
            val list = AppLoader.choosable(this)
            handler.post {
                menu.show(T.s("All apps", "Sve aplikacije", "Alle Apps") + " (${list.size})", R.drawable.lc_layout_grid,
                    list.map { (pkg, label, icon) ->
                        GlassMenu.Item(label, drawable = icon,
                            longAction = if (pkg == AppEntry.FILES) null else ({ _: GlassMenu.Item -> appOptions(pkg, label) })) {
                            if (pkg == AppEntry.FILES) { Usage.add(this, pkg); startActivity(Intent(this, FileBrowserActivity::class.java)) }
                            else launch(pkg, label)
                        }
                    },
                    T.s("Most used first · hold OK (or right click) for app options",
                        "Najkorištenije prve · drži OK (ili desni klik) za opcije aplikacije",
                        "Meistgenutzte zuerst · OK halten (oder Rechtsklick) für App-Optionen"))
            }
        }.start()
    }

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

    /** Move one home-screen app exactly one slot and persist the new order immediately. */
    private fun movePinnedTile(pos: Int, delta: Int) {
        val first = apps.indexOfFirst { !it.isAdd }
        val last = apps.indexOfLast { !it.isAdd }
        if (first < 0 || pos !in first..last) return
        val target = (pos + delta).coerceIn(first, last)
        if (target == pos) {
            toast(T.s("Already at the edge", "Ikona je već na rubu", "Symbol ist bereits am Rand"))
            return
        }
        val item = apps.removeAt(pos)
        apps.add(target, item)
        adapter.notifyItemMoved(pos, target)
        prefs.pinned = apps.filter { !it.isAdd }.map { it.pkg }
        lastPos = target
        grid.scrollToPosition(target)
        grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
    }

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

    // ------------------------------------------------------------------ hidden "+" (2x left on the first app)

    private var plusBitmap: Bitmap? = null
    private var plusPeek = false
    private var lastLeftAtStart = 0L
    private val hidePlus = Runnable { removePeekPlus() }

    private fun focusedPos(): Int {
        val v = grid.focusedChild ?: return RecyclerView.NO_POSITION
        return grid.getChildAdapterPosition(v)
    }

    private fun revealPlus() {
        if (apps.any { it.isAdd }) return
        apps.add(0, AppEntry(AppEntry.ADD, T.s("Add", "Dodaj", "Hinzufügen"), plusBitmap))
        plusPeek = true
        layoutGrid()
        lastPos = 0
        focusTile(0)
        handler.removeCallbacks(hidePlus)
        handler.postDelayed(hidePlus, 3000)
    }

    private fun removePeekPlus() {
        handler.removeCallbacks(hidePlus)
        if (!plusPeek || menu.isOpen) return
        val i = apps.indexOfFirst { it.isAdd }
        plusPeek = false
        if (i < 0) return
        val wasFocused = focusedPos() == i
        apps.removeAt(i)
        layoutGrid()
        if (wasFocused) focusTile(0)
    }

    private fun moveBy(delta: Int) {
        val first = apps.indexOfFirst { !it.isAdd }.coerceAtLeast(0) // "+" stays at the end
        val last = apps.indexOfLast { !it.isAdd }
        val target = (movingPos + delta).coerceIn(first, maxOf(first, last))
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
        // a small dialog / list on top: the cursor stays in it, Back closes it
        if (!menu.isOpen && V10Overlay.handle(root, event, currentFocus)) return true
        val k = event.keyCode
        if (menu.isOpen && k == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) menu.back()
            return true
        }
        if (!menu.isOpen && movingPos < 0 && !V10Dialog.isOpen(root) && !V10List.isOpen(root) && v10Key(event)) return true
        if (!menu.isOpen && movingPos < 0 && k == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f != null && (f === clock || isInGrid(f))) {
                if (!shownBy(prefs.buttonsMode) || !shownBy(prefs.statusMode) || !shownBy(prefs.clockMode)) {
                    // Light: the top is empty – Up brings everything in, then the cursor goes up
                    setReveal(true)
                    val again = KeyEvent(event)
                    root.postDelayed({ dispatchKeyEvent(again) }, 60)
                    return true
                }
                val loc = IntArray(2); f.getLocationOnScreen(loc)
                val cx = loc[0] + f.width / 2
                val sw = screenSize().first
                val buttons = listOfNotNull(bellBadge, findViewById<View>(R.id.vpnKey), profileBtn, settingsBtn, powerBtn)
                    .filter { it.visibility == View.VISIBLE && it.isFocusable }
                fun nearest(l: List<View>) = l.minByOrNull { v -> val p = IntArray(2); v.getLocationOnScreen(p); Math.abs(p[0] + v.width / 2 - cx) }
                if (f === clock || cx > sw * 0.62 || !pillWanted) {
                    nearest(buttons)?.requestFocus(); return true
                }
                // middle / left: up opens the status bar and goes onto it
                setReveal(true)
                statusPill.post { nearest(statusGroups().filter { it.visibility == View.VISIBLE && it.isFocusable })?.requestFocus() ?: nearest(buttons)?.requestFocus() }
                return true
            }
        }
        if (!menu.isOpen && movingPos < 0 && prefs.numberKeys && k in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val real = apps.withIndex().filter { !it.value.isAdd }
                real.getOrNull(k - KeyEvent.KEYCODE_1)?.let { onAppClick(it.index) }
            }
            return true
        }
        if (!menu.isOpen && !demandReveal && (prefs.statusMode == 2 || prefs.clockMode == 2 || prefs.buttonsMode == 2) && k == KeyEvent.KEYCODE_DPAD_UP &&
            event.action == KeyEvent.ACTION_DOWN && inTopRow(currentFocus)) {
            // on demand: one more Up at the top shows the status bar and / or the clock
            setReveal(true)
            if (prefs.statusMode == 2 && pillWanted)
                statusPill.post { statusGroups().firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }?.requestFocus() }
            return true
        }
        if (!menu.isOpen && movingPos < 0 && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val pos = focusedPos()
            // the row stops at both ends: left on the first tile stays there, right on the last tile (or +) stays there
            if (prefs.singleRow && pos != RecyclerView.NO_POSITION) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT && pos == apps.size - 1) return true
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) return true
            }
        }
        if (movingPos >= 0 && !menu.isOpen) {
            val cols = if (prefs.singleRow) 0 else layoutManager.spanCount
            val delta = when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -cols
                KeyEvent.KEYCODE_DPAD_DOWN -> cols
                else -> 0
            }
            val done = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK
            // in one-row mode up/down do nothing while moving (the tile must not lose focus)
            if (delta == 0 && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) return true
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

    private fun lookName(v: Int) = when (v) { 0 -> "Modern"; 1 -> "Semi"; else -> "Light" }

    private fun showSettings() {
        val langName = T.languages.firstOrNull { it.first == T.lang }?.second ?: "English"
        menu.show(T.s("Settings", "Postavke", "Einstellungen"), R.drawable.lc_settings, listOf(
            GlassMenu.Item(T.s("Add apps", "Dodaj aplikacije", "Apps hinzufügen"), R.drawable.lc_plus) { showAddMenu() },
            GlassMenu.Item(T.s("All apps", "Sve aplikacije", "Alle Apps"), R.drawable.lc_layout_grid) { allAppsMenu() },
            GlassMenu.Item(T.s("Android settings", "Android postavke", "Android-Einstellungen"), R.drawable.lc_settings) {
                safeStart(Intent(Settings.ACTION_SETTINGS))
            },
            GlassMenu.Item(T.s("Language", "Jezik", "Sprache"), R.drawable.lc_languages, value = langName) { languageMenu() },
            GlassMenu.Item(T.s("Clock & widgets", "Sat i widgeti", "Uhr & Widgets", "Часы и виджеты"), R.drawable.lc_monitor) { widgetsMenu() },

            GlassMenu.header(T.s("Home screen", "Početni ekran", "Startbildschirm")),
            GlassMenu.Item(T.s("Wallpaper", "Pozadina", "Hintergrund"), R.drawable.lc_wallpaper) { wallpaperMenu() },
            GlassMenu.Item(T.s("Home layout", "Raspored početnog", "Startlayout", "Главный экран"), R.drawable.lc_columns_3) { homeLayoutMenu() },
            GlassMenu.Item(T.s("Icon appearance", "Izgled ikona", "Symbol-Aussehen", "Вид значков"), R.drawable.lc_image) { iconAppearanceMenu() },
            GlassMenu.Item(T.s("Text size", "Veličina teksta", "Textgröße"), R.drawable.lc_type, value = "${FontScale.percent(this)}%") { textSizeMenu() },
            GlassMenu.Item(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock) { clockMenu() },
            GlassMenu.Item(T.s("Weather", "Vrijeme", "Wetter"), R.drawable.lc_cloud_sun, value = prefs.city) { weatherMenu() },
            GlassMenu.Item(T.s("Screensaver and idle", "Screensaver i mirovanje", "Bildschirmschoner und Leerlauf"), R.drawable.lc_monitor) { screensaverMenu() },

            GlassMenu.header(T.s("System", "Sistem", "System", "Система")),
            GlassMenu.Item(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell) { notificationsPanel() },
            GlassMenu.Item(T.s("VPN key over other apps", "VPN ključ preko drugih aplikacija", "VPN-Schlüssel über anderen Apps"), R.drawable.lc_key_round) { overlayMenu() },
            GlassMenu.Item(T.s("Default launcher and autostart", "Zadani launcher i autostart", "Standard-Launcher und Autostart"), R.drawable.lc_house) { defaultLauncherMenu() },
            GlassMenu.Item(T.s("Auto-start apps", "Auto-start aplikacije", "Autostart-Apps"), R.drawable.lc_rocket) { autostartMenu() },
            GlassMenu.Item(T.s("Backup and restore", "Backup i vraćanje", "Sichern und wiederherstellen"), R.drawable.lc_archive) { backupMenu() },
            GlassMenu.Item(T.s("Add-ons (SofaDrop, Shizuku)", "Dodaci (SofaDrop, Shizuku)", "Erweiterungen (SofaDrop, Shizuku)"), R.drawable.lc_plus) { extrasMenu() },
            GlassMenu.Item(T.s("Power", "Napajanje", "Energie"), R.drawable.lc_power) { powerMenu() },

            GlassMenu.header(T.s("Network and tools", "Mreža i alati", "Netzwerk und Werkzeuge")),
            GlassMenu.Item(T.s("Speed test", "Test brzine", "Speedtest"), R.drawable.lc_gauge) {
                startActivity(Intent(this, SpeedTestActivity::class.java))
            },
            GlassMenu.Item("Private DNS", R.drawable.lc_shield_check, value = dnsLabel()) { dnsMenu() },
            GlassMenu.Item(T.s("Wireless debugging", "Wireless debugging", "Drahtloses Debugging"), R.drawable.lc_wifi,
                checked = adbWifiOn(), toggle = true) { setAdbWifi(it.checked) },
            GlassMenu.Item(T.s("Developer options", "Opcije za programere", "Entwickleroptionen"), R.drawable.lc_code) { openDeveloperOptions() },
            GlassMenu.Item(T.s("Android TV OS build (developer mode)", "Android TV OS build (programerski mod)", "Android TV OS Build (Entwicklermodus)"),
                R.drawable.lc_info) { openAbout() },

            GlassMenu.header(T.s("Updates", "Ažuriranja", "Updates")),
            GlassMenu.Item(T.s("App updates", "Ažuriranja aplikacija", "App-Updates"), R.drawable.lc_download,
                value = AppUpdates.list(this).size.takeIf { it > 0 }?.toString()) { appUpdatesMenu() },
            GlassMenu.Item(T.s("LEAD update", "Ažuriranje LEAD-a", "LEAD-Update"), R.drawable.lc_refresh_cw,
                value = Updater.currentName(this) + " · " + channelLabel()) { updateMenu() },
        ))
    }

    private fun channelLabel() = if (Updater.installedIsBeta(this)) "BETA" else "STABLE"

    /** Modern (SofaDrop pictures), Semi (glass tiles) or Light (floating logos on the LEAD wallpaper) */
    private fun visualLookMenu() {
        fun item(v: Int, title: String, info: String) = GlassMenu.Item(title, R.drawable.lc_image, checked = prefs.visualLook == v, value = info) {
            // the look also decides the speed: Light = fastest (no glass effects, least memory), Semi / Modern = full effects
            setPerfMode(v == 2, quiet = true)
            prefs.applyVisualLook(v)
            if (v != 0 && prefs.wpMode == 3) prefs.wpMode = 0
            reloadApps(); applyLook()
            if (v == 0) {
                if (Extras.installed(this, Extras.SOFADROP)) useSofadrop()
                else { toast(T.s("Modern needs SofaDrop – install it here", "Modern treba SofaDrop – instaliraj ga ovdje", "Modern braucht SofaDrop – hier installieren")); extrasMenu() }
            }
        }
        menu.show(T.s("Visual look", "Izgled (Visual Look)", "Aussehen (Visual Look)"), R.drawable.lc_image, listOf(
            item(2, "Light", T.s("floating logos", "logotipi lebde", "schwebende Logos")),
            item(1, "Semi", T.s("glass tiles", "staklene pločice", "Glaskacheln")),
            item(0, "Modern", T.s("SofaDrop pictures", "SofaDrop slike", "SofaDrop-Bilder")),
        ), T.s("Light is also the fastest (least memory). Saved for this profile; your apps stay as they are.",
            "Light je ujedno i najbrži (najmanje memorije). Pamti se za ovaj profil; tvoje aplikacije ostaju kako jesu.",
            "Light ist auch am schnellsten (wenigster Speicher). Wird für dieses Profil gespeichert; deine Apps bleiben, wie sie sind."))
    }

    private fun textSizeMenu() {
        val cur = FontScale.percent(this)
        menu.show(T.s("Text size", "Veličina teksta", "Textgröße"), R.drawable.lc_type, listOf(
            T.s("Small", "Mala", "Klein") to 85, T.s("Normal", "Normalna", "Normal") to 100,
            T.s("Large", "Velika", "Groß") to 115, T.s("Very large", "Vrlo velika", "Sehr groß") to 130,
        ).map { (n, v) -> GlassMenu.Item(n, checked = v == cur, value = "$v%") { if (v != cur) { FontScale.set(this, v); recreate() } } },
            T.s("All texts and menus grow together.", "Svi tekstovi i meniji rastu zajedno.", "Alle Texte und Menüs wachsen mit."))
    }

    /** a default app (PRETORIAN, Netflix …) is not on the box yet: install it from its official place */
    private fun installMissing(e: AppEntry) {
        if (DefaultApps.repo(e.pkg) != null) {
            val n = if (e.pkg == DefaultApps.TIZENTUBE) "TizenTube (YouTube)" else DefaultApps.name(e.pkg)
            confirm(e.label, R.drawable.lc_download, T.s("$n is not installed yet. Install the newest stable version from GitHub?",
                "$n još nije instaliran. Instalirati najnoviju stabilnu verziju sa GitHuba?",
                "$n ist noch nicht installiert. Neueste stabile Version von GitHub installieren?",
                "$n ещё не установлен. Установить последнюю стабильную версию с GitHub?")) {
                toast(T.s("Downloading ", "Preuzimam ", "Lade ") + n + "…")
                Extras.install(this, e.pkg, null) { m ->
                    val pct = m.removeSuffix("%").trim().toIntOrNull()
                    if (pct == null || pct % 25 == 0) toast(if (pct != null) "$n $m" else m)
                }
            }
        } else {
            confirm(e.label, R.drawable.lc_shopping_bag, T.s("${e.label} is not installed yet. Open it in Google Play?",
                "${e.label} još nije instaliran. Otvoriti ga u Google Play prodavnici?",
                "${e.label} ist noch nicht installiert. In Google Play öffnen?",
                "${e.label} ещё не установлен. Открыть в Google Play?")) { openStore(e.pkg) }
        }
    }

    /** first start: LEAD update + all apps (TizenTube, PRETORIAN … from GitHub, the rest from Play) */
    private fun firstUpdateCheck() {
        maybeCheckUpdate(0)
        Thread {
            val l = runCatching { AppUpdates.checkAll(this) }.getOrDefault(emptyList())
            if (l.isNotEmpty()) runCatching { AppUpdates.notify(this, l) }
            handler.post { showUpdateChip(animate = l.isNotEmpty()) }
        }.start()
    }

    private fun openStore(pkg: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (ex: Exception) {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    /** long press › Change icon: own picture, search the internet, a link, LEAD's built-in one, or the app's original */
    private fun iconMenu(e: AppEntry) {
        val items = mutableListOf(
            GlassMenu.Item(T.s("Own picture from the box / USB", "Vlastita slika s diska / USB-a", "Eigenes Bild von der Box / USB"), R.drawable.lc_image) {
                pickImage { f -> f.copyTo(IconStore.file(this, e.pkg), true); reloadApps() }
            },
            GlassMenu.Item(T.s("Search the internet (official icon)", "Potraži na internetu (službena ikona)", "Im Internet suchen (offizielles Symbol)"), R.drawable.lc_globe) {
                searchIcon(e)
            },
            GlassMenu.Item(T.s("From a link (URL)", "Sa linka (URL)", "Von einem Link (URL)"), R.drawable.lc_link) {
                askText(T.s("Icon image URL", "Link slike za ikonu", "Bild-URL für das Symbol"), "https://") { url ->
                    download(url, IconStore.file(this, e.pkg)) { reloadApps() }
                }
            },
        )
        if (BuiltinIcons.has(e.pkg)) items.add(GlassMenu.Item(T.s("LEAD icon", "LEAD ikona", "LEAD-Symbol"), R.drawable.lc_layers) {
            BuiltinIcons.restore(this, e.pkg); reloadApps()
        })
        items.add(GlassMenu.Item(T.s("The app's own icon", "Originalna ikona aplikacije", "Originalsymbol der App"), R.drawable.lc_rotate_ccw) {
            IconStore.file(this, e.pkg).delete(); reloadApps()
        })
        menu.show(T.s("Change icon", "Promijeni ikonu", "Symbol ändern") + " – " + e.label, R.drawable.lc_image, items)
    }

    /** takes the app's official icon from its Google Play page */
    private fun searchIcon(e: AppEntry) {
        if (e.pkg == AppEntry.FILES) return
        toast(T.s("Searching…", "Tražim…", "Suche…"))
        Thread {
            val url = runCatching {
                val html = Http.text("https://play.google.com/store/apps/details?id=${e.pkg}&hl=en")
                Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    ?.substringBefore('=')?.plus("=w512")
            }.getOrNull()
            handler.post {
                if (url.isNullOrBlank()) toast(T.s("No icon found – try a picture or a link", "Nema ikone – probaj sliku ili link", "Kein Symbol gefunden – Bild oder Link probieren"))
                else download(url, IconStore.file(this, e.pkg)) { reloadApps() }
            }
        }.start()
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

    /** Projectivy wallpaper plugins installed on the box (SofaDrop, Bing, Overflight …). */
    private fun pluginMenu() {
        val list = Plugins.list(this)
        if (list.isEmpty()) {
            menu.message(T.s("Wallpaper plugins", "Pluginovi za pozadinu", "Hintergrund-Plugins"), R.drawable.lc_rocket,
                T.s("No wallpaper plugin is installed. Install one (e.g. SofaDrop) and come back.",
                    "Nijedan plugin za pozadinu nije instaliran. Instaliraj ga (npr. SofaDrop) pa se vrati.",
                    "Kein Hintergrund-Plugin installiert. Eines installieren (z. B. SofaDrop) und zurückkommen."))
            return
        }
        val items = ArrayList<GlassMenu.Item>()
        for (p in list) {
            items.add(GlassMenu.Item(p.name + " " + T.s("on", "uključen", "an"), R.drawable.lc_play,
                checked = prefs.wpMode == 3 && prefs.wpPlugin == p.id, toggle = true) {
                if (it.checked) {
                    prefs.wpPlugin = p.id; prefs.wpMode = 3; moviesKey = ""   // replaces TMDB / another plugin
                    toast(T.s("Loading pictures from ", "Učitavam slike iz ", "Lade Bilder von ") + p.name + "…")
                    loadWallpaper()
                    menu.close()
                } else if (prefs.wpMode == 3 && prefs.wpPlugin == p.id) {
                    prefs.wpMode = 0; handler.removeCallbacks(rotate); showMovieInfo(null); loadWallpaper()
                }
            })
            items.add(GlassMenu.Item(p.name + " – " + T.s("settings", "postavke", "Einstellungen"), R.drawable.lc_settings) {
                if (!Plugins.openSettings(this, p)) toast(T.s("This plugin has no settings", "Ovaj plugin nema postavke", "Dieses Plugin hat keine Einstellungen"))
            })
        }
        if (prefs.wpMode == 3) items.add(GlassMenu.Item(T.s("Load new pictures now", "Učitaj nove slike sad", "Jetzt neue Bilder laden"), R.drawable.lc_refresh_cw) {
            moviesKey = ""; loadWallpaper()
        })
        menu.show(T.s("Wallpaper plugins", "Pluginovi za pozadinu", "Hintergrund-Plugins"), R.drawable.lc_rocket, items,
            T.s("Plugins made for Projectivy Launcher work here too. Set them up in their own settings.",
                "Pluginovi napravljeni za Projectivy Launcher rade i ovdje. Podešavaju se u njihovim postavkama.",
                "Plugins für Projectivy Launcher funktionieren auch hier. Eingestellt werden sie in ihren eigenen Einstellungen."))
    }

    private fun wallpaperMenu() {
        val modes = listOf(
            T.s("One picture", "Jedna slika", "Ein Bild") to 0,
            T.s("Slideshow from a folder", "Slideshow iz foldera", "Diashow aus Ordner") to 1,
            T.s("Films and series (changing)", "Filmovi i serije (mijenjaju se)", "Filme und Serien (wechselnd)") to 2,
            T.s("Plugin (e.g. SofaDrop)", "Plugin (npr. SofaDrop)", "Plugin (z. B. SofaDrop)") to 3)
        menu.show(T.s("Wallpaper", "Pozadina", "Hintergrund"), R.drawable.lc_wallpaper, listOf(
            GlassMenu.Item(T.s("Wallpaper type", "Vrsta pozadine", "Hintergrundart"), R.drawable.lc_layers,
                value = modes.first { it.second == prefs.wpMode }.first) {
                choose(T.s("Wallpaper type", "Vrsta pozadine", "Hintergrundart"), R.drawable.lc_layers, modes, prefs.wpMode) { m ->
                    when (m) {
                        1 -> if (prefs.wpFolder.isBlank()) pickFolder() else { prefs.wpMode = 1; loadWallpaper() }
                        2 -> if (prefs.tmdbKey.isBlank()) askTmdbKey(thenStart = true) else { prefs.wpMode = 2; loadWallpaper() }
                        3 -> pluginMenu()
                        else -> { prefs.wpMode = 0; loadWallpaper() }
                    }
                }
            },
            GlassMenu.Item(T.s("Films and series…", "Filmovi i serije…", "Filme und Serien…"), R.drawable.lc_tv) { moviesMenu() },
            GlassMenu.Item(T.s("Wallpaper plugins…", "Pluginovi za pozadinu…", "Hintergrund-Plugins…"), R.drawable.lc_rocket,
                value = Plugins.find(this, prefs.wpPlugin)?.name ?: "–") { pluginMenu() },
            GlassMenu.Item(T.s("Slideshow folder", "Folder za slideshow", "Diashow-Ordner"), R.drawable.lc_folder,
                value = prefs.wpFolder.substringAfterLast('/').ifBlank { "–" }) { pickFolder() },
            GlassMenu.Item(T.s("Change every", "Mijenjaj svakih", "Wechseln alle"), R.drawable.lc_clock, value = intervalLabel(prefs.wpInterval)) {
                choose(T.s("Change every", "Mijenjaj svakih", "Wechseln alle"), R.drawable.lc_clock,
                    listOf(15, 30, 60, 120, 300, 900, 1800).map { intervalLabel(it) to it }, prefs.wpInterval) {
                    prefs.wpInterval = it; if (prefs.wpMode != 0) startRotation()
                }
            },
            GlassMenu.Item(T.s("Choose a picture", "Izaberi sliku", "Bild auswählen"), R.drawable.lc_image) { pickImage { prefs.wpMode = 0; setWallpaper(it) } },
            GlassMenu.Item(T.s("From the internet (URL)", "S interneta (link)", "Aus dem Internet (URL)"), R.drawable.lc_link) {
                askText(T.s("Wallpaper image URL", "Link slike za pozadinu", "Bild-URL für den Hintergrund"), "https://") { url ->
                    download(url, wallpaperFile) { prefs.wpMode = 0; applyLook() }
                }
            },
            GlassMenu.Item(T.s("LEAD wallpaper", "LEAD pozadina", "LEAD-Hintergrund"), R.drawable.lc_wallpaper,
                value = leadWpNames()[prefs.leadWp.coerceIn(0, 4)]) {
                choose(T.s("LEAD wallpaper", "LEAD pozadina", "LEAD-Hintergrund"), R.drawable.lc_wallpaper,
                    leadWpNames().mapIndexed { i, n -> n to i }, prefs.leadWp) {
                    prefs.leadWp = it; wallpaperFile.delete(); prefs.wpMode = 0; applyLook()
                }
            },
            GlassMenu.Item(T.s("Darker at night (20–7 h)", "Noću tamnije (20–7 h)", "Nachts dunkler (20–7 Uhr)"), R.drawable.lc_moon,
                checked = prefs.nightDim, toggle = true) { prefs.nightDim = it.checked; applyDim() },
            GlassMenu.Item(T.s("Softer logos and clock at night", "Noću blaži logotipi i sat", "Nachts sanftere Logos und Uhr"), R.drawable.lc_moon,
                checked = prefs.nightSoft, toggle = true) { prefs.nightSoft = it.checked; applyDim() },
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

    private fun intervalLabel(sec: Int) = if (sec < 60) "$sec s" else "${sec / 60} min"

    private fun pickFolder() {
        pendingPick = { f -> prefs.wpFolder = f.absolutePath; prefs.wpMode = 1; loadWallpaper() }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, FileBrowserActivity::class.java)
            .putExtra(FileBrowserActivity.EXTRA_PICK, "folder"), REQ_FILES)
    }

    private fun askTmdbKey(thenStart: Boolean) {
        menu.show("TMDB", R.drawable.lc_tv, listOf(
            GlassMenu.Item(T.s("Enter TMDB key", "Upiši TMDB ključ", "TMDB-Schlüssel eingeben"), R.drawable.lc_key_round) {
                askText(T.s("TMDB API key", "TMDB API ključ", "TMDB-API-Schlüssel"), prefs.tmdbKey) { k ->
                    prefs.tmdbKey = k.trim()
                    if (thenStart && k.isNotBlank()) { prefs.wpMode = 2; moviesKey = ""; loadWallpaper() }
                }
            },
        ), T.s("Film pictures come from TheMovieDB (free). Make an account on themoviedb.org → Settings → API, copy the \"API Key\" and enter it here once.",
            "Slike filmova dolaze s TheMovieDB (besplatno). Napravi račun na themoviedb.org → Settings → API, kopiraj \"API Key\" i upiši ga ovdje jednom.",
            "Filmbilder kommen von TheMovieDB (kostenlos). Konto auf themoviedb.org anlegen → Settings → API, den \"API Key\" kopieren und hier einmal eintragen."))
    }

    private fun moviesMenu() {
        val types = listOf(T.s("Films and series", "Filmovi i serije", "Filme und Serien") to "all",
            T.s("Only films", "Samo filmovi", "Nur Filme") to "movie", T.s("Only series", "Samo serije", "Nur Serien") to "tv")
        menu.show(T.s("Films and series", "Filmovi i serije", "Filme und Serien"), R.drawable.lc_tv, listOf(
            GlassMenu.Item(T.s("Films & series (TMDB) on", "Filmovi i serije (TMDB) uključeno", "Filme & Serien (TMDB) an"), R.drawable.lc_play,
                checked = prefs.wpMode == 2, toggle = true) {
                if (it.checked) {
                    if (prefs.tmdbKey.isBlank()) { it.checked = false; askTmdbKey(thenStart = true) }
                    else { prefs.wpMode = 2; moviesKey = ""; loadWallpaper() }   // replaces a plugin, if one was on
                } else if (prefs.wpMode == 2) { prefs.wpMode = 0; handler.removeCallbacks(rotate); showMovieInfo(null); loadWallpaper() }
            },
            GlassMenu.Item(T.s("Show", "Prikazuj", "Anzeigen"), R.drawable.lc_tv, value = types.first { it.second == prefs.tmdbType }.first) {
                menu.show(T.s("Show", "Prikazuj", "Anzeigen"), R.drawable.lc_tv, types.map { (n, v) ->
                    GlassMenu.Item(n, checked = v == prefs.tmdbType) { prefs.tmdbType = v; moviesKey = ""; if (prefs.wpMode == 2) loadWallpaper() }
                })
            },
            GlassMenu.Item(T.s("Which titles", "Koji naslovi", "Welche Titel"), R.drawable.lc_rocket,
                value = if (prefs.tmdbList == "new") T.s("New & coming soon", "Novo i uskoro", "Neu & demnächst")
                        else T.s("Popular this week", "Popularno ove sedmice", "Beliebt diese Woche")) {
                choose(T.s("Which titles", "Koji naslovi", "Welche Titel"), R.drawable.lc_rocket, listOf(
                    T.s("New & coming soon (cinema now, upcoming, series on air)", "Novo i uskoro (sad u kinu, uskoro, serije koje se emituju)",
                        "Neu & demnächst (jetzt im Kino, bald, laufende Serien)") to 0,
                    T.s("Popular this week", "Popularno ove sedmice", "Beliebt diese Woche") to 1),
                    if (prefs.tmdbList == "new") 0 else 1) {
                    prefs.tmdbList = if (it == 0) "new" else "trending"; moviesKey = ""; if (prefs.wpMode == 2) loadWallpaper()
                }
            },
            GlassMenu.Item(T.s("Title and description", "Naziv i opis", "Titel und Beschreibung"), R.drawable.lc_type,
                checked = prefs.showMovieInfo, toggle = true) { prefs.showMovieInfo = it.checked; if (!it.checked) showMovieInfo(null) },
            GlassMenu.Item(T.s("TMDB key", "TMDB ključ", "TMDB-Schlüssel"), R.drawable.lc_key_round,
                value = if (prefs.tmdbKey.isBlank()) "–" else "••••" + prefs.tmdbKey.takeLast(4)) { askTmdbKey(thenStart = false) },
        ), T.s("This product uses the TMDB API but is not endorsed or certified by TMDB.",
            "Koristi TMDB API, ali nije odobren ni certificiran od strane TMDB-a.",
            "Nutzt die TMDB-API, ist aber nicht von TMDB unterstützt oder zertifiziert."))
    }

    private fun lookMenu() = tilesMenu()

    private fun relayout() { layoutGrid(); adaptStatus(); invalidateGlass() }

    private fun tilesMenu() {
        val presets = listOf(
            T.s("Banner (wide logos)", "Banner (široki logo)", "Banner (breite Logos)") to 0,
            T.s("Dock (Apple look)", "Dock (Apple izgled)", "Dock (Apple-Look)") to 1,
            "White Snow" to 2,
            T.s("Classic (square)", "Klasično (kvadrat)", "Klassisch (Quadrat)") to 3)
        val borders = listOf(T.s("No border", "Bez okvira", "Kein Rahmen") to 0,
            T.s("Thin line", "Tanka linija", "Dünne Linie") to 1, T.s("Glass rim", "Stakleni rub", "Glasrand") to 2)
        val sizes = listOf("S" to 0, "M" to 1, "L" to 2, "XL" to 3)
        fun pct(v: Int) = "$v%"
        menu.show(T.s("Icons and tiles", "Ikone i pločice", "Symbole und Kacheln"), R.drawable.lc_layout_grid, listOf(
            GlassMenu.Item(T.s("Ready-made style", "Gotov stil", "Fertiger Stil"), R.drawable.lc_layers) {
                choose(T.s("Ready-made style", "Gotov stil", "Fertiger Stil"), R.drawable.lc_layers, presets, -1) {
                    prefs.applyPreset(it); reloadApps(); applyLook()
                }
            },
            GlassMenu.Item(T.s("Tile shape", "Oblik pločice", "Kachelform"), R.drawable.lc_columns_3,
                value = if (prefs.tileShape == 0) T.s("Wide", "Široka", "Breit") else T.s("Square", "Kvadrat", "Quadrat")) {
                choose(T.s("Tile shape", "Oblik pločice", "Kachelform"), R.drawable.lc_columns_3, listOf(
                    T.s("Wide 16:9 (banner)", "Široka 16:9 (banner)", "Breit 16:9 (Banner)") to 0,
                    T.s("Square", "Kvadrat", "Quadrat") to 1), prefs.tileShape) { prefs.tileShape = it; reloadApps() }
            },
            GlassMenu.Item(T.s("White logos (White Snow)", "Bijeli logotipi (White Snow)", "Weiße Logos (White Snow)"), R.drawable.lc_image,
                checked = prefs.logoWhite, toggle = true) { prefs.logoWhite = it.checked; reloadApps() },
            GlassMenu.Item(T.s("Remove logo background", "Ukloni pozadinu logotipa", "Logo-Hintergrund entfernen"), R.drawable.lc_eye,
                checked = prefs.logoNoBg, toggle = true) { prefs.logoNoBg = it.checked; reloadApps() },
            GlassMenu.Item(T.s("Tile background", "Providnost pozadine", "Kachelhintergrund"), R.drawable.lc_layers, value = pct(prefs.tileAlpha)) {
                choose(T.s("Tile background", "Providnost pozadine", "Kachelhintergrund"), R.drawable.lc_layers,
                    listOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 70, 100).map { pct(it) to it }, prefs.tileAlpha) { prefs.tileAlpha = it; relayout() }
            },
            GlassMenu.Item(T.s("Blur behind tiles", "Zamućenje iza pločica", "Unschärfe hinter Kacheln"), R.drawable.lc_layers,
                checked = prefs.tileFrost, toggle = true) { prefs.tileFrost = it.checked; relayout() },
            GlassMenu.Item(T.s("Clean glass (frame only on the selected one)", "Čisto staklo (okvir samo na odabranoj)", "Klares Glas (Rahmen nur bei Auswahl)"),
                R.drawable.lc_layers, checked = prefs.tileClean, toggle = true) { prefs.tileClean = it.checked; relayout() },
            GlassMenu.Item(T.s("Border", "Okvir", "Rahmen"), R.drawable.lc_layout_grid, value = borders.first { it.second == prefs.tileBorder }.first) {
                choose(T.s("Border", "Okvir", "Rahmen"), R.drawable.lc_layout_grid, borders, prefs.tileBorder) { prefs.tileBorder = it; relayout() }
            },
            GlassMenu.Item(T.s("Corners", "Uglovi", "Ecken"), R.drawable.lc_layout_grid, value = pct(prefs.tileCorner)) {
                choose(T.s("Corners", "Uglovi", "Ecken"), R.drawable.lc_layout_grid, listOf(
                    T.s("Square", "Kvadratni", "Eckig") to 0, T.s("Slightly round", "Blago zaobljeni", "Leicht rund") to 8,
                    T.s("Round", "Zaobljeni", "Rund") to 16, T.s("Very round", "Jako zaobljeni", "Sehr rund") to 28,
                    T.s("Capsule", "Kapsula", "Kapsel") to 50), prefs.tileCorner) { prefs.tileCorner = it; relayout() }
            },
            GlassMenu.Item(T.s("Size", "Veličina", "Größe"), R.drawable.lc_columns_3, value = sizes[prefs.tileSize.coerceIn(0, 3)].first) {
                choose(T.s("Size", "Veličina", "Größe"), R.drawable.lc_columns_3, sizes, prefs.tileSize) { prefs.tileSize = it; reloadApps() }
            },
            GlassMenu.Item(T.s("Spacing", "Razmak", "Abstand"), R.drawable.lc_columns_3, value = "${prefs.tileGap} dp") {
                choose(T.s("Spacing", "Razmak", "Abstand"), R.drawable.lc_columns_3,
                    listOf(0, 6, 12, 18, 24, 32, 40).map { "$it dp" to it }, prefs.tileGap) { prefs.tileGap = it; relayout() }
            },
            GlassMenu.Item(T.s("Logo in tile", "Logo u pločici", "Logo in der Kachel"), R.drawable.lc_image,
                value = if (prefs.logoFit == 0) "FIT" else "STRETCH") {
                choose(T.s("Logo in tile", "Logo u pločici", "Logo in der Kachel"), R.drawable.lc_image, listOf(
                    T.s("FIT – whole logo visible", "FIT – vidi se cijeli logo", "FIT – ganzes Logo") to 0,
                    T.s("STRETCH – fills the tile", "STRETCH – popuni pločicu", "STRETCH – füllt die Kachel") to 1), prefs.logoFit) {
                    prefs.logoFit = it; relayout()
                }
            },
            GlassMenu.Item(T.s("Frame around the selected icon", "Okvir oko odabrane ikone", "Rahmen um das ausgewählte Symbol"), R.drawable.lc_layout_grid,
                checked = prefs.focusFrame, toggle = true) { prefs.focusFrame = it.checked; relayout() },
            GlassMenu.Item(T.s("Name under the selected icon", "Ime ispod odabrane ikone", "Name unter dem ausgewählten Symbol"), R.drawable.lc_type,
                checked = prefs.nameOnFocus, toggle = true) { prefs.nameOnFocus = it.checked; relayout() },
            GlassMenu.Item(T.s("Number keys 1–9 open apps", "Tipke 1–9 otvaraju aplikacije", "Zifferntasten 1–9 öffnen Apps"), R.drawable.lc_layout_grid,
                checked = prefs.numberKeys, toggle = true) { prefs.numberKeys = it.checked },
            GlassMenu.Item(T.s("Focus zoom", "Fokus uvećanje", "Fokus-Zoom"), R.drawable.lc_eye, value = "+${prefs.focusScale}%") {
                choose(T.s("Focus zoom", "Fokus uvećanje", "Fokus-Zoom"), R.drawable.lc_eye,
                    listOf(0, 10, 20, 30, 40, 50).map { "+$it%" to it }, prefs.focusScale) { prefs.focusScale = it; relayout() }
            },
            GlassMenu.Item(T.s("One row (scrolls left/right)", "Jedan red (skrola lijevo/desno)", "Eine Reihe (scrollt links/rechts)"),
                R.drawable.lc_columns_3, checked = prefs.singleRow, toggle = true) { prefs.singleRow = it.checked; relayout() },
            GlassMenu.Item(T.s("Show + tile", "Prikaži + pločicu", "+ Kachel anzeigen"), R.drawable.lc_plus,
                checked = prefs.showAddTile, toggle = true) { prefs.showAddTile = it.checked; reloadApps() },
            GlassMenu.Item(T.s("Dock (glass bar behind the apps)", "Dock (staklena traka iza aplikacija)", "Dock (Glasleiste hinter den Apps)"),
                R.drawable.lc_layers, checked = prefs.dock, toggle = true) { prefs.dock = it.checked; relayout() },
            GlassMenu.Item(T.s("App names", "Nazivi aplikacija", "App-Namen"), R.drawable.lc_type,
                checked = prefs.showLabels, toggle = true) { prefs.showLabels = it.checked; relayout() },
            GlassMenu.Item(T.s("Icon style (apps without banner)", "Stil ikone (aplikacije bez bannera)", "Symbolstil (Apps ohne Banner)"), R.drawable.lc_image) {
                choose(T.s("Icon style", "Stil ikona", "Symbolstil"), R.drawable.lc_image, listOf(
                    T.s("Original", "Original", "Original") to 0,
                    T.s("Transparent (no background)", "Providne (bez pozadine)", "Transparent (ohne Hintergrund)") to 1,
                    T.s("White glass", "Bijelo staklo", "Weißes Glas") to 2), prefs.iconStyle) { prefs.iconStyle = it; reloadApps() }
            },
            GlassMenu.Item(T.s("Position – up / down", "Pozicija – gore / dole", "Position – oben / unten"), R.drawable.lc_move,
                value = listOf(T.s("Top", "Gore", "Oben"), T.s("Middle", "Sredina", "Mitte"), T.s("Bottom", "Dolje", "Unten"))[prefs.gridPosition.coerceIn(0, 2)]) {
                choose(T.s("Position", "Pozicija", "Position"), R.drawable.lc_move, listOf(
                    T.s("Top", "Gore", "Oben") to 0, T.s("Middle", "Sredina", "Mitte") to 1, T.s("Bottom", "Dolje", "Unten") to 2),
                    prefs.gridPosition) { prefs.gridPosition = it; applyLook() }
            },
            GlassMenu.Item(T.s("Position – left / right", "Pozicija – lijevo / desno", "Position – links / rechts"), R.drawable.lc_move,
                value = listOf(T.s("Left", "Lijevo", "Links"), T.s("Center", "Sredina", "Mitte"), T.s("Right", "Desno", "Rechts"))[prefs.gridAlign.coerceIn(0, 2)]) {
                choose(T.s("Position", "Pozicija", "Position"), R.drawable.lc_move, listOf(
                    T.s("Left", "Lijevo", "Links") to 0, T.s("Center", "Sredina", "Mitte") to 1, T.s("Right", "Desno", "Rechts") to 2),
                    prefs.gridAlign) { prefs.gridAlign = it; relayout() }
            },
            GlassMenu.Item(T.s("Fine position – up / down (+/-)", "Fino pomjeranje – gore / dole (+/-)", "Feinposition – oben / unten (+/-)"),
                R.drawable.lc_move, value = "${prefs.gridOffY} dp") { nudgeMenu(true) },
            GlassMenu.Item(T.s("Fine position – left / right (+/-)", "Fino pomjeranje – lijevo / desno (+/-)", "Feinposition – links / rechts (+/-)"),
                R.drawable.lc_move, value = "${prefs.gridOffX} dp") { nudgeMenu(false) },
        ))
    }

    private fun statusMenu() {
        fun t(text: String, icon: Int, get: Boolean, set: (Boolean) -> Unit) =
            GlassMenu.Item(text, icon, checked = get, toggle = true) { set(it.checked); applyLook() }
        menu.show(T.s("Status bar", "Statusna traka", "Statusleiste"), R.drawable.lc_bell_ring, listOf(
            t(T.s("Show status bar", "Prikaži statusnu traku", "Statusleiste anzeigen"), R.drawable.lc_eye, prefs.showStatusBar) { prefs.showStatusBar = it },
            GlassMenu.Item(T.s("When it shows", "Način prikaza", "Wann sie erscheint"), R.drawable.lc_layout_grid, value = statusModeName(prefs.statusMode)) {
                choose(T.s("When it shows", "Način prikaza", "Wann sie erscheint"), R.drawable.lc_layout_grid,
                    (0..2).map { statusModeName(it) to it }, prefs.statusMode) { prefs.statusMode = it; setReveal(false); applyLook() }
            },
            t(T.s("Icons only (no numbers)", "Samo ikone (bez brojeva)", "Nur Symbole (ohne Zahlen)"), R.drawable.lc_image, prefs.statusIconsOnly) { prefs.statusIconsOnly = it },
            GlassMenu.header(T.s("Top of the screen", "Vrh ekrana", "Oben am Bildschirm")),
            GlassMenu.Item(T.s("Buttons show", "Dugmad se prikazuju", "Knöpfe erscheinen"), R.drawable.lc_eye, value = statusModeName(prefs.buttonsMode)) {
                choose(T.s("Buttons show", "Dugmad se prikazuju", "Knöpfe erscheinen"), R.drawable.lc_eye,
                    (0..2).map { statusModeName(it) to it }, prefs.buttonsMode) { prefs.buttonsMode = it; setReveal(false); showPill() }
            },
            GlassMenu.Item(T.s("Clock and date show", "Sat i datum se prikazuju", "Uhr und Datum erscheinen"), R.drawable.lc_clock, value = statusModeName(prefs.clockMode)) {
                choose(T.s("Clock and date show", "Sat i datum se prikazuju", "Uhr und Datum erscheinen"), R.drawable.lc_clock,
                    (0..2).map { statusModeName(it) to it }, prefs.clockMode) { prefs.clockMode = it; setReveal(false); showPill() }
            },
            t(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock, prefs.showClock) { prefs.showClock = it },
            t(T.s("Date", "Datum", "Datum"), R.drawable.lc_type, prefs.showDate) { prefs.showDate = it },
            t(T.s("Profile", "Profil", "Profil"), R.drawable.lc_user, prefs.showProfileBtn) { prefs.showProfileBtn = it },
            t(T.s("Settings", "Postavke", "Einstellungen"), R.drawable.lc_settings, prefs.showSettingsBtn) { prefs.showSettingsBtn = it },
            t(T.s("Power", "Napajanje", "Energie"), R.drawable.lc_power, prefs.showPowerBtn) { prefs.showPowerBtn = it },
            t(T.s("VPN key", "VPN ključ", "VPN-Schlüssel"), R.drawable.lc_key_round, prefs.showVpn) { prefs.showVpn = it },
            GlassMenu.Item(T.s("Status bar transparency", "Providnost statusne trake", "Transparenz der Statusleiste"), R.drawable.lc_layers,
                value = "${prefs.statusClear}%") {
                choose(T.s("Status bar transparency (0 % = solid, 100 % = clear)", "Providnost statusne trake (0 % = puna, 100 % = providna)",
                    "Transparenz der Statusleiste (0 % = deckend, 100 % = klar)"), R.drawable.lc_layers,
                    (0..100 step 10).map { "$it%" to it }, prefs.statusClear) { prefs.statusClear = it; adaptStatus() }
            },
            t(T.s("Same glass as the app tiles", "Isto staklo kao pločice", "Gleiches Glas wie die Kacheln"),
                R.drawable.lc_layers, prefs.statusLikeTiles) { prefs.statusLikeTiles = it },
            t(T.s("Adaptive (follows the wallpaper)", "Adaptivno (prati pozadinu)", "Adaptiv (passt sich dem Hintergrund an)"),
                R.drawable.lc_sun_dim, prefs.statusAdaptive) { prefs.statusAdaptive = it },
            t(T.s("Weather", "Vrijeme", "Wetter"), R.drawable.lc_cloud_sun, prefs.showWeather) { prefs.showWeather = it; lastWeather = 0 },
            t("Wi-Fi / LAN", R.drawable.lc_wifi, prefs.showNetwork) { prefs.showNetwork = it },
            t("Bluetooth", R.drawable.lc_bluetooth, prefs.showBluetooth) { prefs.showBluetooth = it },
            t(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell, prefs.showNotifs) { prefs.showNotifs = it },
            t("CPU %", R.drawable.lc_cpu, prefs.showCpu) { prefs.showCpu = it },
            t("RAM %", R.drawable.lc_memory_stick, prefs.showRam) { prefs.showRam = it },
            t(T.s("Storage %", "Memorija (disk) %", "Speicher %"), R.drawable.lc_hard_drive, prefs.showDisk) { prefs.showDisk = it },
        ))
    }

    private fun statusModeName(m: Int) = when (m) {
        0 -> T.s("Always", "Uvijek", "Immer")
        1 -> T.s("Auto-hide (at the top)", "Auto-sakrivanje (kad odeš gore)", "Automatisch (oben)")
        else -> T.s("On demand (Up at the top)", "Na zahtjev (još jednom gore)", "Auf Wunsch (oben nochmal hoch)")
    }

    // ------------------------------------------------------------------ status bar that hides itself

    private var pillWanted = false
    private var statusHandle: View? = null

    private fun inTopRow(v: View?): Boolean {
        if (v == null) return false
        if (v === profileBtn || v === settingsBtn || v === powerBtn || v === updateBtn || v.id == R.id.vpnKey ||
            v === clock || v === bellBadge) return true
        var p = v.parent
        while (p != null) { if (p === statusPill) return true; p = p.parent }
        return false
    }

    private fun setupAutoHide() {
        val d = resources.displayMetrics.density
        statusHandle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 3 * d; setColor(Color.argb(150, 255, 255, 255)) }
        }.also { root.addView(it, FrameLayout.LayoutParams((40 * d).toInt(), (4 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (8 * d).toInt() }) }
        statusPill.radiusDp = 12f   // sharper: less of a pill
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (menu.isOpen) return@addOnGlobalFocusChangeListener
            val top = inTopRow(now)
            autoReveal = top
            if (!top) demandReveal = false
            showPill()
        }
        val key = findViewById<ImageView>(R.id.vpnKey)
        key.isFocusable = true; key.isClickable = true
        if (Build.VERSION.SDK_INT >= 26) key.defaultFocusHighlightEnabled = false
        key.setOnFocusChangeListener { v, has -> v.background = if (has) GlassDrawable(d, 12, true, 10f) else null }
        key.setOnClickListener { vpnPanel() }
    }

    /** focus is in the top row (buttons, status bar, clock) */
    private var autoReveal = false
    /** Up was pressed once more at the top (on demand) or from the middle of the screen */
    private var demandReveal = false

    private fun setReveal(on: Boolean) {
        if (!on) { autoReveal = false; demandReveal = false } else demandReveal = true
        showPill()
    }

    private fun shownBy(mode: Int) = mode == 0 || (mode == 1 && (autoReveal || demandReveal)) || (mode == 2 && demandReveal)

    /** shows / hides the status bar and – with its own setting – the clock and date (buttons and VPN key always stay) */
    private fun showPill() {
        val d = resources.displayMetrics.density
        val dur = if (Backdrop.lite) 0L else 220L
        fun fade(v: View, show: Boolean, still: () -> Boolean) {
            if (show && (v.visibility != View.VISIBLE || v.alpha < 1f)) {
                if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.translationY = -14 * d }
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).translationY(0f).setDuration(dur).withEndAction { v.invalidate() }.start()
            } else if (!show && v.visibility == View.VISIBLE) {
                v.animate().alpha(0f).translationY(-14 * d).setDuration(dur).withEndAction { if (!still()) v.visibility = View.INVISIBLE }.start()
            }
        }
        (clock.parent as? View)?.let { col -> fade(col, shownBy(prefs.clockMode)) { shownBy(prefs.clockMode) } }
        // round buttons, VPN key and bell: each can be switched off; all follow "When the buttons show"
        val btnShow = shownBy(prefs.buttonsMode)
        for ((v, want) in listOf(profileBtn to prefs.showProfileBtn, settingsBtn to prefs.showSettingsBtn, powerBtn to prefs.showPowerBtn,
                findViewById<View>(R.id.vpnKey) to prefs.showVpn, (bellBadge ?: profileBtn) to (bellBadge != null && bellCount() > 0))) {
            if (v === profileBtn && bellBadge == null && want == false) continue
            if (!want) { v.animate().cancel(); v.visibility = View.GONE } else fade(v, btnShow) { shownBy(prefs.buttonsMode) }
        }
        if (!pillWanted) { statusPill.visibility = View.GONE; statusHandle?.visibility = View.GONE; return }
        val show = shownBy(prefs.statusMode)
        statusHandle?.visibility = if (show) View.GONE else View.VISIBLE
        fade(statusPill, show) { shownBy(prefs.statusMode) }
    }


    // ------------------------------------------------------------------ 9.1: bell number, profiles, weather, safety

    /** round bell in the top row with the number of notifications + updates; hidden when there is nothing */
    private var bellBadge: GlassFrame? = null
    private var bellText: TextView? = null

    private fun setupTopExtras() {
        val d = resources.displayMetrics.density
        val row = profileBtn.parent as? LinearLayout ?: return
        val badge = GlassFrame(this).apply {
            radiusDp = 21f; isFocusable = true; isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            visibility = View.GONE
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), 0, (14 * d).toInt(), 0)
        }
        inner.addView(ImageView(this).apply { setImageResource(R.drawable.lc_bell) }, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
        val txt = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.medium(this@MainActivity)
            setPadding((6 * d).toInt(), 0, 0, 0)
        }
        inner.addView(txt)
        badge.addView(inner, FrameLayout.LayoutParams(-2, -1))
        badge.setOnFocusChangeListener { v, has ->
            (v as GlassFrame).focusedLook = has
            v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(if (Backdrop.lite) 0L else 140L).start()
        }
        badge.setOnClickListener { notificationsPanel() }
        row.addView(badge, row.indexOfChild(findViewById(R.id.vpnKey)), LinearLayout.LayoutParams(-2, (42 * d).toInt()).apply { marginStart = (10 * d).toInt() })
        bellBadge = badge; bellText = txt
        // OK on the clock = clock settings; long press on the weather = city
        clock.isFocusable = true; clock.isClickable = true
        if (Build.VERSION.SDK_INT >= 26) clock.defaultFocusHighlightEnabled = false
        clock.setOnFocusChangeListener { v, has -> v.background = if (has) GlassDrawable(d, 14, true, 12f) else null }
        clock.setOnClickListener { clockMenu() }
        clock.setOnLongClickListener { clockMenu(); true }
        findViewById<View>(R.id.weatherBox).setOnLongClickListener { weatherMenu(); true }
        updateBell()
    }

    /** notifications + LEAD update + app updates */
    private fun bellCount(): Int {
        val n = if (NotifListener.connected) NotifListener.current().size else 0
        val upd = Updater.notifyOn(this)
        val lead = if (upd && Updater.pending(this) != null) 1 else 0
        val appsN = if (upd && prefs.appUpdates) AppUpdates.list(this).size else 0
        return n + lead + appsN
    }

    private var lastBell = -1

    /** the small line at the top glows a few times – quiet sign that something new came */
    private fun glowHandle() {
        val h = statusHandle ?: return
        if (h.visibility != View.VISIBLE || Backdrop.lite) return
        h.animate().cancel()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700; repeatCount = 5; repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { a -> val f = a.animatedValue as Float; h.scaleX = 1f + 0.6f * f; h.alpha = 0.6f + 0.4f * f }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { h.scaleX = 1f; h.alpha = 1f }
            })
            start()
        }
    }

    private fun updateBell() {
        updateNavBadge()
        val b = bellBadge ?: return
        val c = bellCount()
        if (lastBell >= 0 && c > lastBell) glowHandle()
        lastBell = c
        showPill()
        bellText?.text = c.toString()
    }

    private fun applyDim() {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = prefs.nightDim && (h >= 20 || h < 7)
        val v = (prefs.dim + if (night) 30 else 0).coerceAtMost(75)
        dim.setBackgroundColor(Color.argb(v * 255 / 100, 0, 0, 0))
        // evening and night: logos and clock a little dimmer, so they don't dazzle in a dark room
        val soft = prefs.nightSoft && (h >= 20 || h < 7)
        val a = if (soft) 0.72f else 1f
        grid.alpha = a; clock.alpha = a; date.alpha = a
    }

    private fun leadWpNames() = listOf(T.s("Living room (v10)", "Dnevna soba (v10)", "Wohnzimmer (v10)", "Гостиная (v10)"),
        T.s("Blue glass", "Plavo staklo", "Blaues Glas", "Синее стекло"), T.s("Black", "Crna", "Schwarz"),
        T.s("Green", "Zelena", "Grün"), T.s("Purple", "Ljubičasta", "Lila"))

    private fun weatherColour(res: Int) = when (res) {
        R.drawable.lc_sun -> Color.rgb(255, 200, 60)
        R.drawable.lc_cloud_sun -> Color.rgb(255, 214, 120)
        R.drawable.lc_moon, R.drawable.lc_cloud_moon -> Color.rgb(200, 210, 255)
        R.drawable.lc_cloud_rain -> Color.rgb(110, 180, 255)
        R.drawable.lc_cloud_snow -> Color.rgb(235, 245, 255)
        R.drawable.lc_cloud_lightning -> Color.rgb(255, 220, 80)
        else -> Color.rgb(200, 210, 225)
    }

    private fun weatherMenu() {
        val styles = listOf(T.s("Minimal", "Minimalna", "Minimal") to 0, T.s("Outline", "Obris", "Umriss") to 1,
            T.s("Filled (colour)", "Popunjena (u boji)", "Gefüllt (farbig)") to 2)
        menu.show(T.s("Weather", "Vrijeme", "Wetter"), R.drawable.lc_cloud_sun, listOf(
            GlassMenu.Item(T.s("City", "Grad", "Stadt"), R.drawable.lc_map_pin, value = prefs.city) { setCity() },
            GlassMenu.Item(T.s("Unit", "Jedinica", "Einheit"), R.drawable.lc_cloud_sun, value = if (prefs.weatherF) "°F" else "°C") {
                choose(T.s("Unit", "Jedinica", "Einheit"), R.drawable.lc_cloud_sun, listOf("°C" to 0, "°F" to 1), if (prefs.weatherF) 1 else 0) {
                    prefs.weatherF = it == 1; Weather.fahrenheit = prefs.weatherF; lastWeather = 0; updateWeather()
                }
            },
            GlassMenu.Item(T.s("Icon style", "Stil ikone", "Symbolstil"), R.drawable.lc_image, value = styles[prefs.weatherIcons.coerceIn(0, 2)].first) {
                choose(T.s("Icon style", "Stil ikone", "Symbolstil"), R.drawable.lc_image, styles, prefs.weatherIcons) {
                    prefs.weatherIcons = it; lastWeather = 0; updateWeather()
                }
            },
        ))
    }

    // ---- profiles: copy from another one, picture

    private fun groupName(g: String) = when (g) {
        "apps" -> T.s("Apps on the home screen", "Aplikacije na početnom ekranu", "Apps auf dem Startbildschirm")
        "look" -> T.s("Look and icons", "Izgled i ikone", "Aussehen und Symbole")
        "wallpaper" -> T.s("Wallpaper", "Pozadina", "Hintergrund")
        "status" -> T.s("Status bar and clock", "Statusna traka i sat", "Statusleiste und Uhr")
        "weather" -> T.s("Weather", "Vrijeme", "Wetter")
        "saver" -> T.s("Screensaver and idle", "Screensaver i mirovanje", "Bildschirmschoner und Leerlauf")
        "lang" -> T.s("Language", "Jezik", "Sprache")
        else -> T.s("Everything else", "Sve ostalo", "Alles andere")
    }

    private fun newProfileFrom(name: String) {
        val items = mutableListOf(GlassMenu.Item(T.s("Empty profile (start fresh)", "Prazan profil (ispočetka)", "Leeres Profil (neu anfangen)"), R.drawable.lc_user_plus) {
            val id = Profiles.add(this, name)
            Prefs.forProfile(this, id) { it.applyVisualLook(2); it.pinned = DefaultApps.list(this) }
            Profiles.setCurrent(this, id); recreate()
        })
        for ((id, n) in Profiles.list(this)) items.add(GlassMenu.Item(T.s("Copy from: ", "Kopiraj iz: ", "Kopieren aus: ") + n, R.drawable.lc_copy) {
            copyChoiceMenu(name, id, n, Profiles.GROUPS.toMutableSet())
        })
        menu.show(T.s("New profile: ", "Novi profil: ", "Neues Profil: ") + name, R.drawable.lc_user_plus, items)
    }

    private fun copyChoiceMenu(name: String, from: String, fromName: String, chosen: MutableSet<String>) {
        val items = mutableListOf(
            GlassMenu.Item(T.s("Select all", "Označi sve", "Alle auswählen"), R.drawable.lc_check) {
                chosen.addAll(Profiles.GROUPS); copyChoiceMenu(name, from, fromName, chosen)
            },
            GlassMenu.Item(T.s("Deselect all", "Odznači sve", "Alle abwählen"), R.drawable.lc_x) {
                chosen.clear(); copyChoiceMenu(name, from, fromName, chosen)
            },
        )
        for (g in Profiles.GROUPS) items.add(GlassMenu.Item(groupName(g), checked = g in chosen, toggle = true) {
            if (it.checked) chosen.add(g) else chosen.remove(g)
        })
        items.add(GlassMenu.Item(T.s("Create profile", "Napravi profil", "Profil erstellen"), R.drawable.lc_user_plus) {
            val id = Profiles.add(this, name)
            Profiles.copy(this, from, id, chosen)
            Profiles.setCurrent(this, id); recreate()
        })
        menu.show(T.s("Copy from ", "Kopiraj iz ", "Kopieren aus ") + fromName, R.drawable.lc_copy, items,
            T.s("Green = copied into \"$name\".", "Zeleno = kopira se u \"$name\".", "Grün = wird in \"$name\" kopiert.", "Зелёное = копируется в \"$name\"."))
    }

    private fun profilePhotoMenu() {
        val id = prefs.profile
        val items = mutableListOf(GlassMenu.Item(T.s("Choose a picture", "Izaberi sliku", "Bild auswählen"), R.drawable.lc_image) {
            pickImage { f -> runCatching { f.copyTo(Profiles.photo(this, id), true) }; showProfilePhoto() }
        })
        if (Profiles.photo(this, id).exists()) items.add(GlassMenu.Item(T.s("Letter instead of a picture", "Slovo umjesto slike", "Buchstabe statt Bild"), R.drawable.lc_type) {
            Profiles.photo(this, id).delete(); showProfilePhoto()
        })
        menu.show(T.s("Profile picture", "Slika profila", "Profilbild"), R.drawable.lc_image, items)
    }

    private var profilePic: ImageView? = null

    private fun showProfilePhoto() {
        val f = Profiles.photo(this, prefs.profile)
        val iv = profilePic ?: ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }.also {
            profilePic = it; profileBtn.addView(it, 0, FrameLayout.LayoutParams(-1, -1))
        }
        val b = if (f.exists()) runCatching { Bitmaps.decodeScaled(f, 160, 160) }.getOrNull() else null
        iv.setImageBitmap(b)
        iv.visibility = if (b != null) View.VISIBLE else View.GONE
        profileLetter.visibility = if (b != null) View.GONE else View.VISIBLE
    }

    // ---- updates: what's new, Mark as Stable

    private fun whatsNew() {
        // the waiting new version first, then what the installed one brought
        val mine = runCatching { resources.openRawResource(R.raw.release_notes).bufferedReader().readText().trim() }.getOrDefault("")
        val next = Updater.pending(this)?.notes?.trim().orEmpty()
        val txt = listOf(next, mine).filter { it.isNotBlank() }.distinct().joinToString("\n\n────────\n\n")
        menu.page(T.s("What's new", "Šta je novo", "Was ist neu"), R.drawable.lc_info, txt.ifBlank { "–" })
    }

    private fun markStable() {
        if (!Updater.hasToken(this)) {
            askText(T.s("GitHub token (only once)", "GitHub token (samo jednom)", "GitHub-Token (nur einmal)"), "") { t ->
                if (t.isNotBlank()) { Updater.setToken(this, t); markStable() }
            }
            return
        }
        val name = Updater.pending(this)?.takeIf { it.code > Updater.stableCode(this) }?.name ?: Updater.currentName(this)
        toast(T.s("Working…", "Radim…", "Arbeite…"))
        // show exactly which beta commit goes to every box; only that one is published
        Thread {
            val head = Updater.betaHead(this)
            handler.post { if (head == null) toast("GitHub ?") else confirmStable(name, head) }
        }.start()
    }

    private fun confirmStable(name: String, head: Pair<String, String>) {
        confirm(T.s("Mark as Stable", "Označi kao Stable", "Als Stable markieren"), R.drawable.lc_shield_check,
            T.s("Make the Beta the Stable version for every box?", "Napraviti Betu Stable verzijom za sve boxove?",
                "Die Beta zur Stable-Version für alle Boxen machen?", "Сделать бету стабильной версией для всех приставок?") +
                "\n\n" + name + "\n" + head.first.take(7) + " · " + head.second.take(70)) {
            toast(T.s("Working…", "Radim…", "Arbeite…"))
            Thread {
                val err = Updater.promote(this, head.first)
                handler.post {
                    if (err == null) toast(T.s("Done – GitHub is building the Stable version (a few minutes)", "Gotovo – GitHub pravi Stable verziju (par minuta)",
                        "Fertig – GitHub baut die Stable-Version (ein paar Minuten)", "Готово – GitHub собирает стабильную версию (несколько минут)"))
                    else toast(err)
                }
            }.start()
        }
    }

    /** three crashes within ten minutes: start with the safe look and say so */
    private fun safeStart() {
        CrashWatch.clear(this)
        if (prefs.wpMode == 2 || prefs.wpMode == 3) prefs.wpMode = 0
        menu.show(T.s("Safe start", "Siguran start", "Sicherer Start", "Безопасный запуск"), R.drawable.lc_shield_check, listOf(
            GlassMenu.Item(T.s("Light look (fastest)", "Light izgled (najbrži)", "Light-Look (am schnellsten)"), R.drawable.lc_image) {
                setPerfMode(true, quiet = true); prefs.applyVisualLook(2); reloadApps(); applyLook()
            },
            GlassMenu.Item(T.s("Stop getting Beta versions", "Ne primaj više Beta verzije", "Keine Beta-Versionen mehr", "Не получать бета-версии"), R.drawable.lc_flask_conical) {
                Updater.setBeta(this, false); updateMenu()
            },
            GlassMenu.Item(T.s("Continue", "Nastavi", "Weiter", "Продолжить"), R.drawable.lc_check),
        ), T.s("LEAD stopped 3 times in a short time. Picture plugins were switched off. Android can't go back to an older version without uninstalling; the next update fixes it.",
            "LEAD se 3 puta zaustavio u kratkom vremenu. Plugini za slike su isključeni. Android ne može vratiti stariju verziju bez deinstalacije; sljedeći update to popravlja.",
            "LEAD wurde 3-mal kurz hintereinander beendet. Bild-Plugins wurden ausgeschaltet. Android kann ohne Deinstallation nicht auf eine ältere Version zurück; das nächste Update behebt es.",
            "LEAD 3 раза завершился за короткое время. Плагины картинок отключены. Android не может вернуться к старой версии без удаления; следующее обновление это исправит."))
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
                        "This box doesn't offer a choice. Use \"Home button opens LEAD Launcher\".",
                        "Ovaj box ne nudi izbor. Koristi \"Dugme Home otvara LEAD Launcher\".",
                        "Diese Box bietet keine Auswahl. Nutze \"Home-Taste öffnet LEAD Launcher\"."))
                }
            },
            GlassMenu.Item(T.s("Home button opens LEAD Launcher", "Dugme Home otvara LEAD Launcher", "Home-Taste öffnet LEAD Launcher"),
                R.drawable.lc_house, value = onOff(HomeService.isEnabled(this) && prefs.homeRedirect)) {
                if (HomeService.isEnabled(this) && prefs.homeRedirect) {
                    prefs.homeRedirect = false
                    toast(T.s("Home button: Android home screen", "Dugme Home: Android početni ekran", "Home-Taste: Android-Startbildschirm"))
                } else enableHomeButton()
            },
            // Google TV sometimes shows itself again after a restart / sleep / screensaver: go back to LEAD by itself
            GlassMenu.Item(T.s("Return to LEAD automatically", "Automatski se vrati na LEAD", "Automatisch zu LEAD zurück", "Автоматически возвращаться в LEAD"),
                R.drawable.lc_rotate_ccw, checked = prefs.autoReturn, toggle = true) { prefs.autoReturn = it.checked },
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
            toast(T.s("Turn on \"LEAD Launcher\" here", "Ovdje uključi \"LEAD Launcher\"", "Hier \"LEAD Launcher\" einschalten"))
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
            T.s("After this, LEAD Launcher can turn on the Home button and Private DNS by itself.\n\nTo switch off the Google home screen completely:",
                "Poslije toga LEAD Launcher sam uključuje dugme Home i Private DNS.\n\nDa potpuno isključiš Google početni ekran:",
                "Danach kann LEAD Launcher die Home-Taste und Private DNS selbst einschalten.\n\nUm den Google-Startbildschirm ganz abzuschalten:") +
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
                    "Switched off = LEAD Launcher stops the app in the background after the box starts.",
                    "Isključeno = LEAD Launcher zaustavlja aplikaciju u pozadini nakon paljenja boxa.",
                    "Aus = LEAD Launcher stoppt die App im Hintergrund nach dem Start der Box."))
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
            askText(T.s("Profile name", "Ime profila", "Profilname"), "") { n -> if (n.isNotBlank()) newProfileFrom(n) }
        })
        items.add(GlassMenu.Item(T.s("Profile picture", "Slika profila", "Profilbild"), R.drawable.lc_image,
            value = if (Profiles.photo(this, cur).exists()) "✓" else prefs.let { Profiles.name(this, cur).take(1).uppercase() }) { profilePhotoMenu() })
        items.add(GlassMenu.Item(T.s("Rename this profile", "Preimenuj ovaj profil", "Dieses Profil umbenennen"), R.drawable.lc_pencil) {
            askText(T.s("Profile name", "Ime profila", "Profilname"), Profiles.name(this, cur)) { n ->
                if (n.isNotBlank()) { Profiles.rename(this, cur, n); applyLook() }
            }
        })
        items.add(GlassMenu.Item(T.s("Delete a profile…", "Obriši profil…", "Profil löschen…"), R.drawable.lc_trash_2) { deleteProfileMenu() })
        items.add(GlassMenu.Item(T.s("Google accounts", "Google računi", "Google-Konten"), R.drawable.lc_log_in,
            value = Profiles.savedAccount(this)) { accountsMenu() })
        val acc = Profiles.savedAccount(this)
        menu.show(T.s("Profile", "Profil", "Profil") + ": " + Profiles.name(this, cur), R.drawable.lc_user, items,
            if (acc != null) "Google: $acc" else null)
    }

    /** pick a profile to delete (the main profile stays – it holds the box's basic setup) */
    private fun deleteProfileMenu() {
        val others = Profiles.list(this).filter { it.first != Profiles.MAIN }
        if (others.isEmpty()) {
            toast(T.s("There is only the main profile – it can't be deleted", "Postoji samo glavni profil – on se ne može obrisati",
                "Es gibt nur das Hauptprofil – es kann nicht gelöscht werden")); return
        }
        menu.show(T.s("Delete a profile", "Obriši profil", "Profil löschen"), R.drawable.lc_trash_2, others.map { (id, name) ->
            GlassMenu.Item(name, R.drawable.lc_user, checked = id == prefs.profile) {
                confirm(T.s("Delete profile", "Obriši profil", "Profil löschen"), R.drawable.lc_trash_2,
                    T.s("Delete \"$name\" with its apps, wallpaper and settings?", "Obrisati \"$name\" sa njegovim aplikacijama, pozadinom i postavkama?",
                        "\"$name\" mit Apps, Hintergrund und Einstellungen löschen?")) {
                    val wasCurrent = id == prefs.profile
                    Profiles.delete(this, id)
                    toast(T.s("Profile deleted", "Profil obrisan", "Profil gelöscht"))
                    if (wasCurrent) recreate() else deleteProfileMenu()
                }
            }
        }, T.s("The main profile can be renamed but not deleted.", "Glavni profil se može preimenovati, ali ne i obrisati.",
            "Das Hauptprofil kann umbenannt, aber nicht gelöscht werden."))
    }

    /** Google accounts on the box: which ones, which this profile uses, add / remove / switch. Stays open. */
    private fun accountsMenu() {
        Thread {
            // the full list needs Shizuku (Android hides accounts from apps); otherwise only the chosen one is known
            val txt = ShizukuCpu.output("dumpsys", "account") ?: ""
            val found = Regex("""Account \{name=([^,]+), type=com\.google\}""").findAll(txt).map { it.groupValues[1] }.distinct().toList()
            handler.post {
                val used = Profiles.savedAccount(this)
                val names = (found + listOfNotNull(used)).distinct()
                val items = mutableListOf<GlassMenu.Item>()
                for (n in names) items.add(GlassMenu.Item(n, R.drawable.lc_user, checked = n == used) {
                    Profiles.saveAccount(this, n)
                    toast(T.s("This profile now uses ", "Ovaj profil sad koristi ", "Dieses Profil nutzt jetzt ") + n)
                    accountsMenu()
                })
                items.add(GlassMenu.Item(T.s("Add account", "Dodaj račun", "Konto hinzufügen"), R.drawable.lc_user_plus) {
                    val add = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                    if (runCatching { startActivity(add) }.isFailure) openAccounts()
                })
                items.add(GlassMenu.Item(T.s("Sign out / remove an account", "Odjavi / ukloni račun", "Abmelden / Konto entfernen"), R.drawable.lc_log_in) { openAccounts() })
                if (found.isEmpty()) items.add(GlassMenu.Item(T.s("Pick the account from the list", "Izaberi račun sa liste", "Konto aus der Liste wählen"), R.drawable.lc_info) { chooseGoogleAccount() })
                menu.show(T.s("Google accounts", "Google računi", "Google-Konten"), R.drawable.lc_log_in, items,
                    when {
                        names.isEmpty() -> T.s("No Google account signed in.", "Nijedan Google račun nije prijavljen.", "Kein Google-Konto angemeldet.")
                        used != null -> T.s("✓ = used by this profile · OK on another one switches", "✓ = koristi ovaj profil · OK na drugom mijenja", "✓ = von diesem Profil genutzt · OK auf einem anderen wechselt")
                        else -> T.s("OK on an account = use it for this profile", "OK na računu = koristi ga za ovaj profil", "OK auf einem Konto = für dieses Profil nutzen")
                    })
            }
        }.start()
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

    /**
     * Clock & date left (classic) or right: on the right they sit after the round buttons, right-aligned,
     * and the status bar moves to the left – so pictures with their own title on the left stay readable.
     */
    private var clockSpacer: View? = null

    /**
     * Left: classic – big clock top left, status bar and buttons next to it.
     * Right: status bar and buttons pushed to the right edge; under them, in the right corner,
     * one line "20:55:12" (smaller clock) and one line "Thursday, 12. November".
     */
    private fun placeClock() {
        val col = clock.parent as? LinearLayout ?: return
        val right = prefs.clockSide == 1
        val d = resources.displayMetrics.density
        // find the top row (holds the status bar) and the column that holds the row
        val row = statusPill.parent as? LinearLayout ?: return
        val outer = row.parent as? LinearLayout ?: return
        (col.parent as? ViewGroup)?.removeView(col)
        clockSpacer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (prefs.clockSide == 2) {
            // Light: time right next to the buttons, in the top row
            val spacer = clockSpacer ?: View(this).also { clockSpacer = it }
            row.addView(spacer, 0, LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (20 * d).toInt(); gravity = Gravity.CENTER_VERTICAL })
            col.gravity = Gravity.END
            styleClock(34f)
        } else if (right) {
            val spacer = clockSpacer ?: View(this).also { clockSpacer = it }
            row.addView(spacer, 0, LinearLayout.LayoutParams(0, 1, 1f))
            outer.addView(col, outer.indexOfChild(row) + 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() })
            col.gravity = Gravity.END
            styleClock(40f)   // about 30 % smaller than on the left
        } else {
            row.addView(col, 0, LinearLayout.LayoutParams(0, -2, 1f))
            col.gravity = Gravity.START
            styleClock(58f)
        }
        // the "new version" chip: clock on the right = third line under the date; otherwise top right
        if (chipHome == null) chipHome = updateBtn.parent as? FrameLayout
        (updateBtn.parent as? ViewGroup)?.removeView(updateBtn)
        if (right && prefs.clockSide == 1) {
            col.addView(updateBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (42 * d).toInt())
                .apply { topMargin = (12 * d).toInt(); gravity = Gravity.END })
        } else {
            chipHome?.addView(updateBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (42 * d).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (84 * d).toInt(); marginEnd = (36 * d).toInt()
            })
        }
    }

    /** the button / status icon a menu was opened from – focus goes back there when the menu closes */
    private var menuOpener: View? = null

    private fun isInGrid(v: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === grid) return true; p = p.parent }
        return false
    }

    /** the screen layer the update chip floats on when it is not under the date */
    private var chipHome: FrameLayout? = null

    private fun clockMenu() {
        val fmt = listOf(T.s("Like Android", "Kao Android", "Wie Android") to 0, "24h" to 1, "12h (AM/PM)" to 2)
        menu.show(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock, listOf(
            GlassMenu.Item(T.s("Format", "Format", "Format"), R.drawable.lc_clock, value = fmt.first { it.second == prefs.clockFormat }.first) {
                choose(T.s("Format", "Format", "Format"), R.drawable.lc_clock, fmt, prefs.clockFormat) { prefs.clockFormat = it; updateClock() }
            },
            GlassMenu.Item(T.s("Clock position", "Položaj sata", "Position der Uhr"), R.drawable.lc_clock,
                value = listOf(T.s("Top left", "Gore lijevo", "Oben links"), T.s("Top right", "Gore desno", "Oben rechts"),
                    T.s("Next to the buttons", "Pored dugmadi", "Neben den Knöpfen"))[prefs.clockSide.coerceIn(0, 2)]) {
                choose(T.s("Clock position", "Položaj sata", "Position der Uhr"), R.drawable.lc_clock, listOf(
                    T.s("Top left", "Gore lijevo", "Oben links") to 0, T.s("Top right", "Gore desno", "Oben rechts") to 1,
                    T.s("Next to the buttons", "Pored dugmadi", "Neben den Knöpfen") to 2), prefs.clockSide) {
                    prefs.clockSide = it; applyLook()
                }
            },
            GlassMenu.Item(T.s("When it shows", "Način prikaza", "Wann sie erscheint"), R.drawable.lc_eye, value = statusModeName(prefs.clockMode)) {
                choose(T.s("When it shows", "Način prikaza", "Wann sie erscheint"), R.drawable.lc_eye,
                    (0..2).map { statusModeName(it) to it }, prefs.clockMode) { prefs.clockMode = it; setReveal(false); showPill() }
            },
            GlassMenu.Item(T.s("Show date", "Prikaži datum", "Datum anzeigen"), R.drawable.lc_type,
                checked = prefs.showDate, toggle = true) { prefs.showDate = it.checked; applyLook() },
            GlassMenu.Item(T.s("Font", "Font", "Schrift"), R.drawable.lc_type, value = clockFonts()[prefs.clockFont.coerceIn(0, 5)]) {
                choose(T.s("Font", "Font", "Schrift"), R.drawable.lc_type, clockFonts().mapIndexed { i, n -> n to i }, prefs.clockFont) {
                    prefs.clockFont = it; placeClock(); updateClock()
                }
            },
            GlassMenu.Item(T.s("Size", "Veličina", "Größe"), R.drawable.lc_columns_3, value = clockSizes()[prefs.clockSize.coerceIn(0, 3)]) {
                choose(T.s("Size", "Veličina", "Größe"), R.drawable.lc_columns_3, clockSizes().mapIndexed { i, n -> n to i }, prefs.clockSize) {
                    prefs.clockSize = it; placeClock(); updateClock()
                }
            },
            GlassMenu.Item(T.s("Time and date in one line", "Vrijeme i datum u jednom redu", "Uhrzeit und Datum in einer Zeile"), R.drawable.lc_layout_grid,
                checked = prefs.clockOneLine, toggle = true) { prefs.clockOneLine = it.checked; updateClock() },
            GlassMenu.Item(T.s("Date format", "Format datuma", "Datumsformat"), R.drawable.lc_type, value = dateStyles()[prefs.dateStyle.coerceIn(0, 2)]) {
                choose(T.s("Date format", "Format datuma", "Datumsformat"), R.drawable.lc_type, dateStyles().mapIndexed { i, n -> n to i }, prefs.dateStyle) {
                    prefs.dateStyle = it; updateClock()
                }
            },
            GlassMenu.Item(T.s("Show seconds", "Prikaži sekunde", "Sekunden anzeigen"), R.drawable.lc_clock,
                checked = prefs.clockSeconds, toggle = true) { prefs.clockSeconds = it.checked; updateClock() },
            GlassMenu.Item(T.s("Time zone automatically", "Vremenska zona automatski", "Zeitzone automatisch"), R.drawable.lc_globe,
                checked = autoZone(), toggle = true) { toggleAutoZone() },
        ))
    }

    private fun clockFonts() = listOf(T.s("Thin", "Tanki", "Dünn"), "Light", T.s("Normal", "Normalni", "Normal"),
        T.s("Bold", "Podebljani", "Fett"), T.s("Narrow", "Uski", "Schmal"), T.s("Digital", "Digitalni", "Digital"))
    private fun clockSizes() = listOf(T.s("Small", "Mali", "Klein"), T.s("Normal", "Normalni", "Normal"),
        T.s("Large", "Veliki", "Groß"), T.s("Very large", "Vrlo veliki", "Sehr groß"))
    private fun dateStyles() = listOf(T.s("Monday, 16 November", "Ponedjeljak, 16. novembar", "Montag, 16. November"),
        T.s("Mon, 16 Nov", "Pon, 16. nov", "Mo, 16. Nov"), "16.11.2026")

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
            GlassMenu.Item(T.s("Full backup (settings + all apps)", "Pun backup (postavke + sve aplikacije)", "Vollständige Sicherung (Einstellungen + alle Apps)"),
                R.drawable.lc_package) {
                withWriteStorage {
                    toast(T.s("Full backup started – this can take a few minutes", "Pun backup je počeo – može trajati nekoliko minuta",
                        "Vollständige Sicherung läuft – kann einige Minuten dauern"))
                    Thread {
                        var last = 0L
                        val r = runCatching { Backup.createFull(this) { step ->
                            val now = System.currentTimeMillis()
                            if (now - last > 2500) { last = now; handler.post { toast(T.s("Backup: ", "Backup: ", "Sicherung: ") + step) } }
                        } }
                        handler.post {
                            r.onSuccess { f -> menu.message("Backup", R.drawable.lc_check, T.s("Full backup saved in:", "Pun backup sačuvan u:", "Vollständige Sicherung in:") +
                                "\n" + (f.parentFile?.absolutePath ?: "") + "\n\n" +
                                T.s("Copy this whole folder to a USB stick. On another box: Restore and choose backup.json in that folder – the apps get installed too.",
                                    "Kopiraj cijeli ovaj folder na USB. Na drugom boxu: Vrati iz backupa i izaberi backup.json u tom folderu – instaliraju se i aplikacije.",
                                    "Kopiere den ganzen Ordner auf einen USB-Stick. Auf einer anderen Box: Wiederherstellen und backup.json darin wählen – die Apps werden mitinstalliert.")) }
                            r.onFailure { toast(T.s("Backup failed (enough space?)", "Backup nije uspio (ima li mjesta?)", "Sicherung fehlgeschlagen (genug Platz?)")) }
                        }
                    }.start()
                }
            },
            GlassMenu.Item(T.s("Settings only backup", "Backup samo postavki", "Nur Einstellungen sichern"), R.drawable.lc_download) {
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
        ), T.s("Settings only: profiles, home screen apps, names, icons, wallpapers, settings (small file). Full: the same plus the installed apps themselves – on another box they get installed. Logins inside apps and passwords can't be saved by any app.",
            "Samo postavke: profili, aplikacije na početnom ekranu, nazivi, ikone, pozadine, postavke (mali fajl). Pun: isto plus same instalirane aplikacije – na drugom boxu se instaliraju. Prijave unutar aplikacija i lozinke ne može sačuvati nijedna aplikacija.",
            "Nur Einstellungen: Profile, Apps am Startbildschirm, Namen, Symbole, Hintergründe, Einstellungen (kleine Datei). Vollständig: dazu die installierten Apps selbst – auf einer anderen Box werden sie installiert. Anmeldungen in Apps und Passwörter kann keine App sichern."))
    }

    private fun restoreBackup(f: File) {
        Thread {
            val r = runCatching { Backup.restore(this, f) }
            val missing = if (r.isSuccess) Backup.missingApps(this, f) else emptyList()
            handler.post {
                if (r.isSuccess && missing.isNotEmpty()) {
                    // full backup: install the apps that are not on this box yet, one after another
                    toast(T.s("Restored – now installing ", "Vraćeno – sad instaliram ", "Wiederhergestellt – installiere jetzt ") + missing.size +
                        T.s(" apps", " aplikacija", " Apps"))
                    AppInstaller.start(this, missing)
                }
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
            GlassMenu.Item(T.s("Shut down", "Ugasi", "Ausschalten"), R.drawable.lc_power) {
                confirm(T.s("Shut down", "Ugasi", "Ausschalten"), R.drawable.lc_power,
                    T.s("Do you really want to switch the box off?", "Sigurno želiš ugasiti uređaj?", "Box wirklich ausschalten?")) { goSleep() }
            },
            GlassMenu.Item(T.s("Restart", "Restart", "Neustart"), R.drawable.lc_rotate_cw) {
                confirm(T.s("Restart", "Restart", "Neustart"), R.drawable.lc_rotate_cw,
                    T.s("Do you really want to restart the box?", "Sigurno želiš restartovati uređaj?", "Box wirklich neu starten?")) { restartBox() }
            },
            GlassMenu.Item(T.s("Screensaver", "Screensaver", "Bildschirmschoner"), R.drawable.lc_monitor) {
                confirm(T.s("Screensaver", "Screensaver", "Bildschirmschoner"), R.drawable.lc_monitor,
                    T.s("Start the screensaver?", "Pokrenuti screensaver?", "Bildschirmschoner starten?")) { startSaver() }
            },
        ), T.s("Shut down: box and TV go off, the remote switches them on again.",
            "Ugasi: box i TV se gase, daljinac ih ponovo pali.",
            "Ausschalten: Box und Fernseher gehen aus, die Fernbedienung schaltet sie wieder ein."))
    }

    // ------------------------------------------------------------------ developer shortcuts

    private fun adbWifiOn() = runCatching { Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)

    /** Wireless debugging on / off straight from LEAD (needs the permission the setup script gives). */
    private fun setAdbWifi(on: Boolean) {
        val ok = runCatching {
            if (on) {
                Settings.Global.putInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
            }
            Settings.Global.putInt(contentResolver, "adb_wifi_enabled", if (on) 1 else 0)
        }.isSuccess || (ShizukuCpu.permitted() && ShizukuCpu.run("settings", "put", "global", "adb_wifi_enabled", if (on) "1" else "0"))
        if (!ok) { openDeveloperOptions(); return }
        if (!on) { toast(T.s("Wireless debugging off", "Wireless debugging isključen", "Drahtloses Debugging aus")); return }
        // show the address to type on the laptop
        Thread {
            Thread.sleep(1500)
            val port = ShizukuCpu.output("getprop", "service.adb.tls.port")?.trim()?.takeIf { it.isNotBlank() && it != "0" }
            val ip = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            }.getOrNull()
            handler.post {
                toast(T.s("Wireless debugging on", "Wireless debugging uključen", "Drahtloses Debugging an") +
                    if (ip != null && port != null) "  ·  $ip:$port" else "")
            }
        }.start()
    }

    private fun openDeveloperOptions() {
        val devOn = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
        if (!devOn) {
            // switch developer mode on ourselves (no 7 taps needed); if not allowed, go to "Android TV OS build"
            val ok = runCatching { Settings.Global.putInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1) }.isSuccess
            if (!ok) { openAbout(); return }
        }
        if (runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.isFailure) openAbout()
    }

    /** "About" page – press "Android TV OS build" 7 times there to switch developer mode on. */
    private fun openAbout() {
        if (runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }.isFailure)
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        toast(T.s("Press \"Android TV OS build\" 7 times", "Pritisni 7 puta \"Android TV OS build\"", "7× auf \"Android TV OS build\" drücken"))
    }

    // ------------------------------------------------------------------ performance / normal mode

    private fun perfModeAsk() {
        confirm(T.s("Performance mode", "Performance mod", "Leistungsmodus"), R.drawable.lc_gauge,
            T.s("For the fastest box possible this switches off:\n" +
                "• film & series pictures and trailers in the background (TMDB)\n" +
                "• wallpaper plugins (SofaDrop)\n" +
                "• the glass effects (blur, edge light) and animations\n\n" +
                "Only your own wallpaper stays. LEAD uses less memory and processor.\nSwitch on?",
                "Za što brži rad boxa ovo se gasi:\n" +
                "• slike i najave filmova i serija u pozadini (TMDB)\n" +
                "• pluginovi za pozadinu (SofaDrop)\n" +
                "• stakleni efekti (zamućenje, svjetlo na rubovima) i animacije\n\n" +
                "Ostaje samo tvoja pozadina. LEAD troši manje memorije i procesora.\nUključiti?",
                "Für eine möglichst schnelle Box wird ausgeschaltet:\n" +
                "• Film- und Serienbilder und Trailer im Hintergrund (TMDB)\n" +
                "• Hintergrund-Plugins (SofaDrop)\n" +
                "• die Glaseffekte (Unschärfe, Randlicht) und Animationen\n\n" +
                "Nur dein eigenes Hintergrundbild bleibt. LEAD braucht weniger Speicher und Prozessor.\nEinschalten?")) {
            setPerfMode(true)
        }
    }

    private fun normalModeAsk() {
        confirm(T.s("Normal mode", "Normalni mod", "Normaler Modus"), R.drawable.lc_image,
            T.s("Everything comes back:\n" +
                "• film & series pictures and trailers (TMDB) or your wallpaper plugin\n" +
                "• the glass effects and animations\n\n" +
                "This needs more memory (RAM) and processor power.\nSwitch on?",
                "Sve se vraća:\n" +
                "• slike i najave filmova i serija (TMDB) ili tvoj plugin za pozadinu\n" +
                "• stakleni efekti i animacije\n\n" +
                "To traži više memorije (RAM-a) i snage procesora.\nUključiti?",
                "Alles kommt zurück:\n" +
                "• Film- und Serienbilder und Trailer (TMDB) oder dein Hintergrund-Plugin\n" +
                "• die Glaseffekte und Animationen\n\n" +
                "Das braucht mehr Speicher (RAM) und Prozessorleistung.\nEinschalten?")) {
            setPerfMode(false)
            handler.postDelayed({ extrasMenu() }, 300)
        }
    }

    /** Add-ons for the full look: SofaDrop wallpapers, TMDB, Shizuku (CPU %) – fetched and installed by LEAD */
    private fun extrasMenu() {
        val items = mutableListOf<GlassMenu.Item>()
        val sofa = Extras.installed(this, Extras.SOFADROP)
        items.add(GlassMenu.Item(
            if (sofa) T.s("Use SofaDrop as wallpaper", "Koristi SofaDrop kao pozadinu", "SofaDrop als Hintergrund nutzen")
            else T.s("Install SofaDrop (film & series wallpapers)", "Instaliraj SofaDrop (pozadine filmova i serija)", "SofaDrop installieren (Film- & Serien-Hintergründe)"),
            R.drawable.lc_image, value = if (sofa) "✓" else null) {
            if (sofa) useSofadrop()
            else {
                getSharedPreferences("glass_global", Context.MODE_PRIVATE).edit().putBoolean("pendingSofa", true).apply()
                toast(T.s("Downloading SofaDrop…", "Skidam SofaDrop…", "Lade SofaDrop…"))
                Extras.install(this, Extras.SOFADROP, Extras.SOFADROP_APK) { toast("SofaDrop: $it") }
            }
        })
        items.add(GlassMenu.Item(T.s("Films & series from TMDB (needs your API key)", "Filmovi i serije sa TMDB (treba tvoj API ključ)",
            "Filme & Serien von TMDB (braucht deinen API-Schlüssel)"), R.drawable.lc_image) { wallpaperMenu() })
        val shz = Extras.installed(this, Extras.SHIZUKU)
        items.add(GlassMenu.Item(
            when {
                !shz -> T.s("Install Shizuku (for CPU %)", "Instaliraj Shizuku (za CPU %)", "Shizuku installieren (für CPU %)")
                ShizukuCpu.running() -> T.s("Shizuku is running", "Shizuku radi", "Shizuku läuft")
                else -> T.s("Start Shizuku (pair once)", "Pokreni Shizuku (jednom upari)", "Shizuku starten (einmal koppeln)")
            }, R.drawable.lc_cpu, value = if (shz && ShizukuCpu.running()) "✓" else null) {
            when {
                !shz -> { toast(T.s("Downloading Shizuku…", "Skidam Shizuku…", "Lade Shizuku…"))
                    Extras.install(this, Extras.SHIZUKU, null) { toast("Shizuku: $it") } }
                !ShizukuCpu.running() -> shizukuHowTo()
                else -> toast(T.s("Shizuku is running – CPU % works", "Shizuku radi – CPU % radi", "Shizuku läuft – CPU % funktioniert"))
            }
        })
        menu.show(T.s("Add-ons", "Dodaci", "Erweiterungen"), R.drawable.lc_plus, items,
            T.s("LEAD downloads them from their official pages and keeps them up to date.",
                "LEAD ih skida sa njihovih službenih stranica i sam ih ažurira.",
                "LEAD lädt sie von ihren offiziellen Seiten und hält sie aktuell."))
    }

    private fun shizukuHowTo() {
        menu.show("Shizuku", R.drawable.lc_cpu, listOf(
            GlassMenu.Item(T.s("1. Switch on Wireless debugging", "1. Uključi Wireless debugging", "1. Drahtloses Debugging einschalten"), R.drawable.lc_wifi) { setAdbWifi(true) },
            GlassMenu.Item(T.s("2. Open Shizuku → pair → Start", "2. Otvori Shizuku → upari → Start", "2. Shizuku öffnen → koppeln → Start"), R.drawable.lc_cpu) {
                packageManager.getLeanbackLaunchIntentForPackage(Extras.SHIZUKU)?.let { startActivity(it) }
                    ?: packageManager.getLaunchIntentForPackage(Extras.SHIZUKU)?.let { startActivity(it) }
            },
        ), T.s("Android needs one pairing before Shizuku can start. In Shizuku also switch on \"Start on boot\".",
            "Android traži jedno uparivanje prije prvog pokretanja Shizukua. U Shizukuu uključi i \"Start on boot\".",
            "Android braucht einmal eine Kopplung, bevor Shizuku starten kann. In Shizuku auch \"Start on boot\" einschalten."))
    }

    /** SofaDrop installed: make it the wallpaper source and open its setup */
    private fun useSofadrop() {
        val p = Plugins.list(this).firstOrNull { it.pkg == Extras.SOFADROP }
        if (p == null) { toast(T.s("SofaDrop is not ready yet", "SofaDrop još nije spreman", "SofaDrop ist noch nicht bereit")); return }
        if (prefs.perfMode) setPerfMode(false)
        prefs.wpPlugin = p.id
        prefs.wpMode = 3
        applyLook()
        toast(T.s("SofaDrop is your wallpaper", "SofaDrop je tvoja pozadina", "SofaDrop ist dein Hintergrund"))
        handler.postDelayed({ Plugins.openSettings(this, p) }, 800)
    }

    private fun setPerfMode(on: Boolean, quiet: Boolean = false) {
        if (on == prefs.perfMode) return
        if (on) {
            prefs.perfPrevWp = prefs.wpMode
            if (prefs.wpMode == 2 || prefs.wpMode == 3) prefs.wpMode = 0
        } else {
            prefs.wpMode = prefs.perfPrevWp
        }
        prefs.perfMode = on
        Backdrop.lite = on
        if (on) { Backdrop.blurred = null; movies = emptyList(); showMovieInfo(null) }
        applyLook()
        invalidateGlass()
        if (!quiet) toast(if (on) T.s("Performance mode on", "Performance mod uključen", "Leistungsmodus an")
            else T.s("Normal mode on", "Normalni mod uključen", "Normaler Modus an"))
    }

    /** One question, Yes / No – Yes does exactly that one thing. */
    private fun confirm(title: String, icon: Int, question: String, yes: () -> Unit) {
        menu.show(title, icon, listOf(
            GlassMenu.Item(T.s("Yes", "Da", "Ja"), R.drawable.lc_check) { yes() },
            GlassMenu.Item(T.s("No", "Ne", "Nein"), R.drawable.lc_x) { },
        ), question)
    }

    /** Restart: directly if Android allows it, otherwise the system power menu (choose "Restart" there). */
    private fun restartBox() {
        // with Shizuku: restart straight away, no system menu in between
        if (ShizukuCpu.permitted()) {
            toast(T.s("Restarting…", "Restartujem…", "Neustart…"))
            Thread { if (!ShizukuCpu.run("svc", "power", "reboot")) ShizukuCpu.run("reboot") }.start()
            return
        }
        val direct = runCatching { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).reboot(null); true }.getOrDefault(false)
        if (direct) return
        if (HomeService.instance == null) { needServiceFor(); return }
        // the question was already asked in LEAD: open Android's power menu and press its "Restart" ourselves
        toast(T.s("Restarting…", "Restartujem…", "Neustart…"))
        HomeService.restartViaMenu { ok ->
            if (!ok) toast(T.s("Choose \"Restart\"", "Izaberi \"Restart\"", "\"Neustart\" wählen"))
        }
    }

    /** Standby: screen + box off, TV off over HDMI-CEC; the remote switches everything on again. */
    private fun goSleep() {
        if (ShizukuCpu.permitted()) {
            Thread { if (!ShizukuCpu.run("input", "keyevent", "KEYCODE_SLEEP")) handler.post { if (!HomeService.sleep()) needServiceFor() } }.start()
            return
        }
        if (!HomeService.sleep()) needServiceFor()
    }

    private fun needServiceFor() {
        menu.show(T.s("Power", "Napajanje", "Energie"), R.drawable.lc_power, listOf(
            GlassMenu.Item(T.s("Switch on the helper service", "Uključi pomoćni servis", "Hilfsdienst einschalten"), R.drawable.lc_shield_check) { enableService() },
        ), T.s("Android lets apps put the box to sleep or open the power menu only through the LEAD Launcher helper service (Accessibility). Sleep needs Android 9 or newer.",
            "Android dozvoljava gašenje i spavanje boxa samo preko pomoćnog servisa LEAD Launchera (Pristupačnost). Spavanje traži Android 9 ili noviji.",
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
                    listOf(1, 5, 10, 15, 30, 60).map { (if (it < 60) "$it min" else "1 h") to it }, prefs.idleMinutes) { prefs.idleMinutes = it }
            },
            GlassMenu.Item(T.s("Pictures", "Slike", "Bilder"), R.drawable.lc_image, value = saverSources().first { it.second == prefs.saverSrc }.first) {
                choose(T.s("Screensaver pictures", "Slike za screensaver", "Bilder für den Bildschirmschoner"), R.drawable.lc_image,
                    saverSources(), prefs.saverSrc) { v ->
                    when (v) {
                        SaverPictures.FOLDER -> {
                            pendingPick = { f -> prefs.saverFolder = f.absolutePath; prefs.saverSrc = SaverPictures.FOLDER; screensaverMenu() }
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(this, FileBrowserActivity::class.java)
                                .putExtra(FileBrowserActivity.EXTRA_PICK, "folder"), REQ_FILES)
                        }
                        SaverPictures.REDDIT -> choose("Reddit", R.drawable.lc_image,
                            SaverPictures.REDDITS.mapIndexed { i, r -> "r/$r" to i }, SaverPictures.REDDITS.indexOf(prefs.saverReddit)) { i ->
                            prefs.saverReddit = SaverPictures.REDDITS[i]; prefs.saverSrc = SaverPictures.REDDIT; screensaverMenu()
                        }
                        else -> { prefs.saverSrc = v; screensaverMenu() }
                    }
                }
            },
            GlassMenu.Item(T.s("Change picture every", "Mijenjaj sliku svakih", "Bild wechseln alle"), R.drawable.lc_clock, value = intervalLabel(prefs.saverInterval)) {
                choose(T.s("Change picture every", "Mijenjaj sliku svakih", "Bild wechseln alle"), R.drawable.lc_clock,
                    listOf(30, 60, 300, 600, 900).map { intervalLabel(it) to it }, prefs.saverInterval) { prefs.saverInterval = it; screensaverMenu() }
            },
            GlassMenu.Item(T.s("Preview screensaver", "Pregled screensavera", "Bildschirmschoner-Vorschau"), R.drawable.lc_eye) { startSaver() },
            GlassMenu.Item(T.s("Android screensaver settings", "Android postavke screensavera", "Android-Bildschirmschoner-Einstellungen"), R.drawable.lc_settings) {
                safeStart(Intent(Settings.ACTION_DREAM_SETTINGS))
            },
        ), T.s("Tip: in the Android screensaver settings you can also choose \"LEAD Launcher\" – then the samurai clock shows in every app.",
            "Savjet: u Android postavkama screensavera možeš izabrati i \"LEAD Launcher\" – tada se samuraj sat prikazuje u svakoj aplikaciji.",
            "Tipp: In den Android-Einstellungen kannst du auch \"LEAD Launcher\" als Bildschirmschoner wählen."))
    }

    private fun saverSources() = listOf(
        T.s("My wallpaper", "Moja pozadina", "Mein Hintergrund") to SaverPictures.OWN,
        T.s("Folder on the box / USB", "Folder na boxu / USB", "Ordner auf der Box / USB") to SaverPictures.FOLDER,
        T.s("Reddit (nature, space, cities…)", "Reddit (priroda, svemir, gradovi…)", "Reddit (Natur, Weltall, Städte…)") to SaverPictures.REDDIT,
        T.s("Bing picture of the day", "Bing slika dana", "Bing-Bild des Tages") to SaverPictures.BING,
    )

    private fun checkIdle() {
        val act = prefs.idleAction
        if (act == 0 || menu.isOpen || movingPos >= 0 || !hasWindowFocus()) return
        if (SystemClock.elapsedRealtime() - lastInput < prefs.idleMinutes * 60_000L) return
        lastInput = SystemClock.elapsedRealtime()
        if (act == 1) startSaver() else goSleep()
    }

    // ================================================================== clickable status bar

    private fun statusGroups(): List<View> =
        listOf(R.id.weatherBox, R.id.netBox, R.id.vpnBox, R.id.btBox, R.id.notifBox).map { findViewById<View>(it) }

    private fun setupStatusClicks() {
        val d = resources.displayMetrics.density
        val pad = (6 * d).toInt()
        val actions = mapOf<Int, () -> Unit>(
            R.id.weatherBox to { weatherPanel() },
            R.id.netBox to { networkPanel() },
            R.id.vpnBox to { vpnPanel() },
            R.id.btBox to { bluetoothPanel() },
            R.id.notifBox to { notificationsPanel() },
            R.id.diskBox to { startActivity(Intent(this, StorageActivity::class.java)) },
        )
        for ((id, act) in actions) {
            val v = findViewById<View>(id)
            v.isFocusable = true
            v.isClickable = true
            v.setPadding(pad, pad / 2, pad, pad / 2)
            if (Build.VERSION.SDK_INT >= 26) v.defaultFocusHighlightEnabled = false
            v.setOnFocusChangeListener { view, has -> view.background = if (has) GlassDrawable(d, 14, true, 12f) else null }
            v.setOnClickListener { act() }
        }
    }

    private fun weatherPanel() {
        val title = T.s("Weather", "Vrijeme", "Wetter") + " – " + prefs.city
        toast(T.s("Loading…", "Učitavam…", "Lade…"))
        Thread {
            val days = Weather.forecast(prefs.lat, prefs.lon)
            handler.post {
                val items = days.map { (day, icon, temps) -> GlassMenu.Item(day, icon, value = temps) } +
                    GlassMenu.Item(T.s("Change city", "Promijeni grad", "Stadt ändern"), R.drawable.lc_map_pin) { setCity() } +
                    GlassMenu.Item(T.s("Refresh", "Osvježi", "Aktualisieren"), R.drawable.lc_refresh_cw) { lastWeather = 0; updateWeather() } +
                    GlassMenu.Item(T.s("Update every", "Osvježavaj svakih", "Aktualisieren alle"), R.drawable.lc_refresh_cw,
                        value = "${prefs.weatherMin} min") {
                        choose(T.s("Update every", "Osvježavaj svakih", "Aktualisieren alle"), R.drawable.lc_refresh_cw,
                            listOf(15, 30, 60, 120).map { "$it min" to it }, prefs.weatherMin) { prefs.weatherMin = it }
                    }
                menu.show(title, R.drawable.lc_cloud_sun, items,
                    if (days.isEmpty()) T.s("No forecast right now (internet?)", "Prognoza trenutno nije dostupna (internet?)", "Keine Vorhersage (Internet?)") else null)
            }
        }.start()
    }

    private fun networkPanel() {
        val st = NetInfo.read(this)
        val head = when {
            st.eth -> "LAN (Ethernet)"
            st.wifi -> "Wi-Fi" + (st.ssid?.let { " – $it" } ?: "")
            else -> T.s("Not connected", "Nije povezano", "Nicht verbunden")
        }
        val info = (listOf(head) + NetDetails.lines(this)).joinToString("\n")
        menu.show(T.s("Network", "Mreža", "Netzwerk"), if (st.eth) R.drawable.lc_ethernet_port else R.drawable.lc_wifi, listOf(
            GlassMenu.Item(T.s("Wi-Fi settings", "Wi-Fi postavke", "WLAN-Einstellungen"), R.drawable.lc_wifi) { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            GlassMenu.Item(T.s("Network settings", "Mrežne postavke", "Netzwerkeinstellungen"), R.drawable.lc_ethernet_port) {
                try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (e: Exception) { safeStart(Intent(Settings.ACTION_SETTINGS)) }
            },
            GlassMenu.Item("Private DNS", R.drawable.lc_shield_check, value = dnsLabel()) { dnsMenu() },
        ), info)
    }

    private fun vpnPanel() {
        val on = NetInfo.read(this).vpn
        val pm = packageManager
        val vpnApps = pm.queryIntentServices(Intent("android.net.VpnService"), 0)
            .map { it.serviceInfo.packageName }.distinct()
            .filter { it != packageName && (pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it)) != null }
        val items = vpnApps.map { p ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
            val icon = runCatching { pm.getApplicationIcon(p) }.getOrNull()
            GlassMenu.Item(T.s("Open ", "Otvori ", "Öffnen: ") + label, drawable = icon) { launch(p, label) }
        } + GlassMenu.Item(T.s("VPN settings (Android)", "VPN postavke (Android)", "VPN-Einstellungen (Android)"), R.drawable.lc_settings) {
            try { startActivity(Intent("android.settings.VPN_SETTINGS")) } catch (e: Exception) { safeStart(Intent(Settings.ACTION_SETTINGS)) }
        }
        menu.show("VPN", R.drawable.lc_key_round, items,
            if (on) T.s("VPN is ON – connection is protected.", "VPN je UKLJUČEN – veza je zaštićena.", "VPN ist AN – Verbindung geschützt.")
            else T.s("VPN is OFF.", "VPN je ISKLJUČEN.", "VPN ist AUS."))
    }

    private fun openBluetoothSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (e: Exception) { safeStart(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun bluetoothPanel() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 77)
        val bt = BtInfo.read(this)
        val items = mutableListOf<GlassMenu.Item>()
        items.add(GlassMenu.Item(T.s("Bluetooth on", "Bluetooth uključen", "Bluetooth an"), R.drawable.lc_bluetooth,
            checked = bt.on, toggle = true) {
            if (!BtInfo.setEnabled(this, it.checked)) { it.checked = !it.checked; openBluetoothSettings() }
            handler.postDelayed({ updateStatus() }, 1500)
        })
        for (dv in bt.devices) {
            items.add(GlassMenu.Item(dv.name, R.drawable.lc_bluetooth,
                value = if (dv.connected) T.s("connected", "povezan", "verbunden") else T.s("paired", "uparen", "gekoppelt"),
                tint = if (dv.connected) Color.rgb(96, 170, 255) else null) { openBluetoothSettings() })
        }
        items.add(GlassMenu.Item(T.s("Pair a new device", "Upari novi uređaj", "Neues Gerät koppeln"), R.drawable.lc_plus) {
            // Android TV's own pairing screen for remotes, gamepads, headphones and speakers
            try { startActivity(Intent("com.google.android.intent.action.CONNECT_INPUT")) } catch (e: Exception) { openBluetoothSettings() }
        })
        items.add(GlassMenu.Item(T.s("Bluetooth settings", "Bluetooth postavke", "Bluetooth-Einstellungen"), R.drawable.lc_settings) { openBluetoothSettings() })
        val sub = when {
            !bt.available -> T.s("This device has no Bluetooth.", "Ovaj uređaj nema Bluetooth.", "Dieses Gerät hat kein Bluetooth.")
            !bt.on -> T.s("Bluetooth is OFF.", "Bluetooth je ISKLJUČEN.", "Bluetooth ist AUS.")
            bt.connectedCount == 0 -> T.s("Bluetooth is ON – nothing connected.", "Bluetooth je UKLJUČEN – ništa nije povezano.", "Bluetooth ist AN – nichts verbunden.")
            else -> T.s("Connected: ", "Povezano: ", "Verbunden: ") + bt.devices.filter { it.connected }.joinToString(", ") { it.name }
        }
        menu.show("Bluetooth", R.drawable.lc_bluetooth, items, sub)
    }

    private fun notifModeName(m: Int) = when (m) {
        0 -> T.s("Important", "Važna", "Wichtige", "Важные")
        1 -> T.s("Critical only", "Samo kritična", "Nur kritische", "Только критичные")
        2 -> T.s("All", "Sva", "Alle", "Все")
        else -> T.s("None", "Nijedno", "Keine", "Нет")
    }

    /** notification center: updates first (a click goes straight to the update), then the apps' notifications */
    private fun notificationsPanel() {
        val pm = packageManager
        val items = mutableListOf<GlassMenu.Item>()
        val updOn = Updater.notifyOn(this)
        if (updOn) Updater.pending(this)?.let { p ->
            items.add(GlassMenu.Item(T.s("New LEAD version ", "Nova LEAD verzija ", "Neue LEAD-Version ") + p.name, R.drawable.lc_download) { updateMenu() })
        }
        if (updOn && prefs.appUpdates) for (u in AppUpdates.list(this)) {
            items.add(GlassMenu.Item(T.s("Update: ", "Ažuriranje: ", "Update: ") + u.label, drawable = runCatching { pm.getApplicationIcon(u.pkg) }.getOrNull(),
                value = u.local + " → " + u.remote) { appUpdateScreen(u) })
        }
        val list = if (NotifListener.connected) NotifListener.current() else emptyList()
        for (sbn in list) {
            val ex = sbn.notification.extras
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app
            val txt = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            // OK = opens what the notification is about; long press = clear / hide this app
            items.add(GlassMenu.Item(if (txt != null) "$title – $txt" else title,
                drawable = runCatching { pm.getApplicationIcon(sbn.packageName) }.getOrNull(),
                longAction = { _: GlassMenu.Item ->
                    menu.show(title, R.drawable.lc_bell, listOf(
                        GlassMenu.Item(T.s("Clear", "Obriši", "Löschen"), R.drawable.lc_x) {
                            NotifListener.clear(sbn); handler.postDelayed({ updateStatus(); notificationsPanel() }, 300)
                        },
                        GlassMenu.Item(T.s("Never show from ", "Nikad ne prikazuj od ", "Nie mehr anzeigen von ") + app, R.drawable.lc_eye) {
                            prefs.mutedNotifs = prefs.mutedNotifs + sbn.packageName
                            NotifListener.muted = prefs.mutedNotifs
                            updateStatus(); handler.postDelayed({ notificationsPanel() }, 200)
                        },
                    ), txt)
                }) {
                val opened = runCatching { sbn.notification.contentIntent?.send(); sbn.notification.contentIntent != null }.getOrDefault(false)
                if (!opened) launch(sbn.packageName, app)
                if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) NotifListener.clear(sbn)
            })
        }
        if (items.isEmpty()) items.add(GlassMenu.Item(T.s("No notifications", "Nema obavještenja", "Keine Benachrichtigungen"), R.drawable.lc_check))
        items.add(GlassMenu.header(T.s("Settings", "Postavke", "Einstellungen")))
        if (list.isNotEmpty()) items.add(GlassMenu.Item(T.s("Clear all", "Obriši sve", "Alle löschen"), R.drawable.lc_trash_2) {
            confirm(T.s("Clear all", "Obriši sve", "Alle löschen"), R.drawable.lc_trash_2,
                T.s("Delete all notifications?", "Obrisati sva obavještenja?", "Alle Benachrichtigungen löschen?", "Удалить все уведомления?")) {
                NotifListener.clearAll(); handler.postDelayed({ updateStatus() }, 400)
            }
        })
        items.add(GlassMenu.Item(T.s("Which notifications", "Koja obavještenja", "Welche Benachrichtigungen"), R.drawable.lc_bell, value = notifModeName(prefs.notifMode)) {
            choose(T.s("Which notifications", "Koja obavještenja", "Welche Benachrichtigungen"), R.drawable.lc_bell,
                (0..3).map { notifModeName(it) to it }, prefs.notifMode) { prefs.notifMode = it; NotifListener.mode = it; updateStatus(); notificationsPanel() }
        })
        items.add(GlassMenu.Item(T.s("Update notifications", "Obavještenja o ažuriranjima", "Update-Benachrichtigungen"), R.drawable.lc_download,
            checked = updOn, toggle = true) { Updater.setNotifyOn(this, it.checked); updateBell(); updateStatus() })
        items.add(GlassMenu.Item(T.s("Hide empty system notices", "Sakrij prazna sistemska obavještenja", "Leere Systemhinweise ausblenden", "Скрывать пустые системные уведомления"),
            R.drawable.lc_eye, checked = prefs.hideSysNotifs, toggle = true) {
            prefs.hideSysNotifs = it.checked; NotifListener.hideJunk = it.checked; updateStatus()
        })
        val muted = prefs.mutedNotifs
        if (muted.isNotEmpty()) items.add(GlassMenu.Item(T.s("Hidden apps", "Skrivene aplikacije", "Ausgeblendete Apps"), R.drawable.lc_eye, value = muted.size.toString()) {
            menu.show(T.s("Hidden apps", "Skrivene aplikacije", "Ausgeblendete Apps"), R.drawable.lc_eye, muted.map { pkg ->
                val n = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                GlassMenu.Item(n, drawable = runCatching { pm.getApplicationIcon(pkg) }.getOrNull(), checked = false, toggle = true) {
                    prefs.mutedNotifs = if (it.checked) prefs.mutedNotifs - pkg else prefs.mutedNotifs + pkg
                    NotifListener.muted = prefs.mutedNotifs; updateStatus()
                }
            }, T.s("Green = show again", "Zeleno = ponovo prikazuj", "Grün = wieder anzeigen"))
        })
        if (!NotifListener.connected) items.add(GlassMenu.Item(T.s("Allow notification access", "Dozvoli pristup obavještenjima", "Benachrichtigungszugriff erlauben"),
            R.drawable.lc_check) { notificationAccess() })
        menu.show(T.s("Notifications", "Obavještenja", "Benachrichtigungen") + " (${bellCount()})", R.drawable.lc_bell, items,
            T.s("OK = open · long press = clear or hide", "OK = otvori · dugi pritisak = obriši ili sakrij", "OK = öffnen · lange drücken = löschen oder ausblenden"))
    }


    // ------------------------------------------------------------------ update

    private var appCheckRunning = false

    /** Updates for the other apps: list, check now, interval, on/off. */
    private fun appUpdatesMenu() {
        val list = AppUpdates.list(this)
        val items = mutableListOf<GlassMenu.Item>()
        for (u in list) {
            items.add(GlassMenu.Item(u.label, drawable = runCatching { packageManager.getApplicationIcon(u.pkg) }.getOrNull(),
                value = u.local + " → " + u.remote + if (u.source == "github") "  ⬇" else "  ▶",
                longAction = { _: GlassMenu.Item ->
                    menu.show(u.label, R.drawable.lc_package, listOf(
                        GlassMenu.Item(T.s("Update", "Ažuriraj", "Aktualisieren"), R.drawable.lc_download) { startAppUpdate(u) },
                        GlassMenu.Item(T.s("Skip this version", "Preskoči ovu verziju", "Diese Version überspringen"), R.drawable.lc_x) {
                            AppUpdates.ignore(this, u); showUpdateChip(false)
                        },
                    ), u.pkg)
                }) { startAppUpdate(u) })
        }
        val last = AppUpdates.lastCheck(this)
        items.add(GlassMenu.Item(T.s("Check now", "Provjeri sada", "Jetzt prüfen"), R.drawable.lc_refresh_cw,
            value = if (last == 0L) "–" else java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last))) {
            checkAppUpdates()
        })
        val every = with(AppUpdates) { this@MainActivity.intervalHours }
        items.add(GlassMenu.Item(T.s("Check every", "Provjeravaj svakih", "Prüfen alle"), R.drawable.lc_clock, value = "$every h") {
            menu.show(T.s("Check every", "Provjeravaj svakih", "Prüfen alle"), R.drawable.lc_clock, listOf(1, 3, 6, 12, 24).map { h ->
                GlassMenu.Item("$h h", checked = h == every) { with(AppUpdates) { this@MainActivity.intervalHours = h }; appUpdatesMenu() }
            })
        })
        items.add(GlassMenu.Item(T.s("Update GitHub apps at night (Shizuku)", "Noću ažuriraj GitHub aplikacije (Shizuku)", "GitHub-Apps nachts aktualisieren (Shizuku)"),
            R.drawable.lc_moon, checked = prefs.nightUpdates, toggle = true) { prefs.nightUpdates = it.checked })
        items.add(GlassMenu.Item(T.s("Check automatically", "Automatska provjera", "Automatisch prüfen"), R.drawable.lc_bell,
            checked = prefs.appUpdates, toggle = true) { prefs.appUpdates = it.checked; showUpdateChip(false) })
        menu.show(T.s("App updates", "Ažuriranja aplikacija", "App-Updates"), R.drawable.lc_download, items,
            if (list.isEmpty()) T.s("All apps are up to date", "Sve aplikacije su ažurne", "Alle Apps sind aktuell")
            else T.s("⬇ = installs directly · ▶ = opens the store · long press = more",
                "⬇ = instalira direktno · ▶ = otvara prodavnicu · dugi pritisak = više",
                "⬇ = installiert direkt · ▶ = öffnet den Store · lange drücken = mehr"))
    }

    private fun startAppUpdate(u: AppUpdates.Update) {
        menu.close()
        AppUpdates.update(this, u) { msg -> toast(msg) }
    }

    private fun checkAppUpdates() {
        if (appCheckRunning) { toast(T.s("Already checking…", "Već provjeravam…", "Prüfe schon…")); return }
        appCheckRunning = true
        menu.close()
        toast(T.s("Checking apps… (takes a minute)", "Provjeravam aplikacije… (traje minutu)", "Prüfe Apps… (dauert eine Minute)"))
        Thread {
            val l = runCatching { AppUpdates.checkAll(this) }.getOrDefault(emptyList())
            handler.post {
                appCheckRunning = false
                showUpdateChip(animate = l.isNotEmpty())
                if (l.isEmpty()) toast(T.s("All apps are up to date", "Sve aplikacije su ažurne", "Alle Apps sind aktuell"))
                else appUpdatesMenu()
            }
        }.start()
    }

    /** presses on "Installed version" – 7 unlock the Beta options on this box (like Android's developer mode) */
    private var betaTaps = 0

    private fun updateMenu() {
        val url = prefs.updateUrl
        val cur = Updater.currentName(this)
        val isBeta = Updater.installedIsBeta(this)
        val dev = Updater.devBox(this)
        val p = Updater.pending(this)
        val gold = Color.rgb(214, 182, 72)
        val items = mutableListOf<GlassMenu.Item>()
        // always first: 7 × OK here makes this box a Beta box (only on Svire's own boxes)
        items.add(GlassMenu.Item(T.s("Installed version", "Instalirana verzija", "Installierte Version"),
            if (isBeta) R.drawable.lc_flask_conical else R.drawable.lc_shield_check, tint = if (isBeta) null else gold,
            value = "$cur · " + if (isBeta) "BETA" else "STABLE") {
            if (!dev && ++betaTaps >= 7) {
                betaTaps = 0; Updater.setDevBox(this, true)
                toast(T.s("Beta options unlocked on this box", "Beta opcije otključane na ovom boxu", "Beta-Optionen auf dieser Box freigeschaltet"))
            }
            updateMenu()
        })
        if (p != null) items.add(GlassMenu.Item(T.s("Update to ", "Ažuriraj na ", "Aktualisieren auf ") + p.name, R.drawable.lc_download,
            value = if (p.code > Updater.stableCode(this)) "BETA" else "STABLE") { installUpdate(p) })
        items.add(GlassMenu.Item(T.s("Check now", "Provjeri sada", "Jetzt prüfen"), R.drawable.lc_refresh_cw) { checkUpdate(manual = true) })
        items.add(GlassMenu.Item(T.s("What's new", "Šta je novo", "Was ist neu"), R.drawable.lc_info) { whatsNew() })
        if (dev) {
            items.add(GlassMenu.header("Beta"))
            items.add(GlassMenu.Item(T.s("Beta versions on this box", "Beta verzije na ovom boxu", "Beta-Versionen auf dieser Box"), R.drawable.lc_flask_conical,
                checked = Updater.beta(this), toggle = true) { item ->
                Updater.setBeta(this, item.checked)
                if (!item.checked) {
                    Updater.forgetPending(this); showUpdateChip(false)
                    // Android never installs an older version over a newer one: this box waits for the next Stable
                    if (Updater.installedIsBeta(this)) toast(T.s("Stable only from now on. This box keeps $cur until a newer Stable comes out.",
                        "Od sada samo Stable. Box ostaje na $cur dok ne izađe novija Stable verzija.",
                        "Ab jetzt nur Stable. Die Box bleibt auf $cur, bis eine neuere Stable-Version erscheint.",
                        "Теперь только Stable. Приставка остаётся на $cur, пока не выйдет более новая Stable."))
                    else checkUpdate(manual = false)
                } else checkUpdate(manual = false)
            })
            if (isBeta || (p != null && p.code > Updater.stableCode(this)))
                items.add(GlassMenu.Item(T.s("Mark as Stable (for all boxes)", "Označi kao Stable (za sve boxove)", "Als Stable markieren (für alle Boxen)"),
                    R.drawable.lc_shield_check, tint = gold) { markStable() })
            items.add(GlassMenu.Item(T.s("Update address (URL)", "Adresa za update (link)", "Update-Adresse (URL)"), R.drawable.lc_link,
                value = if (url.isBlank()) "–" else url.substringAfter("://").take(24)) {
                askText(T.s("Link to update.json", "Link do update.json", "Link zu update.json"), url.ifBlank { "https://" }) { prefs.updateUrl = it.trim() }
            })
        }
        val stable = Updater.stableName(this)
        menu.show(T.s("LEAD update", "Ažuriranje LEAD-a", "LEAD-Update"), R.drawable.lc_refresh_cw, items,
            (if (stable.isNotBlank()) "Stable: $stable · " else "") +
                T.s("Downloads are checked (SHA-256) before installing.", "Preuzimanje se provjerava (SHA-256) prije instalacije.",
                    "Downloads werden vor dem Installieren geprüft (SHA-256)."))
    }


    /** one LEAD version: one click on "Update now" (comes straight from the notification) */
    private fun leadUpdateScreen(info: Updater.Info) {
        val beta = info.code > Updater.stableCode(this) && Updater.stableCode(this) > 0
        menu.show("LEAD " + info.name + " · " + if (beta) "BETA" else "STABLE", R.drawable.lc_download, listOf(
            GlassMenu.Item(T.s("Update now", "Ažuriraj sada", "Jetzt aktualisieren"), R.drawable.lc_download,
                value = Updater.currentName(this) + " → " + info.name) { installUpdate(info) },
            GlassMenu.Item(T.s("Later", "Kasnije", "Später"), R.drawable.lc_x),
            GlassMenu.Item(T.s("What's new", "Šta je novo", "Was ist neu"), R.drawable.lc_info) {
                menu.page(T.s("What's new", "Šta je novo", "Was ist neu") + " · " + info.name, R.drawable.lc_info, info.notes.ifBlank { "–" })
            },
            GlassMenu.Item(T.s("Update settings", "Postavke ažuriranja", "Update-Einstellungen"), R.drawable.lc_settings) { updateMenu() },
        ))
    }

    /** one app: one click on "Update now" */
    private fun appUpdateScreen(u: AppUpdates.Update) {
        menu.show(u.label, R.drawable.lc_package, listOf(
            GlassMenu.Item(T.s("Update now", "Ažuriraj sada", "Jetzt aktualisieren"), R.drawable.lc_download,
                value = u.local + " → " + u.remote) { startAppUpdate(u) },
            GlassMenu.Item(T.s("Later", "Kasnije", "Später"), R.drawable.lc_x),
            GlassMenu.Item(T.s("Skip this version", "Preskoči ovu verziju", "Diese Version überspringen"), R.drawable.lc_skip_forward) {
                AppUpdates.ignore(this, u); showUpdateChip(false)
            },
            GlassMenu.Item(T.s("All app updates", "Sva ažuriranja aplikacija", "Alle App-Updates"), R.drawable.lc_layout_grid) { appUpdatesMenu() },
        ), if (u.source == "github") T.s("Installs directly from GitHub.", "Instalira se direktno sa GitHuba.", "Wird direkt von GitHub installiert.")
            else T.s("Opens the store page.", "Otvara stranicu u prodavnici.", "Öffnet die Store-Seite."))
    }

    /** from the notification: one app = its update screen, more = the list */
    private fun openAppUpdates() {
        val l = AppUpdates.list(this)
        if (l.size == 1) appUpdateScreen(l[0]) else appUpdatesMenu()
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
            if (info != null) Updater.remember(this, info)
            if (info != null && !manual) Updater.notify(this, info)
            handler.post {
                if (info == null) {
                    if (manual) toast(T.s("You have the latest version", "Imaš najnoviju verziju", "Du hast die neueste Version"))
                    showUpdateChip(animate = false)
                    return@post
                }
                showUpdateChip(animate = !manual)
                // found: only the notification / chip; asked for by hand: the update screen
                if (manual) leadUpdateScreen(info)
            }
        }.start()
    }

    private fun showUpdateOffer(info: Updater.Info) {
        menu.show(T.s("New version ", "Nova verzija ", "Neue Version ") + info.name, R.drawable.lc_download, listOf(
            GlassMenu.Item(T.s("Install now", "Instaliraj sada", "Jetzt installieren"), R.drawable.lc_download) { installUpdate(info) },
            GlassMenu.Item(T.s("Later", "Kasnije", "Später"), R.drawable.lc_x),
        ))
    }

    private var chipPulse: android.animation.ValueAnimator? = null

    /** "New version" chip in the top bar: pops in with a little bounce, then glows softly. */
    /** updates are never written on the screen: they only count at the bell (number in the top row) */
    @Suppress("UNUSED_PARAMETER")
    private fun showUpdateChip(animate: Boolean) {
        chipPulse?.cancel(); chipPulse = null
        updateBtn.visibility = View.GONE
        updateBell()
    }

    /** Notification, chip and "Install": one click – download and open the installer straight away. */
    private fun installPendingOrCheck() {
        val p = Updater.pending(this)
        if (p != null && p.apk.isNotBlank()) leadUpdateScreen(p)
        else if (p == null && prefs.appUpdates && AppUpdates.list(this).isNotEmpty()) openAppUpdates()
        else checkUpdate(manual = true)
    }

    private var installing = false

    /** version for which the "install?" question was already shown in this session */
    private var promptedCode = 0L
    private var resumed = false

    private fun installUpdate(info: Updater.Info) {
        if (installing) {
            toast(T.s("Already downloading – the install question comes by itself", "Već preuzimam – pitanje za instalaciju dolazi samo",
                "Lade schon – die Installationsfrage kommt von selbst"))
            return
        }
        promptedCode = info.code
        val ready = Updater.ready(this, info)
        if (ready != null) { Updater.install(this, ready); return }
        installing = true
        handler.postDelayed({ installing = false }, 120_000)
        toast(T.s("Downloading ", "Preuzimam ", "Lade ") + info.name + "…")
        Thread {
            try {
                val f = Updater.download(this, info)
                handler.post {
                    installing = false
                    // ask only while the home screen is on top; otherwise the chip / next return asks
                    if (resumed) Updater.install(this, f) else promptedCode = 0L
                }
            } catch (e: Exception) {
                handler.post { installing = false; toast(T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen")) }
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
                handler.post { installing = false; toast(T.s("Download failed", "Preuzimanje nije uspjelo", "Download fehlgeschlagen")) }
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


    // ================================================================== LEAD v10 – Modern Taupe & Charcoal

    private lateinit var nav: V10Nav
    private lateinit var pageHost: FrameLayout
    private var openPageIx = -1

    /** one-time switch to v10: new look for everybody; the three old looks and profiles are gone */
    private fun v10Migrate() {
        val g = getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        if (g.getBoolean("v10", false)) return
        prefs.fitScreen = false; prefs.reflect = false; prefs.focusScale = 0; prefs.focusFrame = false
        prefs.singleRow = false; prefs.gridPosition = 1; prefs.gridAlign = 1; prefs.gridOffX = 0; prefs.gridOffY = 0
        prefs.showLabels = true; prefs.nameOnFocus = false; prefs.dock = false
        prefs.leadWp = 0; prefs.wpMode = 0
        // a picture of your own is kept aside (not deleted) – v10 uses the living-room wallpaper everywhere
        wallpaperFile.takeIf { it.exists() }?.renameTo(File(filesDir, "wallpaper_before_v10"))
        // Media Center and YouTube get their v10 logos once
        runCatching { IconStore.file(this, DefaultApps.PRETORIAN).delete(); IconStore.file(this, DefaultApps.TIZENTUBE).delete() }
        runCatching { BuiltinIcons.restore(this, DefaultApps.PRETORIAN); BuiltinIcons.restore(this, DefaultApps.TIZENTUBE) }
        g.edit().putBoolean("v10", true).apply()
    }

    private fun v10Setup() {
        val d = resources.displayMetrics.density
        nav = V10Nav(this, { i ->
            // OK on a menu: its content shows below, the cursor stays up on the menu (Down goes into it)
            if (i == V10Nav.POWER) v10Power() else openPage(i, keepFocus = true)
        }, { i ->
            // while a page is open, moving along the menu shows each page at once
            if (openPageIx >= 0 && i != openPageIx && i != V10Nav.POWER) openPage(i, keepFocus = true)
        })
        val at = root.indexOfChild(updateBtn)
        pageHost = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false; clipToPadding = false }
        root.addView(pageHost, at, FrameLayout.LayoutParams(-1, -1))
        root.addView(nav.view, at + 1, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = (18 * d).toInt() })
        // the profile button is gone for good (no profiles in v10)
        profileBtn.visibility = View.GONE
        applyWidgets()
        updateNavBadge()
    }

    /** Clock & widgets (off by default): the status row comes back under the navigation, display only */
    private fun applyWidgets() {
        val d = resources.displayMetrics.density
        val top = findViewById<View>(R.id.topRow)
        top.visibility = if (prefs.v10Widgets && openPageIx < 0) View.VISIBLE else View.GONE
        val column = top.parent as View
        column.setPadding(column.paddingLeft, ((if (prefs.v10Widgets) 78 else 92) * d).toInt(), column.paddingRight, column.paddingBottom)
    }

    /** About shows how many updates are waiting (no banners or chips on Home) */
    private fun updateNavBadge() { if (::nav.isInitialized) nav.badge(4, runCatching { updCount() }.getOrDefault(0)) }

    private fun isIn(parent: View, v: View?): Boolean { var p: View? = v; while (p != null) { if (p === parent) return true; p = p.parent as? View }; return false }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE && v !is ViewGroup) return v
        if (v.isFocusable && v.visibility == View.VISIBLE && v is LinearLayout && v.isClickable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    /** remote keys of the v10 shell; true = handled */
    private fun v10Key(ev: KeyEvent): Boolean {
        if (!::nav.isInitialized) return false
        val k = ev.keyCode
        val f = currentFocus
        val down = ev.action == KeyEvent.ACTION_DOWN
        if (k == KeyEvent.KEYCODE_BACK) {
            if (openPageIx >= 0) {
                // inside a page: first back up to its menu, then (from the menu) back to Home
                if (!down) { if (isIn(pageHost, f)) nav.focus(openPageIx) else closePage() }
                return true
            }
            if (nav.has(f)) { if (!down) focusTile(lastPos); return true }
            return false
        }
        if (!down) return false
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (nav.has(f)) return true
                if (openPageIx < 0 && f != null && isInGrid(f)) { nav.focus(0); return true }
                if (openPageIx >= 0 && f != null && isIn(pageHost, f)) {
                    val next = f.focusSearch(View.FOCUS_UP)
                    if (next == null || !isIn(pageHost, next)) { nav.focus(openPageIx); return true }
                }
                // Undo note showing: Up from it goes back
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (openPageIx < 0 && movingPos < 0 && f != null && isInGrid(f) && apps.size > 1) {
                val p = focusedPos()
                val last = apps.size - 1
                val wrap = if (k == KeyEvent.KEYCODE_DPAD_LEFT && p == 0) last else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && p == last) 0 else -1
                if (wrap >= 0) { lastPos = wrap; focusTile(wrap); return true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (nav.has(f)) {
                    if (openPageIx >= 0) firstFocusable(pageHost)?.requestFocus() else focusTile(lastPos)
                    return true
                }
                if (openPageIx >= 0 && f != null && isIn(pageHost, f)) {
                    val next = f.focusSearch(View.FOCUS_DOWN)
                    if (next == null || !isIn(pageHost, next)) return true   // stay on the page
                }
                if (openPageIx < 0 && f != null && isInGrid(f)) {
                    V10Undo.button()?.let { b ->
                        val p = focusedPos()
                        if (p != RecyclerView.NO_POSITION) { b.requestFocus(); return true }
                    }
                }
            }
        }
        return false
    }

    /** a category page replaces Home completely (one menu at a time) */
    private fun openPage(i: Int, keepFocus: Boolean = false) {
        openPageIx = i
        nav.select(i)
        val (title, sub, tiles) = pageContent(i)
        pageHost.removeAllViews()
        pageHost.addView(V10Page.build(this, title, sub, tiles), FrameLayout.LayoutParams(-1, -1))
        pageHost.visibility = View.VISIBLE
        gridArea.visibility = View.INVISIBLE
        findViewById<View>(R.id.movieInfo).visibility = View.GONE
        applyWidgets()
        if (!keepFocus) pageHost.post { firstFocusable(pageHost)?.requestFocus() }
    }

    /** Power (top-right): Restart, Shut down, Screensaver – one Yes / No, then it happens */
    private fun v10Power() {
        fun ask(title: String, q: String, go: () -> Unit) = V10Dialog.show(root, title, q,
            listOf(T.s("No", "Ne", "Nein", "Нет") to {}, T.s("Yes", "Da", "Ja", "Да") to go), focusIndex = 1)
        V10List.show(root, T.s("Power", "Napajanje", "Energie", "Питание"), listOf(
            V10List.Entry(T.s("Restart", "Restart", "Neustart", "Перезагрузка"), R.drawable.lc_rotate_cw) {
                ask(T.s("Restart", "Restart", "Neustart", "Перезагрузка"), T.s("Restart the box now?", "Restartovati box sada?", "Box jetzt neu starten?", "Перезагрузить сейчас?")) { restartBox() }
            },
            V10List.Entry(T.s("Shut down", "Ugasi", "Ausschalten", "Выключить"), R.drawable.lc_power) {
                ask(T.s("Shut down", "Ugasi", "Ausschalten", "Выключить"), T.s("Switch the box off now?", "Ugasiti box sada?", "Box jetzt ausschalten?", "Выключить сейчас?")) { goSleep() }
            },
            V10List.Entry(T.s("Screensaver", "Screensaver", "Bildschirmschoner", "Заставка"), R.drawable.lc_monitor) {
                ask(T.s("Screensaver", "Screensaver", "Bildschirmschoner", "Заставка"), T.s("Start the screensaver now?", "Pokrenuti screensaver sada?", "Bildschirmschoner jetzt starten?", "Запустить заставку?")) { startSaver() }
            },
        ), onClose = { nav.focus(V10Nav.POWER) })
    }

    /** waiting LEAD + app updates (notifications are not updates) */
    private fun updCount(): Int {
        if (!Updater.notifyOn(this)) return 0
        return (if (Updater.pending(this) != null) 1 else 0) + (if (prefs.appUpdates) AppUpdates.list(this).size else 0)
    }

    private fun closePage() {
        val i = openPageIx
        openPageIx = -1
        pageHost.visibility = View.GONE
        pageHost.removeAllViews()
        gridArea.visibility = View.VISIBLE
        applyWidgets()
        nav.select(0)
        nav.focus(i.coerceAtLeast(0))
    }

    private fun pageContent(i: Int): Triple<String, String, List<V10Tile>> = when (i) {
        0 -> Triple(T.s("Apps", "Aplikacije", "Apps", "Приложения"), T.s("Manage your applications", "Upravljaj svojim aplikacijama", "Deine Apps verwalten", "Управление приложениями"), listOf(
            V10Tile(T.s("Add to Home", "Dodaj na početni", "Zum Start hinzufügen", "На главный экран"), R.drawable.lc_square_plus) { showAddMenu() },
            V10Tile(T.s("All Apps", "Sve aplikacije", "Alle Apps", "Все приложения"), R.drawable.lc_layout_grid) { allAppsMenu() },
            V10Tile(T.s("Manage Apps", "Upravljanje", "Apps verwalten", "Управление"), R.drawable.lc_folder_cog) { manageAppsMenu() },
            V10Tile(T.s("Install Apps", "Instaliraj", "Apps installieren", "Установка"), R.drawable.lc_download) { installAppsMenu() },
            V10Tile(T.s("Hidden Apps", "Skrivene", "Versteckte Apps", "Скрытые"), R.drawable.lc_eye_off,
                prefs.hiddenApps.size.takeIf { it > 0 }?.let { "$it" }) { hiddenAppsMenu() },
            V10Tile(T.s("App Updates", "Ažuriranja", "App-Updates", "Обновления"), R.drawable.lc_refresh_cw,
                AppUpdates.list(this).size.takeIf { it > 0 }?.let { T.s("$it available", "$it dostupno", "$it verfügbar", "доступно: $it") }) { appUpdatesMenu() },
            V10Tile(T.s("Files", "Fajlovi", "Dateien", "Файлы"), R.drawable.lc_folder) { startActivity(Intent(this, FileBrowserActivity::class.java)) },
        ))
        1 -> Triple(T.s("Personalize", "Prilagodi", "Anpassen", "Персонализация"), T.s("Make LEAD yours", "Uredi LEAD po svom", "LEAD nach deinem Geschmack", "Настройте LEAD"), listOf(
            V10Tile(T.s("Wallpapers", "Pozadine", "Hintergründe", "Обои"), R.drawable.lc_wallpaper) { wallpaperMenu() },
            V10Tile(T.s("Themes & Colors", "Teme i boje", "Themen & Farben", "Темы и цвета"), R.drawable.lc_palette) { themesMenu() },
            V10Tile(T.s("Home Layout", "Raspored", "Startlayout", "Главный экран"), R.drawable.lc_columns_3) { homeLayoutMenu() },
            V10Tile(T.s("Icon Appearance", "Izgled ikona", "Symbol-Aussehen", "Вид значков"), R.drawable.lc_image) { iconAppearanceMenu() },
            V10Tile(T.s("Clock & Widgets", "Sat i widgeti", "Uhr & Widgets", "Часы и виджеты"), R.drawable.lc_clock,
                if (prefs.v10Widgets) T.s("On", "Uključeno", "An", "Вкл") else T.s("Off", "Isključeno", "Aus", "Выкл")) { widgetsMenu() },
            V10Tile(T.s("Screensaver", "Screensaver", "Bildschirmschoner", "Заставка"), R.drawable.lc_monitor) { screensaverMenu() },
        ))
        2 -> Triple(T.s("System", "Sistem", "System", "Система"), T.s("Settings for this box", "Postavke ovog uređaja", "Einstellungen dieser Box", "Настройки приставки"), listOf(
            V10Tile(T.s("General Settings", "Opće postavke", "Allgemein", "Общие"), R.drawable.lc_settings) { showSettings() },
            V10Tile(T.s("Language", "Jezik", "Sprache", "Язык"), R.drawable.lc_languages, T.languages.firstOrNull { it.first == T.lang }?.second) { languageMenu() },
            V10Tile(T.s("Remote & Navigation", "Daljinski", "Fernbedienung", "Пульт"), R.drawable.lc_gamepad) { remoteMenu() },
            V10Tile(T.s("Startup & Launcher", "Pokretanje", "Start & Launcher", "Запуск"), R.drawable.lc_rocket) { startupMenu() },
            V10Tile(T.s("Security", "Sigurnost", "Sicherheit", "Безопасность"), R.drawable.lc_lock) { securityMenu() },
            V10Tile(T.s("Permissions", "Dozvole", "Berechtigungen", "Разрешения"), R.drawable.lc_shield_check) { permissionsMenu() },
            V10Tile(T.s("Notifications", "Obavještenja", "Benachrichtigungen", "Уведомления"), R.drawable.lc_bell,
                (if (NotifListener.connected) NotifListener.current().size else 0).takeIf { it > 0 }?.toString()) { notificationsPanel() },
            V10Tile(T.s("Backup & Restore", "Backup", "Sichern", "Резервная копия"), R.drawable.lc_archive) { backupMenu() },
            V10Tile(T.s("Add-ons", "Dodaci", "Erweiterungen", "Дополнения"), R.drawable.lc_package) { addonsMenu() },
        ))
        3 -> Triple(T.s("Diagnostics", "Dijagnostika", "Diagnose", "Диагностика"), T.s("Real measurements, only while you look", "Stvarna mjerenja, samo dok gledaš", "Echte Messungen, nur solange du schaust", "Реальные измерения"), listOf(
            V10Tile(T.s("Device Health", "Stanje uređaja", "Gerätezustand", "Состояние"), R.drawable.lc_cpu) { deviceHealth() },
            V10Tile(T.s("Network Quality", "Kvalitet mreže", "Netzqualität", "Качество сети"), R.drawable.lc_activity) { networkQuality() },
            V10Tile(T.s("Network Connection", "Mrežna veza", "Netzwerk", "Подключение"), R.drawable.lc_wifi) { networkPanel() },
            V10Tile(T.s("VPN Status", "VPN status", "VPN-Status", "VPN"), R.drawable.lc_key_round) { vpnPanel() },
            V10Tile(T.s("Storage", "Prostor", "Speicher", "Хранилище"), R.drawable.lc_hard_drive) { startActivity(Intent(this, StorageActivity::class.java)) },
            V10Tile(T.s("Speed Test", "Test brzine", "Speedtest", "Тест скорости"), R.drawable.lc_gauge) { startActivity(Intent(this, SpeedTestActivity::class.java)) },
            V10Tile(T.s("Tools & Logs", "Alati i logovi", "Werkzeuge & Logs", "Инструменты и логи"), R.drawable.lc_file_text,
                CrashLog.list(this).size.takeIf { it > 0 }?.let { T.s("$it crash notes", "$it zapisa grešaka", "$it Absturzberichte", "отчётов: $it") }) { logsMenu() },
        ))
        else -> Triple(T.s("About", "O LEAD-u", "Über", "О LEAD"), "LEAD Launcher " + Updater.currentName(this), listOf(
            V10Tile(T.s("About LEAD", "O LEAD-u", "Über LEAD", "О LEAD"), R.drawable.lc_info) { aboutLead() },
            V10Tile(T.s("Launcher Version", "Verzija", "Version", "Версия"), R.drawable.lc_tv, Updater.currentName(this) + " · " + channelLabel()) { updateMenu() },
            V10Tile(T.s("Check for Updates", "Provjeri ažuriranja", "Nach Updates suchen", "Проверить обновления"), R.drawable.lc_refresh_cw,
                updCount().takeIf { it > 0 }?.let { T.s("$it waiting", "$it čeka", "$it wartet", "ожидает: $it") }) { checkForUpdates() },
            V10Tile(T.s("Release Notes", "Šta je novo", "Neuigkeiten", "Что нового"), R.drawable.lc_file_text) { whatsNew() },
            V10Tile(T.s("Support & Feedback", "Podrška", "Hilfe & Feedback", "Поддержка"), R.drawable.lc_bell) { supportPage() },
            V10Tile(T.s("Licenses & Credits", "Licence", "Lizenzen", "Лицензии"), R.drawable.lc_code) { licensesPage() },
        ))
    }

    /** Check for Updates: LEAD first, then app updates; if nothing waits, a fresh check with a clear answer */
    private fun checkForUpdates() {
        val p = Updater.pending(this)
        val apps = if (prefs.appUpdates) AppUpdates.list(this).size else 0
        when {
            p != null && p.apk.isNotBlank() -> leadUpdateScreen(p)
            apps > 0 -> openAppUpdates()
            else -> { toast(T.s("Checking…", "Provjeravam…", "Prüfe…", "Проверяю…")); checkUpdate(manual = true); checkAppUpdates() }
        }
    }

    /** reopen the page after a change so its small values are up to date (focus stays on the same card) */
    private fun refreshPage(from: View? = null) {
        if (openPageIx < 0) return
        val f = from ?: currentFocus
        val idx = ArrayList<View>().also { pageHost.addFocusables(it, View.FOCUS_DOWN) }.indexOf(f).coerceAtLeast(0)
        val (title, sub, tiles) = pageContent(openPageIx)
        pageHost.removeAllViews()
        pageHost.addView(V10Page.build(this, title, sub, tiles), FrameLayout.LayoutParams(-1, -1))
        pageHost.post {
            val all = ArrayList<View>(); pageHost.addFocusables(all, View.FOCUS_DOWN)
            (all.getOrNull(idx) ?: all.firstOrNull())?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- Apps

    private fun manageAppsMenu() {
        Thread {
            val list = AppLoader.choosable(this).filter { it.first != AppEntry.FILES }
            handler.post {
                menu.show(T.s("Manage Apps", "Upravljanje aplikacijama", "Apps verwalten", "Управление приложениями"), R.drawable.lc_folder_cog,
                    list.map { (pkg, label, icon) -> GlassMenu.Item(label, drawable = icon) { appOptions(pkg, label) } },
                    T.s("OK = open, home screen, app info, force stop, uninstall", "OK = otvori, početni ekran, info, prisilno zaustavi, deinstaliraj",
                        "OK = öffnen, Startbildschirm, App-Info, beenden, deinstallieren", "OK = открыть, главный экран, сведения, остановить, удалить"))
            }
        }.start()
    }

    /** apps LEAD knows: installed ones show ✓, the others install from GitHub (newest stable) or open Google Play */
    private fun installAppsMenu() {
        val pm = packageManager
        menu.show(T.s("Install Apps", "Instaliraj aplikacije", "Apps installieren", "Установка приложений"), R.drawable.lc_download,
            DefaultApps.CATALOG.map { a ->
                val has = runCatching { pm.getPackageInfo(a.pkg, 0); true }.getOrDefault(false)
                GlassMenu.Item(a.name, R.drawable.lc_package, checked = has,
                    value = if (has) T.s("installed", "instalirano", "installiert", "установлено") else if (a.repo != null) "GitHub" else "Google Play") {
                    if (has) launch(a.pkg, a.name) else installMissing(AppEntry(a.pkg, a.name, null, missing = true))
                }
            } + GlassMenu.Item(T.s("Install from a file (APK)…", "Instaliraj iz fajla (APK)…", "Aus Datei installieren (APK)…", "Установить из файла (APK)…"), R.drawable.lc_folder_down) {
                startActivity(Intent(this, FileBrowserActivity::class.java).putExtra(FileBrowserActivity.EXTRA_START, android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath))
            },
            T.s("Every install shows Android's own confirmation", "Svaka instalacija prikazuje Androidovu potvrdu",
                "Jede Installation zeigt Androids eigene Bestätigung", "Каждая установка показывает подтверждение Android"))
    }

    private fun hiddenAppsMenu() {
        Thread {
            val pm = packageManager
            val all = AppLoader.launchable(this).map { (p, ri) -> Triple(p, ri.loadLabel(pm).toString(), runCatching { ri.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.second.lowercase() }
            handler.post {
                val hidden = prefs.hiddenApps.toMutableSet()
                menu.show(T.s("Hidden Apps", "Skrivene aplikacije", "Versteckte Apps", "Скрытые приложения"), R.drawable.lc_eye_off,
                    all.map { (p, label, ic) ->
                        GlassMenu.Item(label, drawable = ic, checked = p in hidden, toggle = true) {
                            if (it.checked) hidden.add(p) else hidden.remove(p)
                            prefs.hiddenApps = hidden
                            if (it.checked && p in prefs.pinned) { prefs.pinned = prefs.pinned - p; reloadApps() }
                            refreshPage()
                        }
                    },
                    T.s("On = hidden from All Apps and Home", "Uključeno = skriveno iz Svih aplikacija i početnog", "An = in Alle Apps und Start versteckt", "Вкл = скрыто"))
            }
        }.start()
    }

    // ---------------------------------------------------------------- Personalize

    private fun themesMenu() {
        menu.show(T.s("Themes & Colors", "Teme i boje", "Themen & Farben", "Темы и цвета"), R.drawable.lc_palette, listOf(
            GlassMenu.Item("Modern Taupe & Charcoal", R.drawable.lc_palette, checked = true) { },
            GlassMenu.Item(T.s("Text size", "Veličina teksta", "Textgröße", "Размер текста"), R.drawable.lc_type, value = "${FontScale.percent(this)}%") { textSizeMenu() },
            GlassMenu.Item(T.s("Dim at night", "Zatamni noću", "Nachts abdunkeln", "Затемнять ночью"), R.drawable.lc_moon,
                checked = prefs.nightSoft, toggle = true) { prefs.nightSoft = it.checked; applyDim() },
        ), T.s("One calm design for the whole launcher", "Jedan miran dizajn za cijeli launcher", "Ein ruhiges Design für den ganzen Launcher", "Один спокойный дизайн"))
    }

    private fun homeLayoutMenu() {
        menu.show(T.s("Home Layout", "Raspored početnog", "Startlayout", "Главный экран"), R.drawable.lc_columns_3, listOf(
            GlassMenu.Item(T.s("Cards visible at once", "Vidljivih kartica", "Sichtbare Karten", "Видимых карточек"), R.drawable.lc_columns_3, value = "${prefs.v10Cols}") {
                choose(T.s("Cards visible at once", "Vidljivih kartica", "Sichtbare Karten", "Видимых карточек"), R.drawable.lc_columns_3,
                    (3..6).map { "$it" to it }, prefs.v10Cols) { prefs.v10Cols = it; layoutGrid() }
            },
            GlassMenu.Item(T.s("Cards at the top", "Kartice gore", "Karten oben", "Карточки сверху"), R.drawable.lc_arrow_up,
                checked = prefs.gridPosition == 0, toggle = true) { prefs.gridPosition = if (it.checked) 0 else 1; layoutGrid() },
            GlassMenu.Item(T.s("\"Add\" card", "Kartica \"Dodaj\"", "Karte \"Hinzufügen\"", "Карточка \"Добавить\""), R.drawable.lc_plus,
                checked = prefs.showAddTile, toggle = true) { prefs.showAddTile = it.checked; reloadApps() },
            GlassMenu.Item(T.s("Number keys open apps", "Brojevi otvaraju aplikacije", "Zifferntasten öffnen Apps", "Цифры открывают приложения"), R.drawable.lc_type,
                checked = prefs.numberKeys, toggle = true) { prefs.numberKeys = it.checked },
        ))
    }

    private fun iconAppearanceMenu() {
        fun set(m: Int) { prefs.iconMode = m; IconAdapt.clear(this); reloadApps(); iconAppearanceMenu() }
        menu.show(T.s("Icon Appearance", "Izgled ikona", "Symbol-Aussehen", "Вид значков"), R.drawable.lc_image, listOf(
            GlassMenu.Item(T.s("Automatic adaptation", "Automatsko prilagođavanje", "Automatische Anpassung", "Автоадаптация"), R.drawable.lc_image,
                checked = prefs.iconMode == IconAdapt.AUTO, value = T.s("recommended", "preporučeno", "empfohlen", "рекомендуется")) { set(IconAdapt.AUTO) },
            GlassMenu.Item(T.s("Original app icons", "Originalne ikone", "Originale App-Symbole", "Оригинальные значки"), R.drawable.lc_image,
                checked = prefs.iconMode == IconAdapt.ORIGINAL) { set(IconAdapt.ORIGINAL) },
            GlassMenu.Item(T.s("Monochrome / neutral", "Jednobojno / neutralno", "Einfarbig / neutral", "Монохромно"), R.drawable.lc_image,
                checked = prefs.iconMode == IconAdapt.MONO) { set(IconAdapt.MONO) },
            GlassMenu.header(T.s("Your own pictures", "Tvoje slike", "Eigene Bilder", "Свои картинки")),
            GlassMenu.Item(T.s("Reset icon overrides", "Poništi vlastite ikone", "Eigene Symbole zurücksetzen", "Сбросить свои значки"), R.drawable.lc_rotate_ccw) {
                confirm(T.s("Reset icon overrides", "Poništi vlastite ikone", "Eigene Symbole zurücksetzen", "Сбросить свои значки"), R.drawable.lc_rotate_ccw,
                    T.s("All pictures you chose go back to automatic. LEAD's built-in logos stay.", "Sve slike koje si izabrao vraćaju se na automatske. Ugrađeni logotipi ostaju.",
                        "Alle selbst gewählten Bilder werden wieder automatisch. Eingebaute Logos bleiben.", "Все выбранные картинки станут автоматическими.")) {
                    File(filesDir, "icons").listFiles()?.forEach { f -> if (!BuiltinIcons.has(f.name)) f.delete() else BuiltinIcons.restore(this, f.name) }
                    prefs.pinned.forEach { p -> prefs.setIconScale(p, 100); prefs.setIconOffset(p, 0, 0) }
                    IconAdapt.clear(this); reloadApps()
                    toast(T.s("Icons reset", "Ikone vraćene", "Symbole zurückgesetzt", "Значки сброшены"))
                }
            },
        ), T.s("Logos sit cleanly in the glass cards. A picture you choose yourself always wins.",
            "Logotipi čisto sjede u staklenim karticama. Slika koju sam izabereš uvijek ima prednost.",
            "Logos sitzen sauber in den Glaskarten. Ein selbst gewähltes Bild hat immer Vorrang.", "Логотипы аккуратно в стеклянных карточках."))
    }

    private fun widgetsMenu() {
        menu.show(T.s("Clock & Widgets", "Sat i widgeti", "Uhr & Widgets", "Часы и виджеты"), R.drawable.lc_clock, listOf(
            GlassMenu.Item(T.s("Show clock and status", "Prikaži sat i status", "Uhr und Status zeigen", "Показывать часы и статус"), R.drawable.lc_clock,
                checked = prefs.v10Widgets, toggle = true) {
                prefs.v10Widgets = it.checked
                if (it.checked) { prefs.statusMode = 0; prefs.clockMode = 0; prefs.buttonsMode = 0; setReveal(true) }
                applyWidgets(); applyLook(); refreshPage()
            },
            GlassMenu.Item(T.s("Clock", "Sat", "Uhr", "Часы"), R.drawable.lc_clock) { clockMenu() },
            GlassMenu.Item(T.s("Weather", "Vrijeme", "Wetter", "Погода"), R.drawable.lc_cloud_sun, value = prefs.city) { weatherMenu() },
            GlassMenu.Item(T.s("Status items", "Stavke statusa", "Statuselemente", "Элементы статуса"), R.drawable.lc_monitor) { statusMenu() },
        ), T.s("Off by default – Home stays calm", "Isključeno po defaultu – početni ostaje miran", "Standardmäßig aus – der Start bleibt ruhig", "По умолчанию выключено"))
    }

    // ---------------------------------------------------------------- System

    private fun remoteMenu() {
        menu.show(T.s("Remote & Navigation", "Daljinski i navigacija", "Fernbedienung & Navigation", "Пульт и навигация"), R.drawable.lc_gamepad, listOf(
            GlassMenu.Item(T.s("Number keys open apps", "Brojevi otvaraju aplikacije", "Zifferntasten öffnen Apps", "Цифры открывают приложения"), R.drawable.lc_type,
                checked = prefs.numberKeys, toggle = true) { prefs.numberKeys = it.checked },
            GlassMenu.Item(T.s("Home button", "Home dugme", "Home-Taste", "Кнопка Home"), R.drawable.lc_house) { defaultLauncherMenu() },
            GlassMenu.Item(T.s("Text size", "Veličina teksta", "Textgröße", "Размер текста"), R.drawable.lc_type, value = "${FontScale.percent(this)}%") { textSizeMenu() },
        ), T.s("OK opens · hold OK for options · Back goes one step back", "OK otvara · drži OK za opcije · Nazad ide korak nazad",
            "OK öffnet · OK halten für Optionen · Zurück geht einen Schritt zurück", "OK открывает · удерживайте OK для опций"))
    }

    private fun startupMenu() {
        menu.show(T.s("Startup & Default Launcher", "Pokretanje i zadani launcher", "Start & Standard-Launcher", "Запуск и лаунчер"), R.drawable.lc_rocket, listOf(
            GlassMenu.Item(T.s("Default launcher and Home button", "Zadani launcher i Home dugme", "Standard-Launcher und Home-Taste", "Лаунчер по умолчанию"), R.drawable.lc_house) { defaultLauncherMenu() },
            GlassMenu.Item(T.s("Apps that start with the box", "Aplikacije koje se pokreću s boxom", "Apps beim Start der Box", "Автозапуск"), R.drawable.lc_rocket) { autostartMenu() },
        ))
    }

    private fun securityMenu() {
        menu.show(T.s("Security", "Sigurnost", "Sicherheit", "Безопасность"), R.drawable.lc_lock, listOf(
            GlassMenu.Item("Private DNS", R.drawable.lc_shield_check, value = dnsLabel()) { dnsMenu() },
            GlassMenu.Item(T.s("VPN key over other apps", "VPN ključ preko drugih aplikacija", "VPN-Schlüssel über anderen Apps", "Значок VPN поверх приложений"), R.drawable.lc_key_round) { overlayMenu() },
            GlassMenu.Item(T.s("Wireless debugging", "Wireless debugging", "Drahtloses Debugging", "Отладка по Wi-Fi"), R.drawable.lc_wifi,
                checked = adbWifiOn(), toggle = true) { setAdbWifi(it.checked) },
            GlassMenu.Item(T.s("Developer options", "Opcije za programere", "Entwickleroptionen", "Для разработчиков"), R.drawable.lc_code) { openDeveloperOptions() },
        ), T.s("LEAD has no parental controls of its own; no data leaves the box.", "LEAD nema roditeljsku kontrolu; nikakvi podaci ne napuštaju box.",
            "LEAD hat keine eigene Kindersicherung; keine Daten verlassen die Box.", "Данные не покидают приставку."))
    }

    private fun permissionsMenu() {
        fun st(ok: Boolean) = if (ok) T.s("allowed", "dozvoljeno", "erlaubt", "разрешено") else T.s("missing", "nedostaje", "fehlt", "нет")
        val notif = runCatching { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) }.getOrDefault(false)
        val storage = if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
            else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        menu.show(T.s("Permissions", "Dozvole", "Berechtigungen", "Разрешения"), R.drawable.lc_shield_check, listOf(
            GlassMenu.Item(T.s("Helper service (Home button, VPN key)", "Pomoćni servis (Home dugme, VPN ključ)", "Hilfsdienst (Home-Taste, VPN-Schlüssel)", "Вспомогательная служба"),
                R.drawable.lc_shield_check, value = st(HomeService.isEnabled(this))) { enableService() },
            GlassMenu.Item(T.s("Notification access (bell)", "Pristup obavještenjima", "Benachrichtigungszugriff", "Доступ к уведомлениям"),
                R.drawable.lc_bell, value = st(notif)) { notificationAccess() },
            GlassMenu.Item(T.s("Files and USB", "Fajlovi i USB", "Dateien und USB", "Файлы и USB"), R.drawable.lc_folder, value = st(storage)) {
                withStorage { toast(T.s("Files allowed", "Fajlovi dozvoljeni", "Dateien erlaubt", "Файлы разрешены")) }
            },
            GlassMenu.Item(T.s("Secure settings (adb)", "Sigurne postavke (adb)", "Sichere Einstellungen (adb)", "Защищённые настройки (adb)"), R.drawable.lc_code, value = st(hasSecure())) { adbHelp() },
            GlassMenu.Item("Shizuku", R.drawable.lc_package, value = st(ShizukuCpu.permitted())) { shizukuHowTo() },
        ), T.s("Each one is asked through Android's own screens – never bypassed.", "Svaka se traži preko Androidovih ekrana – nikad zaobilazno.",
            "Jede wird über Androids eigene Bildschirme erfragt – nie umgangen.", "Каждое запрашивается через экраны Android."))
    }

    /** Shizuku and VLC: what is there, what is missing, and the trusted way to get it */
    private fun addonsMenu() {
        val pm = packageManager
        fun has(p: String) = runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false)
        val shz = has(Extras.SHIZUKU)
        menu.show(T.s("Add-ons", "Dodaci", "Erweiterungen", "Дополнения"), R.drawable.lc_package, listOf(
            GlassMenu.Item("Shizuku", R.drawable.lc_package, value = when {
                !shz -> T.s("not installed", "nije instaliran", "nicht installiert", "не установлен")
                !ShizukuCpu.running() -> T.s("installed, not started", "instaliran, nije pokrenut", "installiert, nicht gestartet", "не запущен")
                !ShizukuCpu.permitted() -> T.s("running, LEAD not allowed yet", "radi, LEAD još nema dozvolu", "läuft, LEAD noch nicht erlaubt", "нет разрешения")
                else -> T.s("ready", "spreman", "bereit", "готов")
            }) { if (!shz) installMissing(AppEntry(Extras.SHIZUKU, "Shizuku", null, missing = true)) else if (!ShizukuCpu.permitted()) { ShizukuCpu.ask(); shizukuHowTo() } else extrasMenu() },
            GlassMenu.Item("VLC", R.drawable.lc_play, value = if (has(DefaultApps.VLC)) T.s("installed", "instaliran", "installiert", "установлен") else "Google Play") {
                if (has(DefaultApps.VLC)) launch(DefaultApps.VLC, "VLC") else installMissing(AppEntry(DefaultApps.VLC, "VLC", null, missing = true))
            },
            GlassMenu.Item(T.s("More add-ons", "Ostali dodaci", "Weitere Erweiterungen", "Другие"), R.drawable.lc_plus) { extrasMenu() },
        ), T.s("Shizuku has to be started by you once (Android does not allow it silently). VLC plays files LEAD cannot.",
            "Shizuku moraš jednom sam pokrenuti (Android ne dozvoljava tiho). VLC pušta fajlove koje LEAD ne može.",
            "Shizuku musst du einmal selbst starten (Android erlaubt es nicht still). VLC spielt Dateien, die LEAD nicht kann.", "Shizuku нужно запустить вручную."))
    }

    // ---------------------------------------------------------------- Diagnostics

    private fun deviceHealth() {
        toast(T.s("Measuring…", "Mjerim…", "Messe…", "Измеряю…"))
        Thread {
            val lines = DeviceHealth.lines(this)
            handler.post {
                menu.show(T.s("Device Health", "Stanje uređaja", "Gerätezustand", "Состояние устройства"), R.drawable.lc_cpu,
                    lines.map { (k, v) -> GlassMenu.Item(k, R.drawable.lc_info, value = v) { } } +
                        GlassMenu.Item(T.s("Measure again", "Izmjeri ponovo", "Erneut messen", "Измерить снова"), R.drawable.lc_refresh_cw) { deviceHealth() })
            }
        }.start()
    }

    private fun networkQuality() {
        toast(T.s("Measuring for about 6 seconds…", "Mjerim oko 6 sekundi…", "Messe etwa 6 Sekunden…", "Измеряю около 6 секунд…"))
        Thread {
            val r = NetQuality.measure(this)
            handler.post {
                val path = if (r.viaVpn) T.s("through the VPN", "kroz VPN", "durch das VPN", "через VPN") else T.s("direct", "direktno", "direkt", "напрямую")
                menu.show(T.s("Network Quality", "Kvalitet mreže", "Netzqualität", "Качество сети"), R.drawable.lc_activity, listOf(
                    GlassMenu.Item(T.s("Score", "Ocjena", "Wertung", "Оценка"), R.drawable.lc_gauge, value = r.score?.let { "$it / 100" } ?: "–") { },
                    GlassMenu.Item(T.s("Latency", "Kašnjenje", "Latenz", "Задержка"), R.drawable.lc_activity, value = r.latencyMs?.let { "$it ms" } ?: "–") { },
                    GlassMenu.Item(T.s("Jitter", "Jitter", "Jitter", "Джиттер"), R.drawable.lc_activity, value = r.jitterMs?.let { "$it ms" } ?: "–") { },
                    GlassMenu.Item(T.s("Packet loss", "Gubitak", "Verlust", "Потери"), R.drawable.lc_activity, value = "${r.lossPct} %  (${r.sent - r.ok}/${r.sent})") { },
                    GlassMenu.Item(T.s("Connection", "Veza", "Verbindung", "Соединение"), R.drawable.lc_wifi, value = "${r.connection} · $path") { },
                    GlassMenu.Item(T.s("Measure again", "Izmjeri ponovo", "Erneut messen", "Измерить снова"), R.drawable.lc_refresh_cw) { networkQuality() },
                ), T.s("${r.sent} connections to ${r.target}:443, measured $path. Score = ${NetQuality.FORMULA}",
                    "${r.sent} veza prema ${r.target}:443, izmjereno $path. Ocjena = ${NetQuality.FORMULA}",
                    "${r.sent} Verbindungen zu ${r.target}:443, gemessen $path. Wertung = ${NetQuality.FORMULA}",
                    "${r.sent} соединений к ${r.target}:443, $path. Оценка = ${NetQuality.FORMULA}"))
            }
        }.start()
    }

    private fun logsMenu() {
        val logs = CrashLog.list(this)
        menu.show(T.s("Tools & Logs", "Alati i logovi", "Werkzeuge & Logs", "Инструменты и логи"), R.drawable.lc_file_text,
            logs.map { f -> GlassMenu.Item(f.name.removePrefix("crash_").removeSuffix(".txt"), R.drawable.lc_file_text) {
                menu.page(f.name, R.drawable.lc_file_text, runCatching { f.readText() }.getOrDefault("?"))
            } } + listOfNotNull(
                if (logs.isEmpty()) GlassMenu.Item(T.s("No crashes noted", "Nema zabilježenih grešaka", "Keine Abstürze notiert", "Сбоев нет"), R.drawable.lc_check) { } else null,
                if (logs.isNotEmpty()) GlassMenu.Item(T.s("Delete crash notes", "Obriši zapise grešaka", "Absturzberichte löschen", "Удалить отчёты"), R.drawable.lc_trash_2) {
                    logs.forEach { it.delete() }; CrashWatch.clear(this); refreshPage(); logsMenu()
                } else null,
                GlassMenu.Item(T.s("Wireless debugging", "Wireless debugging", "Drahtloses Debugging", "Отладка по Wi-Fi"), R.drawable.lc_wifi,
                    checked = adbWifiOn(), toggle = true) { setAdbWifi(it.checked) },
            ),
            "adb pull ${CrashLog.dir(this).absolutePath}")
    }

    // ---------------------------------------------------------------- About

    private fun aboutLead() {
        menu.page(T.s("About LEAD", "O LEAD-u", "Über LEAD", "О LEAD"), R.drawable.lc_info,
            "LEAD Launcher ${Updater.currentName(this)} (${channelLabel()})\n\n" +
                T.s("A calm, fast home screen for Android TV and Google TV. No ads, no tracking, nothing running in the background that you did not switch on.",
                    "Miran, brz početni ekran za Android TV i Google TV. Bez reklama, bez praćenja, ništa ne radi u pozadini što nisi sam uključio.",
                    "Ein ruhiger, schneller Startbildschirm für Android TV und Google TV. Keine Werbung, kein Tracking, nichts läuft im Hintergrund, was du nicht eingeschaltet hast.",
                    "Спокойный быстрый лаунчер для Android TV. Без рекламы и слежки.") +
                "\n\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    }

    private fun supportPage() {
        menu.page(T.s("Support & Feedback", "Podrška i povratne informacije", "Hilfe & Feedback", "Поддержка"), R.drawable.lc_bell,
            T.s("Report a problem or an idea:", "Prijavi problem ili ideju:", "Problem oder Idee melden:", "Сообщить о проблеме:") +
                "\ngithub.com/KarlAgathon/GlassLauncher/issues\n\n" +
                T.s("Crash notes (attach them, they contain no personal data):", "Zapisi grešaka (priloži ih, nemaju ličnih podataka):",
                    "Absturzberichte (anhängen, sie enthalten keine persönlichen Daten):", "Отчёты о сбоях:") +
                "\nadb pull ${CrashLog.dir(this).absolutePath}")
    }

    private fun licensesPage() {
        menu.page(T.s("Licenses & Credits", "Licence i zasluge", "Lizenzen & Danksagungen", "Лицензии"), R.drawable.lc_code,
            "LEAD Launcher – open source (GitHub KarlAgathon/GlassLauncher)\n\n" +
                "Lucide icons – ISC License\nInter font – SIL Open Font License 1.1\nAndroidX – Apache License 2.0\n" +
                "Shizuku API – Apache License 2.0\nZXing (QR codes) – Apache License 2.0\nSMBJ (Windows shares) – Apache License 2.0\n" +
                "Apache Commons Net (FTP) – Apache License 2.0\nSSHJ (SFTP) – Apache License 2.0\nBouncy Castle – MIT License\n\n" +
                T.s("App names and logos belong to their owners.", "Imena i logotipi aplikacija pripadaju njihovim vlasnicima.",
                    "App-Namen und Logos gehören ihren Inhabern.", "Названия и логотипы принадлежат владельцам."))
    }

    companion object {
        private const val REQ_PERM = 11
        private const val REQ_PICK = 12
        private const val REQ_FILES = 13
        private const val REQ_LOC = 14
        private const val REQ_ACCOUNT = 15
        private const val REQ_BACKUP = 16
        private const val REQ_NOTIF = 17
        const val EXTRA_SHOW_UPDATE = "show_update"
        const val EXTRA_SHOW_APP_UPDATES = "show_app_updates"
    }
}
