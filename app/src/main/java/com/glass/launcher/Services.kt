package com.glass.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional accessibility service:
 *  - Home button of the remote opens LEAD Launcher instead of the stock home screen
 *  - while a film / any other app is open: small VPN key (and optional clock) in the top-right corner
 */
class HomeService : AccessibilityService() {
    companion object {
        val STOCK = setOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher",
            "com.mitv.tvhome",
            "com.android.tv.launcher",
            "com.amazon.tv.launcher",
        )
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        private const val FIRE_TV_TAG = "LEAD_FIRETV"
        private val fireTvLaunchInProgress = AtomicBoolean()
        val isFireTv = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

        private val IGNORE = setOf("com.android.systemui", "android", "com.google.android.tvrecommendations")

        @Volatile var instance: HomeService? = null

        /** our own home screen (or its screensaver) is on top; set by those activities themselves */
        @Volatile var homeVisible = true

        fun setHome(visible: Boolean) {
            homeVisible = visible
            instance?.let { s -> s.h.post { s.reevaluate() } }
        }

        /** System power menu (restart / turn off). */
        fun powerMenu(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) ?: false

        private val RESTART_WORDS = listOf("restart", "reboot", "neustart", "neu starten", "ponovo pokreni", "restartuj",
            "перезагрузить", "перезапуск", "redémarrer", "riavvia", "reiniciar", "opnieuw opstarten", "uruchom ponownie", "yeniden başlat")

        /**
         * Restart without Shizuku: Android's own power menu opens and LEAD presses its "Restart" button itself
         * (on some boxes the remote can't reach those buttons). [done] gets false if the button wasn't found.
         */
        fun restartViaMenu(done: (Boolean) -> Unit) {
            val s = instance ?: return done(false)
            if (!s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) return done(false)
            var tries = 0
            fun look() {
                if (s.clickRestart()) { done(true); return }
                if (++tries < 15) s.h.postDelayed({ look() }, 300) else done(false)
            }
            s.h.postDelayed({ look() }, 500)
        }

        /** Puts the box to sleep (Android 9+). */
        fun sleep(): Boolean =
            if (Build.VERSION.SDK_INT >= 28) instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false else false

        fun component(ctx: Context) = ComponentName(ctx, HomeService::class.java).flattenToString()

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(component(ctx), ignoreCase = true) }
        }

        /** Works only after: adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS */
        fun enableWithSecureSettings(ctx: Context): Boolean = runCatching {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val me = component(ctx)
            if (!cur.split(':').contains(me)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (cur.isBlank()) me else "$cur:$me")
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    private var last = 0L

    private var burst = 0

    private var burstStart = 0L
    private var overlay: LinearLayout? = null
    private var keyView: ImageView? = null
    private var keyRing: FrameLayout? = null
    private var ringBg: GradientDrawable? = null
    private var pillBg: GradientDrawable? = null
    private var breath: ValueAnimator? = null
    private var clockView: TextView? = null
    private var shown = false
    val h = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (shown) h.postDelayed(this, 1000)
        }
    }

    /**
     * Google TV sometimes comes back by itself (after a restart, after sleep or the screensaver).
     * For a short while after such a moment LEAD looks what is on top and, if it is the stock home
     * screen, opens LEAD again – the user does not have to press Home.
     */
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { watchHome() }
    }

    /** finds the "Restart" button in any window on screen and presses it */
    fun clickRestart(): Boolean {
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) +
            listOfNotNull(runCatching { rootInActiveWindow }.getOrNull())
        for (r in roots) {
            val found = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 25) return
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: ""
                if (t.isNotEmpty() && RESTART_WORDS.any { t == it || t.startsWith(it) }) found.add(n)
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(r, 0)
            for (n in found) {
                var c: android.view.accessibility.AccessibilityNodeInfo? = n
                var up = 0
                while (c != null && !c.isClickable && up < 5) { c = c.parent; up++ }
                if (c != null && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    fun watchHome(delays: List<Long> = listOf(500L, 1500L, 3000L, 5000L, 8000L, 15_000L, 30_000L)) {
        if (isFireTv || !Prefs(this).homeRedirect || !Prefs(this).autoReturn) return
        for (d in delays) h.postDelayed({ backFromStock() }, d)
    }

    private fun backFromStock() {
        if (isFireTv || instance !== this || homeVisible || !Prefs(this).homeRedirect || !Prefs(this).autoReturn) return
        val top = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: lastPkg
        if (top !in STOCK) return
        val now = System.currentTimeMillis()
        if (now - last < 800) return
        last = now
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (isFireTv) {
            // Deliver each transition so a later dialog event cannot replace the HOME event in the queue.
            serviceInfo = serviceInfo.apply { notificationTimeout = 0 }
        }
        createOverlay()
        runCatching {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_DREAMING_STOPPED)
            }
            registerReceiver(wakeReceiver, f)
        }
        // switched on / restarted: Google TV starts first – check a few times during the first minute
        if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000L) watchHome(listOf(500L, 1000L, 2000L, 3000L, 5000L, 8000L, 12_000L, 20_000L, 30_000L))
        // right after a LEAD update Android shows Google TV: come back to LEAD by ourselves
        val g = getSharedPreferences("glass_global", MODE_PRIVATE)
        val upd = g.getLong("justUpdated", 0L)
        if (upd > 0 && System.currentTimeMillis() - upd < 3 * 60_000L) {
            g.edit().remove("justUpdated").apply()
            if (!isFireTv && Prefs(this).homeRedirect) h.postDelayed({
                if (instance !== this || !Prefs(this).homeRedirect) return@postDelayed
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
            }, 800)
        }
    }

    private fun createOverlay() {
        val d = resources.displayMetrics.density
        val pill = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(Color.argb(110, 0, 0, 0))
            setStroke((1 * d).toInt(), Color.argb(70, 255, 255, 255))
        }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            background = pill
            visibility = View.GONE
        }
        // key inside a thin round ring: red = VPN off, green = VPN on
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((1.5f * d).toInt().coerceAtLeast(2), Color.rgb(255, 80, 80))
        }
        val wrap = FrameLayout(this).apply { background = ring }
        val key = ImageView(this).apply { setImageResource(R.drawable.lc_key_round) }
        val ks = (17 * d).toInt()
        wrap.addView(key, FrameLayout.LayoutParams(ks, ks, Gravity.CENTER))
        val rs = (34 * d).toInt()
        v.addView(wrap, LinearLayout.LayoutParams(rs, rs))
        val clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.medium(this@HomeService)
        }
        v.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (6 * d).toInt()
            y = (6 * d).toInt()
        }
        val ok = runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp) }.isSuccess
        if (ok) { overlay = v; keyView = key; keyRing = wrap; ringBg = ring; pillBg = pill; clockView = clock }
    }

    /**
     * Slow "breathing": the key fades in (1.5 s), stays (3 s), fades out (1.5 s),
     * stays away (2.5 s) – one calm breath about every 8.5 seconds.
     */
    private fun breathe(on: Boolean) {
        val w = keyRing ?: return
        if (on) {
            if (breath?.isRunning == true) return
            val fadeIn = 1500f; val stay = 3000f; val fadeOut = 1500f; val away = 2500f
            val total = fadeIn + stay + fadeOut + away
            val low = 0.06f
            fun smooth(t: Float) = t * t * (3f - 2f * t)
            breath = ValueAnimator.ofFloat(0f, total).apply {
                duration = total.toLong()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val a = when {
                        t < fadeIn -> smooth(t / fadeIn)
                        t < fadeIn + stay -> 1f
                        t < fadeIn + stay + fadeOut -> 1f - smooth((t - fadeIn - stay) / fadeOut)
                        else -> 0f
                    }
                    w.alpha = low + (1f - low) * a
                }
                start()
            }
        } else {
            breath?.cancel()
            breath = null
            w.alpha = KEY_ALPHA
        }
    }

    private var banner: View? = null

    /** see-through key: visible, but does not disturb the film */
    private val KEY_ALPHA = 0.45f

    /** A glass banner that slides in at the top of the screen for a few seconds (works over other apps). */
    fun showBanner(text: String) {
        val d = resources.displayMetrics.density
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        banner?.let { old -> runCatching { wm.removeView(old) } }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(Color.argb(150, 10, 20, 35))
                setStroke((1.5f * d).toInt().coerceAtLeast(2), Color.rgb(120, 220, 255))
            }
        }
        val icon = ImageView(this).apply { setImageResource(R.drawable.lc_download); setColorFilter(Color.rgb(120, 220, 255)) }
        box.addView(icon, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
        box.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.medium(this@HomeService)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (10 * d).toInt() })
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (20 * d).toInt() }
        if (runCatching { wm.addView(box, lp) }.isFailure) return
        banner = box
        box.alpha = 0f
        box.translationY = -40 * d
        box.animate().alpha(1f).translationY(0f).setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        h.postDelayed({
            box.animate().alpha(0f).translationY(-40 * d).setDuration(400).withEndAction {
                runCatching { wm.removeView(box) }
                if (banner === box) banner = null
            }.start()
        }, 9000)
    }

    private fun refresh() {
        val v = overlay ?: return
        val p = Prefs(this)
        if (!shown || (!p.overlayVpn && !p.overlayClock)) { v.visibility = View.GONE; breathe(false); return }
        v.visibility = View.VISIBLE
        keyRing?.visibility = if (p.overlayVpn) View.VISIBLE else View.GONE
        if (p.overlayVpn) {
            val col = if (NetInfo.read(this).vpn) Color.rgb(80, 245, 150) else Color.rgb(255, 105, 105)
            val d = resources.displayMetrics.density
            keyView?.setColorFilter(col)
            ringBg?.setStroke((1.5f * d).toInt().coerceAtLeast(2), col)
        }
        breathe(false)
        clockView?.visibility = if (p.overlayClock) View.VISIBLE else View.GONE
        if (p.overlayClock) clockView?.text = SimpleDateFormat(ClockFmt.pattern(this, p), Locale.getDefault()).format(Date())
        // only the ringed key: no dark pill around it
        v.background = if (p.overlayClock) pillBg else null
    }

    private fun setShown(v: Boolean) {
        shown = v
        h.removeCallbacks(tick)
        h.post(tick)
    }

    private class FireTvHomeEvent(val windowId: Int, val eventTime: Long)

    @Volatile private var fireTvHomeEvent: FireTvHomeEvent? = null

    private fun isCurrent(event: FireTvHomeEvent): Boolean =
        instance === this && fireTvHomeEvent === event && Prefs(this).homeRedirect &&
            SystemClock.uptimeMillis() - event.eventTime in 0L..900L

    private fun handleFireTvWindow(event: AccessibilityEvent) {
        if (event.packageName?.toString() != AMAZON_LAUNCHER || event.className?.toString() != AMAZON_HOME) {
            fireTvHomeEvent = null
            return
        }
        Log.d(FIRE_TV_TAG, "Amazon HOME event window=${event.windowId} redirect=${Prefs(this).homeRedirect}")
        if (fireTvHomeEvent?.windowId == event.windowId) return
        val request = FireTvHomeEvent(event.windowId, event.eventTime)
        fireTvHomeEvent = request
        if (!isCurrent(request) || !fireTvLaunchInProgress.compareAndSet(false, true)) return

        Thread({
            try {
                if (!isCurrent(request)) return@Thread
                if (ShizukuCpu.permitted()) {
                    if (!isCurrent(request)) return@Thread
                    val result = ShizukuCpu.startFireTvHome("$AMAZON_LAUNCHER/$AMAZON_HOME", packageName)
                    Log.i(FIRE_TV_TAG, "HOME Shizuku response=$result age=${SystemClock.uptimeMillis() - request.eventTime}ms")
                    return@Thread
                }

                Log.d(FIRE_TV_TAG, "HOME Shizuku unavailable")
                h.post {
                    if (!isCurrent(request)) return@post
                    val active = runCatching { rootInActiveWindow }.getOrNull() ?: return@post
                    val matches = try {
                        active.windowId == request.windowId && active.packageName?.toString() == AMAZON_LAUNCHER
                    } finally {
                        active.recycle()
                    }
                    Log.d(FIRE_TV_TAG, "HOME fallback active window matches=$matches")
                    if (matches && isCurrent(request)) {
                        runCatching {
                            startActivity(Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                        }.onSuccess { Log.i(FIRE_TV_TAG, "HOME accessibility fallback requested") }
                    }
                }
            } finally {
                fireTvLaunchInProgress.set(false)
            }
        }, "LEAD Fire TV HOME").start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (isFireTv) handleFireTvWindow(event)
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORE || pkg.contains("inputmethod") || pkg.endsWith(".ime")) return
        // our own windows are handled by the activities themselves (setHome); a late event from
        // the launcher must not hide the key in an app that opened right after it (e.g. TizenTube)
        if (pkg == packageName) return
        // Google TV's launcher also opens small windows of its own over a running app (panels, dialogs,
        // e.g. when Wireless debugging is switched on). Only its real home screen counts as "home" –
        // otherwise LEAD would push the app you are watching (IPTV …) away.
        if (pkg == "com.google.android.apps.tv.launcherx") {
            val cls = event.className?.toString() ?: ""
            if (cls.isNotEmpty() && !cls.contains("HomeActivity") && !cls.contains(".home.")) return
        }
        // Fire TV: redirect only the actual Amazon home screen.
        if (pkg == "com.amazon.tv.launcher" &&
            event.className?.toString() !=
                "com.amazon.tv.launcher.ui.HomeActivity_vNext") return
        val prev = lastPkg
        lastPkg = pkg
        // learn which apps are used most (for the "All apps" list)
        if (pkg != prev && pkg !in STOCK && launchable(pkg)) runCatching { Usage.add(this, pkg) }
        if (pkg in STOCK) {
            setShown(false)
            if (isFireTv) return
            if (!Prefs(this).homeRedirect) return
            val now = System.currentTimeMillis()
            if (now - last < 800) return
            // never fight endlessly: more than 8 jumps within 20 s = something keeps pushing back, pause
            if (now - burstStart > 20_000L) { burstStart = now; burst = 0 }
            if (++burst > 8) return
            last = now

            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            return
        }
        setShown(!homeVisible)
    }

    /** Key is shown whenever any app other than our home screen / stock launcher is on top. */
    fun reevaluate() {
        setShown(!homeVisible && lastPkg != null && lastPkg !in STOCK)
    }

    private val launchCache = HashMap<String, Boolean>()
    private fun launchable(pkg: String): Boolean = launchCache.getOrPut(pkg) {
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private var homeDown = 0L

    /**
     * Home button on the remote: open LEAD straight away instead of letting Google TV show its own home
     * first. Held long (over 0.7 s) = Android settings. If LEAD ever hangs, Android stops waiting for this
     * answer after a moment and Home goes to Google TV as usual – that is the safety net.
     */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        // Fire OS handles HOME itself, including returning from Alexa results to Amazon HOME.
        if (isFireTv) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_HOME) {
                Log.d(FIRE_TV_TAG, "HOME key action=${event.action} device=${event.deviceId}")
            }
            return false
        }
        if (event.keyCode != android.view.KeyEvent.KEYCODE_HOME) return false
        if (!Prefs(this).homeRedirect) return false
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> { if (event.repeatCount == 0) homeDown = event.eventTime }
            android.view.KeyEvent.ACTION_UP -> {
                val held = event.eventTime - homeDown
                runCatching {
                    if (held > 700) startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
            }
        }
        return true
    }

    override fun onInterrupt() {
        fireTvHomeEvent = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        fireTvHomeEvent = null
        runCatching { unregisterReceiver(wakeReceiver) }
        h.removeCallbacksAndMessages(null)
        banner?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }
        banner = null
        breathe(false)
        overlay?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }
        overlay = null
        super.onDestroy()
    }
}

/** After the box starts: stop apps the user blocked from auto-starting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        // only react to the real start of the box (other apps can't use this to stop apps)
        val a = intent?.action ?: return
        val app = ctx.applicationContext
        if (a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (HomeService.isFireTv) {
                runCatching { Updater.schedule(app) }
                return
            }
            // LEAD was just updated: the helper service brings LEAD back to the front once it reconnects
            app.getSharedPreferences("glass_global", Context.MODE_PRIVATE).edit()
                .putLong("justUpdated", System.currentTimeMillis()).apply()
            runCatching { if (!HomeService.isEnabled(app)) HomeService.enableWithSecureSettings(app) }
            HomeService.instance?.let { s ->
                runCatching { s.startActivity(Intent(s, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            runCatching { Updater.schedule(app) }
            return
        }
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON" &&
            a != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // after a restart: helper service back on (needs WRITE_SECURE_SETTINGS, given once over ADB)
        if (!HomeService.isFireTv) runCatching {
            if (!HomeService.isEnabled(app)) HomeService.enableWithSecureSettings(app)
        }
        Autostart.apply(app)
        runCatching { Updater.schedule(app) }
        val pending = goAsync()
        val h = Handler(Looper.getMainLooper())
        // Google TV always starts its own home screen: open LEAD once right after switching on
        if (!HomeService.isFireTv && a != Intent.ACTION_LOCKED_BOOT_COMPLETED && Prefs(app).homeRedirect) h.postDelayed({
            if (!Prefs(app).homeRedirect) return@postDelayed
            runCatching {
                val c: Context = HomeService.instance ?: app
                c.startActivity(Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            HomeService.instance?.watchHome()
        }, 2500)
        h.postDelayed({ Autostart.apply(app) }, 4000)
        h.postDelayed({ Autostart.apply(app); pending.finish() }, 8000)
    }
}
