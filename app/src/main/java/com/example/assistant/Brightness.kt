package com.example.assistant

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Яркость как на системном ползунке. Android хранит яркость числом от 0 до максимума (обычно 255, на Xiaomi 2047 или 4095),
 * а ползунок нелинейный (гамма-кривая HLG): «50%» на ползунке — это около 8% линейной яркости.
 * Поэтому «яркость 100%» раньше ставила 255 из 4095 — ползунок не доходил и до середины. Чистый Kotlin, есть тесты.
 */
object Brightness {
    private const val R = 0.5
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073

    /** Положение ползунка 0..100 → значение настройки в диапазоне min..max. */
    fun sliderToSetting(percent: Int, min: Int, max: Int): Int {
        val v = percent.coerceIn(0, 100) / 100.0
        val ret = if (v <= R) (v / R) * (v / R) else exp((v - C) / A) + B
        val linear = (ret / 12.0).coerceIn(0.0, 1.0)
        return (min + (max - min) * linear).roundToInt().coerceIn(min, max)
    }

    /** Значение настройки → положение ползунка 0..100 (чтобы «сделай ярче» прибавляло от текущего). */
    fun settingToSlider(value: Int, min: Int, max: Int): Int {
        if (max <= min) return 50
        val linear = ((value - min).toDouble() / (max - min)).coerceIn(0.0, 1.0) * 12.0
        val v = if (linear <= 1.0) R * sqrt(linear) else A * ln(linear - B) + C
        return (v * 100).roundToInt().coerceIn(0, 100)
    }

    /** Максимум шкалы: из ресурсов системы; если текущее значение больше 255 — шкала явно шире (Xiaomi). */
    fun guessMax(fromResources: Int?, current: Int): Int = when {
        fromResources != null && fromResources > 0 && current <= fromResources -> fromResources
        current > 2047 -> 4095
        current > 1023 -> 2047
        current > 255 -> 1023
        else -> fromResources?.takeIf { it > 0 } ?: 255
    }
}
