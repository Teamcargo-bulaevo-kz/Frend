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
        /** Начать разговор: слушать без слова активации, пока пользователь не замолчит или не скажет «хватит». */
        const val ACTION_DIALOG = "com.example.assistant.wake.DIALOG"
        private const val CHANNEL_ID = "assistant_wake_word"
        private const val NOTIFICATION_ID = 2002
        private const val PREFS = "assistant_prefs"
        private const val KEY = "wake_word"
        private const val KEY_PAUSED = "wake_word_paused"
        private const val KEY_PHRASE = "wake_phrase"
        private const val DEFAULT_COMMAND_WINDOW_MS = 10_000L
        private const val WINDOW_WAKE = 0
        private const val WINDOW_CONFIRM = 1
        private const val WINDOW_DIALOG = 2
        private const val WINDOW_BARGE = 3

        private fun commandWindowMs(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("command_window_ms", DEFAULT_COMMAND_WINDOW_MS)
                .coerceIn(3_000L, 30_000L)
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

        /** Текущее слово активации (нижний регистр, без пробелов). */
        fun phrase(context: Context): String =
            WakeWord.clean(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHRASE, null))

        /** Для показа пользователю: «Пятница». */
        fun phraseTitle(context: Context): String = phrase(context).replaceFirstChar { it.uppercase() }

        /** Читает сохранённое слово и передаёт его распознавателю. Вызывать при запуске приложения и службы. */
        fun applyPhrase(context: Context) {
            WakeWord.target = phrase(context)
        }

        /** Сохраняет новое слово; работающая служба обновит уведомление. */
        fun setPhrase(context: Context, value: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PHRASE, WakeWord.clean(value)).apply()
            applyPhrase(context)
            start(context)
        }

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!enabled(context) || prefs.getBoolean(KEY_PAUSED, false) || !hasMicPermission(context)) return
            startService(context, Intent(context, WakeWordService::class.java))
        }

        /** Разговор из чата или плитки: работает, даже если фоновое слово активации выключено. */
        fun startDialog(context: Context) {
            if (!hasMicPermission(context)) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, false).apply()
            startService(context, Intent(context, WakeWordService::class.java).setAction(ACTION_DIALOG))
        }

        /** Идёт ли сейчас разговор (для экрана чата). */
        @Volatile var dialogActive = false
            private set

        private val dialogStartPhrases = setOf(
            "давай поговорим", "поговори со мной", "режим диалога", "включи режим диалога", "начнем диалог", "начнём диалог",
            "давай пообщаемся", "поболтаем", "давай поболтаем"
        )

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
    /** Начало команды, оборванной паузой: ждём продолжение (см. CommandAssembler). */
    private var pendingCommand = ""
    private var pendingParts = 0
    private var pendingGeneration = 0
    private val continuationTimeout = Runnable { onContinuationTimeout() }
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val releaseFocusRunnable = Runnable { abandonAssistantAudioFocus() }
    /** Номер команды: ответ старой команды не должен трогать состояние, если пользователь её перебил. */
    private var commandGeneration = 0
    private val dialogTimeout = Runnable { onDialogTimeout() }
    /** Разговор начат явно («давай поговорим», долгое нажатие на микрофон), а не окно после ответа. */
    private var explicitDialog = false
    /** Какое окно слушания открыто: после слова активации, вопрос «да/нет», реплика диалога, перебивание. */
    private var windowKind = WINDOW_WAKE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyPhrase(this)
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            paused = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, true).apply()
            awaitingUntil = 0L
            listeningCuePlayed = false
            clearPending()
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
            clearPending()
            AssistantRunner.cancel()
            Speaker.stop()
            handler.removeCallbacks(releaseFocusRunnable)
            abandonAssistantAudioFocus()
            playCue(listening = false)
        }
        if (intent?.action == ACTION_TEST) diagnosticMode = true
        if (intent?.action == ACTION_DIALOG) {
            paused = false
            stopped = false
            handler.post { beginDialog(greet = true) }
        }
        paused = paused || getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PAUSED, false)
        if (!hasMicPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Слово активации", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildWakeNotification(
            if (paused) "«${phraseTitle(this)}» на паузе" else if (diagnosticMode) "Тест микрофона: слушаю" else "Слушаю «${phraseTitle(this)}»",
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
            localEngine.bargeInEnabled = AppSettings.bool(this, AppSettings.BARGE_IN, true)
            engine = localEngine
            localEngine.start(
                onText = { text -> handler.post { if (!stopped) handleText(text) } },
                onStatus = { message ->
                    handler.post {
                        if (!stopped) {
                            manager.notify(NOTIFICATION_ID, buildWakeNotification(
                                if (paused) "«${phraseTitle(this)}» на паузе" else if (diagnosticMode) "Тест фонового микрофона" else "Слушаю «${phraseTitle(this)}»",
                                if (paused) "Микрофон отключён." else message.take(180)
                            ))
                        }
                    }
                },
                onPartial = { text ->
                    handler.post {
                        if (stopped || paused || text.isBlank()) return@post
                        if (diagnosticMode) updateDiagnosticNotification("Распознаю: ${text.take(150)}")
                        // Пользователь договаривает оборванную команду: продлеваем ожидание
                        if (pendingCommand.isNotEmpty() && !processingCommand) armContinuation()
                        // В диалоге пользователь начал говорить: окно не должно закрыться посреди фразы
                        if (dialogActive && !processingCommand && awaitingUntil > 0L) {
                            awaitingUntil = maxOf(awaitingUntil, System.currentTimeMillis() + 4_000L)
                        }
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
                onBargeIn = { heard -> handler.post { if (!stopped && !paused) onBargeIn(heard) } },
                onError = { message ->
                    handler.post {
                        if (!stopped) {
                            // Clear the failed engine so a later service start can retry.
                            engine = null
                            Log.e(TAG, "Ошибка движка распознавания: $message")
                            manager.notify(
                                NOTIFICATION_ID,
                                buildWakeNotification("«${phraseTitle(this)}»: ошибка запуска", message.take(180))
                            )
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
        if (ListenLog.allEnabled(this)) ListenLog.add(this, "услышал", text)
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
                            .setContentTitle("Слушаю «${phraseTitle(this)}»")
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
            ListenLog.add(this, if (command.isBlank()) "слово активации" else "команда после слова", text)
            acquireAssistantAudioFocus()
            // Новое слово активации начинает команду заново
            clearPending()
            if (ListenFilter.meaningful(command)) {
                // If interim recognition did not catch the keyword, still confirm before acting.
                if (!listeningCuePlayed) playCue(listening = true)
                dispatchCommand(command)
            } else {
                acquireAssistantAudioFocus()
                awaitingUntil = System.currentTimeMillis() + commandWindowMs(this)
                windowKind = WINDOW_WAKE
                if (!listeningCuePlayed) playCue(listening = true)
                listeningCuePlayed = true
                // В режиме шёпота голосовое «Слушаю» не произносим: пока ассистент говорит, микрофон закрыт,
                // и тихое начало команды терялось. Остаётся звуковой сигнал.
                if (!AppSettings.bool(this, AppSettings.WHISPER, true)) Speaker.speak(this, "Слушаю")
                val expectedEnd = awaitingUntil
                handler.postDelayed({
                    if (!stopped && awaitingUntil == expectedEnd) {
                        awaitingUntil = 0L
                        listeningCuePlayed = false
                        abandonAssistantAudioFocus()
                    }
                }, commandWindowMs(this))
            }
            return
        }

        if (System.currentTimeMillis() < awaitingUntil) {
            if (dialogActive && pendingCommand.isEmpty() && BargeIn.isEnd(text)) {
                ListenLog.add(this, "конец диалога", text)
                endDialog(sayBye = true)
                return
            }
            // Окно после ответа слушает без слова активации: чужой разговор и шум отбрасываем, окно остаётся открытым
            if (windowKind == WINDOW_DIALOG && pendingCommand.isEmpty() &&
                !ListenFilter.followUp(text, LocalCommands.parse(text) != null)) {
                ListenLog.add(this, "не похоже на обращение, пропускаю", text)
                return
            }
            ListenLog.add(this, if (pendingCommand.isEmpty()) (if (dialogActive) "реплика в диалоге" else "команда в окне") else "продолжение команды", text)
            awaitingUntil = 0L
            acquireAssistantAudioFocus()
            dispatchCommand(text)
        }
    }

    /**
     * Кусок команды. Законченную команду выполняем сразу. Если она оборвана паузой («поставь…», «напомни мне…»),
     * ждём продолжение [CommandAssembler.CONTINUE_MS] без слова активации и склеиваем куски.
     */
    private fun dispatchCommand(part: String) {
        val full = CommandAssembler.join(pendingCommand, part)
        val parts = pendingParts + 1
        if (parts < CommandAssembler.MAX_PARTS && CommandAssembler.looksIncomplete(full)) {
            pendingCommand = full
            pendingParts = parts
            ListenLog.add(this, "жду продолжение", full)
            acquireAssistantAudioFocus()
            listeningCuePlayed = true
            armContinuation()
            return
        }
        if (pendingParts > 0) ListenLog.add(this, "команда из частей", full)
        if (TextMatch.normalize(full) in dialogStartPhrases) {
            clearPending()
            beginDialog(greet = true)
            return
        }
        if (dialogActive && BargeIn.isEnd(full)) {
            clearPending()
            endDialog(sayBye = true)
            return
        }
        clearPending()
        awaitingUntil = 0L
        listeningCuePlayed = false
        playCue(listening = false)
        val generation = pendingGeneration
        // «Отмена» или новое слово активации за эти 180 мс отменяют команду
        handler.postDelayed({
            if (!stopped && !paused && generation == pendingGeneration && !processingCommand) runCommand(full)
        }, 180L)
    }

    /** (Пере)запускает ожидание продолжения; если его нет — выполняем то, что услышали, модель переспросит. */
    private fun armContinuation() {
        awaitingUntil = System.currentTimeMillis() + CommandAssembler.CONTINUE_MS
        handler.removeCallbacks(continuationTimeout)
        handler.postDelayed(continuationTimeout, CommandAssembler.CONTINUE_MS)
    }

    private fun onContinuationTimeout() {
        if (stopped || paused || processingCommand || pendingCommand.isEmpty()) return
        val command = pendingCommand
        ListenLog.add(this, "продолжения нет", command)
        clearPending()
        awaitingUntil = 0L
        listeningCuePlayed = false
        playCue(listening = false)
        runCommand(command)
    }

    private fun clearPending() {
        handler.removeCallbacks(continuationTimeout)
        // Ждали продолжение — сигнал «слушаю» для нового слова активации должен прозвучать снова
        if (pendingCommand.isNotEmpty()) listeningCuePlayed = false
        pendingCommand = ""
        pendingParts = 0
        pendingGeneration++
    }

    /**
     * Short audible acknowledgement. A high ToneGenerator volume makes it clearer than
     * relying on TTS alone; actual loudness still follows the phone's media volume.
     */
    private fun playCue(listening: Boolean) {
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val enabled = prefs.getBoolean(if (listening) "start_cue_enabled" else "end_cue_enabled", true)
            if (!enabled) return
            val volume = prefs.getInt(if (listening) "start_cue_volume" else "end_cue_volume", 70).coerceIn(0, 100)
            if (volume == 0) return
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            val type = if (listening) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_PROMPT
            tone.startTone(type, if (listening) 180 else 260)
            handler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 450L)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось проиграть звуковой сигнал", e)
        }
    }

    private fun runWhenQuiet(waitedMs: Long = 0L, block: () -> Unit) {
        if (stopped) return
        if (!Speaker.isBusy() || waitedMs > 60_000L) block()
        else handler.postDelayed({ runWhenQuiet(waitedMs + 250L, block) }, 250L)
    }

    /** Окно для ответа «да/нет» на вопрос ассистента: слово активации не нужно. */
    private fun openConfirmWindow() {
        acquireAssistantAudioFocus()
        awaitingUntil = System.currentTimeMillis() + commandWindowMs(this)
        windowKind = WINDOW_CONFIRM
        playCue(listening = true)
        listeningCuePlayed = true
        val expectedEnd = awaitingUntil
        handler.postDelayed({
            if (!stopped && awaitingUntil == expectedEnd) {
                awaitingUntil = 0L
                listeningCuePlayed = false
            }
        }, commandWindowMs(this))
    }

    private fun runCommand(command: String) {
        processingCommand = true
        handler.removeCallbacks(dialogTimeout)
        val generation = ++commandGeneration
        AssistantRunner.process(this, command) { answer ->
            if (generation != commandGeneration) return@process
            AssistantService.showResult(applicationContext, command, answer)
            // В режиме диалога после ответа слушаем сразу (ассистента можно перебить), без длинной паузы
            val dialog = dialogActive || AppSettings.bool(this, AppSettings.DIALOG, true)
            val pause = if (dialog) 250L else AppSettings.int(this, AppSettings.AFTER_ANSWER_MS).toLong()
            handler.postDelayed({
                // Ждём, пока ассистент договорит, и только потом снова слушаем
                runWhenQuiet {
                    if (generation != commandGeneration) return@runWhenQuiet
                    processingCommand = false
                    when {
                        // Ассистент задал вопрос «да или нет» — слушаем ответ без слова активации
                        AssistantRepository.hasPendingConfirmation() -> openConfirmWindow()
                        dialog -> openDialogWindow()
                    }
                    handler.removeCallbacks(releaseFocusRunnable)
                    handler.postDelayed(releaseFocusRunnable, 15_000L)
                }
            }, pause)
        }
    }

    // ---------------------------------------------------------------- диалог и перебивание

    /** Начало разговора: дальше реплики без слова активации, пока пользователь не замолчит или не скажет «хватит». */
    private fun beginDialog(greet: Boolean) {
        dialogActive = true
        explicitDialog = true
        ListenLog.add(this, "начало диалога", "")
        acquireAssistantAudioFocus()
        if (greet) {
            Speaker.speak(this, "Давай. Говори, я слушаю. Можешь перебивать.")
            runWhenQuiet { if (dialogActive) openDialogWindow() }
        } else openDialogWindow()
    }

    /** Окно для следующей реплики. Если пользователь молчит — разговор заканчивается тихим сигналом. */
    private fun openDialogWindow() {
        if (stopped || paused) return
        acquireAssistantAudioFocus()
        dialogActive = true
        val window = AppSettings.int(this, AppSettings.DIALOG_WINDOW_S) * 1000L
        awaitingUntil = System.currentTimeMillis() + window
        windowKind = if (explicitDialog) WINDOW_BARGE else WINDOW_DIALOG
        listeningCuePlayed = true
        playCue(listening = true)
        handler.removeCallbacks(dialogTimeout)
        handler.postDelayed(dialogTimeout, window)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION_ID, buildWakeNotification("Диалог: слушаю", "Говори без слова «${phraseTitle(this)}», можно перебивать. «Хватит» — закончить.")
        )
    }

    private fun onDialogTimeout() {
        if (stopped || processingCommand || pendingCommand.isNotEmpty()) return
        // Пользователь ещё говорит (распознавание идёт) — onPartial продлит окно; здесь окно истекло
        if (System.currentTimeMillis() < awaitingUntil) {
            handler.postDelayed(dialogTimeout, awaitingUntil - System.currentTimeMillis())
            return
        }
        endDialog(sayBye = false)
    }

    private fun endDialog(sayBye: Boolean) {
        handler.removeCallbacks(dialogTimeout)
        val wasActive = dialogActive
        val wasExplicit = explicitDialog
        dialogActive = false
        explicitDialog = false
        if (!stopped) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION_ID, buildWakeNotification("Слушаю «${phraseTitle(this)}»", "Ожидаю ключевое слово.")
        )
        awaitingUntil = 0L
        listeningCuePlayed = false
        if (wasActive) ListenLog.add(this, "диалог закончен", "")
        if (sayBye) Speaker.speak(this, "Хорошо, если что — зови.")
        else if (wasExplicit) playCue(listening = false)
        handler.removeCallbacks(releaseFocusRunnable)
        handler.postDelayed(releaseFocusRunnable, 2_000L)
        // Слово активации выключено: служба работала только ради разговора
        if (!enabled(this)) runWhenQuiet { if (!dialogActive) stopSelf() }
    }

    /** Пользователь заговорил поверх ответа: замолкаем и слушаем его. */
    private fun onBargeIn(heard: String) {
        ListenLog.add(this, "перебил", heard)
        Speaker.stop()
        commandGeneration++ // ответ, который перебили, больше ничего не открывает
        processingCommand = false
        clearPending()
        handler.removeCallbacks(dialogTimeout)
        acquireAssistantAudioFocus()
        dialogActive = dialogActive || AppSettings.bool(this, AppSettings.DIALOG, true)
        awaitingUntil = System.currentTimeMillis() + 12_000L
        windowKind = WINDOW_BARGE
        listeningCuePlayed = true
        if (dialogActive) handler.postDelayed(dialogTimeout, 12_000L)
    }

    override fun onDestroy() {
        stopped = true
        dialogActive = false
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
