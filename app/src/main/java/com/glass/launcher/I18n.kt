package com.glass.launcher

import java.util.Locale

/**
 * Tiny translation helper: T.s("English", "Bosanski", "Deutsch").
 * Bulgarian, Romanian, Turkish and Arabic come from [I18nExtra] (looked up by the English text; English if missing).
 */
object T {
    /** "en", "bs", "de", "bg", "ro", "tr" or "ar" */
    @Volatile var lang: String = "en"

    private val extra = listOf("bg", "ro", "tr", "ar")

    fun init(saved: String?) {
        lang = saved ?: when (Locale.getDefault().language) {
            "de" -> "de"
            "bs", "hr", "sr", "sh" -> "bs"
            "bg" -> "bg"
            "ro" -> "ro"
            "tr" -> "tr"
            "ar" -> "ar"
            else -> "en"
        }
    }

    fun s(en: String, bs: String, de: String): String = when (lang) {
        "bs" -> bs
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
        Triple("de", "Deutsch", R.drawable.flag_de),
        Triple("bg", "Български", R.drawable.flag_bg),
        Triple("ro", "Română", R.drawable.flag_ro),
        Triple("tr", "Türkçe", R.drawable.flag_tr),
        Triple("ar", "العربية", R.drawable.flag_ar),
    )
}
