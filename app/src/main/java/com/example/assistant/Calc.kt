package com.example.assistant

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Калькулятор для голоса и чата без модели (0 токенов): «сколько будет 25 умножить на 4», «15 процентов от 3000»,
 * «корень из 144», «2 в степени 10», «(3 + 4) * 2». Только числа и простые действия; всё остальное — модели.
 */
object Calc {

    private val prefixes = listOf("сколько будет", "посчитай", "вычисли", "подсчитай", "сосчитай", "сколько это", "реши", "калькулятор")

    /** Выражение из фразы или null, если это не просьба посчитать. */
    fun extract(text: String): String? {
        var t = text.lowercase().replace('ё', 'е').replace(',', '.').trim().trimEnd('?', '.', '!', '=')
        val prefix = prefixes.firstOrNull { t.startsWith(it) } ?: return null
        t = t.removePrefix(prefix).trim()
        if (t.isEmpty()) return null
        val expr = toExpression(t) ?: return null
        return expr.takeIf { it.any { c -> c.isDigit() } }
    }

    private val units = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90, "сто" to 100, "двести" to 200,
        "триста" to 300, "четыреста" to 400, "пятьсот" to 500, "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900
    )
    private val scales = mapOf("тысяча" to 1_000L, "тысячи" to 1_000L, "тысяч" to 1_000L, "тысячу" to 1_000L,
        "миллион" to 1_000_000L, "миллиона" to 1_000_000L, "миллионов" to 1_000_000L)

    /** «двадцать пять умножить на три тысячи» → «25 умножить на 3000». */
    fun wordsToDigits(text: String): String {
        val out = ArrayList<String>()
        var total = 0L
        var current = 0L
        var inNumber = false
        fun flush() { if (inNumber) out.add((total + current).toString()); total = 0; current = 0; inNumber = false }
        for (w in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val u = units[w]
            val sc = scales[w]
            when {
                u != null -> { current += u; inNumber = true }
                sc != null -> { total += (if (current == 0L) 1 else current) * sc; current = 0; inNumber = true }
                else -> { flush(); out.add(w) }
            }
        }
        flush()
        return out.joinToString(" ")
    }

    /** Русские слова в знаки. null — в тексте есть что-то кроме чисел и действий. */
    fun toExpression(text: String): String? {
        val digits = wordsToDigits(text.lowercase().replace('ё', 'е'))
        var t = " " + digits.replace(Regex("(\\d)\\s+(?=\\d{3}\\b)"), "$1") + " "
        // «15 процентов от 3000» → (15/100*3000)
        t = Regex("([\\d.]+)\\s*(?:%|процент\\p{L}*)\\s*от\\s*([\\d.]+)").replace(t) { "(${it.groupValues[1]}/100*${it.groupValues[2]})" }
        val words = listOf(
            "умножить на" to "*", "умножь на" to "*", "помножить на" to "*", "умноженное на" to "*", "умножено на" to "*",
            "разделить на" to "/", "раздели на" to "/", "делить на" to "/", "поделить на" to "/", "деленное на" to "/", "делённое на" to "/",
            "в квадрате" to "^2", "в кубе" to "^3", "в степени" to "^", "квадратный корень из" to "√", "корень из" to "√",
            "плюс" to "+", "минус" to "-", "прибавить" to "+", "отнять" to "-", "вычесть" to "-", "на" to "*",
            "x" to "*", "х" to "*", "×" to "*", "÷" to "/", ":" to "/"
        )
        for ((w, op) in words) t = t.replace(Regex("(?<=[\\s\\d)])" + Regex.escape(w) + "(?=[\\s\\d(√])"), " $op ")
        t = t.replace(Regex("\\s+"), "")
        if (t.isEmpty() || !t.all { it.isDigit() || it in "+-*/^().√" }) return null
        return t
    }

    /** Значение выражения или null, если ошибка (деление на ноль, скобки). */
    fun eval(expr: String): Double? = try {
        val p = Parser(expr)
        val v = p.expression()
        if (p.pos != expr.length || v.isNaN() || v.isInfinite()) null else v
    } catch (e: Exception) { null }

    /** Ответ для пользователя: «25 × 4 = 100». */
    fun answer(text: String): String? {
        val expr = extract(text) ?: return null
        val v = eval(expr) ?: return "Не смог посчитать «$expr»"
        return "${pretty(expr)} = ${format(v)}"
    }

    fun format(v: Double): String {
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) return String.format(java.util.Locale.ROOT, "%,d", v.toLong()).replace(',', ' ')
        return String.format(java.util.Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.').replace('.', ',')
    }

    private fun pretty(expr: String) = expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ")
        .replace(Regex("(?<=[\\d)])-"), " − ").replace("^", "^")

    private class Parser(val s: String) {
        var pos = 0
        fun peek(): Char? = s.getOrNull(pos)
        fun expression(): Double {
            var v = term()
            while (true) when (peek()) {
                '+' -> { pos++; v += term() }
                '-' -> { pos++; v -= term() }
                else -> return v
            }
        }
        fun term(): Double {
            var v = power()
            while (true) when (peek()) {
                '*' -> { pos++; v *= power() }
                '/' -> { pos++; val d = power(); if (d == 0.0) throw ArithmeticException(); v /= d }
                else -> return v
            }
        }
        fun power(): Double {
            val base = unary()
            if (peek() == '^') { pos++; return base.pow(power()) }
            return base
        }
        fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            '√' -> { pos++; val v = unary(); if (v < 0) throw ArithmeticException(); sqrt(v) }
            else -> atom()
        }
        fun atom(): Double {
            if (peek() == '(') {
                pos++
                val v = expression()
                if (peek() != ')') throw IllegalArgumentException()
                pos++
                return v
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            if (start == pos) throw IllegalArgumentException()
            return s.substring(start, pos).toDouble()
        }
    }
}
