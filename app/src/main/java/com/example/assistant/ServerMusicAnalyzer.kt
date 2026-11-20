package com.example.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional server enrichment. Sends track metadata only; audio files never leave the device.
 * An empty endpoint disables the remote stage without affecting local analysis.
 */
object ServerMusicAnalyzer {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .callTimeout(110, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = BuildConfig.MUSIC_INTELLIGENCE_URL.startsWith("https://")

    suspend fun analyze(context: Context, tracks: List<MusicTrack>): Map<String, TrackTags> = withContext(Dispatchers.IO) {
        if (!isConfigured() || tracks.isEmpty()) return@withContext emptyMap()
        val endpoint = BuildConfig.MUSIC_INTELLIGENCE_URL.trimEnd('/') + "/v1/analyze"
        val payload = JSONObject().put("tracks", JSONArray().apply {
            tracks.forEach { track ->
                put(JSONObject()
                    .put("key", MusicTags.trackKey(track))
                    .put("title", track.title.take(180))
                    .put("artist", track.artist.take(180))
                    .put("album", track.album.take(180))
                    .put("durationMs", track.durationMs))
            }
        })
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        BuildConfig.MUSIC_INTELLIGENCE_TOKEN.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Сервер музыкального анализа: HTTP ${response.code}")
            val root = JSONObject(body)
            val array = root.optJSONArray("results") ?: return@withContext emptyMap()
            val tracksByKey = tracks.associateBy { MusicTags.trackKey(it) }
            val result = LinkedHashMap<String, TrackTags>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val key = item.optString("key")
                val track = tracksByKey[key] ?: continue
                val current = MusicTags.get(context, track)
                if (current?.edited == true) continue
                val matchConfidence = item.optInt("confidence", 0).coerceIn(0, 100)
                if (current != null && matchConfidence < 45) {
                    result[key] = current.copy(webTried = true, aiTried = true)
                    continue
                }
                val scoresObject = item.optJSONObject("moodScores") ?: continue
                val serverScores = LinkedHashMap<String, Int>()
                for (mood in MusicTags.moods.keys) {
                    serverScores[mood] = scoresObject.optInt(mood, 0).coerceIn(0, 100)
                }
                if (serverScores.values.sum() == 0) continue
                val localScores = current?.moodScores.orEmpty()
                val blended = LinkedHashMap<String, Int>()
                for (mood in MusicTags.moods.keys) {
                    val remote = serverScores[mood] ?: 0
                    val local = localScores[mood] ?: 0
                    blended[mood] = if (localScores.isEmpty()) remote else (remote * 65 + local * 35) / 100
                }
                val sum = blended.values.sum().coerceAtLeast(1)
                val normalized = blended.mapValues { (_, value) -> (value * 100f / sum).toInt() }.toMutableMap()
                val correction = 100 - normalized.values.sum()
                val primary = MusicTags.moods.keys.maxByOrNull { normalized[it] ?: 0 } ?: "calm"
                normalized[primary] = ((normalized[primary] ?: 0) + correction).coerceIn(0, 100)
                val mood = primary
                val serverGenre = item.optString("genre", "other").lowercase()
                val genre = serverGenre.takeIf { it in MusicTags.genres } ?: "other"
                result[key] = TrackTags(
                    genre = if (genre == "other" && current != null) current.genre else genre,
                    mood = mood,
                    energy = current?.energy ?: item.optInt("energy", 3).coerceIn(1, 5),
                    moods = normalized.entries.sortedByDescending { it.value }.take(3).map { it.key }.toSet(),
                    genreLabel = item.optString("genreLabel").ifBlank { current?.genreLabel.orEmpty() },
                    tempo = item.optString("tempo").ifBlank { current?.tempo.orEmpty() },
                    description = item.optString("description").ifBlank { current?.description.orEmpty() },
                    confidence = matchConfidence,
                    source = TrackTags.SOURCE_WEB,
                    liked = current?.liked ?: 0,
                    edited = false,
                    webTried = true,
                    aiTried = true,
                    artist = track.artist,
                    title = track.title,
                    album = track.album,
                    moodScores = normalized,
                    segments = current?.segments.orEmpty()
                )
            }
            result
        }
    }
}
