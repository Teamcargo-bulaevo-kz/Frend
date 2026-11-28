package com.example.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * «Отчёт для разработчика»: всё, что нужно, чтобы разобрать проблему без телефона в руках —
 * версия, Shizuku, службы, разрешения, переключатели, последние события слушания и действий.
 * Копируется в буфер и открывается «Поделиться». Личные данные (чаты, контакты, файлы) не входят.
 */
object DevReport {

    fun build(context: Context): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        sb.append("Assistant ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.BUILD_TAG.trim()).append(")\n")
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n")
        sb.append("— Shizuku —\n").append(try { ShizukuShell.selfTest(app) } catch (e: Exception) { "ошибка: ${e.message}" }).append("\n\n")
        sb.append("— Переключатели —\n").append(try { SystemToggle.diagnose(app) } catch (e: Exception) { "ошибка: ${e.message}" }).append("\n\n")
        sb.append("— Службы —\n")
        sb.append("Слово активации: ").append(if (WakeWordService.enabled(app)) "вкл" else "выкл").append('\n')
        sb.append("Уведомления (чтение): ").append(if (NotificationReader.enabled(app)) "вкл" else "выкл").append('\n')
        sb.append("Все файлы: ").append(if (SharedFolder.hasAccess()) "да" else "нет").append('\n')
        sb.append("Живой экран: ").append(if (ScreenAssist.live(app)) "вкл" else "выкл").append('\n')
        sb.append("Ключ модели: ").append(if (BuildConfig.GROQ_API_KEY.isNotBlank()) "есть" else "НЕТ").append("\n\n")
        sb.append("— Журнал слушания и действий (последнее) —\n").append(try { ListenLog.recent(app, 80) } catch (e: Exception) { "" })
        val crash = java.io.File(app.filesDir, "last_crash.txt")
        if (crash.exists()) sb.append("\n\n— Последнее падение —\n").append(crash.readText().take(3000))
        return sb.toString()
    }

    fun share(context: Context): String {
        val text = build(context)
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Отчёт Assistant", text))
                app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить отчёт")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        return "Собрал отчёт для разработчика (${text.length} символов): он скопирован, и открыто «Поделиться». Переписки, контакты и файлы туда не входят."
    }
}
