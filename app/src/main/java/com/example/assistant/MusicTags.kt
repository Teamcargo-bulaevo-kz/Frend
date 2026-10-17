package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Жанр, настроение и энергичность трека. mood и genre — ключи из MusicTags.moods и MusicTags.genres. */
data class TrackTags(val genre: String, val mood: String, val energy: Int)

/** Словарь настроений и жанров, разбор речи и ответа модели, хранение тегов. */
object MusicTags {

    val moods: Map<String, String> = linkedMapOf(
        "energetic" to "Энергичная",
        "calm" to "Спокойная",
        "happy" to "Весёлая",
        "sad" to "Грустная",
        "romantic" to "Романтичная",
        "dance" to "Танцевальная",
        "focus" to "Для фона и работы",
        "epic" to "Мощная"
    )

    val genres: Map<String, String> = linkedMapOf(
        "pop" to "Поп", "rock" to "Рок", "hiphop" to "Хип-хоп", "electronic" to "Электроника",
        "classical" to "Классика", "jazz" to "Джаз", "chanson" to "Шансон", "folk" to "Народная",
        "metal" to "Метал", "rnb" to "R&B", "indie" to "Инди", "country" to "Кантри",
        "soundtrack" to "Саундтрек", "reggae" to "Регги", "blues" to "Блюз", "other" to "Другое"
    )

    private val moodWords: List<Pair<String, List<String>>> = listOf(
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

    /** Ответ модели → теги по номерам треков в пачке (с 1). Некорректные записи пропускаются. */
    fun parseResponse(text: String): Map<Int, TrackTags> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val array = try { JSONArray(text.substring(start, end + 1)) } catch (e: Exception) { return emptyMap() }
        val result = LinkedHashMap<Int, TrackTags>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val n = o.optInt("i", -1)
            val mood = o.optString("m").trim().lowercase()
            if (n < 1 || mood !in moods) continue
            val genre = o.optString("g").trim().lowercase().let { if (it in genres) it else "other" }
            result[n] = TrackTags(genre, mood, o.optInt("e", 3).coerceIn(1, 5))
        }
        return result
    }

    // ---------------------------------------------------------------- хранение

    private const val FILE_NAME = "music_tags.json"
    @Volatile private var cache: MutableMap<String, TrackTags>? = null

    fun trackKey(t: MusicTrack): String = TextMatch.normalize(t.artist + " " + t.title)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun loaded(context: Context): MutableMap<String, TrackTags> {
        cache?.let { return it }
        val map = HashMap<String, TrackTags>()
        val f = file(context)
        if (f.exists()) {
            try {
                val root = JSONObject(f.readText())
                for (key in root.keys()) {
                    val o = root.getJSONObject(key)
                    map[key] = TrackTags(o.optString("g", "other"), o.optString("m", "calm"), o.optInt("e", 3))
                }
            } catch (e: Exception) {
                // повреждённый файл: начнём разметку заново
            }
        }
        cache = map
        return map
    }

    fun get(context: Context, track: MusicTrack): TrackTags? = loaded(context)[trackKey(track)]

    fun count(context: Context): Int = loaded(context).size

    @Synchronized
    fun putAll(context: Context, tags: Map<String, TrackTags>) {
        val map = loaded(context)
        map.putAll(tags)
        val root = JSONObject()
        for ((k, v) in map) root.put(k, JSONObject().put("g", v.genre).put("m", v.mood).put("e", v.energy))
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file(context))) file(context).writeText(root.toString())
    }

    @Synchronized
    fun clear(context: Context) {
        cache = HashMap()
        file(context).delete()
    }

    /** Треки с нужным настроением и/или жанром; maxEnergy/minEnergy сужают по энергичности. */
    fun filter(
        context: Context, tracks: List<MusicTrack>, mood: String?, genre: String?,
        minEnergy: Int? = null, maxEnergy: Int? = null
    ): List<MusicTrack> = tracks.filter { t ->
        val tags = get(context, t) ?: return@filter false
        (mood == null || tags.mood == mood) && (genre == null || tags.genre == genre) &&
            (minEnergy == null || tags.energy >= minEnergy) && (maxEnergy == null || tags.energy <= maxEnergy)
    }

    /** Сводка: сколько треков размечено и как они распределены. */
    fun summary(context: Context, tracks: List<MusicTrack>): String {
        val tagged = tracks.mapNotNull { get(context, it) }
        if (tagged.isEmpty()) return "Музыка ещё не размечена. Скажи «проанализируй мою музыку»."
        val byMood = tagged.groupingBy { it.mood }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${moods[it.key]?.lowercase()} ${it.value}" }
        val byGenre = tagged.groupingBy { it.genre }.eachCount().entries.sortedByDescending { it.value }.take(6)
            .joinToString(", ") { "${genres[it.key]?.lowercase()} ${it.value}" }
        return "Размечено ${tagged.size} из ${tracks.size}.\nНастроения: $byMood.\nЖанры: $byGenre."
    }
}
