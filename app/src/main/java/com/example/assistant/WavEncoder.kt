package com.example.assistant

import java.io.ByteArrayOutputStream

/** Упаковка сырого звука (16 бит, моно) в WAV-файл, чтобы отправить на распознавание. Чистый Kotlin. */
object WavEncoder {

    fun encode(samples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun int(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(dataSize)
        for (s in samples) short(s.toInt())
        return out.toByteArray()
    }

    /** Громкость куска звука (среднеквадратичная), чтобы отличать речь от тишины. */
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return Math.sqrt(sum / count)
    }
}
