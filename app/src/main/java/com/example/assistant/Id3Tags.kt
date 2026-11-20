package com.example.assistant

import java.nio.charset.Charset

/** Название, исполнитель и альбом, прочитанные прямо из ID3-тега файла. */
data class Id3Text(val title: String = "", val artist: String = "", val album: String = "")

/**
 * Свой разбор ID3v2 (v2.2/2.3/2.4). Системный сканер Android иногда теряет старший байт
 * у кириллицы в UTF-16 и показывает вместо слов «5=8 >B ?0;L<». Здесь тег читается сам.
 * Чистый Kotlin, без Android: проверяется обычными тестами.
 */
object Id3Tags {

    private val cp1251: Charset = Charset.forName("windows-1251")
    private val latin1: Charset = Charsets.ISO_8859_1

    /** Читает ID3v2 из начала файла. Нужно ~64 КБ. Возвращает null, если тега нет. */
    fun parse(data: ByteArray): Id3Text? {
        if (data.size < 10 || data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return null
        val version = data[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val flags = data[5].toInt() and 0xFF
        val tagSize = synchsafe(data, 6)
        var pos = 10
        val end = minOf(data.size, 10 + tagSize)
        if (version >= 3 && flags and 0x40 != 0 && pos + 4 <= end) { // расширенный заголовок
            val ext = if (version == 4) synchsafe(data, pos) else int32(data, pos) + 4
            pos += ext.coerceAtLeast(0)
        }
        val idLen = if (version == 2) 3 else 4
        val hdrLen = if (version == 2) 6 else 10
        var title = ""; var artist = ""; var album = ""
        while (pos + hdrLen <= end) {
            if (data[pos].toInt() == 0) break
            val id = String(data, pos, idLen, latin1)
            val size = when (version) {
                2 -> ((data[pos + 3].toInt() and 0xFF) shl 16) or ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
                3 -> int32(data, pos + 4)
                else -> synchsafe(data, pos + 4)
            }
            val bodyStart = pos + hdrLen
            if (size <= 0 || bodyStart + size > end) break
            when (id) {
                "TIT2", "TT2" -> title = decodeFrame(data, bodyStart, size)
                "TPE1", "TP1" -> artist = decodeFrame(data, bodyStart, size)
                "TALB", "TAL" -> album = decodeFrame(data, bodyStart, size)
            }
            pos = bodyStart + size
        }
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) return null
        return Id3Text(title, artist, album)
    }

    private fun decodeFrame(d: ByteArray, start: Int, size: Int): String {
        if (size < 1) return ""
        val enc = d[start].toInt() and 0xFF
        val off = start + 1
        val len = size - 1
        val raw = when (enc) {
            0 -> {
                val s = String(d, off, len, latin1)
                // Часто в «Latin-1» лежит cp1251
                if (s.any { it.code in 0x80..0xFF }) String(d, off, len, cp1251) else s
            }
            1 -> decodeUtf16WithBom(d, off, len)
            2 -> String(d, off, len, Charsets.UTF_16BE)
            3 -> String(d, off, len, Charsets.UTF_8)
            else -> String(d, off, len, latin1)
        }
        // несколько значений разделены нулём: берём первое
        return raw.substringBefore('\u0000').trim()
    }

    private fun decodeUtf16WithBom(d: ByteArray, off: Int, len: Int): String {
        if (len < 2) return ""
        val b0 = d[off].toInt() and 0xFF
        val b1 = d[off + 1].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xFE -> String(d, off + 2, len - 2, Charsets.UTF_16LE)
            b0 == 0xFE && b1 == 0xFF -> String(d, off + 2, len - 2, Charsets.UTF_16BE)
            else -> String(d, off, len, Charsets.UTF_16LE)
        }
    }

    private fun synchsafe(d: ByteArray, p: Int): Int =
        ((d[p].toInt() and 0x7F) shl 21) or ((d[p + 1].toInt() and 0x7F) shl 14) or
            ((d[p + 2].toInt() and 0x7F) shl 7) or (d[p + 3].toInt() and 0x7F)

    private fun int32(d: ByteArray, p: Int): Int =
        ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or
            ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)

    /**
     * Строка выглядит испорченной: символы-мусор «внутри слов» (потерянный старший байт кириллицы),
     * управляющие символы, U+FFFD, или кириллица, прочитанная как Latin-1 («Ïåñíÿ»).
     */
    fun looksBroken(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.any { it == '�' || (it.code < 32) }) return true
        if (s.any { it.code in 0x80..0x9F }) return true
        val hasCyr = s.any { it in 'Ѐ'..'ӿ' }
        // Latin-1 с ударениями подряд: «Ïåñíÿ»
        var run = 0
        for (c in s) {
            if (c.code in 0xC0..0xFF) { run++; if (run >= 3 && !hasCyr) return true } else run = 0
        }
        // «5=8 >B ?0;L<»: знаки из диапазона ;<=>?@[\]^ между буквами/цифрами
        val junk = "<=>?@[\\]^;"
        var between = 0
        for (i in 1 until s.length - 1) {
            if (s[i] in junk && s[i - 1].isLetterOrDigit() && s[i + 1].isLetterOrDigit()) between++
        }
        return between >= 2
    }

    /** Чинит cp1251, прочитанную как Latin-1. Если не вышло: возвращает исходную строку. */
    fun repairLatin1(s: String): String {
        if (s.any { it.code > 0xFF }) return s
        val fixed = String(s.toByteArray(latin1), cp1251)
        return if (fixed.any { it in 'Ѐ'..'ӿ' } && !fixed.any { it == '�' }) fixed else s
    }
}
