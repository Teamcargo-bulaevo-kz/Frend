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
 * Сам размечает музыку: жанр из ID3, настроения по акустике, энергичность и темп.
 * 1) читает таблицу Музыка.xlsx, если она уже есть (после переустановки);
 * 2) декодирует аудио и оценивает акустические признаки локальным алгоритмом;
 * интернет и внешние модели не нужны.
 * Идёт в фоне (служба с уведомлением), делает паузы при лимитах и в конце присылает уведомление.
 */
object MusicTagger {

    const val PROGRESS_CHANNEL = "music_tagging"
    const val DONE_CHANNEL = "music_tagging_done"
    const val PROGRESS_NOTIFICATION_ID = 2003
    private const val DONE_NOTIFICATION_ID = 2004
    private const val PREFS = "music_tagger"
    private const val KEY_LAST_AUTO = "last_auto"

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
        val serverPending = if (ServerMusicAnalyzer.isConfigured()) serverPendingCount(app, music) else 0
        if (untagged == 0 && serverPending == 0) {
            return "Все ${music.size} треков уже размечены и проверены доступными анализаторами. " +
                "Чтобы выполнить анализ заново, скажи «разметь музыку заново»."
        }
        startRun(app)
        val what = when {
            effective == Mode.REDO -> "Размечаю заново (твои правки и оценки сохранены)"
            effective == Mode.RETRY -> "Повторяю неопознанные"
            untagged == 0 -> "Уточняю уже размеченные песни через серверный ИИ"
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
            val music = MusicLibrary.index(app).allMusic()
            if (music.isEmpty()) return@launch
            val untagged = untaggedCount(app, music)
            val serverPending = if (ServerMusicAnalyzer.isConfigured()) serverPendingCount(app, music) else 0
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
            val tags = MusicTags.get(context, t)
            if (!MusicTags.isTagged(tags) && tags?.aiTried != true) untagged++
        }
        return untagged
    }

    private fun serverPendingCount(context: Context, music: List<MusicTrack>): Int =
        music.distinctBy { MusicTags.trackKey(it) }.count { track ->
            val tags = MusicTags.get(context, track)
            tags?.edited != true && tags?.webTried != true
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
        phaseServer(context, music)
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
        val todo = music.distinctBy { MusicTags.trackKey(it) }.filter {
            val tags = MusicTags.get(context, it)
            !MusicTags.isTagged(tags) && tags?.aiTried != true
        }
        phase = "Анализирую звук локально"
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
            TrackTags(
                genre = genre, mood = primary, energy = result.energy, moods = moods,
                genreLabel = genreText, tempo = tempo, description = result.description,
                confidence = result.confidence, source = TrackTags.SOURCE_LOCAL,
                liked = old?.liked ?: 0, artist = track.artist, title = track.title,
                album = track.album, moodScores = result.scores, segments = result.segments,
                bpm = result.bpm, valence = result.valence, arousal = result.arousal,
                tension = result.tension, danceability = result.danceability
            )
        } catch (e: Exception) {
            lastError = e.message?.take(80) ?: "не удалось прочитать один из файлов"
            (old ?: TrackTags(
                genre = "other", mood = "calm", energy = 3, confidence = 0,
                artist = track.artist, title = track.title, album = track.album
            )).copy(aiTried = true)
        }
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
