package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class OfflineBrainTest {

    private val now: Calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 14); set(Calendar.MINUTE, 35) }
    private val intents = IntentModel.parseTsv(File("src/main/assets/brain/intents.tsv").readText())
    private val massive = IntentModel.parseTsv(File("src/main/assets/brain/massive_ru.tsv").readText())
    private val model = IntentModel(intents + massive)

    @Test fun everyPreinstalledCommandIsUnderstoodByRules() {
        val canon = intents.map { it.first }.distinct()
        assertTrue("мало команд: ${canon.size}", canon.size >= 60)
        val broken = canon.filter { LocalCommands.parse(it.replace("{n}", "50"), now) == null }
        assertTrue("правила не понимают: $broken", broken.isEmpty())
    }

    @Test fun examplesPointToTheirCommand() {
        val wrong = intents.filter { (c, t) -> t != c }.filter { (c, t) -> model.predict(t)?.canonical != c }
        val share = wrong.size.toDouble() / intents.size
        assertTrue("ошибок ${wrong.size}: ${wrong.take(15)}", share < 0.12)
    }

    @Test fun newWordingsUnderstood() {
        fun c(t: String) = model.predict(t)?.canonical
        assertEquals("отключи мобильный интернет", c("вырубай интернет"))
        assertEquals("громче", c("ничего не слышно"))
        assertEquals("включи фонарик", c("посвети мне"))
        assertEquals("громкость на {n}", c("звук на 40"))
        assertEquals("громкость на 40", IntentModel.fill("громкость на {n}", "звук на сорок"))
        assertNull(IntentModel.fill("громкость на {n}", "звук на"))
        assertEquals("что на экране", c("глянь что на экране"))
    }

    @Test fun massiveSubset() {
        assertTrue(massive.count { it.first == IntentModel.NONE } > 500)
        // Позитивные примеры из MASSIVE ведут в команды, которые понимают правила
        val canon = massive.map { it.first }.distinct().filter { it != IntentModel.NONE }
        assertTrue(canon.all { LocalCommands.parse(it, now) != null })
        // Вопросы из набора «не-команд» не превращаются в команды
        assertNull(model.predict("какая погода будет завтра"))
        assertNull(model.predict("расскажи последние новости"))
    }

    @Test fun questionsAreNotCommands() {
        for (q in listOf("какая столица франции", "расскажи анекдот", "как приготовить борщ", "что такое фотосинтез",
                "мне грустно", "кто написал войну и мир", "переведи слово кошка на английский", "сколько километров до москвы")) {
            assertNull("«$q» → ${model.predict(q)}", model.predict(q))
        }
    }

    @Test fun preinstalledSkillsWork() {
        val skills = Skill.parseList(File("src/main/assets/brain/skills.json").readText())
        assertTrue(skills.size >= 8)
        val broken = skills.flatMap { s -> s.steps.filter { LocalCommands.parse(it, now) == null }.map { "${s.name}: $it" } }
        assertTrue("шаги не понимаются: $broken", broken.isEmpty())
        val home = skills.first { it.name == "Я дома" }
        assertTrue(home.matches("я дома"))
        assertTrue(home.matches("Я дома!"))
        assertFalse(home.matches("я дома был вчера весь день"))
        assertEquals("Утро.json", Skill.fileName("Утро"))
        assertEquals(home, Skill.parseList(home.toJson().toString()).first())
    }

    @Test fun knowledgeSearch() {
        val facts = File("src/main/assets/brain/knowledge.tsv").readLines().filter { it.contains('\t') && !it.startsWith("#") }
            .mapIndexed { i, l -> Fact(i.toLong(), l.substringBefore('\t'), l.substringAfter('\t')) }
        assertTrue(facts.size >= 12)
        assertEquals("Первая помощь при ожоге", KnowledgeRank.search("что делать при ожоге", facts).first().fact.title)
        assertEquals("Телефоны экстренных служб", KnowledgeRank.search("номер скорой", facts).first().fact.title)
        val mine = facts + Fact(99, "код домофона", "код домофона 4512")
        assertTrue(KnowledgeRank.answer(KnowledgeRank.search("какой код домофона", mine)).contains("4512"))
        assertTrue(KnowledgeRank.search("квантовая хромодинамика", facts).isEmpty())
        val countries = File("src/main/assets/brain/countries.tsv").readLines().filter { it.contains('\t') && !it.startsWith("#") }
            .mapIndexed { i, l -> Fact(1000L + i, l.substringBefore('\t'), l.substringAfter('\t')) }
        assertTrue(countries.size > 200)
        val all = facts + countries
        assertTrue(KnowledgeRank.search("какой код у турции", all).first().fact.body.contains("+90"))
        assertTrue(KnowledgeRank.search("какая валюта в грузии", all).first().fact.body.contains("GEL"))
    }

    @Test fun brainPhrases() {
        fun cmd(t: String) = LocalCommands.parse(t, now)
        assertEquals(mapOf("action" to "add", "text" to "код домофона 4512"), cmd("запомни факт: код домофона 4512")?.args)
        assertEquals("search", cmd("что ты знаешь про код домофона")?.args?.get("action"))
        assertEquals("build", cmd("собери справочник по первой помощи")?.args?.get("action"))
        assertEquals("skills", cmd("мои навыки")?.name)
        assertEquals("create", cmd("создай навык утро: погода и моя волна")?.args?.get("action"))
        assertEquals("off", cmd("выключи навык кино")?.args?.get("action"))
        assertEquals("train", cmd("обучись")?.args?.get("action"))
        // обычные заметки не сломаны
        assertEquals("create_note", cmd("Запомни что завтра встреча в 10")?.name)
        assertNotNull(model)
    }
}
