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
    private var resumeOnFocusGain = false

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        session = MediaSession(this, "AssistantMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next(auto = false)
                override fun onSkipToPrevious() = previous()
                override fun onStop() = stopPlayback()
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
                queue = MusicQueue.tracks
                originalQueue = queue
                shuffleOn = false
                index = intent.getIntExtra(EXTRA_INDEX, 0)
                errorsInRow = 0
                playCurrent()
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> next(auto = false)
            ACTION_PREV -> previous()
            ACTION_STOP -> stopPlayback()
            ACTION_LIKE -> toggleLike()
        }
        if (queue.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
            stopPlayback()
            return
        }
        if (index !in queue.indices) index = 0
        releasePlayer()
        requestFocus()
        prepared = false
        wantPlay = true

        val track = queue[index]
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
                    if (wantPlay) it.start()
                    refreshState()
                }
            }
            mp.setOnCompletionListener {
                if (player === it) next(auto = true)
            }
            mp.setOnErrorListener { failed, _, _ ->
                if (player === failed) skipBroken()
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
            stopPlayback()
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
        if (auto && index >= queue.lastIndex && repeatMode != PlayerState.REPEAT_ALL) {
            stopPlayback()
            return
        }
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
        index = if (index <= 0) queue.lastIndex else index - 1
        playCurrent()
    }

    private fun pause() {
        resumeOnFocusGain = false
        wantPlay = false
        player?.let { if (prepared && it.isPlaying) it.pause() }
        refreshState()
    }

    private fun resume() {
        val current = player
        if (current == null) {
            if (queue.isNotEmpty()) playCurrent()
            return
        }
        wantPlay = true
        if (prepared && !current.isPlaying) {
            requestFocus()
            current.start()
        }
        refreshState()
    }

    private fun stopPlayback() {
        releasePlayer()
        abandonFocus()
        queue = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
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
        index = i
        errorsInRow = 0
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
        refreshState()
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
