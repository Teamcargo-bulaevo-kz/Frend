package com.example.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Сколько раз песню включали, дослушивали и пропускали. Только на телефоне (`listen_stats.json`).
 * Из этого «Моя волна» понимает вкус без лайков: дослушанные песни притягивают похожие, пропущенные — отталкивают.
 */
object ListenStats {
    private const val FILE_NAME = "listen_stats.json"

    /** Пропуск: выключил раньше 30 секунд и раньше трети песни. Дослушал: больше 80% или до конца. */
    const val SKIP_MAX_MS = 30_000L
    const val COMPLETE_SHARE = 0.8

    @Volatile private var cache: Map<String, TrackStats>? = null
    private var dirty = 0

    enum class Outcome { SKIP, COMPLETE, PARTIAL }

    fun outcome(listenedMs: Long, durationMs: Long, finished: Boolean): Outcome = when {
        finished -> Outcome.COMPLETE
        durationMs > 0 && listenedMs >= durationMs * COMPLETE_SHARE -> Outcome.COMPLETE
        listenedMs < SKIP_MAX_MS && (durationMs <= 0 || listenedMs < durationMs / 3) -> Outcome.SKIP
        else -> Outcome.PARTIAL
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun load(context: Context): Map<String, TrackStats> {
        cache?.let { return it }
        val map = LinkedHashMap<String, TrackStats>()
        try {
            val f = file(context)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                for (key in root.keys()) {
                    val o = root.getJSONObject(key)
                    map[key] = TrackStats(o.optInt("p"), o.optInt("c"), o.optInt("s"), o.optLong("t"), o.optLong("ms"))
                }
            }
        } catch (_: Exception) {
            // повреждённый файл: начнём заново
        }
        cache = map
        return map
    }

    fun all(context: Context): Map<String, TrackStats> = cache ?: load(context)

    fun get(context: Context, track: MusicTrack): TrackStats? = all(context)[MusicTags.trackKey(track)]

    /** Записывает итог прослушивания. Короткие касания (меньше 3 секунд при переключении подряд) учитываются как пропуск. */
    @Synchronized
    fun record(context: Context, track: MusicTrack, listenedMs: Long, finished: Boolean): Outcome {
        val result = outcome(listenedMs, track.durationMs, finished)
        val key = MusicTags.trackKey(track)
        val map = LinkedHashMap(all(context))
        val old = map[key] ?: TrackStats()
        map[key] = old.copy(
            plays = old.plays + if (result == Outcome.SKIP) 0 else 1,
            completes = old.completes + if (result == Outcome.COMPLETE) 1 else 0,
            skips = old.skips + if (result == Outcome.SKIP) 1 else 0,
            lastPlayedMs = System.currentTimeMillis(),
            listenedMs = old.listenedMs + listenedMs.coerceAtLeast(0)
        )
        cache = map
        // Пишем на диск не на каждое событие, а пачками (и всегда при дослушивании).
        if (++dirty >= 3 || result == Outcome.COMPLETE) flush(context)
        return result
    }

    @Synchronized
    fun flush(context: Context) {
        val map = cache ?: return
        dirty = 0
        try {
            val root = JSONObject()
            for ((k, v) in map) root.put(k, JSONObject().put("p", v.plays).put("c", v.completes).put("s", v.skips).put("t", v.lastPlayedMs).put("ms", v.listenedMs))
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) { f.writeText(root.toString()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear(context: Context) {
        cache = emptyMap()
        file(context).delete()
    }

    /** Все песни с разметкой и статистикой — вход для [MusicRecommender]. Блокирующий. */
    fun items(context: Context, tracks: List<MusicTrack> = MusicLibrary.index(context).allMusic()): List<RecItem> {
        val stats = all(context)
        val tags = MusicTags.all(context)
        return tracks.distinctBy { MusicTags.trackKey(it) }.map { t ->
            val key = MusicTags.trackKey(t)
            RecItem(t, tags[key], stats[key])
        }
    }
}
