package com.example.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.Currency
import java.util.Locale

/**
 * Самонастройка: язык, страна, валюта, единицы, 12/24 часа, модель телефона и то, что он умеет.
 * Всё берётся с устройства, поэтому приложение одинаково работает у любого пользователя.
 */
object DeviceInfo {

    fun promptContext(context: Context): PromptContext {
        val app = context.applicationContext
        val locale = Locale.getDefault()
        val country = locale.displayCountry.takeIf { it.isNotBlank() }
        val currency = try {
            Currency.getInstance(locale).let { "${it.currencyCode} (${it.symbol})" }
        } catch (e: Exception) {
            null
        }
        val pm = app.packageManager
        val available = ArrayList<String>()
        val missing = ArrayList<String>()
        fun cap(ok: Boolean, yes: String, no: String? = null) { if (ok) available.add(yes) else if (no != null) missing.add(no) }

        cap(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH), "фонарик")
        cap(pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY), "звонки")
        cap(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH), "Bluetooth")
        cap(pm.hasSystemFeature(PackageManager.FEATURE_WIFI), "Wi-Fi")
        cap(pm.hasSystemFeature(PackageManager.FEATURE_NFC), "NFC")
        cap(AssistantAccessibilityService.instance != null, "переключение плиток шторки",
            "переключение плиток шторки (нужно включить службу «Assistant» в Специальных возможностях)")
        val files = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        cap(files, "файлы и музыка", "доступ к файлам (разрешение «Все файлы»)")
        val calls = ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        cap(calls && contacts, "звонки по имени из контактов", "звонки по имени (нет разрешения на контакты или звонки)")
        val calendar = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        cap(calendar, "календарь", "календарь (нет разрешения)")

        return PromptContext(
            userName = UserProfile.name(app),
            city = UserProfile.city(app)?.name,
            country = country,
            language = locale.displayLanguage.takeIf { it.isNotBlank() } ?: "русский",
            currency = currency,
            metric = locale.country !in setOf("US", "LR", "MM"),
            hours24 = DateFormat.is24HourFormat(app),
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            available = available,
            missing = missing
        )
    }
}
