package com.example.assistant

import java.util.Calendar

/** Файл для планировщика сортировки (без Android, чтобы тестировать). */
data class FileEntry(val name: String, val size: Long, val modifiedMs: Long)

/** Куда переложить файл: подпапка внутри той же папки. */
data class FileMove(val name: String, val folder: String)

/**
 * Как раскладывать файлы по папкам. Только перемещение внутри выбранной папки: ничего не удаляется
 * и не перезаписывается. Чистый Kotlin, покрыт тестами.
 */
object FileSort {

    enum class Mode { TYPE, DATE, EXTENSION }

    /** Категория по расширению → название папки. */
    val categories: List<Pair<String, Set<String>>> = listOf(
        "Документы" to setOf("doc", "docx", "odt", "rtf", "txt", "md", "pages"),
        "PDF" to setOf("pdf"),
        "Таблицы" to setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers"),
        "Презентации" to setOf("ppt", "pptx", "odp", "key"),
        "Картинки" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "raw", "dng"),
        "Видео" to setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "wmv", "flv"),
        "Музыка" to setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid"),
        "Архивы" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz"),
        "Установщики" to setOf("apk", "xapk", "apks", "apkm"),
        "Книги" to setOf("epub", "fb2", "mobi", "djvu", "azw3"),
        "Контакты и календари" to setOf("vcf", "ics"),
        "Код и данные" to setOf("json", "xml", "html", "htm", "js", "kt", "java", "py", "sql", "db", "sqlite", "log", "yaml", "yml")
    )

    const val OTHER = "Прочее"

    /** Недокачанное и служебное не трогаем. */
    private val skipExtensions = setOf("crdownload", "part", "partial", "tmp", "download", "opdownload", "temp")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun categoryOf(name: String): String {
        val ext = extension(name)
        return categories.firstOrNull { ext in it.second }?.first ?: OTHER
    }

    /** Все папки, которые создаёт сортировка по типам (их саму сортировка не трогает). */
    val folderNames: Set<String> = categories.map { it.first }.toSet() + OTHER

    private val months = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")

    fun monthFolder(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(java.util.Locale.ROOT, "%04d-%02d %s", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, months[c.get(Calendar.MONTH)])
    }

    /**
     * План: какие файлы куда. Пропускаются скрытые, недокачанные и совсем свежие (меньше 2 минут — ещё могут писаться).
     */
    fun plan(files: List<FileEntry>, mode: Mode, now: Long = System.currentTimeMillis()): List<FileMove> =
        files.filter { f ->
            !f.name.startsWith(".") && extension(f.name) !in skipExtensions && now - f.modifiedMs > 120_000L
        }.map { f ->
            FileMove(f.name, when (mode) {
                Mode.TYPE -> categoryOf(f.name)
                Mode.DATE -> monthFolder(f.modifiedMs)
                Mode.EXTENSION -> extension(f.name).uppercase().ifEmpty { "Без расширения" }
            })
        }

    /** «Разложу 57 файлов: Картинки 30, Документы 12, …» */
    fun summary(moves: List<FileMove>): String {
        if (moves.isEmpty()) return "Раскладывать нечего: файлы уже разложены или папка пуста."
        val groups = moves.groupingBy { it.folder }.eachCount().entries.sortedByDescending { it.value }
        return "${moves.size} ${plural(moves.size, "файл", "файла", "файлов")}: " +
            groups.take(8).joinToString(", ") { "${it.key} ${it.value}" } + if (groups.size > 8) " и ещё ${groups.size - 8} папок" else ""
    }

    /** Свободное имя, если в папке уже есть файл с таким: «отчёт (2).pdf». */
    fun freeName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (exists("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}
