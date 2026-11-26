package com.glass.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AmbientIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun forecastCacheSurvivesFailedRefreshAndStaysScopedToLocationAndUnits() {
        val latitude = -20.321f
        val longitude = 153.456f
        val storage = context.getSharedPreferences("ambient_weather", Context.MODE_PRIVATE)
        val key = "$latitude,$longitude,false"
        val time = System.currentTimeMillis()
        val forecast = JSONObject("""
            {"timezone":"UTC","current":{"temperature_2m":16},"daily":{
            "time":["${LocalDate.now(java.time.ZoneOffset.UTC)}"],"weather_code":[2],
            "temperature_2m_max":[17],"temperature_2m_min":[10]}}
        """.trimIndent())
        try {
            storage.edit().putString(key, JSONObject().put("time", time).put("forecast", forecast).toString()).commit()
            val requests = Http.Session()
            requests.cancel()
            assertNull(SaverWeather.refresh(context, requests, latitude, longitude, false))
            val cached = requireNotNull(SaverWeather.cached(context, latitude, longitude, false))
            assertEquals(time, cached.updatedAt)
            assertEquals(16, cached.current.temp)
            assertNull(SaverWeather.cached(context, latitude, longitude, true))
            assertNull(SaverWeather.cached(context, latitude + 1, longitude, false))
        } finally {
            storage.edit().remove(key).commit()
        }
    }

    @Test fun missingThermalSensorDoesNotProduceTemperature() {
        val noSensors = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.HARDWARE_PROPERTIES_SERVICE) null else super.getSystemService(name)
        }
        assertNull(ambientCpuTemperature(noSensors))
    }

    @Test fun previousWallpaperAndIdleSettingsSurviveNewDefaults() {
        val prefs = Prefs(context)
        val storage = context.getSharedPreferences(Profiles.prefsName(prefs.profile), Context.MODE_PRIVATE)
        val original = storage.all
        try {
            storage.edit().remove("saverMode").remove("saverSeconds")
                .putInt("saverSrc", SaverPictures.FOLDER).putString("saverFolder", "/storage/usb/photos")
                .putInt("idleAct", 1).putInt("idleMin", 10).commit()
            val loaded = Prefs(context)
            assertEquals(AmbientMode.BALANCED, loaded.ambientMode)
            assertFalse(loaded.saverSeconds)
            assertEquals(SaverPictures.FOLDER, loaded.saverSrc)
            assertEquals("/storage/usb/photos", loaded.saverFolder)
            assertEquals(1, loaded.idleAction)
            assertEquals(10, loaded.idleMinutes)
        } finally {
            val editor = storage.edit()
            for (key in listOf("saverMode", "saverSeconds", "saverSrc", "saverFolder", "idleAct", "idleMin")) {
                when (val value = original[key]) {
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.remove(key)
                }
            }
            editor.commit()
        }
    }

    @Test fun speedRecordsAreIndependentAndFailedAttemptRetainsLastSuccess() {
        val storage = context.getSharedPreferences("ambient_speed", Context.MODE_PRIVATE)
        val initial = HashMap(storage.all)
        try {
            val speeds = AmbientSpeedStore(context)
            val vpn = AmbientSpeed(10, 17.0, 3.0)
            val direct = AmbientSpeed(20, 70.0, 21.0)
            speeds.record(AmbientRoute.VPN, vpn)
            speeds.record(AmbientRoute.DIRECT, direct)
            speeds.record(AmbientRoute.VPN, null)
            assertEquals(vpn, speeds.latest(AmbientRoute.VPN))
            assertEquals(direct, speeds.latest(AmbientRoute.DIRECT))
            assertFalse(storage.getBoolean("VPN_success", true))
            assertTrue(storage.getBoolean("DIRECT_success", false))
        } finally {
            val editor = storage.edit().clear()
            for ((key, value) in initial) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
            }
            editor.commit()
        }
    }

    @Test fun ambientRendersAtBothTvSizesAndStopsOnExit() {
        val activity = instrumentation.startActivitySync(Intent(context, ScreensaverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScreensaverActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                val ambient = content.getChildAt(0) as SaverView
                assertTrue(ambient.isRunning)
                for ((width, height) in listOf(1280 to 720, 1920 to 1080)) {
                    val preview = SaverView(activity)
                    fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                    preview.measure(exact(width), exact(height))
                    preview.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    preview.draw(Canvas(bitmap))
                    bitmap.recycle()
                    assertEquals(width, preview.width)
                    assertEquals(height, preview.height)
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            assertTrue(activity.isFinishing)
            instrumentation.runOnMainSync {
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                val ambient = content.getChildAt(0) as? SaverView
                assertFalse(ambient?.isRunning ?: false)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
