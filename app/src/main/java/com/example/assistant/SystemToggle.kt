package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Переключатели телефона (Wi-Fi, мобильный интернет, Bluetooth, режим полёта, геолокация, «Не беспокоить», NFC…)
 * по цепочке способов, от надёжного к запасному:
 *  1) Shizuku — команда системы напрямую, с проверкой, что телефон и правда переключился;
 *  2) сами (геолокация — если выдано разрешение защищённых настроек, Wi-Fi на Android 9 и старше);
 *  3) нажатие плитки в шторке через службу специальных возможностей (если служба выключилась после обновления,
 *     Shizuku включит её снова);
 *  4) если ничего не вышло — открываем нужный экран настроек и честно говорим, почему не получилось.
 * Каждый шаг пишется в «Журнал слушания» (тип «переключатель»), чтобы было видно, что именно не сработало.
 */
object SystemToggle {

    private fun log(context: Context, text: String) = ListenLog.add(context, "переключатель", text)

    private fun title(key: String?, fallback: String): String = when (key) {
        "wifi" -> "Wi-Fi"; "bluetooth" -> "Bluetooth"; "mobile_data" -> "Мобильный интернет"; "airplane" -> "Режим полёта"
        "location" -> "Геолокация"; "dnd" -> "«Не беспокоить»"; "nfc" -> "NFC"; "hotspot" -> "Точка доступа"
        "battery_saver" -> "Экономия заряда"; "dark_theme" -> "Тёмная тема"; else -> "«$fallback»"
    }

    private fun done(name: String, on: Boolean) = "$name: ${if (on) "включено" else "выключено"}"

    suspend fun set(context: Context, setting: String, want: Boolean?): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val key = QuickSettings.resolve(setting)
        val name = title(key, setting)
        val before = SystemState.isOn(app, key)
        val target = want ?: before?.not()
        log(app, "просьба: $name → ${target?.let { if (it) "вкл" else "выкл" } ?: "переключить"}, сейчас ${before?.let { if (it) "вкл" else "выкл" } ?: "неизвестно"}")
        if (target != null && before == target) return@withContext "$name: уже ${if (target) "включено" else "выключено"}"
        val reasons = ArrayList<String>()

        // 1. Shizuku
        val shizuku = ShizukuShell.state(app)
        if (key != null && target != null && ShizukuPower.toggleCommands(key, target) != null) {
            when (shizuku) {
                ShizukuState.READY -> {
                    for (c in ShizukuPower.toggleCommands(key, target).orEmpty()) {
                        val r = Shell.control(app, c)
                        log(app, "Shizuku: $c → ${if (r.ok) "ок" else "ошибка"} ${r.output.take(120)}")
                        if (!r.ok) { reasons += "консоль: ${r.output.take(60).ifBlank { "ошибка" }}"; continue }
                        if (key == "airplane" && c.startsWith("settings")) Shell.control(app, "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $target")
                        if (waitFor(app, key, target)) return@withContext done(name, target)
                        reasons += "консоль выполнила «$c», но телефон не переключился"
                    }
                }
                ShizukuState.NO_PERMISSION -> {
                    withContext(Dispatchers.Main) { try { rikka.shizuku.Shizuku.requestPermission(4711) } catch (_: Throwable) {} }
                    reasons += "Shizuku не дал разрешение — подтверди запрос на экране"
                }
                else -> reasons += "Shizuku: ${shizuku.text.lowercase()}"
            }
        }

        // 2. Сами, без консоли
        if (target != null) direct(app, key, target)?.let { ok ->
            log(app, "напрямую: ${if (ok) "ок" else "не вышло"}")
            if (ok && waitFor(app, key, target)) return@withContext done(name, target)
        }

        // 3. Плитка в шторке
        var service = AssistantAccessibilityService.instance
        if ((service == null || !service.hasWindowAccess()) && shizuku == ShizukuState.READY) {
            log(app, "служба специальных возможностей выключена — включаю через Shizuku")
            ShizukuPower.enableAccessibility(app, restart = service != null)
            for (i in 0 until 10) { delay(300); service = AssistantAccessibilityService.instance; if (service?.hasWindowAccess() == true) break }
        }
        if (service != null && service.hasWindowAccess()) {
            val result = withContext(Dispatchers.Main) { service.quickSetting(setting, target) }
            log(app, "шторка: $result")
            val failed = result.startsWith("Не ") || result.contains("не переключился")
            if (!failed) return@withContext result
            reasons += result.trimEnd('.')
        } else reasons += "служба специальных возможностей «Assistant» выключена"

        // 4. Экран настроек
        openSettings(app, key)
        log(app, "не вышло: ${reasons.joinToString("; ")}")
        "Не получилось переключить $name сам (${reasons.take(2).joinToString("; ")}). Открыл настройки — нажми переключатель. " +
            "Чтобы работало голосом: Настройки ассистента → «Выдать все разрешения через Shizuku», потом «Проверить переключатели»."
    }

    /** Ждём, пока система переключится (Wi-Fi и Bluetooth включаются за 1–3 с). Неизвестное состояние считаем успехом. */
    private suspend fun waitFor(context: Context, key: String?, target: Boolean): Boolean {
        for (i in 0 until 12) {
            val s = SystemState.isOn(context, key) ?: return true
            if (s == target) return true
            delay(350)
        }
        return false
    }

    /** То, что приложение может переключить само. null — этим способом нельзя. */
    @Suppress("DEPRECATION")
    private fun direct(context: Context, key: String?, on: Boolean): Boolean? = try {
        when (key) {
            "location" -> if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, if (on) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF) else null
            "wifi" -> if (Build.VERSION.SDK_INT < 29) (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.setWifiEnabled(on) else null
            "dnd" -> (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.let { nm ->
                if (!nm.isNotificationPolicyAccessGranted) null
                else { nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL); true }
            }
            else -> null
        }
    } catch (e: Exception) { false }

    private fun openSettings(context: Context, key: String?) {
        val action = when (key) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "mobile_data" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "nfc" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            "dnd" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "dark_theme" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        PhoneActions.launch(context, Intent(action))
    }

    /** Проверка без переключения: что из цепочки готово. Для кнопки «Проверить переключатели». */
    fun diagnose(context: Context): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        val st = ShizukuShell.state(app)
        sb.append("1. Shizuku: ").append(st.text).append('\n')
        if (st == ShizukuState.READY) {
            val r = Shell.control(app, "settings get global mobile_data")
            sb.append("   проверочная команда: ").append(if (r.ok) "работает (mobile_data=${r.output.trim()})" else "ОШИБКА ${r.output.take(100)}").append('\n')
        }
        val wss = app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        sb.append("2. Защищённые настройки: ").append(if (wss) "разрешено" else "нет (выдаётся через Shizuku)").append('\n')
        val s = AssistantAccessibilityService.instance
        sb.append("3. Служба специальных возможностей: ").append(
            when {
                s == null -> "ВЫКЛЮЧЕНА — включи «Assistant» в специальных возможностях"
                !s.hasWindowAccess() -> "включена без доступа к окнам — выключи и включи заново"
                else -> "работает"
            }
        ).append('\n')
        sb.append("4. Сейчас: ")
        sb.append(listOf("wifi" to "Wi-Fi", "mobile_data" to "данные", "bluetooth" to "Bluetooth", "airplane" to "полёт", "location" to "геолокация")
            .joinToString(", ") { (k, n) -> "$n ${SystemState.isOn(app, k)?.let { if (it) "вкл" else "выкл" } ?: "?"}" })
        sb.append("\n\nЧто делать: если пункт 1 не «Работает» — открой Shizuku, нажми «Запустить» и разреши Assistant; " +
            "потом «Выдать все разрешения через Shizuku». Подробности каждой попытки — в «Журнале слушания».")
        return sb.toString()
    }
}
