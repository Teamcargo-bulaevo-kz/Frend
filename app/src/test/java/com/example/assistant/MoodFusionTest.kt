package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodFusionTest {

    private val audio = TrackTags(
        "other", "calm", 2, moods = setOf("calm"), confidence = 70, source = TrackTags.SOURCE_LOCAL,
        moodScores = mapOf("calm" to 60, "sad" to 25, "focus" to 15), bpm = 76, liked = 1, audioTried = true
    )

    @Test fun aiProfileSumsTo100() {
        assertEquals(100, MoodFusion.aiProfile(listOf("romantic")).values.sum())
        assertEquals(100, MoodFusion.aiProfile(listOf("romantic", "sad")).values.sum())
        assertEquals(emptyMap<String, Int>(), MoodFusion.aiProfile(listOf("nonsense")))
    }

    @Test fun confidentAiShiftsMoodButAudioKeepsTempo() {
        val ai = TrackTags("pop", "romantic", 3, moods = setOf("romantic"), genreLabel = "Лирик-поп", confidence = 90, description = "о любви")
        val fused = MoodFusion.fuse(audio, ai)
        assertEquals("pop", fused.genre)
        assertEquals("Лирик-поп", fused.genreLabel)
        assertEquals(76, fused.bpm)
        assertEquals(1, fused.liked)
        assertTrue(fused.webTried)
        assertTrue((fused.moodScores["romantic"] ?: 0) >= 30)
        assertTrue("romantic" in fused.moods)
        assertEquals(100, fused.moodScores.values.sum().let { if (it in 98..102) 100 else it })
    }

    @Test fun unsureAiBarelyChangesMoodAndGenre() {
        val ai = TrackTags("rock", "energetic", 5, moods = setOf("energetic"), confidence = 20)
        val fused = MoodFusion.fuse(audio.copy(genre = "jazz"), ai)
        assertEquals("jazz", fused.genre)
        assertEquals("calm", fused.mood)
        assertTrue(fused.energy <= 3)
    }

    @Test fun withoutAudioTakesAi() {
        val ai = TrackTags("rock", "epic", 4, moods = setOf("epic"), confidence = 60)
        val fused = MoodFusion.fuse(TrackTags("other", "calm", 3, confidence = 0, liked = 1), ai)
        assertEquals("rock", fused.genre)
        assertEquals("epic", fused.mood)
        assertEquals(1, fused.liked)
    }
}
