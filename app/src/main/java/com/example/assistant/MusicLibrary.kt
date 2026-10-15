package com.example.assistant

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class MusicTrack(val id: Long, val title: String, val artist: String) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/** Очередь воспроизведения: ассистент кладёт её сюда, а MusicService (тот же процесс) забирает. */
object MusicQueue {
    @Volatile var tracks: List<MusicTrack> = emptyList()
}

/** Поиск музыки на телефоне через MediaStore. Блокирующий: вызывать с IO-потока. */
object MusicLibrary {

    /**
     * Ищет треки по названию, исполнителю или альбому. Пустой запрос — вся музыка.
     * Точные совпадения названия идут первыми.
     */
    fun search(context: Context, query: String, limit: Int = 50): List<MusicTrack> {
        val text = query.trim()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val base = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val selection: String
        val args: Array<String>?
        if (text.isEmpty()) {
            selection = base
            args = null
        } else {
            selection = "$base AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
                "${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)"
            val like = "%$text%"
            args = arrayOf(like, like, like)
        }

        val found = mutableListOf<MusicTrack>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (cursor.moveToNext()) {
                    found += MusicTrack(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "Без названия",
                        artist = cursor.getString(artistCol) ?: ""
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }

        val lower = text.lowercase(Locale.getDefault())
        val ranked = if (lower.isEmpty()) {
            found
        } else {
            found.sortedBy { track ->
                val title = track.title.lowercase(Locale.getDefault())
                when {
                    title == lower -> 0
                    title.startsWith(lower) -> 1
                    title.contains(lower) -> 2
                    else -> 3
                }
            }
        }
        return ranked.take(limit)
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
