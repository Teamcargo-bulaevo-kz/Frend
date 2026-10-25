package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.ArrayAdapter
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var repository: AssistantRepository
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var emptyState: View
    private lateinit var drawer: DrawerLayout
    private lateinit var chatTitle: TextView
    private lateinit var messageList: RecyclerView
    private lateinit var chatsListView: ListView
    private val adapter = MessageAdapter()
    private var chats: List<ChatInfo> = emptyList()
    private var busy = false

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognized = results?.firstOrNull()
            if (!recognized.isNullOrBlank()) {
                sendPrompt(recognized)
            }
        }
    }

    private val diagnosticVoiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val recognized = if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        AlertDialog.Builder(this)
            .setTitle(if (!recognized.isNullOrBlank()) "Тест микрофона: УСПЕХ" else "Тест микрофона: НЕТ РЕЗУЛЬТАТА")
            .setMessage(
                if (!recognized.isNullOrBlank()) "Система распознала:\n\n«$recognized»\n\nЭто проверяет микрофон и системное распознавание, но не фоновый движок слова активации."
                else "Система не вернула распознанный текст. Проверь интернет, системный сервис распознавания речи и разрешение микрофона."
            )
            .setPositiveButton("ОК", null)
            .show()
    }

    private val wakePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        findViewById<CompoundButton>(R.id.wakeCheck).isChecked = granted
        if (granted) {
            WakeWordService.setEnabled(this, true)
        } else {
            Toast.makeText(this, "Без доступа к микрофону слово активации не работает", Toast.LENGTH_SHORT).show()
        }
    }

    private val attachLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attachFile(uri)
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchVoiceRecognition()
        } else {
            Toast.makeText(this, "Нужен доступ к микрофону для голосового ввода", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = AssistantRepository(applicationContext, BuildConfig.GROQ_API_KEY)

        requestMediaPermissionIfNeeded()
        AssistantService.start(this)

        input = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        emptyState = findViewById(R.id.emptyState)
        micButton = findViewById(R.id.micButton)
        drawer = findViewById(R.id.drawer)
        chatTitle = findViewById(R.id.chatTitle)
        messageList = findViewById(R.id.messageList)
        chatsListView = findViewById(R.id.chatsListView)

        messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageList.adapter = adapter

        findViewById<ImageButton>(R.id.attachButton).setOnClickListener { attachLauncher.launch("*/*") }

        val speakCheck = findViewById<CompoundButton>(R.id.speakCheck)
        speakCheck.isChecked = Speaker.enabled(this)
        speakCheck.setOnCheckedChangeListener { _, checked -> Speaker.setEnabled(this, checked) }

        val wakeCheck = findViewById<CompoundButton>(R.id.wakeCheck)
        wakeCheck.isChecked = WakeWordService.enabled(this) && WakeWordService.hasMicPermission(this)
        wakeCheck.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (!checked) {
                WakeWordService.setEnabled(this, false)
            } else if (WakeWordService.hasMicPermission(this)) {
                WakeWordService.setEnabled(this, true)
            } else {
                wakePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        findViewById<Button>(R.id.testWakeButton).setOnClickListener {
            Toast.makeText(this, "Кнопка нажата: запускаю тест фонового движка", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this)
                .setTitle("Проверка фонового движка")
                .setMessage("Сейчас запускается локальное распознавание Sherpa-ONNX. Первая загрузка модели может занять несколько минут. Если системное уведомление не появится, сервис не стартовал.")
                .setPositiveButton("Запустить") { _, _ ->
                    if (WakeWordService.hasMicPermission(this)) {
                        WakeWordService.test(this)
                    } else {
                        wakePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        findViewById<Button>(R.id.testSystemSpeechButton).setOnClickListener {
            if (!WakeWordService.hasMicPermission(this)) {
                wakePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                val intent = VoiceInput.intent()
                // Освобождаем микрофон для системного (Google) распознавания.
                WakeWordService.pauseForSystemVoiceInput(this)
                try {
                    diagnosticVoiceLauncher.launch(intent)
                } catch (e: Exception) {
                    WakeWordService.start(this)
                    AlertDialog.Builder(this)
                        .setTitle("Не удалось запустить тест")
                        .setMessage("${e.javaClass.simpleName}: ${e.message ?: "без подробностей"}")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }

        findViewById<TextView>(R.id.appVersion).text = "V${BuildConfig.VERSION_NAME}"

        WakeWordService.applyPhrase(this)
        findViewById<TextView>(R.id.privacySettings).setOnClickListener {
            drawer.closeDrawers()
            showPrivacySettings()
        }
        findViewById<TextView>(R.id.wakeSettings).setOnClickListener {
            drawer.closeDrawers()
            showWakeSettings()
        }
        findViewById<TextView>(R.id.voiceSettings).setOnClickListener {
            drawer.closeDrawers()
            showVoiceSettings()
        }
        buildSuggestions()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            refreshChatList()
            drawer.openDrawer(android.view.Gravity.START)
        }
        findViewById<ImageButton>(R.id.newChatButton).setOnClickListener { startNewChat() }
        findViewById<Button>(R.id.newChatDrawerButton).setOnClickListener { startNewChat() }

        chatsListView.setOnItemClickListener { _, _, position, _ ->
            if (blockedWhileBusy()) return@setOnItemClickListener
            ChatHistory.select(applicationContext, chats[position].id)
            drawer.closeDrawers()
            showCurrentChat()
        }
        chatsListView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(chats[position])
            true
        }

        sendButton.setOnClickListener {
            val prompt = input.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener
            sendPrompt(prompt)
        }

        micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchVoiceRecognition()
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Чат читаем заново: за время, пока приложение было свёрнуто, могли прийти голосовые команды из шторки
        showCurrentChat()
        WakeWordService.start(this)
        uiScope.launch(Dispatchers.IO) {
            // Музыка, которую система ещё не знает (скачанное, из мессенджеров), добавляется в медиатеку
            MusicIndexer.scanNewFiles(applicationContext)
            // Новые файлы в общей папке Assistant/Входящие: сообщаем в чате
            val fresh = SharedFolder.takeNewFiles(applicationContext)
            if (fresh.isNotEmpty()) {
                val names = fresh.take(8).joinToString("\n") { "• ${it.name}" }
                val more = if (fresh.size > 8) "\n… и ещё ${fresh.size - 8}" else ""
                ChatHistory.append(
                    applicationContext, "assistant",
                    "Новые файлы во Входящих:\n$names$more\nСкажи, что с ними сделать (прочитать, найти, посчитать)."
                )
                launch(Dispatchers.Main) { showCurrentChat() }
            }
        }
    }

    // ---------------------------------------------------------------- чаты

    private fun showCurrentChat() {
        val context = applicationContext
        chatTitle.text = ChatHistory.currentTitle(context)
        val history = ChatHistory.load(context)
        adapter.setAll(history)
        updateEmptyState()
        scrollToEnd()
    }

    private fun refreshChatList() {
        chats = ChatHistory.list(applicationContext)
        val current = ChatHistory.currentId(applicationContext)
        chatsListView.adapter = object : ArrayAdapter<ChatInfo>(this, R.layout.item_chat, R.id.chatTitleItem, chats) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = chats[position]
                (view as TextView).apply {
                    text = item.title
                    setBackgroundColor(if (item.id == current) ContextCompat.getColor(context, R.color.chat_selected) else 0x00000000)
                }
                return view
            }
        }
    }

    private fun startNewChat() {
        if (blockedWhileBusy()) return
        ChatHistory.create(applicationContext)
        drawer.closeDrawers()
        showCurrentChat()
    }

    private fun confirmDelete(chat: ChatInfo) {
        AlertDialog.Builder(this)
            .setTitle("Удалить чат?")
            .setMessage("«${chat.title}» удалится вместе со всей перепиской.")
            .setPositiveButton("Удалить") { _, _ ->
                val wasCurrent = chat.id == ChatHistory.currentId(applicationContext)
                if (wasCurrent && blockedWhileBusy()) return@setPositiveButton
                ChatHistory.delete(applicationContext, chat.id)
                refreshChatList()
                if (wasCurrent) showCurrentChat()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Пока ассистент отвечает, чат не переключаем: ответ должен попасть в тот же чат. */
    private fun blockedWhileBusy(): Boolean {
        if (busy) Toast.makeText(this, "Подожди ответа ассистента", Toast.LENGTH_SHORT).show()
        return busy
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(android.view.Gravity.START)) drawer.closeDrawers() else super.onBackPressed()
    }

    private fun scrollToEnd() {
        messageList.post { if (adapter.itemCount > 0) messageList.scrollToPosition(adapter.itemCount - 1) }
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /** Подсказки для пустого чата: нажал — отправилось. */
    private fun buildSuggestions() {
        val box = findViewById<android.widget.LinearLayout>(R.id.suggestions)
        val examples = listOf(
            "Включи фонарик",
            "Поставь будильник на 7:30",
            "Включи спокойную музыку",
            "Какая погода завтра?"
        )
        val dp = resources.displayMetrics.density
        for (text in examples) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.chat_text))
                setBackgroundResource(R.drawable.chip_bg)
                setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * dp).toInt(); gravity = android.view.Gravity.CENTER_HORIZONTAL }
                setOnClickListener { sendPrompt(text) }
            })
        }
    }

    /** Что ассистент может видеть: у каждого пункта своё разрешение, всё выключено по умолчанию. */
    private fun showPrivacySettings() {
        val screenOn = Privacy.screenAllowed(this)
        val usageOn = DeviceStatus.hasUsageAccess(this)
        val labels = arrayOf(
            "Чтение экрана: ${if (screenOn) "ВКЛЮЧЕНО" else "выключено"} (нажми, чтобы сменить)",
            "История использования приложений: ${if (usageOn) "разрешено" else "не разрешено"} (открыть настройки)",
            "Служба специальных возможностей (нужна для чтения экрана и плиток шторки)",
            "Shizuku (процессы и память по приложениям): скоро"
        )
        AlertDialog.Builder(this)
            .setTitle("Что ассистент может видеть")
            .setItems(labels) { dialog, which ->
                when (which) {
                    0 -> {
                        Privacy.setScreenAllowed(this, !screenOn)
                        Toast.makeText(
                            this,
                            if (!screenOn) "Чтение экрана включено: читаю только когда просишь, текст уходит в ИИ для ответа, пароли не читаются" else "Чтение экрана выключено",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    1 -> try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) {
                        Toast.makeText(this, "Открой Настройки → Приложения → Специальный доступ → История использования", Toast.LENGTH_LONG).show()
                    }
                    2 -> try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {
                        Toast.makeText(this, "Открой Настройки → Специальные возможности", Toast.LENGTH_LONG).show()
                    }
                    else -> Toast.makeText(this, "Shizuku подключим позже, всё уже подготовлено", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Слово активации и быстрый переход к настройке батареи. */
    private fun showWakeSettings() {
        val field = EditText(this).apply {
            setText(WakeWordService.phraseTitle(this@MainActivity))
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Слово активации")
            .setMessage(
                "Слово, на которое отзывается ассистент (не короче 4 букв). Лучше длинное и необычное: так меньше ложных срабатываний.\n\n" +
                    "Чтобы слушание не отключалось само, разреши приложению работать без ограничений батареи " +
                    "(на Xiaomi ещё включи «Автозапуск»)."
            )
            .setView(field)
            .setPositiveButton("Сохранить") { _, _ ->
                WakeWordService.setPhrase(this, field.text.toString())
                Toast.makeText(this, "Слово: ${WakeWordService.phraseTitle(this)}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Батарея") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Окно выбора голоса и скорости озвучки. */
    private fun showVoiceSettings() {
        Speaker.voices(applicationContext) { voices ->
            runOnUiThread {
                val rates = floatArrayOf(0.8f, 0.95f, 1.1f, 1.3f)
                val rateNames = arrayOf("Медленно", "Спокойно", "Быстрее", "Быстро")
                var rateIndex = rates.indexOfFirst { kotlin.math.abs(it - Speaker.rate(this)) < 0.03f }.let { if (it < 0) 1 else it }
                var brief = Speaker.brief(this)
                fun briefLabel() = if (brief) "Длинные ответы: пересказывать кратко (нажми, чтобы сменить)" else "Длинные ответы: читать полностью (нажми, чтобы сменить)"
                val labels = (listOf("Скорость: ${rateNames[rateIndex]} (нажми, чтобы сменить)", briefLabel()) + voices.map { it.label } +
                    listOf("Проверить голос")).toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Голос озвучки")
                    .setItems(labels) { dialog, which ->
                        when {
                            which == 0 -> {
                                rateIndex = (rateIndex + 1) % rates.size
                                Speaker.setRate(applicationContext, rates[rateIndex])
                                Toast.makeText(this, "Скорость: ${rateNames[rateIndex]}", Toast.LENGTH_SHORT).show()
                                Speaker.sample(applicationContext)
                            }
                            which == 1 -> {
                                brief = !brief
                                Speaker.setBrief(applicationContext, brief)
                                Toast.makeText(this, if (brief) "Длинные ответы пересказываю кратко" else "Длинные ответы читаю полностью", Toast.LENGTH_SHORT).show()
                            }
                            which == labels.size - 1 -> Speaker.sample(applicationContext)
                            else -> {
                                Speaker.setVoice(applicationContext, voices[which - 2].name)
                                Speaker.sample(applicationContext)
                            }
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }
    }

    private fun sendPrompt(prompt: String) {
        if (busy) return
        Speaker.stop()
        input.setText("")
        val chatId = ChatHistory.currentId(applicationContext)
        ChatHistory.append(applicationContext, "user", prompt, chatId)
        chatTitle.text = ChatHistory.currentTitle(applicationContext)
        adapter.add(ChatMessage("user", prompt, System.currentTimeMillis()))
        adapter.showTyping()
        updateEmptyState()
        scrollToEnd()

        busy = true
        setControlsEnabled(false)

        uiScope.launch {
            val answer = try {
                repository.handle(prompt)
            } catch (e: Exception) {
                FriendlyError.text(e)
            }
            ChatHistory.append(applicationContext, "assistant", answer, chatId)
            if (Speaker.enabled(applicationContext)) Speaker.speak(applicationContext, answer)
            adapter.add(ChatMessage("assistant", answer, System.currentTimeMillis()))
            scrollToEnd()
            busy = false
            setControlsEnabled(true)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        micButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1f else 0.5f
        micButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun launchVoiceRecognition() {
        // Останавливаем озвучку и освобождаем микрофон для Google Speech Recognizer.
        Speaker.stop()
        WakeWordService.pauseForSystemVoiceInput(this)
        val intent = VoiceInput.intent()
        try {
            micButton.postDelayed({
                if (!isFinishing) {
                    try {
                        voiceLauncher.launch(intent)
                    } catch (e: Exception) {
                        WakeWordService.start(this)
                        Toast.makeText(this, "Не удалось запустить системный голосовой ввод", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    WakeWordService.start(this)
                }
            }, VoiceInput.AFTER_SPEECH_DELAY_MS)
        } catch (e: Exception) {
            WakeWordService.start(this)
            Toast.makeText(this, "На телефоне нет приложения для распознавания речи", Toast.LENGTH_SHORT).show()
        }
    }

    /** Копирует выбранный файл во Входящие общей папки, чтобы с ним можно было работать. */
    private fun attachFile(uri: Uri) {
        uiScope.launch {
            val message = withContext(Dispatchers.IO) {
                if (!SharedFolder.ensure()) {
                    return@withContext "Нет доступа ко всем файлам, поэтому некуда сохранить. Разреши его для Assistant в настройках."
                }
                try {
                    var name = "файл"
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { name = it }
                    }
                    name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    var target = java.io.File(SharedFolder.inbox(), name)
                    var n = 1
                    while (target.exists()) {
                        target = java.io.File(SharedFolder.inbox(), "${target.nameWithoutExtension.replace(Regex(" \\(\\d+\\)$"), "")} ($n).${target.extension}".removeSuffix("."))
                        n++
                    }
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: return@withContext "Не получилось открыть файл."
                    SharedFolder.markSeen(applicationContext, target)
                    val hint = if (target.extension.lowercase() in SharedFolder.imageExtensions) {
                        "Напиши, что сделать: например «распознай текст на ${target.name}»."
                    } else {
                        "Скажи, что с ним сделать."
                    }
                    "Добавил «${target.name}» во Входящие. $hint"
                } catch (e: Exception) {
                    "Не получилось добавить файл: ${e.message}"
                }
            }
            ChatHistory.append(applicationContext, "assistant", message)
            adapter.add(ChatMessage("assistant", message, System.currentTimeMillis()))
            updateEmptyState()
            scrollToEnd()
        }
    }

    private fun requestMediaPermissionIfNeeded() {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val extra = arrayOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
        )
        val missing = (wanted + extra).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        } else {
            askAllFilesAccessOnce()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) askAllFilesAccessOnce()
    }

    /** Один раз открывает настройки «Все файлы», чтобы ассистент видел файлы и всю музыку. */
    private fun askAllFilesAccessOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return
        val prefs = getSharedPreferences("assistant_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("asked_all_files", false)) return
        prefs.edit().putBoolean("asked_all_files", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "Включи доступ ко всем файлам для Assistant и вернись назад", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // нет такого экрана настроек — ассистент спросит позже, когда понадобится
        }
    }
}
