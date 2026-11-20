package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcTest {

    private fun value(text: String): String? = Calc.extract(text)?.let { Calc.eval(it) }?.let { Calc.format(it) }

    @Test fun digitsAndWords() {
        assertEquals("100", value("сколько будет 25 умножить на 4"))
        assertEquals("100", value("сколько будет двадцать пять умножить на четыре"))
        assertEquals("450", value("посчитай 15 процентов от 3000"))
        assertEquals("12", value("сколько будет корень из 144"))
        assertEquals("1 024", value("сколько будет 2 в степени 10"))
        assertEquals("14", value("посчитай (3 + 4) * 2"))
        assertEquals("2,5", value("сколько будет 5 разделить на 2"))
        assertEquals("3 200", value("сколько будет три тысячи двести"))
        assertEquals("-5", value("сколько будет 5 минус 10"))
        assertEquals("25", value("сколько будет 5 в квадрате"))
    }

    @Test fun notMath() {
        assertNull(Calc.extract("сколько будет стоить билет"))
        assertNull(Calc.extract("посчитай калории"))
        assertNull(Calc.extract("включи музыку"))
        assertNull(Calc.extract("сколько будет 5 разделить на 0")?.let { Calc.eval(it) })
    }
}
