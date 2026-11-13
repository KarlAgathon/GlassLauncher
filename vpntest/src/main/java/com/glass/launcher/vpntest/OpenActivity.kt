package com.glass.launcher.vpntest

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/** Opening the helper from the app list just opens the speed test in LEAD Launcher. */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(Intent().setComponent(ComponentName("com.glass.launcher", "com.glass.launcher.SpeedTestActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
