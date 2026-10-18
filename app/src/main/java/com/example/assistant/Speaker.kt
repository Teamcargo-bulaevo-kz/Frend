package com.example.assistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Озвучка ответов. Выключается переключателем в меню чатов; для голосовых команд из шторки и плитки говорит всегда. */
object Speaker {

    private const val PREFS = "assistant_prefs"
    private const val KEY = "speak_replies"

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: String? = null

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        if (!value) stop()
    }

    /** Убирает то, что плохо звучит вслух: разметку, значки, длинные хвосты. */
    internal fun clean(text: String): String =
        text.replace(Regex("[*_#`•▪]+"), " ")
            .replace(Regex("https?://\\S+"), "ссылка")
            .replace(Regex("[\\p{So}\\p{Cs}]"), "")
            .replace(Regex("\\s+"), " ").trim().take(350)

    @Synchronized
    fun speak(context: Context, text: String) {
        val spoken = clean(text)
        if (spoken.isEmpty()) return
        val engine = tts
        if (engine != null && ready) {
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant")
            return
        }
        pending = spoken
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    if (status == TextToSpeech.SUCCESS) {
                        val result = tts?.setLanguage(Locale("ru", "RU"))
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts?.setLanguage(Locale.getDefault())
                        }
                        ready = true
                        pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant") }
                        pending = null
                    } else {
                        tts = null
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        tts?.stop()
        pending = null
    }
}
