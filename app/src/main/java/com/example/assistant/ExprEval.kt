package com.example.assistant

import kotlin.math.PI
import kotlin.math.E

/**
 * Маленький вычислитель формул для графиков на доске: «x^2 - 2*x + 1», «sin(x)/x», «2x+1», «sqrt(abs(x))».
 * Переменная x, числа (точка или запятая), + − * / ^, скобки, неявное умножение («2x», «3(x+1)»),
 * функции sin cos tan sqrt abs ln log exp, константы pi и e. Разбирает один раз, считает быстро. Чистый Kotlin, есть тесты.
 */
class ExprEval private constructor(private val root: Node) {

    fun at(x: Double): Double = try { root.eval(x) } catch (_: Exception) { Double.NaN }

    private sealed class Node {
        abstract fun eval(x: Double): Double
        class Num(val v: Double) : Node() { override fun eval(x: Double) = v }
        object X : Node() { override fun eval(x: Double) = x }
        class Neg(val a: Node) : Node() { override fun eval(x: Double) = -a.eval(x) }
        class Bin(val op: Char, val a: Node, val b: Node) : Node() {
            override fun eval(x: Double): Double {
                val l = a.eval(x); val r = b.eval(x)
                return when (op) { '+' -> l + r; '-' -> l - r; '*' -> l * r; '/' -> l / r; else -> Math.pow(l, r) }
            }
        }
        class Fn(val name: String, val a: Node) : Node() {
            override fun eval(x: Double): Double {
                val v = a.eval(x)
                return when (name) {
                    "sin" -> Math.sin(v); "cos" -> Math.cos(v); "tan", "tg" -> Math.tan(v); "ctg", "cot" -> 1 / Math.tan(v)
                    "sqrt" -> Math.sqrt(v); "abs" -> Math.abs(v); "ln" -> Math.log(v); "log", "lg" -> Math.log10(v)
                    "exp" -> Math.exp(v); "asin", "arcsin" -> Math.asin(v); "acos", "arccos" -> Math.acos(v); "atan", "arctg" -> Math.atan(v)
                    else -> Double.NaN
                }
            }
        }
    }

    private class Parser(src: String) {
        val s = src.lowercase().replace(',', '.').replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace(" ", "")
            .removePrefix("y=").removePrefix("f(x)=")
        var i = 0

        fun parse(): Node { val n = sum(); if (i != s.length) error("лишнее: ${s.substring(i)}"); return n }

        private fun sum(): Node {
            var n = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; n = Node.Bin(op, n, product()) }
            return n
        }

        private fun product(): Node {
            var n = unary()
            while (i < s.length) {
                val c = s[i]
                n = when {
                    c == '*' || c == '/' -> { i++; Node.Bin(c, n, unary()) }
                    // неявное умножение: 2x, 3(x+1), x sin(x), )(
                    c == '(' || c == 'x' || c.isLetter() || c.isDigit() || c == '.' -> Node.Bin('*', n, power())
                    else -> return n
                }
            }
            return n
        }

        private fun unary(): Node = when {
            i < s.length && s[i] == '-' -> { i++; Node.Neg(unary()) }
            i < s.length && s[i] == '+' -> { i++; unary() }
            else -> power()
        }

        private fun power(): Node {
            val base = atom()
            if (i < s.length && s[i] == '^') { i++; return Node.Bin('^', base, unary()) }
            if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') { i += 2; return Node.Bin('^', base, unary()) }
            return base
        }

        private fun atom(): Node {
            if (i >= s.length) error("конец формулы")
            val c = s[i]
            if (c == '(') { i++; val n = sum(); if (i < s.length && s[i] == ')') i++; return n }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return Node.Num(s.substring(start, i).toDouble())
            }
            if (c.isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetter()) i++
                val word = s.substring(start, i)
                return when (word) {
                    "x" -> Node.X
                    "pi", "п" -> Node.Num(PI)
                    "e" -> Node.Num(E)
                    else -> {
                        // «xsin» и похожие склейки: x перед функцией
                        if (word.startsWith("x") && word.length > 1) { i = start + 1; return Node.X }
                        if (i < s.length && s[i] == '(') { i++; val a = sum(); if (i < s.length && s[i] == ')') i++; Node.Fn(word, a) }
                        else Node.Fn(word, atom())
                    }
                }
            }
            error("непонятный символ «$c»")
        }
    }

    companion object {
        /** null — формулу не удалось разобрать. */
        fun compile(formula: String): ExprEval? = try {
            if (formula.isBlank() || formula.length > 200) null else ExprEval(Parser(formula).parse())
        } catch (_: Exception) { null }
    }
}
