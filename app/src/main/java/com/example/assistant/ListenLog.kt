package com.example.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал слушания: что услышал распознаватель и что с этим сделал ассистент. Нужен, чтобы по фактам искать ошибки
 * распознавания слова активации и команд. Хранится только на телефоне, не больше [MAX_LINES] строк.
 * События слова активации и команд пишутся всегда; всё услышанное — только если включено в настройках.
 */
object ListenLog {

    private const val MAX_LINES = 150
    private const val KEY_ALL = "listen_log_all"
    private val lock = Any()

    @Volatile private var appContext: Context? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, "listen_log.txt")

    fun allEnabled(context: Context) =
        context.applicationContext.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALL, false)

    fun setAllEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALL, value).apply()
    }

    fun add(context: Context, kind: String, text: String) {
        val app = context.applicationContext
        appContext = app
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        val line = "$time  $kind: ${text.replace('\n', ' ').take(200)}"
        synchronized(lock) {
            try {
                val f = file(app)
                val lines = if (f.exists()) f.readLines().takeLast(MAX_LINES - 1) else emptyList()
                f.writeText((lines + line).joinToString("\n") + "\n")
            } catch (_: Exception) {
                // журнал не должен ломать работу ассистента
            }
        }
    }

    /** Последние [count] строк, новые сверху. */
    fun recent(context: Context, count: Int = 60): String = synchronized(lock) {
        try {
            val f = file(context)
            if (!f.exists()) "Пока пусто." else f.readLines().takeLast(count).reversed().joinToString("\n").ifBlank { "Пока пусто." }
        } catch (_: Exception) {
            "Не удалось прочитать журнал."
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { try { file(context).delete() } catch (_: Exception) {} }
    }
}
