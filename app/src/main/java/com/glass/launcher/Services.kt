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
 *  - Home button of the remote opens Glass Launcher instead of the stock home screen
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
        )
        private val IGNORE = setOf("com.android.systemui", "android", "com.google.android.tvrecommendations")

        @Volatile var instance: HomeService? = null

        /** System power menu (restart / turn off). */
        fun powerMenu(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) ?: false

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
    private var overlay: LinearLayout? = null
    private var keyView: ImageView? = null
    private var keyRing: FrameLayout? = null
    private var ringBg: GradientDrawable? = null
    private var pillBg: GradientDrawable? = null
    private var breath: ValueAnimator? = null
    private var clockView: TextView? = null
    private var shown = false
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (shown) h.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createOverlay()
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
            setColor(Color.argb(90, 0, 0, 0))
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
            x = (24 * d).toInt()
            y = (18 * d).toInt()
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
            w.alpha = 1f
        }
    }

    private var banner: View? = null

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
            val col = if (NetInfo.read(this).vpn) Color.rgb(52, 212, 122) else Color.rgb(255, 80, 80)
            val d = resources.displayMetrics.density
            keyView?.setColorFilter(col)
            ringBg?.setStroke((1.5f * d).toInt().coerceAtLeast(2), col)
        }
        breathe(p.overlayVpn)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORE || pkg.contains("inputmethod") || pkg.endsWith(".ime")) return
        if (pkg == packageName) { setShown(false); return }
        if (pkg in STOCK) {
            setShown(false)
            if (!Prefs(this).homeRedirect) return
            val now = System.currentTimeMillis()
            if (now - last < 800) return
            last = now
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            return
        }
        setShown(true)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        h.removeCallbacks(tick)
        breathe(false)
        overlay?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }
        overlay = null
        super.onDestroy()
    }
}

/** After the box starts: stop apps the user blocked from auto-starting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        Autostart.apply(app)
        runCatching { Updater.schedule(app) }
        val pending = goAsync()
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ Autostart.apply(app) }, 4000)
        h.postDelayed({ Autostart.apply(app); pending.finish() }, 8000)
    }
}
