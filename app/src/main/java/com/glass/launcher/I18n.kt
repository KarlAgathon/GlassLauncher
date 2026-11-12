package com.glass.launcher

import java.util.Locale

/** Tiny translation helper: T.s("English", "Bosanski", "Deutsch"). */
object T {
    /** "en", "bs" or "de" */
    @Volatile var lang: String = "en"

    fun init(saved: String?) {
        lang = saved ?: when (Locale.getDefault().language) {
            "de" -> "de"
            "bs", "hr", "sr", "sh" -> "bs"
            else -> "en"
        }
    }

    fun s(en: String, bs: String, de: String): String = when (lang) {
        "bs" -> bs
        "de" -> de
        else -> en
    }

    val locale: Locale get() = Locale(lang)

    val languages = listOf(
        Triple("en", "English", R.drawable.flag_en),
        Triple("bs", "Bosanski", R.drawable.flag_bs),
        Triple("de", "Deutsch", R.drawable.flag_de),
    )
}
