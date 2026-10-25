package com.example.assistant

/**
 * Понижение частоты записи до 8 кГц, которые ждёт модель. Усредняет отсчёты внутри каждого окна,
 * поэтому высокие частоты не «заворачиваются» и распознавание слова не страдает. Чистый Kotlin.
 * Блок должен быть длиной rate/10 отсчётов (0,1 с): тогда на выходе ровно 800 отсчётов.
 */
object AudioResampler {

    const val MODEL_RATE = 8000

    /** Частоты, которые пробуем открыть, начиная с родной для модели. */
    val RATES = listOf(8000, 16000, 24000, 48000, 22050, 44100)

    fun frameSamples(rate: Int) = rate / 10

    /** pcm[0 until n] с частотой rate -> числа от -1 до 1 с частотой 8 кГц. */
    fun toModel(pcm: ShortArray, n: Int, rate: Int): FloatArray {
        if (n <= 0) return FloatArray(0)
        if (rate == MODEL_RATE) return FloatArray(n) { pcm[it] / 32768.0f }
        val outLen = (n.toLong() * MODEL_RATE / rate).toInt().coerceAtLeast(1)
        val step = rate.toDouble() / MODEL_RATE
        return FloatArray(outLen) { i ->
            val from = i * step
            val to = minOf((i + 1) * step, n.toDouble())
            var sum = 0.0
            var weight = 0.0
            var k = from.toInt()
            while (k < to && k < n) {
                val w = minOf(k + 1.0, to) - maxOf(k.toDouble(), from)
                if (w > 0) { sum += pcm[k] * w; weight += w }
                k++
            }
            if (weight > 0) (sum / weight / 32768.0).toFloat() else 0f
        }
    }
}
