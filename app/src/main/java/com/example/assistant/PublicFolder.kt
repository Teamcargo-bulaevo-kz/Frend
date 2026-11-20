package com.example.assistant

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Публичная папка «Документы/Harmonia» в памяти телефона: туда кладётся таблица всех песен с характеристиками
 * (готовыми или пустыми). Её видно в любом файловом менеджере, можно отправить ИИ и потом импортировать обратно.
 * Доступ ко всей памяти не нужен: файл создаётся через MediaStore (Android 10 и новее).
 */
object PublicFolder {
    private const val REL = "Documents/Harmonia/"
    private const val PREFS = "public_folder"

    /** Как путь называется для пользователя. */
    const val DISPLAY_PATH = "Документы / Harmonia"

    private const val README = """Папка Harmonia. Здесь лежит таблица «Музыка.xlsx» со всеми песнями и их характеристиками.

Как пользоваться:
1. В приложении: Настройки, Таблица для ИИ, «Обновить таблицу».
2. Отправь файл «Музыка.xlsx» ИИ (ChatGPT, Claude и др.) и попроси заполнить пустые характеристики, не меняя названия колонок.
3. Сохрани исправленный файл на телефон.
4. В приложении: Настройки, Таблица для ИИ, «Импортировать таблицу» и выбери исправленный файл.
Приложение применит характеристики и заменит «Музыка.xlsx» в этой папке обновлённой таблицей.
"""

    fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Подсказка системному выбору файла открыться сразу в нашей папке. */
    fun initialUri(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Harmonia")

    fun lastMirrorMs(context: Context): Long = prefs(context).getLong("last", 0L)
    fun lastRows(context: Context): Int = prefs(context).getInt("rows", 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Копирует таблицу в публичную папку (создаёт или перезаписывает). false, если нельзя. Блокирующий. */
    fun mirrorTable(context: Context, table: File, rows: Int): Boolean {
        if (!supported() || !table.exists()) return false
        val ok = write(context, table.name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", table.readBytes())
        if (ok) {
            prefs(context).edit().putLong("last", System.currentTimeMillis()).putInt("rows", rows).apply()
            if (!prefs(context).getBoolean("readme", false)) {
                if (write(context, "Прочитай.txt", "text/plain", README.toByteArray())) prefs(context).edit().putBoolean("readme", true).apply()
            }
        }
        return ok
    }

    private fun write(context: Context, name: String, mime: String, bytes: ByteArray): Boolean = try {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = find(context, collection, name) ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL)
        })
        if (uri == null) false
        else resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun find(context: Context, collection: Uri, name: String): Uri? {
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, REL), null
        )?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }
}
