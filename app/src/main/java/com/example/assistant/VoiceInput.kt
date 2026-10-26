package com.example.assistant

import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Настройки голосового ввода в одном месте. Главное — паузы: по умолчанию Android обрывает фразу
 * через секунду тишины, и длинная команда режется на полуслове. Здесь ждём чуть дольше.
 */
object VoiceInput {

    /** Сколько тишины считать концом фразы. */
    const val COMPLETE_SILENCE_MS = 1800L
    const val POSSIBLY_COMPLETE_SILENCE_MS = 1400L
    const val MIN_LENGTH_MS = 1500L

    /** Пауза после остановки озвучки перед записью, чтобы микрофон не слышал хвост голоса ассистента. */
    const val AFTER_SPEECH_DELAY_MS = 350L

    /** context нужен, чтобы взять паузу из настроек; без него используется значение по умолчанию. */
    fun intent(prompt: String = "Говорите...", context: android.content.Context? = null): Intent {
        val silence = context?.let { AppSettings.int(it, AppSettings.VOICE_SILENCE_MS).toLong() } ?: COMPLETE_SILENCE_MS
        val possibly = (silence * POSSIBLY_COMPLETE_SILENCE_MS / COMPLETE_SILENCE_MS).coerceAtLeast(600L)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibly)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_LENGTH_MS)
        }    }
}
