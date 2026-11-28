package com.example.assistant

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Рисует [Visual.Drawing] — от простой картинки фигурами до доски и чертежа.
 *
 * Стили: «plain» — цветные фигуры на карточке; «chalk» — мелом по школьной доске: белые чуть неровные линии
 * в два прохода (как мел), пастельные цветные мелки, рукописный шрифт, лёгкие разводы на доске;
 * «blueprint» — технический чертёж: синий лист с сеткой, рамка, ровные тонкие и основные линии, моноширинный шрифт.
 *
 * Кроме простых фигур умеет то, что нужно для объяснений: размерные линии (dim), дуги (arc), отметки углов (angle,
 * в том числе прямого), точки с подписями, сетку, оси координат с делениями (axes), графики функций по формуле (plot),
 * штриховку сечений (hatch), плавные кривые (curve), пунктир, точки и штрихпунктир осевых линий.
 *
 * Пошаговость: upTo — последний видимый шаг, progress (0…1) — насколько дорисован этот шаг: его фигуры появляются
 * по очереди, линии «прорисовываются» на глазах. Холст должен быть уже масштабирован в единицы рисунка.
 */
object BoardPainter {

    private class Theme(val bg: Int, val ink: Int, val dim: Int, val grid: Int, val font: Typeface, val chalk: Boolean, val blueprint: Boolean)

    private val chalkColors = mapOf(
        "white" to 0xFFF4F1E8.toInt(), "белый" to 0xFFF4F1E8.toInt(),
        "yellow" to 0xFFF6E27F.toInt(), "жёлтый" to 0xFFF6E27F.toInt(), "желтый" to 0xFFF6E27F.toInt(),
        "pink" to 0xFFF5A3B5.toInt(), "розовый" to 0xFFF5A3B5.toInt(),
        "blue" to 0xFF9FD0F5.toInt(), "синий" to 0xFF9FD0F5.toInt(), "голубой" to 0xFF9FD0F5.toInt(),
        "green" to 0xFFA9E3A1.toInt(), "зелёный" to 0xFFA9E3A1.toInt(), "зеленый" to 0xFFA9E3A1.toInt(),
        "orange" to 0xFFF7BF86.toInt(), "оранжевый" to 0xFFF7BF86.toInt(),
        "red" to 0xFFF28B82.toInt(), "красный" to 0xFFF28B82.toInt(),
        "violet" to 0xFFC9B6F5.toInt(), "purple" to 0xFFC9B6F5.toInt(), "фиолетовый" to 0xFFC9B6F5.toInt(),
        "teal" to 0xFF8EE3D6.toInt(), "cyan" to 0xFF8EE3D6.toInt(), "бирюзовый" to 0xFF8EE3D6.toInt(),
        "gray" to 0xFFB8BDB5.toInt(), "grey" to 0xFFB8BDB5.toInt(), "серый" to 0xFFB8BDB5.toInt()
    )

    private fun theme(v: Visual.Drawing): Theme = when (v.style) {
        "chalk" -> Theme(VisualView.color(v.background, 0xFF1E2421.toInt()), 0xFFF4F1E8.toInt(), 0xFFB9BDB4.toInt(), 0x22FFFFFF,
            Typeface.create("casual", Typeface.NORMAL), chalk = true, blueprint = false)
        "blueprint" -> Theme(VisualView.color(v.background, 0xFF0F3460.toInt()), 0xFFEFF5FF.toInt(), 0xFFB5C8E8.toInt(), 0x30FFFFFF,
            Typeface.MONOSPACE, chalk = false, blueprint = true)
        else -> Theme(VisualView.color(v.background, VisualView.CARD), VisualView.TEXT, VisualView.DIM, VisualView.GRID,
            Typeface.DEFAULT, chalk = false, blueprint = false)
    }

    private fun named(th: Theme, name: String, fallback: Int): Int {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return fallback
        if (!th.chalk && !th.blueprint) return VisualView.color(n, fallback)
        if (n.startsWith("#")) return VisualView.color(n, fallback)
        return chalkColors[n] ?: fallback
    }

    private fun alpha(color: Int, a: Float): Int = Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Система координат осей: перевод «математических» x, y в точки рисунка. */
    private class Axes(val x: Float, val y: Float, val w: Float, val h: Float, val xmin: Double, val xmax: Double, val ymin: Double, val ymax: Double) {
        fun px(v: Double) = (x + (v - xmin) / (xmax - xmin) * w).toFloat()
        fun py(v: Double) = (y + h - (v - ymin) / (ymax - ymin) * h).toFloat()
        val kx get() = w / (xmax - xmin).toFloat()
    }

    private fun axesOf(s: Visual.Shape): Axes {
        val o = s.opt
        val xmin = o["xmin"] ?: -5.0; var xmax = o["xmax"] ?: 5.0
        val ymin = o["ymin"] ?: -5.0; var ymax = o["ymax"] ?: 5.0
        if (xmax <= xmin) xmax = xmin + 1; if (ymax <= ymin) ymax = ymin + 1
        return Axes(s.n[0].toFloat(), s.n[1].toFloat(), max(10.0, s.n[2]).toFloat(), max(10.0, s.n[3]).toFloat(), xmin, xmax, ymin, ymax)
    }

    /** Шаг делений: 1, 2, 5, 10… так, чтобы делений было около восьми. */
    internal fun niceStep(range: Double, target: Int = 8): Double {
        if (range <= 0 || range.isNaN()) return 1.0
        val raw = range / target
        val p = 10.0.pow(floor(log10(raw)))
        val m = raw / p
        return p * when { m < 1.5 -> 1.0; m < 3.5 -> 2.0; m < 7.5 -> 5.0; else -> 10.0 }
    }

    // Кисти создаются при первом рисовании (в юнит-тестах Android-классов нет)
    private val fill by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val line by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }
    private val text by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }

    @Synchronized
    fun paint(c: Canvas, v: Visual.Drawing, upTo: Int = Int.MAX_VALUE, progress: Float = 1f) {
        val th = theme(v)
        val w = v.w.toFloat(); val h = v.h.toFloat()
        background(c, th, w, h)

        // Оси действуют на фигуры после них, даже если сами ещё не показаны
        var current: Axes? = null
        val axesFor = v.shapes.map { s -> if (s.t == "axes" && s.n.size >= 4) current = axesOf(s); current }

        // Номер фигуры внутри текущего шага: фигуры шага появляются по очереди
        val order = IntArray(v.shapes.size)
        var inStep = 0
        v.shapes.forEachIndexed { i, s -> if (s.step == upTo) order[i] = inStep++ }
        v.shapes.forEachIndexed { i, s ->
            if (s.step > upTo) return@forEachIndexed
            val local = if (s.step < upTo) 1f else (progress * inStep - order[i]).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            try { shape(c, th, s, i, local, axesFor[i]) } catch (_: Exception) {}
        }
    }

    private fun background(c: Canvas, th: Theme, w: Float, h: Float) {
        fill.style = Paint.Style.FILL
        fill.color = th.bg
        c.drawRect(0f, 0f, w, h, fill)
        if (th.chalk) {
            // разводы от тряпки: несколько больших едва заметных пятен
            val r = java.util.Random(7)
            repeat(7) {
                fill.color = Color.argb(6 + r.nextInt(6), 255, 255, 255)
                val cx = r.nextFloat() * w; val cy = r.nextFloat() * h; val rr = (0.15f + r.nextFloat() * 0.3f) * max(w, h)
                c.drawOval(RectF(cx - rr, cy - rr * 0.45f, cx + rr, cy + rr * 0.45f), fill)
            }
            line.pathEffect = null; line.strokeWidth = 3f; line.color = 0x33000000
            c.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, line)
        } else if (th.blueprint) {
            line.pathEffect = null
            var x = 0f
            while (x <= w) { line.strokeWidth = if ((x / 10f).toInt() % 5 == 0) 0.6f else 0.3f; line.color = if ((x / 10f).toInt() % 5 == 0) 0x40FFFFFF else 0x1CFFFFFF; c.drawLine(x, 0f, x, h, line); x += 10f }
            var y = 0f
            while (y <= h) { line.strokeWidth = if ((y / 10f).toInt() % 5 == 0) 0.6f else 0.3f; line.color = if ((y / 10f).toInt() % 5 == 0) 0x40FFFFFF else 0x1CFFFFFF; c.drawLine(0f, y, w, y, line); y += 10f }
            // рамка листа
            line.strokeWidth = 1.6f; line.color = alpha(th.ink, 0.8f)
            c.drawRect(4f, 4f, w - 4f, h - 4f, line)
        }
    }

    // ---------------------------------------------------------------- линии

    private fun dashEffect(dash: String, width: Float): DashPathEffect? = when (dash) {
        "dash", "dashed", "пунктир", "true" -> DashPathEffect(floatArrayOf(7f + width * 2, 4f + width), 0f)
        "dot", "dotted", "точки" -> DashPathEffect(floatArrayOf(0.5f, 3.5f + width), 0f)
        "center", "axis", "осевая", "dashdot" -> DashPathEffect(floatArrayOf(14f + width * 2, 3f, 2f, 3f), 0f)
        else -> null
    }

    /** Обводка контура: в стиле мела — неровно, в два прохода; local < 1 — только начало контура. */
    private fun stroke(c: Canvas, th: Theme, path: Path, color: Int, width: Float, dash: String, local: Float, seed: Int) {
        if (Color.alpha(color) == 0) return
        line.pathEffect = dashEffect(dash, width)
        line.strokeWidth = width
        if (!th.chalk) {
            line.color = color
            if (local >= 1f) c.drawPath(path, line)
            else {
                val pm = PathMeasure(path, false); val part = Path()
                var total = 0f
                do { total += pm.length } while (pm.nextContour())
                var left = total * local
                val pm2 = PathMeasure(path, false)
                do {
                    val l = pm2.length
                    if (left <= 0f) break
                    pm2.getSegment(0f, min(l, left), part, true)
                    left -= l
                } while (pm2.nextContour())
                c.drawPath(part, line)
            }
            line.pathEffect = null
            return
        }
        // мел: точки вдоль контура с плавным дрожанием поперёк линии
        val pm = PathMeasure(path, false)
        var total = 0f
        do { total += pm.length } while (pm.nextContour())
        var budget = total * local
        val main = Path(); val dust = Path()
        val pos = FloatArray(2); val tan = FloatArray(2)
        val pm2 = PathMeasure(path, false)
        var dist = 0f
        do {
            val l = pm2.length
            if (budget <= 0f || l <= 0f) { budget -= l; continue }
            val end = min(l, budget)
            var d = 0f
            var first = true
            while (true) {
                pm2.getPosTan(d, pos, tan)
                val t = dist + d
                val amp = 0.35f + width * 0.12f
                val o1 = amp * (sin(t * 0.21f + seed) + 0.5f * sin(t * 0.93f + seed * 1.7f)).toFloat()
                val o2 = amp * 1.3f * sin(t * 0.47f + seed * 2.3f + 1f).toFloat()
                val nx = -tan[1]; val ny = tan[0]
                if (first) { main.moveTo(pos[0] + nx * o1, pos[1] + ny * o1); dust.moveTo(pos[0] + nx * o2, pos[1] + ny * o2); first = false }
                else { main.lineTo(pos[0] + nx * o1, pos[1] + ny * o1); dust.lineTo(pos[0] + nx * o2, pos[1] + ny * o2) }
                if (d >= end) break
                d = min(end, d + 2.5f)
            }
            dist += l
            budget -= l
        } while (pm2.nextContour())
        line.color = alpha(color, 0.9f); line.strokeWidth = width
        c.drawPath(main, line)
        line.color = alpha(color, 0.35f); line.strokeWidth = max(0.6f, width * 0.5f)
        c.drawPath(dust, line)
        line.pathEffect = null
    }

    private fun fillPath(c: Canvas, th: Theme, path: Path, color: Int, local: Float) {
        fill.style = Paint.Style.FILL
        fill.color = alpha(color, (if (th.chalk) 0.33f else if (th.blueprint) 0.3f else 1f) * local)
        c.drawPath(path, fill)
    }

    private fun arrowHead(c: Canvas, color: Int, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, thin: Boolean = false) {
        val a = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = if (thin) 6f + width * 1.5f else 8f + width * 2f
        val spread = if (thin) 0.28 else 0.42
        val p = Path().apply {
            moveTo(x2, y2)
            lineTo((x2 - len * cos(a - spread)).toFloat(), (y2 - len * sin(a - spread)).toFloat())
            lineTo((x2 - len * cos(a + spread)).toFloat(), (y2 - len * sin(a + spread)).toFloat()); close()
        }
        fill.style = Paint.Style.FILL; fill.color = color
        c.drawPath(p, fill)
    }

    private fun label(c: Canvas, th: Theme, s: String, x: Float, y: Float, size: Float, color: Int, local: Float,
                      align: Paint.Align = Paint.Align.LEFT, rotate: Float = 0f) {
        if (s.isBlank()) return
        text.typeface = th.font
        text.textSize = (if (th.chalk) size * 1.08f else size).coerceIn(5f, 90f)
        text.color = alpha(color, local)
        text.textAlign = align
        c.save()
        if (rotate != 0f) c.rotate(rotate, x, y)
        s.split('\n').forEachIndexed { i, ln -> c.drawText(ln, x, y + i * text.textSize * 1.2f, text) }
        c.restore()
    }

    // ---------------------------------------------------------------- фигуры

    private fun shape(c: Canvas, th: Theme, s: Visual.Shape, i: Int, local: Float, axes: Axes?) {
        val plainColor = VisualView.palette[i % VisualView.palette.size]
        val fillColor = if (s.fill.isNotBlank()) named(th, s.fill, plainColor) else null
        val ink = if (th.chalk || th.blueprint) th.ink else plainColor
        val strokeColor = when {
            s.stroke.isNotBlank() -> named(th, s.stroke, ink)
            fillColor != null && !th.chalk && !th.blueprint -> Color.TRANSPARENT
            else -> ink
        }
        val width = (if (th.chalk) max(s.width.toFloat(), 1.4f) * 1.15f else s.width.toFloat()).coerceIn(0.3f, 20f)
        val textColor = named(th, s.fill.ifBlank { s.stroke }, if (th.chalk || th.blueprint) th.ink else VisualView.TEXT)
        val ax = if (s.ax) axes else null
        fun X(v: Double) = ax?.px(v) ?: v.toFloat()
        fun Y(v: Double) = ax?.py(v) ?: v.toFloat()
        val n = s.n
        when (s.t) {
            "rect", "ellipse" -> {
                val x1 = X(n[0]); val x2 = X(n[0] + n[2])
                val y1 = Y(n[1]); val y2 = if (ax != null) Y(n[1] - n[3]) else Y(n[1] + n[3])
                val r = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
                val p = Path().apply { if (s.t == "rect") { if (th.chalk || th.blueprint) addRect(r, Path.Direction.CW) else addRoundRect(r, 4f, 4f, Path.Direction.CW) } else addOval(r, Path.Direction.CW) }
                fillColor?.let { fillPath(c, th, p, it, local) }
                stroke(c, th, p, strokeColor, width, s.dash, local, i)
                if (s.text.isNotBlank()) label(c, th, s.text, r.centerX(), r.centerY() + s.size.toFloat() * 0.35f, s.size.toFloat(), textColor, local, Paint.Align.CENTER)
            }
            "circle" -> {
                val cx = X(n[0]); val cy = Y(n[1]); val r = if (ax != null) (n[2] * ax.kx).toFloat() else n[2].toFloat()
                val p = Path().apply { addCircle(cx, cy, max(0.5f, r), Path.Direction.CW) }
                fillColor?.let { fillPath(c, th, p, it, local) }
                stroke(c, th, p, strokeColor, width, s.dash, local, i)
                if (s.text.isNotBlank()) label(c, th, s.text, cx, cy + s.size.toFloat() * 0.35f, s.size.toFloat(), textColor, local, Paint.Align.CENTER)
            }
            "arc" -> {
                val cx = X(n[0]); val cy = Y(n[1]); val r = if (ax != null) (n[2] * ax.kx).toFloat() else n[2].toFloat()
                val a1 = n[3].toFloat(); val a2 = n[4].toFloat()
                val p = Path().apply { addArc(RectF(cx - r, cy - r, cx + r, cy + r), -a1, -(a2 - a1)) }
                stroke(c, th, p, strokeColor, width, s.dash, local, i)
                if (s.text.isNotBlank()) {
                    val mid = Math.toRadians(((a1 + a2) / 2).toDouble())
                    label(c, th, s.text, cx + ((r + 8) * cos(mid)).toFloat(), cy - ((r + 8) * sin(mid)).toFloat() + 4, s.size.toFloat(), textColor, local, Paint.Align.CENTER)
                }
            }
            "line", "arrow", "darrow" -> {
                val x1 = X(n[0]); val y1 = Y(n[1]); val x2 = X(n[2]); val y2 = Y(n[3])
                val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                stroke(c, th, p, strokeColor, width, s.dash, local, i)
                if (s.t != "line" && local > 0.9f) {
                    arrowHead(c, alpha(strokeColor, 0.95f), x1, y1, x2, y2, width)
                    if (s.t == "darrow") arrowHead(c, alpha(strokeColor, 0.95f), x2, y2, x1, y1, width)
                }
                if (s.text.isNotBlank()) {
                    val ang = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat().let { if (it > 90) it - 180 else if (it < -90) it + 180 else it }
                    label(c, th, s.text, (x1 + x2) / 2, (y1 + y2) / 2 - 5f, s.size.toFloat(), textColor, local, Paint.Align.CENTER, ang)
                }
            }
            "dim" -> dimension(c, th, s, X(n[0]), Y(n[1]), X(n[2]), Y(n[3]), hypot(n[2] - n[0], n[3] - n[1]), strokeColor, textColor, local, i)
            "point" -> {
                val x = X(n[0]); val y = Y(n[1])
                fill.style = Paint.Style.FILL; fill.color = alpha(if (s.stroke.isNotBlank() || s.fill.isNotBlank()) named(th, s.stroke.ifBlank { s.fill }, ink) else ink, local)
                c.drawCircle(x, y, (2.2f + width * 0.6f), fill)
                label(c, th, s.text, x + 5f, y - 5f, s.size.toFloat(), textColor, local)
            }
            "text" -> label(c, th, s.text, X(n[0]), Y(n[1]), s.size.toFloat(), textColor, local,
                when (s.opt["o"]?.toInt()) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }, (s.opt["angle"] ?: 0.0).toFloat())
            "angle" -> angleMark(c, th, s, X(n[0]), Y(n[1]), X(n[2]), Y(n[3]), X(n[4]), Y(n[5]), strokeColor, textColor, width, local, i)
            "grid" -> {
                val step = (s.opt["step"] ?: 10.0).toFloat().coerceAtLeast(2f)
                val gc = if (s.stroke.isNotBlank()) alpha(strokeColor, 0.35f) else th.grid
                val p = Path()
                var x = n[0].toFloat(); while (x <= (n[0] + n[2]).toFloat() + 0.01f) { p.moveTo(x, n[1].toFloat()); p.lineTo(x, (n[1] + n[3]).toFloat()); x += step }
                var y = n[1].toFloat(); while (y <= (n[1] + n[3]).toFloat() + 0.01f) { p.moveTo(n[0].toFloat(), y); p.lineTo((n[0] + n[2]).toFloat(), y); y += step }
                line.pathEffect = null; line.strokeWidth = 0.5f; line.color = alpha(gc or 0xFF000000.toInt(), Color.alpha(gc) / 255f * local)
                c.drawPath(p, line)
            }
            "axes" -> axes?.let { axesShape(c, th, s, it, strokeColor, textColor, width, local, i) }
            "plot" -> axes?.let { plot(c, th, s, it, if (s.stroke.isNotBlank()) strokeColor else if (th.chalk || th.blueprint) named(th, "yellow", ink) else plainColor, textColor, width, local, i) }
            "poly", "path", "curve", "hatch" -> {
                val pts = (0 until n.size / 2).map { X(n[it * 2]) to Y(n[it * 2 + 1]) }
                val p = Path()
                p.moveTo(pts[0].first, pts[0].second)
                if (s.t == "curve" && pts.size > 2) {
                    for (k in 1 until pts.size - 1) {
                        val mx = (pts[k].first + pts[k + 1].first) / 2; val my = (pts[k].second + pts[k + 1].second) / 2
                        p.quadTo(pts[k].first, pts[k].second, mx, my)
                    }
                    p.lineTo(pts.last().first, pts.last().second)
                } else pts.drop(1).forEach { p.lineTo(it.first, it.second) }
                val closed = s.t == "poly" || s.t == "hatch"
                if (closed) p.close()
                if (closed) fillColor?.let { fillPath(c, th, p, it, local) }
                if (s.t == "hatch") hatch(c, th, p, if (s.fill.isNotBlank()) fillColor!! else strokeColor, (s.opt["gap"] ?: 6.0).toFloat(), local, i)
                stroke(c, th, p, strokeColor, width, s.dash, local, i)
                if (s.text.isNotBlank()) {
                    val cx = pts.map { it.first }.average().toFloat(); val cy = pts.map { it.second }.average().toFloat()
                    label(c, th, s.text, cx, cy + 4f, s.size.toFloat(), textColor, local, Paint.Align.CENTER)
                }
            }
        }
    }

    /** Размерная линия по ГОСТ: выносные линии, линия со стрелками и число над ней. */
    private fun dimension(c: Canvas, th: Theme, s: Visual.Shape, x1: Float, y1: Float, x2: Float, y2: Float, length: Double,
                          color: Int, textColor: Int, local: Float, seed: Int) {
        val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat().coerceAtLeast(0.01f)
        val ux = (x2 - x1) / len; val uy = (y2 - y1) / len
        val nx = uy; val ny = -ux // слева по ходу: для горизонтали — вверх
        val off = (s.opt["o"] ?: 14.0).toFloat()
        val q1x = x1 + nx * off; val q1y = y1 + ny * off; val q2x = x2 + nx * off; val q2y = y2 + ny * off
        val sign = if (off >= 0) 1f else -1f
        val thin = (if (th.chalk) 1.2f else 0.8f)
        val ext = Path().apply {
            moveTo(x1 + nx * 2f * sign, y1 + ny * 2f * sign); lineTo(q1x + nx * 4f * sign, q1y + ny * 4f * sign)
            moveTo(x2 + nx * 2f * sign, y2 + ny * 2f * sign); lineTo(q2x + nx * 4f * sign, q2y + ny * 4f * sign)
        }
        stroke(c, th, ext, alpha(color, 0.8f), thin, "", local, seed)
        stroke(c, th, Path().apply { moveTo(q1x, q1y); lineTo(q2x, q2y) }, color, thin, "", local, seed + 1)
        if (local > 0.8f) {
            arrowHead(c, color, q2x, q2y, q1x, q1y, thin, thin = true)
            arrowHead(c, color, q1x, q1y, q2x, q2y, thin, thin = true)
        }
        val t = s.text.ifBlank { VisualView.fmt(length) }
        var ang = Math.toDegrees(atan2(uy.toDouble(), ux.toDouble())).toFloat()
        if (ang > 90) ang -= 180; if (ang < -90) ang += 180
        val mx = (q1x + q2x) / 2 + nx * 3f * sign; val my = (q1y + q2y) / 2 + ny * 3f * sign
        label(c, th, t, mx, my + if (sign < 0) s.size.toFloat() * 0.8f else 0f, (s.size * 0.85).toFloat(), textColor, local, Paint.Align.CENTER, ang)
    }

    /** Отметка угла между лучами V→A и V→B дугой (или квадратиком, если угол прямой) с подписью. */
    private fun angleMark(c: Canvas, th: Theme, s: Visual.Shape, vx: Float, vy: Float, ax: Float, ay: Float, bx: Float, by: Float,
                          color: Int, textColor: Int, width: Float, local: Float, seed: Int) {
        val r = (s.opt["r"] ?: 18.0).toFloat()
        val a1 = Math.toDegrees(atan2((ay - vy).toDouble(), (ax - vx).toDouble())).toFloat()
        val a2 = Math.toDegrees(atan2((by - vy).toDouble(), (bx - vx).toDouble())).toFloat()
        var sweep = a2 - a1
        while (sweep > 180) sweep -= 360
        while (sweep < -180) sweep += 360
        val thin = max(1f, width * 0.7f)
        if (s.flag || (abs(abs(sweep) - 90f) < 0.5f && s.text.isBlank())) {
            val k = r * 0.6f
            val u1 = Math.toRadians(a1.toDouble()); val u2 = Math.toRadians(a2.toDouble())
            val p1x = vx + k * cos(u1).toFloat(); val p1y = vy + k * sin(u1).toFloat()
            val p2x = vx + k * cos(u2).toFloat(); val p2y = vy + k * sin(u2).toFloat()
            stroke(c, th, Path().apply { moveTo(p1x, p1y); lineTo(p1x + p2x - vx, p1y + p2y - vy); lineTo(p2x, p2y) }, color, thin, "", local, seed)
        } else {
            stroke(c, th, Path().apply { addArc(RectF(vx - r, vy - r, vx + r, vy + r), a1, sweep) }, color, thin, "", local, seed)
        }
        if (s.text.isNotBlank()) {
            val mid = Math.toRadians((a1 + sweep / 2).toDouble())
            val d = r + 9f
            label(c, th, s.text, vx + d * cos(mid).toFloat(), vy + d * sin(mid).toFloat() + s.size.toFloat() * 0.35f, s.size.toFloat(), textColor, local, Paint.Align.CENTER)
        }
    }

    /** Оси координат со стрелками, делениями и числами; при flag — клетчатая сетка. */
    private fun axesShape(c: Canvas, th: Theme, s: Visual.Shape, a: Axes, color: Int, textColor: Int, width: Float, local: Float, seed: Int) {
        val sx = niceStep(a.xmax - a.xmin); val sy = niceStep(a.ymax - a.ymin)
        if (s.flag) {
            val g = Path()
            var v = Math.ceil(a.xmin / sx) * sx
            while (v <= a.xmax + 1e-9) { g.moveTo(a.px(v), a.y); g.lineTo(a.px(v), a.y + a.h); v += sx }
            v = Math.ceil(a.ymin / sy) * sy
            while (v <= a.ymax + 1e-9) { g.moveTo(a.x, a.py(v)); g.lineTo(a.x + a.w, a.py(v)); v += sy }
            line.pathEffect = null; line.strokeWidth = 0.5f
            line.color = alpha(if (th.chalk || th.blueprint) th.ink else VisualView.GRID, (if (th.chalk || th.blueprint) 0.18f else 1f) * local)
            c.drawPath(g, line)
        }
        val ox = if (a.xmin <= 0 && a.xmax >= 0) a.px(0.0) else a.x
        val oy = if (a.ymin <= 0 && a.ymax >= 0) a.py(0.0) else a.y + a.h
        val aw = max(1.2f, width * 0.75f)
        stroke(c, th, Path().apply { moveTo(a.x, oy); lineTo(a.x + a.w, oy) }, color, aw, "", local, seed)
        stroke(c, th, Path().apply { moveTo(ox, a.y + a.h); lineTo(ox, a.y) }, color, aw, "", local, seed + 3)
        if (local < 0.7f) return
        val tl = local
        arrowHead(c, color, a.x, oy, a.x + a.w + 2f, oy, aw, thin = true)
        arrowHead(c, color, ox, a.y + a.h, ox, a.y - 2f, aw, thin = true)
        val fs = (s.size * 0.72).toFloat().coerceIn(6f, 20f)
        val dim = if (th.chalk || th.blueprint) th.dim else VisualView.DIM
        line.strokeWidth = aw * 0.8f; line.color = alpha(color, tl)
        var v = Math.ceil(a.xmin / sx) * sx
        var count = 0
        while (v <= a.xmax + 1e-9 && count++ < 40) {
            if (abs(v) > 1e-9) {
                val x = a.px(v); c.drawLine(x, oy - 2.5f, x, oy + 2.5f, line)
                if (x < a.x + a.w - 6f) label(c, th, VisualView.fmt(v), x, oy + fs + 3f, fs, dim, tl, Paint.Align.CENTER)
            }
            v += sx
        }
        v = Math.ceil(a.ymin / sy) * sy; count = 0
        while (v <= a.ymax + 1e-9 && count++ < 40) {
            if (abs(v) > 1e-9) {
                val y = a.py(v); c.drawLine(ox - 2.5f, y, ox + 2.5f, y, line)
                if (y > a.y + 6f) label(c, th, VisualView.fmt(v), ox - 4f, y + fs * 0.35f, fs, dim, tl, Paint.Align.RIGHT)
            }
            v += sy
        }
        label(c, th, "0", ox - 4f, oy + fs + 3f, fs, dim, tl, Paint.Align.RIGHT)
        label(c, th, s.text.substringBefore(',').ifBlank { "x" }, a.x + a.w - 2f, oy - 5f, fs * 1.15f, textColor, tl, Paint.Align.RIGHT)
        label(c, th, s.text.substringAfter(',', "").ifBlank { "y" }, ox + 6f, a.y + fs, fs * 1.15f, textColor, tl)
    }

    /** График функции y = f(x) в осях: разрывы (деление на ноль, корень из отрицательного) не соединяются. */
    private fun plot(c: Canvas, th: Theme, s: Visual.Shape, a: Axes, color: Int, textColor: Int, width: Float, local: Float, seed: Int) {
        val f = ExprEval.compile(s.expr) ?: return
        val from = s.opt["from"] ?: a.xmin; val to = s.opt["to"] ?: a.xmax
        val p = Path()
        var pen = false
        val steps = 240
        val span = (a.ymax - a.ymin)
        var lastY = Double.NaN
        for (k in 0..steps) {
            val x = from + (to - from) * k / steps
            val y = f.at(x)
            val bad = y.isNaN() || y.isInfinite() || y > a.ymax + span * 2 || y < a.ymin - span * 2 ||
                (!lastY.isNaN() && abs(y - lastY) > span * 1.5)
            if (bad) { pen = false; lastY = if (y.isNaN() || y.isInfinite()) Double.NaN else y; continue }
            if (!pen) { p.moveTo(a.px(x), a.py(y)); pen = true } else p.lineTo(a.px(x), a.py(y))
            lastY = y
        }
        c.save()
        c.clipRect(a.x - 1f, a.y - 1f, a.x + a.w + 1f, a.y + a.h + 1f)
        stroke(c, th, p, color, max(width, 1.6f), s.dash, local, seed)
        c.restore()
        if (s.text.isNotBlank() && local > 0.8f) {
            val lx = (s.opt["o"] ?: (from + (to - from) * 0.8))
            val ly = f.at(lx)
            if (!ly.isNaN() && !ly.isInfinite()) label(c, th, s.text, a.px(lx) + 4f, (a.py(ly) - 6f).coerceIn(a.y + 10f, a.y + a.h), s.size.toFloat(), color, local)
        }
    }

    /** Штриховка сечения: наклонные линии под 45° внутри контура. */
    private fun hatch(c: Canvas, th: Theme, area: Path, color: Int, gap: Float, local: Float, seed: Int) {
        val b = RectF(); area.computeBounds(b, true)
        val p = Path()
        val g = gap.coerceIn(2f, 40f)
        var d = -b.height()
        while (d <= b.width()) { p.moveTo(b.left + d, b.bottom); p.lineTo(b.left + d + b.height(), b.top); d += g }
        c.save()
        c.clipPath(area)
        stroke(c, th, p, alpha(color, 0.75f), if (th.chalk) 1.1f else 0.7f, "", local, seed + 5)
        c.restore()
    }
}
