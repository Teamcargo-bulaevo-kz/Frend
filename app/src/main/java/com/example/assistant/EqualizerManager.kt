package com.example.assistant

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/**
 * Эквалайзер и усиление баса для воспроизведения приложения (Equalizer и BassBoost на сессии плеера).
 * Звук телефона в целом не трогаем. Если прошивка не даёт эффекты, настройки просто не действуют.
 */
object EqualizerManager {

    /** Параметры эквалайзера устройства. */
    data class Info(val bands: Int, val minMb: Int, val maxMb: Int, val centerHz: List<Int>)

    /** Типичные значения, пока плеер не запущен и устройство не опрошено. */
    val DEFAULT_INFO = Info(5, -1500, 1500, listOf(60, 230, 910, 3600, 14000))

    /** Готовые настройки для пяти полос (в дБ), на других числах полос растягиваются. */
    val presets: List<Pair<String, List<Int>>> = listOf(
        "Плоский" to listOf(0, 0, 0, 0, 0),
        "Больше баса" to listOf(6, 4, 0, 0, 0),
        "Чёткий вокал" to listOf(-2, 0, 3, 3, 1),
        "Яркий" to listOf(2, 0, 0, 3, 5),
        "Мягкий" to listOf(2, 1, 0, -2, -4),
        "Вечер" to listOf(3, 2, 0, -1, -2)
    )

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    @Volatile var info: Info = DEFAULT_INFO
        private set
    private var attempted = false

    @Synchronized
    fun attach(sessionId: Int) {
        release()
        // Эффекты создаём только когда они нужны: меньше риска для воспроизведения
        if (sessionId <= 0 || !(MusicSettings.eqEnabled || MusicSettings.bassBoost > 0)) return
        attempted = true
        try {
            val e = Equalizer(0, sessionId)
            val range = e.bandLevelRange
            info = Info(e.numberOfBands.toInt(), range[0].toInt(), range[1].toInt(),
                (0 until e.numberOfBands.toInt()).map { e.getCenterFreq(it.toShort()) / 1000 })
            eq = e
        } catch (t: Throwable) { eq = null }
        try { bass = BassBoost(0, sessionId) } catch (t: Throwable) { bass = null }
        apply()
    }

    @Synchronized
    fun release() {
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        eq = null; bass = null
        attempted = false
    }

    /** Растягивает список уровней под нужное число полос. */
    fun resample(levels: List<Int>, n: Int): List<Int> {
        if (levels.isEmpty() || n <= 0) return List(n.coerceAtLeast(0)) { 0 }
        if (levels.size == n) return levels
        return List(n) { i ->
            val pos = if (n == 1) 0f else i * (levels.size - 1).toFloat() / (n - 1)
            val lo = pos.toInt().coerceAtMost(levels.size - 1)
            val hi = (lo + 1).coerceAtMost(levels.size - 1)
            val t = pos - lo
            (levels[lo] * (1 - t) + levels[hi] * t).toInt()
        }
    }

    /** Применяет сохранённые настройки к работающим эффектам. */
    @Synchronized
    fun apply() {
        val on = MusicSettings.eqEnabled
        if (on && eq == null && bass == null && !attempted) {
            val sid = MusicService.instance?.audioSessionId ?: 0
            if (sid > 0) { attach(sid); return }
        }
        eq?.let { e ->
            try {
                val levels = resample(MusicSettings.eqLevels, info.bands)
                for (i in 0 until info.bands) {
                    val mb = if (on) levels.getOrElse(i) { 0 }.coerceIn(info.minMb, info.maxMb) else 0
                    e.setBandLevel(i.toShort(), mb.toShort())
                }
                e.enabled = on
            } catch (_: Throwable) {}
        }
        bass?.let { b ->
            try {
                val strength = if (on) MusicSettings.bassBoost * 10 else 0
                if (b.strengthSupported) b.setStrength(strength.coerceIn(0, 1000).toShort())
                b.enabled = on && strength > 0
            } catch (_: Throwable) {}
        }
    }
}
