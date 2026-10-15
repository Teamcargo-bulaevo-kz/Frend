package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repository: AssistantRepository
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: ImageButton
    private lateinit var chatLog: TextView
    private lateinit var chatScroll: ScrollView

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognized = results?.firstOrNull()
            if (!recognized.isNullOrBlank()) {
                sendPrompt(recognized)
            }
        }
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

        input = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        micButton = findViewById(R.id.micButton)
        chatLog = findViewById(R.id.chatLogView)
        chatScroll = findViewById(R.id.chatScrollView)

        loadHistory()

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

    private fun loadHistory() {
        val history = ChatHistory.load(applicationContext)
        for (m in history) {
            appendToLog(m.role, m.text)
        }
    }

    private fun sendPrompt(prompt: String) {
        input.setText("")
        appendToLog("user", prompt)
        ChatHistory.append(applicationContext, "user", prompt)

        sendButton.isEnabled = false
        micButton.isEnabled = false

        uiScope.launch {
            val answer = repository.handle(prompt)
            appendToLog("assistant", answer)
            ChatHistory.append(applicationContext, "assistant", answer)
            sendButton.isEnabled = true
            micButton.isEnabled = true
        }
    }

    private fun appendToLog(role: String, text: String) {
        val prefix = if (role == "user") "Вы: " else "Ассистент: "
        chatLog.append("\n$prefix$text\n")
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun launchVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "На телефоне нет приложения для распознавания речи", Toast.LENGTH_SHORT).show()
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
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }
}
