package com.example.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Рисует наглядный блок ([Visual]) прямо на Canvas: цветные диаграммы, таблицы, деревья с ветками, списки,
 * плитки статистики, шаги, план с галочками и рисунки фигурами. Тёмная карточка с яркой палитрой читается
 * и в светлой, и в тёмной теме чата.
 */
@SuppressLint("ViewConstructor")
class VisualView(context: Context, val visual: Visual, private val scaleText: Float = 1f) : View(context) {

    companion object {
        val palette = intArrayOf(
            0xFF7C6CFF.toInt(), 0xFF2EC4B6.toInt(), 0xFFFF6B6B.toInt(), 0xFFF4A261.toInt(),
            0xFF4D9DE0.toInt(), 0xFF7FB069.toInt(), 0xFFE76F9A.toInt(), 0xFFFFD166.toInt()
        )
        const val BG = 0xFF171A2B.toInt()
        const val CARD = 0xFF22263A.toInt()
        const val TEXT = 0xFFF1F2F8.toInt()
        const val DIM = 0xFF9EA3BC.toInt()
        const val GRID = 0xFF33384F.toInt()

        fun color(name: String, fallback: Int): Int {
            val n = name.trim().lowercase()
            if (n.isEmpty()) return fallback
            if (n.startsWith("#")) return try { Color.parseColor(n) } catch (e: Exception) { fallback }
            return when (n) {
                "green", "зелёный", "зеленый", "ok", "good" -> 0xFF5BD18B.toInt()
                "red", "красный", "bad", "error" -> 0xFFFF6B6B.toInt()
                "yellow", "жёлтый", "желтый", "warn", "warning" -> 0xFFFFD166.toInt()
                "orange", "оранжевый" -> 0xFFF4A261.toInt()
                "blue", "синий", "голубой" -> 0xFF4D9DE0.toInt()
                "violet", "purple", "фиолетовый" -> 0xFF7C6CFF.toInt()
                "teal", "cyan", "бирюзовый" -> 0xFF2EC4B6.toInt()
                "pink", "розовый" -> 0xFFE76F9A.toInt()
                "gray", "grey", "серый" -> 0xFF8A8FA8.toInt()
                "white", "белый" -> Color.WHITE
                "black", "чёрный", "черный" -> Color.BLACK
                else -> fallback
            }
        }

        /** 1234567 → «1,2 млн», 1530 → «1 530», 2.5 → «2,5». */
        fun fmt(v: Double): String {
            val a = abs(v)
            return when {
                a >= 1e9 -> "%.1f млрд".format(v / 1e9)
                a >= 1e6 -> "%.1f млн".format(v / 1e6)
                a >= 1000 || v == Math.floor(v) -> String.format(java.util.Locale.ROOT, "%,d", v.toLong()).replace(',', ' ')
                a >= 10 -> "%.1f".format(v)
                else -> "%.2f".format(v).trimEnd('0').trimEnd(',', '.')
            }.replace('.', ',')
        }
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val pad = dp(12f)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = dp(13.5f) * scaleText }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; textSize = dp(11.5f) * scaleText }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = dp(14f) * scaleText; typeface = Typeface.DEFAULT_BOLD }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = dp(21f) * scaleText; typeface = Typeface.DEFAULT_BOLD }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }

    private var layoutWidth = -1
    private var heightPx = 0
    private val ops = ArrayList<(Canvas) -> Unit>()

    private fun sl(s: String, p: TextPaint, w: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, maxLines: Int = 6): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, max(1, w.toInt())).setAlignment(align).setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()

    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float) { c.save(); c.translate(x, y); l.draw(c); c.restore() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        if (w != layoutWidth) build(w)
        setMeasuredDimension(w, heightPx)
    }

    override fun onDraw(canvas: Canvas) {
        if (layoutWidth != width) { build(width); requestLayout() }
        fill.color = BG
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dp(14f), dp(14f), fill)
        ops.forEach { it(canvas) }
    }

    /** Снимок блока для «Сохранить картинкой». */
    fun toBitmap(w: Int): Bitmap {
        build(w)
        val b = Bitmap.createBitmap(w, max(1, heightPx), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        fill.color = BG
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), heightPx.toFloat()), dp(14f), dp(14f), fill)
        ops.forEach { it(c) }
        return b
    }

    // ---------------------------------------------------------------- раскладка

    private fun build(w: Int) {
        layoutWidth = w
        ops.clear()
        var y = pad
        if (visual.title.isNotBlank()) {
            val l = sl(visual.title, bold, w - 2 * pad, maxLines = 2)
            val top = y
            ops += { c -> drawLayout(c, l, pad, top) }
            y += l.height + dp(8f)
        }
        val inner = w - 2 * pad
        y = when (val v = visual) {
            is Visual.Chart -> if (v.kind == "hbar") hbar(v, y, inner) else if (v.kind == "line") line(v, y, inner) else bars(v, y, inner)
            is Visual.Pie -> pie(v, y, inner)
            is Visual.Table -> table(v, y, inner)
            is Visual.Tree -> tree(v, y, inner)
            is Visual.ItemList -> list(v, y, inner)
            is Visual.Stats -> stats(v, y, inner)
            is Visual.Drawing -> drawing(v, y, inner)
        }
        heightPx = (y + pad).toInt()
    }

    /** Красивая верхняя граница шкалы: 87 → 100, 23 → 25, 4300 → 5000. */
    private fun niceMax(v: Double): Double {
        if (v <= 0) return 1.0
        val p = 10.0.pow(kotlin.math.floor(log10(v)))
        val m = v / p
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { m <= it + 1e-9 }
        return step * p
    }

    private fun legend(series: List<Visual.Series>, y0: Float, inner: Float): Float {
        if (series.size < 2 || series.all { it.name.isBlank() }) return y0
        var x = pad
        var y = y0
        series.forEachIndexed { i, s ->
            val wText = small.measureText(s.name) + dp(22f)
            if (x + wText > pad + inner) { x = pad; y += dp(18f) }
            val cx = x; val cy = y
            ops += { c -> fill.color = palette[i % palette.size]; c.drawRoundRect(RectF(cx, cy + dp(3f), cx + dp(10f), cy + dp(13f)), dp(3f), dp(3f), fill); c.drawText(s.name, cx + dp(14f), cy + dp(12f), small) }
            x += wText
        }
        return y + dp(22f)
    }

    private fun bars(v: Visual.Chart, y0: Float, inner: Float): Float {
        var y = legend(v.series, y0, inner)
        val maxV = niceMax(v.series.flatMap { it.values }.maxOrNull() ?: 1.0)
        val chartH = dp(170f)
        val axisW = small.measureText(fmt(maxV)) + dp(6f)
        val left = pad + axisW
        val plotW = inner - axisW
        val top = y
        val n = v.labels.size
        val groupW = plotW / max(1, n)
        val barW = (groupW * 0.72f / v.series.size).coerceAtMost(dp(36f))
        ops += { c ->
            stroke.color = GRID; stroke.strokeWidth = dp(1f)
            for (k in 0..4) {
                val gy = top + chartH - chartH * k / 4f
                c.drawLine(left, gy, pad + inner, gy, stroke)
                c.drawText(fmt(maxV * k / 4), pad, gy + dp(4f), small)
            }
        }
        v.series.forEachIndexed { si, s ->
            s.values.forEachIndexed { i, value ->
                val h = (value / maxV * chartH).toFloat().coerceAtLeast(0f)
                val x = left + groupW * i + (groupW - barW * v.series.size) / 2 + barW * si
                val col = if (v.series.size == 1) palette[i % palette.size] else palette[si % palette.size]
                ops += { c ->
                    fill.color = col
                    c.drawRoundRect(RectF(x + dp(1f), top + chartH - h, x + barW - dp(1f), top + chartH), dp(4f), dp(4f), fill)
                    if (v.series.size == 1 && n <= 12) {
                        val t = fmt(value)
                        val tw = small.measureText(t)
                        c.drawText(t, x + barW / 2 - tw / 2, top + chartH - h - dp(4f), small)
                    }
                }
            }
        }
        y = top + chartH + dp(4f)
        // Подписи снизу: если не помещаются — через одну
        val step = if (n > 0 && v.labels.maxOf { small.measureText(it) } > groupW * 1.8f) ceil(n / 8.0).toInt().coerceAtLeast(1) else 1
        val labelTop = y
        var maxH = 0f
        v.labels.forEachIndexed { i, label ->
            if (i % step != 0) return@forEachIndexed
            val l = sl(label, small, groupW * step - dp(2f), Layout.Alignment.ALIGN_CENTER, 2)
            maxH = max(maxH, l.height.toFloat())
            val x = left + groupW * i - groupW * (step - 1) / 2f
            ops += { c -> drawLayout(c, l, x + dp(1f), labelTop) }
        }
        if (v.unit.isNotBlank()) { val uy = labelTop + maxH + dp(14f); ops += { c -> c.drawText(v.unit, pad, uy, small) }; return uy + dp(4f) }
        return labelTop + maxH
    }

    private fun hbar(v: Visual.Chart, y0: Float, inner: Float): Float {
        var y = legend(v.series, y0, inner)
        val s = v.series.first()
        val maxV = (s.values.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
        val labelW = (inner * 0.36f).coerceAtMost(dp(150f))
        val valueW = small.measureText(fmt(maxV) + " " + v.unit) + dp(8f)
        val barMax = inner - labelW - valueW
        v.labels.forEachIndexed { i, label ->
            val value = s.values.getOrElse(i) { 0.0 }
            val l = sl(label, text, labelW - dp(6f), maxLines = 2)
            val rowH = max(l.height.toFloat(), dp(18f)) + dp(10f)
            val top = y
            val w = (value / maxV * barMax).toFloat()
            val col = palette[i % palette.size]
            ops += { c ->
                drawLayout(c, l, pad, top + (rowH - l.height) / 2 - dp(2f))
                fill.color = CARD
                c.drawRoundRect(RectF(pad + labelW, top + rowH / 2 - dp(8f), pad + labelW + barMax, top + rowH / 2 + dp(8f)), dp(8f), dp(8f), fill)
                fill.color = col
                c.drawRoundRect(RectF(pad + labelW, top + rowH / 2 - dp(8f), pad + labelW + max(w, dp(4f)), top + rowH / 2 + dp(8f)), dp(8f), dp(8f), fill)
                c.drawText(fmt(value) + if (v.unit.isNotBlank()) " " + v.unit else "", pad + labelW + barMax + dp(6f), top + rowH / 2 + dp(4f), small)
            }
            y += rowH
        }
        return y
    }

    private fun line(v: Visual.Chart, y0: Float, inner: Float): Float {
        val y = legend(v.series, y0, inner)
        val all = v.series.flatMap { it.values }
        val maxV = niceMax(all.maxOrNull() ?: 1.0)
        val minV = (all.minOrNull() ?: 0.0).let { if (it >= 0) 0.0 else -niceMax(-it) }
        val chartH = dp(170f)
        val axisW = small.measureText(fmt(maxV)) + dp(6f)
        val left = pad + axisW
        val plotW = inner - axisW
        val top = y
        val n = v.labels.size
        fun px(i: Int) = left + if (n <= 1) plotW / 2 else plotW * i / (n - 1)
        fun py(value: Double) = (top + chartH - (value - minV) / (maxV - minV) * chartH).toFloat()
        ops += { c ->
            stroke.color = GRID; stroke.strokeWidth = dp(1f)
            for (k in 0..4) {
                val value = minV + (maxV - minV) * k / 4
                val gy = py(value)
                c.drawLine(left, gy, pad + inner, gy, stroke)
                c.drawText(fmt(value), pad, gy + dp(4f), small)
            }
            v.series.forEachIndexed { si, s ->
                val col = palette[si % palette.size]
                val path = Path()
                s.values.forEachIndexed { i, value -> if (i == 0) path.moveTo(px(i), py(value)) else path.lineTo(px(i), py(value)) }
                if (v.series.size == 1 && s.values.size > 1) {
                    val area = Path(path).apply { lineTo(px(s.values.size - 1), top + chartH); lineTo(px(0), top + chartH); close() }
                    fill.color = (col and 0x00FFFFFF) or 0x33000000
                    c.drawPath(area, fill)
                }
                stroke.color = col; stroke.strokeWidth = dp(2.5f)
                c.drawPath(path, stroke)
                fill.color = col
                s.values.forEachIndexed { i, value -> c.drawCircle(px(i), py(value), dp(3.5f), fill) }
            }
        }
        val labelTop = top + chartH + dp(16f)
        val step = ceil(n / 7.0).toInt().coerceAtLeast(1)
        v.labels.forEachIndexed { i, label ->
            if (i % step != 0 && i != n - 1) return@forEachIndexed
            val tw = small.measureText(label).coerceAtMost(dp(70f))
            val x = (px(i) - tw / 2).coerceIn(left - dp(4f), pad + inner - tw)
            val t = if (small.measureText(label) > dp(70f)) label.take(9) + "…" else label
            ops += { c -> c.drawText(t, x, labelTop, small) }
        }
        return labelTop + dp(6f)
    }

    private fun pie(v: Visual.Pie, y0: Float, inner: Float): Float {
        val total = v.slices.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val diam = min(inner * 0.46f, dp(170f))
        val legendX = pad + diam + dp(16f)
        val legendW = inner - diam - dp(16f)
        val rowH = dp(22f)
        val top = y0
        val rect = RectF(pad, top, pad + diam, top + diam)
        ops += { c ->
            var start = -90f
            v.slices.forEachIndexed { i, (_, value) ->
                val sweep = (value / total * 360).toFloat()
                fill.color = palette[i % palette.size]
                c.drawArc(rect, start, sweep, true, fill)
                start += sweep
            }
            if (v.donut) {
                fill.color = BG
                c.drawCircle(rect.centerX(), rect.centerY(), diam * 0.31f, fill)
                val t = fmt(total) + if (v.unit.isNotBlank()) " " + v.unit else ""
                val tw = bold.measureText(t)
                c.drawText(t, rect.centerX() - tw / 2, rect.centerY() + dp(5f), bold)
            }
        }
        // Подпись может занять две строки: высота строки легенды по факту, ничего не обрезается
        var ly = top
        v.slices.forEachIndexed { i, (label, value) ->
            val pct = value / total * 100
            val l = sl("$label — ${fmt(value)}${if (v.unit.isNotBlank()) " " + v.unit else ""} (${fmt(Math.round(pct).toDouble())}%)", small, legendW - dp(16f), maxLines = 2)
            val y0 = ly
            ops += { c ->
                fill.color = palette[i % palette.size]
                c.drawRoundRect(RectF(legendX, y0 + dp(4f), legendX + dp(11f), y0 + dp(15f)), dp(3f), dp(3f), fill)
                drawLayout(c, l, legendX + dp(16f), y0 + dp(2f))
            }
            ly += max(rowH, l.height + dp(8f))
        }
        return max(top + diam, ly)
    }

    private fun table(v: Visual.Table, y0: Float, inner: Float): Float {
        val cols = v.columns.size
        val cellPad = dp(6f)
        // Ширина колонок по самому длинному тексту, но не уже 44dp и не шире 45% таблицы
        val natural = (0 until cols).map { ci ->
            val texts = listOf(v.columns[ci]) + v.rows.map { it[ci] }
            (texts.maxOf { text.measureText(it) } + cellPad * 2).coerceIn(dp(44f), inner * 0.45f)
        }
        val sum = natural.sum()
        val widths = natural.map { it * inner / sum }
        var y = y0
        val hasHeader = v.columns.any { it.isNotBlank() }
        fun row(cells: List<String>, header: Boolean, index: Int) {
            val paint = if (header) bold else text
            val layouts = cells.mapIndexed { ci, s -> sl(s, paint, widths[ci] - cellPad * 2, maxLines = 5) }
            val h = (layouts.maxOf { it.height } + cellPad * 2)
            val top = y
            ops += { c ->
                fill.color = if (header) 0xFF3A3170.toInt() else if (index % 2 == 0) CARD else BG
                c.drawRect(pad, top, pad + inner, top + h, fill)
                var x = pad
                layouts.forEachIndexed { ci, l ->
                    // Числа выравниваем по правому краю — их так удобнее сравнивать
                    val numeric = !header && cells[ci].trim().matches(Regex("[-+]?[\\d\\s.,]+%?\\s*\\S{0,4}"))
                    val dx = if (numeric) widths[ci] - cellPad - l.getLineWidth(0) else cellPad
                    drawLayout(c, l, x + dx, top + cellPad)
                    x += widths[ci]
                }
            }
            y += h
        }
        if (hasHeader) row(v.columns, true, -1)
        v.rows.forEachIndexed { i, r -> row(r, false, i) }
        val bottom = y
        ops += { c ->
            stroke.color = GRID; stroke.strokeWidth = dp(1f)
            var x = pad
            for (ci in 0 until cols - 1) { x += widths[ci]; c.drawLine(x, y0, x, bottom, stroke) }
        }
        return y
    }

    private fun tree(v: Visual.Tree, y0: Float, inner: Float): Float {
        var y = y0
        val indent = dp(20f)
        data class Row(val node: Visual.Node, val depth: Int, val top: Float, val mid: Float)
        val rows = ArrayList<Row>()
        val parents = HashMap<Int, Int>() // индекс строки → индекс родителя
        fun walk(n: Visual.Node, depth: Int, parent: Int) {
            val x = pad + indent * depth + dp(14f)
            val label = n.text + if (n.note.isNotBlank()) "  · ${n.note}" else ""
            val p = if (depth == 0) bold else text
            val l = sl(label, p, pad + inner - x - dp(4f), maxLines = 3)
            val h = max(l.height.toFloat(), dp(18f)) + dp(8f)
            val top = y
            val idx = rows.size
            rows += Row(n, depth, top, top + h / 2)
            if (parent >= 0) parents[idx] = parent
            val col = palette[depth % palette.size]
            ops += { c ->
                fill.color = col
                c.drawCircle(x - dp(8f), top + h / 2, dp(if (depth == 0) 5f else 4f), fill)
                drawLayout(c, l, x, top + (h - l.height) / 2)
            }
            y += h
            n.children.forEach { walk(it, depth + 1, idx) }
        }
        v.root.forEach { walk(it, 0, -1) }
        // Ветки: от родителя вниз и вбок к каждому ребёнку
        val snapshot = rows.toList()
        ops.add(0) { c ->
            stroke.strokeWidth = dp(1.5f)
            for ((child, parent) in parents) {
                val pr = snapshot[parent]; val cr = snapshot[child]
                val px = pad + indent * pr.depth + dp(6f)
                val cx = pad + indent * cr.depth + dp(6f)
                stroke.color = (palette[pr.depth % palette.size] and 0x00FFFFFF) or 0x99000000.toInt()
                c.drawLine(px, pr.mid, px, cr.mid, stroke)
                c.drawLine(px, cr.mid, cx - dp(4f), cr.mid, stroke)
            }
        }
        return y
    }

    private fun list(v: Visual.ItemList, y0: Float, inner: Float): Float {
        var y = y0
        val timeW = if (v.kind == "timeline") (v.items.maxOf { small.measureText(it.note) } + dp(14f)).coerceIn(dp(40f), inner * 0.3f) else 0f
        v.items.forEachIndexed { i, item ->
            val col = color(item.color, palette[i % palette.size])
            val markW = dp(30f)
            val x = pad + timeW + markW
            val textW = inner - timeW - markW
            val main = sl(item.text, if (v.kind == "check" && item.done == true) small else text, textW, maxLines = 4)
            val note = if (item.note.isNotBlank() && v.kind != "timeline") sl(item.note, small, textW, maxLines = 3) else null
            val barH = if (v.kind == "progress") dp(12f) else 0f
            val h = main.height + (note?.height?.plus(dp(2f)) ?: 0f) + barH + dp(12f)
            val top = y
            val isLast = i == v.items.lastIndex
            ops += { c ->
                val cy = top + dp(6f) + main.getLineBottom(0) / 2f
                val cx = pad + timeW + dp(11f)
                when (v.kind) {
                    "steps" -> {
                        fill.color = col; c.drawCircle(cx, cy, dp(10f), fill)
                        val t = "${i + 1}"; val tw = small.measureText(t)
                        small.color = Color.WHITE; c.drawText(t, cx - tw / 2, cy + dp(4f), small); small.color = DIM
                        if (!isLast) { stroke.color = GRID; stroke.strokeWidth = dp(2f); c.drawLine(cx, cy + dp(11f), cx, top + h + dp(2f), stroke) }
                    }
                    "check" -> {
                        val done = item.done == true
                        if (done) { fill.color = 0xFF5BD18B.toInt(); c.drawCircle(cx, cy, dp(9f), fill); stroke.color = Color.WHITE; stroke.strokeWidth = dp(2f)
                            c.drawLine(cx - dp(4f), cy, cx - dp(1f), cy + dp(3.5f), stroke); c.drawLine(cx - dp(1f), cy + dp(3.5f), cx + dp(4.5f), cy - dp(3.5f), stroke) }
                        else { stroke.color = if (item.note.contains("сейчас") || item.color == "yellow") 0xFFFFD166.toInt() else DIM; stroke.strokeWidth = dp(2f); c.drawCircle(cx, cy, dp(8f), stroke) }
                    }
                    "timeline" -> {
                        c.drawText(item.note, pad, cy + dp(4f), small)
                        fill.color = col; c.drawCircle(cx, cy, dp(6f), fill)
                        if (!isLast) { stroke.color = GRID; stroke.strokeWidth = dp(2f); c.drawLine(cx, cy + dp(7f), cx, top + h + dp(2f), stroke) }
                    }
                    else -> {
                        if (item.icon.isNotBlank()) { val tw = text.measureText(item.icon); c.drawText(item.icon, cx - tw / 2, cy + dp(5f), text) }
                        else { fill.color = col; c.drawCircle(cx, cy, dp(5f), fill) }
                    }
                }
                drawLayout(c, main, x, top + dp(6f))
                var ny = top + dp(6f) + main.height
                note?.let { drawLayout(c, it, x, ny + dp(2f)); ny += it.height + dp(2f) }
                if (v.kind == "progress") {
                    val p = ((item.value ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
                    fill.color = CARD; c.drawRoundRect(RectF(x, ny + dp(3f), x + textW, ny + dp(11f)), dp(4f), dp(4f), fill)
                    fill.color = col; c.drawRoundRect(RectF(x, ny + dp(3f), x + max(dp(6f), textW * p), ny + dp(11f)), dp(4f), dp(4f), fill)
                }
            }
            y += h
        }
        return y
    }

    private fun stats(v: Visual.Stats, y0: Float, inner: Float): Float {
        val colsN = if (inner > dp(520f)) 3 else 2
        val gap = dp(8f)
        val tileW = (inner - gap * (colsN - 1)) / colsN
        var y = y0
        v.items.chunked(colsN).forEach { rowItems ->
            val layouts = rowItems.map { s ->
                Triple(sl(s.value, big, tileW - dp(20f), maxLines = 1), sl(s.label, small, tileW - dp(20f), maxLines = 2),
                    s.note.takeIf { it.isNotBlank() }?.let { sl(it, small, tileW - dp(20f), maxLines = 2) })
            }
            val h = layouts.maxOf { (a, b, c) -> a.height + b.height + (c?.height ?: 0) } + dp(22f) + if (rowItems.any { it.percent != null }) dp(10f) else 0f
            val top = y
            rowItems.forEachIndexed { i, s ->
                val x = pad + (tileW + gap) * i
                val (valueL, labelL, noteL) = layouts[i]
                val col = color(s.color, palette[(v.items.indexOf(s)) % palette.size])
                ops += { c ->
                    fill.color = CARD
                    c.drawRoundRect(RectF(x, top, x + tileW, top + h), dp(12f), dp(12f), fill)
                    fill.color = col
                    c.drawRoundRect(RectF(x, top + dp(10f), x + dp(4f), top + h - dp(10f)), dp(2f), dp(2f), fill)
                    var ty = top + dp(8f)
                    drawLayout(c, labelL, x + dp(12f), ty); ty += labelL.height + dp(2f)
                    big.color = col; drawLayout(c, valueL, x + dp(12f), ty); big.color = TEXT; ty += valueL.height
                    noteL?.let { drawLayout(c, it, x + dp(12f), ty); ty += it.height }
                    s.percent?.let { p ->
                        val bw = tileW - dp(24f)
                        fill.color = BG; c.drawRoundRect(RectF(x + dp(12f), top + h - dp(14f), x + dp(12f) + bw, top + h - dp(8f)), dp(3f), dp(3f), fill)
                        fill.color = col; c.drawRoundRect(RectF(x + dp(12f), top + h - dp(14f), x + dp(12f) + bw * (p / 100).toFloat().coerceIn(0.02f, 1f), top + h - dp(8f)), dp(3f), dp(3f), fill)
                    }
                }
            }
            y += h + gap
        }
        return y - gap
    }

    private fun drawing(v: Visual.Drawing, y0: Float, inner: Float): Float {
        val k = (inner / v.w).toFloat()
        val h = (v.h * k).toFloat()
        val top = y0
        ops += { c ->
            c.save()
            c.translate(pad, top)
            c.clipRect(0f, 0f, inner, h)
            fill.color = color(v.background, CARD)
            c.drawRoundRect(RectF(0f, 0f, inner, h), dp(8f), dp(8f), fill)
            c.scale(k, k)
            v.shapes.forEachIndexed { i, s ->
                val f = if (s.fill.isNotBlank()) color(s.fill, palette[i % palette.size]) else null
                val st = color(s.stroke, if (f == null) palette[i % palette.size] else Color.TRANSPARENT)
                stroke.color = st; stroke.strokeWidth = (s.width.toFloat()).coerceIn(0.5f, 20f)
                val n = s.n.map { it.toFloat() }
                when (s.t) {
                    "rect", "box" -> { val r = RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]); f?.let { fill.color = it; c.drawRoundRect(r, 4f, 4f, fill) }; if (st != Color.TRANSPARENT) c.drawRoundRect(r, 4f, 4f, stroke) }
                    "ellipse", "oval" -> { val r = RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]); f?.let { fill.color = it; c.drawOval(r, fill) }; if (st != Color.TRANSPARENT) c.drawOval(r, stroke) }
                    "circle" -> { f?.let { fill.color = it; c.drawCircle(n[0], n[1], n[2], fill) }; if (st != Color.TRANSPARENT) c.drawCircle(n[0], n[1], n[2], stroke) }
                    "line" -> c.drawLine(n[0], n[1], n[2], n[3], stroke)
                    "arrow" -> {
                        if (stroke.color == Color.TRANSPARENT) stroke.color = TEXT
                        c.drawLine(n[0], n[1], n[2], n[3], stroke)
                        val a = atan2((n[3] - n[1]).toDouble(), (n[2] - n[0]).toDouble())
                        val len = 10f + stroke.strokeWidth * 2
                        val p = Path().apply {
                            moveTo(n[2], n[3])
                            lineTo((n[2] - len * cos(a - 0.45)).toFloat(), (n[3] - len * sin(a - 0.45)).toFloat())
                            lineTo((n[2] - len * cos(a + 0.45)).toFloat(), (n[3] - len * sin(a + 0.45)).toFloat()); close()
                        }
                        fill.color = stroke.color; c.drawPath(p, fill)
                    }
                    "text", "label" -> {
                        val p = TextPaint(text).apply { textSize = s.size.toFloat().coerceIn(6f, 80f); color = color(s.fill.ifBlank { s.stroke }, TEXT) }
                        c.drawText(s.text, n[0], n[1], p)
                    }
                    "poly", "polygon", "path" -> if (n.size >= 4) {
                        val p = Path().apply { moveTo(n[0], n[1]); var j = 2; while (j + 1 < n.size) { lineTo(n[j], n[j + 1]); j += 2 }; if (s.t != "path") close() }
                        f?.let { fill.color = it; c.drawPath(p, fill) }; if (st != Color.TRANSPARENT) c.drawPath(p, stroke)
                    }
                }
            }
            c.restore()
        }
        return top + h
    }
}
