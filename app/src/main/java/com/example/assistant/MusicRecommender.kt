package com.example.assistant

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Статистика прослушиваний одной песни (хранится в [ListenStats]). */
data class TrackStats(
    val plays: Int = 0,
    val completes: Int = 0,
    val skips: Int = 0,
    val lastPlayedMs: Long = 0,
    val listenedMs: Long = 0
)

/** Песня со всем, что о ней известно: разметка и статистика. */
data class RecItem(val track: MusicTrack, val tags: TrackTags?, val stats: TrackStats?)

/** Что играет бесконечная волна. */
data class WaveSpec(
    val kind: Kind,
    /** Для MOOD: ключ настроения из [MusicTags.moods]. */
    val mood: String? = null,
    /** Для SIMILAR: ключ песни-образца ([MusicTags.trackKey]). */
    val seedKey: String? = null,
    /** Для PRESET: ключ готовой подборки из [MusicRecommender.presets]. */
    val preset: String? = null
) {
    enum class Kind { MY_WAVE, MOOD, SIMILAR, PRESET }

    fun title(): String = when (kind) {
        Kind.MY_WAVE -> "Моя волна"
        Kind.MOOD -> "Волна: " + (MusicTags.moods[mood]?.lowercase() ?: "настроение")
        Kind.SIMILAR -> "Похожие"
        Kind.PRESET -> MusicRecommender.presets.firstOrNull { it.key == preset }?.title ?: "Подборка"
    }
}

/**
 * Рекомендации без интернета: «Моя волна», волна по настроению, похожие песни, умные подборки.
 *
 * Каждая размеченная песня превращается в вектор признаков по звуку (профиль настроений, энергия, темп с учётом
 * двойного/половинного темпа, позитивность, возбуждение, напряжение, танцевальность), к сходству добавляются
 * совпадение жанра и исполнителя. Вкус пользователя — взвешенный центр песен, которые он лайкал и дослушивал
 * (свежие весят больше), пропуски тянут в другую сторону. Внутри одной волны пропуск сразу уводит от похожих
 * песен, дослушанная песня — притягивает к похожим. Немного случайности, чтобы волна не повторялась.
 * Чистые функции без Android: покрыты тестами.
 */
object MusicRecommender {

    private val moodKeys = MusicTags.moods.keys.toList()
    private const val DAY_MS = 86_400_000L

    // ---------------------------------------------------------------- признаки и сходство

    /** Вектор признаков песни; null — песня не размечена. */
    fun features(t: TrackTags?): DoubleArray? {
        if (t == null || !MusicTags.isTagged(t)) return null
        val scores = if (t.moodScores.isNotEmpty()) t.moodScores else MoodFusion.aiProfile(t.moods.ifEmpty { setOf(t.mood) })
        val v = DoubleArray(moodKeys.size + 6)
        moodKeys.forEachIndexed { i, k -> v[i] = (scores[k] ?: 0) / 100.0 }
        val o = moodKeys.size
        v[o] = (t.energy.coerceIn(1, 5) - 1) / 4.0
        v[o + 1] = tempoNorm(t)
        // Позитивность и остальное есть только после анализа звука; без них — по энергии и настроению.
        val measured = t.hasAudio
        v[o + 2] = if (measured) t.valence / 100.0 else guessValence(scores)
        v[o + 3] = if (measured) t.arousal / 100.0 else v[o]
        v[o + 4] = if (measured) t.tension / 100.0 else ((scores["dark"] ?: 0) + (scores["epic"] ?: 0)) / 100.0
        v[o + 5] = if (measured) t.danceability / 100.0 else (scores["dance"] ?: 0) / 100.0
        return v
    }

    private fun guessValence(scores: Map<String, Int>): Double {
        val plus = (scores["happy"] ?: 0) + (scores["dance"] ?: 0) + (scores["romantic"] ?: 0) * 0.5
        val minus = (scores["sad"] ?: 0) + (scores["dark"] ?: 0)
        return (0.5 + (plus - minus) / 200.0).coerceIn(0.0, 1.0)
    }

    /** Темп на шкале 0..1 (60..180 BPM, логарифмически). Неизвестный темп оценивается по энергии. */
    private fun tempoNorm(t: TrackTags): Double {
        val bpm = if (t.bpm > 0) t.bpm.toDouble() else 70.0 + t.energy.coerceIn(1, 5) * 12
        return (ln(bpm.coerceIn(50.0, 200.0) / 60.0) / ln(3.0)).coerceIn(0.0, 1.0)
    }

    /** Веса признаков: настроение важнее всего, затем энергия и темп. */
    private val weights: DoubleArray = DoubleArray(moodKeys.size + 6) { i ->
        when (i - moodKeys.size) {
            0 -> 1.6   // энергия
            1 -> 1.0   // темп
            2 -> 1.0   // позитивность
            3 -> 0.8   // возбуждение
            4 -> 0.7   // напряжение
            5 -> 0.6   // танцевальность
            else -> 1.3 // каждое настроение
        }
    }

    /** Разница темпов с учётом ошибки на октаву: 70 и 140 BPM для слуха почти одно и то же. */
    fun tempoDistance(a: Int, b: Int): Int {
        if (a <= 0 || b <= 0) return 0
        return minOf(abs(a - b), abs(a * 2 - b), abs(a - b * 2))
    }

    /** Сходство двух векторов признаков, 0..1. */
    fun similarity(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0
        var w = 0.0
        for (i in a.indices) {
            val diff = a[i] - b[i]
            d += weights[i] * diff * diff
            w += weights[i]
        }
        return (1.0 - sqrt(d / w) * 1.6).coerceIn(0.0, 1.0)
    }

    /** Сходство двух песен с учётом жанра, исполнителя и темпа. */
    fun trackSimilarity(a: RecItem, b: RecItem): Double {
        val fa = features(a.tags) ?: return 0.0
        val fb = features(b.tags) ?: return 0.0
        var s = similarity(fa, fb)
        val ta = a.tags!!
        val tb = b.tags!!
        if (ta.genre != "other" && ta.genre == tb.genre) s += 0.12
        if (a.track.artist.isNotBlank() && a.track.artist.equals(b.track.artist, ignoreCase = true)) s += 0.08
        val td = tempoDistance(ta.bpm, tb.bpm)
        if (ta.bpm > 0 && tb.bpm > 0 && td > 25) s -= 0.08
        return s.coerceIn(0.0, 1.2)
    }

    // ---------------------------------------------------------------- вкус

    /** Насколько песня нравится по поведению: лайк, дослушивания, пропуски; свежие события весят больше. */
    fun affinity(item: RecItem, now: Long): Double {
        val liked = item.tags?.liked ?: 0
        if (liked < 0) return -10.0
        val s = item.stats ?: TrackStats()
        val ageDays = if (s.lastPlayedMs > 0) (now - s.lastPlayedMs).coerceAtLeast(0) / DAY_MS.toDouble() else 365.0
        val fresh = 0.4 + 0.6 * exp(-ageDays / 45.0)
        return liked * 3.0 + (s.completes * 1.0 + s.plays * 0.2 - s.skips * 1.3) * fresh
    }

    /** Центр вкуса: взвешенное среднее признаков песен с положительной оценкой. null — данных пока нет. */
    fun tasteProfile(items: List<RecItem>, now: Long): DoubleArray? {
        var sum: DoubleArray? = null
        var total = 0.0
        for (item in items) {
            val a = affinity(item, now)
            if (a <= 0.3) continue
            val f = features(item.tags) ?: continue
            val w = min(a, 8.0)
            val acc = sum ?: DoubleArray(f.size).also { sum = it }
            for (i in f.indices) acc[i] += f[i] * w
            total += w
        }
        val acc = sum ?: return null
        if (total <= 0) return null
        for (i in acc.indices) acc[i] /= total
        return acc
    }

    /** Типичный вектор настроения: центр песен, где это настроение главное. */
    fun moodCentroid(items: List<RecItem>, mood: String): DoubleArray? {
        val picked = items.filter { val t = it.tags; t != null && MusicTags.isTagged(t) && moodScore(t, mood) >= 35 }
        val fs = picked.mapNotNull { features(it.tags) }
        if (fs.isEmpty()) return null
        val acc = DoubleArray(fs[0].size)
        for (f in fs) for (i in f.indices) acc[i] += f[i]
        for (i in acc.indices) acc[i] = acc[i] / fs.size
        return acc
    }

    /** Доля настроения в песне, 0..100. */
    fun moodScore(t: TrackTags, mood: String): Int =
        t.moodScores[mood] ?: when {
            t.mood == mood -> 70
            mood in t.moods -> 40
            else -> 0
        }

    // ---------------------------------------------------------------- состояние одной волны

    /** Что произошло в текущей волне: от этого она сразу подстраивается. */
    class Session {
        val played = LinkedHashSet<String>()
        val liked = ArrayList<DoubleArray>()
        val skipped = ArrayList<DoubleArray>()
        val recentArtists = ArrayDeque<String>()

        fun onPlayed(item: RecItem) {
            played.add(MusicTags.trackKey(item.track))
            val a = item.track.artist.lowercase()
            if (a.isNotBlank()) {
                recentArtists.addLast(a)
                while (recentArtists.size > 6) recentArtists.removeFirst()
            }
        }

        fun onFeedback(item: RecItem, completed: Boolean) {
            val f = features(item.tags) ?: return
            if (completed) { liked.add(f); if (liked.size > 12) liked.removeAt(0) }
            else { skipped.add(f); if (skipped.size > 12) skipped.removeAt(0) }
        }
    }

    // ---------------------------------------------------------------- выбор

    private fun noveltyScore(item: RecItem, now: Long): Double {
        val s = item.stats ?: return 0.15 // ни разу не играла: немного открытий
        if (s.lastPlayedMs <= 0) return 0.15
        val hours = (now - s.lastPlayedMs) / 3_600_000.0
        return when {
            hours < 2 -> -0.6   // только что звучала
            hours < 24 -> -0.2
            hours < 24 * 7 -> 0.0
            else -> 0.08
        }
    }

    private fun sessionScore(f: DoubleArray?, session: Session): Double {
        if (f == null) return 0.0
        var s = 0.0
        if (session.liked.isNotEmpty()) s += 0.25 * session.liked.maxOf { similarity(f, it) }
        if (session.skipped.isNotEmpty()) {
            val near = session.skipped.maxOf { similarity(f, it) }
            if (near > 0.8) s -= 0.35 * (near - 0.8) / 0.2 + 0.1
        }
        return s
    }

    /**
     * Следующие [count] песен волны. [items] — вся музыка, [seed] — песня-образец для SIMILAR.
     * Случайность берётся из [random], чтобы тесты были повторяемыми.
     */
    fun nextForWave(
        spec: WaveSpec, items: List<RecItem>, session: Session, count: Int,
        now: Long = System.currentTimeMillis(), random: Random = Random.Default
    ): List<RecItem> {
        val pool = items.filter { (it.tags?.liked ?: 0) >= 0 && MusicTags.trackKey(it.track) !in session.played }
        if (pool.isEmpty()) return emptyList()
        val target: DoubleArray? = when (spec.kind) {
            WaveSpec.Kind.MY_WAVE -> tasteProfile(items, now)
            WaveSpec.Kind.MOOD -> spec.mood?.let { moodCentroid(items, it) }
            WaveSpec.Kind.SIMILAR -> items.firstOrNull { MusicTags.trackKey(it.track) == spec.seedKey }?.let { features(it.tags) }
            WaveSpec.Kind.PRESET -> null
        }
        val seedItem = if (spec.kind == WaveSpec.Kind.SIMILAR) items.firstOrNull { MusicTags.trackKey(it.track) == spec.seedKey } else null
        val preset = presets.firstOrNull { it.key == spec.preset }
        val maxAffinity = max(1.0, pool.maxOf { affinity(it, now) })

        val scored = pool.mapNotNull { item ->
            val f = features(item.tags)
            val tags = item.tags
            val base = when (spec.kind) {
                WaveSpec.Kind.MY_WAVE -> when {
                    target != null && f != null -> 0.6 * similarity(f, target) + 0.25 * (affinity(item, now) / maxAffinity).coerceIn(-1.0, 1.0)
                    f != null -> 0.4 + 0.2 * (affinity(item, now) / maxAffinity).coerceIn(-1.0, 1.0)
                    else -> 0.3 + 0.2 * (affinity(item, now) / maxAffinity).coerceIn(-1.0, 1.0) // неразмеченная: по поведению
                }
                WaveSpec.Kind.MOOD -> {
                    if (tags == null || f == null) return@mapNotNull null
                    val m = moodScore(tags, spec.mood ?: return@mapNotNull null)
                    if (m < 15) return@mapNotNull null
                    0.55 * m / 100.0 + (if (target != null) 0.3 * similarity(f, target) else 0.0) +
                        0.1 * (affinity(item, now) / maxAffinity).coerceIn(-1.0, 1.0)
                }
                WaveSpec.Kind.SIMILAR -> {
                    if (seedItem == null || f == null) return@mapNotNull null
                    if (MusicTags.trackKey(item.track) == spec.seedKey) return@mapNotNull null
                    val s = trackSimilarity(seedItem, item)
                    if (s < 0.45) return@mapNotNull null
                    s + 0.08 * (affinity(item, now) / maxAffinity).coerceIn(-1.0, 1.0)
                }
                WaveSpec.Kind.PRESET -> {
                    if (tags == null || preset == null || !preset.matches(tags)) return@mapNotNull null
                    0.5 + 0.2 * (affinity(item, now) / maxAffinity).coerceIn(-1.0, 1.0)
                }
            }
            val jitter = random.nextDouble() * 0.18
            item to (base + noveltyScore(item, now) + sessionScore(f, session) + jitter)
        }.sortedByDescending { it.second }

        // Разнообразие: один исполнитель не подряд и не больше двух раз за выдачу.
        val result = ArrayList<RecItem>()
        val artistCount = HashMap<String, Int>()
        val recent = session.recentArtists.toMutableList()
        for ((item, _) in scored) {
            if (result.size >= count) break
            val artist = item.track.artist.lowercase()
            if (artist.isNotBlank()) {
                if (recent.lastOrNull() == artist) continue
                if ((artistCount[artist] ?: 0) >= 2) continue
            }
            result.add(item)
            if (artist.isNotBlank()) { artistCount[artist] = (artistCount[artist] ?: 0) + 1; recent.add(artist) }
        }
        // Если разнообразие отсекло слишком много (у пользователя мало исполнителей) — добираем как есть.
        if (result.size < count) for ((item, _) in scored) {
            if (result.size >= count) break
            if (item !in result) result.add(item)
        }
        return result
    }

    /** Похожие на песню, лучшие сверху. */
    fun similar(seed: RecItem, items: List<RecItem>, count: Int): List<RecItem> {
        val seedKey = MusicTags.trackKey(seed.track)
        return items.asSequence()
            .filter { MusicTags.trackKey(it.track) != seedKey && (it.tags?.liked ?: 0) >= 0 }
            .map { it to trackSimilarity(seed, it) }
            .filter { it.second >= 0.45 }
            .sortedByDescending { it.second }
            .take(count).map { it.first }.toList()
    }

    // ---------------------------------------------------------------- умные подборки

    class Preset(val key: String, val title: String, val subtitle: String, val matches: (TrackTags) -> Boolean)

    private fun sc(t: TrackTags, mood: String) = moodScore(t, mood)

    val presets: List<Preset> = listOf(
        Preset("morning", "Утро · бодрое начало", "светлое, средний темп") { t ->
            t.energy in 2..4 && (sc(t, "happy") >= 20 || sc(t, "energetic") >= 25) && sc(t, "dark") < 25 && sc(t, "sad") < 30
        },
        Preset("focus", "Фокус · работа", "ровное, без резкости") { t ->
            (sc(t, "focus") >= 18 || (sc(t, "calm") >= 25 && t.energy <= 3)) && t.energy <= 3 && (t.bpm == 0 || t.bpm <= 125) && t.tension <= 60
        },
        Preset("workout", "Заряд · тренировка", "энергично, быстро") { t ->
            (sc(t, "energetic") >= 25 || sc(t, "epic") >= 25) && t.energy >= 4 && (t.bpm == 0 || t.bpm >= 110 || t.bpm <= 75)
        },
        Preset("run", "Бег · ровный темп", "150–180 шагов в минуту") { t ->
            t.energy >= 3 && t.bpm > 0 && (t.bpm in 145..185 || t.bpm * 2 in 145..185)
        },
        Preset("dance", "Танцевальный ритм", "грув, вечеринка") { t ->
            (sc(t, "dance") >= 20 || t.danceability >= 55) && t.energy >= 3 && (t.bpm == 0 || t.bpm in 95..160)
        },
        Preset("drive", "В дорогу", "энергично, но без агрессии") { t ->
            t.energy in 3..5 && sc(t, "dark") < 30 && (sc(t, "energetic") >= 20 || sc(t, "happy") >= 20 || sc(t, "epic") >= 20)
        },
        Preset("evening", "Спокойствие · вечер", "мягко, медленно") { t ->
            (sc(t, "calm") >= 25 || sc(t, "romantic") >= 25) && t.energy <= 3 && (t.bpm == 0 || t.bpm <= 110)
        },
        Preset("sleep", "Сон", "тихо и медленно") { t ->
            sc(t, "calm") >= 35 && t.energy <= 2 && t.tension <= 45 && (t.bpm == 0 || t.bpm <= 95)
        },
        Preset("romance", "Романтика", "о любви") { t -> sc(t, "romantic") >= 30 },
        Preset("sad", "Погрустить", "меланхолия") { t -> sc(t, "sad") >= 30 && t.energy <= 3 },
        Preset("power", "Мощь", "эпично и тяжело") { t -> (sc(t, "epic") >= 30 || sc(t, "dark") >= 30) && t.energy >= 3 }
    )

    /** Подборка по готовому шаблону: подходящие песни, любимые и дослушиваемые выше. */
    fun preset(key: String, items: List<RecItem>, now: Long = System.currentTimeMillis()): List<RecItem> {
        val p = presets.firstOrNull { it.key == key } ?: return emptyList()
        return items.filter { val t = it.tags; t != null && MusicTags.isTagged(t) && t.liked >= 0 && p.matches(t) }
            .sortedByDescending { affinity(it, now) }
    }

    /** Подборка, подходящая под время суток. */
    fun presetForHour(hour: Int): String = when (hour) {
        in 5..10 -> "morning"
        in 11..17 -> "focus"
        in 18..21 -> "evening"
        else -> "sleep"
    }

    /** Ни разу не слушанные, но близкие к вкусу. */
    fun discoveries(items: List<RecItem>, count: Int, now: Long = System.currentTimeMillis()): List<RecItem> {
        val taste = tasteProfile(items, now)
        val fresh = items.filter { (it.stats?.plays ?: 0) == 0 && (it.tags?.liked ?: 0) >= 0 }
        if (taste == null) return fresh.shuffled(Random(now / DAY_MS)).take(count)
        return fresh.mapNotNull { item -> features(item.tags)?.let { item to similarity(it, taste) } }
            .sortedByDescending { it.second }.take(count).map { it.first }
    }

    /** Любимые, которые давно не звучали (больше 3 недель). */
    fun forgotten(items: List<RecItem>, count: Int, now: Long = System.currentTimeMillis()): List<RecItem> =
        items.filter {
            val s = it.stats
            affinity(it, now) >= 2.0 && s != null && s.lastPlayedMs > 0 && now - s.lastPlayedMs > 21 * DAY_MS
        }.sortedByDescending { affinity(it, now) }.take(count)

    /** Чаще всего дослушиваемые. */
    fun topPlayed(items: List<RecItem>, count: Int): List<RecItem> =
        items.filter { (it.stats?.completes ?: 0) >= 2 && (it.tags?.liked ?: 0) >= 0 }
            .sortedByDescending { (it.stats?.completes ?: 0) * 2 + (it.stats?.plays ?: 0) - (it.stats?.skips ?: 0) * 2 }
            .take(count)

    /** Короткое описание вкуса для пользователя и для модели. */
    fun describeTaste(items: List<RecItem>, now: Long = System.currentTimeMillis()): String {
        val taste = tasteProfile(items, now) ?: return "Пока мало данных: лайкай песни и слушай волну, я быстро подстроюсь."
        val moods = moodKeys.indices.sortedByDescending { taste[it] }.take(3)
            .filter { taste[it] > 0.08 }.joinToString(", ") { MusicTags.moods[moodKeys[it]].orEmpty().lowercase() }
        val o = moodKeys.size
        val energy = 1 + taste[o] * 4
        val loved = items.filter { affinity(it, now) > 0.3 }
        val genres = loved.mapNotNull { it.tags?.genre?.takeIf { g -> g != "other" } }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { MusicTags.genres[it.key].orEmpty().lowercase() }
        val artists = loved.mapNotNull { it.track.artist.takeIf { a -> a.isNotBlank() && !a.equals("<unknown>", true) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
        return buildString {
            append("Тебе нравится: ").append(moods.ifBlank { "разное" })
            append(". Энергия в среднем ").append("%.1f".format(energy)).append(" из 5")
            if (genres.isNotBlank()) append(". Жанры: ").append(genres)
            if (artists.isNotBlank()) append(". Чаще всего: ").append(artists)
            append('.')
        }
    }
}
