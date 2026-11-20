package com.example.assistant

import kotlin.math.roundToInt

/**
 * Сводит вместе два источника разметки:
 *  - измерения по самому звуку ([LocalMoodAnalyzer]): темп, энергия, профиль настроений в процентах;
 *  - мнение модели по названию и исполнителю (Groq): жанр, «характер» песни, который по звуку не слышно
 *    (романтичная, грустная по тексту), описание.
 * Звук надёжнее для темпа и энергии, модель надёжнее для жанра. Настроение — взвешенная смесь,
 * вес модели растёт с её уверенностью. Чистая функция, без Android: покрыта тестами.
 */
object MoodFusion {

    /** Максимальный вес мнения модели в профиле настроений (при уверенности 100). */
    const val MAX_AI_WEIGHT = 0.45

    /** Процентный профиль из списка настроений модели: первое главное. */
    fun aiProfile(moods: Collection<String>): Map<String, Int> {
        val list = moods.filter { it in MusicTags.moods }.distinct().take(3)
        if (list.isEmpty()) return emptyMap()
        val weights = when (list.size) { 1 -> listOf(100); 2 -> listOf(62, 38); else -> listOf(50, 30, 20) }
        return list.zip(weights).toMap()
    }

    /** Смесь двух профилей; результат нормирован к сумме 100. */
    fun blend(local: Map<String, Int>, ai: Map<String, Int>, aiWeight: Double): Map<String, Int> {
        if (ai.isEmpty()) return local
        if (local.isEmpty()) return ai
        val w = aiWeight.coerceIn(0.0, 1.0)
        val raw = MusicTags.moods.keys.associateWith { key ->
            (local[key] ?: 0) * (1 - w) + (ai[key] ?: 0) * w
        }.filterValues { it > 0.0 }
        val sum = raw.values.sum().takeIf { it > 0 } ?: return local
        val scaled = raw.mapValues { (it.value * 100 / sum).roundToInt() }.filterValues { it > 0 }
        return scaled.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    /** Главное и заметные дополнительные настроения: как в разметке по звуку (не меньше 12 баллов и 40% от главного). */
    fun topMoods(scores: Map<String, Int>): Set<String> {
        val ordered = scores.entries.sortedByDescending { it.value }
        val top = ordered.firstOrNull()?.value ?: return emptySet()
        return ordered.take(3).filterIndexed { i, e -> i == 0 || (e.value >= 12 && e.value >= top * 0.4) }.map { it.key }.toCollection(LinkedHashSet())
    }

    /**
     * [audio] — теги по звуку (могут быть без измерений, если файл не прочитался), [ai] — ответ модели.
     * Ручные правки сюда не попадают: их вызывающий код не трогает.
     */
    fun fuse(audio: TrackTags, ai: TrackTags): TrackTags {
        if (!audio.hasAudio) {
            // Звук не измерен: берём модель как есть, сохраняя лайк и подписи.
            return ai.copy(
                liked = audio.liked, artist = audio.artist, title = audio.title, album = audio.album,
                bpm = audio.bpm, webTried = true, aiTried = true, audioTried = audio.audioTried
            )
        }
        val aiWeight = MAX_AI_WEIGHT * ai.confidence.coerceIn(0, 100) / 100.0
        val scores = blend(audio.moodScores, aiProfile(ai.moods), aiWeight)
        val moods = topMoods(scores).ifEmpty { audio.moods }
        // Жанр: модель, если уверена; иначе жанр из тега файла; иначе то, что сказала модель.
        val genre = when {
            ai.genre != "other" && ai.confidence >= 45 -> ai.genre
            audio.genre != "other" -> audio.genre
            else -> ai.genre
        }
        val genreLabel = if (genre == ai.genre && ai.genreLabel.isNotBlank()) ai.genreLabel else audio.genreLabel
        // Энергия по звуку точнее, модель лишь слегка сдвигает.
        val energy = (audio.energy * 0.75 + ai.energy * 0.25).roundToInt().coerceIn(1, 5)
        val confidence = (audio.confidence * 0.6 + ai.confidence * 0.4).roundToInt().coerceIn(1, 100)
        return audio.copy(
            genre = genre, genreLabel = genreLabel, mood = moods.first(), moods = moods, moodScores = scores,
            energy = energy, confidence = maxOf(confidence, audio.confidence),
            description = ai.description.ifBlank { audio.description },
            webTried = true, aiTried = true
        )
    }
}
