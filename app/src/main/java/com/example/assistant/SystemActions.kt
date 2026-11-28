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

    /** Границы шкалы яркости этого телефона (на Xiaomi максимум бывает 2047 или 4095, а не 255). */
    private fun range(context: Context, current: Int): Pair<Int, Int> {
        fun sysInt(name: String): Int? = try {
            val id = android.content.res.Resources.getSystem().getIdentifier(name, "integer", "android")
            if (id != 0) android.content.res.Resources.getSystem().getInteger(id) else null
        } catch (e: Exception) { null }
        val pmMax = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.javaClass.getMethod("getMaximumScreenBrightnessSetting").invoke(pm) as? Int
        } catch (e: Throwable) { null }
        val max = Brightness.guessMax(pmMax ?: sysInt("config_screenBrightnessSettingMaximum"), current)
        val min = (sysInt("config_screenBrightnessSettingMinimum") ?: 1).coerceIn(0, max / 10)
        return min to max
    }

    /** level — процент 0–100 (как на ползунке); change — up, down, max, min или auto. */
    fun brightness(context: Context, level: Int?, change: String?): String {
        needWriteSettings(context)?.let { return it }
        val resolver = context.contentResolver
        if (change.equals("auto", ignoreCase = true)) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Включил автоматическую яркость"
        }
        val raw = try { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Settings.SettingNotFoundException) { 128 }
        val (min, max) = range(context, raw)
        val current = Brightness.settingToSlider(raw, min, max)
        val target = when {
            level != null -> level
            change.equals("up", ignoreCase = true) -> current + 20
            change.equals("down", ignoreCase = true) -> current - 20
            change.equals("max", ignoreCase = true) -> 100
            change.equals("min", ignoreCase = true) -> 1
            else -> return "Скажи, какую яркость поставить: например «яркость 50» или «сделай ярче»."
        }.coerceIn(1, 100)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, Brightness.sliderToSetting(target, min, max))
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
