package com.example.assistant

import java.io.File
import java.util.zip.ZipFile

/** Чтение xlsx и docx без внешних библиотек, разбор и сборка csv. Чистый Kotlin. */
object FileConvert {

    private fun unescape(s: String): String =
        s.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun entry(zip: ZipFile, name: String): String? =
        zip.getEntry(name)?.let { e -> zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() } }

    // ---------------------------------------------------------------- xlsx

    /** Первый лист книги Excel как таблица строк. Даты остаются числами Excel. */
    fun xlsxRows(file: File, maxRows: Int = 20000): List<List<String>> {
        ZipFile(file).use { zip ->
            val shared = entry(zip, "xl/sharedStrings.xml")?.let { xml ->
                Regex("(?s)<si>(.*?)</si>").findAll(xml).map { si ->
                    Regex("(?s)<t[^>]*>(.*?)</t>").findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
                }.toList()
            }.orEmpty()

            val sheetName = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .minByOrNull { it.length.toString().padStart(3, '0') + it } ?: return emptyList()
            val xml = entry(zip, sheetName) ?: return emptyList()

            val rows = ArrayList<List<String>>()
            for (row in Regex("(?s)<row\\b[^>]*?(?:/>|>(.*?)</row>)").findAll(xml)) {
                if (rows.size >= maxRows) break
                val cells = ArrayList<String>()
                for (c in Regex("(?s)<c\\b([^>]*?)(?:/>|>(.*?)</c>)").findAll(row.groupValues[1])) {
                    val attrs = c.groupValues[1]
                    val inner = c.groupValues[2]
                    val ref = Regex("\\br=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                    val type = Regex("\\bt=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    val raw = Regex("(?s)<v>(.*?)</v>").find(inner)?.groupValues?.get(1)
                    val value = when (type) {
                        "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> Regex("(?s)<t[^>]*>(.*?)</t>").findAll(inner).joinToString("") { unescape(it.groupValues[1]) }
                        "b" -> if (raw == "1") "ИСТИНА" else "ЛОЖЬ"
                        else -> unescape(raw.orEmpty())
                    }
                    if (ref != null) {
                        val col = columnIndex(ref)
                        while (cells.size < col) cells.add("")
                    }
                    cells.add(value)
                }
                rows.add(cells)
            }
            return rows.dropLastWhile { r -> r.all { it.isBlank() } }
        }
    }

    private fun columnIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    // ---------------------------------------------------------------- docx

    fun docxText(file: File): String {
        ZipFile(file).use { zip ->
            val xml = entry(zip, "word/document.xml") ?: return ""
            return unescape(
                xml.replace("</w:p>", "\n").replace("</w:tc>", "\t").replace(Regex("<w:tab\\s*/>"), "\t")
                    .replace(Regex("<w:br\\s*/>"), "\n").replace(Regex("<[^>]+>"), "")
            ).lines().joinToString("\n") { it.trimEnd() }.trim()
        }
    }

    // ---------------------------------------------------------------- csv

    /** Выбирает разделитель по первой строке: запятая, точка с запятой или табуляция. */
    fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull().orEmpty()
        return listOf(',', ';', '\t').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.contains(d) } ?: ','
    }

    fun parseCsv(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val src = text.removePrefix("\uFEFF")
        while (i < src.length) {
            val ch = src[i]
            when {
                quoted && ch == '"' && i + 1 < src.length && src[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < src.length && src[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    fun toCsv(rows: List<List<String>>, delimiter: Char = ','): String =
        rows.joinToString("\n") { r ->
            r.joinToString(delimiter.toString()) { cell ->
                if (cell.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
            }
        }

    /**
     * Склеивает таблицы: заголовок берётся из первой, одинаковые заголовки у остальных пропускаются.
     * dedupe убирает полностью одинаковые строки.
     */
    fun merge(tables: List<List<List<String>>>, dedupe: Boolean): List<List<String>> {
        val result = ArrayList<List<String>>()
        var header: List<String>? = null
        val seen = HashSet<List<String>>()
        for (table in tables) {
            if (table.isEmpty()) continue
            var start = 0
            if (header == null) {
                header = table[0]
                result.add(header)
                start = 1
            } else if (norm(table[0]) == norm(header)) {
                start = 1
            }
            for (r in table.drop(start)) {
                if (dedupe && !seen.add(r.map { it.trim() })) continue
                result.add(r)
            }
        }
        return result
    }

    private fun norm(r: List<String>) = r.map { it.trim().lowercase() }
}
