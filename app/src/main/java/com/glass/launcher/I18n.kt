package com.glass.launcher

import java.util.Locale

/**
 * Tiny translation helper: T.s("English", "Bosanski", "Deutsch").
 * Bulgarian, Romanian, Turkish and Arabic come from [I18nExtra] (looked up by the English text; English if missing).
 */
object T {
    /** "en", "bs", "hr", "sr" (Cyrillic), "de", "bg", "ro", "tr" or "ar" */
    @Volatile var lang: String = "en"

    private val extra = listOf("bg", "ro", "tr", "ar")

    fun init(saved: String?) {
        lang = saved ?: when (Locale.getDefault().language) {
            "de" -> "de"
            "bs", "sh" -> "bs"
            "hr" -> "hr"
            "sr" -> "sr"
            "bg" -> "bg"
            "ro" -> "ro"
            "tr" -> "tr"
            "ar" -> "ar"
            else -> "en"
        }
    }

    fun s(en: String, bs: String, de: String): String = when (lang) {
        "bs" -> bs
        "hr" -> Slavic.croatian(bs)
        "sr" -> Slavic.cyrillic(bs)
        "de" -> de
        "en" -> en
        else -> {
            val i = extra.indexOf(lang)
            if (i < 0) en else I18nExtra.map[en]?.getOrNull(i) ?: en
        }
    }

    val locale: Locale get() = Locale(lang)

    val languages = listOf(
        Triple("en", "English", R.drawable.flag_en),
        Triple("bs", "Bosanski", R.drawable.flag_bs),
        Triple("hr", "Hrvatski", R.drawable.flag_hr),
        Triple("sr", "Српски (ћирилица)", R.drawable.flag_rs),
        Triple("de", "Deutsch", R.drawable.flag_de),
        Triple("bg", "Български", R.drawable.flag_bg),
        Triple("ro", "Română", R.drawable.flag_ro),
        Triple("tr", "Türkçe", R.drawable.flag_tr),
        Triple("ar", "العربية", R.drawable.flag_ar),
    )
}

/**
 * Croatian and Serbian Cyrillic are made from the Bosnian texts:
 * Croatian with a few word swaps, Serbian by turning the Latin letters into Cyrillic.
 */
object Slavic {
    private val hrWords = listOf(
        "Obavještenja" to "Obavijesti", "obavještenja" to "obavijesti", "Obavještenje" to "Obavijest", "obavještenje" to "obavijest",
        "Fajlovi" to "Datoteke", "fajlovi" to "datoteke", "Fajlove" to "Datoteke", "fajlove" to "datoteke",
        "Fajla" to "Datoteke", "fajla" to "datoteke", "Fajl" to "Datoteka", "fajl" to "datoteka",
        "Historija" to "Povijest", "historija" to "povijest", "sedmica" to "tjedan", "Sedmica" to "Tjedan",
        "hiljad" to "tisuć", "Hiljad" to "Tisuć", "uključi" to "uključi", "Generalno" to "Općenito",
    )
    private val hrCache = HashMap<String, String>()
    private val srCache = HashMap<String, String>()

    fun croatian(bs: String): String = synchronized(hrCache) {
        hrCache.getOrPut(bs) { var t = bs; for ((a, b) in hrWords) t = t.replace(a, b); t }
    }

    /** words that stay in Latin letters (names, abbreviations, technical words) */
    private val keep = setOf(
        "Google", "GitHub", "Wi-Fi", "WiFi", "Bluetooth", "Shizuku", "Projectivy", "SofaDrop", "Play", "Store", "Aurora",
        "Android", "YouTube", "Netflix", "VLC", "Ethernet", "Private", "DNS", "White", "Snow", "TMDB", "LEAD", "Launcher",
        "launcher", "Backup", "backup", "Screensaver", "screensaver", "Auto-start", "File", "browser", "update.json", "json",
        "http", "https", "Telescope", "TizenTube", "SmartTube", "IPTV", "Smarters", "Anynet", "CEC", "HDMI", "Force", "stop",
        "Open-Meteo", "OK", "USB", "VPN", "CPU", "RAM", "URL", "APK", "ADB", "dp", "px", "h", "MB", "GB", "KB", "TB",
    )

    private val one = mapOf(
        'a' to "а", 'b' to "б", 'c' to "ц", 'č' to "ч", 'ć' to "ћ", 'd' to "д", 'đ' to "ђ", 'e' to "е", 'f' to "ф",
        'g' to "г", 'h' to "х", 'i' to "и", 'j' to "ј", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о",
        'p' to "п", 'r' to "р", 's' to "с", 'š' to "ш", 't' to "т", 'u' to "у", 'v' to "в", 'z' to "з", 'ž' to "ж",
    )

    private fun word(w: String): String {
        if (w in keep || w.any { it in "qwxyQWXY" }) return w
        if (w.length >= 2 && w.all { it.isUpperCase() || !it.isLetter() }) return w   // abbreviations: OK, HD …
        val sb = StringBuilder(); var i = 0
        while (i < w.length) {
            val c = w[i]; val n = w.getOrNull(i + 1)
            val lc = c.lowercaseChar(); val ln = n?.lowercaseChar()
            val di = when {
                lc == 'l' && ln == 'j' -> "љ"; lc == 'n' && ln == 'j' -> "њ"; lc == 'd' && ln == 'ž' -> "џ"; else -> null
            }
            if (di != null) { sb.append(if (c.isUpperCase()) di.uppercase() else di); i += 2; continue }
            val m = one[lc]
            sb.append(if (m == null) c.toString() else if (c.isUpperCase()) m.uppercase() else m)
            i++
        }
        return sb.toString()
    }

    fun cyrillic(bs: String): String = synchronized(srCache) {
        srCache.getOrPut(bs) {
            // keep %s / %d placeholders and anything after "://" untouched
            Regex("""%[0-9]*\$?[sd]|[\p{L}][\p{L}.\-]*""").replace(bs) { m ->
                val v = m.value
                if (v.startsWith("%")) v else word(v.trimEnd('.', '-')) + v.substring(v.trimEnd('.', '-').length)
            }
        }
    }
}
