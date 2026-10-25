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
