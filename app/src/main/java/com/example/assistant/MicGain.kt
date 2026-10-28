package com.example.assistant

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Усиление микрофона перед распознаванием. [fixedGain] — постоянное усиление (1 = без него).
 * В режиме шёпота [whisper] дополнительно включается автоусиление: тихая речь подтягивается к рабочему уровню
 * (не больше чем в [whisperMax] раз), громкая не усиливается. Отсчёты в диапазоне -1..1, с ограничением от клиппинга.
 * Чистый Kotlin, без Android.
 */
class MicGain(private val fixedGain: Float, private val whisper: Boolean, private val whisperMax: Float = 30f) {

    private var level = 0.0
    private var auto = 1.0

    /** Текущее автоусиление (для диагностики и тестов). */
    val currentAutoGain: Double get() = auto

    fun process(samples: FloatArray) {
        if (samples.isEmpty()) return
        if (whisper) {
            var sum = 0.0
            for (v in samples) sum += v.toDouble() * v
            val rms = sqrt(sum / samples.size)
            // Оценка уровня: быстро растёт при громких звуках, медленно падает в тишине
            level = if (rms > level) level * 0.7 + rms * 0.3 else level * 0.98 + rms * 0.02
            val desired = (TARGET / max(level, FLOOR)).coerceIn(1.0, whisperMax.toDouble().coerceAtLeast(1.0))
            // Усиление падает сразу (чтобы не оглушить), растёт плавно
            auto = if (desired < auto) desired else auto + (desired - auto) * 0.15
        }
        val total = (fixedGain.toDouble().coerceAtLeast(1.0) * auto).toFloat()
        if (total <= 1.001f) return
        for (i in samples.indices) samples[i] = (samples[i] * total).coerceIn(-1f, 1f)
    }

    private companion object {
        const val TARGET = 0.09   // ≈ 3000 из 32768
        const val FLOOR = 0.00076 // ≈ 25 из 32768: тише считаем тишиной
    }
}
