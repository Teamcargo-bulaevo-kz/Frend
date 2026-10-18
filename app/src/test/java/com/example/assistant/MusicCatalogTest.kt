package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCatalogTest {

    // Заголовок и строки в формате файла songs_by_mood.xlsx пользователя
    private val userHeader = listOf(
        "Исполнитель", "Название", "Альбом (папка)", "Жанр", "Настроение", "Энергичность (1-5)",
        "Ритм / темп", "Краткое описание", "Уверенность", "Пометка для ИИ"
    )

    private fun key(a: String, t: String) = MusicTags.trackKey(a, t)

    @Test fun readsUsersOwnTable() {
        val rows = listOf(
            userHeader,
            listOf("Akon", "Smack That", "R&B Collaborations 2007", "R&B / клубный поп", "Весело", "4", "средний (90–110 BPM)", "Клубный ритм.", "Высокая", ""),
            listOf("DJ Gollum, Scarlet", "All The Things She Said", "Welcome To St. Tropez", "Клубная электроника", "Энергично", "5", "быстрый (120–140 BPM)", "Драйв.", "Низкая", ""),
            listOf("10AGE", "Милая (Sefon.Pro)", "песни", "", "Не определено", "", "", "", "", "ИИ: уточни у пользователя")
        )
        val parsed = MusicCatalog.parseRows(rows, emptyMap())
        assertEquals(2, parsed.updates.size)
        val akon = parsed.updates[key("Akon", "Smack That")]!!
        assertEquals(setOf("happy"), akon.moods)
        assertEquals("rnb", akon.genre)
        assertEquals("R&B / клубный поп", akon.genreLabel)
        assertEquals(4, akon.energy)
        assertEquals(MusicTags.tempos["medium"], akon.tempo)
        assertEquals(90, akon.confidence)
        assertEquals(TrackTags.SOURCE_IMPORT, akon.source)
        assertFalse(akon.needsReview)
        val gollum = parsed.updates[key("DJ Gollum, Scarlet", "All The Things She Said")]!!
        assertEquals("electronic", gollum.genre)
        assertTrue(gollum.needsReview) // «Низкая» уверенность попадает на проверку
        // «Альбом (папка)» не считается колонкой папок, а «Не определено» не создаёт разметку
        assertTrue(parsed.folders.isEmpty())
        assertNull(parsed.updates[key("10AGE", "Милая (Sefon.Pro)")])
    }

    @Test fun roundTripKeepsEverything() {
        val tags = TrackTags(
            genre = "pop", mood = "happy", energy = 4, moods = setOf("happy", "dance"), genreLabel = "Латино-поп",
            tempo = MusicTags.tempos["fast"].orEmpty(), description = "Солнечный хит", confidence = 92,
            source = TrackTags.SOURCE_WEB, liked = 1, artist = "Alvaro Soler", title = "Sofia", album = "Eterno Agosto"
        )
        val row = MusicCatalog.rowFor("Alvaro Soler", "Sofia", "Eterno Agosto", tags, listOf("Для бега", "Дорога"))
        val parsed = MusicCatalog.parseRows(listOf(MusicCatalog.header, row), emptyMap())
        val back = parsed.updates[key("Alvaro Soler", "Sofia")]!!
        assertEquals(setOf("happy", "dance"), back.moods)
        assertEquals("happy", back.mood)
        assertEquals("pop", back.genre)
        assertEquals("Латино-поп", back.genreLabel)
        assertEquals(4, back.energy)
        assertEquals(tags.tempo, back.tempo)
        assertEquals("Солнечный хит", back.description)
        assertEquals(1, back.liked)
        assertEquals(TrackTags.SOURCE_WEB, back.source)
        assertEquals(listOf("Для бега", "Дорога"), parsed.folders.single().second)
    }

    @Test fun unchangedRowsAreNotReportedAgain() {
        val tags = TrackTags("rock", "energetic", 5, confidence = 88, artist = "A", title = "B", album = "C", source = TrackTags.SOURCE_AI)
        val row = MusicCatalog.rowFor("A", "B", "C", tags, emptyList())
        val parsed = MusicCatalog.parseRows(listOf(MusicCatalog.header, row), mapOf(key("A", "B") to tags))
        assertTrue(parsed.updates.isEmpty())
    }

    @Test fun localManualEditBeatsTableUnlessMarkedManual() {
        val mine = TrackTags("rock", "sad", 2, edited = true, confidence = 100, source = TrackTags.SOURCE_USER, artist = "A", title = "B")
        val aiRow = MusicCatalog.rowFor("A", "B", "", TrackTags("pop", "happy", 5, confidence = 80, artist = "A", title = "B"), emptyList())
        val kept = MusicCatalog.parseRows(listOf(MusicCatalog.header, aiRow), mapOf(key("A", "B") to mine))
        assertTrue(kept.updates.isEmpty())
        val userRow = MusicCatalog.rowFor("A", "B", "", TrackTags("pop", "happy", 5, edited = true, confidence = 100, source = TrackTags.SOURCE_USER, artist = "A", title = "B"), emptyList())
        val replaced = MusicCatalog.parseRows(listOf(MusicCatalog.header, userRow), mapOf(key("A", "B") to mine))
        assertEquals("pop", replaced.updates[key("A", "B")]!!.genre)
        assertTrue(replaced.updates[key("A", "B")]!!.edited)
    }

    @Test fun likeSurvivesInUndefinedRow() {
        val placeholder = TrackTags("other", "calm", 3, confidence = 0, liked = 1, artist = "X", title = "Y")
        val row = MusicCatalog.rowFor("X", "Y", "", placeholder, emptyList())
        assertEquals("Не определено", row[4])
        assertEquals("да", row[10])
        val parsed = MusicCatalog.parseRows(listOf(MusicCatalog.header, row), emptyMap())
        assertEquals(1, parsed.updates[key("X", "Y")]!!.liked)
        assertFalse(MusicTags.isTagged(parsed.updates[key("X", "Y")]))
    }

    @Test fun needsReviewFlagIsWrittenAsNote() {
        val weak = TrackTags("pop", "calm", 3, confidence = 30, artist = "A", title = "B")
        val row = MusicCatalog.rowFor("A", "B", "", weak, emptyList())
        assertEquals("Низкая", row[8])
        assertTrue(row[9].startsWith("Проверить"))
        // авто-пометка не сохраняется как заметка пользователя
        val back = MusicCatalog.parseRows(listOf(MusicCatalog.header, row), emptyMap()).updates[key("A", "B")]!!
        assertEquals("", back.note)
    }
}
