package com.example.assistant

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проводник: папки и файлы телефона и память ассистента. Только просмотр — ничего не удаляет.
 * Быстрые места: папка Assistant (база знаний, навыки, входящие), Загрузки, Документы, Фото, Музыка, весь телефон
 * и внутренние данные приложения (базы заметок и выученного, настройки) — их можно посмотреть прямо здесь:
 * текст и таблицы баз данных открываются встроенным просмотром, остальное — в подходящем приложении.
 */
class ExplorerActivity : KitActivity() {

    override val screenTitle = "Проводник"

    private val storage: File get() = Environment.getExternalStorageDirectory()
    private var dir: File? = null
    private var rootOfWalk: File? = null
    private val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))

    override fun build() {
        val d = dir
        if (d == null) { places(); return }
        setScreenTitle(d.name.ifEmpty { "Телефон" })
        content.addView(label(shortPath(d), 12.5f, dim = true).apply { setPadding(dp(2), 0, 0, dp(6)) })
        ui.launch {
            val items = withContext(Dispatchers.IO) {
                (d.listFiles()?.toList().orEmpty()).filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            }
            if (items.isEmpty()) empty(if (d.canRead()) "Папка пуста" else "Нет доступа к папке. Разреши «Доступ ко всем файлам».")
            val shown = items.take(500)
            shown.forEach { f ->
                if (f.isDirectory) {
                    val n = f.list()?.count { !it.startsWith(".") } ?: 0
                    row("📁", f.name, "$n ${FileSort.plural(n, "объект", "объекта", "объектов")}", onLong = { info(f) }) { go(f) }
                } else {
                    row(icon(f), f.name, "${DeviceReport.bytes(f.length())} · ${fmt.format(Date(f.lastModified()))}", onLong = { fileMenu(f) }) { openFile(f) }
                }
            }
            if (items.size > shown.size) content.addView(label("…и ещё ${items.size - shown.size}", 13f, dim = true))
        }
    }

    private fun places() {
        setScreenTitle("Проводник")
        val all = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        if (!all) {
            row("🔓", "Разрешить доступ ко всем файлам", "Без него видны не все папки. Только просмотр, ничего не удаляется.") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }
        section("Память ассистента")
        row("📝", "Заметки и память", "Заметки, факты базы знаний — с поиском и правкой") { startActivity(Intent(this, NotesActivity::class.java)) }
        row("🧠", "Папка Assistant", "База знаний, навыки, входящие и исходящие файлы, мини-приложения") { go(SharedFolder.root().apply { mkdirs() }) }
        row("🗄", "Внутренние данные приложения", "Базы заметок и выученных команд, настройки — только просмотр") { go(filesDir.parentFile ?: filesDir) }
        section("Телефон")
        row("⬇️", "Загрузки", null) { go(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
        row("📄", "Документы", null) { go(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)) }
        row("🖼", "Фото и видео (DCIM)", null) { go(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)) }
        row("📸", "Скриншоты и картинки", null) { go(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)) }
        row("🎵", "Музыка", null) { go(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)) }
        row("💬", "WhatsApp (медиа)", null) {
            val candidates = listOf(File(storage, "Android/media/com.whatsapp/WhatsApp/Media"), File(storage, "WhatsApp/Media"))
            go(candidates.firstOrNull { it.exists() } ?: candidates[0])
        }
        row("📱", "Весь телефон", shortPath(storage)) { go(storage) }
    }

    private fun go(f: File) {
        if (dir == null) rootOfWalk = f
        dir = f
        rebuild()
    }

    override fun onBack() {
        val d = dir ?: return finish()
        val parent = d.parentFile
        dir = if (d == rootOfWalk || parent == null || !parent.canRead()) null else parent
        if (d == rootOfWalk) rootOfWalk = null
        rebuild()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dir != null) onBack() else super.onBackPressed()
    }

    private fun shortPath(f: File) = f.path.replace(storage.path, "Телефон").replace(filesDir.parentFile?.path ?: "\u0000", "Внутренние данные")

    private fun icon(f: File): String = when (f.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> "🖼"
        "mp4", "mkv", "mov", "3gp", "webm", "avi" -> "🎬"
        "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac" -> "🎵"
        "pdf" -> "📕"
        "doc", "docx", "odt", "rtf" -> "📘"
        "xls", "xlsx", "csv", "ods", "tsv" -> "📗"
        "zip", "rar", "7z", "tar", "gz" -> "🗜"
        "apk" -> "📦"
        "db", "sqlite", "sqlite3" -> "🗄"
        "json", "xml", "txt", "md", "log", "html", "kt", "py", "js" -> "📄"
        else -> "📎"
    }

    private val textExt = setOf("txt", "md", "log", "json", "xml", "tsv", "csv", "html", "htm", "kt", "py", "js", "css", "ini", "conf", "yaml", "yml", "properties")
    private val dbExt = setOf("db", "sqlite", "sqlite3")

    private fun internal(f: File) = f.path.startsWith(filesDir.parentFile?.path ?: "\u0000")

    private fun openFile(f: File) {
        val ext = f.extension.lowercase()
        when {
            ext in dbExt -> viewDb(f)
            ext in textExt || (internal(f) && f.length() < 2_000_000) -> viewText(f)
            else -> openExternal(f)
        }
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun openExternal(f: File) {
        if (internal(f)) { viewText(f); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Нет приложения, чтобы открыть «${f.name}»") }
    }

    private fun share(f: File) {
        if (internal(f)) { toast("Внутренние файлы только для просмотра"); return }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться"))
        } catch (e: Exception) { toast("Не получилось: ${e.message}") }
    }

    private fun fileMenu(f: File) {
        val actions = arrayOf("Открыть", "Открыть в другом приложении", "Поделиться", "Спросить ассистента про файл", "Скопировать путь", "Свойства")
        AlertDialog.Builder(this).setTitle(f.name).setItems(actions) { _, i ->
            when (i) {
                0 -> openFile(f)
                1 -> openExternal(f)
                2 -> share(f)
                3 -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_PREFILL, "Про файл ${f.path}: ")); finish() }
                4 -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("путь", f.path)); toast("Путь скопирован") }
                5 -> info(f)
            }
        }.show()
    }

    private fun info(f: File) {
        ui.launch {
            val size = withContext(Dispatchers.IO) { if (f.isDirectory) f.walkTopDown().maxDepth(6).take(20000).filter { it.isFile }.sumOf { it.length() } else f.length() }
            AlertDialog.Builder(this@ExplorerActivity).setTitle(f.name)
                .setMessage("Путь: ${f.path}\nРазмер: ${DeviceReport.bytes(size)}\nИзменён: ${fmt.format(Date(f.lastModified()))}" +
                    if (f.isDirectory) "\nОбъектов: ${f.list()?.size ?: 0}" else "\nТип: ${mime(f)}")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text; textSize = 12.5f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(color(R.color.chat_text)); setTextIsSelectable(true); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(HorizontalScrollView(this@ExplorerActivity).apply { addView(tv) }) })
            .setPositiveButton("Закрыть", null).show()
    }

    private fun viewText(f: File) {
        ui.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val bytes = f.inputStream().use { s -> s.readNBytesCompat(300_000) }
                    val s = String(bytes, Charsets.UTF_8)
                    if (s.count { it == '�' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') } > s.length / 10) "Это не текстовый файл (${DeviceReport.bytes(f.length())})."
                    else s + if (f.length() > bytes.size) "\n\n… показано ${DeviceReport.bytes(bytes.size.toLong())} из ${DeviceReport.bytes(f.length())}" else ""
                } catch (e: Exception) { "Не получилось прочитать: ${e.message}" }
            }
            showText(f.name, text)
        }
    }

    /** Таблицы базы: названия, число строк и первые строки каждой. Открывается только для чтения. */
    private fun viewDb(f: File) {
        ui.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')", null)
                            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
                        buildString {
                            for (t in tables) {
                                val count = db.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                                append("■ $t — $count ${FileSort.plural(count.toInt(), "строка", "строки", "строк")}\n")
                                db.rawQuery("SELECT * FROM \"$t\" ORDER BY rowid DESC LIMIT 40", null).use { c ->
                                    append(c.columnNames.joinToString(" | ")).append('\n')
                                    while (c.moveToNext()) {
                                        append((0 until c.columnCount).joinToString(" | ") { i ->
                                            if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) "[данные]" else c.getString(i).orEmpty().replace('\n', ' ').take(80)
                                        }).append('\n')
                                    }
                                }
                                append('\n')
                            }
                            if (tables.isEmpty()) append("Таблиц нет.")
                        }
                    }
                } catch (e: Exception) { "Не получилось открыть базу: ${e.message}" }
            }
            showText(f.name, text)
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var left = n
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size, left))
        if (r < 0) break
        out.write(buf, 0, r); left -= r
    }
    return out.toByteArray()
}
