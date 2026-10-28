package com.example.assistant

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Автоусиление микрофона для шёпота: подтягивает тихую речь к рабочему уровню, но не больше [maxGain] раз.
 * Громкая речь не усиливается (усиление не меньше 1) и не клипуется. Чистый Kotlin, без Android.
 */
class MicGain(private val maxGain: () -> Int) {

    private var level = 0.0
    private var gain = 1.0

    /** Текущее усиление (для диагностики и тестов). */
    val currentGain: Double get() = gain

    /** Усиливает первые [n] отсчётов на месте. */
    fun process(pcm: ShortArray, n: Int) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) { val v = pcm[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / n)
        // Оценка уровня: быстро растёт при громких звуках, медленно падает в тишине
        level = if (rms > level) level * 0.7 + rms * 0.3 else level * 0.98 + rms * 0.02
        val limit = maxGain().coerceIn(1, 30).toDouble()
        val desired = (TARGET / max(level, FLOOR)).coerceIn(1.0, limit)
        // Усиление падает сразу (чтобы не оглушить), растёт плавно
        gain = if (desired < gain) desired else gain + (desired - gain) * 0.15
        if (gain <= 1.001) return
        for (i in 0 until n) {
            pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private companion object {
        const val TARGET = 3000.0
        const val FLOOR = 25.0
    }
}
