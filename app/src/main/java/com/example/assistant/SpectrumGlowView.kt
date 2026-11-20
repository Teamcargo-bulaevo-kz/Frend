package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Harmonia: единая текучая световая волна вместо набора столбцов.
 * Частоты идут слева направо: глубокий бас — тёмный, верх — светлый.
 * Форма спектра остаётся цельной и плавной.
 */
class SpectrumGlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val glowPath = Path()
    private val pointCount = 56
    private val levels = FloatArray(pointCount)
    private val smoothed = FloatArray(pointCount)
    @Volatile private var fft: ByteArray? = null
    private var lastFrameNanos = 0L
    private var previousEnergy = 0f
    private var pulse = 0f
    private var paletteEnergy = 0f
    private var paletteStep = -1
    private var hasSignal = false
    private var gradient: LinearGradient? = null
    private var glowGradient: LinearGradient? = null
    private var phase = 0f

    fun setFftData(data: ByteArray?) {
        fft = data?.clone()
        hasSignal = data != null
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        updatePalette(w.toFloat(), 0f, force = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else
            ((now - lastFrameNanos) / 1e9).toFloat().coerceIn(0.008f, 0.05f)
        lastFrameNanos = now
        phase = (phase + dt * 0.42f) % 6.28318f

        val data = fft
        val bins = data?.size?.div(2) ?: 0
        var energy = 0f
        if (data != null && bins > 8) {
            val usable = (bins - 2).coerceAtLeast(2)
            for (i in 0 until pointCount) {
                // Логарифмическая шкала: бас получает больше места, верхние частоты не сжимаются.
                val position = (i + 0.5f) / pointCount
                val maxBin = (bins - 2).toFloat()
                val bin = exp(ln(maxBin) * position).toInt().coerceIn(1, bins - 2)
                var magnitude = 0.0
                for (offset in -1..1) {
                    val b = (bin + offset).coerceIn(1, bins - 2)
                    val re = data[b * 2].toInt()
                    val im = data[b * 2 + 1].toInt()
                    val weight = if (offset == 0) 0.5 else 0.25
                    magnitude += sqrt((re * re + im * im).toDouble()) * weight
                }
                val normalized = (ln(1.0 + magnitude * 2.0) / ln(1.0 + 180.0)).toFloat()
                val target = (normalized * 0.82f).coerceIn(0.025f, 0.82f)
                val tau = if (target > levels[i]) 0.38f else 0.82f
                val blend = 1f - exp(-dt / tau)
                levels[i] += (target - levels[i]) * blend
                energy += levels[i] / pointCount
            }
            val attack = (energy - previousEnergy).coerceAtLeast(0f)
            pulse += ((attack * 2.2f).coerceIn(0f, 0.12f) - pulse) *
                (1f - exp(-dt / 0.32f))
            previousEnergy = energy
        } else {
            for (i in levels.indices) levels[i] *= (1f - exp(-dt / 0.75f))
            pulse *= (1f - exp(-dt / 0.55f))
            energy = 0f
        }
        paletteEnergy += (energy.coerceIn(0f, 0.72f) - paletteEnergy) * (1f - exp(-dt / 2.8f))
        updatePalette(width.toFloat(), paletteEnergy)

        // Пространственное сглаживание соединяет соседние зоны в одно текучее поле.
        for (i in 0 until pointCount) {
            val left = levels[(i - 1).coerceAtLeast(0)]
            val center = levels[i]
            val right = levels[(i + 1).coerceAtMost(pointCount - 1)]
            smoothed[i] = left * 0.18f + center * 0.64f + right * 0.18f
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val base = h * 0.96f
        val minY = h * 0.08f
        val amplitude = h * 0.88f
        val points = FloatArray(pointCount * 2)
        for (i in 0 until pointCount) {
            val x = w * i / (pointCount - 1)
            val organic = sin(phase + i * 0.13f) * 0.006f +
                sin(phase * 0.57f - i * 0.08f) * 0.004f
            val level = (smoothed[i] + organic + pulse * 0.12f).coerceIn(0.012f, 0.98f)
            points[i * 2] = x
            points[i * 2 + 1] = (base - amplitude * level).coerceIn(minY, base)
        }

        // Низкая широкая аура: никаких отдельных светящихся элементов.
        buildWave(glowPath, points, base, h * 0.045f)
        paint.shader = glowGradient
        paint.style = Paint.Style.FILL
        paint.alpha = 58
        paint.setShadowLayer(h * 0.12f, 0f, 0f, Color.rgb(151, 116, 255))
        canvas.drawPath(glowPath, paint)

        // Основная волна — цельная полупрозрачная форма с мягким краем.
        buildWave(path, points, base, h * 0.012f)
        paint.clearShadowLayer()
        paint.shader = gradient
        paint.alpha = 118
        canvas.drawPath(path, paint)

        // Тонкая световая сердцевина повторяет волну, но не дробит её на сегменты.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * 0.009f).coerceAtLeast(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.alpha = 215
        paint.setShadowLayer(h * 0.045f, 0f, 0f, Color.rgb(139, 205, 255))
        val line = Path()
        for (i in 0 until pointCount) {
            val x = points[i * 2]
            val y = points[i * 2 + 1] - h * 0.012f
            if (i == 0) line.moveTo(x, y) else {
                val px = points[(i - 1) * 2]
                val py = points[(i - 1) * 2 + 1] - h * 0.012f
                val midX = (px + x) * 0.5f
                val midY = (py + y) * 0.5f
                line.quadTo(px, py, midX, midY)
                if (i == pointCount - 1) line.lineTo(x, y)
            }
        }
        canvas.drawPath(line, paint)

        paint.clearShadowLayer()
        paint.shader = null
        paint.style = Paint.Style.FILL
        if (hasSignal) postInvalidateOnAnimation()
    }


    private fun updatePalette(w: Float, energy: Float, force: Boolean = false) {
        if (w <= 0f) return
        val step = (energy.coerceIn(0f, 0.72f) / 0.72f * 32f).toInt().coerceIn(0, 32)
        if (!force && step == paletteStep) return
        paletteStep = step
        val t = step / 32f
        // Цвет привязан к частоте: тёмный бас, насыщенная середина, светлый верх.
        val calm = intArrayOf(
            Color.rgb(14, 22, 78), Color.rgb(36, 40, 140), Color.rgb(72, 58, 190),
            Color.rgb(58, 96, 224), Color.rgb(112, 140, 255), Color.rgb(196, 208, 255)
        )
        val lively = intArrayOf(
            Color.rgb(30, 32, 128), Color.rgb(68, 52, 206), Color.rgb(124, 70, 240),
            Color.rgb(66, 128, 255), Color.rgb(134, 170, 255), Color.rgb(228, 232, 255)
        )
        val colors = IntArray(calm.size) { i -> blend(calm[i], lively[i], t) }
        val stops = floatArrayOf(0f, 0.16f, 0.38f, 0.61f, 0.83f, 1f)
        gradient = LinearGradient(0f, 0f, w, 0f, colors, stops, Shader.TileMode.CLAMP)
        glowGradient = LinearGradient(0f, 0f, w, 0f, colors, stops, Shader.TileMode.CLAMP)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun buildWave(target: Path, points: FloatArray, baseline: Float, padding: Float) {
        target.reset()
        target.moveTo(points[0], baseline)
        target.lineTo(points[0], points[1] - padding)
        for (i in 1 until pointCount) {
            val prevX = points[(i - 1) * 2]
            val prevY = points[(i - 1) * 2 + 1] - padding
            val x = points[i * 2]
            val y = points[i * 2 + 1] - padding
            val midX = (prevX + x) * 0.5f
            val midY = (prevY + y) * 0.5f
            target.quadTo(prevX, prevY, midX, midY)
            if (i == pointCount - 1) target.quadTo(x, y, x, y)
        }
        target.lineTo(points[(pointCount - 1) * 2], baseline)
        target.close()
    }
}
