package com.example.assistant

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val isMusic: Boolean = true
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/** Очередь воспроизведения: ассистент кладёт её сюда, а MusicService (тот же процесс) забирает. */
object MusicQueue {
    @Volatile var tracks: List<MusicTrack> = emptyList()
}

/** Вся музыка телефона, загруженная в память. Блокирующий: вызывать с IO-потока. */
object MusicLibrary {

    private const val CACHE_MS = 5 * 60 * 1000L

    @Volatile private var index: MusicIndex? = null
    @Volatile private var loadedAt = 0L

    /** Индекс музыки. Обновляется не чаще раза в 5 минут (или сразу, если force). */
    @Synchronized
    fun index(context: Context, force: Boolean = false): MusicIndex {
        val cached = index
        if (!force && cached != null && System.currentTimeMillis() - loadedAt < CACHE_MS) return cached
        val fresh = MusicIndex(load(context))
        index = fresh
        loadedAt = System.currentTimeMillis()
        return fresh
    }

    fun invalidate() {
        loadedAt = 0L
    }

    fun search(context: Context, query: String, limit: Int = 50): List<MusicTrack> =
        index(context).search(query, limit)

    private fun load(context: Context): List<MusicTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.IS_MUSIC
        )
        // Музыка, а также любое длинное аудио (альбомы без тегов, скачанное из мессенджеров).
        // Рингтоны, будильники и уведомления не берём.
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} >= 120000) " +
            "AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0 " +
            "AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 " +
            "AND ${MediaStore.Audio.Media.IS_ALARM} = 0"
        val result = mutableListOf<MusicTrack>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
                "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val musicCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
                while (cursor.moveToNext()) {
                    val artist = cursor.getString(artistCol).orEmpty()
                    result += MusicTrack(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "Без названия",
                        artist = if (artist == "<unknown>") "" else artist,
                        album = cursor.getString(albumCol).orEmpty(),
                        isMusic = cursor.getInt(musicCol) != 0
                    )
                }
            }
        } catch (e: SecurityException) {
            // нет доступа к аудио — вернём пустой список
        }
        return result
    }
}

/**
 * Находит аудиофайлы, которые система ещё не добавила в медиатеку (скачанные, из мессенджеров,
 * с флешки), и просит систему их проиндексировать. Нужен доступ «Все файлы».
 */
object MusicIndexer {

    private val extensions = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "mka")
    private const val PREFS = "music_indexer"
    private const val KEY_LAST_SCAN = "last_scan"
    private const val MIN_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val MAX_VISITED = 60000
    private const val MAX_NEW_FILES = 5000

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Блокирующий. Возвращает число файлов, отправленных на индексацию. */
    fun scanNewFiles(context: Context, force: Boolean = false): Int {
        if (!hasAllFilesAccess()) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_SCAN, 0L) < MIN_INTERVAL_MS) return 0

        val known = knownPaths(context)
        val root = Environment.getExternalStorageDirectory()
        val androidDir = File(root, "Android")
        val fresh = mutableListOf<String>()
        var visited = 0
        val tree = root.walkTopDown()
            .maxDepth(8)
            .onEnter { dir -> !dir.name.startsWith(".") && dir != androidDir }
        for (file in tree) {
            visited++
            if (visited > MAX_VISITED || fresh.size >= MAX_NEW_FILES) break
            if (file.isFile && file.extension.lowercase(Locale.ROOT) in extensions && file.path !in known) {
                fresh += file.path
            }
        }
        prefs.edit().putLong(KEY_LAST_SCAN, now).apply()
        if (fresh.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, fresh.toTypedArray(), null) { _, _ ->
                MusicLibrary.invalidate()
            }
        }
        return fresh.size
    }

    @Suppress("DEPRECATION")
    private fun knownPaths(context: Context): Set<String> {
        val paths = HashSet<String>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA), null, null, null
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) cursor.getString(col)?.let { paths += it }
            }
        } catch (e: Exception) {
            // не получилось прочитать — индексируем всё найденное
        }
        return paths
    }
}

/** Плейлисты ассистента. Хранятся в файле playlists.json, базу данных не трогаем. */
object PlaylistStore {

    private const val FILE_NAME = "playlists.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): LinkedHashMap<String, MutableList<MusicTrack>> {
        val result = LinkedHashMap<String, MutableList<MusicTrack>>()
        val f = file(context)
        if (!f.exists()) return result
        try {
            val root = JSONObject(f.readText())
            for (name in root.keys()) {
                val array = root.getJSONArray(name)
                val tracks = mutableListOf<MusicTrack>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    tracks += MusicTrack(
                        id = obj.getLong("id"),
                        title = obj.optString("title"),
                        artist = obj.optString("artist")
                    )
                }
                result[name] = tracks
            }
        } catch (e: Exception) {
            // повреждённый файл — начинаем с пустого списка
        }
        return result
    }

    fun save(context: Context, playlists: Map<String, List<MusicTrack>>) {
        val root = JSONObject()
        for ((name, tracks) in playlists) {
            val array = JSONArray()
            for (t in tracks) {
                array.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                })
            }
            root.put(name, array)
        }
        file(context).writeText(root.toString())
    }

    /** Находит название плейлиста без учёта регистра. */
    fun findName(playlists: Map<String, *>, name: String): String? =
        playlists.keys.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
}
