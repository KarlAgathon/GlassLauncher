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
import androidx.recyclerview.widget.LinearLayoutManager
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
                if (System.currentTimeMillis() - lastWeather > prefs.weatherMin * 60 * 1000L) updateWeather()
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
        dock = findViewById(R.id.dock)
        gridArea = findViewById(R.id.gridArea)
        hint = findViewById(R.id.hint)
        profileBtn = findViewById(R.id.profileBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        updateBtn = findViewById(R.id.updateBtn)
        powerBtn = findViewById(R.id.powerBtn)
        profileLetter = findViewById(R.id.profileLetter)

        menu = GlassMenu(this, root) { open ->
            grid.descendantFocusability =
                if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            profileBtn.isFocusable = !open
            settingsBtn.isFocusable = !open
            updateBtn.isFocusable = !open
            powerBtn.isFocusable = !open
            statusGroups().forEach { it.isFocusable = !open }
            if (!open) {
                focusTile(lastPos)
                if (plusPeek) { handler.removeCallbacks(hidePlus); handler.postDelayed(hidePlus, 1500) }
            }
        }

        for (b in listOf(profileBtn, settingsBtn, powerBtn)) {
            b.radiusDp = 21f
            b.setOnFocusChangeListener { v, has ->
                (v as GlassFrame).focusedLook = has
                val sc = if (has) 1.15f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).withEndAction { v.invalidate() }.start()
            }
        }
        updateBtn.radiusDp = 21f
        updateBtn.accent = true
        updateBtn.setOnFocusChangeListener { v, has ->
            (v as GlassFrame).focusedLook = has
            val sc = if (has) 1.12f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(140).withEndAction { v.invalidate() }.start()
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
        setupStatusClicks()
        runCatching { Updater.schedule(this) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) handler.postDelayed({ installPendingOrCheck() }, 800)
    }

    override fun onResume() {
        super.onResume()
        HomeService.setHome(true)
        lastInput = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (prefs.wpMode != 0 && !firstLoad) {
            handler.removeCallbacks(rotate)
            handler.postDelayed(rotate, prefs.wpInterval.coerceAtLeast(10) * 1000L)
        }
        resumed = true
        showUpdateChip(animate = false)
        Updater.pending(this)?.let { p ->
            if (p.code != promptedCode && Updater.ready(this, p) != null) handler.postDelayed({ if (resumed) installUpdate(p) }, 1200)
        }
        val every = 60 * 60 * 1000L
        if (prefs.updateUrl.isNotBlank() && System.currentTimeMillis() - prefs.lastUpdateCheck > every) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            checkUpdate(manual = false)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        HomeService.setHome(false)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(rotate)
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
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) handler.postDelayed({ installPendingOrCheck() }, 500)
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
        adaptStatus()
        profileLetter.text = Profiles.name(this, prefs.profile).take(1).uppercase()
        (hint.layoutParams as FrameLayout.LayoutParams).gravity = when (prefs.gridPosition) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.CENTER_VERTICAL or Gravity.START
            else -> Gravity.BOTTOM or Gravity.START
        }
        layoutGrid()
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        hint.text = T.s("Press OK to add your apps", "Pritisni OK da dodaš svoje aplikacije", "OK drücken, um deine Apps hinzuzufügen")
        loadWallpaper()
        updateClock()
        updateStatus()
    }

    /** Size, columns, alignment and dock of the app row(s). */
    private fun layoutGrid() {
        val d = resources.displayMetrics.density
        val (tw, _) = prefs.tileDp()
        val cell = ((tw + prefs.tileGap) * d).toInt()
        val avail = if (gridArea.width > 0) gridArea.width else resources.displayMetrics.widthPixels - (80 * d).toInt()
        val dockPad = if (prefs.dock) (14 * d).toInt() else 0
        val maxCols = maxOf(1, (avail - 2 * dockPad) / cell)
        cols = maxOf(1, minOf(maxCols, apps.size))
        val glp = grid.layoutParams
        if (prefs.singleRow) {
            if (grid.layoutManager !== rowManager) grid.layoutManager = rowManager
            // one row: as wide as the apps, at most the screen; scrolls when there are more
            glp.width = minOf(apps.size * cell, avail - 2 * dockPad).coerceAtLeast(cell)
            cols = apps.size.coerceAtLeast(1)
        } else {
            if (grid.layoutManager !== layoutManager) grid.layoutManager = layoutManager
            layoutManager.spanCount = cols
            glp.width = cols * cell
        }
        grid.layoutParams = glp

        dock.setPadding(dockPad, dockPad / 2, dockPad, dockPad / 2)
        dock.plain = !prefs.dock
        dock.backdrop = prefs.dock
        dock.tint = if (prefs.dock) 0.08f else 0f
        dock.rimMode = if (prefs.dock) 1 else 0
        dock.radiusDp = 26f
        dock.clipToOutline = false
        val vert = when (prefs.gridPosition) { 0 -> Gravity.TOP; 1 -> Gravity.CENTER_VERTICAL; else -> Gravity.BOTTOM }
        val hor = when (prefs.gridAlign) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
        (dock.layoutParams as FrameLayout.LayoutParams).gravity = vert or hor
        dock.requestLayout()
        dock.invalidate()
        adapter.notifyDataSetChanged()
        if (gridArea.width == 0) gridArea.post { if (gridArea.width > 0) layoutGrid() }
        dock.post { placeMovieInfo() }
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
            val src = runCatching { load() }.getOrNull() ?: return@Thread
            val crop = Bitmaps.centerCrop(src, w, h)
            val frosted = Bitmaps.frosted(crop)
            val shown = if (blur) Bitmaps.softBlur(crop) else crop
            handler.post {
                val apply = {
                    Backdrop.blurred = frosted
                    Backdrop.w = w
                    Backdrop.h = h
                    wallpaper.setImageBitmap(shown)
                    showMovieInfo(movie)
                    adaptStatus(shown)
                    invalidateGlass()
                }
                if (fade) {
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
        val f = wallpaperFile
        val (w, h) = screenSize()
        showPicture({
            if (f.exists()) Bitmaps.decodeScaled(f, w, h)
            else BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default)
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
                    toast(T.s("Wallpaper plugin not found", "Plugin za pozadinu nije pronađen", "Hintergrund-Plugin nicht gefunden"))
                    prefs.wpMode = 0; loadWallpaper(); return
                }
                val cacheKey = "plugin:" + p.id
                if (cacheKey == moviesKey && moviesMode == 3 && movies.isNotEmpty()) { handler.post(rotate); return }
                if (moviesMode != 3) { movies = emptyList(); moviesKey = "" }
                Thread {
                    val list = Plugins.movies(Plugins.fetch(this, p)).shuffled()
                    handler.post {
                        if (prefs.wpMode != 3) return@post   // switched to something else meanwhile
                        if (list.isEmpty()) {
                            toast(T.s("The plugin sent no pictures – open its settings and try again",
                                "Plugin nije poslao slike – otvori njegove postavke pa pokušaj ponovo",
                                "Das Plugin hat keine Bilder geliefert – Einstellungen öffnen und erneut versuchen"))
                            if (movies.isEmpty()) { prefs.wpMode = 0; loadWallpaper() }
                            return@post
                        }
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

        val btBox = findViewById<View>(R.id.btBox)
        val bt = if (prefs.showBluetooth) BtInfo.read(this) else null
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
        findViewById<TextView>(R.id.notifCount).text =
            if (NotifListener.connected) NotifListener.current().size.toString() else "–"

        findViewById<View>(R.id.cpuBox).visibility = if (prefs.showCpu) View.VISIBLE else View.GONE
        if (prefs.showCpu) findViewById<TextView>(R.id.cpuText).text = SysStats.cpuPercent()?.let { "$it%" } ?: "–"
        findViewById<View>(R.id.ramBox).visibility = if (prefs.showRam) View.VISIBLE else View.GONE
        if (prefs.showRam) findViewById<TextView>(R.id.ramText).text = "${SysStats.ramPercent(this)}%"

        findViewById<View>(R.id.weatherBox).visibility = if (prefs.showWeather) View.VISIBLE else View.GONE

        val left = prefs.showWeather || prefs.showNetwork || prefs.showVpn || prefs.showBluetooth || prefs.showNotifs
        val right = prefs.showCpu || prefs.showRam
        findViewById<View>(R.id.divider).visibility = if (left && right) View.VISIBLE else View.GONE
        // first visible group must not have a left margin
        val groups = listOf(R.id.weatherBox, R.id.netBox, R.id.vpnBox, R.id.btBox, R.id.notifBox, R.id.cpuBox, R.id.ramBox)
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
                    lastWeather = System.currentTimeMillis() - (prefs.weatherMin - 5).coerceAtLeast(0) * 60 * 1000L // retry in ~5 min
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
            GlassMenu.Item(T.s("Icon look (all options)", "Izgled ikona (sve opcije)", "Symbol-Aussehen (alle Optionen)"), R.drawable.lc_layout_grid) { tilesMenu() },
            GlassMenu.Item(T.s("Size", "Veličina", "Größe"), R.drawable.lc_columns_3, value = listOf("S", "M", "L", "XL")[prefs.tileSize.coerceIn(0, 3)]) {
                choose(T.s("Size", "Veličina", "Größe"), R.drawable.lc_columns_3, listOf("S" to 0, "M" to 1, "L" to 2, "XL" to 3), prefs.tileSize) { prefs.tileSize = it; reloadApps() }
            },
            GlassMenu.Item(T.s("Spacing", "Razmak", "Abstand"), R.drawable.lc_columns_3, value = "${prefs.tileGap} dp") {
                choose(T.s("Spacing", "Razmak", "Abstand"), R.drawable.lc_columns_3,
                    listOf(0, 6, 12, 18, 24, 32, 40).map { "$it dp" to it }, prefs.tileGap) { prefs.tileGap = it; relayout() }
            },
            GlassMenu.Item(T.s("Row position", "Pozicija reda", "Position der Reihe"), R.drawable.lc_move) {
                choose(T.s("Row position", "Pozicija reda", "Position der Reihe"), R.drawable.lc_move, listOf(
                    T.s("Bottom left", "Dolje lijevo", "Unten links") to 20, T.s("Bottom center", "Dolje sredina", "Unten Mitte") to 21,
                    T.s("Bottom right", "Dolje desno", "Unten rechts") to 22, T.s("Middle left", "Sredina lijevo", "Mitte links") to 10,
                    T.s("Center", "Sredina", "Mitte") to 11, T.s("Top left", "Gore lijevo", "Oben links") to 0,
                    T.s("Top center", "Gore sredina", "Oben Mitte") to 1), prefs.gridPosition * 10 + prefs.gridAlign) {
                    prefs.gridPosition = it / 10; prefs.gridAlign = it % 10; applyLook()
                }
            },
            GlassMenu.Item(T.s("Add apps", "Dodaj aplikacije", "Apps hinzufügen"), R.drawable.lc_plus) { showAddMenu() },
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

    /** Every app on the box (also system apps) – just open it, nothing is added to the home screen. */
    private fun allAppsMenu() {
        Thread {
            val list = AppLoader.choosable(this)
            handler.post {
                menu.show(T.s("All apps", "Sve aplikacije", "Alle Apps") + " (${list.size})", R.drawable.lc_layout_grid,
                    list.map { (pkg, label, icon) ->
                        GlassMenu.Item(label, drawable = icon) {
                            if (pkg == AppEntry.FILES) startActivity(Intent(this, FileBrowserActivity::class.java))
                            else launch(pkg, label)
                        }
                    })
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
        val k = event.keyCode
        if (menu.isOpen && k == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) menu.close()
            return true
        }
        if (!menu.isOpen && movingPos < 0 && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val pos = focusedPos()
            // endless row: right on the last tile jumps to the first, left on the first jumps to the last
            if (prefs.singleRow && pos != RecyclerView.NO_POSITION && apps.size > 1) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT && pos == apps.size - 1) { lastPos = 0; focusTile(0); return true }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) { lastPos = apps.size - 1; focusTile(apps.size - 1); return true }
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

    private fun showSettings() {
        val langName = T.languages.firstOrNull { it.first == T.lang }?.second ?: "English"
        menu.show(T.s("Settings", "Postavke", "Einstellungen"), R.drawable.lc_settings, listOf(
            GlassMenu.Item(T.s("Add apps", "Dodaj aplikacije", "Apps hinzufügen"), R.drawable.lc_plus) { showAddMenu() },
            GlassMenu.Item(T.s("All apps", "Sve aplikacije", "Alle Apps"), R.drawable.lc_layout_grid) { allAppsMenu() },
            GlassMenu.Item(T.s("Android settings of the box", "Android postavke boxa", "Android-Einstellungen der Box"), R.drawable.lc_settings) {
                safeStart(Intent(Settings.ACTION_SETTINGS))
            },
            GlassMenu.Item(T.s("Language", "Jezik", "Sprache"), R.drawable.lc_languages, value = langName) { languageMenu() },
            GlassMenu.Item(T.s("Profiles and Google account", "Profili i Google račun", "Profile und Google-Konto"), R.drawable.lc_user,
                value = Profiles.name(this, prefs.profile)) { profileMenu() },
            GlassMenu.Item(T.s("Wallpaper", "Pozadina", "Hintergrund"), R.drawable.lc_wallpaper) { wallpaperMenu() },
            GlassMenu.Item(T.s("Icons and tiles", "Ikone i pločice", "Symbole und Kacheln"), R.drawable.lc_layout_grid) { lookMenu() },
            GlassMenu.Item(T.s("Status bar", "Statusna traka", "Statusleiste"), R.drawable.lc_bell_ring) { statusMenu() },
            GlassMenu.Item(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock) { clockMenu() },
            GlassMenu.Item(T.s("While watching (corner)", "Dok gledaš (u ćošku)", "Beim Schauen (Ecke)"), R.drawable.lc_tv) { overlayMenu() },
            GlassMenu.Item(T.s("Weather city", "Grad za vrijeme", "Wetter-Stadt"), R.drawable.lc_map_pin, value = prefs.city) { setCity() },
            GlassMenu.Item(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell) { notificationsPanel() },
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
            GlassMenu.Item(T.s("Samurai (default)", "Samuraj (zadana)", "Samurai (Standard)"), R.drawable.lc_rotate_ccw) {
                wallpaperFile.delete(); prefs.wpMode = 0; applyLook()
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
        ))
    }

    private fun statusMenu() {
        fun t(text: String, icon: Int, get: Boolean, set: (Boolean) -> Unit) =
            GlassMenu.Item(text, icon, checked = get, toggle = true) { set(it.checked); applyLook() }
        menu.show(T.s("Status bar", "Statusna traka", "Statusleiste"), R.drawable.lc_bell_ring, listOf(
            t(T.s("Show status bar", "Prikaži statusnu traku", "Statusleiste anzeigen"), R.drawable.lc_eye, prefs.showStatusBar) { prefs.showStatusBar = it },
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
            t(T.s("Clock", "Sat", "Uhr"), R.drawable.lc_clock, prefs.showClock) { prefs.showClock = it },
            t(T.s("Date", "Datum", "Datum"), R.drawable.lc_type, prefs.showDate) { prefs.showDate = it },
            t(T.s("Weather", "Vrijeme", "Wetter"), R.drawable.lc_cloud_sun, prefs.showWeather) { prefs.showWeather = it; lastWeather = 0 },
            t("Wi-Fi / LAN", R.drawable.lc_wifi, prefs.showNetwork) { prefs.showNetwork = it },
            t("VPN", R.drawable.lc_key_round, prefs.showVpn) { prefs.showVpn = it },
            t("Bluetooth", R.drawable.lc_bluetooth, prefs.showBluetooth) { prefs.showBluetooth = it },
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
                    listOf(2, 5, 10, 15, 30, 60, 120).map { "$it min" to it }, prefs.idleMinutes) { prefs.idleMinutes = it }
            },
            GlassMenu.Item(T.s("Start screensaver now", "Pokreni screensaver sada", "Bildschirmschoner jetzt starten"), R.drawable.lc_monitor) { startSaver() },
            GlassMenu.Item(T.s("Android screensaver settings", "Android postavke screensavera", "Android-Bildschirmschoner-Einstellungen"), R.drawable.lc_settings) {
                safeStart(Intent(Settings.ACTION_DREAM_SETTINGS))
            },
        ), T.s("Tip: in the Android screensaver settings you can also choose \"LEAD Launcher\" – then the samurai clock shows in every app.",
            "Savjet: u Android postavkama screensavera možeš izabrati i \"LEAD Launcher\" – tada se samuraj sat prikazuje u svakoj aplikaciji.",
            "Tipp: In den Android-Einstellungen kannst du auch \"LEAD Launcher\" als Bildschirmschoner wählen."))
    }

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

    private fun notificationsPanel() {
        if (!NotifListener.connected) {
            menu.show(T.s("Notifications", "Obavještenja", "Benachrichtigungen"), R.drawable.lc_bell, listOf(
                GlassMenu.Item(T.s("Allow notification access", "Dozvoli pristup obavještenjima", "Benachrichtigungszugriff erlauben"), R.drawable.lc_check) { notificationAccess() },
            ), T.s("LEAD Launcher needs permission to show and clear notifications.",
                "LEAD Launcher treba dozvolu da prikazuje i briše obavještenja.",
                "LEAD Launcher braucht die Erlaubnis, Benachrichtigungen anzuzeigen und zu löschen."))
            return
        }
        val list = NotifListener.current()
        if (list.isEmpty()) { toast(T.s("No notifications", "Nema obavještenja", "Keine Benachrichtigungen")); return }
        val pm = packageManager
        val items = mutableListOf(GlassMenu.Item(T.s("Clear all", "Obriši sve", "Alle löschen"), R.drawable.lc_trash_2) {
            NotifListener.clearAll(); handler.postDelayed({ updateStatus() }, 400)
        })
        for (sbn in list) {
            val ex = sbn.notification.extras
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app
            val txt = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            items.add(GlassMenu.Item(if (txt != null) "$title – $txt" else title,
                drawable = runCatching { pm.getApplicationIcon(sbn.packageName) }.getOrNull()) {
                menu.show(title, R.drawable.lc_bell, listOf(
                    GlassMenu.Item(T.s("Open", "Otvori", "Öffnen"), R.drawable.lc_play) { runCatching { sbn.notification.contentIntent?.send() } },
                    GlassMenu.Item(T.s("Clear", "Obriši", "Löschen"), R.drawable.lc_x) {
                        NotifListener.clear(sbn); handler.postDelayed({ updateStatus(); notificationsPanel() }, 300)
                    },
                ), txt)
            })
        }
        menu.show(T.s("Notifications", "Obavještenja", "Benachrichtigungen") + " (${list.size})", R.drawable.lc_bell, items)
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
            if (info != null) Updater.remember(this, info)
            if (info != null && !manual) Updater.notify(this, info)
            handler.post {
                if (info == null) {
                    if (manual) toast(T.s("You have the latest version", "Imaš najnoviju verziju", "Du hast die neueste Version"))
                    showUpdateChip(animate = false)
                    return@post
                }
                showUpdateChip(animate = !manual)
                // found = download and ask "install?" straight away (once per version, so "No" is respected)
                if (manual || promptedCode != info.code) installUpdate(info)
            }
        }.start()
    }

    private fun showUpdateOffer(info: Updater.Info) {
        menu.show(T.s("New version ", "Nova verzija ", "Neue Version ") + info.name, R.drawable.lc_download, listOf(
            GlassMenu.Item(T.s("Install now", "Instaliraj sada", "Jetzt installieren"), R.drawable.lc_download) { installUpdate(info) },
            GlassMenu.Item(T.s("Later", "Kasnije", "Später"), R.drawable.lc_x),
        ), info.notes.ifBlank { null })
    }

    private var chipPulse: android.animation.ValueAnimator? = null

    /** "New version" chip in the top bar: pops in with a little bounce, then glows softly. */
    private fun showUpdateChip(animate: Boolean) {
        val info = Updater.pending(this)
        if (info == null) {
            chipPulse?.cancel(); chipPulse = null
            updateBtn.visibility = View.GONE
            return
        }
        findViewById<TextView>(R.id.updateText).text = T.s("New version ", "Nova verzija ", "Neue Version ") + info.name
        val wasHidden = updateBtn.visibility != View.VISIBLE
        updateBtn.visibility = View.VISIBLE
        if (animate || wasHidden) {
            updateBtn.scaleX = 0.3f; updateBtn.scaleY = 0.3f; updateBtn.alpha = 0f
            updateBtn.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(550)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        if (chipPulse == null) {
            chipPulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { a ->
                    if (!updateBtn.isFocused) updateBtn.tint = 0.06f + 0.16f * (a.animatedValue as Float)
                    updateBtn.invalidate()
                }
                start()
            }
        }
    }

    /** Notification, chip and "Install": one click – download and open the installer straight away. */
    private fun installPendingOrCheck() {
        val p = Updater.pending(this)
        if (p != null && p.apk.isNotBlank()) installUpdate(p) else checkUpdate(manual = true)
    }

    private var installing = false

    /** version for which the "install?" question was already shown in this session */
    private var promptedCode = 0L
    private var resumed = false

    private fun installUpdate(info: Updater.Info) {
        if (installing) return
        promptedCode = info.code
        val ready = Updater.ready(this, info)
        if (ready != null) { Updater.install(this, ready); return }
        installing = true
        handler.postDelayed({ installing = false }, 120_000)
        toast(T.s("Downloading version ", "Preuzimam verziju ", "Lade Version ") + info.name + "…")
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

    companion object {
        private const val REQ_PERM = 11
        private const val REQ_PICK = 12
        private const val REQ_FILES = 13
        private const val REQ_LOC = 14
        private const val REQ_ACCOUNT = 15
        private const val REQ_BACKUP = 16
        private const val REQ_NOTIF = 17
        const val EXTRA_SHOW_UPDATE = "show_update"
    }
}
