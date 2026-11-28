package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BoardAndScheduleTest {

    // ---------------------------------------------------------------- формулы для графиков

    @Test fun evaluatesFormulas() {
        fun f(s: String, x: Double) = ExprEval.compile(s)!!.at(x)
        assertEquals(4.0, f("x^2", 2.0), 1e-9)
        assertEquals(9.0, f("y = x^2 - 2*x + 1", 4.0), 1e-9)
        assertEquals(7.0, f("2x+1", 3.0), 1e-9)
        assertEquals(8.0, f("2(x+1)", 3.0), 1e-9)
        assertEquals(1.0, f("sin(pi/2)", 0.0), 1e-9)
        assertEquals(3.0, f("sqrt(abs(x))", -9.0), 1e-9)
        assertEquals(-4.0, f("-x^2", 2.0), 1e-9)
        assertEquals(2.5, f("x/2", 5.0), 1e-9)
        assertEquals(0.5, f("0,5", 0.0), 1e-9)
        assertTrue(f("1/x", 0.0).isInfinite())
        assertTrue(f("sqrt(x)", -1.0).isNaN())
        assertNull(ExprEval.compile(""))
        assertNull(ExprEval.compile("x +* ("))
    }

    @Test fun niceTicks() {
        assertEquals(1.0, BoardPainter.niceStep(10.0), 1e-9)
        assertEquals(2.0, BoardPainter.niceStep(20.0), 1e-9)
        assertEquals(50.0, BoardPainter.niceStep(400.0), 1e-9)
        assertEquals(0.5, BoardPainter.niceStep(4.0), 1e-9)
    }

    // ---------------------------------------------------------------- доска

    @Test fun parsesBoardWithSteps() {
        val json = """{"type":"board","title":"Треугольник","w":320,"h":220,"steps":[
            {"note":"Отметим точки","shapes":[{"t":"point","x":40,"y":180,"s":"A"},{"t":"dot","x":280,"y":180,"s":"B"}]},
            {"note":"Проведём стороны","shapes":[{"t":"poly","pts":[[40,180],[280,180],[160,40]]},{"t":"dim","x1":40,"y1":180,"x2":280,"y2":180,"s":"8 см","o":-16}]},
            {"say":"Угол при вершине","shapes":[{"t":"angle","x":160,"y":40,"x1":40,"y1":180,"x2":280,"y2":180,"s":"α"},{"t":"line","x1":160,"y1":40,"x2":160,"y2":180,"dash":"center"}]}
        ]}"""
        val d = VisualSpec.parse(json) as Visual.Drawing
        assertEquals("chalk", d.style)
        assertEquals(3, d.steps)
        assertEquals(listOf("Отметим точки", "Проведём стороны", "Угол при вершине"), d.notes)
        assertEquals(6, d.shapes.size)
        assertEquals("point", d.shapes[1].t)
        assertEquals(6, d.shapes[2].n.size) // пары [[x,y]] разворачиваются
        assertEquals(-16.0, d.shapes[3].opt["o"]!!, 0.0)
        assertEquals("center", d.shapes[5].dash)
        assertEquals(3, d.shapes[5].step)
        // в ответе находится доска с шагами — её окно открывается само
        assertNotNull(VisualSpec.firstBoard("Смотри:\n```visual\n$json\n```"))
        assertNull(VisualSpec.firstBoard("```visual\n{\"type\":\"draw\",\"shapes\":[{\"t\":\"circle\",\"x\":1,\"y\":1,\"r\":1}]}\n```"))
    }

    @Test fun parsesBlueprintAxesAndPlot() {
        val d = VisualSpec.parse("""{"type":"blueprint","shapes":[{"t":"axes","x":20,"y":20,"w":260,"h":160,"xmin":-3,"xmax":3,"ymin":-1,"ymax":9},
            {"t":"plot","f":"x^2","s":"y=x²"},{"t":"hatch","pts":[0,0,10,0,10,10]},{"t":"arc","x":5,"y":5,"r":3,"a1":0,"a2":90},{"t":"bad"}]}""") as Visual.Drawing
        assertEquals("blueprint", d.style)
        assertEquals(4, d.shapes.size)
        assertTrue(d.shapes[0].flag) // сетка у осей по умолчанию
        assertEquals("x^2", d.shapes[1].expr)
        assertEquals(0, d.steps)
        // старый формат рисунка не сломался
        val old = VisualSpec.parse("""{"type":"draw","shapes":[{"t":"box","x":1,"y":2,"w":3,"h":4},{"t":"label","x":1,"y":1,"s":"дом"}]}""") as Visual.Drawing
        assertEquals("plain", old.style); assertEquals("rect", old.shapes[0].t); assertEquals("text", old.shapes[1].t)
    }

    @Test fun boardPromptTriggers() {
        assertTrue(AssistantPrompt.wantsBoard("объясни на доске теорему Пифагора"))
        assertTrue(AssistantPrompt.wantsBoard("начерти чертёж шестерни"))
        assertTrue(AssistantPrompt.wantsBoard("построй график функции y=x^2"))
        assertFalse(AssistantPrompt.wantsBoard("какая погода завтра"))
        assertFalse(AssistantPrompt.wantsBoard("размер файла"))
    }

    // ---------------------------------------------------------------- расписание

    private fun now(h: Int, m: Int): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.MARCH, 10, h, m, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun hm(at: Long): String { val c = Calendar.getInstance().apply { timeInMillis = at }; return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
    private fun day(at: Long): Int = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_MONTH)

    @Test fun parsesInMinutes() {
        val n = now(8, 0)
        val p = ScheduledCommands.parse("через 5 минут напиши максиму в ватсап доброе утро", n)!!
        assertEquals("напиши максиму в ватсап доброе утро", p.command)
        assertEquals("08:05", hm(p.at))
        assertEquals("08:30", hm(ScheduledCommands.parse("Напиши маме, что я еду, через полчаса", n)!!.at))
        assertEquals("09:00", hm(ScheduledCommands.parse("через час выключи вайфай", n)!!.at))
        assertEquals("08:01", hm(ScheduledCommands.parse("через минуту позвони папе", n)!!.at))
        assertEquals("10:15", hm(ScheduledCommands.parse("через 2 часа 15 минут включи мою волну", n)!!.at))
        assertEquals("08:10", hm(ScheduledCommands.parse("через десять минут отключи интернет", n)!!.at))
        assertEquals("включи режим полета", ScheduledCommands.parse("запланируй через 3 минуты включи режим полета", n)!!.command)
    }

    @Test fun parsesClockTimes() {
        val n = now(14, 0)
        val a = ScheduledCommands.parse("в 23:00 выключи вайфай", n)!!
        assertEquals("23:00", hm(a.at)); assertEquals("выключи вайфай", a.command); assertEquals(10, day(a.at))
        assertEquals("08:30", hm(ScheduledCommands.parse("в 8.30 напиши максиму привет", n)!!.at))
        assertEquals(11, day(ScheduledCommands.parse("в 8.30 напиши максиму привет", n)!!.at)) // уже прошло — завтра
        assertEquals("15:00", hm(ScheduledCommands.parse("в 3 позвони маме", n)!!.at)) // «в 3» днём — это 15:00
        assertEquals("21:00", hm(ScheduledCommands.parse("напиши саше в 9 вечера спокойной ночи", n)!!.at))
        val t = ScheduledCommands.parse("завтра в 9 утра включи мою волну", n)!!
        assertEquals("09:00", hm(t.at)); assertEquals(11, day(t.at)); assertFalse(t.daily)
        val d = ScheduledCommands.parse("каждый день в 7:30 включи мою волну", n)!!
        assertTrue(d.daily); assertEquals("07:30", hm(d.at)); assertEquals("включи мою волну", d.command)
    }

    @Test fun ignoresNonSchedules() {
        val n = now(14, 0)
        assertNull(ScheduledCommands.parse("напомни через 2 часа позвонить в склад", n))
        assertNull(ScheduledCommands.parse("поставь будильник через 10 минут", n))
        assertNull(ScheduledCommands.parse("разбуди через полчаса", n))
        assertNull(ScheduledCommands.parse("какая погода будет через 3 часа", n))
        assertNull(ScheduledCommands.parse("выключи музыку через 20 минут", n))
        assertNull(ScheduledCommands.parse("сделай громкость на 5", n))
        assertNull(ScheduledCommands.parse("напиши в 2 чата привет", n))
        assertNull(ScheduledCommands.parse("включи вайфай", n))
        assertNull(ScheduledCommands.parse("работай через интернет", n))
    }

    @Test fun managesScheduled() {
        assertEquals("list" to null, ScheduledCommands.manage("что запланировано"))
        assertEquals("cancel" to "2", ScheduledCommands.manage("отмени запланированное 2"))
        assertEquals("cancel" to "все", ScheduledCommands.manage("удали все запланированные действия"))
        assertNull(ScheduledCommands.manage("включи музыку"))
        assertTrue(ScheduledCommands.needsScreen("напиши максиму в ватсап привет"))
        assertFalse(ScheduledCommands.needsScreen("выключи вайфай"))
    }

    // ---------------------------------------------------------------- промпт и режимы

    @Test fun composesUserPrompt() {
        val tutor = PromptStore.Instruction("Репетитор", listOf("объясни", "реши задачу"), "по шагам")
        val always = PromptStore.Instruction("Стиль", emptyList(), "на ты")
        val off = PromptStore.Instruction("Выкл", emptyList(), "не надо", enabled = false)
        assertTrue(PromptStore.matches(tutor, "Объясни дроби"))
        assertTrue(PromptStore.matches(tutor, "реши задачу про поезд"))
        assertFalse(PromptStore.matches(tutor, "включи музыку"))
        assertTrue(PromptStore.matches(always, "что угодно"))
        assertFalse(PromptStore.matches(off, "что угодно"))
        val text = PromptStore.compose("Зови меня Макс", listOf(tutor, always, off), "объясни степени")!!
        assertTrue(text.contains("Зови меня Макс")); assertTrue(text.contains("Репетитор")); assertTrue(text.contains("на ты")); assertFalse(text.contains("не надо"))
        assertNull(PromptStore.compose("", listOf(tutor), "включи свет"))
        val round = PromptStore.parse(PromptStore.toJson(listOf(tutor, off)))
        assertEquals(listOf(tutor, off), round)
        assertEquals(listOf("а", "б в"), PromptStore.splitTriggers(" а, б в ;"))
    }
}
