package com.example.assistant

import org.junit.Assert.assertEquals
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
}
