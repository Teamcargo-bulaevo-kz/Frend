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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Слушает микрофон и откликается на слово активации («Джарвис» по умолчанию, меняется в настройках).
 *
 * Как это работает:
 *  1. Слово ловит офлайн-распознавание Vosk прямо в телефоне: без интернета, без звуков и окон Google.
 *     В тишине ничего не обрабатывается, поэтому заряд почти не расходуется.
 *  2. «Джарвис, включи музыку» выполняется сразу. Просто «Джарвис» — тихий сигнал и ждём команду.
 *  3. Саму команду точнее переводит в текст Whisper на Groq; если интернета нет, берётся текст Vosk.
 * Запускается только из открытого приложения (Android не даёт включать микрофон из фона).
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.assistant.wake.STOP"
        private const val CHANNEL_ID = "assistant_wake_word"
        private const val NOTIFICATION_ID = 2002
        private const val PROBLEM_ID = 2003
        private const val PREFS = "assistant_prefs"
        private const val KEY = "wake_word"
        private const val KEY_PHRASE = "wake_phrase"

        private const val RATE = 16000
        private const val CHUNK = 1600            // 0,1 секунды звука
        private const val HANG_IDLE = 8           // тишина, после которой фраза считается законченной (0,8 с)
        private const val HANG_AWAIT = 15         // при ожидании команды терпим паузы подольше (1,5 с)
        private const val MAX_CHUNKS = 200        // самая длинная фраза — 20 секунд
        private const val PRE_ROLL = 4            // сколько звука до начала речи сохранять, чтобы не обрезать первое слово
        private const val AWAIT_MS = 8_000L       // сколько ждать команду после слова активации
        private const val RESUME_AFTER_MS = 2_500L
        private const val RESUME_LIMIT_MS = 90_000L

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
            if (value) start(context) else context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Текущее слово активации (в нижнем регистре, без пробелов). */
        fun phrase(context: Context): String =
            WakeWord.clean(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHRASE, null))

        /** Для показа: «Джарвис». */
        fun phraseTitle(context: Context): String = phrase(context).replaceFirstChar { it.uppercase() }

        fun setPhrase(context: Context, value: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PHRASE, WakeWord.clean(value)).apply()
            if (enabled(context)) start(context) // служба обновит уведомление
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

    private enum class Mode { IDLE, AWAIT }

    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var resetRequested = false
    private var mode = Mode.IDLE
    private var awaitDeadline = 0L

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
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Слово активации", NotificationManager.IMPORTANCE_LOW))
        val notification = buildNotification("Скажи «${phraseTitle(this)}» и команду")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (worker?.isAlive != true) {
            running = true
            worker = Thread({ work() }, "wake-word").also { it.start() }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 4, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Слушаю «${phraseTitle(this)}»")
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Выкл", stop
                ).build()
            )
            .build()
    }

    private fun status(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Показывает причину, по которой слушание выключилось, и выключает его. */
    private fun problem(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            PROBLEM_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Слушание выключено")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        )
        handler.post { setEnabled(this, false) }
    }

    // ---------------------------------------------------------------- рабочий поток

    private fun work() {
        var model: Model? = null
        try {
            if (!VoskModelStore.ready(this)) {
                status("Скачиваю модель распознавания… 0%")
                var shown = -1
                VoskModelStore.download(this) { p ->
                    if (p / 5 != shown / 5) {
                        shown = p
                        status("Скачиваю модель распознавания… $p%")
                    }
                }
            }
            if (!running) return
            status("Загружаю модель…")
            model = Model(VoskModelStore.dir(this).absolutePath)
            status("Скажи «${phraseTitle(this)}» и команду")
            listenLoop(model)
        } catch (e: Throwable) {
            if (running) {
                val why = when (e) {
                    is java.io.IOException -> "Не удалось скачать модель распознавания. Проверь интернет и включи слушание снова."
                    is UnsatisfiedLinkError -> "Это устройство не поддерживает офлайн-распознавание."
                    else -> "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                }
                problem(why)
            }
        } finally {
            try { model?.close() } catch (_: Throwable) {}
        }
    }

    private fun listenLoop(model: Model) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 2 * 6)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            problem("Не удалось включить микрофон. Возможно, его занимает другое приложение.")
            return
        }
        val recognizer = Recognizer(model, RATE.toFloat())
        val buf = ShortArray(CHUNK)
        val utter = ArrayList<ShortArray>()
        val pre = ArrayDeque<ShortArray>()
        var hang = 0
        var noise = 150.0
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, CHUNK)
                if (n < 0) throw IllegalStateException("Микрофон перестал отвечать")
                if (n == 0) continue
                if (paused) continue
                if (resetRequested) {
                    resetRequested = false
                    recognizer.reset(); utter.clear(); pre.clear(); hang = 0
                    continue
                }
                val data = buf.copyOf(n)
                val level = WavEncoder.rms(data)
                val threshold = maxOf(350.0, noise * 2.5)
                val voice = level > threshold
                val limit = if (mode == Mode.AWAIT) HANG_AWAIT else HANG_IDLE
                if (voice) hang = limit

                if (voice || hang > 0) {
                    if (!voice) hang--
                    if (utter.isEmpty()) { utter.addAll(pre); pre.clear() }
                    utter.add(data)
                    val endpoint = recognizer.acceptWaveForm(data, n)
                    if (endpoint || utter.size >= MAX_CHUNKS || hang == 0) {
                        val text = textOf(if (endpoint) recognizer.result else recognizer.finalResult)
                        val chunks = ArrayList(utter)
                        utter.clear(); hang = 0
                        onUtterance(text, chunks, recognizer, record, buf)
                    }
                } else {
                    pre.addLast(data)
                    while (pre.size > PRE_ROLL) pre.removeFirst()
                    noise = noise * 0.97 + level * 0.03
                    if (mode == Mode.AWAIT && System.currentTimeMillis() > awaitDeadline) mode = Mode.IDLE
                }
            }
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
            try { recognizer.close() } catch (_: Throwable) {}
        }
    }

    private fun textOf(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (e: Exception) { "" }

    /** Закончилась фраза: решаем, что с ней делать. Работает в рабочем потоке. */
    private fun onUtterance(text: String, chunks: List<ShortArray>, recognizer: Recognizer, record: AudioRecord, buf: ShortArray) {
        val word = phrase(this)
        if (mode == Mode.AWAIT) {
            mode = Mode.IDLE
            val seconds = chunks.size / 10.0
            if (text.isBlank() && seconds < 1.0) return // шум, а не команда
            val command = best(chunks, text, word)
            if (command.isNotBlank()) dispatch(command)
            return
        }
        val quick = WakeWord.commandAfter(text, word) ?: return
        if (quick.isBlank()) {
            // Только слово активации: тихий сигнал и ждём команду
            beep(record, buf)
            recognizer.reset()
            mode = Mode.AWAIT
            awaitDeadline = System.currentTimeMillis() + AWAIT_MS
            return
        }
        // «Джарвис, включи музыку» одной фразой
        val command = best(chunks, text, word)
        dispatch(command.ifBlank { quick })
    }

    /** Лучший текст команды: Whisper (точнее), а без интернета — текст Vosk. Слово активации отрезается. */
    private fun best(chunks: List<ShortArray>, voskText: String, word: String): String {
        val fallback = (WakeWord.commandAfter(voskText, word) ?: voskText).trim()
        if (BuildConfig.GROQ_API_KEY.isBlank()) return fallback
        return try {
            val all = ShortArray(chunks.sumOf { it.size })
            var pos = 0
            for (c in chunks) { c.copyInto(all, pos); pos += c.size }
            val full = GroqClient(BuildConfig.GROQ_API_KEY).transcribe(
                WavEncoder.encode(all, RATE), "${phraseTitle(this)}, включи музыку."
            )
            if (full.isBlank()) fallback else (WakeWord.commandAfter(full, word) ?: full).trim().ifBlank { fallback }
        } catch (e: Exception) {
            fallback
        }
    }

    /** Короткий сигнал «слушаю». Звук сигнала успевает уйти из буфера микрофона до продолжения. */
    private fun beep(record: AudioRecord, buf: ShortArray) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Thread.sleep(260)
            tone.release()
        } catch (_: Throwable) {}
        repeat(5) { record.read(buf, 0, CHUNK) }
    }

    /** Выполняет команду в главном потоке; микрофон на это время отдыхает, чтобы не слышать ответ ассистента. */
    private fun dispatch(command: String) {
        paused = true
        val resume = Runnable { resetRequested = true; paused = false }
        handler.postDelayed(resume, RESUME_LIMIT_MS)
        handler.post {
            AssistantRunner.process(this, command) { answer ->
                AssistantService.showResult(applicationContext, command, answer)
                handler.removeCallbacks(resume)
                handler.postDelayed(resume, RESUME_AFTER_MS)
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        worker = null
        super.onDestroy()
    }
}
