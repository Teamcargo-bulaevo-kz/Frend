package com.example.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общая открытая папка «Assistant» в памяти телефона (видна в любом файловом менеджере и по USB).
 *   Входящие  — сюда кладёшь файлы и базы, ассистент их читает, но не меняет;
 *   Исходящие — сюда ассистент сохраняет то, что создаёт сам.
 * Все функции блокирующие: вызывать с IO-потока.
 */
object SharedFolder {

    const val NAME = "Assistant"
    const val INBOX = "Входящие"
    const val OUTBOX = "Исходящие"

    private const val MAX_READ_CHARS = 6000
    private const val MAX_WRITE_CHARS = 200_000
    private const val MAX_SQL_ROWS = 30
    private const val MAX_SQL_CHARS = 4000

    private val textExtensions = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "sql", "ini", "yml", "yaml",
        "kt", "java", "py", "js", "css", "conf", "properties", "rtf", "srt", "vcf", "ics", "gpx"
    )
    private val databaseExtensions = setOf("db", "sqlite", "sqlite3", "db3")

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun root(): File = File(Environment.getExternalStorageDirectory(), NAME)
    fun inbox(): File = File(root(), INBOX)
    fun outbox(): File = File(root(), OUTBOX)

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private const val NO_ACCESS =
        "Нет доступа ко всем файлам. Включи его: Настройки → Приложения → Assistant → Разрешения → Все файлы (или открой приложение, оно предложит само)."

    /** Создаёт папку и подпапки. false — не получилось (обычно нет доступа ко всем файлам). */
    fun ensure(): Boolean {
        if (!hasAccess()) return false
        return try {
            inbox().mkdirs()
            outbox().mkdirs()
            val readme = File(root(), "Как пользоваться.txt")
            if (!readme.exists()) {
                readme.writeText(
                    "Папка для обмена файлами с ассистентом.\n\n" +
                        "$INBOX — клади сюда файлы и базы данных (txt, csv, json, sqlite и т.д.).\n" +
                        "Потом скажи ассистенту, что с ними сделать: «прочитай файл clients.csv», " +
                        "«найди в файле Иванов», «сколько строк в таблице orders».\n" +
                        "Ассистент ничего не меняет и не удаляет во $INBOX.\n\n" +
                        "$OUTBOX — сюда ассистент сохраняет файлы, которые создаёт сам.\n"
                )
            }
            inbox().isDirectory && outbox().isDirectory
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- новые файлы

    /** Файлы во Входящих, о которых ассистент ещё не говорил. Запоминает их, повторно не вернёт. */
    fun takeNewFiles(context: Context): List<File> {
        if (!ensure()) return emptyList()
        val prefs = context.getSharedPreferences("assistant_prefs", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("shared_seen", emptySet()).orEmpty().toMutableSet()
        val files = inbox().listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        val fresh = files.filter { fileKey(it) !in seen }
        if (fresh.isNotEmpty()) {
            seen.addAll(fresh.map { fileKey(it) })
            prefs.edit().putStringSet("shared_seen", seen).apply()
        }
        return fresh.sortedBy { it.lastModified() }
    }

    private fun fileKey(f: File) = "${f.name}|${f.lastModified()}|${f.length()}"

    // ---------------------------------------------------------------- список

    fun list(context: Context, folder: String?): String {
        if (!ensure()) return NO_ACCESS
        val dir = when {
            folder.isNullOrBlank() -> inbox()
            folder.trim().lowercase() in setOf("исходящие", "outbox", "out") -> outbox()
            folder.trim().lowercase() in setOf("входящие", "inbox", "in") -> inbox()
            folder.trim().lowercase() in setOf("все", "всё", "root", "assistant") -> root()
            else -> safeFile(folder) ?: return "Папка «$folder» вне общей папки."
        }
        val items = dir.listFiles()?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        val title = dir.relativeTo(root()).path.ifEmpty { NAME }
        if (items.isEmpty()) return "Папка «$title» пуста. Положи файл в ${root().path}/$INBOX и скажи, что с ним сделать."
        val lines = items.take(40).map {
            if (it.isDirectory) "📁 ${it.name}"
            else "${it.name} — ${Formatter.formatShortFileSize(context, it.length())}, ${dateFormat.format(Date(it.lastModified()))}"
        }
        val more = if (items.size > 40) "\n… и ещё ${items.size - 40}" else ""
        return "Папка «$title» (${items.size}):\n${lines.joinToString("\n")}$more"
    }

    // ---------------------------------------------------------------- чтение

    /** Читает файл. С query показывает только подходящие строки. Базы данных описывает по таблицам. */
    fun read(context: Context, name: String, query: String?): String {
        if (!ensure()) return NO_ACCESS
        val file = find(name) ?: return notFound(name)
        val ext = file.extension.lowercase()
        return when {
            ext in databaseExtensions -> describeDatabase(file)
            ext in textExtensions || looksLikeText(file) -> readText(file, query)
            else -> "«${file.name}» (${Formatter.formatShortFileSize(context, file.length())}): этот формат читать не умею. " +
                "Сохрани его как csv, txt или json, и я разберу."
        }
    }

    private fun readText(file: File, query: String?): String {
        val size = file.length()
        if (query.isNullOrBlank()) {
            val text = file.bufferedReader().use { r ->
                val buf = CharArray(MAX_READ_CHARS)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
            val tail = if (size > text.toByteArray().size) "\n… (показано начало, всего ${size / 1024 + 1} КБ; чтобы найти нужное, назови слово для поиска)" else ""
            return "«${file.name}»:\n$text$tail"
        }
        val needle = TextMatch.normalize(query)
        val hits = ArrayList<String>()
        var total = 0
        var header: String? = null
        file.useLines { lines ->
            for ((i, line) in lines.withIndex()) {
                if (i == 0) header = line
                if (TextMatch.normalize(line).contains(needle)) {
                    total++
                    if (hits.size < 40) hits.add("${i + 1}: ${line.take(300)}")
                }
            }
        }
        if (total == 0) return "В «${file.name}» не нашёл «$query»."
        val head = header?.takeIf { file.extension.lowercase() in setOf("csv", "tsv") }?.let { "Заголовок: ${it.take(300)}\n" }.orEmpty()
        val more = if (total > hits.size) "\n… и ещё ${total - hits.size}" else ""
        return "«${file.name}», найдено строк: $total\n$head${hits.joinToString("\n")}$more".take(MAX_READ_CHARS)
    }

    private fun looksLikeText(file: File): Boolean {
        if (file.length() == 0L) return true
        val head = ByteArray(512)
        val n = file.inputStream().use { it.read(head) }
        return (0 until n).none { head[it].toInt() == 0 }
    }

    // ---------------------------------------------------------------- базы данных

    private fun describeDatabase(file: File): String = withDatabase(file) { db ->
        val tables = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name", null)
            .use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
        if (tables.isEmpty()) return@withDatabase "База «${file.name}» пустая."
        val parts = tables.take(30).map { table ->
            val cols = ArrayList<String>()
            db.rawQuery("PRAGMA table_info(\"${table.replace("\"", "")}\")", null).use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            val count = try {
                db.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"", "")}\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
            } catch (e: Exception) { -1 }
            "• $table ($count строк): ${cols.joinToString(", ")}"
        }
        "База «${file.name}»:\n${parts.joinToString("\n")}"
    }

    /** Только чтение: база открывается без права записи, разрешён один запрос SELECT. */
    fun sql(name: String, query: String): String {
        if (!ensure()) return NO_ACCESS
        val file = find(name) ?: return notFound(name)
        if (file.extension.lowercase() !in databaseExtensions) return "«${file.name}» не база данных (sqlite)."
        val q = query.trim().trimEnd(';').trim()
        if (!Regex("^(select|with)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) || q.contains(';')) {
            return "Разрешён только один запрос SELECT: базы я только читаю."
        }
        return withDatabase(file) { db ->
            db.rawQuery(q, null).use { c ->
                val names = c.columnNames.joinToString(" | ")
                val rows = ArrayList<String>()
                var count = 0
                while (c.moveToNext()) {
                    count++
                    if (rows.size < MAX_SQL_ROWS) {
                        rows.add((0 until c.columnCount).joinToString(" | ") { i ->
                            val cell = try { c.getString(i) } catch (e: Exception) { "<данные>" }
                            (cell ?: "NULL").take(80)
                        })
                    }
                }
                if (count == 0) return@use "Пусто (0 строк)."
                val more = if (count > rows.size) "\n… всего строк: $count" else ""
                "$names\n${rows.joinToString("\n")}$more".take(MAX_SQL_CHARS)
            }
        }
    }

    private fun withDatabase(file: File, block: (SQLiteDatabase) -> String): String =
        try {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try { block(db) } finally { db.close() }
        } catch (e: Exception) {
            "Не смог прочитать базу «${file.name}»: ${e.message?.take(150)}"
        }

    // ---------------------------------------------------------------- запись

    /** Сохраняет текст в Исходящие. Входящие не трогает. */
    fun write(name: String, content: String, append: Boolean): String {
        if (!ensure()) return NO_ACCESS
        val clean = name.trim().substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\:*?\"<>|]"), "_")
        if (clean.isEmpty() || clean.startsWith(".")) return "Нужно нормальное имя файла."
        if (content.length > MAX_WRITE_CHARS) return "Слишком большой текст для одного файла."
        val file = File(outbox(), clean)
        return try {
            if (append) file.appendText(content) else file.writeText(content)
            "Сохранил в $NAME/$OUTBOX/${file.name}"
        } catch (e: Exception) {
            "Не получилось сохранить: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- поиск файла по имени

    /** Файл внутри общей папки; путь наружу (../) не пропускаем. */
    private fun safeFile(relative: String): File? {
        val base = root().canonicalFile
        val f = File(base, relative.trim().trimStart('/')).canonicalFile
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun find(name: String): File? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        listOf(File(inbox(), wanted), File(outbox(), wanted)).forEach { f ->
            safeFile(f.relativeTo(root()).path)?.takeIf { it.isFile }?.let { return it }
        }
        safeFile(wanted)?.takeIf { it.isFile }?.let { return it }

        val all = root().walkTopDown().maxDepth(3).filter { it.isFile && !it.name.startsWith(".") }.toList()
        val lower = wanted.lowercase()
        all.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
        all.filter { it.name.lowercase().contains(lower) }.maxByOrNull { it.lastModified() }?.let { return it }
        val key = TextMatch.key(wanted)
        return all.filter { TextMatch.key(it.nameWithoutExtension).let { k -> k.isNotEmpty() && (k.contains(key) || key.contains(k)) } }
            .maxByOrNull { it.lastModified() }
    }

    private fun notFound(name: String): String {
        val names = (inbox().listFiles().orEmpty().toList() + outbox().listFiles().orEmpty().toList())
            .filter { it.isFile }.take(10).joinToString(", ") { it.name }
        return "Файл «$name» не нашёл. " + if (names.isEmpty()) "В папке пока пусто." else "Есть: $names"
    }
}
