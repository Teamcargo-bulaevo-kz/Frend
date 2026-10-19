package com.example.assistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

data class VoiceOption(val name: String, val label: String)

/** Озвучка ответов: голос и скорость настраиваются. Для голосовых команд из шторки и плитки говорит всегда. */
object Speaker {

    private const val PREFS = "assistant_prefs"
    private const val KEY = "speak_replies"
    private const val KEY_RATE = "tts_rate"
    private const val KEY_VOICE = "tts_voice"
    private const val KEY_BRIEF = "tts_brief"

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val waiting = ArrayList<(TextToSpeech) -> Unit>()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        if (!value) stop()
    }

    fun rate(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_RATE, 0.95f)

    fun setRate(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_RATE, value).apply()
        synchronized(this) { tts?.setSpeechRate(value) }
    }

    private fun savedVoice(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null)

    fun setVoice(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOICE, name).apply()
        synchronized(this) { tts?.let { applySettings(context, it) } }
    }

    internal fun clean(text: String): String = SpeechText.clean(text)

    @Synchronized
    private fun ensure(context: Context, action: (TextToSpeech) -> Unit) {
        val engine = tts
        if (engine != null && ready) {
            action(engine)
            return
        }
        waiting.add(action)
        if (engine != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            synchronized(this) {
                val created = tts
                if (status == TextToSpeech.SUCCESS && created != null) {
                    val result = created.setLanguage(Locale("ru", "RU"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        created.setLanguage(Locale.getDefault())
                    }
                    applySettings(app, created)
                    ready = true
                    val todo = ArrayList(waiting)
                    waiting.clear()
                    todo.forEach { it(created) }
                } else {
                    tts = null
                    waiting.clear()
                }
            }
        }
    }

    private fun russianVoices(engine: TextToSpeech): List<Voice> = try {
        engine.voices.orEmpty().filter {
            it.locale.language == "ru" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedByDescending { it.quality }
    } catch (e: Exception) {
        emptyList()
    }

    private fun applySettings(context: Context, engine: TextToSpeech) {
        engine.setSpeechRate(rate(context))
        engine.setPitch(1.0f)
        val voices = russianVoices(engine)
        val chosen = voices.firstOrNull { it.name == savedVoice(context) } ?: voices.firstOrNull()
        if (chosen != null) try { engine.setVoice(chosen) } catch (e: Exception) { /* оставим голос по умолчанию */ }
    }

    /** Русские голоса, установленные на телефоне, лучшие первыми. Пустой список — голос по умолчанию. */
    fun voices(context: Context, onResult: (List<VoiceOption>) -> Unit) {
        ensure(context) { engine ->
            val list = russianVoices(engine).mapIndexed { i, v ->
                val kind = if (v.isNetworkConnectionRequired) "онлайн" else "на телефоне"
                VoiceOption(v.name, "Голос ${i + 1} ($kind)")
            }
            onResult(list)
        }
    }

    /** true — длинные ответы пересказываются кратко (по умолчанию), false — читаются полностью. */
    fun brief(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BRIEF, true)

    fun setBrief(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BRIEF, value).apply()
    }

    fun speak(context: Context, text: String) {
        val spoken = if (brief(context)) SpeechText.brief(text) else clean(text)
        if (spoken.isEmpty()) return
        ensure(context) { it.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant") }
    }

    /** Короткая проверка голоса в настройках. */
    fun sample(context: Context) {
        val spoken = "Здравствуйте! Так звучит мой голос."
        ensure(context) { it.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant") }
    }

    @Synchronized
    fun stop() {
        tts?.stop()
        waiting.clear()
    }
}
