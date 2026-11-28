package com.example.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Звуковые сигналы голосового общения — у каждого свой звук, чтобы по слуху было понятно, что происходит:
 *  LISTEN — «слушаю» (после слова активации и когда ждём ответ), высокий короткий;
 *  ACCEPT — «принял команду», двойной;
 *  THINKING — «думаю дольше обычного», тихий короткий щелчок;
 *  CLOSE — «перестал слушать» (окно истекло без команды), низкий мягкий;
 *  ERROR — «не получилось / не понял», низкий двойной.
 * Громкость и включение — в настройках («Сигнал начала прослушивания» / «Сигнал завершения»).
 */
object Cues {

    enum class Kind { LISTEN, ACCEPT, THINKING, CLOSE, ERROR }

    private const val PREFS = "assistant_prefs"
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Тон и длительность сигнала. Чистая функция — проверяется тестом, что сигналы различаются. */
    fun tone(kind: Kind): Pair<Int, Int> = when (kind) {
        Kind.LISTEN -> ToneGenerator.TONE_PROP_ACK to 160
        Kind.ACCEPT -> ToneGenerator.TONE_PROP_PROMPT to 220
        Kind.THINKING -> ToneGenerator.TONE_PROP_BEEP to 70
        Kind.CLOSE -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 160
        Kind.ERROR -> ToneGenerator.TONE_PROP_NACK to 300
    }

    /** Какая настройка управляет сигналом: начало (слушаю) или завершение (всё остальное). */
    fun isStart(kind: Kind) = kind == Kind.LISTEN

    /** mute — заглушить распознавание на время сигнала (по умолчанию для всех, кроме «слушаю» сразу после слова:
     *  тогда пользователь может уже говорить команду). */
    fun play(context: Context, kind: Kind, mute: Boolean = kind != Kind.LISTEN) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val start = isStart(kind)
            if (!prefs.getBoolean(if (start) "start_cue_enabled" else "end_cue_enabled", true)) return
            var volume = prefs.getInt(if (start) "start_cue_volume" else "end_cue_volume", 70).coerceIn(0, 100)
            if (kind == Kind.THINKING || kind == Kind.CLOSE) volume = volume * 55 / 100
            if (volume == 0) return
            val (type, ms) = tone(kind)
            val gen = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            gen.startTone(type, ms)
            // Пока звучит сигнал, распознаватель его не слушает (иначе «пик» превращается в слово)
            if (mute) Speaker.muteFor(ms + 120L)
            handler.postDelayed({ try { gen.release() } catch (_: Exception) {} }, ms + 300L)
        } catch (_: Exception) {}
    }
}
