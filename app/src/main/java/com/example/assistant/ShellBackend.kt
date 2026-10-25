package com.example.assistant

import android.content.Context

/**
 * Заготовка под Shizuku: «консоль» с правами оболочки. Пока Shizuku не подключён, работает заглушка NoShell,
 * а всё, что требует консоли (процессы, память по приложениям), честно отвечает «нужен Shizuku».
 *
 * Как подключить Shizuku позже (подробно — docs/TZ_DEVICE_INFO.md, раздел «Shizuku»):
 *  1. Добавить зависимости dev.rikka.shizuku:api и provider, провайдер в манифест.
 *  2. Написать ShizukuShell : ShellBackend (проверка Shizuku.pingBinder и разрешения, запуск команд).
 *  3. В MainActivity/Application: Shell.backend = ShizukuShell, если Shizuku доступен.
 * Больше нигде код менять не нужно: DeviceActions уже вызывает Shell.
 */
data class ShellResult(val ok: Boolean, val output: String)

interface ShellBackend {
    val name: String
    fun available(context: Context): Boolean
    fun run(command: String, timeoutMs: Long = 8000): ShellResult
}

object NoShell : ShellBackend {
    override val name = "нет"
    override fun available(context: Context) = false
    override fun run(command: String, timeoutMs: Long) = ShellResult(false, "Консоль не подключена")
}

object Shell {
    @Volatile var backend: ShellBackend = NoShell

    fun available(context: Context) = backend.available(context)
    fun run(command: String) = backend.run(command)
}

/** Память одного процесса из `dumpsys meminfo` (раздел «Total PSS by process»). */
data class ProcMem(val name: String, val pid: Int, val kb: Long)

object ProcessReport {

    private val line = Regex("""^\s*([\d.,]+)K: (.+?) \(pid (\d+)""")

    /** Разбирает вывод `dumpsys meminfo` и возвращает процессы, самые тяжёлые первыми. */
    fun parseMeminfo(text: String): List<ProcMem> {
        val section = text.substringAfter("Total PSS by process:", "").substringBefore("Total PSS by OOM adjustment:")
        val source = if (section.isNotBlank()) section else text
        return source.lineSequence().mapNotNull { l ->
            val m = line.find(l) ?: return@mapNotNull null
            val kb = m.groupValues[1].replace(",", "").replace(".", "").toLongOrNull() ?: return@mapNotNull null
            ProcMem(m.groupValues[2].trim(), m.groupValues[3].toInt(), kb)
        }.sortedByDescending { it.kb }.toList()
    }

    /** «1. Telegram — 410 МБ». names — подписи приложений по имени пакета. */
    fun format(list: List<ProcMem>, names: Map<String, String> = emptyMap(), limit: Int = 8): String {
        if (list.isEmpty()) return "Не получилось разобрать список процессов."
        return "Больше всего оперативной памяти занимают:\n" + list.take(limit).mapIndexed { i, p ->
            val pkg = p.name.substringBefore(':')
            "${i + 1}. ${names[pkg] ?: p.name} — ${DeviceReport.bytes(p.kb * 1024)}"
        }.joinToString("\n")
    }
}
