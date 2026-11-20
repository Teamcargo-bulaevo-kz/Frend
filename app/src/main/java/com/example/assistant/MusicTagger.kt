package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сам размечает музыку в два этапа:
 * 1) читает таблицу Музыка.xlsx, если она уже есть (после переустановки);
 * 2) декодирует аудио и измеряет темп, энергию и профиль настроений прямо на телефоне;
 * 3) уточняет жанр и характер песни по названию и исполнителю: серверный анализатор, если он настроен,
 *    иначе модель Groq (тот же ключ, что у ассистента). Результаты сводятся в [MoodFusion].
 * Без интернета работает этап 2, этап 3 продолжится позже.
 * Идёт в фоне (служба с уведомлением), делает паузы при лимитах и в конце присылает уведомление.
 */
object MusicTagger {

    const val PROGRESS_CHANNEL = "music_tagging"
    const val DONE_CHANNEL = "music_tagging_done"
    const val PROGRESS_NOTIFICATION_ID = 2003
    private const val DONE_NOTIFICATION_ID = 2004
    private const val PREFS = "music_tagger"
    private const val KEY_LAST_AUTO = "last_auto"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"
    private const val AI_BATCH = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processing = AtomicBoolean(false)
    private val playbackGate = kotlinx.coroutines.sync.Mutex()

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

    enum class Mode { NEW, RETRY, REDO }

    /**
     * Запуск по просьбе пользователя. NEW — только новые песни (если новых нет, сам повторяет те, что ИИ раньше не смог определить),
     * RETRY — повторить неопознанные, REDO — разметить всё заново (ручные правки и оценки сохраняются).
     */
    fun start(context: Context, mode: Mode = Mode.NEW): String {
        if (running) return progressText()
        val app = context.applicationContext
        val music = MusicLibrary.index(app, force = true).allMusic()
        if (music.isEmpty()) return "Не вижу музыку на телефоне. Разреши доступ к аудио."
        var effective = mode
        if (mode == Mode.REDO) {
            MusicTags.resetAuto(app)
            // Таблица Музыка.xlsx перезаписывается пустой, иначе старая разметка подхватилась бы обратно
            try { MusicCatalog.exportNow(app) } catch (e: Exception) { /* нет доступа к папке */ }
        }
        if (mode == Mode.NEW && untaggedCount(app, music) == 0 && MusicTags.resetFailed(app) > 0) effective = Mode.RETRY
        if (mode == Mode.RETRY) MusicTags.resetFailed(app)
        val untagged = untaggedCount(app, music)
        prefs(app).edit().remove(KEY_BLOCKED_UNTIL).apply()
        val serverPending = metaPendingCount(app, music)
        if (untagged == 0 && serverPending == 0) {
            return "Все ${music.size} треков уже размечены и проверены доступными анализаторами. " +
                "Чтобы выполнить анализ заново, скажи «разметь музыку заново»."
        }
        startRun(app)
        val what = when {
            effective == Mode.REDO -> "Размечаю заново (твои правки и оценки сохранены)"
            effective == Mode.RETRY -> "Повторяю неопознанные"
            untagged == 0 -> "Уточняю жанры и настроение по названиям через ИИ"
            else -> "Начал разметку"
        }
        val workCount = if (untagged > 0) untagged else serverPending
        return "$what: песен $workCount. Идёт в фоне, можно пользоваться телефоном. Когда закончу, пришлю уведомление."
    }

    /**
     * Автозапуск при открытии приложения «Музыка»: сам подхватывает таблицу и размечает, что осталось.
     * Не чаще раза в 10 минут.
     */
    fun autoStart(context: Context) {
        if (running) return
        val app = context.applicationContext
        scope.launch {
            try { MusicCatalog.syncOnStart(app) } catch (e: Exception) { /* нет доступа к папке */ }
            val p = prefs(app)
            val now = System.currentTimeMillis()
            if (now - p.getLong(KEY_LAST_AUTO, 0L) < 10 * 60_000L) return@launch
            val blocked = now < p.getLong(KEY_BLOCKED_UNTIL, 0L)
            val music = MusicLibrary.index(app).allMusic()
            if (music.isEmpty()) return@launch
            val untagged = untaggedCount(app, music)
            val serverPending = if (blocked) 0 else metaPendingCount(app, music)
            if (untagged == 0 && serverPending == 0) {
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

    /** Сколько песен ещё без разметки (и которые ИИ ещё не пробовал определить). */
    fun untaggedCount(context: Context, music: List<MusicTrack>): Int {
        val seen = HashSet<String>()
        var untagged = 0
        for (t in music) {
            if (!seen.add(MusicTags.trackKey(t))) continue
            if (needsAudio(MusicTags.get(context, t))) untagged++
        }
        return untagged
    }

    /** Звук ещё не измеряли: нет разметки или есть только мнение ИИ по названию (старые теги Frend). */
    fun needsAudio(tags: TrackTags?): Boolean =
        tags == null || (!tags.edited && !tags.hasAudio && !tags.audioTried)

    /** Нужно ли уточнение по метаданным: не правилось руками и ещё не уточнялось. */
    private fun needsMeta(tags: TrackTags?): Boolean =
        tags?.edited != true && tags?.webTried != true &&
            tags?.source != TrackTags.SOURCE_IMPORT && tags?.source != TrackTags.SOURCE_AI

    private fun groqAvailable() = BuildConfig.GROQ_API_KEY.isNotBlank()

    private fun metaPendingCount(context: Context, music: List<MusicTrack>): Int {
        if (!ServerMusicAnalyzer.isConfigured() && !groqAvailable()) return 0
        return music.distinctBy { MusicTags.trackKey(it) }.count { needsMeta(MusicTags.get(context, it)) }
    }

    // ---------------------------------------------------------------- работа

    /** Вызывается из MusicTagService (флаг processing уже взят в launch). */
    suspend fun runLocked(context: Context) {
        try {
            runPhases(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            lastError = "остановлено системой, продолжится при следующем запуске"
            throw e
        } catch (e: Exception) {
            lastError = e.message?.take(100) ?: "ошибка"
        } finally {
            running = false
            processing.set(false)
            notifyDone(context)
        }
    }

    private suspend fun runPhases(context: Context) {
        try { MusicCatalog.syncOnStart(context) } catch (_: Exception) {}
        val music = MusicLibrary.index(context, force = true).allMusic()
        phaseLocal(context, music)
        if (ServerMusicAnalyzer.isConfigured()) phaseServer(context, music)
        else if (groqAvailable() && System.currentTimeMillis() >= prefs(context).getLong(KEY_BLOCKED_UNTIL, 0L)) {
            try { phaseGroq(context, music) } catch (e: Stop) {
                lastError = e.reason
                val pause = if (e.reason.contains("сегодня")) 6 * 3_600_000L else 40 * 60_000L
                prefs(context).edit().putLong(KEY_BLOCKED_UNTIL, System.currentTimeMillis() + pause).apply()
            }
        }
        try { MusicCatalog.exportNow(context) } catch (_: Exception) {}
    }

    /**
     * Enriches the complete library in small batches using public web metadata.
     * If no server URL is configured or the network is unavailable, local analysis remains intact.
     */
    private suspend fun phaseServer(context: Context, music: List<MusicTrack>) {
        if (!ServerMusicAnalyzer.isConfigured()) {
            phase = "Локальный анализ завершён; серверный ИИ не подключён"
            total = 0
            done = 0
            notifyProgress(context)
            return
        }
        val candidates = music.distinctBy { MusicTags.trackKey(it) }.filter {
            val tags = MusicTags.get(context, it)
            tags?.edited != true && tags?.webTried != true
        }
        if (candidates.isEmpty()) return
        phase = "Уточняю настроение и жанры через интернет"
        total = candidates.size
        done = 0
        notifyProgress(context)
        for (batch in candidates.chunked(10)) {
            try {
                val enriched = ServerMusicAnalyzer.analyze(context, batch)
                val updates = LinkedHashMap(enriched)
                for (track in batch) {
                    val key = MusicTags.trackKey(track)
                    if (key in updates) continue
                    val current = MusicTags.get(context, track)
                    if (current?.edited == true) continue
                    updates[key] = (current ?: TrackTags(
                        genre = "other", mood = "calm", energy = 3, confidence = 0,
                        source = TrackTags.SOURCE_WEB, artist = track.artist, title = track.title, album = track.album
                    )).copy(webTried = true, aiTried = true)
                }
                if (updates.isNotEmpty()) MusicTags.putAll(context, updates)
            } catch (e: Exception) {
                lastError = "серверный анализ: " + (e.message?.take(90) ?: "ошибка сети")
                break
            }
            done = (done + batch.size).coerceAtMost(total)
            notifyProgress(context)
            kotlinx.coroutines.yield()
        }
    }

    /** Офлайн-разметка: декодируем до четырёх минут аудио с пониженной частотой дискретизации и считаем распределение настроений. */
    private suspend fun phaseLocal(context: Context, music: List<MusicTrack>) {
        val todo = music.distinctBy { MusicTags.trackKey(it) }.filter { needsAudio(MusicTags.get(context, it)) }
        if (todo.isEmpty()) return
        phase = "Слушаю песни: темп, энергия, настроение"
        total = todo.size
        done = 0
        notifyProgress(context)
        // Несколько песен анализируются одновременно; число потоков в настройках (по умолчанию по ядрам процессора)
        val workers = MusicSettings.analysisWorkers.takeIf { it in 1..4 }
            ?: (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        PlayLog.add("разметка: ${todo.size} песен, потоков $workers")
        val sem = kotlinx.coroutines.sync.Semaphore(workers)
        val lock = Any()
        val updates = LinkedHashMap<String, TrackTags>()
        try {
            kotlinx.coroutines.coroutineScope {
                todo.map { track ->
                    async {
                        sem.withPermit {
                            // фоновый приоритет, чтобы музыка и экран не подтормаживали
                            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) {}
                            val key = MusicTags.trackKey(track)
                            // пока играет музыка, декодеров должно быть как можно меньше: параллельно анализируем только на паузе
                            val tags = if (MusicService.isRunning && PlayerState.playing) playbackGate.withLock { analyzeOne(context, track) }
                            else analyzeOne(context, track)
                            synchronized(lock) {
                                updates[key] = tags
                                done++
                                if (updates.size >= 5 || done == todo.size) {
                                    MusicTags.putAll(context, updates.toMap())
                                    updates.clear()
                                    notifyProgress(context)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            // при остановке сохраняем то, что уже посчитано
            synchronized(lock) { if (updates.isNotEmpty()) MusicTags.putAll(context, updates.toMap()) }
        }
    }

    /** Разметка одной песни. Не бросает исключений: при ошибке песня помечается как попробованная. */
    private fun analyzeOne(context: Context, track: MusicTrack): TrackTags {
        val old = MusicTags.get(context, track)
        return try {
            val result = LocalMoodAnalyzer.analyze(context, track.uri)
            val ordered = result.scores.entries.sortedByDescending { it.value }
            // Второй и третий теги только если они заметны: не меньше 12 баллов и не меньше 40% от главного.
            val topScore = ordered.firstOrNull()?.value ?: 0
            val moods = ordered.take(3).filterIndexed { i, e -> i == 0 || (e.value >= 12 && e.value >= topScore * 0.4) }.map { it.key }.toSet()
            val primary = ordered.firstOrNull()?.key ?: "calm"
            val genreText = embeddedGenre(context, track)
            val genre = if (genreText.isBlank()) "other" else MusicTags.genreKeyFromLabel(genreText)
            val tempo = when {
                result.bpm in 1..89 -> MusicTags.tempos["slow"].orEmpty()
                result.bpm in 90..119 -> MusicTags.tempos["medium"].orEmpty()
                result.bpm >= 120 -> MusicTags.tempos["fast"].orEmpty()
                else -> ""
            }
            val audio = TrackTags(
                genre = genre, mood = primary, energy = result.energy, moods = moods,
                genreLabel = genreText, tempo = tempo, description = result.description,
                confidence = result.confidence, source = TrackTags.SOURCE_LOCAL,
                liked = old?.liked ?: 0, artist = track.artist, title = track.title,
                album = track.album, moodScores = result.scores, segments = result.segments,
                bpm = result.bpm, valence = result.valence, arousal = result.arousal,
                tension = result.tension, danceability = result.danceability, audioTried = true
            )
            // Уже есть мнение ИИ (старая разметка Frend или импорт): сводим его со звуком, а не выбрасываем
            val aiOpinion = old?.takeIf { !it.edited && it.confidence > 0 && it.source != TrackTags.SOURCE_LOCAL }
            if (aiOpinion != null) MoodFusion.fuse(audio, aiOpinion).copy(source = aiOpinion.source) else audio
        } catch (e: Exception) {
            lastError = e.message?.take(80) ?: "не удалось прочитать один из файлов"
            (old ?: TrackTags(
                genre = "other", mood = "calm", energy = 3, confidence = 0,
                artist = track.artist, title = track.title, album = track.album
            )).copy(audioTried = true)
        }
    }

    // ---------------------------------------------------------------- уточнение через Groq

    private class Stop(val reason: String) : Exception(reason)

    private val junkAlbums = setOf("download", "downloads", "whatsapp audio", "песни", "music", "telegram", "audio", "unknown", "<unknown>")

    /**
     * Модель получает название, исполнителя, альбом, жанр из тега и то, что уже измерено по звуку (темп, энергия),
     * и возвращает жанр, характер песни и описание. Результат сводится со звуком в [MoodFusion].
     */
    private suspend fun phaseGroq(context: Context, music: List<MusicTrack>) {
        val todo = music.distinctBy { MusicTags.trackKey(it) }.filter { needsMeta(MusicTags.get(context, it)) }
        if (todo.isEmpty()) return
        val client = GroqClient(BuildConfig.GROQ_API_KEY)
        phase = "Уточняю жанры и настроение через ИИ"
        total = todo.size
        done = 0
        notifyProgress(context)
        var failures = 0
        for (batch in todo.chunked(AI_BATCH)) {
            val answer = try {
                withRetry { askModel(client, context, batch) }
            } catch (e: Stop) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError = "ИИ: " + (e.message?.take(80) ?: "ошибка сети")
                if (failures >= 3) throw Stop("не получается связаться с моделью, продолжу позже")
                null
            }
            if (answer != null) {
                failures = 0
                val updates = LinkedHashMap<String, TrackTags>()
                for ((i, track) in batch.withIndex()) {
                    val key = MusicTags.trackKey(track)
                    val current = MusicTags.get(context, track)
                    if (current?.edited == true) continue
                    val base = current ?: TrackTags("other", "calm", 3, confidence = 0, artist = track.artist, title = track.title, album = track.album)
                    val ai = answer[i + 1]
                    updates[key] = if (ai == null) base.copy(webTried = true)
                    else MoodFusion.fuse(base, ai.copy(artist = track.artist, title = track.title, album = track.album))
                }
                if (updates.isNotEmpty()) MusicTags.putAll(context, updates)
            }
            done = (done + batch.size).coerceAtMost(total)
            notifyProgress(context)
            delay(1_500)
        }
    }

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

    private fun describe(context: Context, t: MusicTrack): String {
        val album = t.album.takeIf { it.isNotBlank() && it.lowercase() !in junkAlbums && !it.contains('.') }.orEmpty()
        val tags = MusicTags.get(context, t)
        val measured = if (tags?.hasAudio == true) "${tags.bpm.takeIf { it > 0 } ?: "?"} BPM, энергия ${tags.energy}/5" else ""
        return "${t.artist.ifBlank { "?" }}|${MusicTags.cleanTitle(t.title)}|$album|${embeddedGenre(context, t)}|$measured"
    }

    private suspend fun askModel(client: GroqClient, context: Context, batch: List<MusicTrack>): Map<Int, TrackTags> {
        val lines = batch.mapIndexed { i, t -> "${i + 1}|${describe(context, t)}" }
        val system = "Ты музыкальный эксперт. Тебе дают песни: номер|исполнитель|название|альбом|жанр из тега файла|измерено по звуку (поля могут быть пустыми). " +
            "Для каждой песни верни объект {\"i\":номер,\"g\":жанр из [${MusicTags.genres.keys.joinToString(",")}]," +
            "\"gl\":\"жанр по-русски в 1–3 словах, например Латино-поп\"," +
            "\"m\":[одно-два настроения из [${MusicTags.moods.keys.joinToString(",")}], первое главное; учитывай смысл текста песни, если её знаешь]," +
            "\"e\":энергичность 1–5,\"t\":\"slow|medium|fast\",\"d\":\"описание до 10 слов по-русски\",\"c\":уверенность 0–100}. " +
            "Если песню не знаешь, оцени по исполнителю, жанру тега и измерениям и поставь c ниже 40, ничего не выдумывай. " +
            "Ответь только JSON-массивом, по объекту на каждую песню, без пояснений."
        val messages = org.json.JSONArray()
            .put(org.json.JSONObject().put("role", "system").put("content", system))
            .put(org.json.JSONObject().put("role", "user").put("content", lines.joinToString("\n")))
        val result = client.sendConversation(messages, null, effort = "low", maxTokens = 5000)
        val text = (result as? GroqResult.FinalText)?.text ?: return emptyMap()
        return MusicTags.parseResponse(text, TrackTags.SOURCE_AI).filterKeys { it in 1..batch.size }
    }

    /** Жанр берётся из ID3, если он записан в самом аудиофайле. */
    private fun embeddedGenre(context: Context, track: MusicTrack): String {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty().take(40)
        } catch (_: Exception) {
            ""
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Разбор серверного Retry-After: используется также общими сетевыми ошибками ассистента. */
    fun retryDelayMs(message: String): Long? {
        val m = Regex("try again in ((?:\\d+h)?(?:\\d+m(?!s))?(?:\\d+(?:\\.\\d+)?s)?(?:\\d+(?:\\.\\d+)?ms)?)").find(message) ?: return null
        val value = m.groupValues[1]
        if (value.isEmpty()) return null
        var ms = 0.0
        Regex("(\\d+)h").find(value)?.let { ms += it.groupValues[1].toDouble() * 3_600_000 }
        Regex("(\\d+)m(?!s)").find(value)?.let { ms += it.groupValues[1].toDouble() * 60_000 }
        Regex("(\\d+(?:\\.\\d+)?)s").find(value)?.let { ms += it.groupValues[1].toDouble() * 1_000 }
        Regex("(\\d+(?:\\.\\d+)?)ms").find(value)?.let { ms += it.groupValues[1].toDouble() }
        return if (ms > 0) ms.toLong() else null
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
        val title = if (error == null || done >= total) "Музыка обработана" else "Разметка приостановлена"
        val text = buildString {
            append("Размечено $tagged из ${music.size}.")
            if (review > 0) append(" Проверь $review песен, где я не уверен.")
            if (error != null) append(" Причина: $error.")
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
