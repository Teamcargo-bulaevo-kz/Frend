package com.example.assistant

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

    /** Лист для writeXlsx: название, строки (первая — заголовок) и номера колонок, где значения числа. */
    class Sheet(val name: String, val rows: List<List<String>>, val numericColumns: Set<Int> = emptySet(), val widths: List<Int> = emptyList())

    private fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 32 && ch != '\n' && ch != '\t' -> {} // управляющие символы в xml запрещены
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun columnLetters(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /** Записывает книгу Excel без внешних библиотек: заголовок жирным и закреплён, строки как текст, числа как числа. */
    fun writeXlsx(file: File, sheets: List<Sheet>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val xmlHead = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            put("[Content_Types].xml", xmlHead +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                sheets.indices.joinToString("") {
                    "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                } + "</Types>")
            put("_rels/.rels", xmlHead +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", xmlHead +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                sheets.mapIndexed { i, sh ->
                    "<sheet name=\"${xmlEscape(sh.name.take(31))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>"
                }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", xmlHead +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                sheets.indices.joinToString("") {
                    "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>"
                } +
                "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            put("xl/styles.xml", xmlHead +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>")
            sheets.forEachIndexed { i, sh ->
                val sb = StringBuilder(xmlHead)
                sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                if (sh.widths.isNotEmpty()) {
                    sb.append("<cols>")
                    sh.widths.forEachIndexed { c, w -> sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>") }
                    sb.append("</cols>")
                }
                sb.append("<sheetData>")
                sh.rows.forEachIndexed { r, row ->
                    sb.append("<row r=\"${r + 1}\">")
                    row.forEachIndexed { c, value ->
                        if (value.isEmpty()) return@forEachIndexed
                        val ref = columnLetters(c) + (r + 1)
                        val number = r > 0 && c in sh.numericColumns && value.toDoubleOrNull() != null
                        if (number) {
                            sb.append("<c r=\"$ref\"><v>$value</v></c>")
                        } else {
                            val style = if (r == 0) " s=\"1\"" else ""
                            sb.append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${xmlEscape(value)}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
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
