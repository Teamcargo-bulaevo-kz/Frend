package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Лёгкий профиль настроения: до четырёх ведущих оценок без сторонних графических библиотек. */
class MoodProfileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xFFD5DCE8.toInt()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x333F5268 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = 0xFF9EACC0.toInt()
        textAlign = Paint.Align.RIGHT
    }
    private val colors = intArrayOf(
        0xFF55C9B5.toInt(), 0xFFFFB86B.toInt(), 0xFF8E9EFF.toInt(),
        0xFFE99BC7.toInt(), 0xFF73BDE8.toInt()
    )
    private var rows: List<Pair<String, Int>> = emptyList()

    fun setScores(scores: Map<String, Int>) {
        rows = scores.entries.sortedByDescending { it.value }.take(4)
            .map { (key, value) -> (MusicTags.moodShort[key] ?: key) to value.coerceIn(0, 100) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        val left = 2f * density
        val labelWidth = 88f * density
        val right = width - 34f * density
        val barHeight = 4f * density
        val rowHeight = 22f * density
        rows.forEachIndexed { index, (label, score) ->
            val top = index * rowHeight
            canvas.drawText(label, left, top + 10f * density, textPaint)
            canvas.drawText("$score%", width - 2f * density, top + 10f * density, valuePaint)
            val rect = RectF(labelWidth, top + 14f * density, max(labelWidth + 1f, right), top + 14f * density + barHeight)
            canvas.drawRoundRect(rect, barHeight, barHeight, trackPaint)
            fillPaint.color = colors[index % colors.size]
            val fill = RectF(rect.left, rect.top, rect.left + rect.width() * score / 100f, rect.bottom)
            if (score > 0) canvas.drawRoundRect(fill, barHeight, barHeight, fillPaint)
        }
    }
}
