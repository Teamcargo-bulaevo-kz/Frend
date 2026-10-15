package com.example.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Доступ к фото, видео и файлам на телефоне. Все функции здесь блокирующие: вызывать с IO-потока. */
object MediaActions {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- фото и видео

    /**
     * Ищет фото или видео в галерее. Без запроса показывает самые свежие.
     * open=true — сразу открывает первый найденный файл.
     */
    fun findMedia(context: Context, kind: String, query: String?, limit: Int, open: Boolean): String {
        val video = kind.equals("video", ignoreCase = true)
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (!hasPermission(context, permission)) {
            return "Нет доступа к ${if (video) "видео" else "фото"}. " +
                "Разреши его в настройках приложения Assistant (Разрешения → Фото и видео)"
        }

        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val text = query?.trim().orEmpty()
        val selection = if (text.isNotEmpty()) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val args = if (text.isNotEmpty()) arrayOf("%$text%") else null
        val max = limit.coerceIn(1, 20)

        val lines = mutableListOf<String>()
        var firstUri: Uri? = null
        var firstMime: String? = null
        try {
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext() && lines.size < max) {
                    val id = cursor.getLong(idCol)
                    if (firstUri == null) {
                        firstUri = Uri.withAppendedPath(collection, id.toString())
                        firstMime = cursor.getString(mimeCol)
                    }
                    lines += "• ${cursor.getString(nameCol)} — " +
                        "${dateFormat.format(Date(cursor.getLong(dateCol) * 1000))}, " +
                        Formatter.formatShortFileSize(context, cursor.getLong(sizeCol))
                }
            }
        } catch (e: SecurityException) {
            return "Нет доступа к галерее: ${e.message}"
        }

        val what = if (video) "видео" else "фото"
        if (lines.isEmpty()) {
            return if (text.isNotEmpty()) "Не нашёл $what по запросу «$text»" else "В галерее нет файлов ($what)"
        }
        var result = lines.joinToString("\n")
        val uri = firstUri
        if (open && uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, firstMime ?: if (video) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            result += if (PhoneActions.launch(context, intent)) {
                "\nОткрыл первый файл"
            } else {
                "\nНе нашёл приложение, чтобы открыть файл"
            }
        }
        return result
    }

    // ---------------------------------------------------------------- файлы

    private val folderAliases = mapOf(
        "download" to "Download", "downloads" to "Download", "загрузки" to "Download", "загрузка" to "Download",
        "documents" to "Documents", "документы" to "Documents",
        "dcim" to "DCIM", "камера" to "DCIM",
        "pictures" to "Pictures", "картинки" to "Pictures", "изображения" to "Pictures",
        "movies" to "Movies", "видео" to "Movies",
        "music" to "Music", "музыка" to "Music"
    )

    private fun resolveFolder(root: File, folder: String?): File {
        val raw = folder?.trim().orEmpty()
        if (raw.isEmpty()) return root
        val direct = File(root, folderAliases[raw.lowercase(Locale.getDefault())] ?: raw)
        return if (direct.isDirectory) direct else root
    }

    private fun describe(context: Context, root: File, file: File): String {
        val relative = file.relativeTo(root).path
        val details = if (file.isDirectory) {
            "папка"
        } else {
            "${dateFormat.format(Date(file.lastModified()))}, " +
                Formatter.formatShortFileSize(context, file.length())
        }
        return "• $relative — $details"
    }

    /**
     * Ищет файлы в памяти телефона по части названия. Без запроса показывает содержимое папки.
     * Нужен доступ «Все файлы» (его выдают вручную в настройках).
     */
    fun findFiles(context: Context, query: String?, folder: String?, limit: Int, open: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            PhoneActions.launch(context, intent)
            return "Для работы с файлами нужен доступ «Все файлы». Открыл настройки: " +
                "включи переключатель для Assistant и повтори просьбу"
        }

        val root = Environment.getExternalStorageDirectory()
        val start = resolveFolder(root, folder)
        val text = query?.trim().orEmpty()
        val max = limit.coerceIn(1, 20)

        val found: List<File> = if (text.isEmpty()) {
            (start.listFiles()?.toList() ?: emptyList())
                .filter { !it.name.startsWith(".") }
                .sortedByDescending { it.lastModified() }
                .take(max)
        } else {
            val androidDir = File(root, "Android")
            val matches = mutableListOf<File>()
            var visited = 0
            val tree = start.walkTopDown()
                .maxDepth(6)
                .onEnter { dir -> !dir.name.startsWith(".") && dir != androidDir }
            for (file in tree) {
                visited++
                if (visited > 30000 || matches.size >= 300) break
                if (file != start && file.name.contains(text, ignoreCase = true)) matches += file
            }
            matches.sortedByDescending { it.lastModified() }.take(max)
        }

        if (found.isEmpty()) {
            return if (text.isEmpty()) "Папка пуста или недоступна" else "Не нашёл файлы по запросу «$text»"
        }
        var result = found.joinToString("\n") { describe(context, root, it) }

        if (open) {
            val target = found.firstOrNull { it.isFile }
            if (target != null) {
                result += openFile(context, target)
            }
        }
        return result
    }

    private fun openFile(context: Context, file: File): String {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase(Locale.getDefault())) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (PhoneActions.launch(context, intent)) {
                "\nОткрыл «${file.name}»"
            } else {
                "\nНе нашёл приложение, чтобы открыть «${file.name}»"
            }
        } catch (e: Exception) {
            "\nНе получилось открыть «${file.name}»: ${e.message}"
        }
    }
}
