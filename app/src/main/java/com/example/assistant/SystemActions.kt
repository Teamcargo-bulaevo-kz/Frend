package com.example.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Системные настройки, которые приложению можно менять напрямую (после выдачи разрешения «Изменение системных настроек»). */
object SystemActions {

    private fun needWriteSettings(context: Context): String? {
        if (Settings.System.canWrite(context)) return null
        PhoneActions.launch(
            context,
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        return "Нужно разрешение «Изменение системных настроек». Открыл экран: включи переключатель для Assistant и повтори команду."
    }

    /** level — процент 0–100; change — up, down, max, min или auto. */
    fun brightness(context: Context, level: Int?, change: String?): String {
        needWriteSettings(context)?.let { return it }
        val resolver = context.contentResolver
        if (change.equals("auto", ignoreCase = true)) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Включил автоматическую яркость"
        }
        val current = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        } catch (e: Settings.SettingNotFoundException) {
            50
        }
        val target = when {
            level != null -> level
            change.equals("up", ignoreCase = true) -> current + 20
            change.equals("down", ignoreCase = true) -> current - 20
            change.equals("max", ignoreCase = true) -> 100
            change.equals("min", ignoreCase = true) -> 1
            else -> return "Скажи, какую яркость поставить: например «яркость 50» или «сделай ярче»."
        }.coerceIn(1, 100)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (target * 255 / 100).coerceIn(1, 255))
        return "Яркость $target%"
    }

    /** Автоповорот экрана. on: true включить, false выключить, null переключить. */
    fun rotation(context: Context, on: Boolean?): String {
        needWriteSettings(context)?.let { return it }
        val resolver = context.contentResolver
        val now = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        val target = on ?: !now
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0)
        return "Автоповорот ${if (target) "включён" else "выключен"}"
    }
}
