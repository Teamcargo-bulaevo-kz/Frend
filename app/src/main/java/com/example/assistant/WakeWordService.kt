package com.example.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Offline Russian speech recognition with Sherpa-ONNX. No Android SpeechRecognizer or Google
 * speech UI is invoked. First start downloads the Russian model once; later recognition is offline.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.assistant.wake.STOP"
        const val ACTION_TEST = "com.example.assistant.wake.TEST"
        const val ACTION_PAUSE = "com.example.assistant.wake.PAUSE"
        const val ACTION_RESUME = "com.example.assistant.wake.RESUME"
        const val ACTION_CANCEL = "com.example.assistant.wake.CANCEL"
        private const val CHANNEL_ID = "assistant_wake_word"
        private const val NOTIFICATION_ID = 2002
        private const val PREFS = "assistant_prefs"
        private const val KEY = "wake_word"
        private const val KEY_PAUSED = "wake_word_paused"
        private const val COMMAND_WINDOW_MS = 10_000L
        private const val TAG = "WakeWordService"

        fun enabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY, value)
                .putBoolean(KEY_PAUSED, false)
                .apply()
            if (value) start(context) else context.stopService(Intent(context, WakeWordService::class.java))
        }

        /**
         * Temporarily releases AudioRecord so the system/Google speech recognizer can own the mic.
         * The user's wake-word preference is intentionally left enabled; MainActivity restarts the
         * service when the system voice UI closes and the activity returns to the foreground.
         */
        fun pauseForSystemVoiceInput(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!enabled(context) || prefs.getBoolean(KEY_PAUSED, false) || !hasMicPermission(context)) return
            startService(context, Intent(context, WakeWordService::class.java))
        }

        fun test(context: Context) {
            if (!hasMicPermission(context)) return
            startService(context, Intent(context, WakeWordService::class.java).setAction(ACTION_TEST))
        }

        private fun startService(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось запустить сервис фонового прослушивания", e)
                Toast.makeText(
                    context.applicationContext,
                    "Не удалось запустить прослушивание: ${e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var engine: SherpaWakeWordEngine? = null
    private var awaitingUntil = 0L
    private var stopped = false
    private var processingCommand = false
    @Volatile private var listeningCuePlayed = false
    @Volatile private var diagnosticMode = false
    @Volatile private var paused = false
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val releaseFocusRunnable = Runnable { abandonAssistantAudioFocus() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            paused = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, true).apply()
            awaitingUntil = 0L
            listeningCuePlayed = false
            engine?.stop()
            engine = null
            try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (_: Exception) {}
            abandonAssistantAudioFocus()
        } else if (intent?.action == ACTION_RESUME) {
            paused = false
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, false).apply()
            stopped = false
        } else if (intent?.action == ACTION_CANCEL) {
            awaitingUntil = 0L
            listeningCuePlayed = false
            processingCommand = false
            AssistantRunner.cancel()
            Speaker.stop()
            handler.removeCallbacks(releaseFocusRunnable)
            abandonAssistantAudioFocus()
            playCue(listening = false)
        }
        if (intent?.action == ACTION_TEST) diagnosticMode = true
        paused = paused || getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PAUSED, false)
        if (!hasMicPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Слушаю «Пятница»", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildWakeNotification(
            if (paused) "«Пятница» на паузе" else if (diagnosticMode) "Тест микрофона: слушаю" else "Слушаю «Пятница»",
            if (paused) "Микрофон отключён. Нажми «Продолжить», чтобы возобновить." else if (diagnosticMode) "Скажи любую фразу. Результат появится здесь." else "Ожидаю ключевое слово. Музыка приглушается после активации."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (paused) return START_STICKY

        if (engine == null) {
            stopped = false
            acquireCpuWakeLock()
            val localEngine = SherpaWakeWordEngine(applicationContext)
            engine = localEngine
            localEngine.start(
                onText = { text -> handler.post { if (!stopped) handleText(text) } },
                onStatus = { message ->
                    handler.post {
                        if (!stopped) {
                            manager.notify(NOTIFICATION_ID, buildWakeNotification(
                                if (paused) "«Пятница» на паузе" else if (diagnosticMode) "Тест фонового микрофона" else "Слушаю «Пятница»",
                                if (paused) "Микрофон отключён." else message.take(180)
                            ))
                        }
                    }
                },
                onPartial = { text ->
                    handler.post {
                        if (stopped || paused || text.isBlank()) return@post
                        if (diagnosticMode) updateDiagnosticNotification("Распознаю: ${text.take(150)}")
                        // Give immediate feedback as soon as an interim hypothesis contains the wake word.
                        if (!diagnosticMode && !listeningCuePlayed && WakeWord.commandAfter(text) != null) {
                            listeningCuePlayed = true
                            acquireAssistantAudioFocus()
                            playCue(listening = true)
                        }
                    }
                },
                onAudioLevel = { level ->
                    handler.post {
                        if (!stopped && diagnosticMode) {
                            updateDiagnosticNotification("Микрофон получает звук: $level%")
                        }
                    }
                },
                onError = { message ->
                    handler.post {
                        if (!stopped) {
                            // Clear the failed engine so a later service start can retry.
                            engine = null
                            Log.e(TAG, "Ошибка движка распознавания: $message")
                            val n = Notification.Builder(this, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setContentTitle("«Пятница»: ошибка запуска")
                                .setContentText(message.take(180))
                                .setOngoing(true)
                                .addAction(
                                    Notification.Action.Builder(
                                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                                        "Выкл", stopIntent
                                    ).build()
                                ).build()
                            manager.notify(NOTIFICATION_ID, n)
                        }
                    }
                }
            )
        }
        return START_STICKY
    }

    private fun acquireCpuWakeLock() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (cpuWakeLock == null) {
                cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:WakeWord")
                    .apply { setReferenceCounted(false) }
            }
            if (cpuWakeLock?.isHeld != true) cpuWakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось удерживать CPU для фонового распознавания", e)
        }
    }

    private fun updateDiagnosticNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildWakeNotification("Тест фонового микрофона", message.take(180)))
    }

    private fun action(title: String, icon: Int, code: Int, action: String): Notification.Action {
        val pending = PendingIntent.getService(
            this, code, Intent(this, WakeWordService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending).build()
    }

    private fun buildWakeNotification(title: String, message: String, ongoing: Boolean = true): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(message.take(180))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(action(if (paused) "Продолжить" else "Пауза",
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                5, if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(action("Отмена", android.R.drawable.ic_menu_close_clear_cancel, 6, ACTION_CANCEL))
            .addAction(action("Выкл", android.R.drawable.ic_menu_close_clear_cancel, 4, ACTION_STOP))
            .build()

    private fun acquireAssistantAudioFocus() {
        try {
            handler.removeCallbacks(releaseFocusRunnable)
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = manager
            if (focusRequest == null) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .setWillPauseWhenDucked(true)
                    .build()
            }
            manager.requestAudioFocus(focusRequest!!)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось приглушить другие звуки", e)
        }
    }

    private fun abandonAssistantAudioFocus() {
        try {
            val request = focusRequest
            val manager = audioManager
            if (request != null && manager != null) manager.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось вернуть звуковой фокус", e)
        }
    }

    private fun handleText(text: String) {
        if (stopped || paused || text.isBlank()) return
        if (diagnosticMode) {
            diagnosticMode = false
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val stopIntent = PendingIntent.getService(
                this, 4, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val result = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Тест распознавания: УСПЕХ")
                .setContentText("Телефон услышал: ${text.take(180)}")
                .setOngoing(enabled(this))
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Выкл", stopIntent
                    ).build()
                )
                .build()
            manager.notify(NOTIFICATION_ID, result)
            if (enabled(this)) {
                handler.postDelayed({
                    if (!stopped) {
                        val normal = Notification.Builder(this, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                            .setContentTitle("Слушаю «Пятница»")
                            .setContentText("Распознавание работает. Ожидаю ключевое слово.")
                            .setOngoing(true)
                            .addAction(
                                Notification.Action.Builder(
                                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Выкл", stopIntent
                                ).build()
                            ).build()
                        manager.notify(NOTIFICATION_ID, normal)
                    }
                }, 4000L)
            } else {
                handler.postDelayed({ stopSelf() }, 1500L)
            }
            return
        }
        // Ignore ambient speech while an assistant command is being processed.
        if (processingCommand) return

        val command = WakeWord.commandAfter(text)
        if (command != null) {
            acquireAssistantAudioFocus()
            if (command.isNotBlank()) {
                // If interim recognition did not catch the keyword, still confirm before acting.
                if (!listeningCuePlayed) playCue(listening = true)
                listeningCuePlayed = false
                playCue(listening = false)
                handler.postDelayed({ if (!stopped && !paused) runCommand(command) }, 180L)
            } else {
                acquireAssistantAudioFocus()
                awaitingUntil = System.currentTimeMillis() + COMMAND_WINDOW_MS
                if (!listeningCuePlayed) playCue(listening = true)
                listeningCuePlayed = true
                Speaker.speak(this, "Слушаю")
                val expectedEnd = awaitingUntil
                handler.postDelayed({
                    if (!stopped && awaitingUntil == expectedEnd) {
                        awaitingUntil = 0L
                        listeningCuePlayed = false
                        abandonAssistantAudioFocus()
                    }
                }, COMMAND_WINDOW_MS)
            }
            return
        }

        if (System.currentTimeMillis() < awaitingUntil) {
            awaitingUntil = 0L
            listeningCuePlayed = false
            acquireAssistantAudioFocus()
            playCue(listening = false)
            handler.postDelayed({ if (!stopped && !paused) runCommand(text) }, 180L)
        }
    }

    /**
     * Short audible acknowledgement. A high ToneGenerator volume makes it clearer than
     * relying on TTS alone; actual loudness still follows the phone's media volume.
     */
    private fun playCue(listening: Boolean) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            val type = if (listening) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_PROMPT
            tone.startTone(type, if (listening) 180 else 260)
            handler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 450L)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось проиграть звуковой сигнал", e)
        }
    }

    private fun runCommand(command: String) {
        processingCommand = true
        AssistantRunner.process(this, command) { answer ->
            AssistantService.showResult(applicationContext, command, answer)
            handler.postDelayed({
                processingCommand = false
                handler.removeCallbacks(releaseFocusRunnable)
                handler.postDelayed(releaseFocusRunnable, 15_000L)
            }, 1800)
        }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        AssistantRunner.cancel()
        Speaker.stop()
        abandonAssistantAudioFocus()
        engine?.stop()
        engine = null
        try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (_: Exception) {}
        cpuWakeLock = null
        super.onDestroy()
    }
}
