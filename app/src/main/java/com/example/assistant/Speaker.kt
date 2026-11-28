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

    /** Идёт ли озвучка. Пока ассистент говорит (и ещё чуть-чуть после), микрофон слова активации ничего не слушает. */
    @Volatile private var speakingSince = 0L
    @Volatile private var lastEnd = 0L
    private const val TAIL_MS = 400L
    private const val MAX_SPEECH_MS = 60_000L

    /** Что ассистент сейчас произносит: по этому тексту перебивание отличается от эха ([BargeIn]). */
    @Volatile var currentText: String = ""
        private set

    /** Ассистент говорит прямо сейчас (без хвоста после конца фразы). */
    fun isSpeaking(): Boolean = speakingSince != 0L && System.currentTimeMillis() - speakingSince < expectedMs

    /** Сколько может идти текущая фраза: по длине текста (защита от зависшей озвучки — иначе микрофон глохнет). */
    @Volatile private var expectedMs = MAX_SPEECH_MS
    /** Короткая «тишина» для сигналов: пока звучит пик, распознаватель его не слушает. */
    @Volatile private var mutedUntil = 0L

    fun muteFor(ms: Long) { mutedUntil = maxOf(mutedUntil, System.currentTimeMillis() + ms) }

    fun isBusy(): Boolean {
        val now = System.currentTimeMillis()
        if (now < mutedUntil) return true
        if (speakingSince != 0L && now - speakingSince < expectedMs) return true
        return now - lastEnd < TAIL_MS
    }

    /** Оценка длительности фразы: ~14 символов в секунду с учётом скорости, плюс запас на запуск движка. */
    internal fun estimateMs(text: String, rate: Float): Long =
        (text.length * 72L / rate.coerceIn(0.5f, 2.5f).toDouble()).toLong().plus(4000L).coerceIn(3000L, MAX_SPEECH_MS)

    private fun markStart() { speakingSince = System.currentTimeMillis() }
    private fun markEnd() { speakingSince = 0L; lastEnd = System.currentTimeMillis() }

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
                    // Голос ассистента — как у голосовых помощников: не путается с музыкой и уведомлениями
                    try {
                        created.setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    } catch (_: Exception) {}
                    created.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { markStart() }
                        override fun onDone(utteranceId: String?) { markEnd() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { markEnd() }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { markEnd() }
                    })
                    ready = true
                    val todo = ArrayList(waiting)
                    waiting.clear()
                    todo.forEach { it(created) }
                } else {
                    // Озвучки нет на телефоне: не держим микрофон «занятым»
                    tts = null
                    waiting.clear()
                    markEnd()
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
        currentText = spoken
        expectedMs = estimateMs(spoken, rate(context))
        markStart()
        ensure(context) { if (it.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant") == TextToSpeech.ERROR) markEnd() }
    }

    /** Короткая проверка голоса в настройках. */
    fun sample(context: Context) {
        val spoken = "Здравствуйте! Так звучит мой голос."
        ensure(context) { it.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant") }
    }

    @Synchronized
    fun stop() {
        markEnd()
        tts?.stop()
        waiting.clear()
    }
}
