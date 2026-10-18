package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTagsTest {

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

    @Test fun localCommandsForMusicTags() {
        val calm = LocalCommands.parse("включи спокойную музыку")!!
        assertEquals("play_music", calm.name)
        assertEquals("calm", calm.args["mood"])
        assertEquals("rock", LocalCommands.parse("включи рок")!!.args["genre"])
        assertEquals("analyze", LocalCommands.parse("проанализируй мою музыку")!!.args["action"])
        assertEquals("export", LocalCommands.parse("выгрузи список песен")!!.args["action"])
        // название песни не должно превращаться в настроение
        assertNull(LocalCommands.parse("включи песню спокойной ночи")?.args?.get("mood"))
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

    @Test fun likeAndFolderCommands() {
        assertEquals("like", LocalCommands.parse("поставь лайк")!!.args["action"])
        assertEquals("dislike", LocalCommands.parse("не нравится эта песня")!!.args["action"])
        val tag = LocalCommands.parse("эта песня грустная")!!
        assertEquals("tag", tag.args["action"])
        assertEquals(listOf("sad"), tag.args["moods"])
        val liked = LocalCommands.parse("включи любимое")!!
        assertEquals("play_music", liked.name)
        assertEquals(true, liked.args["liked"])
        assertEquals("calm", LocalCommands.parse("включи любимые спокойные песни")!!.args["mood"])
    }
}
