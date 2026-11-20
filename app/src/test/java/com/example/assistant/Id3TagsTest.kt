package com.example.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3TagsTest {

    private fun frame(id: String, enc: Int, text: ByteArray): ByteArray {
        val body = byteArrayOf(enc.toByte()) + text
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(byteArrayOf(0, 0, ((body.size shr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte()))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    private fun tag(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b }
        val n = body.size
        val head = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
        return head + body
    }

    @Test fun utf16WithBom() {
        val t = frame("TIT2", 1, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Тени от пальм".toByteArray(Charsets.UTF_16LE))
        val a = frame("TPE1", 1, byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Кино".toByteArray(Charsets.UTF_16BE))
        val r = Id3Tags.parse(tag(t, a))!!
        assertEquals("Тени от пальм", r.title)
        assertEquals("Кино", r.artist)
    }

    @Test fun cp1251InLatin1Frame() {
        val t = frame("TIT2", 0, "Песня".toByteArray(charset("windows-1251")))
        assertEquals("Песня", Id3Tags.parse(tag(t))!!.title)
    }

    @Test fun utf8Frame() {
        val t = frame("TALB", 3, "Альбом".toByteArray(Charsets.UTF_8))
        assertEquals("Альбом", Id3Tags.parse(tag(t))!!.album)
    }

    @Test fun noTag() = assertNull(Id3Tags.parse(ByteArray(100)))

    @Test fun detectsBroken() {
        assertTrue(Id3Tags.looksBroken("\"5=8 >B ?0;L<"))
        assertTrue(Id3Tags.looksBroken("Ïåñíÿ"))
        assertFalse(Id3Tags.looksBroken("Тени от пальм"))
        assertFalse(Id3Tags.looksBroken("Hello World (feat. Bob)"))
        assertFalse(Id3Tags.looksBroken("Beyoncé"))
    }

    @Test fun repairsLatin1() {
        val garbled = String("Песня".toByteArray(charset("windows-1251")), Charsets.ISO_8859_1)
        assertEquals("Песня", Id3Tags.repairLatin1(garbled))
    }
}
