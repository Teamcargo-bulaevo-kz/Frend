package com.example.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAndLearningTest {

    @Test fun parsesAllVisualTypes() {
        val bar = VisualSpec.parse("""{"type":"bar","title":"Продажи","labels":["Янв","Фев"],"series":[{"name":"2025","values":[10,"12,5"]}]}""") as Visual.Chart
        assertEquals(listOf("Янв", "Фев"), bar.labels); assertEquals(12.5, bar.series[0].values[1], 0.001)
        val hbar = VisualSpec.parse("""{"type":"hbar","data":{"A":3,"B":5}}""") as Visual.Chart
        assertEquals("hbar", hbar.kind); assertEquals(2, hbar.labels.size)
        val pie = VisualSpec.parse("""{"type":"donut","data":[{"label":"Фото","value":12},{"label":"Видео","value":30}]}""") as Visual.Pie
        assertEquals("Видео", pie.slices.first().first)
        val table = VisualSpec.parse("""{"type":"table","columns":["Тариф","Цена"],"rows":[["А","1000"],{"Тариф":"Б","Цена":"2000"}]}""") as Visual.Table
        assertEquals(2, table.rows.size); assertEquals("Б", table.rows[1][0])
        val tree = VisualSpec.parse("""{"type":"tree","root":{"text":"Телефон","children":[{"text":"Связь","children":["Wi-Fi","SIM"]},"Экран"]}}""") as Visual.Tree
        assertEquals(2, tree.root[0].children.size); assertEquals("SIM", tree.root[0].children[0].children[1].text)
        val plan = VisualSpec.parse("""{"type":"checklist","items":[{"text":"Найти","done":true},{"text":"Отправить","status":"pending"}]}""") as Visual.ItemList
        assertEquals("check", plan.kind); assertEquals(true, plan.items[0].done); assertEquals(false, plan.items[1].done)
        val stats = VisualSpec.parse("""{"type":"stats","items":[{"label":"Заряд","value":"87%","percent":87}]}""") as Visual.Stats
        assertEquals(87.0, stats.items[0].percent!!, 0.0)
        val draw = VisualSpec.parse("""{"type":"draw","w":300,"h":200,"shapes":[{"t":"rect","x":1,"y":2,"w":3,"h":4,"fill":"blue"},{"t":"text","x":5,"y":6,"s":"дом"},{"t":"bad"}]}""") as Visual.Drawing
        assertEquals(2, draw.shapes.size); assertEquals("дом", draw.shapes[1].text)
    }

    @Test fun guessesTypeAndRejectsJunk() {
        assertTrue(VisualSpec.parse("""{"columns":["a"],"rows":[["1"]]}""") is Visual.Table)
        assertTrue(VisualSpec.parse("""{"data":{"x":1}}""") is Visual.Pie)
        assertNull(VisualSpec.parse("не json"))
        assertNull(VisualSpec.parse("""{"type":"bar"}"""))
        // больше 8 кусков пирога объединяются в «Остальное»
        val many = VisualSpec.parse("""{"type":"pie","data":{${(1..12).joinToString(",") { "\"k$it\":$it" }}}}""") as Visual.Pie
        assertEquals(8, many.slices.size); assertEquals("Остальное", many.slices.last().first)
    }

    @Test fun blocksAreHiddenFromModelAndSpeech() {
        val text = "Вот расходы.\n```visual\n{\"type\":\"pie\",\"data\":{\"a\":1}}\n```\nГотово."
        assertEquals("Вот расходы.\n[показана наглядная схема]\nГотово.", VisualSpec.stripForModel(text))
        assertFalse(VisualSpec.plain(text).contains("type"))
        val spoken = SpeechText.clean(text)
        assertTrue(spoken, spoken.contains("Схема на экране") && !spoken.contains("Код"))
    }

    @Test fun miniApps() {
        assertTrue(MiniApps.isApp("html", "<div>"))
        assertTrue(MiniApps.isApp("", "<!DOCTYPE html><html>"))
        assertFalse(MiniApps.isApp("kotlin", "fun x()"))
        assertEquals("Калькулятор", MiniApps.title("<html><head><title>Калькулятор</title></head></html>"))
        assertTrue(MiniApps.wrap("<button>1</button>").contains("<body><button>1</button></body>"))
    }

    @Test fun templatesLearnNumbers() {
        assertEquals("T:поставь громкость на #", Learning.template("поставь громкость на 30"))
        assertNull(Learning.template("включи музыку"))
        val g = Learning.generalize("поставь громкость на 30", JSONObject().put("action", "set").put("percent", 30))!!
        assertEquals("#0", g.getString("percent"))
        val filled = Learning.fill(g, "поставь громкость на 70")!!
        assertEquals(70, filled.getInt("percent")); assertEquals("set", filled.getString("action"))
        assertNull(Learning.generalize("поставь громкость на 30", JSONObject().put("percent", 50)))
        val alarm = Learning.generalize("разбуди в 7 30", JSONObject().put("hour", 7).put("minute", 30))!!
        assertEquals(6, Learning.fill(alarm, "разбуди в 6 45")!!.getInt("hour"))
    }

    @Test fun teachAndCacheable() {
        val (phrase, cmds) = Learning.parseTeach("Запомни: когда я говорю «я дома», включи вайфай и выключи мобильный интернет")!!
        assertEquals("я дома", phrase)
        assertEquals(listOf("включи вайфай", "выключи мобильный интернет"), cmds)
        assertNull(Learning.parseTeach("включи вайфай"))
        assertTrue(Learning.cacheable("quick_setting", JSONObject()))
        assertFalse(Learning.cacheable("call_contact", JSONObject()))
        assertFalse(Learning.cacheable("screen_control", JSONObject().put("action", "type")))
        assertTrue(Learning.close("включи свет в комнате", "включи свет в комнатe"))
        assertFalse(Learning.close("громкость 30", "громкость 70"))
        val macro = Learning.macroJson(listOf("flashlight" to JSONObject().put("on", true), "set_volume" to JSONObject().put("percent", 20)))
        assertEquals(2, Learning.macroCalls(macro).size)
    }

    @Test fun guideSteps() {
        val s = GuideStep.parse("Вот: {\"say\":\"Нажми Подключения\",\"target\":3,\"action\":\"tap\"}")!!
        assertEquals(3, s.target); assertEquals("tap", s.action)
        assertEquals("stuck", GuideStep.parse("{\"say\":\"x\",\"target\":0,\"action\":\"tap\"}")!!.action)
        assertEquals("scroll_down", GuideStep.parse("{\"say\":\"листай\",\"action\":\"scroll_down\"}")!!.action)
        assertNull(GuideStep.parse("нет json"))
        assertTrue(GuideStep.risky("Удалить аккаунт"))
        assertTrue(GuideStep.risky("Оплатить 500 ₽"))
        assertFalse(GuideStep.risky("Подключения"))
    }

    @Test fun agentPlan() {
        assertTrue(AgentPlan.multiStep("найди номер Ивана и отправь его маме"))
        assertTrue(AgentPlan.multiStep("сначала проверь заряд, потом включи экономию"))
        assertFalse(AgentPlan.multiStep("включи фонарик"))
        val steps = AgentPlan.steps(JSONObject("""{"steps":["Найти номер","Отправить маме"],"done":1}"""))
        assertEquals(2, steps.size)
        val v = VisualSpec.parse(AgentPlan.visual(steps, 1).substringAfter("```visual\n").substringBefore("\n```")) as Visual.ItemList
        assertEquals(true, v.items[0].done); assertEquals("сейчас", v.items[1].note)
        assertNotNull(v)
    }
}
