package com.example.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Наглядные блоки в ответах: диаграммы, таблицы, деревья, списки, плитки статистики, шаги, план и рисунок фигурами.
 * Модель (или само приложение) пишет короткий JSON в блоке ```visual … ```, телефон рисует его сам (VisualView):
 * без интернета, без библиотек, мало токенов. Здесь только разбор JSON в модель — чистый Kotlin, покрыт тестами.
 */
sealed class Visual {
    abstract val title: String

    data class Series(val name: String, val values: List<Double>)

    /** Столбцы (kind = bar, hbar) или линии (line). */
    data class Chart(override val title: String, val kind: String, val labels: List<String>, val series: List<Series>, val unit: String) : Visual()
    /** Круговая (pie) или кольцо (donut). */
    data class Pie(override val title: String, val donut: Boolean, val slices: List<Pair<String, Double>>, val unit: String) : Visual()
    data class Table(override val title: String, val columns: List<String>, val rows: List<List<String>>) : Visual()
    data class Node(val text: String, val note: String = "", val children: List<Node> = emptyList())
    data class Tree(override val title: String, val root: List<Node>) : Visual()
    data class Item(val text: String, val note: String = "", val icon: String = "", val color: String = "", val value: Double? = null, val done: Boolean? = null)
    /** list — список с метками; steps — пронумерованные шаги; check — план с галочками; timeline — время → событие; progress — полоски 0..100. */
    data class ItemList(override val title: String, val kind: String, val items: List<Item>) : Visual()
    data class Stat(val label: String, val value: String, val note: String = "", val color: String = "", val percent: Double? = null)
    data class Stats(override val title: String, val items: List<Stat>) : Visual()
    /** Рисунок фигурами в координатах w×h, как в Паскале: rect, circle, ellipse, line, arrow, text, poly. */
    data class Shape(val t: String, val n: List<Double>, val text: String = "", val fill: String = "", val stroke: String = "", val width: Double = 2.0, val size: Double = 14.0)
    data class Drawing(override val title: String, val w: Double, val h: Double, val shapes: List<Shape>, val background: String) : Visual()
}

object VisualSpec {

    /** Языки блока кода, которые считаются наглядным блоком. */
    val langs = setOf("visual", "viz", "chart", "diagram", "graph", "table", "tree", "draw", "plan")

    fun parse(json: String): Visual? = try {
        val o = JSONObject(json.trim().let { if (it.startsWith("{")) it else it.substring(it.indexOf('{')) })
        parse(o)
    } catch (e: Exception) { null }

    fun parse(o: JSONObject): Visual? {
        val type = o.optString("type", o.optString("kind", "")).lowercase().trim()
        val title = o.optString("title").take(120)
        return when (type) {
            "bar", "column", "columns", "hbar", "line", "area" -> chart(o, title, if (type == "column" || type == "columns") "bar" else if (type == "area") "line" else type)
            "pie", "donut", "ring" -> pie(o, title, type != "pie")
            "table" -> table(o, title)
            "tree", "mindmap", "hierarchy", "org" -> tree(o, title)
            "list", "steps", "check", "checklist", "plan", "todo", "timeline", "progress" -> list(o, title, when (type) {
                "checklist", "plan", "todo" -> "check"; else -> type
            })
            "stats", "kpi", "cards", "tiles" -> stats(o, title)
            "draw", "drawing", "canvas", "picture", "scheme" -> drawing(o, title)
            else -> guess(o, title)
        }
    }

    /** Модель забыла type: угадываем по полям. */
    private fun guess(o: JSONObject, title: String): Visual? = when {
        o.has("columns") && o.has("rows") -> table(o, title)
        o.has("shapes") -> drawing(o, title)
        o.has("root") || o.has("children") -> tree(o, title)
        o.has("series") || o.has("labels") -> chart(o, title, "bar")
        o.has("data") -> pie(o, title, true)
        o.has("items") -> list(o, title, "list")
        else -> null
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.replace(" ", "").replace(',', '.').replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
        else -> null
    }

    /** {"A":1,"B":2} или [{"label":"A","value":1}] или [["A",1]] → пары. */
    private fun pairs(v: Any?): List<Pair<String, Double>> = when (v) {
        is JSONObject -> v.keys().asSequence().mapNotNull { k -> num(v.opt(k))?.let { k to it } }.toList()
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            when (val e = v.opt(i)) {
                is JSONObject -> {
                    val label = e.optString("label", e.optString("name", e.optString("text", "")))
                    num(e.opt("value") ?: e.opt("v") ?: e.opt("count"))?.let { label to it }
                }
                is JSONArray -> num(e.opt(1))?.let { e.optString(0) to it }
                else -> null
            }
        }
        else -> emptyList()
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.opt(it)?.toString().orEmpty() }

    private fun chart(o: JSONObject, title: String, kind: String): Visual? {
        val unit = o.optString("unit")
        val seriesArr = o.optJSONArray("series")
        if (seriesArr != null && seriesArr.length() > 0) {
            val labels = strings(o.optJSONArray("labels"))
            val series = (0 until seriesArr.length()).mapNotNull { i ->
                val s = seriesArr.optJSONObject(i) ?: return@mapNotNull null
                val values = s.optJSONArray("values") ?: s.optJSONArray("data") ?: return@mapNotNull null
                Visual.Series(s.optString("name", ""), (0 until values.length()).map { num(values.opt(it)) ?: 0.0 })
            }.take(6)
            if (series.isEmpty()) return null
            val n = maxOf(labels.size, series.maxOf { it.values.size })
            return Visual.Chart(title, kind, (0 until n).map { labels.getOrElse(it) { "${it + 1}" } }.take(40), series.map { it.copy(values = it.values.take(40)) }, unit)
        }
        val values = o.optJSONArray("values")
        if (values != null) {
            val labels = strings(o.optJSONArray("labels"))
            val vals = (0 until values.length()).map { num(values.opt(it)) ?: 0.0 }.take(40)
            return Visual.Chart(title, kind, vals.indices.map { labels.getOrElse(it) { "${it + 1}" } }, listOf(Visual.Series("", vals)), unit)
        }
        val p = pairs(o.opt("data") ?: o.opt("items")).take(40)
        if (p.isEmpty()) return null
        return Visual.Chart(title, kind, p.map { it.first }, listOf(Visual.Series("", p.map { it.second })), unit)
    }

    private fun pie(o: JSONObject, title: String, donut: Boolean): Visual? {
        val p = pairs(o.opt("data") ?: o.opt("items") ?: o.opt("slices")).filter { it.second > 0 }
        if (p.isEmpty()) return null
        // Больше 8 кусков не читается: мелкие объединяем в «Остальное»
        val sorted = p.sortedByDescending { it.second }
        val slices = if (sorted.size <= 8) sorted else sorted.take(7) + ("Остальное" to sorted.drop(7).sumOf { it.second })
        return Visual.Pie(title, donut, slices, o.optString("unit"))
    }

    private fun table(o: JSONObject, title: String): Visual? {
        val rowsArr = o.optJSONArray("rows") ?: return null
        var columns = strings(o.optJSONArray("columns") ?: o.optJSONArray("headers"))
        val rows = (0 until rowsArr.length()).mapNotNull { i ->
            when (val r = rowsArr.opt(i)) {
                is JSONArray -> strings(r)
                is JSONObject -> {
                    if (columns.isEmpty()) columns = r.keys().asSequence().toList()
                    columns.map { r.opt(it)?.toString().orEmpty() }
                }
                else -> null
            }
        }.take(60)
        if (rows.isEmpty()) return null
        val width = maxOf(columns.size, rows.maxOf { it.size }).coerceAtMost(8)
        return Visual.Table(title, (0 until width).map { columns.getOrElse(it) { "" } },
            rows.map { r -> (0 until width).map { r.getOrElse(it) { "" }.take(200) } })
    }

    private fun node(v: Any?, depth: Int): Visual.Node? {
        if (depth > 6) return null
        return when (v) {
            is String -> Visual.Node(v.take(120))
            is JSONObject -> {
                val text = v.optString("text", v.optString("name", v.optString("label", "")))
                val kids = v.optJSONArray("children") ?: v.optJSONArray("items")
                Visual.Node(text.take(120), v.optString("note").take(120),
                    if (kids == null) emptyList() else (0 until kids.length()).mapNotNull { node(kids.opt(it), depth + 1) }.take(20))
            }
            else -> null
        }
    }

    private fun tree(o: JSONObject, title: String): Visual? {
        val roots = when (val r = o.opt("root") ?: o.opt("items") ?: o.opt("children")) {
            is JSONArray -> (0 until r.length()).mapNotNull { node(r.opt(it), 0) }
            null -> listOfNotNull(node(o, 0))
            else -> listOfNotNull(node(r, 0))
        }
        if (roots.isEmpty()) return null
        return Visual.Tree(title, roots)
    }

    private fun list(o: JSONObject, title: String, kind: String): Visual? {
        val arr = o.optJSONArray("items") ?: o.optJSONArray("steps") ?: return null
        val items = (0 until arr.length()).mapNotNull { i ->
            when (val e = arr.opt(i)) {
                is String -> Visual.Item(e.take(200))
                is JSONObject -> {
                    val text = e.optString("text", e.optString("label", e.optString("title", "")))
                    val done = when {
                        e.has("done") -> e.optBoolean("done")
                        e.has("status") -> e.optString("status").lowercase() in setOf("done", "completed", "готово", "сделано")
                        else -> null
                    }
                    Visual.Item(text.take(200), e.optString("note", e.optString("time", "")).take(120), e.optString("icon").take(4),
                        e.optString("color"), num(e.opt("value") ?: e.opt("percent")), done)
                }
                else -> null
            }
        }.take(40)
        if (items.isEmpty()) return null
        return Visual.ItemList(title, kind, items)
    }

    private fun stats(o: JSONObject, title: String): Visual? {
        val arr = o.optJSONArray("items") ?: return null
        val items = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            Visual.Stat(e.optString("label").take(40), e.opt("value")?.toString().orEmpty().take(24), e.optString("note").take(60),
                e.optString("color"), num(e.opt("percent")))
        }.take(12)
        if (items.isEmpty()) return null
        return Visual.Stats(title, items)
    }

    private fun drawing(o: JSONObject, title: String): Visual? {
        val arr = o.optJSONArray("shapes") ?: return null
        val shapes = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = s.optString("t", s.optString("type", "")).lowercase()
            val n: List<Double> = when (t) {
                "rect", "box", "ellipse", "oval" -> listOf("x", "y", "w", "h").map { s.optDouble(it, 0.0) }
                "circle" -> listOf("x", "y", "r").map { s.optDouble(it, 0.0) }
                "line", "arrow" -> listOf("x1", "y1", "x2", "y2").map { s.optDouble(it, 0.0) }
                "text", "label" -> listOf("x", "y").map { s.optDouble(it, 0.0) }
                "poly", "polygon", "path" -> s.optJSONArray("pts")?.let { p -> (0 until p.length()).map { p.optDouble(it, 0.0) } } ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            Visual.Shape(t, n, s.optString("s", s.optString("text", "")).take(80), s.optString("fill"), s.optString("stroke", s.optString("color")),
                s.optDouble("width", 2.0), s.optDouble("size", 14.0))
        }.take(200)
        if (shapes.isEmpty()) return null
        return Visual.Drawing(title, o.optDouble("w", 300.0).coerceIn(50.0, 2000.0), o.optDouble("h", 200.0).coerceIn(50.0, 2000.0), shapes, o.optString("bg"))
    }

    // ---------------------------------------------------------------- для истории чата и озвучки

    private val blockRe = Regex("```(visual|viz|chart|diagram|graph|table|tree|draw|plan)[ \\t]*\\r?\\n.*?(```|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val htmlBlock = Regex("```html[ \\t]*\\r?\\n.*?(```|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Текст без наглядных блоков (мини-приложения и код остаются). */
    fun withoutVisual(text: String): String = text.replace(blockRe, "")

    /** Для уведомлений и всплывающих подсказок: блоки убираются совсем. */
    fun plain(text: String): String =
        text.replace(blockRe, "").replace(htmlBlock, "[мини-приложение — в чате]").replace(Regex("\n{3,}"), "\n\n").trim()

    /** Для модели: наглядные блоки из прошлых ответов заменяются пометкой, чтобы не тратить токены. */
    fun stripForModel(text: String): String =
        text.replace(blockRe, "[показана наглядная схема]").replace(htmlBlock, "[показано мини-приложение]")

    // ---------------------------------------------------------------- построение из данных приложения

    fun block(json: JSONObject): String = "\n```visual\n$json\n```"

    fun pieJson(title: String, data: List<Pair<String, Double>>, unit: String = ""): JSONObject =
        JSONObject().put("type", "donut").put("title", title).put("unit", unit)
            .put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v) } })

    fun barJson(title: String, data: List<Pair<String, Double>>, unit: String = "", horizontal: Boolean = true): JSONObject =
        JSONObject().put("type", if (horizontal) "hbar" else "bar").put("title", title).put("unit", unit)
            .put("data", JSONArray().apply { data.forEach { (k, v) -> put(JSONObject().put("label", k).put("value", v)) } })

    fun statsJson(title: String, items: List<Visual.Stat>): JSONObject =
        JSONObject().put("type", "stats").put("title", title).put("items", JSONArray().apply {
            items.forEach { s -> put(JSONObject().put("label", s.label).put("value", s.value).put("note", s.note).put("color", s.color).apply { s.percent?.let { put("percent", it) } }) }
        })

    fun listJson(title: String, kind: String, items: List<Visual.Item>): JSONObject =
        JSONObject().put("type", kind).put("title", title).put("items", JSONArray().apply {
            items.forEach { i -> put(JSONObject().put("text", i.text).put("note", i.note).put("icon", i.icon).put("color", i.color).apply { i.done?.let { put("done", it) }; i.value?.let { put("value", it) } }) }
        })
}
