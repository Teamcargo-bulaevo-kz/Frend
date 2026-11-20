package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Свечение из центра круглой обложки. Цвета задаёт настроение песни (или выбранная палитра) и
 * меняются только плавно при смене песни или её части. Ритм даёт дыхание: свечение расширяется на ударах
 * и мягко сжимается. Пульс берётся из анализатора звука; если его нет, идёт по темпу песни (BPM).
 * Без колец, частиц и случайных цветов.
 */
class AuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Центр свечения по вертикали в пикселях (за обложкой); пока не задан, берётся 40% высоты. */
    var centerYPx = -1f

    /** Радиус круглой обложки в пикселях: цветной ореол начинается от её края. */
    var coverRadiusPx = 0f

    /** Общая сила свечения: 1 для плеера, меньше для списка, чтобы не мешать читать. */
    var intensity = 1f

    /** Тонкий светлый ободок у края обложки (нужен только в плеере). */
    var showRim = true

    private var target = MoodPalette.DEFAULT
    private var baseColor = target.color
    private var brightness = target.brightness
    private var reactivity = target.reactivity
    private var colorReady = false

    // сглаженные показатели 0..1
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var pulse = 0f
    private var beatPhase = 0f
    /** Детектор ударов одной полосы частот: порог над средним уровнем и пауза между ударами. */
    private class Onset { var mean = 0f; var prev = 0f; var since = 10f; var bump = 0f }
    private val low = Onset()
    private val midB = Onset()
    private val high = Onset()

    private var thr = 1f
    private var swell = 0f
    private var shimmerV = 0f
    private var midAvg = 0f
    private var breathT = 0f

    private fun detect(o: Onset, v: Float, dt: Float, minGap: Float) {
        o.mean += (v - o.mean) * (1f - exp(-0.9f * dt))
        o.since += dt
        val threshold = maxOf(0.45f, o.mean * 1.45f + 0.12f) * thr
        if (v > threshold && v - o.prev > 0.04f && o.since >= minGap) {
            o.since = 0f
            o.bump = maxOf(o.bump, ((v - o.mean) * 1.4f + 0.35f).coerceIn(0.25f, 1f))
        }
        o.prev = v
    }

    private var lastMs = 0L
    private var attached = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)

    fun setAura(aura: Aura) { target = aura }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; lastMs = 0L; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { attached = false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { lastMs = 0L; postInvalidateOnAnimation() }
    }

    /** EMA с разной скоростью роста и затухания; зависит от времени кадра, а не от частоты кадров. */
    private fun ema(cur: Float, raw: Float, dt: Float, attack: Float, release: Float): Float {
        val rate = if (raw > cur) attack else release
        return cur + (raw - cur) * (1f - exp(-rate * dt))
    }

    private fun tone(h: Float, s: Float, v: Float): Int {
        hsv[0] = (h % 360f + 360f) % 360f; hsv[1] = s.coerceIn(0f, 1f); hsv[2] = v.coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Цвет-цель: настроение песни, готовая палитра или своя. */
    private fun targetColor(): Int {
        val mode = MusicSettings.paletteMode
        return when {
            mode == 0 -> target.color
            mode == 8 -> tone(MusicSettings.customHue.toFloat(), 1f, 1f)
            else -> tone(MoodPalette.fixed[(mode - 1).coerceIn(0, MoodPalette.fixed.lastIndex)].second.toFloat(), 1f, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastMs = now
        BeatSource.tick(PlayerState.playing && MusicService.isRunning)
        val playing = PlayerState.playing && MusicService.isRunning

        // Смена цвета: только плавный переход к цели (скорость в настройках, по умолчанию около 1.5 с)
        val rate = 0.4f + 3.2f * (MusicSettings.colorSpeed / 100f)
        val k = 1f - exp(-rate * dt)
        val tc = targetColor()
        if (!colorReady) { baseColor = tc; colorReady = true }
        val prevBase = baseColor
        baseColor = ColorUtils.blendARGB(baseColor, tc, k)
        val colorMoving = baseColor != prevBase
        brightness += (target.brightness - brightness) * k
        reactivity += (target.reactivity - reactivity) * k

        val sens = MusicSettings.beatSensitivity / 100f * (0.8f + 0.5f * reactivity)
        fun boost(x: Float) = (sqrt(x.coerceIn(0f, 1f)) * sens).coerceIn(0f, 1f)
        mid = ema(mid, boost(BeatSource.mid), dt, 6f, 1.5f)
        treble = ema(treble, boost(BeatSource.treble), dt, 8f, 2f)

        // Пульс по трём полосам: низкие (бочка/бас) сильнее всего, средние слабее, высокие слабее всех (веса в настройках).
        // Удар в каждой полосе ищем по порогу над средним уровнем; чаще заданного числа раз в секунду не пульсируем,
        // а на быстрых песнях (больше 110 BPM) пульсируем через удар.
        val live = BeatSource.isLive
        val bpm = target.bpm.coerceIn(0, 220)
        val divisor = if (bpm > 110) 2 else 1
        val period = if (bpm > 0) 60f / bpm * divisor else 0.5f
        val minGap = maxOf(1f / MusicSettings.maxPulsesPerSec, period * 0.7f)
        val ampScale = (0.25f + 0.75f * reactivity.coerceIn(0f, 1f)) * (MusicSettings.pulseAmplitude / 100f)
        thr = MusicSettings.onsetThreshold / 100f
        val wLow = MusicSettings.bandLow / 100f
        val wMid = MusicSettings.bandMid / 100f
        val wHigh = MusicSettings.bandHigh / 100f
        var raw = 0f
        if (playing && live) {
            // Три полосы со своими детекторами ударов: низкие пульсируют сильнее, средние слабее, высокие слабее всех.
            // источник 2 («любые удары») берёт рост спектра: ловит бочку, бас-линию, снейр, голос и тарелки
            val src = MusicSettings.pulseSource
            val lowNow = when (src) { 1 -> BeatSource.bass; 2 -> maxOf(BeatSource.kick, BeatSource.fluxLow); else -> BeatSource.kick }
            val midNow = if (src == 2) BeatSource.fluxMid else BeatSource.mid
            val highNow = if (src == 2) BeatSource.fluxHigh else BeatSource.treble
            detect(low, lowNow, dt, minGap)
            detect(midB, midNow, dt, maxOf(0.3f, minGap * 0.7f))
            detect(high, highNow, dt, maxOf(0.25f, minGap * 0.5f))
            low.bump = ema(low.bump, 0f, dt, 1f, 2.2f)
            midB.bump = ema(midB.bump, 0f, dt, 1f, 2.6f)
            high.bump = ema(high.bump, 0f, dt, 1f, 3.2f)
            raw = (wLow * low.bump + wMid * 0.7f * midB.bump + wHigh * 0.5f * high.bump).coerceIn(0f, 1f)
        } else if (playing && bpm > 0 && MusicSettings.tempoLock) {
            // нет сигнала звука: ровное дыхание по темпу песни
            beatPhase = (beatPhase + dt * bpm / 60f / divisor) % 1f
            raw = 0.7f * wLow * (0.5f * (1f + cos(beatPhase * 2f * PI.toFloat()))).pow(1.5f)
        } else {
            low.bump = 0f; midB.bump = 0f; high.bump = 0f
        }
        // дыхание между ударами: медленное колебание, чтобы свечение не застывало на спокойных песнях
        breathT = (breathT + dt) % 6f
        val breath = 0.5f + 0.5f * kotlin.math.sin(breathT / 6f * 2f * PI.toFloat())
        raw = maxOf(raw, 0.18f * (MusicSettings.idleBreath / 100f) * breath)
        val smooth = MusicSettings.pulseSmooth / 100f
        val atk = 0.4f + 1.2f * (MusicSettings.pulseAttack / 100f)
        val rel = 0.4f + 1.2f * (MusicSettings.pulseRelease / 100f)
        pulse = ema(pulse, if (playing) raw else 0f, dt, (14f - 6f * smooth) * atk, (3.2f - 1.6f * smooth) * rel)
        bass = pulse

        // Непрерывные движения между ударами: набухание от баса и громкости, дрожь от верхов, игра оттенка от середины.
        val lively = MusicSettings.liveliness / 100f
        val shimmerK = MusicSettings.shimmer / 100f
        val swellRaw = if (playing && live) 0.6f * BeatSource.bass + 0.4f * BeatSource.level else 0f
        swell = ema(swell, swellRaw, dt, 5f, 1.4f)
        shimmerV = ema(shimmerV, if (playing && live) treble else 0f, dt, 9f, 2.5f)
        midAvg = ema(midAvg, mid, dt, 0.4f, 0.4f)
        val sway = ((mid - midAvg) * 2f * 18f * (MusicSettings.hueSway / 100f)).coerceIn(-18f, 18f)


        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(BG)
        val cx = w / 2f
        val cy = if (centerYPx > 0f) centerYPx else h * 0.40f
        val gain = MusicSettings.auraBrightness / 100f * intensity
        val cr = if (coverRadiusPx > 0f) coverRadiusPx else w * 0.25f

        // дыхание: радиус от базового размера до базового + размах, яркость тоже чуть дышит
        val size = MusicSettings.glowSize / 100f
        val outerR = cr * (2.0f * size + 1.0f * ampScale * pulse + 0.35f * lively * swell * ampScale.coerceAtMost(1.5f))
        val a = (gain * (0.50f + 0.40f * pulse * ampScale.coerceAtMost(1.2f) * (MusicSettings.brightnessPulse / 100f) + 0.12f * lively * swell + 0.08f * shimmerK * shimmerV) * (0.85f + 0.15f * brightness)).coerceIn(0f, 1f)
        val f = (cr / outerR).coerceIn(0.05f, 0.9f)

        // оттенок задан настроением; соседние цвета расходятся от центра на фиксированные углы
        Color.colorToHSV(baseColor, hsv)
        val hue = hsv[0] + sway
        val sat = (MusicSettings.paletteSaturation / 100f * (0.92f + 0.08f * mid)).coerceIn(0f, 1f)
        val fan = 0.15f + 1.25f * (MusicSettings.paletteSpread / 100f)
        val c0 = tone(hue, sat, 1f)
        val c1 = tone(hue + 22f * fan, sat, 1f)
        val c2 = tone(hue + 48f * fan, sat, 0.95f)
        val c3 = tone(hue + 75f * fan, sat, 0.9f)
        fun al(x: Float) = (a * x * 255f).toInt().coerceIn(0, 255)
        paint.shader = RadialGradient(
            cx, cy, outerR,
            intArrayOf(
                ColorUtils.setAlphaComponent(c0, al(1f)),
                ColorUtils.setAlphaComponent(c0, al(1f)),
                ColorUtils.setAlphaComponent(c1, al(0.75f)),
                ColorUtils.setAlphaComponent(c2, al(0.38f)),
                ColorUtils.setAlphaComponent(c3, al(0.12f)),
                0x00000000
            ),
            floatArrayOf(0f, f, f + (1f - f) * 0.22f, f + (1f - f) * 0.5f, f + (1f - f) * 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        if (showRim) {
            // тонкий светлый ободок у края обложки
            val rim = ColorUtils.blendARGB(c0, 0xFFFFFFFF.toInt(), 0.30f)
            val ra = (0.40f + 0.45f * pulse) * gain
            paint.shader = RadialGradient(
                cx, cy, cr * 1.20f,
                intArrayOf(0x00000000, ColorUtils.setAlphaComponent(rim, (ra * 255).toInt().coerceIn(0, 255)),
                    ColorUtils.setAlphaComponent(rim, (ra * 0.3f * 255).toInt().coerceIn(0, 255)), 0x00000000),
                floatArrayOf(0f, 0.95f / 1.20f, 1.06f / 1.20f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        if (attached && visibility == VISIBLE && windowVisibility == VISIBLE) {
            // в покое (пауза, цвет дошёл до цели, пульс угас) кадры не нужны: проверяем 4 раза в секунду
            val idle = !playing && pulse < 0.003f && !colorMoving
            postInvalidateDelayed(if (idle) 250L else 1000L / MusicSettings.fps)
        }
    }

    private companion object {
        const val BG = 0xFF020206.toInt()
    }
}

/** Окрашивает свечение по настроению песни (или нейтрально, если песня не размечена). */
fun AuraView.applyTrack(context: Context, track: MusicTrack?) {
    val tg = track?.let { MusicTags.get(context, it) }?.takeIf { MusicTags.isTagged(it) }
    setAura(if (tg == null) MoodPalette.DEFAULT else MoodPalette.from(tg.moodScores.ifEmpty { mapOf(tg.mood to 100) }, tg.arousal, tg.bpm))
}
