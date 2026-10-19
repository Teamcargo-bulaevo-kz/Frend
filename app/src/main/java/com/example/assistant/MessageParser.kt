package com.example.assistant

/** Часть ответа: обычный текст или блок кода (между тройными кавычками ```). Чистый Kotlin, есть тесты. */
sealed class Segment {
    data class Text(val text: String) : Segment()
    data class Code(val lang: String, val code: String) : Segment()
}

object MessageParser {

    private val fence = Regex("```([\\p{L}\\p{N}_+#.-]*)[ \\t]*\\r?\\n?(.*?)(```|$)", RegexOption.DOT_MATCHES_ALL)

    fun split(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        var last = 0
        for (m in fence.findAll(text)) {
            val before = text.substring(last, m.range.first).trim('\n', '\r')
            if (before.isNotBlank()) result.add(Segment.Text(before))
            val code = m.groupValues[2].trimEnd('\n', '\r', ' ')
            if (code.isNotEmpty()) result.add(Segment.Code(m.groupValues[1].lowercase(), code))
            last = m.range.last + 1
        }
        val tail = if (last <= text.length) text.substring(last).trim('\n', '\r') else ""
        if (tail.isNotBlank()) result.add(Segment.Text(tail))
        if (result.isEmpty()) result.add(Segment.Text(text))
        return result
    }

    /** Границы выделений в тексте: **жирный** и `код`. Возвращает текст без значков и списки диапазонов. */
    data class Styled(val text: String, val bold: List<IntRange>, val mono: List<IntRange>)

    private val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`")

    fun styled(raw: String): Styled {
        val sb = StringBuilder()
        val bold = ArrayList<IntRange>()
        val mono = ArrayList<IntRange>()
        var last = 0
        for (m in inline.findAll(raw)) {
            sb.append(raw, last, m.range.first)
            val start = sb.length
            if (m.groups[1] != null) {
                sb.append(m.groupValues[1]); bold.add(start until sb.length)
            } else {
                sb.append(m.groupValues[2]); mono.add(start until sb.length)
            }
            last = m.range.last + 1
        }
        sb.append(raw, last, raw.length)
        return Styled(sb.toString(), bold, mono)
    }
}
