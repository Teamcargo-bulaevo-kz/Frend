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
        for ((key, r) in sorted) rows.add(rowFor(r.artist, r.title, r.album, r.tags, folders[key].orEmpty()))

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

    /** Одна строка таблицы «Все песни». Чистая функция: по ней же проверяется чтение обратно. */
    fun rowFor(artist: String, title: String, album: String, tags: TrackTags?, folders: List<String>): List<String> {
        val tg = tags?.takeIf { MusicTags.isTagged(it) }
        val liked = likedLabel(tags?.liked ?: 0)
        val folderText = folders.joinToString("; ")
        if (tg == null) return listOf(artist, title, album, "", "Не определено", "", "", "", "", "", liked, folderText, "")
        val note = if (tg.note.isNotBlank()) tg.note else if (tg.needsReview) AUTO_NOTE else ""
        return listOf(
            artist, title, album, tg.genreText,
            tg.moods.joinToString(", ") { MusicTags.moodShort[it] ?: it },
            tg.energy.toString(), tg.tempo, tg.description, confidenceLabel(tg.confidence),
            note, liked, folderText, sourceLabel(tg.source)
        )
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

    /** Таблица из текста: CSV (разделитель «;» или «,»), таблица markdown с «|», блоки ``` убираются. */
    fun parseTextTable(raw: String): List<List<String>> {
        val lines = raw.replace("\uFEFF", "").lines().filter { !it.trim().startsWith("```") }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val first = lines.first()
        if (first.count { it == '|' } >= 2 && first.count { it == '|' } > first.count { it == ';' }) {
            // markdown-таблица: строки вида | а | б |, разделитель |---|---| пропускаем
            return lines.filter { !Regex("^[\\s|:\\-]+$").matches(it) }.map { line ->
                line.trim().trim('|').split('|').map { it.trim() }
            }
        }
        return FileConvert.parseCsv(lines.joinToString("\n"), if (first.count { it == ';' } >= first.count { it == ',' }) ';' else ',')
    }

    /** Строки таблицы из файла: xlsx (все листы), csv, tsv, txt или md. */
    fun readAnyTable(file: File): List<List<String>> = when (file.extension.lowercase()) {
        "xlsx" -> try { FileConvert.xlsxRows(file, allSheets = true) } catch (e: Exception) { emptyList() }
        else -> try { parseTextTable(file.readText(Charsets.UTF_8)) } catch (e: Exception) { emptyList() }
    }

    /** Что получилось при последнем импорте: для понятного сообщения, если файл не подошёл. */
    @Volatile var lastReport: String = ""
        private set

    /** Подсказка, что ожидается в файле. */
    const val EXPECTED = "Нужна таблица, где у каждой песни есть исполнитель и название, плюс хотя бы настроение или жанр " +
        "(заголовки по-русски или по-английски: Исполнитель/Artist, Название/Title, Настроение/Mood, Жанр/Genre…)."

    /** Сопоставление с песнями на этом телефоне: нечёткое, чтобы «Artist - Song (Official)» нашлось как «Song». */
    private fun resolver(context: Context): (String, String) -> Pair<String, String>? {
        val device = try { MusicLibrary.index(context).allMusic().map { it.artist to it.title } } catch (e: Exception) { emptyList() }
        return TrackMatcher.build(device)
    }

    /** Вставка ответа другого ИИ: разбирает текст и применяет разметку. Возвращает число обновлённых песен. */
    fun importText(context: Context, text: String): Int {
        val parsed = parseRows(parseTextTable(text), MusicTags.all(context), resolver(context))
        lastReport = parsed.report()
        if (parsed.updates.isNotEmpty()) MusicTags.putAll(context, parsed.updates)
        if (parsed.folders.isNotEmpty()) restoreFolders(context, parsed.folders)
        return parsed.updates.size
    }

    /** Результат разбора таблицы: изменённые теги по ключам песен и папки по ключам песен, плюс статистика. */
    class Parsed(
        val updates: Map<String, TrackTags>,
        val folders: List<Pair<String, List<String>>>,
        val rows: Int = 0,
        val matched: Int = 0,
        val columns: String = ""
    ) {
        fun report(): String = when {
            rows == 0 && columns.isEmpty() -> "Не нашёл в файле заголовков с исполнителем и названием песни."
            rows == 0 -> "В файле нет строк с песнями (колонки: $columns)."
            else -> "Строк с песнями: $rows, совпало с музыкой на телефоне: $matched, обновлено: ${updates.size}. Колонки: $columns."
        }
    }

    /** Читает таблицу в нашем формате или в формате другого ИИ. Возвращает число песен, у которых теги обновились. */
    fun importFrom(context: Context, file: File): Int {
        val rows = readAnyTable(file)
        val parsed = parseRows(rows, MusicTags.all(context), resolver(context))
        lastReport = "«${file.name}»: " + parsed.report()
        if (parsed.updates.isNotEmpty()) MusicTags.putAll(context, parsed.updates, export = false)
        if (parsed.folders.isNotEmpty()) restoreFolders(context, parsed.folders)
        return parsed.updates.size
    }

    // ---------------------------------------------------------------- разбор таблицы

    private class Cols(
        val artist: Int, val title: Int, val combined: Boolean, val album: Int, val genre: Int, val mood: Int, val energy: Int,
        val tempo: Int, val desc: Int, val conf: Int, val note: Int, val liked: Int, val folders: Int, val source: Int, val names: String
    ) {
        val known: Int get() = listOf(artist, title, album, genre, mood, energy, tempo, desc, conf, liked).count { it >= 0 }
    }

    private val artistWords = listOf("исполнитель", "артист", "автор", "singer", "artist", "performer", "музыкант")
    private val titleWords = listOf("назван", "трек", "песн", "композиц", "title", "track", "song", "name")

    /** Заголовок таблицы или null, если строка на него не похожа. Слова ищутся по вхождению, без учёта регистра. */
    private fun detectHeader(row: List<String>): Cols? {
        val head = row.map { it.trim().lowercase() }
        if (head.count { it.isNotEmpty() } < 2) return null
        fun col(vararg names: String): Int = head.indexOfFirst { h -> h.isNotEmpty() && names.any { h.contains(it) } }
        val artist = col(*artistWords.toTypedArray())
        // «Название», но не «Альбом (название)»
        val title = head.indexOfFirst { h -> h.isNotEmpty() && !h.contains("альбом") && !h.contains("album") && titleWords.any { h.contains(it) } }
        if (title < 0) return null
        val mood = col("настроен", "mood", "vibe", "эмоци")
        val genre = col("жанр", "genre", "style", "стиль")
        val energy = col("энергичн", "энерг", "energy")
        // Это точно строка заголовка, если кроме названия есть ещё хотя бы одна известная колонка
        if (artist < 0 && mood < 0 && genre < 0 && energy < 0) return null
        val folders = head.indexOfFirst { it == "папки" || it == "folders" || it == "playlists" || it == "плейлисты" }
        val names = head.filter { it.isNotEmpty() }.take(12).joinToString(", ")
        return Cols(
            artist = artist, title = title, combined = artist < 0,
            album = col("альбом", "album"), genre = genre, mood = mood, energy = energy,
            tempo = col("ритм", "темп", "tempo", "bpm"), desc = col("описан", "description", "comment", "коммент"),
            conf = col("уверенн", "confidence"), note = col("пометк", "note"), liked = col("нравит", "liked", "like", "favorite", "любим"),
            folders = folders, source = col("источник", "source"), names = names
        )
    }

    private val splitDash = Regex("\\s+[-–—]\\s+")

    /** «5», «4/5», «8/10», «высокая», «low» → 1..5. */
    internal fun parseEnergy(text: String): Int? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        Regex("^(\\d+(?:[.,]\\d+)?)\\s*/\\s*(\\d+)$").find(t)?.let {
            val v = it.groupValues[1].replace(',', '.').toDouble()
            val max = it.groupValues[2].toDouble()
            if (max > 0) return Math.round(v / max * 5).toInt().coerceIn(1, 5)
        }
        t.replace(',', '.').toDoubleOrNull()?.let { return if (it > 5) Math.round(it / 2).toInt().coerceIn(1, 5) else Math.round(it).toInt().coerceIn(1, 5) }
        return when {
            "очень выс" in t || "very high" in t || "extreme" in t -> 5
            "очень низ" in t || "very low" in t -> 1
            "выс" in t || "high" in t || "энергич" in t -> 4
            "низ" in t || "low" in t || "спокой" in t -> 2
            "сред" in t || "medium" in t || "mid" in t || "moderate" in t -> 3
            else -> null
        }
    }

    /** «128», «128 BPM», «fast», «быстрый» → подпись темпа как в нашей таблице; непонятное остаётся как есть. */
    internal fun parseTempo(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        MusicTags.tempoKey(t)?.let { return MusicTags.tempos[it].orEmpty() }
        Regex("(\\d{2,3})").find(t)?.let {
            val bpm = it.groupValues[1].toInt()
            return MusicTags.tempos[if (bpm < 90) "slow" else if (bpm <= 110) "medium" else "fast"].orEmpty()
        }
        return t
    }

    /** Чистый разбор строк таблицы. Заголовок может быть не первой строкой и повторяться (несколько листов). Без Android, покрыт тестами. */
    fun parseRows(
        rows: List<List<String>>,
        local: Map<String, TrackTags>,
        resolve: ((String, String) -> Pair<String, String>?)? = null
    ): Parsed {
        var c: Cols? = null
        val updates = LinkedHashMap<String, TrackTags>()
        val folderRows = ArrayList<Pair<String, List<String>>>()
        var count = 0
        var matched = 0
        var firstColumns = ""

        for (row in rows) {
            val header = detectHeader(row)
            // Следующий заголовок (другой лист) принимаем только если он явно похож на заголовок: данные песни не должны его имитировать
            if (header != null && (c == null || header.known >= 3)) {
                c = header
                if (firstColumns.isEmpty()) firstColumns = header.names
                continue
            }
            val cols = c ?: continue
            fun cell(i: Int) = if (i >= 0) row.getOrNull(i)?.trim().orEmpty() else ""

            var artist = cell(cols.artist)
            var title = cell(cols.title)
            if (cols.combined) {
                val parts = splitDash.split(title, 2)
                if (parts.size == 2) { artist = parts[0].trim(); title = parts[1].trim() }
            }
            if (title.isEmpty()) continue
            count++
            val fileArtist = artist
            val fileTitle = title
            resolve?.invoke(artist, title)?.let { (a, t) -> artist = a; title = t; matched++ }
            val key = MusicTags.trackKey(artist, title)
            val liked = likedFromLabel(cell(cols.liked))
            val folders = cell(cols.folders).split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            if (folders.isNotEmpty()) folderRows.add(key to folders)

            val moodText = cell(cols.mood)
            var moods = MusicTags.moodsFromText(moodText)
            val old = local[key]
            val unknownLabel = moodText.lowercase().let { it.contains("не определ") || it == "-" || it == "—" }
            val energy = parseEnergy(cell(cols.energy))
            // Настроение названо незнакомым словом: берём из описания, иначе по энергичности. Такую строку стоит проверить (уверенность ниже)
            var guessed = false
            if (moods.isEmpty() && moodText.isNotEmpty() && !unknownLabel) {
                MusicTags.moodFromWords(cell(cols.desc))?.let { moods = setOf(it) }
                if (moods.isEmpty()) moods = setOf(if ((energy ?: 3) >= 4) "energetic" else if ((energy ?: 3) <= 2) "calm" else "happy")
                guessed = true
            }
            if (moods.isEmpty()) {
                // Разметки нет: сохраняем лайк
                if (liked != 0 && (old == null || old.liked != liked)) {
                    updates[key] = (old ?: TrackTags("other", "calm", 3, confidence = 0, artist = artist, title = title, album = cell(cols.album))).copy(liked = liked)
                }
                continue
            }
            val source = sourceFromLabel(cell(cols.source))
            val fileEdit = source == TrackTags.SOURCE_USER
            if (old != null && old.edited && !fileEdit) {
                if (old.liked != liked && liked != 0) updates[key] = old.copy(liked = liked)
                continue
            }
            val genreLabel = cell(cols.genre)
            val genreKey = if (genreLabel.isEmpty()) "other" else
                (MusicTags.genres.entries.firstOrNull { it.value.equals(genreLabel, true) }?.key ?: MusicTags.genreKeyFromLabel(genreLabel))
            val fileConf = confidenceFromLabel(cell(cols.conf))
            val conf = when {
                fileEdit -> 100
                guessed -> 50
                fileConf == null -> 70
                old != null && confidenceLabel(old.confidence) == confidenceLabel(fileConf) -> old.confidence
                else -> fileConf
            }
            val noteText = cell(cols.note)
            val tags = TrackTags(
                genre = genreKey, mood = moods.first(), energy = energy ?: 3,
                moods = moods, genreLabel = if (genreLabel.equals(MusicTags.genres[genreKey], true)) "" else genreLabel,
                tempo = parseTempo(cell(cols.tempo)), description = cell(cols.desc), confidence = conf, source = source,
                liked = if (liked != 0) liked else (old?.liked ?: 0), edited = fileEdit || (old?.edited ?: false),
                webTried = old?.webTried ?: false, aiTried = old?.aiTried ?: false,
                note = if (noteText == AUTO_NOTE || noteText.startsWith("ИИ: уточни")) "" else noteText,
                artist = artist.ifEmpty { fileArtist }, title = title.ifEmpty { fileTitle }, album = cell(cols.album)
            )
            if (old != tags) updates[key] = tags
        }
        return Parsed(updates, folderRows, count, matched, firstColumns)
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
