package com.glass.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Built-in viewer so pictures, videos and music open without any other app.
 *  - pictures: jpg, png, webp, bmp, gif (animated), heic/heif; LEFT/RIGHT = previous/next picture in the folder
 *  - video and music: OK = pause/play, LEFT/RIGHT = 10 s back/forward, UP/DOWN = previous/next file in the folder
 * If Android can't play a file, it offers to open it with another app.
 */
class MediaViewerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"

        val IMAGE = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "wmv", "flv", "3gp", "mpg", "mpeg", "m2ts", "mts", "vob")
        val AUDIO = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "amr", "mid", "midi", "oga")

        fun kind(f: File): String? {
            val e = f.extension.lowercase()
            return when (e) {
                in IMAGE -> "image"
                in VIDEO -> "video"
                in AUDIO -> "audio"
                else -> null
            }
        }

        const val VLC = "org.videolan.vlc"

        fun hasVlc(act: Activity) = runCatching { act.packageManager.getPackageInfo(VLC, 0); true }.getOrDefault(false)

        /**
         * Default players: videos and music open in VLC (when it is installed), pictures in the built-in viewer.
         * Without VLC, videos and music play in the built-in player.
         */
        fun openDefault(act: Activity, f: File) {
            val k = kind(f)
            if ((k == "video" || k == "audio") && hasVlc(act)) {
                try {
                    val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                        ?: if (k == "video") "video/*" else "audio/*"
                    act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(VLC)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    return
                } catch (e: Exception) { /* fall back to the built-in player */ }
            }
            if (k != null) {
                act.startActivity(Intent(act, MediaViewerActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath))
                return
            }
            openWith(act, f)
        }

        /** Shares one or more files (send to another app). */
        fun share(act: Activity, files: List<File>) {
            try {
                val uris = ArrayList(files.filter { it.isFile }.map { FileProvider.getUriForFile(act, "${act.packageName}.files", it) })
                if (uris.isEmpty()) return
                val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                i.type = if (files.size == 1) (MimeTypeMap.getSingleton().getMimeTypeFromExtension(files[0].extension.lowercase()) ?: "*/*") else "*/*"
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(i, T.s("Share", "Podijeli", "Teilen")))
            } catch (e: Exception) {
                Toast.makeText(act, T.s("No app can receive this", "Nijedna aplikacija ne može primiti ovo", "Keine App kann das empfangen"),
                    Toast.LENGTH_LONG).show()
            }
        }

        /** Opens a file with another app, always with the Android "open with" chooser. */
        fun openWith(act: Activity, f: File) {
            try {
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(i, T.s("Open with", "Otvori s", "Öffnen mit")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(act, T.s("No app can open this file", "Nijedna aplikacija ne može otvoriti ovaj fajl",
                    "Keine App kann diese Datei öffnen"), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(act, T.s("Can't open this file", "Ne mogu otvoriti ovaj fajl", "Datei kann nicht geöffnet werden"),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var audioBox: LinearLayout
    private lateinit var audioTitle: TextView
    private lateinit var bar: LinearLayout
    private lateinit var barTitle: TextView
    private lateinit var barTime: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playIcon: ImageView

    private var files: List<File> = emptyList()
    private var index = 0
    private var kind: String? = null
    private var loadToken = 0

    private val hideBar = Runnable { if (kind != "audio") bar.animate().alpha(0f).setDuration(300).start() }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            h.postDelayed(this, 500)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(Prefs(this).lang)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val f = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        val k = kind(f)
        if (k == null) { openWith(this, f); finish(); return }
        // the other files of the same kind in this folder, for previous / next
        val sameKind: (File) -> Boolean = { kind(it) == k || (k != "image" && kind(it) in setOf("video", "audio")) }
        files = (f.parentFile?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && sameKind(it) } ?: listOf(f))
            .sortedBy { it.name.lowercase() }
        index = files.indexOfFirst { it.absolutePath == f.absolutePath }.coerceAtLeast(0)
        if (files.isEmpty()) files = listOf(f)
        showCurrent()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        video = VideoView(this).apply { visibility = View.GONE }
        root.addView(video, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        audioBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val note = ImageView(this).apply { setImageResource(R.drawable.lc_file_music); alpha = 0.85f }
        audioBox.addView(note, LinearLayout.LayoutParams(dp(120f).toInt(), dp(120f).toInt()))
        audioTitle = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Fonts.medium(this@MediaViewerActivity); gravity = Gravity.CENTER
            setPadding(dp(40f).toInt(), dp(24f).toInt(), dp(40f).toInt(), 0)
        }
        audioBox.addView(audioTitle)
        root.addView(audioBox, FrameLayout.LayoutParams(-1, -1))

        // bottom glass bar: name, play state, time, progress
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(14f).toInt(), dp(24f).toInt(), dp(16f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(22f)
                setColor(Color.argb(150, 12, 14, 24))
                setStroke(dp(1f).toInt(), Color.argb(70, 255, 255, 255))
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playIcon = ImageView(this).apply { setImageResource(R.drawable.lc_play) }
        row.addView(playIcon, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()).apply { marginEnd = dp(12f).toInt() })
        barTitle = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Fonts.medium(this@MediaViewerActivity); isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        row.addView(barTitle, LinearLayout.LayoutParams(0, -2, 1f))
        barTime = TextView(this).apply {
            setTextColor(Color.argb(200, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.regular(this@MediaViewerActivity)
        }
        row.addView(barTime, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16f).toInt() })
        bar.addView(row)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        bar.addView(progress, LinearLayout.LayoutParams(-1, dp(6f).toInt()).apply { topMargin = dp(10f).toInt() })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            val m = dp(40f).toInt(); setMargins(m, 0, m, dp(28f).toInt())
        })
        setContentView(root)
    }

    private fun current() = files[index]

    private fun showCurrent() {
        val f = current()
        kind = kind(f)
        barTitle.text = f.name + if (files.size > 1) "   (${index + 1}/${files.size})" else ""
        h.removeCallbacks(tick)
        runCatching { video.stopPlayback() }
        image.visibility = View.GONE; video.visibility = View.GONE; audioBox.visibility = View.GONE
        when (kind) {
            "image" -> showImage(f)
            else -> play(f)
        }
        showBar()
    }

    // ------------------------------------------------------------------ pictures

    private fun showImage(f: File) {
        image.visibility = View.VISIBLE
        image.setImageDrawable(null)
        progress.visibility = View.GONE
        playIcon.setImageResource(R.drawable.lc_file_image)
        barTime.text = ""
        val token = ++loadToken
        val dm = resources.displayMetrics
        Thread {
            val d: Any? = runCatching { decode(f, dm.widthPixels, dm.heightPixels) }.getOrNull()
            h.post {
                if (token != loadToken || isFinishing) return@post
                when (d) {
                    is Drawable -> { image.setImageDrawable(d); if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) d.start() }
                    is Bitmap -> image.setImageBitmap(d)
                    else -> cantOpen(f)
                }
            }
        }.start()
    }

    /** Decodes any picture Android knows (HEIC and animated GIF on Android 9+), scaled down to the screen. */
    private fun decode(f: File, w: Int, hgt: Int): Any? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(f)
            return ImageDecoder.decodeDrawable(src) { dec, info, _ ->
                val s = maxOf(info.size.width.toFloat() / w, info.size.height.toFloat() / hgt)
                if (s > 1f) dec.setTargetSize((info.size.width / s).toInt().coerceAtLeast(1), (info.size.height / s).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= w || o.outHeight / (sample * 2) >= hgt) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ------------------------------------------------------------------ video & music

    private fun play(f: File) {
        val isAudio = kind == "audio"
        progress.visibility = View.VISIBLE
        progress.progress = 0
        video.visibility = View.VISIBLE
        if (isAudio) {
            audioBox.visibility = View.VISIBLE
            audioTitle.text = audioName(f)
            // the video view stays (tiny) so it can play the sound
            video.layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
        } else {
            video.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        video.setOnPreparedListener { mp ->
            playIcon.setImageResource(R.drawable.lc_play)
            mp.isLooping = false
            video.start()
            h.post(tick)
        }
        video.setOnCompletionListener { if (index < files.size - 1) { index++; showCurrent() } else showBar() }
        video.setOnErrorListener { _, _, _ ->
            cantOpen(f)
            true
        }
        video.setVideoPath(f.absolutePath)
    }

    private fun audioName(f: File): String = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        r.release()
        if (!t.isNullOrBlank()) (if (!a.isNullOrBlank()) "$a – $t" else t) else f.nameWithoutExtension
    }.getOrDefault(f.nameWithoutExtension)

    private fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun updateTime() {
        if (kind == "image") return
        val dur = runCatching { video.duration }.getOrDefault(0)
        val pos = runCatching { video.currentPosition }.getOrDefault(0)
        barTime.text = if (dur > 0) "${fmt(pos)} / ${fmt(dur)}" else fmt(pos)
        progress.progress = if (dur > 0) (pos * 1000L / dur).toInt() else 0
        playIcon.alpha = if (video.isPlaying) 1f else 0.45f
    }

    private fun cantOpen(f: File) {
        Toast.makeText(this, T.s("This file can't be shown here – choose another app",
            "Ovaj fajl se ne može prikazati ovdje – izaberi drugu aplikaciju",
            "Diese Datei kann hier nicht angezeigt werden – andere App wählen"), Toast.LENGTH_LONG).show()
        openWith(this, f)
    }

    // ------------------------------------------------------------------ remote

    private fun showBar() {
        bar.animate().cancel()
        bar.alpha = 1f
        h.removeCallbacks(hideBar)
        h.postDelayed(hideBar, 3500)
    }

    private fun step(d: Int) {
        if (files.size < 2) return
        index = (index + d + files.size) % files.size
        showCurrent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val media = kind != "image"
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (media) { video.seekTo((video.currentPosition - 10_000).coerceAtLeast(0)); showBar(); updateTime() } else step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (media) { video.seekTo(video.currentPosition + 10_000); showBar(); updateTime() } else step(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (media) step(-1) else showBar(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> { if (media) step(1) else showBar(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (media) { if (video.isPlaying) video.pause() else video.start(); updateTime() }
                showBar()
                return true
            }
            KeyEvent.KEYCODE_MENU -> { openWith(this, current()); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (kind != "image") runCatching { video.pause() }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        runCatching { video.stopPlayback() }
        super.onDestroy()
    }
}
