package com.example.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Короткие сигналы «слушаю» (начало) и «готово» (конец). Каждый включается и настраивается отдельно.
 * Громкость — уровень самого сигнала: системная громкость медиа не меняется (при выключенном звуке сигнала не слышно).
 */
object Cues {

    private val handler = Handler(Looper.getMainLooper())

    fun startOn(context: Context) = AppSettings.bool(context, AppSettings.CUE_START_ON, true)
    fun endOn(context: Context) = AppSettings.bool(context, AppSettings.CUE_END_ON, true)

    /** start = true: сигнал «слушаю»; false: сигнал «команда принята / готово». */
    fun play(context: Context, start: Boolean) {
        if (start && !startOn(context)) return
        if (!start && !endOn(context)) return
        val volume = AppSettings.int(context, if (start) AppSettings.CUE_START_VOLUME else AppSettings.CUE_END_VOLUME)
        if (volume <= 0) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            val type = if (start) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_PROMPT
            tone.startTone(type, if (start) 180 else 260)
            handler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 450L)
        } catch (e: Exception) {
            // сигнал не проигрался — работа ассистента не зависит от него
        }
    }

    /** Прослушивание из настроек: играет сигнал независимо от переключателя (но с выбранной громкостью). */
    fun preview(context: Context, start: Boolean) {
        val volume = AppSettings.int(context, if (start) AppSettings.CUE_START_VOLUME else AppSettings.CUE_END_VOLUME)
        if (volume <= 0) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            tone.startTone(if (start) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_PROMPT, if (start) 180 else 260)
            handler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 450L)
        } catch (e: Exception) {
            // нечего проигрывать
        }
    }
}

/** Приложение: применяет сохранённую тему при запуске. */
class AssistantApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyTheme(this)
    }
}
