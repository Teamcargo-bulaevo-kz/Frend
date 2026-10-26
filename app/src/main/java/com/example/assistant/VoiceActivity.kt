package com.example.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Прозрачный экран для голосовой команды из шторки: распознаёт речь, выполняет команду
 * и закрывается. Остаётся на экране, пока команда выполняется: так Android разрешает ей
 * открывать другие приложения.
 */
class VoiceActivity : ComponentActivity() {

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        if (text.isNullOrBlank()) {
            finish()
        } else {
            execute(text)
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecognition()
        } else {
            Toast.makeText(this, "Нужен доступ к микрофону для голосовой команды", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Экран прозрачный и не перехватывает касания, пока ждём ответ
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        if (savedInstanceState != null) return
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMic) startRecognition() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecognition() {
        Speaker.stop()
        val intent = VoiceInput.intent(context = this)
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "На телефоне нет приложения для распознавания речи", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun execute(text: String) {
        val app = applicationContext
        AssistantRunner.process(app, text) { answer ->
            Toast.makeText(app, answer.take(300), Toast.LENGTH_LONG).show()
            AssistantService.showResult(app, text, answer)
            finish()
        }
    }
}
