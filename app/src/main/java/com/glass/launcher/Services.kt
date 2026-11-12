package com.glass.launcher

import android.accessibilityservice.AccessibilityService
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
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(Color.argb(110, 0, 0, 0))
                setStroke((1 * d).toInt(), Color.argb(70, 255, 255, 255))
            }
            visibility = View.GONE
        }
        val key = ImageView(this).apply { setImageResource(R.drawable.lc_key_round) }
        v.addView(key, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
        val clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.medium(this@HomeService)
        }
        v.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (8 * d).toInt() })
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
        if (ok) { overlay = v; keyView = key; clockView = clock }
    }

    private fun refresh() {
        val v = overlay ?: return
        val p = Prefs(this)
        if (!shown || (!p.overlayVpn && !p.overlayClock)) { v.visibility = View.GONE; return }
        v.visibility = View.VISIBLE
        keyView?.visibility = if (p.overlayVpn) View.VISIBLE else View.GONE
        if (p.overlayVpn) {
            keyView?.setColorFilter(if (NetInfo.read(this).vpn) Color.rgb(52, 212, 122) else Color.rgb(255, 80, 80))
        }
        clockView?.visibility = if (p.overlayClock) View.VISIBLE else View.GONE
        if (p.overlayClock) clockView?.text = SimpleDateFormat(ClockFmt.pattern(this, p), Locale.getDefault()).format(Date())
        (clockView?.layoutParams as? LinearLayout.LayoutParams)?.marginStart =
            if (p.overlayVpn) (8 * resources.displayMetrics.density).toInt() else 0
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
