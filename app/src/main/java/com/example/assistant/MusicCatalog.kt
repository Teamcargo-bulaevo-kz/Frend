package com.example.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Таблица Assistant/Музыка.xlsx: приложение само создаёт её и обновляет, а при переустановке или на другом
 * устройстве читает обратно. Так разметка не теряется. Таблицу можно править в Excel: изменения подхватятся.
 * Колонки совпадают с твоим файлом songs_by_mood.xlsx, плюс «Нравится», «Папки» и «Источник».
 */
object MusicCatalog {

    const val FILE_NAME = "Музыка.xlsx"
    private const val PREFS = "music_catalog"
    private const val KEY_EXPORTED = "exported_mtime"
    private const val AUTO_NOTE = "Проверить: ИИ не уверен"

    val header = listOf(
        "Исполнитель", "Название", "Альбом (папка)", "Жанр", "Настроение", "Энергичность (1-5)",
        "Ритм / темп", "Краткое описание", "Уверенность", "Пометка для ИИ", "Нравится", "Папки", "Источник"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportJob: Job? = null
    private val jobLock = Any()
    private val exportLock = Any()

    fun file(): File = File(SharedFolder.root(), FILE_NAME)

    // ---------------------------------------------------------------- подписи

    fun confidenceLabel(c: Int) = when {
        c >= 75 -> "Высокая"
        c >= 45 -> "Средняя"
        else -> "Низкая"
    }

    private fun confidenceFromLabel(text: String): Int? {
        val t = text.trim().lowercase()
        t.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        return when {
            t.startsWith("высок") -> 90
            t.startsWith("средн") -> 60
            t.startsWith("низк") -> 30
            else -> null
        }
    }

    private fun sourceLabel(s: String) = when (s) {
        TrackTags.SOURCE_USER -> "Вручную"
        TrackTags.SOURCE_WEB -> "Интернет"
        TrackTags.SOURCE_IMPORT -> "Импорт"
        else -> "ИИ"
    }

    private fun sourceFromLabel(text: String): String = when (text.trim().lowercase()) {
        "вручную", "вы", "user" -> TrackTags.SOURCE_USER
        "интернет", "web" -> TrackTags.SOURCE_WEB
        "ии", "ai" -> TrackTags.SOURCE_AI
        else -> TrackTags.SOURCE_IMPORT
    }

    private fun likedLabel(v: Int) = when {
        v > 0 -> "да"
        v < 0 -> "нет"
        else -> ""
    }

    private fun likedFromLabel(text: String): Int {
        val t = text.trim().lowercase()
        return when {
            t in setOf("да", "♥", "❤", "1", "+", "нравится", "like") -> 1
            t in setOf("нет", "-1", "-", "не нравится", "dislike") -> -1
            else -> 0
        }
    }

    // ---------------------------------------------------------------- выгрузка

    private class Row(val artist: String, val title: String, val album: String, val tags: TrackTags?)

    /** Откладывает выгрузку на несколько секунд: разметка идёт пачками, писать файл после каждой незачем. */
    fun scheduleExport(context: Context) {
        val app = context.applicationContext
        synchronized(jobLock) {
            exportJob?.cancel()
            exportJob = scope.launch {
                delay(8_000)
                try { exportNow(app) } catch (e: Exception) { /* нет доступа к папке: попробуем при следующем изменении */ }
            }
        }
    }

    /** Записывает таблицу сразу. Блокирующий. Возвращает false, если нет доступа к общей папке. */
    fun exportNow(context: Context): Boolean = synchronized(exportLock) { exportLocked(context) }

    private fun exportLocked(context: Context): Boolean {
        if (!SharedFolder.ensure()) return false
        val tags = MusicTags.all(context)
        val device = MusicLibrary.index(context).allMusic()
        val byKey = LinkedHashMap<String, Row>()
        for (t in device) byKey[MusicTags.trackKey(t)] = Row(t.artist, t.title, t.album, tags[MusicTags.trackKey(t)])
        for ((key, tg) in tags) if (key !in byKey && MusicTags.isTagged(tg)) byKey[key] = Row(tg.artist, tg.title, tg.album, tg)

        val folders = HashMap<String, MutableList<String>>()
        for ((name, list) in PlaylistStore.load(context)) {
            for (tr in list) folders.getOrPut(MusicTags.trackKey(tr)) { mutableListOf() }.add(name)
        }

        val rows = ArrayList<List<String>>()
        rows.add(header)
        val sorted = byKey.entries.sortedWith(compareBy({ it.value.artist.lowercase() }, { it.value.title.lowercase() }))
        for ((key, r) in sorted) {
            val tg = r.tags?.takeIf { MusicTags.isTagged(it) }
            val liked = r.tags?.liked ?: 0
            val folderText = folders[key]?.joinToString("; ").orEmpty()
            if (tg == null) {
                rows.add(listOf(r.artist, r.title, r.album, "", "Не определено", "", "", "", "", "", likedLabel(liked), folderText, ""))
            } else {
                val note = if (tg.note.isNotBlank()) tg.note else if (tg.needsReview) AUTO_NOTE else ""
                rows.add(listOf(
                    r.artist, r.title, r.album, tg.genreText,
                    tg.moods.joinToString(", ") { MusicTags.moodShort[it] ?: it },
                    tg.energy.toString(), tg.tempo, tg.description, confidenceLabel(tg.confidence),
                    note, likedLabel(liked), folderText, sourceLabel(tg.source)
                ))
            }
        }

        val counts = LinkedHashMap<String, Int>()
        for (r in byKey.values) {
            val tg = r.tags?.takeIf { MusicTags.isTagged(it) }
            if (tg == null) counts["Не определено"] = (counts["Не определено"] ?: 0) + 1
            else for (m in tg.moods) counts[MusicTags.moodShort[m] ?: m] = (counts[MusicTags.moodShort[m] ?: m] ?: 0) + 1
        }
        val summary = ArrayList<List<String>>()
        summary.add(listOf("Настроение", "Треков"))
        counts.forEach { (k, v) -> summary.add(listOf(k, v.toString())) }

        val target = file()
        FileConvert.writeXlsx(
            target,
            listOf(
                FileConvert.Sheet("Все песни", rows, setOf(5), listOf(26, 34, 22, 24, 22, 12, 22, 44, 12, 26, 10, 22, 10)),
                FileConvert.Sheet("Сводка", summary, setOf(1), listOf(24, 10))
            )
        )
        prefs(context).edit().putLong(KEY_EXPORTED, target.lastModified()).apply()
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- чтение

    /**
     * При запуске: если локальных тегов нет (переустановка, другое устройство) или таблицу правили снаружи,
     * читает её. Возвращает число обновлённых песен. Блокирующий.
     */
    fun syncOnStart(context: Context): Int {
        if (!SharedFolder.ensure()) return 0
        val f = file()
        if (!f.exists()) return 0
        val exported = prefs(context).getLong(KEY_EXPORTED, 0L)
        val changedOutside = f.lastModified() > exported + 2_000
        if (MusicTags.count(context) > 0 && !changedOutside) return 0
        val n = importFrom(context, f)
        prefs(context).edit().putLong(KEY_EXPORTED, f.lastModified()).apply()
        return n
    }

    /** Читает таблицу в нашем или твоём формате songs_by_mood.xlsx. Возвращает число песен, у которых теги обновились. */
    fun importFrom(context: Context, file: File): Int {
        val rows = try { FileConvert.xlsxRows(file) } catch (e: Exception) { return 0 }
        if (rows.size < 2) return 0
        val head = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String): Int = head.indexOfFirst { h -> names.any { h.contains(it) } }
        val cArtist = col("исполнитель")
        val cTitle = col("назван")
        if (cArtist < 0 || cTitle < 0) return 0
        val cAlbum = col("альбом")
        val cGenre = col("жанр")
        val cMood = col("настроен")
        val cEnergy = col("энергичн")
        val cTempo = col("ритм", "темп")
        val cDesc = col("описан")
        val cConf = col("уверенн")
        val cNote = col("пометк")
        val cLiked = col("нравит")
        val cFolders = col("папк")
        val cSource = col("источник")

        fun cell(row: List<String>, c: Int) = if (c >= 0) row.getOrNull(c)?.trim().orEmpty() else ""

        val local = MusicTags.all(context)
        val updates = LinkedHashMap<String, TrackTags>()
        val folderRows = ArrayList<Pair<String, List<String>>>()
        for (row in rows.drop(1)) {
            val artist = cell(row, cArtist)
            val title = cell(row, cTitle)
            if (title.isEmpty()) continue
            val key = MusicTags.trackKey(artist, title)
            val liked = likedFromLabel(cell(row, cLiked))
            val folders = cell(row, cFolders).split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            if (folders.isNotEmpty()) folderRows.add(key to folders)

            val moods = MusicTags.moodsFromText(cell(row, cMood))
            val old = local[key]
            if (moods.isEmpty()) {
                // «Не определено»: разметки в файле нет, но лайк сохраняем
                if (liked != 0 && (old == null || old.liked != liked)) {
                    updates[key] = (old ?: TrackTags("other", "calm", 3, confidence = 0, artist = artist, title = title, album = cell(row, cAlbum))).copy(liked = liked)
                }
                continue
            }
            val source = sourceFromLabel(cell(row, cSource))
            val fileEdit = source == TrackTags.SOURCE_USER
            // Локальная ручная правка важнее строки из файла, кроме строк, помеченных «Вручную»
            if (old != null && old.edited && !fileEdit) {
                if (old.liked != liked && liked != 0) updates[key] = old.copy(liked = liked)
                continue
            }
            val genreLabel = cell(row, cGenre)
            val genreKey = if (genreLabel.isEmpty()) "other" else
                (MusicTags.genres.entries.firstOrNull { it.value.equals(genreLabel, true) }?.key ?: MusicTags.genreKeyFromLabel(genreLabel))
            val fileConf = confidenceFromLabel(cell(row, cConf))
            val conf = when {
                fileEdit -> 100
                fileConf == null -> 70
                old != null && confidenceLabel(old.confidence) == confidenceLabel(fileConf) -> old.confidence
                else -> fileConf
            }
            val noteText = cell(row, cNote)
            val tags = TrackTags(
                genre = genreKey, mood = moods.first(), energy = cell(row, cEnergy).toDoubleOrNull()?.toInt()?.coerceIn(1, 5) ?: 3,
                moods = moods, genreLabel = if (genreLabel.equals(MusicTags.genres[genreKey], true)) "" else genreLabel,
                tempo = cell(row, cTempo), description = cell(row, cDesc), confidence = conf, source = source,
                liked = if (liked != 0) liked else (old?.liked ?: 0), edited = fileEdit || (old?.edited ?: false),
                webTried = old?.webTried ?: false, note = if (noteText == AUTO_NOTE || noteText.startsWith("ИИ: уточни")) "" else noteText,
                artist = artist, title = title, album = cell(row, cAlbum)
            )
            if (old != tags) updates[key] = tags
        }
        if (updates.isNotEmpty()) MusicTags.putAll(context, updates, export = false)
        if (folderRows.isNotEmpty()) restoreFolders(context, folderRows)
        return updates.size
    }

    /** Папки из таблицы возвращаются в плейлисты: песни ищутся на этом устройстве по исполнителю и названию. */
    private fun restoreFolders(context: Context, rows: List<Pair<String, List<String>>>) {
        val device = MusicLibrary.index(context).allMusic().associateBy { MusicTags.trackKey(it) }
        val lists = PlaylistStore.load(context)
        var changed = false
        for ((key, names) in rows) {
            val track = device[key] ?: continue
            for (name in names) {
                val existing = PlaylistStore.findName(lists, name) ?: name
                val list = lists.getOrPut(existing) { mutableListOf() }
                if (list.none { MusicTags.trackKey(it) == key }) {
                    list.add(track)
                    changed = true
                }
            }
        }
        if (changed) PlaylistStore.save(context, lists)
    }
}
