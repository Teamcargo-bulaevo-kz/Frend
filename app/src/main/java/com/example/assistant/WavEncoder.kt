package com.example.assistant

import java.io.ByteArrayOutputStream

/** Кодирует PCM 16-bit mono в WAV для передачи в Whisper. */
object WavEncoder {
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun int(value: Int) {
            out.write(value and 0xff); out.write(value shr 8 and 0xff)
            out.write(value shr 16 and 0xff); out.write(value shr 24 and 0xff)
        }
        fun short(value: Int) { out.write(value and 0xff); out.write(value shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + dataSize)
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(dataSize)
        samples.forEach { short(it.toInt()) }
        return out.toByteArray()
    }

    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return kotlin.math.sqrt(sum / count)
    }
}
