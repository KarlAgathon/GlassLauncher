package com.glass.launcher

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Open-Meteo weather shared by both screensaver entry points. */
internal object SaverWeather {
    data class Now(
        val temp: Int, val code: Int, val day: Boolean,
        val feels: Int?, val humidity: Int?, val wind: Int?, val windUnit: String
    )

    data class Day(
        val date: LocalDate, val code: Int, val max: Int, val min: Int,
        val sunrise: String?, val sunset: String?, val rain: Int?
    )

    private fun cache(context: Context) = context.getSharedPreferences("ambient_weather", Context.MODE_PRIVATE)
    private fun key(lat: Float, lon: Float, fahrenheit: Boolean) = "$lat,$lon,$fahrenheit"

    fun needsRefresh(snapshot: AmbientForecast?, now: Long) =
        snapshot == null || now - snapshot.updatedAt !in 0 until 30 * 60_000L

    fun cached(context: Context, lat: Float, lon: Float, fahrenheit: Boolean): AmbientForecast? = runCatching {
        val entry = JSONObject(cache(context).getString(key(lat, lon, fahrenheit), null) ?: return null)
        parse(entry.getJSONObject("forecast"), entry.getLong("time"))
    }.getOrNull()

    fun refresh(context: Context, requests: Http.Session, lat: Float, lon: Float, fahrenheit: Boolean): AmbientForecast? = runCatching {
        val reply = JSONObject(requests.text("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7" + if (fahrenheit) "&temperature_unit=fahrenheit" else ""))
        val time = System.currentTimeMillis()
        val snapshot = parse(reply, time)
        if (!Thread.currentThread().isInterrupted) {
            val entry = JSONObject().put("time", time).put("forecast", reply)
            cache(context).edit().putString(key(lat, lon, fahrenheit), entry.toString()).apply()
        }
        snapshot
    }.getOrNull()

    internal fun parse(reply: JSONObject, time: Long): AmbientForecast {
        val current = reply.getJSONObject("current")
        val daily = reply.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val sunrise = daily.optJSONArray("sunrise")
        val sunset = daily.optJSONArray("sunset")
        val precipitation = daily.optJSONArray("precipitation_probability_max")
        fun clock(value: String?) = value?.substringAfter('T', "")?.takeIf { it.isNotBlank() }
        val days = (0 until minOf(7, dates.length())).mapNotNull { i ->
            val high = highs.optDouble(i).takeIf { it.isFinite() } ?: return@mapNotNull null
            val low = lows.optDouble(i).takeIf { it.isFinite() && it <= high } ?: return@mapNotNull null
            Day(LocalDate.parse(dates.getString(i)), codes.optInt(i, -1),
                Math.round(high).toInt(), Math.round(low).toInt(),
                clock(sunrise?.optString(i)), clock(sunset?.optString(i)),
                precipitation?.optInt(i, -1)?.takeIf { it in 0..100 })
        }
        val units = reply.optJSONObject("current_units")
        val temperature = current.getDouble("temperature_2m")
        require(temperature.isFinite())
        val now = Now(
            Math.round(temperature).toInt(), current.optInt("weather_code", -1),
            current.optInt("is_day", 1) == 1,
            current.optDouble("apparent_temperature").takeIf { it.isFinite() }?.let { Math.round(it).toInt() },
            current.optInt("relative_humidity_2m", -1).takeIf { it in 0..100 },
            current.optDouble("wind_speed_10m").takeIf { it.isFinite() && it >= 0 }?.let { Math.round(it).toInt() },
            units?.optString("wind_speed_10m")?.takeIf { it.isNotBlank() } ?: "km/h"
        )
        return AmbientForecast(now, days, ZoneId.of(reply.getString("timezone")), time)
    }

    fun icon(code: Int, day: Boolean = true) = when (code) {
        0 -> if (day) R.drawable.lc_sun else R.drawable.lc_moon
        1, 2 -> if (day) R.drawable.lc_cloud_sun else R.drawable.lc_cloud_moon
        3 -> R.drawable.lc_cloud
        45, 48 -> R.drawable.lc_cloud_fog
        in 51..67, in 80..82 -> R.drawable.lc_cloud_rain
        in 71..77, 85, 86 -> R.drawable.lc_cloud_snow
        in 95..99 -> R.drawable.lc_cloud_lightning
        else -> R.drawable.lc_cloud
    }

    fun text(code: Int) = when (code) {
        0 -> T.s("Clear", "Vedro", "Klar")
        1 -> T.s("Mostly clear", "Pretežno vedro", "Überwiegend klar")
        2 -> T.s("Partly cloudy", "Djelimično oblačno", "Teilweise bewölkt")
        3 -> T.s("Cloudy", "Oblačno", "Bewölkt")
        45, 48 -> T.s("Fog", "Magla", "Nebel")
        in 51..57 -> T.s("Drizzle", "Rosulja", "Nieselregen")
        in 61..67, in 80..82 -> T.s("Rain", "Kiša", "Regen")
        in 71..77, 85, 86 -> T.s("Snow", "Snijeg", "Schnee")
        in 95..99 -> T.s("Thunderstorm", "Grmljavina", "Gewitter")
        else -> ""
    }
}
