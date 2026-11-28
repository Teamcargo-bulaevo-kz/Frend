package com.example.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Что Shizuku умеет сделать за пользователя: выдать этому приложению все разрешения одной кнопкой
 * и переключать Wi-Fi, Bluetooth, данные, режим полёта, геолокацию и «Не беспокоить» напрямую,
 * без нажатий на плитки шторки. Только для этого приложения и только из белого списка [ShellPolicy.isControl].
 */
object ShizukuPower {

    data class Step(val title: String, val ok: Boolean, val note: String = "")

    /** Обычные разрешения из манифеста, которые выдаются командой pm grant. */
    private fun runtimePermissions(): List<String> = buildList {
        add("android.permission.RECORD_AUDIO")
        add("android.permission.READ_CONTACTS")
        add("android.permission.CALL_PHONE")
        add("android.permission.READ_PHONE_STATE")
        add("android.permission.READ_CALENDAR")
        add("android.permission.WRITE_CALENDAR")
        if (Build.VERSION.SDK_INT >= 33) {
            add("android.permission.POST_NOTIFICATIONS")
            add("android.permission.READ_MEDIA_AUDIO")
            add("android.permission.READ_MEDIA_IMAGES")
            add("android.permission.READ_MEDIA_VIDEO")
        } else add("android.permission.READ_EXTERNAL_STORAGE")
        if (Build.VERSION.SDK_INT >= 31) add("android.permission.BLUETOOTH_CONNECT")
        // Разрешение разработчика: приложение само меняет защищённые настройки (специальные возможности, геолокация)
        add("android.permission.WRITE_SECURE_SETTINGS")
    }

    /** Специальные доступы (appops): все файлы, история использования, системные настройки, поверх окон. */
    private fun appOps(): List<Pair<String, String>> = buildList {
        if (Build.VERSION.SDK_INT >= 30) add("MANAGE_EXTERNAL_STORAGE" to "Доступ ко всем файлам")
        add("GET_USAGE_STATS" to "История использования приложений")
        add("WRITE_SETTINGS" to "Изменение системных настроек")
        add("SYSTEM_ALERT_WINDOW" to "Поверх других окон")
    }

    private fun granted(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    /** Выдаёт всё сразу. Блокирующий (несколько секунд). Возвращает отчёт по шагам. */
    fun grantAll(context: Context): List<Step> {
        val app = context.applicationContext
        val pkg = app.packageName
        if (!Shell.available(app)) return listOf(Step("Shizuku", false, ShizukuShell.state(app).text))
        val steps = ArrayList<Step>()
        for (perm in runtimePermissions()) {
            val short = perm.substringAfterLast('.')
            if (granted(app, perm)) { steps += Step(short, true, "уже было"); continue }
            val r = Shell.control(app, "pm grant $pkg $perm")
            steps += Step(short, r.ok || granted(app, perm), r.output.take(80).trim())
        }
        for ((op, title) in appOps()) {
            val r = Shell.control(app, "appops set $pkg $op allow")
            steps += Step(title, r.ok, r.output.take(80).trim())
        }
        steps += enableAccessibility(app, restart = true)
        val listener = "$pkg/${NotificationReader::class.java.name}"
        steps += Step("Чтение уведомлений", Shell.control(app, "cmd notification allow_listener $listener").ok)
        steps += Step("Без ограничений батареи (слово активации не засыпает)", Shell.control(app, "cmd deviceidle whitelist +$pkg").ok)
        return steps
    }

    /** Включает службу специальных возможностей, не трогая уже включённые чужие службы. */
    fun enableAccessibility(context: Context, restart: Boolean = false): Step {
        val pkg = context.packageName
        val ours = "$pkg/${AssistantAccessibilityService::class.java.name}"
        var current = Shell.control(context, "settings get secure enabled_accessibility_services").output.trim()
            .takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val listed = current.split(':').any { it.equals(ours, ignoreCase = true) }
        val running = AssistantAccessibilityService.instance?.hasWindowAccess() == true
        if (listed && running && !restart) return Step("Специальные возможности", true, "уже включены")
        if (listed) {
            // В списке, но служба не работает (или после обновления без новых прав): выключаем и включаем заново
            current = current.split(':').filter { it.isNotBlank() && !it.equals(ours, ignoreCase = true) }.joinToString(":")
            Shell.control(context, "settings put secure enabled_accessibility_services ${current.ifEmpty { "null" }}")
            Thread.sleep(500)
        }
        val value = if (current.isEmpty()) ours else "$current:$ours"
        val put = Shell.control(context, "settings put secure enabled_accessibility_services $value")
        Shell.control(context, "settings put secure accessibility_enabled 1")
        return Step("Специальные возможности", put.ok, put.output.take(80).trim())
    }

    fun report(steps: List<Step>): String {
        val ok = steps.count { it.ok }
        return "Выдано $ok из ${steps.size}:\n" + steps.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.title + if (!it.ok && it.note.isNotBlank()) " — ${it.note}" else "" }
    }

    // ---------------------------------------------------------------- переключатели напрямую

    /** Команды консоли для переключателя: по очереди, первая сработавшая. null — консолью не переключается. */
    fun toggleCommands(key: String, on: Boolean): List<String>? = when (key) {
        "wifi" -> listOf("cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}", "svc wifi ${if (on) "enable" else "disable"}")
        "bluetooth" -> listOf("cmd bluetooth_manager ${if (on) "enable" else "disable"}", "svc bluetooth ${if (on) "enable" else "disable"}")
        "mobile_data" -> listOf("svc data ${if (on) "enable" else "disable"}", "cmd phone data ${if (on) "enable" else "disable"}")
        "nfc" -> listOf("svc nfc ${if (on) "enable" else "disable"}")
        "airplane" -> listOf(
            "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}",
            "settings put global airplane_mode_on ${if (on) 1 else 0}"
        )
        "location" -> listOf("cmd location set-location-enabled $on", "settings put secure location_mode ${if (on) 3 else 0}")
        "dnd" -> listOf("cmd notification set_dnd ${if (on) "priority" else "off"}")
        else -> null
    }
}
