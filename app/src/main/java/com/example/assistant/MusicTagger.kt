package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сам размечает музыку: жанр, настроения, энергичность, темп, описание.
 * 1) читает таблицу Музыка.xlsx, если она уже есть (после переустановки);
 * 2) просит модель оценить неразмеченные песни пачками;
 * 3) песни, в которых модель не уверена, уточняет поиском в интернете небольшими группами.
 * Идёт в фоне (служба с уведомлением), делает паузы при лимитах и в конце присылает уведомление.
 */
object MusicTagger {

    private const val BATCH = 40
    private const val WEB_BATCH = 4
    private const val WEB_BELOW = 50
    const val PROGRESS_CHANNEL = "music_tagging"
    const val DONE_CHANNEL = "music_tagging_done"
    const val PROGRESS_NOTIFICATION_ID = 2003
    private const val DONE_NOTIFICATION_ID = 2004
    private const val PREFS = "music_tagger"
    private const val KEY_LAST_AUTO = "last_auto"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processing = AtomicBoolean(false)

    @Volatile var running = false
        private set
    @Volatile var phase = ""
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun progressText(): String = when {
        running && total > 0 -> "$phase: $done из $total"
        running -> "$phase…"
        lastError != null -> "Разметка остановилась: $lastError"
        else -> "Разметка не идёт"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- запуск

    /** Запуск по просьбе пользователя. Возвращает текст для ответа. */
    fun start(context: Context, redo: Boolean = false): String {
        if (running) return progressText()
        val app = context.applicationContext
        val music = MusicLibrary.index(app, force = true).allMusic()
        if (music.isEmpty()) return "Не вижу музыку на телефоне. Разреши доступ к аудио."
        if (redo) MusicTags.clear(app)
        prefs(app).edit().remove(KEY_BLOCKED_UNTIL).apply()
        val (untagged, weak) = workCounts(app, music)
        if (untagged == 0 && weak == 0) return "Вся музыка уже размечена (${music.size} треков)."
        startRun(app)
        return "Начал разметку: новых песен $untagged" + (if (weak > 0) ", ещё $weak уточню через интернет" else "") +
            ". Идёт в фоне, можно пользоваться телефоном. Когда закончу, пришлю уведомление."
    }

    /**
     * Автозапуск при открытии приложения «Музыка»: сам подхватывает таблицу и размечает, что осталось.
     * Не чаще раза в 10 минут и не раньше, чем кончится пауза после лимита Groq.
     */
    fun autoStart(context: Context) {
        if (running) return
        val app = context.applicationContext
        scope.launch {
            try { MusicCatalog.syncOnStart(app) } catch (e: Exception) { /* нет доступа к папке */ }
            val p = prefs(app)
            val now = System.currentTimeMillis()
            if (now < p.getLong(KEY_BLOCKED_UNTIL, 0L)) return@launch
            if (now - p.getLong(KEY_LAST_AUTO, 0L) < 10 * 60_000L) return@launch
            val music = MusicLibrary.index(app).allMusic()
            if (music.isEmpty()) return@launch
            val (untagged, weak) = workCounts(app, music)
            if (untagged == 0 && weak == 0) {
                // Таблица должна существовать и после полной разметки
                if (!MusicCatalog.file().exists() && MusicTags.count(app) > 0) MusicCatalog.scheduleExport(app)
                return@launch
            }
            p.edit().putLong(KEY_LAST_AUTO, now).apply()
            startRun(app)
        }
    }

    private fun startRun(app: Context) {
        if (!processing.compareAndSet(false, true)) return
        running = true
        phase = "Начинаю"
        done = 0
        total = 0
        lastError = null
        try {
            ContextCompat.startForegroundService(app, Intent(app, MusicTagService::class.java))
        } catch (e: Exception) {
            // Система не разрешила службу (приложение в фоне): работаем без неё, уведомление всё равно покажем
            scope.launch { runLocked(app) }
        }
    }

    /** Сколько песен ещё без разметки и сколько размечено неуверенно и ждёт интернета. */
    fun workCounts(context: Context, music: List<MusicTrack>): Pair<Int, Int> {
        val seen = HashSet<String>()
        var untagged = 0
        var weak = 0
        for (t in music) {
            val key = MusicTags.trackKey(t)
            if (!seen.add(key)) continue
            val tags = MusicTags.get(context, t)
            if (!MusicTags.isTagged(tags)) {
                if (tags?.aiTried == true) { if (!tags.webTried) weak++ } else untagged++
            } else if (tags != null && !tags.edited && !tags.webTried && tags.confidence < WEB_BELOW) weak++
        }
        return untagged to weak
    }

    // ---------------------------------------------------------------- работа

    /** Вызывается из MusicTagService (флаг processing уже взят в launch). */
    suspend fun runLocked(context: Context) {
        try {
            runPhases(context)
        } catch (e: Exception) {
            lastError = e.message?.take(100) ?: "ошибка"
        } finally {
            running = false
            processing.set(false)
            notifyDone(context)
        }
    }

    private class Stop(val reason: String) : Exception(reason)

    private suspend fun runPhases(context: Context) {
        val client = GroqClient(BuildConfig.GROQ_API_KEY)
        try { MusicCatalog.syncOnStart(context) } catch (e: Exception) { /* нет доступа к папке */ }
        val music = MusicLibrary.index(context, force = true).allMusic()
        try {
            phaseAi(context, client, music)
            phaseWeb(context, client, music)
        } catch (e: Stop) {
            lastError = e.reason
        }
        // Таблицу пишем в конце сразу, а не через задержку
        try { MusicCatalog.exportNow(context) } catch (e: Exception) { /* нет доступа к папке */ }
    }

    private suspend fun phaseAi(context: Context, client: GroqClient, music: List<MusicTrack>) {
        val todo = music.distinctBy { MusicTags.trackKey(it) }.filter {
            val tg = MusicTags.get(context, it)
            !MusicTags.isTagged(tg) && tg?.aiTried != true
        }
        phase = "Определяю настроение и жанр"
        total = todo.size
        done = 0
        notifyProgress(context)
        var failures = 0
        for (batch in todo.chunked(BATCH)) {
            val result = try {
                withRetry { askModel(client, context, batch) }
            } catch (e: Stop) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError = e.message?.take(80)
                if (failures >= 3) throw Stop("не получается связаться с моделью: ${e.message?.take(60)}")
                null
            }
            if (result != null) {
                failures = 0
                val tags = HashMap<String, TrackTags>()
                for ((n, tg) in result) {
                    val track = batch.getOrNull(n - 1) ?: continue
                    val old = MusicTags.get(context, track)
                    tags[MusicTags.trackKey(track)] = tg.copy(
                        artist = track.artist, title = track.title, album = track.album,
                        liked = old?.liked ?: 0
                    )
                }
                // Модель пропустила песню: помечаем, что ИИ пробовал, и оставляем интернету
                for (track in batch) {
                    val key = MusicTags.trackKey(track)
                    if (key !in tags && !MusicTags.isTagged(MusicTags.get(context, track))) {
                        val old = MusicTags.get(context, track)
                        tags[key] = (old ?: TrackTags("other", "calm", 3, confidence = 0, artist = track.artist, title = track.title, album = track.album))
                            .copy(aiTried = true)
                    }
                }
                if (tags.isNotEmpty()) MusicTags.putAll(context, tags)
            }
            done += batch.size
            notifyProgress(context)
            delay(2_000)
        }
    }

    private suspend fun phaseWeb(context: Context, client: GroqClient, music: List<MusicTrack>) {
        val weak = music.distinctBy { MusicTags.trackKey(it) }.filter {
            val tg = MusicTags.get(context, it)
            tg != null && !tg.edited && !tg.webTried &&
                ((MusicTags.isTagged(tg) && tg.confidence < WEB_BELOW) || (!MusicTags.isTagged(tg) && tg.aiTried))
        }
        if (weak.isEmpty()) return
        phase = "Уточняю через интернет"
        total = weak.size
        done = 0
        notifyProgress(context)
        var failures = 0
        for (group in weak.chunked(WEB_BATCH)) {
            val result = try {
                withRetry { askWeb(client, group) }
            } catch (e: Stop) {
                // Лимит на сегодня: то, что уже размечено моделью, остаётся, остальное уточним позже
                throw e
            } catch (e: Exception) {
                failures++
                if (failures >= 3) throw Stop("поиск в интернете недоступен: ${e.message?.take(60)}")
                null
            }
            val updates = HashMap<String, TrackTags>()
            for ((i, track) in group.withIndex()) {
                val key = MusicTags.trackKey(track)
                val old = MusicTags.get(context, track) ?: continue
                val found = result?.get(i + 1)
                updates[key] = if (found != null && found.confidence >= old.confidence) {
                    found.copy(
                        artist = old.artist, title = old.title, album = old.album, liked = old.liked, note = old.note,
                        webTried = true, source = TrackTags.SOURCE_WEB,
                        genreLabel = found.genreLabel.ifBlank { old.genreLabel }
                    )
                } else {
                    old.copy(webTried = true)
                }
            }
            if (result != null) failures = 0
            if (updates.isNotEmpty() && result != null) MusicTags.putAll(context, updates)
            done += group.size
            notifyProgress(context)
            delay(4_000)
        }
    }

    /** Повторяет запрос при лимите: ждёт столько, сколько просит сервер. Слишком долгое ожидание (суточный лимит) — остановка. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: GroqApiException) {
                if (e.code != 429 && e.code != 503) throw e
                val wait = retryDelayMs(e.message.orEmpty()) ?: (20_000L * attempt)
                if (wait > 6 * 60_000L) throw Stop("лимит Groq на сегодня исчерпан, продолжу позже")
                if (attempt >= 5) throw Stop("лимит запросов Groq, продолжу позже")
                delay(wait + 1_000)
            }
        }
    }

    /** «Please try again in 1m12.5s», «in 6.3s», «in 250ms» → миллисекунды. */
    fun retryDelayMs(message: String): Long? {
        val m = Regex("try again in ((?:\\d+h)?(?:\\d+m(?!s))?(?:\\d+(?:\\.\\d+)?s)?(?:\\d+(?:\\.\\d+)?ms)?)").find(message) ?: return null
        val text = m.groupValues[1]
        if (text.isEmpty()) return null
        var ms = 0.0
        Regex("(\\d+)h").find(text)?.let { ms += it.groupValues[1].toDouble() * 3_600_000 }
        Regex("(\\d+)m(?!s)").find(text)?.let { ms += it.groupValues[1].toDouble() * 60_000 }
        Regex("(\\d+(?:\\.\\d+)?)s").find(text)?.let { ms += it.groupValues[1].toDouble() * 1_000 }
        Regex("(\\d+(?:\\.\\d+)?)ms").find(text)?.let { ms += it.groupValues[1].toDouble() }
        return if (ms > 0) ms.toLong() else null
    }

    // ---------------------------------------------------------------- запросы к модели

    private val junkAlbums = setOf("download", "downloads", "whatsapp audio", "песни", "music", "telegram", "audio", "unknown", "<unknown>")

    private fun describe(context: Context, t: MusicTrack, withGenre: Boolean): String {
        val artist = t.artist.ifBlank { "?" }
        val album = t.album.takeIf { it.isNotBlank() && it.lowercase() !in junkAlbums && !it.contains('.') }.orEmpty()
        val genre = if (withGenre) embeddedGenre(context, t) else ""
        return "$artist|${MusicTags.cleanTitle(t.title)}|$album|$genre"
    }

    private fun formatSpec() =
        "Для каждой песни верни объект {\"i\":номер,\"g\":жанр из [${MusicTags.genres.keys.joinToString(",")}]," +
            "\"gl\":\"жанр по-русски в 1–3 словах, например Латино-поп\"," +
            "\"m\":[от одного до двух настроений из [${MusicTags.moods.keys.joinToString(",")}]]," +
            "\"e\":энергичность от 1 (очень спокойно) до 5 (очень энергично),\"t\":\"slow|medium|fast\"," +
            "\"d\":\"описание до 10 слов по-русски\",\"c\":уверенность от 0 до 100}."

    private suspend fun askModel(client: GroqClient, context: Context, batch: List<MusicTrack>): Map<Int, TrackTags> {
        val lines = batch.mapIndexed { i, t -> "${i + 1}|${describe(context, t, true)}" }
        val system = "Ты музыкальный эксперт. Тебе дают песни в формате номер|исполнитель|название|папка|жанр из тега файла (поля могут быть пустыми). " +
            formatSpec() + " Если песню не знаешь, оцени по названию, исполнителю и жанру из тега и поставь c ниже 40, ничего не выдумывай. " +
            "Ответь только JSON-массивом, по одному объекту на каждую песню, без пояснений."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", lines.joinToString("\n")))
        val result = client.sendConversation(messages, null, effort = "low", maxTokens = 6000)
        val text = (result as? GroqResult.FinalText)?.text ?: return emptyMap()
        return MusicTags.parseResponse(text, TrackTags.SOURCE_AI).filterKeys { it in 1..batch.size }
    }

    private suspend fun askWeb(client: GroqClient, group: List<MusicTrack>): Map<Int, TrackTags> {
        val lines = group.mapIndexed { i, t -> "${i + 1}|${t.artist.ifBlank { "?" }}|${MusicTags.cleanTitle(t.title)}" }
        val system = "Найди в интернете (Last.fm, Spotify, Genius, Яндекс Музыка, Shazam) жанр, настроение и темп каждой песни из списка " +
            "в формате номер|исполнитель|название. " + formatSpec() + " Если песню не нашёл, поставь c ниже 30. " +
            "Сначала поищи, потом ответь: после слова «ИТОГ:» только JSON-массив, без пояснений."
        val text = client.webAsk(system, lines.joinToString("\n"), 4000)
        return MusicTags.parseResponse(text, TrackTags.SOURCE_WEB).filterKeys { it in 1..group.size }
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

    // ---------------------------------------------------------------- уведомления

    private fun manager(context: Context): NotificationManager {
        val m = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "Разметка музыки", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Музыка размечена", NotificationManager.IMPORTANCE_DEFAULT))
        return m
    }

    private fun openMusic(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MusicActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Уведомление службы: служба показывает его при старте, а дальше здесь же обновляется прогресс. */
    fun progressNotification(context: Context): Notification {
        manager(context)
        val b = Notification.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Разбираю твою музыку")
            .setContentText(progressText())
            .setContentIntent(openMusic(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (total > 0) b.setProgress(total, done.coerceAtMost(total), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyProgress(context: Context) {
        manager(context).notify(PROGRESS_NOTIFICATION_ID, progressNotification(context))
    }

    private fun notifyDone(context: Context) {
        val m = manager(context)
        m.cancel(PROGRESS_NOTIFICATION_ID)
        val music = try { MusicLibrary.index(context).allMusic() } catch (e: Exception) { emptyList() }
        val tagged = music.count { MusicTags.isTagged(MusicTags.get(context, it)) }
        val review = music.count { val t = MusicTags.get(context, it); t != null && MusicTags.isTagged(t) && t.needsReview }
        val error = lastError
        val title = if (error == null) "Музыка размечена" else "Разметка приостановлена"
        val text = buildString {
            append("Размечено $tagged из ${music.size}.")
            if (review > 0) append(" Проверь $review песен, где я не уверен.")
            if (error != null) append(" Причина: $error.")
        }
        if (error != null) {
            // Ошибка или лимит: не пытаемся автоматически минут 40, чтобы не стучаться впустую
            prefs(context).edit().putLong(KEY_BLOCKED_UNTIL, System.currentTimeMillis() + 40 * 60_000L).apply()
        }
        val n = Notification.Builder(context, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openMusic(context))
            .setAutoCancel(true)
            .build()
        m.notify(DONE_NOTIFICATION_ID, n)
    }
}
