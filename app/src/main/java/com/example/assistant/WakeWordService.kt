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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Слушает микрофон и откликается на слово «Джарвис»: «Джарвис, включи музыку» выполняется сразу,
 * просто «Джарвис» — ассистент отвечает «Слушаю» и ждёт команду.
 * Работает через системное распознавание речи, поэтому нужен интернет и расходует заряд.
 * Запускается только из открытого приложения (Android не даёт включать микрофон из фона).
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.assistant.wake.STOP"
        private const val CHANNEL_ID = "assistant_wake_word"
        private const val NOTIFICATION_ID = 2002
        private const val PREFS = "assistant_prefs"
        private const val KEY = "wake_word"
        private const val COMMAND_WINDOW_MS = 10_000L

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
            if (value) start(context) else context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Запускает службу, если режим включён и есть разрешение на микрофон. Вызывать из открытого приложения. */
        fun start(context: Context) {
            if (!enabled(context) || !hasMicPermission(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                // Android не разрешил запуск из фона — запустится при следующем открытии приложения
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var awaitingUntil = 0L
    private var stopped = false

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
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Слушаю «Джарвис»", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 4, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Слушаю «Джарвис»")
            .setContentText("Скажи «Джарвис» и команду")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Выкл", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        stopped = false
        listen()
        return START_STICKY
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }.orEmpty()
            handle(texts)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> setEnabled(this@WakeWordService, false)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(600)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(2000)
                else -> restart(4000)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun listen() {
        if (stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        // Распознаватель создаём один раз и переиспользуем: пересоздавать его каждые полсекунды тяжело для телефона
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        r.cancel()
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
        )
    }

    private fun restart(delayMs: Long) {
        if (stopped) return
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun handle(texts: List<String>) {
        if (texts.isEmpty()) return restart(600)

        // «Джарвис, включи музыку» или просто «Джарвис»
        for (t in texts) {
            val command = WakeWord.commandAfter(t) ?: continue
            if (command.isNotBlank()) run(command) else {
                awaitingUntil = System.currentTimeMillis() + COMMAND_WINDOW_MS
                Speaker.speak(this, "Слушаю")
                restart(1200)
            }
            return
        }
        // После «Джарвис» ассистент ждёт команду
        if (System.currentTimeMillis() < awaitingUntil) {
            awaitingUntil = 0
            return run(texts.first())
        }
        restart(600)
    }

    private fun run(command: String) {
        AssistantRunner.process(this, command) { answer ->
            AssistantService.showResult(applicationContext, command, answer)
            restart(2500)
        }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
