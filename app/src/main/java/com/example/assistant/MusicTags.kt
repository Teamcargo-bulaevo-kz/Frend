package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Всё, что известно о песне: жанр, настроения (можно несколько), энергичность, темп, описание,
 * уверенность в оценке, лайк и откуда данные. artist/title/album хранятся рядом, чтобы таблица
 * Музыка.xlsx была полной и теги переезжали на другое устройство.
 */
data class TrackTags(
    val genre: String,
    val mood: String,
    val energy: Int,
    val moods: Set<String> = setOf(mood),
    val genreLabel: String = "",
    val tempo: String = "",
    val description: String = "",
    val confidence: Int = 70,
    val source: String = SOURCE_AI,
    val liked: Int = 0,
    val edited: Boolean = false,
    val webTried: Boolean = false,
    val note: String = "",
    val artist: String = "",
    val title: String = "",
    val album: String = ""
) {
    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_WEB = "web"
        const val SOURCE_USER = "user"
        const val SOURCE_IMPORT = "import"
    }

    /** Подпись жанра для людей: свободный текст, если есть, иначе из словаря. */
    val genreText: String get() = genreLabel.ifBlank { MusicTags.genres[genre].orEmpty() }

    /** Песню стоит показать пользователю на проверку. */
    val needsReview: Boolean get() = !edited && confidence < MusicTags.REVIEW_BELOW
}

/** Словарь настроений и жанров, разбор речи и ответа модели, хранение тегов. */
object MusicTags {

    const val REVIEW_BELOW = 55

    val moods: Map<String, String> = linkedMapOf(
        "energetic" to "Энергичная",
        "calm" to "Спокойная",
        "happy" to "Весёлая",
        "sad" to "Грустная",
        "romantic" to "Романтичная",
        "dance" to "Танцевальная",
        "focus" to "Для фона и работы",
        "epic" to "Мощная",
        "dark" to "Агрессивная / мрачная"
    )

    /** Короткие подписи для таблицы (как в твоём файле: «Весело», «Энергично»). */
    val moodShort: Map<String, String> = linkedMapOf(
        "energetic" to "Энергично", "calm" to "Спокойно", "happy" to "Весело", "sad" to "Грустно",
        "romantic" to "Романтично", "dance" to "Танцевально", "focus" to "Для фона", "epic" to "Мощно",
        "dark" to "Агрессивно/Мрачно"
    )

    val genres: Map<String, String> = linkedMapOf(
        "pop" to "Поп", "rock" to "Рок", "hiphop" to "Хип-хоп", "electronic" to "Электроника",
        "classical" to "Классика", "jazz" to "Джаз", "chanson" to "Шансон", "folk" to "Народная",
        "metal" to "Метал", "rnb" to "R&B", "indie" to "Инди", "country" to "Кантри",
        "soundtrack" to "Саундтрек", "reggae" to "Регги", "blues" to "Блюз", "other" to "Другое"
    )

    val tempos: Map<String, String> = linkedMapOf(
        "slow" to "медленный (до 90 BPM)",
        "medium" to "средний (90–110 BPM)",
        "fast" to "быстрый (120–140 BPM)"
    )

    fun tempoKey(label: String): String? {
        val t = label.lowercase()
        return when {
            "медлен" in t || "slow" in t -> "slow"
            "средн" in t || "medium" in t -> "medium"
            "быстр" in t || "fast" in t -> "fast"
            else -> null
        }
    }

    private val moodWords: List<Pair<String, List<String>>> = listOf(
        "dark" to listOf("агрессивн", "мрачн", "злую", "злой", "тяжел"),
        "epic" to listOf("эпичн", "мощн", "героич", "пафосн"),
        "energetic" to listOf("энергичн", "бодр", "заряжа", "зарядк", "тренировк", "спорт", "пробежк", "качалк", "драйвов"),
        "calm" to listOf("спокойн", "расслабл", "релакс", "чилл", "для сна", "засыпа", "тиху", "тихие", "медленн", "умиротвор", "лёгк", "легк"),
        "happy" to listOf("весел", "позитив", "радостн", "жизнерадост", "солнечн", "хорошего настроения"),
        "sad" to listOf("грустн", "печальн", "тоскл", "меланхол", "погрустить"),
        "romantic" to listOf("романтич", "любовн", "про любовь", "для свидания"),
        "dance" to listOf("танцевальн", "танцпол", "вечеринк", "клубн", "потанцева"),
        "focus" to listOf("для работы", "для учёбы", "для учебы", "сосредоточ", "фонов", "концентрац", "для фона")
    ).map { (k, v) -> k to v.map { TextMatch.normalize(it) } }

    /** «спокойную», «для работы», «энергичные» → ключ настроения. null — настроение в тексте не названо. */
    fun moodFromWords(text: String): String? {
        val t = TextMatch.normalize(text)
        return moodWords.firstOrNull { (_, stems) -> stems.any { t.contains(it) } }?.first
    }

    /** «Весело, Романтично» → несколько настроений (по запятым и точкам с запятой). */
    fun moodsFromText(text: String): Set<String> =
        text.split(',', ';').mapNotNull { moodFromWords(it) }.toCollection(LinkedHashSet())

    /** Короткие названия жанров: слово целиком или с падежным окончанием («рок», «рока», «рэпа»), чтобы не путать с именами. */
    private val genreExact: List<Pair<String, List<String>>> = listOf(
        "pop" to listOf("поп", "попс"),
        "rock" to listOf("рок"),
        "hiphop" to listOf("рэп", "реп", "хип", "хоп"),
        "electronic" to listOf("техно", "хаус", "транс", "edm"),
        "metal" to listOf("метал", "металл"),
        "indie" to listOf("инди"),
        "rnb" to listOf("rnb", "рнб"),
        "soundtrack" to listOf("ost")
    )

    /** Длинные основы: подходит любое слово, которое с них начинается («джазовую», «классическую»). */
    private val genrePrefix: List<Pair<String, List<String>>> = listOf(
        "classical" to listOf("классик", "классическ"),
        "electronic" to listOf("электрон", "диджейск"),
        "jazz" to listOf("джаз"),
        "chanson" to listOf("шансон"),
        "folk" to listOf("народн", "фолк"),
        "country" to listOf("кантри"),
        "soundtrack" to listOf("саундтрек"),
        "reggae" to listOf("регги"),
        "blues" to listOf("блюз")
    )

    private val caseEndings = listOf("", "а", "у", "е", "ом", "ы", "ов")

    /** «рок», «джаз», «рэп» → ключ жанра. null — жанр в тексте не назван. */
    fun genreFromWords(text: String): String? {
        val words = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }
        for ((key, stems) in genreExact) {
            if (words.any { w -> stems.any { s -> caseEndings.any { e -> w == s + e } } }) return key
        }
        for ((key, stems) in genrePrefix) {
            if (words.any { w -> stems.any { s -> w.startsWith(s) } }) return key
        }
        return null
    }

    /** Подпись жанра из таблицы («R&B / клубный поп», «Латино-поп») → ближайший ключ жанра. */
    fun genreKeyFromLabel(label: String): String {
        val t = label.lowercase()
        val rules = listOf(
            "rnb" to listOf("r&b", "rnb", "ритм-н", "соул"),
            "hiphop" to listOf("хип", "рэп", "рап", "rap", "hip"),
            "metal" to listOf("метал", "metal"),
            "rock" to listOf("рок", "rock", "панк", "гранж"),
            "electronic" to listOf("электрон", "хаус", "house", "edm", "техно", "транс", "club", "дэнс", "dance", "диджей"),
            "pop" to listOf("поп", "pop"),
            "jazz" to listOf("джаз", "jazz"),
            "classical" to listOf("классик"),
            "chanson" to listOf("шансон"),
            "indie" to listOf("инди", "indie"),
            "folk" to listOf("фолк", "народ", "folk"),
            "country" to listOf("кантри", "country"),
            "blues" to listOf("блюз", "blues"),
            "reggae" to listOf("регги", "reggae"),
            "soundtrack" to listOf("саундтрек", "ost", "soundtrack")
        )
        return rules.firstOrNull { (_, stems) -> stems.any { it in t } }?.first ?: "other"
    }

    /** Убирает из названия мусор вроде «[muzmo.ru]» и «(Sefon.Pro)», подчёркивания и расширение. */
    fun cleanTitle(raw: String): String {
        var s = raw.replace('_', ' ')
        s = s.replace(Regex("\\.(mp3|m4a|flac|ogg|wav|aac)$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s*[\\[(][^\\])]*\\.[A-Za-z]{2,}[^\\])]*[\\])]"), "")
        s = s.replace(Regex("\\s*[\\[(](official|lyric|audio|video|hd|hq)[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Ответ модели → теги по номерам треков в пачке (с 1). Некорректные записи пропускаются. */
    fun parseResponse(text: String, source: String = TrackTags.SOURCE_AI): Map<Int, TrackTags> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val array = try { JSONArray(text.substring(start, end + 1)) } catch (e: Exception) { return emptyMap() }
        val result = LinkedHashMap<Int, TrackTags>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val n = o.optInt("i", -1)
            if (n < 1) continue
            val found = LinkedHashSet<String>()
            val m = o.opt("m")
            when (m) {
                is JSONArray -> for (j in 0 until m.length()) m.optString(j).trim().lowercase().takeIf { it in moods }?.let { found.add(it) }
                is String -> m.trim().lowercase().takeIf { it in moods }?.let { found.add(it) }
            }
            if (found.isEmpty()) continue
            val genre = o.optString("g").trim().lowercase().let { if (it in genres) it else "other" }
            val label = o.optString("gl").trim().take(40)
            val tempo = tempos[o.optString("t").trim().lowercase()] ?: ""
            result[n] = TrackTags(
                genre = genre, mood = found.first(), energy = o.optInt("e", 3).coerceIn(1, 5), moods = found.take(3).toSet(),
                genreLabel = label, tempo = tempo, description = o.optString("d").trim().take(120),
                confidence = o.optInt("c", 70).coerceIn(0, 100), source = source
            )
        }
        return result
    }

    // ---------------------------------------------------------------- хранение

    private const val FILE_NAME = "music_tags.json"
    @Volatile private var cache: MutableMap<String, TrackTags>? = null

    fun trackKey(artist: String, title: String): String = TextMatch.normalize("$artist $title")
    fun trackKey(t: MusicTrack): String = trackKey(t.artist, t.title)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun toJson(v: TrackTags) = JSONObject()
        .put("g", v.genre).put("m", v.mood).put("e", v.energy).put("ms", JSONArray(v.moods.toList()))
        .put("gl", v.genreLabel).put("t", v.tempo).put("d", v.description).put("c", v.confidence)
        .put("s", v.source).put("l", v.liked).put("ed", v.edited).put("w", v.webTried).put("n", v.note)
        .put("ar", v.artist).put("ti", v.title).put("al", v.album)

    private fun fromJson(o: JSONObject): TrackTags {
        val mood = o.optString("m", "calm")
        val ms = o.optJSONArray("ms")
        val moods = if (ms == null) setOf(mood) else (0 until ms.length()).map { ms.optString(it) }.filter { it in this.moods }.toSet()
        return TrackTags(
            genre = o.optString("g", "other"), mood = mood, energy = o.optInt("e", 3),
            moods = moods.ifEmpty { setOf(mood) }, genreLabel = o.optString("gl"), tempo = o.optString("t"),
            description = o.optString("d"), confidence = o.optInt("c", 70), source = o.optString("s", TrackTags.SOURCE_AI),
            liked = o.optInt("l", 0), edited = o.optBoolean("ed", false), webTried = o.optBoolean("w", false),
            note = o.optString("n"), artist = o.optString("ar"), title = o.optString("ti"), album = o.optString("al")
        )
    }

    @Synchronized
    private fun loaded(context: Context): MutableMap<String, TrackTags> {
        cache?.let { return it }
        val map = LinkedHashMap<String, TrackTags>()
        val f = file(context)
        if (f.exists()) {
            try {
                val root = JSONObject(f.readText())
                for (key in root.keys()) map[key] = fromJson(root.getJSONObject(key))
            } catch (e: Exception) {
                // повреждённый файл: начнём заново (таблица Музыка.xlsx всё восстановит)
            }
        }
        cache = map
        return map
    }

    fun get(context: Context, track: MusicTrack): TrackTags? = loaded(context)[trackKey(track)]

    fun getByKey(context: Context, key: String): TrackTags? = loaded(context)[key]

    /** Сколько песен реально размечено (заготовки, где есть только лайк, не считаются). */
    fun count(context: Context): Int = loaded(context).values.count { isTagged(it) }

    @Synchronized
    fun all(context: Context): Map<String, TrackTags> = LinkedHashMap(loaded(context))

    private fun persist(context: Context, map: Map<String, TrackTags>) {
        val root = JSONObject()
        for ((k, v) in map) root.put(k, toJson(v))
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file(context))) file(context).writeText(root.toString())
    }

    /** Сохраняет пачку тегов и просит таблицу Музыка.xlsx обновиться (с задержкой). */
    @Synchronized
    fun putAll(context: Context, tags: Map<String, TrackTags>, export: Boolean = true) {
        val map = loaded(context)
        map.putAll(tags)
        persist(context, map)
        if (export) MusicCatalog.scheduleExport(context)
    }

    /** Меняет теги одной песни. Если их ещё нет, создаёт пустую заготовку. Ручное изменение отмечается как «от пользователя». */
    @Synchronized
    fun update(context: Context, track: MusicTrack, manual: Boolean = true, change: (TrackTags) -> TrackTags) {
        val key = trackKey(track)
        val base = loaded(context)[key]
            ?: TrackTags(genre = "other", mood = "calm", energy = 3, confidence = 0, artist = track.artist, title = track.title, album = track.album)
        var updated = change(base)
        if (manual) updated = updated.copy(edited = true, confidence = 100, source = TrackTags.SOURCE_USER)
        val withNames = updated.copy(
            artist = updated.artist.ifBlank { track.artist }, title = updated.title.ifBlank { track.title },
            album = updated.album.ifBlank { track.album }
        )
        putAll(context, mapOf(key to withNames))
    }

    /** Лайк (1), дизлайк (-1) или снятие оценки (0). Оценка не делает разметку «проверенной вручную». */
    fun setLiked(context: Context, track: MusicTrack, value: Int) {
        val key = trackKey(track)
        val existing = loaded(context)[key]
        if (existing == null) {
            // песня не размечена: храним только оценку, confidence 0 — разметка ещё предстоит
            update(context, track, manual = false) { it.copy(liked = value) }
        } else {
            putAll(context, mapOf(key to existing.copy(liked = value)))
        }
    }

    fun likedOf(context: Context, track: MusicTrack): Int = get(context, track)?.liked ?: 0

    /** Размечена ли песня на самом деле (не просто хранит лайк). */
    fun isTagged(tags: TrackTags?): Boolean = tags != null && (tags.confidence > 0 || tags.edited)

    @Synchronized
    fun clear(context: Context) {
        cache = LinkedHashMap()
        file(context).delete()
    }

    /** Треки с нужным настроением и/или жанром; дизлайкнутые не попадают. */
    fun filter(
        context: Context, tracks: List<MusicTrack>, mood: String?, genre: String?,
        minEnergy: Int? = null, maxEnergy: Int? = null, likedOnly: Boolean = false
    ): List<MusicTrack> = tracks.filter { t ->
        val tags = get(context, t) ?: return@filter false
        if (!isTagged(tags) || tags.liked < 0) return@filter false
        (mood == null || mood in tags.moods) && (genre == null || tags.genre == genre) &&
            (minEnergy == null || tags.energy >= minEnergy) && (maxEnergy == null || tags.energy <= maxEnergy) &&
            (!likedOnly || tags.liked > 0)
    }

    /** Сводка: сколько треков размечено и как они распределены. */
    fun summary(context: Context, tracks: List<MusicTrack>): String {
        val tagged = tracks.mapNotNull { get(context, it) }.filter { isTagged(it) }
        if (tagged.isEmpty()) return "Музыка ещё не размечена. Скажи «проанализируй мою музыку»."
        val byMood = tagged.flatMap { it.moods }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${moods[it.key]?.lowercase()} ${it.value}" }
        val byGenre = tagged.groupingBy { it.genre }.eachCount().entries.sortedByDescending { it.value }.take(6)
            .joinToString(", ") { "${genres[it.key]?.lowercase()} ${it.value}" }
        val review = tagged.count { it.needsReview }
        val liked = tagged.count { it.liked > 0 }
        val tail = (if (review > 0) "\nНужно проверить: $review." else "") + (if (liked > 0) "\nПонравилось: $liked." else "")
        return "Размечено ${tagged.size} из ${tracks.size}.\nНастроения: $byMood.\nЖанры: $byGenre.$tail"
    }
}
