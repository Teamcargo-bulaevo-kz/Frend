package com.example.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Офлайн-мозг ассистента: база знаний (SQLite в Assistant/База), навыки (JSON в Assistant/Навыки) и «понималка»
 * ([IntentModel]). Всё работает без интернета; Groq только пополняет: придумывает примеры для понималки,
 * собирает справочники в базу, пишет навыки по описанию. С предустановленной базой из assets/brain.
 */
object OfflineBrain {

    private const val PREFS = "assistant_prefs"
    const val KEY_MODE = "brain_mode" // auto | online | offline
    private const val KEY_TRAINED = "brain_trained_at"

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "auto") ?: "auto"
    fun setMode(context: Context, mode: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()

    fun modeTitle(mode: String) = when (mode) { "online" -> "Только онлайн (Groq)"; "offline" -> "Только офлайн"; else -> "Авто" }

    // ---------------------------------------------------------------- папки

    private fun base(context: Context, name: String): File {
        val root = if (SharedFolder.hasAccess()) SharedFolder.root() else context.applicationContext.filesDir
        return File(root, name).apply { mkdirs() }
    }

    fun dataDir(context: Context) = base(context, "База")
    fun skillsDir(context: Context) = base(context, "Навыки")

    private fun asset(context: Context, path: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) { "" }

    // ---------------------------------------------------------------- база знаний

    @Volatile private var db: SQLiteDatabase? = null
    private var dbPath = ""

    @Synchronized
    private fun db(context: Context): SQLiteDatabase {
        val file = File(dataDir(context), "знания.db")
        db?.let { if (it.isOpen && dbPath == file.path) return it }
        val d = SQLiteDatabase.openOrCreateDatabase(file, null)
        d.execSQL("CREATE TABLE IF NOT EXISTS facts(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, tags TEXT, source TEXT, created INTEGER)")
        val count = d.rawQuery("SELECT COUNT(*) FROM facts", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count == 0) {
            asset(context, "brain/knowledge.tsv").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('\t') }
                .forEach { line -> insert(d, line.substringBefore('\t'), line.substringAfter('\t'), "", "встроено") }
        }
        db = d; dbPath = file.path
        return d
    }

    private fun insert(d: SQLiteDatabase, title: String, body: String, tags: String, source: String): Long =
        d.insert("facts", null, ContentValues().apply {
            put("title", title.trim().take(200)); put("body", body.trim().take(4000)); put("tags", tags); put("source", source); put("created", System.currentTimeMillis())
        })

    fun facts(context: Context): List<Fact> = db(context).rawQuery("SELECT id, title, body, tags, source FROM facts ORDER BY id DESC LIMIT 5000", null).use { c ->
        val out = ArrayList<Fact>()
        while (c.moveToNext()) out += Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3).orEmpty(), c.getString(4).orEmpty())
        out
    }

    fun addFact(context: Context, title: String, body: String, source: String = "пользователь"): Long = insert(db(context), title, body, "", source)

    fun deleteFacts(context: Context, ids: List<Long>): Int = ids.sumOf { db(context).delete("facts", "id = ?", arrayOf(it.toString())) }

    fun search(context: Context, query: String, limit: Int = 5) = KnowledgeRank.search(query, facts(context), limit)

    /** «запомни факт: номер машины 123 АВС» → заголовок «номер машины», текст целиком. */
    fun remember(context: Context, text: String): String {
        val t = text.trim().trim(':', ' ', '.')
        if (t.length < 3) return "Скажи, что запомнить: например «запомни факт: код домофона 1234»."
        val title = t.split(Regex("[:—–-]| это | — | равно | будет ")).first().trim().take(60).ifEmpty { t.take(40) }
        addFact(context, title, t)
        return "Записал в базу знаний: «$t». Спроси потом: «что ты знаешь про ${title.lowercase()}»."
    }

    fun listText(context: Context): String {
        val all = facts(context)
        val mine = all.filter { it.source != "встроено" }
        val rows = all.take(30).map { listOf(it.title, it.source.ifBlank { "—" }) }
        val table = JSONObject().put("type", "table").put("title", "База знаний: ${all.size}").put("columns", JSONArray(listOf("Запись", "Откуда")))
            .put("rows", JSONArray(rows.map { JSONArray(it) }))
        return "В базе знаний ${all.size} записей (твоих — ${mine.size}). Файл: ${File(dataDir(context), "знания.db").path.substringAfter("/0/")}." + VisualSpec.block(table)
    }

    // ---------------------------------------------------------------- навыки

    @Volatile private var skillCache: List<Skill>? = null

    fun skills(context: Context): List<Skill> {
        skillCache?.let { return it }
        val dir = skillsDir(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("skills_seeded", false)) {
            Skill.parseList(asset(context, "brain/skills.json")).forEach { s ->
                val f = File(dir, Skill.fileName(s.name)); if (!f.exists()) f.writeText(s.toJson().toString(2))
            }
            prefs.edit().putBoolean("skills_seeded", true).apply()
        }
        val list = dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }.orEmpty()
            .flatMap { f -> try { Skill.parseList(f.readText()) } catch (e: Exception) { emptyList() } }
            .sortedBy { it.name.lowercase() }
        skillCache = list
        return list
    }

    fun saveSkill(context: Context, skill: Skill) {
        File(skillsDir(context), Skill.fileName(skill.name)).writeText(skill.toJson().toString(2))
        skillCache = null; model = null
    }

    fun skillFor(context: Context, prompt: String): Skill? = skills(context).firstOrNull { it.enabled && it.matches(prompt) }

    fun skillsText(context: Context): String {
        val all = skills(context)
        if (all.isEmpty()) return "Навыков пока нет. Скажи: «создай навык „утро“: погода, заряд и моя волна»."
        val rows = all.map { listOf((if (it.enabled) "✓ " else "✗ ") + it.name, it.triggers.take(2).joinToString(", "), it.steps.joinToString(" → ").take(80)) }
        val table = JSONObject().put("type", "table").put("title", "Навыки").put("columns", JSONArray(listOf("Навык", "Фразы", "Что делает")))
            .put("rows", JSONArray(rows.map { JSONArray(it) }))
        return "Навыков: ${all.size}, включено ${all.count { it.enabled }}. «Выключи навык …», «создай навык …». Файлы — в папке Навыки." + VisualSpec.block(table)
    }

    fun toggleSkill(context: Context, name: String, on: Boolean): String {
        val key = TextMatch.normalize(name)
        val s = skills(context).firstOrNull { TextMatch.normalize(it.name) == key } ?: skills(context).firstOrNull { TextMatch.normalize(it.name).contains(key) }
            ?: return "Нет навыка «$name». Скажи «мои навыки» — покажу список."
        saveSkill(context, s.copy(enabled = on))
        return "Навык «${s.name}» ${if (on) "включён" else "выключен"}."
    }

    // ---------------------------------------------------------------- понималка

    @Volatile private var model: IntentModel? = null
    @Volatile var learnedExamples: List<Pair<String, String>> = emptyList()

    private fun userIntentsFile(context: Context) = File(dataDir(context), "понималка.tsv")

    fun invalidate() { model = null }

    /** Модель из предустановленных примеров + придуманных Groq + триггеров навыков + выученных фраз. */
    fun model(context: Context): IntentModel {
        model?.let { return it }
        val ex = ArrayList<Pair<String, String>>()
        ex += IntentModel.parseTsv(asset(context, "brain/intents.tsv"))
        try { userIntentsFile(context).takeIf { it.exists() }?.let { ex += IntentModel.parseTsv(it.readText()) } } catch (_: Exception) {}
        skills(context).filter { it.enabled }.forEach { s -> (s.triggers + s.name).forEach { ex += ("skill:" + s.name) to it } }
        ex += learnedExamples
        return IntentModel(ex).also { model = it }
    }

    /** Каноническая команда для фразы или null. relaxed — без интернета берём и менее уверенные совпадения. */
    fun understand(context: Context, prompt: String, relaxed: Boolean = false): IntentModel.Match? {
        val words = TextMatch.normalize(prompt).split(' ').size
        if (words > 10) return null // длинные фразы — это вопросы и беседа, а не команды
        return if (relaxed) model(context).predict(prompt, 0.45, 0.04) else model(context).predict(prompt)
    }

    fun stats(context: Context): String {
        val m = model(context)
        return "Понималка: ${m.intents} команд, ${m.size} примеров. База знаний: ${facts(context).size} записей. Навыков: ${skills(context).size}."
    }

    /**
     * Обучение понималки: Groq придумывает разговорные варианты для команд (предустановленных и выученных),
     * они сохраняются в Assistant/База/понималка.tsv и сразу учитываются. Блокирующий (сеть).
     */
    suspend fun train(context: Context, client: GroqClient, extraCanonicals: List<String> = emptyList()): String {
        val canon = (IntentModel.parseTsv(asset(context, "brain/intents.tsv")).map { it.first }.distinct() + extraCanonicals).distinct()
        val existing = LinkedHashMap<String, MutableList<String>>()
        try { IntentModel.parseTsv(userIntentsFile(context).takeIf { it.exists() }?.readText().orEmpty()).forEach { (c, t) -> if (t != c) existing.getOrPut(c) { ArrayList() }.add(t) } } catch (_: Exception) {}
        var added = 0
        for (chunk in canon.chunked(12)) {
            val prompt = "Для каждой команды голосового ассистента в телефоне придумай 12 разных способов сказать её по-русски: разговорно, коротко, " +
                "со сленгом и опечатками распознавания речи, без слова «пожалуйста». Если в команде есть {n} — оставь {n} на месте числа. " +
                "Ответь ТОЛЬКО JSON-объектом {\"команда\": [\"вариант\", …]}.\nКоманды:\n" + chunk.joinToString("\n")
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
            val text = try { (client.sendConversation(messages, null, effort = "low", maxTokens = 3000) as? GroqResult.FinalText)?.text.orEmpty() } catch (e: Exception) { break }
            val o = try { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) } catch (e: Exception) { continue }
            for (c in chunk) {
                val arr = o.optJSONArray(c) ?: continue
                val list = existing.getOrPut(c) { ArrayList() }
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.length in 2..80 && v !in list) { list += v; added++ }
                }
            }
        }
        if (added == 0) return "Не получилось обучить понималку: нет связи с моделью. Попробую позже."
        userIntentsFile(context).writeText("# Придумано Groq для понималки. Можно править.\n" + IntentModel.toTsv(existing))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_TRAINED, System.currentTimeMillis()).apply()
        invalidate()
        return "Понималка обучена: +$added новых вариантов. ${stats(context)}"
    }

    /** Пора ли тихо дообучиться (раз в 5 дней, при наличии ключа модели). */
    fun trainDue(context: Context): Boolean = BuildConfig.GROQ_API_KEY.isNotBlank() &&
        System.currentTimeMillis() - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TRAINED, 0L) > 5 * 86_400_000L

    /** Справочник по теме: Groq пишет 10–15 записей, они сохраняются в базу и дальше доступны без интернета. */
    suspend fun buildReference(context: Context, client: GroqClient, topic: String): String {
        val prompt = "Составь короткий справочник по теме «$topic» для базы знаний в телефоне: 10–15 записей, каждая — заголовок и 1–3 предложения " +
            "с самым полезным (по-русски, проверенные факты, без воды). Ответь ТОЛЬКО JSON-массивом [{\"title\":\"…\",\"body\":\"…\"}]."
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val text = (client.sendConversation(messages, null, effort = "low", maxTokens = 3500) as? GroqResult.FinalText)?.text.orEmpty()
        val arr = try { JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)) } catch (e: Exception) { return "Не получилось собрать справочник. Попробуй ещё раз." }
        var n = 0
        val titles = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title").trim(); val b = o.optString("body").trim()
            if (t.isNotEmpty() && b.isNotEmpty()) { addFact(context, t, b, "справочник: $topic"); titles += t; n++ }
        }
        return "Собрал справочник «$topic»: $n записей — теперь отвечаю на это и без интернета." +
            VisualSpec.block(VisualSpec.listJson("Справочник: $topic", "list", titles.take(15).map { Visual.Item(it) }))
    }

    /** Навык по описанию: Groq превращает «утро: погода, заряд, моя волна» в файл навыка из понятных команд. */
    suspend fun createSkill(context: Context, client: GroqClient, description: String): String {
        val prompt = "Создай навык для голосового ассистента по описанию: «$description». Шаги — короткие команды, которые ассистент понимает " +
            "(например: «включи вайфай», «отключи мобильный интернет», «яркость на 30», «включи режим не беспокоить», «включи мою волну», " +
            "«какая погода», «полный отчёт о телефоне», «прочитай уведомления», «поставь таймер на 10 минут»). " +
            "Ответь ТОЛЬКО JSON: {\"name\":\"…\",\"triggers\":[\"фраза запуска\", …],\"answer\":\"что сказать в начале\",\"steps\":[\"команда\", …]}."
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val text = (client.sendConversation(messages, null, effort = "low", maxTokens = 1200) as? GroqResult.FinalText)?.text.orEmpty()
        val skill = Skill.parseList(text).firstOrNull() ?: return "Не получилось составить навык. Опиши проще: «создай навык „дом“: вайфай включи, интернет выключи»."
        saveSkill(context, skill)
        return "Создал навык «${skill.name}». Запуск: ${skill.triggers.take(3).joinToString(", ") { "«$it»" }}." +
            VisualSpec.block(VisualSpec.listJson("Навык «${skill.name}»", "steps", skill.steps.map { Visual.Item(it) }))
    }
}
