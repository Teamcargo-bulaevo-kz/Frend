package com.example.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Offline Russian speech recognition with Sherpa-ONNX. No Android SpeechRecognizer or Google
 * speech UI is invoked. First start downloads the Russian model once; later recognition is offline.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.assistant.wake.STOP"
        private const val CHANNEL_ID = "assistant_wake_word"
        private const val NOTIFICATION_ID = 2002
        private const val PREFS = "assistant_prefs"
        private const val KEY = "wake_word"
        private const val COMMAND_WINDOW_MS = 10_000L

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
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (_: Exception) {
                // Android may reject a background microphone start; the next foreground app launch retries.
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var engine: SherpaWakeWordEngine? = null
    private var awaitingUntil = 0L
    private var stopped = false
    private var processingCommand = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            return START_NOT_STICKY
        }
        if (!hasMicPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Слушаю «Джарвис»", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 4, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Слушаю «Джарвис»")
            .setContentText("Локальное распознавание речи; первая загрузка модели может занять время")
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
            val localEngine = SherpaWakeWordEngine(applicationContext)
            engine = localEngine
            localEngine.start(
                onText = { text -> handler.post { if (!stopped) handleText(text) } },
                onError = { message ->
                    handler.post {
                        if (!stopped) {
                            val n = Notification.Builder(this, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setContentTitle("«Джарвис»: ошибка запуска")
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

    private fun handleText(text: String) {
        if (text.isBlank()) return
        // Ignore ambient speech while an assistant command is being processed.
        if (processingCommand) return

        val command = WakeWord.commandAfter(text)
        if (command != null) {
            if (command.isNotBlank()) {
                runCommand(command)
            } else {
                awaitingUntil = System.currentTimeMillis() + COMMAND_WINDOW_MS
                Speaker.speak(this, "Слушаю")
            }
            return
        }

        if (System.currentTimeMillis() < awaitingUntil) {
            awaitingUntil = 0L
            runCommand(text)
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
        super.onDestroy()
    }
}
