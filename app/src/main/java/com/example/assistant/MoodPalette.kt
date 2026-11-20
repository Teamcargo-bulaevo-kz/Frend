package com.example.assistant

/** Как должно выглядеть свечение фона: цвет, яркость, насколько сильно бьётся в такт и как быстро плывёт. */
data class Aura(
    val color: Int,
    val brightness: Float,
    val reactivity: Float,
    val speed: Float,
    val bpm: Int = 0
)

/**
 * Настроение песни → вид свечения. Чистый Kotlin, без Android.
 * Агрессивное: яркий красный и резкие удары; спокойное: бледное светлое и медленное;
 * мрачное: тёмное и глубокое; весёлое: тёплое; грустное: приглушённый синий.
 */
object MoodPalette {

    /** Готовые палитры: название и оттенок центра (градусы). */
    val fixed: List<Pair<String, Int>> = listOf(
        "Огонь" to 8, "Закат" to 345, "Океан" to 208, "Лёд" to 190, "Лес" to 140, "Неон" to 290, "Фиолетовая ночь" to 262
    )

    val DEFAULT = Aura(0xFF7C6CFF.toInt(), 0.5f, 0.5f, 0.7f)

    private val presets: Map<String, Aura> = mapOf(
        "energetic" to Aura(0xFFFF5A36.toInt(), 0.90f, 1.00f, 1.3f),
        "calm" to Aura(0xFF4C8DFF.toInt(), 0.60f, 0.30f, 0.45f),
        "happy" to Aura(0xFFFFC857.toInt(), 0.80f, 0.70f, 1.0f),
        "sad" to Aura(0xFF3B5BD6.toInt(), 0.42f, 0.30f, 0.40f),
        "romantic" to Aura(0xFFFF7FAE.toInt(), 0.60f, 0.50f, 0.70f),
        "dance" to Aura(0xFFE24BFF.toInt(), 0.90f, 0.95f, 1.2f),
        "focus" to Aura(0xFF4CC3D9.toInt(), 0.38f, 0.25f, 0.40f),
        "epic" to Aura(0xFFFF9A3C.toInt(), 0.95f, 0.90f, 1.0f)
    )

    /** «Агрессивная / мрачная»: при высоком возбуждении: яркий красный, иначе тёмный глубокий. */
    private fun dark(arousal: Int): Aura =
        if (arousal >= 55) Aura(0xFFFF2A3C.toInt(), 0.95f, 1.00f, 1.2f)
        else Aura(0xFF4A2F8F.toInt(), 0.30f, 0.45f, 0.45f)

    /** [scores]: доли настроений (сумма ~100), [arousal] 0..100 (0: неизвестно). */
    fun from(scores: Map<String, Int>, arousal: Int = 0, bpm: Int = 0): Aura {
        val positive = scores.filterValues { it > 0 }
        if (positive.isEmpty()) return DEFAULT
        // Квадрат веса: побеждает доминирующее настроение, а цвета не смешиваются в грязь
        var total = 0f
        positive.values.forEach { total += it.toFloat() * it }
        var r = 0f; var g = 0f; var b = 0f; var br = 0f; var re = 0f; var sp = 0f
        for ((key, value) in positive) {
            val aura = if (key == "dark") dark(arousal) else presets[key] ?: continue
            val w = value.toFloat() * value / total
            r += ((aura.color shr 16) and 0xFF) * w
            g += ((aura.color shr 8) and 0xFF) * w
            b += (aura.color and 0xFF) * w
            br += aura.brightness * w
            re += aura.reactivity * w
            sp += aura.speed * w
        }
        val color = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        return Aura(color, br, re, sp, bpm.coerceIn(0, 240))
    }
}
