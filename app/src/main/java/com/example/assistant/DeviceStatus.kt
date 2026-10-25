package com.example.assistant

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings

/**
 * Сбор данных о телефоне: заряд, сеть, память, место, приложения. Только чтение, ничего не меняется.
 * Заряд, сеть и память не требуют разрешений. Приложения по времени и размеру требуют «Доступ к истории использования».
 */
object DeviceStatus {

    fun battery(context: Context): BatteryInfo {
        val app = context.applicationContext
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when {
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
            else -> null
        }
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> null
        }
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val minutes = if (Build.VERSION.SDK_INT >= 28) {
            bm?.computeChargeTimeRemaining()?.takeIf { it > 0 }?.let { (it / 60000).toInt() }
        } else null
        // Одни телефоны отдают микроамперы, другие миллиамперы
        val current = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }
            ?.let { if (Math.abs(it) > 20000) it / 1000 else it }
        val saver = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
        return BatteryInfo(
            percent = percent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            source = source,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
            tempC = temp,
            health = health,
            minutesToFull = if (status == BatteryManager.BATTERY_STATUS_CHARGING) minutes else null,
            currentMa = current,
            powerSave = saver
        )
    }

    @Suppress("DEPRECATION")
    fun network(context: Context): NetworkInfo {
        val app = context.applicationContext
        val airplane = try { Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (e: Exception) { false }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkInfo(false, null, false, false, null, null, null, null, airplane)
        }
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> null
        }
        var level: Int? = null
        var link: Int? = null
        if (type == "wifi") {
            try {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                if (info != null) {
                    level = if (Build.VERSION.SDK_INT >= 30) wm.calculateSignalLevel(info.rssi)
                    else WifiManager.calculateSignalLevel(info.rssi, 5) - 1
                    link = info.linkSpeed.takeIf { it > 0 }
                }
            } catch (e: Exception) {
                // без данных о Wi-Fi: расскажем только общее
            }
        }
        return NetworkInfo(
            connected = true,
            type = type,
            internetWorks = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            downMbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000 },
            upMbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000 },
            wifiLevel = level,
            wifiLinkMbps = link,
            airplane = airplane
        )
    }

    fun memory(context: Context): MemoryInfoData {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemoryInfoData(info.totalMem, info.availMem, info.lowMemory)
    }

    fun storage(): StorageInfoData {
        val stat = StatFs(Environment.getDataDirectory().path)
        return StorageInfoData(stat.blockCountLong * stat.blockSizeLong, stat.availableBytes)
    }

    // ---------------------------------------------------------------- приложения (нужен доступ к истории использования)

    fun hasUsageAccess(context: Context): Boolean {
        val app = context.applicationContext
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Приложения с иконкой в меню (то, что пользователь видит и ставил сам). */
    private fun launchable(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
    }

    /** Время в приложениях за последние часы, в минутах. */
    fun usage(context: Context, hours: Int = 24): List<AppStat> {
        val app = context.applicationContext
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(end - hours * 3_600_000L, end)
        val names = launchable(app).toMap()
        return stats.values
            .filter { it.totalTimeInForeground > 60_000 && it.packageName in names }
            .map { AppStat(names.getValue(it.packageName), it.packageName, minutes = (it.totalTimeInForeground / 60_000).toInt()) }
    }

    /** Сколько места занимает каждое приложение: программа, данные и кэш. */
    fun appSizes(context: Context): List<AppStat> {
        val app = context.applicationContext
        val ssm = app.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val user = Process.myUserHandle()
        val result = ArrayList<AppStat>()
        for ((pkg, label) in launchable(app)) {
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                result.add(AppStat(label, pkg, bytes = s.appBytes + s.dataBytes + s.cacheBytes))
            } catch (e: Exception) {
                // у этого приложения размер недоступен
            }
        }
        return result
    }
}
