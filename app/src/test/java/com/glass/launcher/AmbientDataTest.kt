package com.glass.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AmbientDataTest {
    private val firstDay = LocalDate.of(2026, 12, 29)

    private fun forecast(updated: Long = 10_000L): AmbientForecast {
        val dates = (0..6).joinToString(",") { "\"${firstDay.plusDays(it.toLong())}\"" }
        return SaverWeather.parse(JSONObject("""
            {
              "timezone":"Pacific/Auckland",
              "current":{"temperature_2m":14.7,"weather_code":2,"is_day":1},
              "current_units":{"wind_speed_10m":"km/h"},
              "daily":{
                "time":[$dates],
                "weather_code":[2,3,0,0,0,0,63],
                "temperature_2m_max":[15,16,17,18,19,20,21],
                "temperature_2m_min":[4,5,6,7,8,9,10],
                "sunrise":["2026-12-29T06:05"],
                "sunset":["2026-12-29T20:11"],
                "precipitation_probability_max":[0,null]
              }
            }
        """.trimIndent()), updated)
    }

    @Test fun forecastStartsTodayAndIncludesSixMoreCalendarDays() {
        val week = forecast().week(firstDay)
        assertEquals((0..6).map { firstDay.plusDays(it.toLong()) }, week.map { it?.date })
        assertEquals(15, week.first()?.max)
        assertEquals(63, week.last()?.code)
        assertEquals("06:05", week.first()?.sunrise)
        assertEquals("20:11", week.first()?.sunset)
        assertEquals(0, week.first()?.rain)
        assertNull(week[1]?.rain)
    }

    @Test fun cachedForecastAdvancesWithoutRelabelingYesterday() {
        val week = forecast().week(firstDay.plusDays(1))
        assertEquals(16, week.first()?.max)
        assertEquals(7, week.size)
        assertNull(week.last())
        assertTrue(forecast().week(firstDay.plusDays(7)).all { it == null })
    }

    @Test fun weatherFreshnessUsesTheFetchTimestamp() {
        assertFalse(SaverWeather.needsRefresh(forecast(), 1_809_999))
        assertTrue(SaverWeather.needsRefresh(forecast(), 1_810_000))
        assertTrue(SaverWeather.needsRefresh(forecast(), 9_999))
        assertTrue(SaverWeather.needsRefresh(null, 10_000))
        assertNull(forecast().current.feels)
        assertNull(forecast().current.wind)
        assertNull(forecast().current.humidity)
    }

    @Test fun clockSupportsBothFormatsAndOptionalSeconds() {
        fun format(is24: Boolean, seconds: Boolean): String =
            SimpleDateFormat(ambientClockPattern(is24, seconds), Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(13 * 3_600_000L + 4 * 60_000 + 20_000))
        assertEquals("13:04", format(true, false))
        assertEquals("1:04", format(false, false))
        assertEquals("13:04:20", format(true, true))
        assertEquals("1:04:20", format(false, true))
    }

    @Test fun ramUsesDeviceTotalsAndRejectsInvalidReadings() {
        val memory = requireNotNull(AmbientMemory.from(4_000_000_000, 1_800_000_000))
        assertEquals(2_200_000_000, memory.used)
        assertEquals(55, memory.percent)
        assertNull(AmbientMemory.from(0, 0))
        assertNull(AmbientMemory.from(100, 101))
        assertNull(AmbientMemory.from(100, -1))
    }

    @Test fun vpnDetectionDistinguishesVisibleTunnelAndUnavailableState() {
        assertEquals(true, ambientVpnState(visibleTunnel = true, observableNetwork = false))
        assertEquals(false, ambientVpnState(visibleTunnel = false, observableNetwork = true))
        assertNull(ambientVpnState(visibleTunnel = false, observableNetwork = false))
    }

    @Test fun speedStalenessAndPartialResults() {
        val result = AmbientSpeed(20_000, 15.0, 5.0)
        assertTrue(result.valid)
        assertFalse(result.isStale(20_000 + 86_399_999L))
        assertTrue(result.isStale(20_000 + 86_400_000L))
        assertTrue(result.isStale(19_999))
        assertFalse(result.copy(upload = 0.0).valid)
        assertFalse(result.copy(download = Double.NaN).valid)
    }

    @Test fun oversizedWideAndPortraitWallpapersAreBoundedBeforeDecoding() {
        assertEquals(2, ambientSampleSize(3840, 2160, 1920, 1080))
        assertEquals(2, ambientSampleSize(2560, 1440, 1280, 720))
        for ((width, height) in listOf(48_000 to 800, 800 to 48_000, 8_000 to 12_000)) {
            val sample = ambientSampleSize(width, height, 1920, 1080)
            assertTrue(width / sample <= 4096)
            assertTrue(height / sample <= 4096)
            assertTrue((width / sample).toLong() * (height / sample) <= 4_150_000)
        }
    }
}
