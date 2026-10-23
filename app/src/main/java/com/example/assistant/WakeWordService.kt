package com.example.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
        private const val CHANNEL_ID = "assistant_wake_word"
        private const val NOTIFICATION_ID = 2002
        private const val PREFS = "assistant_prefs"
        private const val KEY = "wake_word"
        private const val COMMAND_WINDOW_MS = 10_000L
        private const val TAG = "WakeWordService"

        fun enabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
            if (value) start(context) else context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (!enabled(context) || !hasMicPermission(context)) return
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
    private var cpuWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST) diagnosticMode = true
        if (!hasMicPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Слушаю «Пятница»", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 4, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (diagnosticMode) "Тест микрофона: слушаю" else "Слушаю «Пятница»")
            .setContentText(if (diagnosticMode) "Скажи любую фразу. Результат появится здесь." else "Локальное распознавание речи; первая загрузка модели может занять время")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Выкл", stopIntent
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

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
                            val statusNotification = Notification.Builder(this, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                                .setContentTitle(if (diagnosticMode) "Тест фонового микрофона" else "Слушаю «Пятница»")
                                .setContentText(message.take(180))
                                .setOngoing(true)
                                .addAction(
                                    Notification.Action.Builder(
                                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                                        "Выкл", stopIntent
                                    ).build()
                                ).build()
                            manager.notify(NOTIFICATION_ID, statusNotification)
                        }
                    }
                },
                onPartial = { text ->
                    handler.post {
                        if (stopped || text.isBlank()) return@post
                        if (diagnosticMode) updateDiagnosticNotification("Распознаю: ${text.take(150)}")
                        // Give immediate feedback as soon as an interim hypothesis contains the wake word.
                        if (!diagnosticMode && !listeningCuePlayed && WakeWord.commandAfter(text) != null) {
                            listeningCuePlayed = true
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
        val stopIntent = PendingIntent.getService(
            this, 4, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Тест фонового микрофона")
            .setContentText(message.take(180))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Выкл", stopIntent
                ).build()
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun handleText(text: String) {
        if (text.isBlank()) return
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
            if (command.isNotBlank()) {
                // If interim recognition did not catch the keyword, still confirm before acting.
                if (!listeningCuePlayed) playCue(listening = true)
                listeningCuePlayed = false
                playCue(listening = false)
                handler.postDelayed({ if (!stopped) runCommand(command) }, 180L)
            } else {
                awaitingUntil = System.currentTimeMillis() + COMMAND_WINDOW_MS
                listeningCuePlayed = true
                playCue(listening = true)
                Speaker.speak(this, "Слушаю")
            }
            return
        }

        if (System.currentTimeMillis() < awaitingUntil) {
            awaitingUntil = 0L
            listeningCuePlayed = false
            playCue(listening = false)
            handler.postDelayed({ if (!stopped) runCommand(text) }, 180L)
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
            handler.postDelayed({ processingCommand = false }, 1800)
        }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (_: Exception) {}
        cpuWakeLock = null
        super.onDestroy()
    }
}
