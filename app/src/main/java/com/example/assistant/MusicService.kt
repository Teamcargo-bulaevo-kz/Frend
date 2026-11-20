package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager

/**
 * Свой плеер ассистента: играет очередь из MusicQueue, управляется командами
 * (пауза, дальше, назад, стоп), кнопками в шторке и кнопками наушников.
 */
class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "com.example.assistant.music.PLAY"
        const val ACTION_PAUSE = "com.example.assistant.music.PAUSE"
        const val ACTION_RESUME = "com.example.assistant.music.RESUME"
        const val ACTION_NEXT = "com.example.assistant.music.NEXT"
        const val ACTION_PREV = "com.example.assistant.music.PREV"
        const val ACTION_STOP = "com.example.assistant.music.STOP"
        const val ACTION_LIKE = "com.example.assistant.music.LIKE"

        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_INDEX = "index"

        @Volatile var isRunning = false
            private set

        /** Живой экземпляр сервиса: экраны музыки (тот же процесс) вызывают его напрямую. */
        @Volatile var instance: MusicService? = null
            private set
    }

    private lateinit var audioManager: AudioManager
    private var session: MediaSession? = null
    private var player: MediaPlayer? = null

    /** Audio session активного трека для спектрального визуализатора экрана плеера. */
    val audioSessionId: Int get() = player?.audioSessionId ?: 0

    private var prepared = false
    private var wantPlay = true

    private var queue: List<MusicTrack> = emptyList()
    private var originalQueue: List<MusicTrack> = emptyList()
    private var shuffleOn = false
    private var repeatMode = PlayerState.REPEAT_OFF
    private val handler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable { pause(); PlayerState.sleepAtMs = 0L }
    private var index = 0
    private var errorsInRow = 0
    private var errorRetries = 0
    private var resumeAtMs = 0
    private var resumeOnFocusGain = false

    // Волна (Моя волна, настроение, похожие): очередь пополняется сама по мере игры
    private var wave: WaveSpec? = null
    private var waveSession: MusicRecommender.Session? = null
    @Volatile private var waveLoading = false
    // Прослушивание текущей песни уже записано в статистику
    private var listenRecorded = true
    private val waveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        PlayLog.add("звуковой фокус: ${focusName(change)}")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player?.isPlaying == true) {
                    resumeOnFocusGain = true
                    player?.pause()
                    refreshState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    player?.start()
                    refreshState()
                }
            }
        }
    }

    private fun focusName(c: Int) = when (c) {
        AudioManager.AUDIOFOCUS_LOSS -> "потерян насовсем (другое приложение захватило звук)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "потерян ненадолго"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "потерян, можно приглушить"
        AudioManager.AUDIOFOCUS_GAIN -> "возвращён"
        else -> "код $c"
    }

    /** Автостоп: если музыка долго на паузе, освобождаем плеер, чтобы системные кнопки ничего не включали сами. */
    private val idleStop = Runnable {
        if (player?.isPlaying != true) stopPlayback("пауза дольше 10 минут")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlayLog.add("приложение смахнули из недавних")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        PlayLog.add("системе не хватает памяти (уровень $level)")
        super.onTrimMemory(level)
    }

    /** Наушники или Bluetooth отключились: ставим на паузу, чтобы музыка не заиграла из динамика. */
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && MusicSettings.pauseOnUnplug && player?.isPlaying == true) {
                PlayLog.add("наушники или Bluetooth отключились: пауза")
                pause()
            }
        }
    }
    private var noisyRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        try {
            registerReceiver(noisyReceiver, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        } catch (_: Exception) {}
        session = MediaSession(this, "AssistantMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { PlayLog.add("команда Play от системы (наушники, Bluetooth, шторка)"); resume() }
                override fun onPause() { PlayLog.add("команда Pause от системы"); pause() }
                override fun onSkipToNext() { PlayLog.add("команда Next от системы"); next(auto = false) }
                override fun onSkipToPrevious() { PlayLog.add("команда Previous от системы"); previous() }
                override fun onStop() = stopPlayback("команда Stop от системы")
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_LIKE) toggleLike()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // startForegroundService обязывает сразу показать уведомление
                enterForeground()
                recordListen(finished = false)
                wave = MusicQueue.wave
                waveSession = if (wave != null) (MusicQueue.waveSession ?: MusicRecommender.Session()) else null
                MusicQueue.wave = null
                MusicQueue.waveSession = null
                PlayerState.wave = wave
                queue = MusicQueue.tracks
                originalQueue = queue
                shuffleOn = false
                index = intent.getIntExtra(EXTRA_INDEX, 0)
                errorsInRow = 0
                errorRetries = 0
                resumeAtMs = 0
                playCurrent()
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> next(auto = false)
            ACTION_PREV -> previous()
            ACTION_STOP -> stopPlayback("кнопка Стоп в уведомлении")
            ACTION_LIKE -> toggleLike()
        }
        if (queue.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PlayLog.add("служба музыки остановлена")
        recordListen(finished = false)
        ListenStats.flush(this)
        waveExecutor.shutdown()
        handler.removeCallbacks(idleStop)
        if (noisyRegistered) { try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}; noisyRegistered = false }
        releasePlayer()
        abandonFocus()
        handler.removeCallbacks(sleepRunnable)
        session?.release()
        session = null
        isRunning = false
        instance = null
        PlayerState.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- воспроизведение

    private fun playCurrent() {
        if (queue.isEmpty()) {
            stopPlayback("очередь пуста")
            return
        }
        if (index !in queue.indices) index = 0
        handler.removeCallbacks(idleStop)
        releasePlayer()
        requestFocus()
        prepared = false
        wantPlay = true

        val track = queue[index]
        PlayLog.add("играет: ${track.artist} - ${track.title} (${index + 1} из ${queue.size})")
        listenRecorded = false
        waveSession?.onPlayed(RecItem(track, null, null))
        if (wave != null && index >= queue.size - 3) extendWave()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, track.uri)
            mp.setOnPreparedListener {
                if (player === it) {
                    errorsInRow = 0
                    prepared = true
                    if (resumeAtMs > 0) { try { it.seekTo(resumeAtMs) } catch (_: Exception) {}; resumeAtMs = 0 }
                    if (wantPlay) it.start()
                    if (MusicSettings.auraEnabled || MusicSettings.listAura || MusicSettings.debugView) BeatSource.attach(it.audioSessionId)
                    EqualizerManager.attach(it.audioSessionId)
                    refreshState()
                }
            }
            mp.setOnCompletionListener {
                if (player === it) { PlayLog.add("трек закончился"); errorRetries = 0; recordListen(finished = true); next(auto = true) }
            }
            mp.setOnErrorListener { failed, what, extra ->
                PlayLog.add("ошибка плеера: код $what/$extra")
                if (player === failed) {
                    // Сбой во время игры (например, декодеру не хватило ресурсов): два раза пробуем продолжить с того же места,
                    // и только потом пропускаем песню. Так трек не перескакивает сам.
                    val pos = try { if (prepared) failed.currentPosition else 0 } catch (_: Exception) { 0 }
                    if (prepared && errorRetries < 2) {
                        errorRetries++
                        PlayLog.add("ошибка плеера: пробую продолжить с ${pos / 1000} с (попытка $errorRetries)")
                        val w = wantPlay
                        resumeAtMs = pos
                        playCurrent()
                        wantPlay = w
                    } else skipBroken()
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            mp.release()
            player = null
            skipBroken()
            return
        }
        refreshState()
    }

    /** Файл не открылся: пропускаем его, а если подряд ломаются все треки, останавливаемся. */
    private fun skipBroken() {
        errorsInRow++
        if (errorsInRow >= queue.size) {
            stopPlayback("подряд не открылись все треки")
        } else {
            next(auto = false)
        }
    }

    private fun next(auto: Boolean) {
        if (queue.isEmpty()) return
        if (auto && repeatMode == PlayerState.REPEAT_ONE) {
            playCurrent()
            return
        }
        if (!auto) recordListen(finished = false)
        if (auto && index >= queue.lastIndex && repeatMode != PlayerState.REPEAT_ALL) {
            // Волна не кончается: если пополнение не успело, добираем сразу
            if (wave != null && appendWaveNow()) { index++; playCurrent(); return }
            stopPlayback("очередь закончилась")
            return
        }
        if (wave != null && index >= queue.lastIndex && appendWaveNow()) { index++; playCurrent(); return }
        index = if (index >= queue.lastIndex) 0 else index + 1
        playCurrent()
    }

    private fun previous() {
        if (queue.isEmpty()) return
        val current = player
        if (current != null && prepared && current.currentPosition > 3000) {
            current.seekTo(0)
            refreshState()
            return
        }
        recordListen(finished = false)
        index = if (index <= 0) queue.lastIndex else index - 1
        playCurrent()
    }

    private fun pause() {
        resumeOnFocusGain = false
        wantPlay = false
        player?.let { if (prepared && it.isPlaying) it.pause() }
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, 10 * 60_000L)
        PlayLog.add("пауза")
        refreshState()
    }

    private fun resume() {
        // Воспроизведение разрешено только после явного ACTION_PLAY.
        // Системный MediaSession/кнопка Resume не должны создавать новый трек
        // или запускать случайную очередь после пересоздания службы.
        val current = player ?: return
        handler.removeCallbacks(idleStop)
        current.setVolume(1f, 1f) // после приглушения другим приложением громкость не должна остаться 20 %
        PlayLog.add("продолжить")
        if (!prepared) {
            wantPlay = true
            return
        }
        if (!current.isPlaying) {
            wantPlay = true
            requestFocus()
            current.start()
        }
        refreshState()
    }

    private fun stopPlayback(why: String = "команда") {
        PlayLog.add("остановка: $why")
        recordListen(finished = false)
        wave = null
        waveSession = null
        PlayerState.wave = null
        handler.removeCallbacks(idleStop)
        releasePlayer()
        abandonFocus()
        queue = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        BeatSource.detach()
        EqualizerManager.release()
        player?.let {
            try {
                it.setOnPreparedListener(null)
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)
                it.release()
            } catch (e: Exception) {
                // игнорируем: плеер и так выбрасывается
            }
        }
        player = null
    }

    // ---------------------------------------------------------------- статистика и волна

    /** Итог прослушивания текущей песни: дослушал, пропустил или частично. Один раз на включение. */
    private fun recordListen(finished: Boolean) {
        if (listenRecorded) return
        val track = queue.getOrNull(index) ?: return
        listenRecorded = true
        val pos = try { if (prepared) player?.currentPosition ?: 0 else 0 } catch (_: Exception) { 0 }
        val listened = if (finished) track.durationMs.coerceAtLeast(pos.toLong()) else pos.toLong()
        val app = applicationContext
        val session = waveSession
        waveExecutor.execute {
            try {
                val result = ListenStats.record(app, track, listened, finished)
                if (session != null && result != ListenStats.Outcome.PARTIAL) {
                    val item = RecItem(track, MusicTags.get(app, track), null)
                    handler.post { session.onFeedback(item, result == ListenStats.Outcome.COMPLETE) }
                }
            } catch (_: Exception) {}
        }
    }

    /** Пополняет волну в фоне: данные читаются в фоне, выбор делается на главном потоке. */
    private fun extendWave() {
        val spec = wave ?: return
        if (waveLoading) return
        waveLoading = true
        val app = applicationContext
        waveExecutor.execute {
            val items = try { ListenStats.items(app) } catch (_: Exception) { emptyList() }
            handler.post {
                waveLoading = false
                if (wave !== spec) return@post
                appendFrom(items)
            }
        }
    }

    /** Добирает волну сразу (очередь кончилась раньше, чем пришло фоновое пополнение). */
    private fun appendWaveNow(): Boolean {
        val items = try { ListenStats.items(applicationContext) } catch (_: Exception) { emptyList() }
        return appendFrom(items)
    }

    private fun appendFrom(items: List<RecItem>): Boolean {
        val spec = wave ?: return false
        val session = waveSession ?: MusicRecommender.Session().also { waveSession = it }
        // Всё, что уже в очереди, не повторяем
        queue.forEach { session.played.add(MusicTags.trackKey(it)) }
        var more = MusicRecommender.nextForWave(spec, items, session, 10)
        if (more.isEmpty() && session.played.size > 20) {
            // Подходящие песни кончились: начинаем круг заново, кроме последних 20
            val keep = session.played.toList().takeLast(20)
            session.played.clear(); session.played.addAll(keep)
            more = MusicRecommender.nextForWave(spec, items, session, 10)
        }
        if (more.isEmpty()) return false
        val tracks = more.map { it.track }
        queue = queue + tracks
        originalQueue = originalQueue + tracks
        PlayLog.add("волна: добавлено ${tracks.size}")
        refreshState()
        return true
    }

    /** Не прерывая текущую песню, превращает остаток очереди в волну (например, «Ещё такое» в плеере). */
    fun continueAsWave(spec: WaveSpec, upcoming: List<MusicTrack>) {
        val current = queue.getOrNull(index)
        queue = (if (current != null) queue.take(index + 1) else emptyList()) + upcoming.filter { it.id != current?.id }
        originalQueue = queue
        shuffleOn = false
        wave = spec
        waveSession = MusicRecommender.Session().also { s -> queue.forEach { s.played.add(MusicTags.trackKey(it)) } }
        PlayerState.wave = spec
        refreshState()
    }

    /** Текущая волна: название для экранов. */
    fun currentWave(): WaveSpec? = wave

    // ---------------------------------------------------------------- управление из экранов музыки

    fun seekTo(ms: Int) {
        val current = player ?: return
        if (prepared) {
            current.seekTo(ms.coerceIn(0, current.duration.coerceAtLeast(0)))
            refreshState()
        }
    }

    fun togglePlay() {
        if (player?.isPlaying == true) pause() else resume()
    }

    fun skipNext() = next(auto = false)

    fun skipPrevious() = previous()

    fun playIndex(i: Int) {
        if (i !in queue.indices) return
        if (i != index) recordListen(finished = false)
        index = i
        errorsInRow = 0
        errorRetries = 0
        resumeAtMs = 0
        playCurrent()
    }

    fun toggleShuffle() {
        val current = queue.getOrNull(index)
        if (!shuffleOn) {
            originalQueue = queue
            val rest = queue.filterIndexed { i, _ -> i != index }.shuffled()
            queue = if (current != null) listOf(current) + rest else rest
            index = 0
        } else {
            queue = originalQueue
            index = current?.let { c -> queue.indexOfFirst { it.id == c.id } }?.takeIf { it >= 0 } ?: 0
        }
        shuffleOn = !shuffleOn
        refreshState()
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        refreshState()
    }

    /** Добавляет трек в очередь: следующим или в конец. Если плеер не запущен, начинает с него. */
    fun enqueue(track: MusicTrack, playNext: Boolean) {
        val list = queue.toMutableList()
        val at = if (playNext) (index + 1).coerceAtMost(list.size) else list.size
        list.add(at, track)
        queue = list
        originalQueue = originalQueue + track
        refreshState()
    }

    /** Убирает трек из очереди (не текущий). */
    fun removeFromQueue(i: Int) {
        if (i !in queue.indices || i == index) return
        val list = queue.toMutableList()
        val removed = list.removeAt(i)
        queue = list
        originalQueue = originalQueue.filter { it.id != removed.id }
        if (i < index) index--
        refreshState()
    }

    /** Таймер сна: через minutes минут музыка встанет на паузу. 0 — отменить. */
    fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes > 0) {
            handler.postDelayed(sleepRunnable, minutes * 60_000L)
            PlayerState.sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
        } else {
            PlayerState.sleepAtMs = 0L
        }
    }

    // ---------------------------------------------------------------- звуковой фокус

    private fun requestFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    // ---------------------------------------------------------------- уведомление и сессия

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Музыка", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Сердечко в шторке и на экране блокировки: нравится / не нравится для играющей песни. */
    private fun toggleLike() {
        val track = queue.getOrNull(index) ?: return
        val now = MusicTags.likedOf(this, track)
        MusicTags.setLiked(this, track, if (now > 0) 0 else 1)
        if (now <= 0) waveSession?.onFeedback(RecItem(track, MusicTags.get(this, track), null), completed = true)
        refreshState()
    }

    /** Дизлайк в волне: песня пропускается, похожие больше не предлагаются. */
    fun dislikeCurrent() {
        val track = queue.getOrNull(index) ?: return
        MusicTags.setLiked(this, track, -1)
        waveSession?.onFeedback(RecItem(track, MusicTags.get(this, track), null), completed = false)
        listenRecorded = true
        next(auto = false)
    }

    private fun isLiked(): Boolean = queue.getOrNull(index)?.let { MusicTags.likedOf(this, it) > 0 } == true

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, MusicService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, title: String, action: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(action)).build()

    private fun buildNotification(): Notification {
        val track = queue.getOrNull(index)
        val playing = player?.isPlaying == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MusicActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setColor(Color.rgb(82, 190, 210))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(Notification.PRIORITY_LOW)
            .setContentTitle(track?.title ?: "Музыка")
            .setContentText(track?.artist.orEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Назад", ACTION_PREV))
            .addAction(
                if (playing) {
                    action(android.R.drawable.ic_media_pause, "Пауза", ACTION_PAUSE)
                } else {
                    action(android.R.drawable.ic_media_play, "Играть", ACTION_RESUME)
                }
            )
            .addAction(action(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT))
            .addAction(
                if (isLiked()) action(R.drawable.ic_heart_filled, "Убрать из любимых", ACTION_LIKE)
                else action(R.drawable.ic_heart, "Нравится", ACTION_LIKE)
            )
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", ACTION_STOP))
        session?.let {
            builder.setStyle(
                Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    /** Обновляет уведомление и состояние для кнопок наушников и экрана блокировки. */
    private fun refreshState() {
        val track = queue.getOrNull(index)
        val playing = player?.isPlaying == true
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artist ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (prepared) (player?.duration ?: 0).toLong() else 0L)
                .build()
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_LIKE,
                        if (isLiked()) "Убрать из любимых" else "Нравится",
                        if (isLiked()) R.drawable.ic_heart_filled else R.drawable.ic_heart
                    ).build()
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    player?.currentPosition?.toLong() ?: 0L,
                    1f
                )
                .build()
        )
        PlayerState.track = track
        PlayerState.playing = playing
        PlayerState.index = index
        PlayerState.queue = queue
        PlayerState.shuffle = shuffleOn
        PlayerState.repeat = repeatMode
        PlayerState.durationMs = if (prepared) (player?.duration ?: 0).coerceAtLeast(0) else 0
        PlayerState.positionProvider = { if (prepared) (player?.currentPosition ?: 0) else 0 }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
