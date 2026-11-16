package com.glass.launcher

import android.content.Context

/** Notes when LEAD crashes. Three crashes within ten minutes = the next start is a safe start. */
object CrashWatch {
    private fun g(ctx: Context) = ctx.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val now = System.currentTimeMillis()
                val list = times(app).filter { now - it < 10 * 60_000L } + now
                // commit(): the app is about to die, apply() might not be written any more
                g(app).edit().putString("crashTimes", list.joinToString(",")).commit()
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun times(ctx: Context) = (g(ctx).getString("crashTimes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    fun tooMany(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        return times(ctx).count { now - it < 10 * 60_000L } >= 3
    }

    fun clear(ctx: Context) { g(ctx).edit().remove("crashTimes").apply() }
}
