package com.example.assistant

import android.content.Context
import java.io.File

/**
 * Папка для таблицы «Музыка.xlsx» и файлов для другого ИИ.
 * Если ассистенту выдан доступ ко всем файлам, это общая папка Assistant (таблица переживает переустановку,
 * как было раньше во Frend). Без доступа — личная папка приложения; наружу файлы уходят кнопкой «Поделиться».
 */
object MusicFiles {
    private const val ROOT_NAME = "Музыка"
    private const val OUTBOX_NAME = "Для ИИ"

    @Volatile private var base: File? = null

    /** Вызывается один раз при старте процесса ([AssistantApp]). */
    fun init(context: Context) {
        base = context.applicationContext.let { it.getExternalFilesDir(null) ?: it.filesDir }
    }

    private fun shared(): Boolean = try { SharedFolder.hasAccess() } catch (_: Exception) { false }

    fun root(): File = if (shared()) SharedFolder.root() else File(checkNotNull(base) { "MusicFiles.init не вызван" }, ROOT_NAME)

    fun outbox(): File = if (shared()) SharedFolder.outbox() else File(root(), OUTBOX_NAME)

    /** Создаёт папки. false, если не получилось. */
    fun ensure(): Boolean = try {
        if (shared()) SharedFolder.ensure() else {
            outbox().mkdirs()
            root().isDirectory && outbox().isDirectory
        }
    } catch (e: Exception) {
        false
    }
}
