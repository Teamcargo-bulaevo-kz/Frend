package com.example.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Короткий журнал воспроизведения: старт трека, конец, ошибки, потеря звукового фокуса, остановки службы.
 * Нужен, чтобы понять, почему музыка сама включилась или выключилась. Хранит последние строки в файле приложения.
 */
object PlayLog {
    private const val MAX_LINES = 120
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    fun init(context: Context) { file = File(context.applicationContext.filesDir, "play_log.txt") }

    @Synchronized
    fun add(message: String) {
        val f = file ?: return
        try {
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${fmt.format(Date())}  $message"
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun read(): String = try { file?.takeIf { it.exists() }?.readText().orEmpty() } catch (_: Exception) { "" }

    @Synchronized
    fun clear() { try { file?.delete() } catch (_: Exception) {} }
}
