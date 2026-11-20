package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTagsTest {

    @Test fun customPlaylistFilterCombinesMoodEnergyAndTempo() {
        val tags = TrackTags("pop", "happy", 4, moods = setOf("happy", "dance"),
            moodScores = mapOf("calm" to 12), bpm = 122)
        assertTrue(MusicTags.matchesPlaylistFilter(tags, "happy", 3, 110, 130))
        assertTrue(MusicTags.matchesPlaylistFilter(tags, "calm", 4, 120, 125))
        assertFalse(MusicTags.matchesPlaylistFilter(tags, "sad", 3, 110, 130))
        assertFalse(MusicTags.matchesPlaylistFilter(tags, "happy", 5, 110, 130))
        assertFalse(MusicTags.matchesPlaylistFilter(tags, "happy", 3, 130, 160))
    }

    @Test fun customPlaylistFilterHandlesUnknownTempoAndInvalidRanges() {
        val tags = TrackTags("other", "calm", 2, bpm = 0)
        assertFalse(MusicTags.matchesPlaylistFilter(tags, null, 2, 100, 140))
        assertTrue(MusicTags.matchesPlaylistFilter(tags, null, 2))
        assertTrue(MusicTags.matchesPlaylistFilter(tags.copy(bpm = 120), null, 2, 100, 140))
        assertFalse(MusicTags.matchesPlaylistFilter(tags, null, 2, 150, 100))
        assertFalse(MusicTags.matchesPlaylistFilter(tags, null, 0, 0, 0))
        assertFalse(MusicTags.matchesPlaylistFilter(tags, null, 2, 0, 241))
    }

    @Test fun moodFromSpeech() {
        assertEquals("calm", MusicTags.moodFromWords("спокойную музыку"))
        assertEquals("energetic", MusicTags.moodFromWords("что нибудь энергичное"))
        assertEquals("happy", MusicTags.moodFromWords("веселые песни"))
        assertEquals("focus", MusicTags.moodFromWords("музыку для работы"))
        assertEquals("sad", MusicTags.moodFromWords("грустное"))
        assertNull(MusicTags.moodFromWords("баста"))
    }

    @Test fun genreFromSpeech() {
        assertEquals("rock", MusicTags.genreFromWords("рок"))
        assertEquals("rock", MusicTags.genreFromWords("русский рок"))
        assertEquals("hiphop", MusicTags.genreFromWords("рэп"))
        assertEquals("jazz", MusicTags.genreFromWords("джазовую музыку"))
        assertEquals("classical", MusicTags.genreFromWords("классическую"))
        assertNull(MusicTags.genreFromWords("металлику"))
        assertNull(MusicTags.genreFromWords("роксану"))
        assertNull(MusicTags.genreFromWords("попросил"))
    }

    @Test fun parsesModelResponse() {
        val text = "Вот:\n[{\"i\":1,\"g\":\"pop\",\"m\":\"happy\",\"e\":4},{\"i\":2,\"g\":\"xxx\",\"m\":\"calm\",\"e\":9},{\"i\":3,\"g\":\"rock\",\"m\":\"zzz\",\"e\":3}]"
        val r = MusicTags.parseResponse(text)
        assertEquals(setOf(1, 2), r.keys)
        assertEquals(TrackTags("pop", "happy", 4), r[1])
        assertEquals(TrackTags("other", "calm", 5), r[2])
        assertTrue(MusicTags.parseResponse("нет json").isEmpty())
    }

    @Test fun multipleMoodsFromTableText() {
        assertEquals(setOf("happy", "romantic"), MusicTags.moodsFromText("Весело, Романтично"))
        assertEquals(setOf("dark"), MusicTags.moodsFromText("Агрессивно/Мрачно"))
        assertEquals(setOf("calm"), MusicTags.moodsFromText("Спокойно"))
        assertTrue(MusicTags.moodsFromText("Не определено").isEmpty())
    }

    @Test fun genreLabelsFromTable() {
        assertEquals("rnb", MusicTags.genreKeyFromLabel("R&B / клубный поп"))
        assertEquals("pop", MusicTags.genreKeyFromLabel("Латино-поп"))
        assertEquals("electronic", MusicTags.genreKeyFromLabel("Клубная электроника"))
        assertEquals("hiphop", MusicTags.genreKeyFromLabel("Хип-хоп"))
        assertEquals("other", MusicTags.genreKeyFromLabel("Что-то странное"))
        assertEquals("electronic", MusicTags.genreKeyFromLabel("Фонк / бразильский фанк"))
        assertEquals("electronic", MusicTags.genreKeyFromLabel("Slowed + reverb ремикс"))
        assertEquals("electronic", MusicTags.genreKeyFromLabel("Электро / TikTok-ремикс"))
        assertEquals("pop", MusicTags.genreKeyFromLabel("Русская лирика / ремикс"))
    }

    @Test fun cleansTitles() {
        assertEquals("Милая", MusicTags.cleanTitle("Милая (Sefon.Pro)"))
        assertEquals("Flight Electro Souls", MusicTags.cleanTitle("Flight Electro Souls [muzmo.ru]"))
        assertEquals("Song Name", MusicTags.cleanTitle("Song_Name.mp3"))
        assertEquals("Sexy Bitch", MusicTags.cleanTitle("Sexy Bitch"))
        assertEquals("Лето (remix)", MusicTags.cleanTitle("Лето (remix)"))
    }

    @Test fun richModelResponse() {
        val text = "[{\"i\":1,\"g\":\"pop\",\"gl\":\"Латино-поп\",\"m\":[\"happy\",\"dance\",\"calm\",\"sad\"],\"e\":4,\"t\":\"medium\",\"d\":\"Солнечный хит\",\"c\":85}]"
        val t = MusicTags.parseResponse(text)[1]!!
        assertEquals("happy", t.mood)
        assertEquals(setOf("happy", "dance", "calm"), t.moods)
        assertEquals("Латино-поп", t.genreLabel)
        assertEquals(MusicTags.tempos["medium"], t.tempo)
        assertEquals(85, t.confidence)
        assertFalse(t.needsReview)
        assertTrue(t.copy(confidence = 30).needsReview)
        assertFalse(t.copy(confidence = 30, edited = true).needsReview)
    }

    @Test fun retryDelayParsing() {
        assertEquals(6325L, MusicTagger.retryDelayMs("Rate limit. Please try again in 6.325s. Need more"))
        assertEquals(72_500L, MusicTagger.retryDelayMs("try again in 1m12.5s"))
        assertEquals(250L, MusicTagger.retryDelayMs("try again in 250ms"))
        assertEquals(3_720_000L, MusicTagger.retryDelayMs("try again in 1h2m"))
        assertNull(MusicTagger.retryDelayMs("no hint here"))
    }
}
