package com.example.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Размечает музыку: жанр, настроение, энергичность. Берёт жанр из тегов файла, если он там есть,
 * и просит модель оценить пачками по 40 треков. Идёт в фоне, прогресс виден в уведомлении.
 */
object MusicTagger {

    private const val BATCH = 40
    private const val CHANNEL_ID = "music_tagging"
    private const val NOTIFICATION_ID = 2003

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var running = false
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun progressText(): String =
        if (running) "Размечаю музыку: $done из $total" else lastError?.let { "Разметка остановилась: $it" } ?: "Разметка не идёт"

    /** Запускает разметку неразмеченных треков (или всех, если redo). Возвращает текст для пользователя. */
    fun start(context: Context, redo: Boolean = false): String {
        if (running) return progressText()
        val app = context.applicationContext
        val music = MusicLibrary.index(app, force = true).allMusic()
        if (music.isEmpty()) return "Не вижу музыку на телефоне. Разреши доступ к аудио."
        if (redo) MusicTags.clear(app)
        val todo = music.filter { MusicTags.get(app, it) == null }
        if (todo.isEmpty()) return "Вся музыка уже размечена (${music.size} треков)."
        running = true
        done = 0
        total = todo.size
        lastError = null
        scope.launch { process(app, todo) }
        return "Начал разметку: ${todo.size} треков. Идёт в фоне, можно пользоваться телефоном. Прогресс в уведомлении."
    }

    private suspend fun process(context: Context, todo: List<MusicTrack>) {
        val client = GroqClient(BuildConfig.GROQ_API_KEY)
        var failures = 0
        try {
            for (batch in todo.chunked(BATCH)) {
                var result: Map<Int, TrackTags>? = null
                var attempt = 0
                while (result == null && attempt < 4) {
                    attempt++
                    try {
                        result = askModel(context, client, batch)
                        failures = 0
                    } catch (e: GroqApiException) {
                        // Лимит запросов Groq: ждём и пробуем снова
                        if (e.code == 429 && attempt < 4) delay(20_000L * attempt)
                        else { lastError = "ошибка ${e.code}"; failures++; break }
                    } catch (e: Exception) {
                        lastError = e.message?.take(80)
                        failures++
                        break
                    }
                }
                if (result != null) {
                    MusicTags.putAll(context, result.mapKeys { MusicTags.trackKey(batch[it.key - 1]) }.filterKeys { true })
                }
                done += batch.size
                notifyProgress(context, finished = false)
                if (failures >= 3) break
            }
        } finally {
            running = false
            notifyProgress(context, finished = true)
        }
    }

    private suspend fun askModel(context: Context, client: GroqClient, batch: List<MusicTrack>): Map<Int, TrackTags> {
        val lines = batch.mapIndexed { i, t -> "${i + 1}|${t.artist}|${t.title}|${embeddedGenre(context, t)}" }
        val system = "Ты музыкальный эксперт. Тебе дают список треков в формате номер|исполнитель|название|жанр из тега файла (может быть пустым). " +
            "Для каждого трека определи: g — жанр из [${MusicTags.genres.keys.joinToString(",")}]; " +
            "m — настроение из [${MusicTags.moods.keys.joinToString(",")}]; e — энергичность от 1 (очень спокойно) до 5 (очень энергично). " +
            "Если трек не знаешь, оцени по названию, исполнителю и жанру из тега. " +
            "Ответь только JSON-массивом вида [{\"i\":1,\"g\":\"pop\",\"m\":\"happy\",\"e\":4}] без пояснений: по одному объекту на каждый трек."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", lines.joinToString("\n")))
        val result = client.sendConversation(messages, null, effort = "low", maxTokens = 4000)
        val text = (result as? GroqResult.FinalText)?.text ?: return emptyMap()
        return MusicTags.parseResponse(text).filterKeys { it in 1..batch.size }
    }

    /** Жанр, записанный в самом файле (ID3). Часто пустой, но когда есть, сильно помогает модели. */
    private fun embeddedGenre(context: Context, track: MusicTrack): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty().take(40)
        } catch (e: Exception) {
            ""
        } finally {
            try { retriever.release() } catch (e: Exception) { /* уже освобождён */ }
        }
    }

    private fun notifyProgress(context: Context, finished: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Разметка музыки", NotificationManager.IMPORTANCE_LOW))
        val builder = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Разметка музыки")
            .setOnlyAlertOnce(true)
        if (finished) {
            val tagged = MusicTags.count(context)
            builder.setContentText(lastError?.let { "Остановилась: $it. Размечено $tagged треков" } ?: "Готово: размечено $tagged треков")
                .setOngoing(false).setAutoCancel(true)
        } else {
            builder.setContentText("$done из $total").setProgress(total, done, false).setOngoing(true)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
