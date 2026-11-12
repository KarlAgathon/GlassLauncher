package com.glass.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** What fills the storage: a pie chart by kind of file (video, music, pictures, documents, apps, other). */
class StorageActivity : Activity() {

    companion object {
        const val EXTRA_ROOT = "root"

        val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "rtf", "csv", "json", "xml", "html", "md", "epub")
        val ARCH = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
    }

    data class Slice(val name: String, val color: Int, var bytes: Long = 0L, var count: Int = 0)

    private lateinit var pie: PieView
    private lateinit var legend: LinearLayout
    private lateinit var head: TextView
    private lateinit var sub: TextView

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(Prefs(this).lang)
        val root = File(intent.getStringExtra(EXTRA_ROOT) ?: Environment.getExternalStorageDirectory().absolutePath)

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(11, 10, 22)) }
        Backdrop.blurred?.let { bd -> frame.addView(ImageView(this).apply {
            setImageBitmap(bd); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.35f }, FrameLayout.LayoutParams(-1, -1)) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f).toInt(), dp(28f).toInt(), dp(48f).toInt(), dp(20f).toInt())
        }
        head = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Fonts.medium(this@StorageActivity)
            text = T.s("Storage", "Memorija", "Speicher")
        }
        col.addView(head)
        sub = TextView(this).apply {
            setTextColor(Color.argb(170, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Fonts.regular(this@StorageActivity)
            text = T.s("Counting files…", "Brojim fajlove…", "Zähle Dateien…")
        }
        col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f).toInt() })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pie = PieView(this)
        row.addView(pie, LinearLayout.LayoutParams(dp(300f).toInt(), dp(300f).toInt()))
        legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26f).toInt(), dp(20f).toInt(), dp(26f).toInt(), dp(20f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(22f); setColor(Color.argb(60, 255, 255, 255)); setStroke(dp(1f).toInt(), Color.argb(70, 255, 255, 255))
            }
        }
        row.addView(legend, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(48f).toInt() })
        col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16f).toInt() })
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)

        Thread { scan(root) }.start()
    }

    private fun scan(root: File) {
        val slices = linkedMapOf(
            "video" to Slice(T.s("Videos", "Video", "Videos"), Color.rgb(255, 99, 132)),
            "audio" to Slice(T.s("Music & audio", "Muzika i zvuk", "Musik & Audio"), Color.rgb(255, 176, 59)),
            "image" to Slice(T.s("Pictures", "Slike", "Bilder"), Color.rgb(80, 220, 160)),
            "doc" to Slice(T.s("Documents", "Dokumenti", "Dokumente"), Color.rgb(90, 170, 255)),
            "apk" to Slice(T.s("Apps (APK)", "Aplikacije (APK)", "Apps (APK)"), Color.rgb(170, 120, 255)),
            "arch" to Slice(T.s("Archives", "Arhive", "Archive"), Color.rgb(120, 230, 240)),
            "other" to Slice(T.s("Other files", "Ostali fajlovi", "Andere Dateien"), Color.rgb(180, 185, 200)),
        )
        var lastPost = 0L
        fun walk(d: File, depth: Int) {
            if (depth > 40) return
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) { walk(f, depth + 1); continue }
                val e = f.extension.lowercase()
                val key = when {
                    e in MediaViewerActivity.VIDEO -> "video"
                    e in MediaViewerActivity.AUDIO -> "audio"
                    e in MediaViewerActivity.IMAGE -> "image"
                    e in DOCS -> "doc"
                    e == "apk" -> "apk"
                    e in ARCH -> "arch"
                    else -> "other"
                }
                slices[key]!!.let { it.bytes += f.length(); it.count++ }
                val now = System.currentTimeMillis()
                if (now - lastPost > 400) { lastPost = now; val snap = slices.values.map { it.copy() }; runOnUiThread { render(root, snap, false) } }
            }
        }
        walk(root, 0)
        val done = slices.values.map { it.copy() }
        runOnUiThread { render(root, done, true) }
    }

    private fun render(root: File, files: List<Slice>, done: Boolean) {
        val st = runCatching { StatFs(root.absolutePath) }.getOrNull()
        val total = st?.totalBytes ?: 0L
        val free = st?.availableBytes ?: 0L
        val used = (total - free).coerceAtLeast(0L)
        val counted = files.sumOf { it.bytes }
        // what is used but not in visible files: Android itself, installed apps and their data
        val system = Slice(T.s("System & installed apps", "Sistem i instalirane aplikacije", "System & installierte Apps"),
            Color.rgb(110, 115, 140), (used - counted).coerceAtLeast(0L))
        val freeSlice = Slice(T.s("Free", "Slobodno", "Frei"), Color.argb(70, 255, 255, 255), free)
        val all = files.filter { it.bytes > 0 } + listOfNotNull(if (done && system.bytes > 0) system else null) + freeSlice
        pie.set(all.map { it.bytes.toFloat() to it.color },
            Formatter.formatShortFileSize(this, used), T.s("used of ", "zauzeto od ", "belegt von ") + Formatter.formatShortFileSize(this, total))

        head.text = T.s("Storage", "Memorija", "Speicher") + " – " + if (root == Environment.getExternalStorageDirectory())
            T.s("internal", "interna", "intern") else root.name
        sub.text = if (done) T.s("Free: ", "Slobodno: ", "Frei: ") + Formatter.formatShortFileSize(this, free) +
            "   ·   " + T.s("Total: ", "Ukupno: ", "Gesamt: ") + Formatter.formatShortFileSize(this, total)
        else T.s("Counting files…", "Brojim fajlove…", "Zähle Dateien…")

        legend.removeAllViews()
        for (s in all.sortedByDescending { if (it === freeSlice) -1L else it.bytes }) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.color) }
            }, LinearLayout.LayoutParams(dp(14f).toInt(), dp(14f).toInt()).apply { marginEnd = dp(14f).toInt() })
            r.addView(TextView(this).apply {
                text = s.name + if (s.count > 0) "  (${s.count})" else ""
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Fonts.regular(this@StorageActivity)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val pct = if (total > 0) (s.bytes * 100.0 / total) else 0.0
            r.addView(TextView(this).apply {
                text = Formatter.formatShortFileSize(this@StorageActivity, s.bytes) + String.format(java.util.Locale.US, "   %.1f %%", pct)
                setTextColor(Color.argb(210, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.medium(this@StorageActivity)
            })
            legend.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7f).toInt(); bottomMargin = dp(7f).toInt() })
        }
    }

    /** Donut chart with the used size in the middle. */
    class PieView(ctx: Context) : View(ctx) {
        private var parts: List<Pair<Float, Int>> = emptyList()
        private var center = ""
        private var centerSub = ""
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Fonts.medium(ctx) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255); textAlign = Paint.Align.CENTER; typeface = Fonts.regular(ctx) }
        private val r = RectF()

        fun set(p: List<Pair<Float, Int>>, c: String, cs: String) { parts = p; center = c; centerSub = cs; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val sw = 34 * d
            val size = minOf(width, height).toFloat()
            r.set((width - size) / 2 + sw / 2, (height - size) / 2 + sw / 2, (width + size) / 2 - sw / 2, (height + size) / 2 - sw / 2)
            arc.strokeWidth = sw
            val sum = parts.sumOf { it.first.toDouble() }.toFloat()
            var start = -90f
            if (sum > 0f) for ((v, c) in parts) {
                val sweep = 360f * v / sum
                if (sweep <= 0f) continue
                arc.color = c
                canvas.drawArc(r, start, sweep - if (sweep > 2f) 1.2f else 0f, false, arc)
                start += sweep
            }
            big.textSize = 30 * d
            small.textSize = 14 * d
            canvas.drawText(center, width / 2f, height / 2f + 4 * d, big)
            canvas.drawText(centerSub, width / 2f, height / 2f + 28 * d, small)
        }
    }
}
