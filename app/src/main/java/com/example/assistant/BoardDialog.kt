package com.example.assistant

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

/**
 * Окно доски: ассистент объясняет как учитель у доски. Фигуры каждого шага прорисовываются на глазах,
 * под доской — пояснение шага, по желанию оно звучит голосом. «Дальше» и «Назад» листают шаги,
 * «▶/⏸» — сам ведёт объяснение до конца, «↻» — повторить шаг.
 */
object BoardDialog {

    @SuppressLint("ViewConstructor")
    class BoardView(context: Context, val drawing: Visual.Drawing) : View(context) {
        var step = 1
        var progress = 1f
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * drawing.h / drawing.w).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            val k = width / drawing.w.toFloat()
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f, 18f, Path.Direction.CW) })
            canvas.scale(k, k)
            BoardPainter.paint(canvas, drawing, step, progress)
            canvas.restore()
        }
    }

    private const val PREFS = "assistant_prefs"

    fun show(context: Context, d: Visual.Drawing) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = d.steps.coerceAtLeast(1)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val board = BoardView(context, d)
        val note = TextView(context).apply {
            textSize = 16.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), dp(12), dp(4), dp(6))
            minLines = 2
        }
        val counter = TextView(context).apply { textSize = 13f; alpha = 0.7f; gravity = Gravity.CENTER }
        fun button(t: String, desc: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; textSize = 17f; contentDescription = desc; minWidth = dp(52); setPadding(0, 0, 0, 0)
        }
        val back = button("‹", "Назад"); val play = button("⏸", "Пауза"); val again = button("↻", "Повторить шаг")
        val voice = button("🔊", "Озвучка"); val next = button("›", "Дальше")
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            listOf(back, play, again, voice, next).forEach { addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(4))
            if (d.title.isNotBlank()) addView(TextView(context).apply { text = d.title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(4), 0, 0, dp(10)) })
            addView(board, LinearLayout.LayoutParams(-1, -2))
            addView(note)
            addView(counter)
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        val handler = Handler(Looper.getMainLooper())
        var animator: ValueAnimator? = null
        var auto = true
        var speak = prefs.getBoolean("board_voice", Speaker.enabled(context))
        var current = 1
        var closed = false

        fun refreshButtons() {
            back.isEnabled = current > 1
            next.isEnabled = current < total
            play.text = if (auto && current < total) "⏸" else "▶"
            voice.alpha = if (speak) 1f else 0.4f
            counter.text = "Шаг $current из $total"
        }

        lateinit var go: (Int, Boolean) -> Unit
        // Автопоказ: после прорисовки ждём, пока договорит, и идём дальше
        val advance = object : Runnable {
            override fun run() {
                if (closed || !auto) return
                if (animator?.isRunning == true || (speak && Speaker.isSpeaking())) { handler.postDelayed(this, 300); return }
                if (current < total) handler.postDelayed({ if (!closed && auto && animator?.isRunning != true) go(current + 1, true) }, 900)
                else { auto = false; refreshButtons() }
            }
        }

        go = { k, animate ->
            current = k.coerceIn(1, total)
            handler.removeCallbacksAndMessages(null)
            animator?.cancel()
            board.step = current
            val text = d.notes.getOrNull(current - 1).orEmpty()
            note.text = text
            refreshButtons()
            val count = d.shapes.count { it.step == current }
            if (animate && count > 0) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = (count * 520L).coerceIn(700L, 3200L)
                    interpolator = LinearInterpolator()
                    addUpdateListener { board.progress = it.animatedValue as Float; board.invalidate() }
                    start()
                }
            } else { board.progress = 1f; board.invalidate() }
            if (speak && text.isNotBlank()) Speaker.speak(context, text) else Speaker.stop()
            if (auto) handler.postDelayed(advance, 400)
        }

        back.setOnClickListener { auto = false; go(current - 1, false) }
        next.setOnClickListener { go(current + 1, true) }
        again.setOnClickListener { go(current, true) }
        play.setOnClickListener {
            if (auto && current < total) { auto = false; handler.removeCallbacksAndMessages(null); refreshButtons() }
            else { auto = true; if (current >= total) go(1, true) else go(current + 1, true) }
        }
        voice.setOnClickListener {
            speak = !speak
            prefs.edit().putBoolean("board_voice", speak).apply()
            if (!speak) Speaker.stop()
            refreshButtons()
        }

        val dialog = AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Сохранить") { _, _ -> VisualDialog.save(context, d) }
            .setNegativeButton("Поделиться") { _, _ -> VisualDialog.share(context, d) }
            .create()
        dialog.setOnDismissListener {
            closed = true
            handler.removeCallbacksAndMessages(null)
            animator?.cancel()
            Speaker.stop()
        }
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.96f).toInt(), -2)
        go(1, true)
    }
}
