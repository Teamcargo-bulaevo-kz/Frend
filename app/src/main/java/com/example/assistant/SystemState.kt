package com.example.assistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Настоящее состояние переключателей, прямо из системы (а не по подписи на плитке).
 * Нужно, чтобы точно знать, включено ли что-то, и проверять, что команда сработала.
 * null — система не даёт это узнать (например, нет разрешения для Bluetooth).
 */
object SystemState {

    fun isOn(context: Context, key: String?): Boolean? = try {
        val app = context.applicationContext
        when (key) {
            "wifi" -> (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            "bluetooth" -> bluetooth(app)
            "airplane" -> Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "location" -> (app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLocationEnabled
                else Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            }
            "battery_saver" -> (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun bluetooth(app: Context): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        return (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
    }
}
