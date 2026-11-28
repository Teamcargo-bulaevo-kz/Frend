package com.example.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Что ассистенту разрешено видеть. Всё, что читает чужие данные, по умолчанию выключено. */
object Privacy {
    private const val PREFS = "assistant_prefs"
    private const val KEY_SCREEN = "allow_screen_reading"

    fun screenAllowed(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCREEN, false)

    fun setScreenAllowed(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCREEN, value).apply()
    }
}

/** Ответы «как там телефон»: заряд, сеть, память, место, приложения. Только чтение. */
object DeviceActions {

    private fun open(context: Context, action: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // экран настроек недоступен — пользователь откроет вручную
        }
    }

    private const val NEED_USAGE =
        "Для этого нужен доступ к истории использования. Открыл настройки: найди «Assistant» и включи. Потом повтори вопрос."

    /** section: battery, network, memory, storage, memory_all, apps_storage, apps_usage, processes, all. */
    fun report(context: Context, section: String): String {
        val text = reportText(context, section)
        // Картинка дублирует главное (заряд, память, место, приложения) — только если ответ не об ошибке доступа
        if (text.startsWith("Для этого нужен") || text.startsWith("Не получилось")) return text
        return text + DeviceVisuals.forSection(context, section.trim().lowercase())
    }

    private fun reportText(context: Context, section: String): String {
        val app = context.applicationContext
        return try {
            when (section.trim().lowercase()) {
                "battery" -> DeviceReport.battery(DeviceStatus.battery(app))
                "network" -> DeviceReport.network(DeviceStatus.network(app))
                "memory" -> DeviceReport.memory(DeviceStatus.memory(app))
                "storage" -> DeviceReport.storage(DeviceStatus.storage())
                "memory_all" -> DeviceReport.memory(DeviceStatus.memory(app)) + "\n" + DeviceReport.storage(DeviceStatus.storage())
                "apps_storage" -> {
                    if (!DeviceStatus.hasUsageAccess(app)) { open(app, Settings.ACTION_USAGE_ACCESS_SETTINGS); NEED_USAGE }
                    else DeviceReport.appsByStorage(DeviceStatus.appSizes(app))
                }
                "apps_usage" -> {
                    if (!DeviceStatus.hasUsageAccess(app)) { open(app, Settings.ACTION_USAGE_ACCESS_SETTINGS); NEED_USAGE }
                    else DeviceReport.appsByUsage(DeviceStatus.usage(app))
                }
                "processes" -> processes(app)
                "battery_apps" -> batteryApps(app)
                "battery_health" -> batteryHealth(app)
                "toggles" -> SystemToggle.diagnose(app)
                "device" -> deviceLine(app)
                "files" -> FileOrganizer.storageReport()
                "big_files" -> FileOrganizer.biggestFiles()
                "duplicates" -> FileOrganizer.duplicates()
                "full" -> listOf(
                    deviceLine(app),
                    DeviceReport.battery(DeviceStatus.battery(app)),
                    batteryHealth(app).takeIf { Shell.available(app) }.orEmpty(),
                    DeviceReport.network(DeviceStatus.network(app)),
                    DeviceReport.memory(DeviceStatus.memory(app)),
                    DeviceReport.storage(DeviceStatus.storage()),
                    if (DeviceStatus.hasUsageAccess(app)) DeviceReport.appsByUsage(DeviceStatus.usage(app)).lines().take(4).joinToString("\n") else ""
                ).filter { it.isNotBlank() }.joinToString("\n")
                else -> listOf(
                    DeviceReport.battery(DeviceStatus.battery(app)),
                    DeviceReport.network(DeviceStatus.network(app)),
                    DeviceReport.memory(DeviceStatus.memory(app)),
                    DeviceReport.storage(DeviceStatus.storage())
                ).joinToString("\n")
            }
        } catch (e: Exception) {
            "Не получилось узнать: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Модель, версия Android, сколько работает без перезагрузки. */
    fun deviceLine(context: Context): String {
        val up = android.os.SystemClock.elapsedRealtime() / 60_000
        val upText = if (up >= 60 * 24) "${up / (60 * 24)} дн ${up / 60 % 24} ч" else if (up >= 60) "${up / 60} ч ${up % 60} мин" else "$up мин"
        val patch = if (android.os.Build.VERSION.SDK_INT >= 23) android.os.Build.VERSION.SECURITY_PATCH else ""
        return "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}" +
            (if (patch.isNotBlank()) " (обновление безопасности $patch)" else "") + ". Работает без перезагрузки $upText."
    }

    /** Износ батареи и число циклов зарядки (через Shizuku, если телефон отдаёт эти данные). */
    fun batteryHealth(context: Context): String {
        if (!Shell.available(context)) return "Износ батареи и циклы зарядки покажу после подключения Shizuku."
        fun read(name: String): Long? = listOf("battery", "bms").firstNotNullOfOrNull { dir ->
            Shell.run("cat /sys/class/power_supply/$dir/$name", 4000).takeIf { it.ok }?.output?.trim()?.toLongOrNull()
        }
        return BatteryWear.describe(read("cycle_count"), read("charge_full"), read("charge_full_design"))
    }

    /** Память по процессам. Нужна консоль (Shizuku); без неё честно говорим об этом и даём то, что можно. */
    private fun processes(context: Context): String {
        if (!Shell.available(context)) {
            return DeviceReport.memory(DeviceStatus.memory(context)) +
                "\nСписок процессов и память по каждому приложению появятся после подключения Shizuku. " +
                "Пока могу показать приложения по времени использования и по занятому месту."
        }
        val result = Shell.run("dumpsys meminfo")
        if (!result.ok) return "Консоль не ответила: ${result.output.take(120)}"
        val pm = context.packageManager
        val names = HashMap<String, String>()
        val list = ProcessReport.parseMeminfo(result.output)
        for (p in list.take(30)) {
            val pkg = p.name.substringBefore(':')
            if (pkg !in names) names[pkg] = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        }
        return ProcessReport.format(list, names)
    }

    private fun label(context: Context, uid: Int): String {
        if (uid < 10_000) return when (uid) { 0 -> "Ядро системы"; 1000 -> "Система Android"; 1001 -> "Связь (телефон)"; 1010 -> "Wi-Fi"; 1013 -> "Медиа"; else -> "Система ($uid)" }
        val pm = context.packageManager
        val pkgs = pm.getPackagesForUid(uid).orEmpty()
        return pkgs.firstNotNullOfOrNull { p -> try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { null } }
            ?: pkgs.firstOrNull() ?: "uid $uid"
    }

    /** Расход заряда по приложениям: точно — через Shizuku (статистика батареи), иначе — время использования. */
    private fun batteryApps(context: Context): String {
        if (Shell.available(context)) {
            var items = BatteryUsage.parse(Shell.run("dumpsys batterystats --checkin", 30_000).output)
            if (items.isEmpty()) items = BatteryUsage.parse(Shell.run("dumpsys batterystats", 30_000).output)
            if (items.isNotEmpty()) {
                val named = items.map { label(context, it.uid) to it.mah }.groupBy({ it.first }, { it.second }).map { it.key to it.value.sum() }
                    .sortedByDescending { it.second }
                return BatteryUsage.format(named) +
                    VisualSpec.block(VisualSpec.barJson("Расход заряда, мА·ч", named.take(10).map { it.first to Math.round(it.second * 10) / 10.0 }, "мА·ч"))
            }
        }
        val usage = if (DeviceStatus.hasUsageAccess(context)) DeviceStatus.usage(context) else emptyList()
        val note = if (Shell.available(context)) "Система не отдала статистику батареи." else "Точный расход заряда по приложениям покажу через Shizuku."
        if (usage.isEmpty()) return "$note Сейчас могу показать заряд: «сколько заряда»."
        return "$note Пока — кто дольше всего работал на экране за сутки (это главный расход):\n" +
            DeviceReport.appsByUsage(usage)
    }

    /** Текст с экрана (только если пользователь разрешил и включена служба специальных возможностей). */
    fun readScreen(context: Context): String {
        if (!Privacy.screenAllowed(context)) {
            return "Чтение экрана выключено. Включить: меню → «Что ассистент может видеть»."
        }
        val service = AssistantAccessibilityService.instance
            ?: return "Нужно включить службу специальных возможностей: Настройки → Специальные возможности → Assistant."
        return service.readScreen() ?: "Не получилось прочитать экран: открытое окно не отдаёт текст."
    }
}
