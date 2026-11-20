package com.example.assistant

import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Единственный владелец системного анализатора звука [Visualizer].
 * Из него берутся: [bass] (удар, 0..1), [level] (общая громкость, 0..1) и сырой спектр для [SpectrumGlowView]
 * через [fftListener]. Значения подстраиваются под громкость сами, поэтому фон одинаково живо ходит
 * и на тихом, и на громком звуке.
 *
 * Нужно разрешение «Запись звука» (микрофон при этом не включается). Если его нет или прошивка не даёт
 * анализатор, пока играет музыка, фон живёт по мягкому условному ритму.
 */
object BeatSource {
    @Volatile var bass = 0f
        private set
    @Volatile var level = 0f
        private set

    /** Средние (300-2000 Гц) и высокие (2000-8000 Гц) частоты, 0..1. Сглаживает потребитель. */
    /** Только удар бочки (40-110 Гц), 0..1, без сглаживания: по нему ищутся удары. */
    @Volatile var kick = 0f
        private set

    @Volatile var mid = 0f
        private set
    @Volatile var treble = 0f
        private set

    /** Рост спектра (удары и атаки звука) в трёх областях, 0..1 без сглаживания: низ, середина, верх. Ловит любые инструменты. */
    @Volatile var fluxLow = 0f
        private set
    @Volatile var fluxMid = 0f
        private set
    @Volatile var fluxHigh = 0f
        private set

    /** Получает копию спектра (re, im попарно) на потоке анализатора. Ставится на время жизни экрана плеера. */
    @Volatile var fftListener: ((ByteArray) -> Unit)? = null

    private var visualizer: Visualizer? = null
    private var live = false
    @Volatile private var lastRealFrame = 0L
    private var bassPeak = 12f
    private var levelPeak = 6f
    private var midPeak = 6f
    private var kickPeak = 10f
    private var treblePeak = 4f
    private var fluxLowPeak = 3f
    private var fluxMidPeak = 2f
    private var fluxHighPeak = 1.5f
    private var prevMag = FloatArray(0)

    /** true, если приходят настоящие данные звука, а не условный ритм. */
    val isLive: Boolean get() = live && SystemClock.uptimeMillis() - lastRealFrame < 600

    fun attach(sessionId: Int) {
        detach()
        if (sessionId <= 0) return // 0 это общий микс всего телефона, его не трогаем
        try {
            val range = Visualizer.getCaptureSizeRange()
            val v = Visualizer(sessionId)
            v.enabled = false
            v.captureSize = minOf(1024, range[1]).coerceAtLeast(range[0])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) {
                        process(fft, samplingRate)
                        fftListener?.invoke(fft)
                    }
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            visualizer = v
            live = true
        } catch (e: Throwable) {
            // Нет разрешения или прошивка не поддерживает Visualizer: оставляем свечение без пульсации.
            visualizer = null
            live = false
        }
    }

    fun detach() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Throwable) {
            // игнорируем: объект и так выбрасывается
        }
        visualizer = null
        live = false
        bass = 0f
        level = 0f
        mid = 0f
        treble = 0f
        kick = 0f
        fluxLow = 0f
        fluxMid = 0f
        fluxHigh = 0f
    }

    private fun process(fft: ByteArray, samplingRateMilliHz: Int) {
        val bins = fft.size / 2
        if (bins < 4) return
        val binHz = samplingRateMilliHz / 1000f / 2f / bins
        if (binHz <= 0f) return
        var bassSum = 0f
        var bassN = 0
        var allSum = 0f
        var allN = 0
        var kickSum = 0f
        var kickN = 0
        var midSum = 0f
        var midN = 0
        var trebleSum = 0f
        var trebleN = 0
        var fLowSum = 0f; var fLowN = 0
        var fMidSum = 0f; var fMidN = 0
        var fHighSum = 0f; var fHighN = 0
        if (prevMag.size != bins) prevMag = FloatArray(bins)
        val lowCut = MusicSettings.freqLow.toFloat()
        val highCut = MusicSettings.freqHigh.toFloat()
        for (k in 1 until bins) {
            val f = k * binHz
            if (f > highCut) break
            val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            val grow = max(0f, m - prevMag[k])
            prevMag[k] = m
            if (f >= lowCut && f < 250f) { fLowSum += grow; fLowN++ }
            else if (f >= 250f && f < 2000f) { fMidSum += grow; fMidN++ }
            else if (f >= 2000f) { fHighSum += grow; fHighN++ }
            if (f > 8000f) continue
            if (f in 40f..220f) { bassSum += m; bassN++ }
            if (f in 40f..110f) { kickSum += m; kickN++ }
            if (f in 300f..2000f) { midSum += m; midN++ }
            if (f >= 2000f) { trebleSum += m; trebleN++ }
            if (f <= 4000f) { allSum += m; allN++ }
        }
        val rawBass = if (bassN > 0) bassSum / bassN else 0f
        val rawLevel = if (allN > 0) allSum / allN else 0f
        // Автоподстройка: «потолок» быстро растёт на пике и медленно опускается
        bassPeak = max(bassPeak * 0.992f, max(rawBass, 6f))
        levelPeak = max(levelPeak * 0.992f, max(rawLevel, 3f))
        val b = (rawBass / bassPeak).coerceIn(0f, 1f).pow(1.4f)
        val l = (rawLevel / levelPeak).coerceIn(0f, 1f).pow(1.2f)
        val rawKick = if (kickN > 0) kickSum / kickN else 0f
        kickPeak = max(kickPeak * 0.995f, max(rawKick, 6f))
        kick = (rawKick / kickPeak).coerceIn(0f, 1f)
        val rawMid = if (midN > 0) midSum / midN else 0f
        val rawTreble = if (trebleN > 0) trebleSum / trebleN else 0f
        midPeak = max(midPeak * 0.992f, max(rawMid, 3f))
        treblePeak = max(treblePeak * 0.992f, max(rawTreble, 2f))
        mid = (rawMid / midPeak).coerceIn(0f, 1f)
        treble = (rawTreble / treblePeak).coerceIn(0f, 1f)
        // быстро растём на ударе, плавно затухаем
        bass = if (b > bass) bass + (b - bass) * 0.75f else bass * 0.88f + b * 0.12f
        level = if (l > level) level + (l - level) * 0.5f else level * 0.93f + l * 0.07f
        // Рост спектра: у каждой области свой «потолок», поэтому реагирует и тихий, и громкий звук
        val rawFL = if (fLowN > 0) fLowSum / fLowN else 0f
        val rawFM = if (fMidN > 0) fMidSum / fMidN else 0f
        val rawFH = if (fHighN > 0) fHighSum / fHighN else 0f
        fluxLowPeak = max(fluxLowPeak * 0.99f, max(rawFL, 2f))
        fluxMidPeak = max(fluxMidPeak * 0.99f, max(rawFM, 1.5f))
        fluxHighPeak = max(fluxHighPeak * 0.99f, max(rawFH, 1f))
        fluxLow = (rawFL / fluxLowPeak).coerceIn(0f, 1f)
        fluxMid = (rawFM / fluxMidPeak).coerceIn(0f, 1f)
        fluxHigh = (rawFH / fluxHighPeak).coerceIn(0f, 1f)
        lastRealFrame = SystemClock.uptimeMillis()
    }

    /** Вызывается из рисующего вида. При отсутствии реального сигнала значения плавно затухают. */
    fun tick(playing: Boolean) {
        if (!playing) {
            bass *= 0.92f
            level *= 0.95f
            mid *= 0.95f
            treble *= 0.95f
            kick *= 0.9f
            fluxLow *= 0.8f; fluxMid *= 0.8f; fluxHigh *= 0.8f
            return
        }
        if (isLive) return
        bass *= 0.88f
        level *= 0.94f
        mid *= 0.94f
        treble *= 0.94f
        fluxLow *= 0.8f; fluxMid *= 0.8f; fluxHigh *= 0.8f
    }
}
