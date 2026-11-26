package com.glass.launcher

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities as Capabilities
import android.os.Build
import android.os.HardwarePropertiesManager
import android.os.PowerManager
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

internal enum class AmbientMode { MINIMAL, BALANCED, FULL }
internal enum class AmbientMetric(val icon: Int) {
    VPN(R.drawable.lc_key_round),
    VPN_SPEED(R.drawable.lc_gauge),
    DIRECT_SPEED(R.drawable.lc_gauge),
    RAM(R.drawable.lc_cpu),
    TEMPERATURE(R.drawable.lc_activity),
    CONNECTION(R.drawable.lc_wifi);

    fun title(): String = when (this) {
        VPN -> "VPN"
        VPN_SPEED -> T.s("VPN speed", "VPN brzina", "VPN-Tempo", "Скорость VPN")
        DIRECT_SPEED -> T.s("Direct speed", "Direktna brzina", "Direktes Tempo", "Скорость без VPN")
        RAM -> "RAM"
        TEMPERATURE -> T.s("Temperature", "Temperatura", "Temperatur", "Температура")
        CONNECTION -> T.s("Connection", "Veza", "Verbindung", "Подключение")
    }
}

internal data class AmbientMemory(val used: Long, val total: Long) {
    val percent: Int get() = (used.toDouble() / total * 100).toInt()
    companion object {
        fun from(total: Long, available: Long): AmbientMemory? =
            if (total > 0 && available in 0..total) AmbientMemory(total - available, total) else null

        fun read(context: Context): AmbientMemory? = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            from(info.totalMem, info.availMem)
        }.getOrNull()
    }
}

internal data class AmbientNetwork(val vpn: Boolean?, val connection: String, val vpnName: String? = null) {
    companion object {
        fun read(context: Context): AmbientNetwork = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val active = manager.activeNetwork
            val capabilities = active?.let(manager::getNetworkCapabilities)
            @Suppress("DEPRECATION")
            val visible = manager.allNetworks.map(manager::getNetworkCapabilities) + capabilities
            val tunnel = visible.filterNotNull().firstOrNull { it.hasTransport(Capabilities.TRANSPORT_VPN) }
            val vpn = ambientVpnState(tunnel != null, capabilities != null)
            val vpnName = if (Build.VERSION.SDK_INT >= 30 && tunnel != null) runCatching {
                val uid = tunnel.ownerUid
                if (uid < 0) return@runCatching null
                val pm = context.packageManager
                val packageName = pm.getPackagesForUid(uid)?.singleOrNull() ?: return@runCatching null
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().takeIf { it.isNotBlank() }
            }.getOrNull() else null
            val connection = when {
                active == null -> T.s("Offline", "Van mreže", "Offline", "Не в сети")
                capabilities == null -> T.s("Unavailable", "Nedostupno", "Nicht verfügbar", "Недоступно")
                else -> {
                    val transport = when {
                        capabilities.hasTransport(Capabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        capabilities.hasTransport(Capabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        capabilities.hasTransport(Capabilities.TRANSPORT_CELLULAR) -> T.s("Mobile", "Mobilna mreža", "Mobilfunk", "Мобильная сеть")
                        capabilities.hasTransport(Capabilities.TRANSPORT_VPN) -> "VPN"
                        else -> T.s("Network", "Mreža", "Netzwerk", "Сеть")
                    }
                    val validation = if (capabilities.hasCapability(Capabilities.NET_CAPABILITY_VALIDATED))
                        T.s("Internet validated", "Internet potvrđen", "Internet bestätigt", "Интернет подтверждён")
                    else T.s("Internet unverified", "Internet nije potvrđen", "Internet unbestätigt", "Интернет не подтверждён")
                    "$transport · $validation"
                }
            }
            AmbientNetwork(vpn, connection, vpnName)
        }.getOrDefault(AmbientNetwork(null, "Unavailable"))
    }
}

internal fun ambientVpnState(visibleTunnel: Boolean, observableNetwork: Boolean): Boolean? = when {
    visibleTunnel -> true
    observableNetwork -> false
    else -> null
}

internal fun ambientCpuTemperature(context: Context): Float? = runCatching {
    context.getSystemService(HardwarePropertiesManager::class.java)
        ?.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, HardwarePropertiesManager.TEMPERATURE_CURRENT)
        ?.filter { it.isFinite() && it in -40f..150f }?.maxOrNull()
}.getOrNull()

internal fun ambientClockPattern(is24: Boolean, seconds: Boolean) =
    (if (is24) "HH:mm" else "h:mm") + if (seconds) ":ss" else ""

internal fun ambientThermalStatus(context: Context): String? {
    if (Build.VERSION.SDK_INT < 29) return null
    return runCatching {
        when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> null
        }
    }.getOrNull()
}

internal enum class AmbientRoute { VPN, DIRECT }

internal data class AmbientSpeed(val time: Long, val download: Double, val upload: Double) {
    fun isStale(now: Long) = now - time >= 86_400_000L || now < time
    val valid: Boolean get() = download.isFinite() && upload.isFinite() && download > 0 && upload > 0
}

internal class AmbientSpeedStore(context: Context) {
    private val storage = context.getSharedPreferences("ambient_speed", Context.MODE_PRIVATE)

    fun record(route: AmbientRoute, result: AmbientSpeed?) {
        val key = route.name
        val success = result?.valid == true
        val edit = storage.edit().putLong("${key}_attempt", System.currentTimeMillis())
            .putBoolean("${key}_success", success)
        if (result != null && success) {
            edit.putString(key, JSONObject().put("time", result.time)
                .put("download", result.download).put("upload", result.upload).toString())
        }
        edit.apply()
    }

    fun latest(route: AmbientRoute): AmbientSpeed? = runCatching {
        val data = JSONObject(storage.getString(route.name, null) ?: return null)
        AmbientSpeed(data.getLong("time"), data.getDouble("download"), data.getDouble("upload"))
            .takeIf { it.valid }
    }.getOrNull()
}

internal data class AmbientForecast(
    val current: SaverWeather.Now,
    val days: List<SaverWeather.Day>,
    val timeZone: ZoneId,
    val updatedAt: Long
) {
    fun week(today: LocalDate = LocalDate.now(timeZone)): List<SaverWeather.Day?> =
        List(7) { offset -> days.firstOrNull { it.date == today.plusDays(offset.toLong()) } }
}
