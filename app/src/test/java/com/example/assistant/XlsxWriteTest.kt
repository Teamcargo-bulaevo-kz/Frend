package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class XlsxWriteTest {

    @Test fun roundTripKeepsCyrillicAndSpecialCharacters() {
        val dir = File(System.getProperty("java.io.tmpdir"), "xlsx-test-" + System.nanoTime()).also { it.mkdirs() }
        val file = File(dir, "t.xlsx")
        val rows = listOf(
            listOf("Исполнитель", "Название", "Энергичность"),
            listOf("Баста & Co", "Песня <1> \"тест\"", "4"),
            listOf("Akon", "", "5"),
            listOf("", "Только название", "")
        )
        FileConvert.writeXlsx(file, listOf(FileConvert.Sheet("Все песни", rows, setOf(2)), FileConvert.Sheet("Сводка", listOf(listOf("a", "b")))))
        val read = FileConvert.xlsxRows(file)
        assertEquals(4, read.size)
        assertEquals(listOf("Исполнитель", "Название", "Энергичность"), read[0])
        assertEquals("Баста & Co", read[1][0])
        assertEquals("Песня <1> \"тест\"", read[1][1])
        assertEquals("4", read[1][2])
        assertEquals("Akon", read[2][0])
        assertEquals("5", read[2][2])
        assertEquals("Только название", read[3][1])
        file.delete(); dir.delete()
    }
}
