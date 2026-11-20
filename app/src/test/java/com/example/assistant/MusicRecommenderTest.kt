package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MusicRecommenderTest {

    private val now = 1_800_000_000_000L
    private var nextId = 1L

    private fun calm(artist: String, title: String, liked: Int = 0, stats: TrackStats? = null) = item(
        artist, title, TrackTags("classical", "calm", 1, moods = setOf("calm"), confidence = 80, source = TrackTags.SOURCE_LOCAL,
            moodScores = mapOf("calm" to 70, "romantic" to 20, "sad" to 10), bpm = 72, valence = 45, arousal = 15, tension = 10, danceability = 10, liked = liked),
        stats
    )

    private fun loud(artist: String, title: String, liked: Int = 0, stats: TrackStats? = null) = item(
        artist, title, TrackTags("electronic", "energetic", 5, moods = setOf("energetic", "dance"), confidence = 80, source = TrackTags.SOURCE_LOCAL,
            moodScores = mapOf("energetic" to 55, "dance" to 35, "happy" to 10), bpm = 128, valence = 70, arousal = 90, tension = 55, danceability = 80, liked = liked),
        stats
    )

    private fun item(artist: String, title: String, tags: TrackTags?, stats: TrackStats?): RecItem {
        val t = MusicTrack(nextId++, title, artist, durationMs = 200_000)
        return RecItem(t, tags?.copy(artist = artist, title = title), stats)
    }

    @Test fun similarTracksAreCloserThanDifferent() {
        val a = calm("A", "1"); val b = calm("B", "2"); val c = loud("C", "3")
        assertTrue(MusicRecommender.trackSimilarity(a, b) > MusicRecommender.trackSimilarity(a, c) + 0.3)
    }

    @Test fun tempoOctaveTolerance() {
        assertEquals(0, MusicRecommender.tempoDistance(70, 140))
        assertEquals(2, MusicRecommender.tempoDistance(64, 130))
        assertEquals(40, MusicRecommender.tempoDistance(100, 140))
        assertEquals(0, MusicRecommender.tempoDistance(0, 120))
    }

    @Test fun untaggedHasNoFeatures() {
        assertNull(MusicRecommender.features(null))
        assertNull(MusicRecommender.features(TrackTags("other", "calm", 3, confidence = 0)))
        assertNotNull(MusicRecommender.features(TrackTags("rock", "epic", 4, confidence = 60)))
    }

    @Test fun affinityRewardsLikesAndCompletesAndPunishesSkips() {
        val liked = calm("A", "1", liked = 1)
        val completed = calm("A", "2", stats = TrackStats(plays = 4, completes = 4, lastPlayedMs = now))
        val skipped = calm("A", "3", stats = TrackStats(skips = 4, lastPlayedMs = now))
        val disliked = calm("A", "4", liked = -1)
        assertTrue(MusicRecommender.affinity(liked, now) > 0)
        assertTrue(MusicRecommender.affinity(completed, now) > 0)
        assertTrue(MusicRecommender.affinity(skipped, now) < 0)
        assertTrue(MusicRecommender.affinity(disliked, now) < MusicRecommender.affinity(skipped, now))
    }

    @Test fun myWaveFollowsTaste() {
        val library = ArrayList<RecItem>()
        repeat(5) { library += calm("Calm$it", "liked$it", liked = 1) }
        repeat(30) { library += calm("CalmNew$it", "new$it") }
        repeat(30) { library += loud("Loud$it", "loud$it") }
        val picks = MusicRecommender.nextForWave(WaveSpec(WaveSpec.Kind.MY_WAVE), library, MusicRecommender.Session(), 10, now, Random(1))
        assertEquals(10, picks.size)
        val calmShare = picks.count { it.tags?.mood == "calm" }
        assertTrue("волна должна идти за вкусом, спокойных $calmShare из 10", calmShare >= 8)
    }

    @Test fun waveNeverRepeatsAndSkipsDisliked() {
        val library = ArrayList<RecItem>()
        repeat(20) { library += calm("A$it", "c$it") }
        library += calm("X", "bad", liked = -1)
        val session = MusicRecommender.Session()
        val seen = HashSet<String>()
        repeat(2) {
            val picks = MusicRecommender.nextForWave(WaveSpec(WaveSpec.Kind.MY_WAVE), library, session, 8, now, Random(it))
            for (p in picks) {
                assertTrue("повтор в волне: ${p.track.title}", seen.add(p.track.title))
                assertFalse(p.track.title == "bad")
                session.onPlayed(p)
            }
        }
    }

    @Test fun skipsInSessionSteerAway() {
        val library = ArrayList<RecItem>()
        repeat(20) { library += calm("C$it", "c$it") }
        repeat(20) { library += loud("L$it", "l$it") }
        val session = MusicRecommender.Session()
        // пользователь трижды пропустил громкое и дослушал спокойное
        repeat(3) { session.onFeedback(library[20 + it], completed = false) }
        session.onFeedback(library[0], completed = true)
        val picks = MusicRecommender.nextForWave(WaveSpec(WaveSpec.Kind.MY_WAVE), library, session, 10, now, Random(3))
        assertTrue(picks.count { it.tags?.mood == "calm" } >= 8)
    }

    @Test fun moodWavePicksThatMood() {
        val library = ArrayList<RecItem>()
        repeat(15) { library += calm("C$it", "c$it") }
        repeat(15) { library += loud("L$it", "l$it") }
        val picks = MusicRecommender.nextForWave(WaveSpec(WaveSpec.Kind.MOOD, mood = "energetic"), library, MusicRecommender.Session(), 10, now, Random(2))
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.all { it.tags?.mood == "energetic" })
    }

    @Test fun artistDiversity() {
        val library = ArrayList<RecItem>()
        repeat(10) { library += calm("Same", "s$it", liked = 1) }
        repeat(10) { library += calm("Other$it", "o$it") }
        val picks = MusicRecommender.nextForWave(WaveSpec(WaveSpec.Kind.MY_WAVE), library, MusicRecommender.Session(), 10, now, Random(5))
        assertTrue(picks.count { it.track.artist == "Same" } <= 2)
        for (i in 1 until picks.size) assertFalse(picks[i].track.artist == "Same" && picks[i - 1].track.artist == "Same")
    }

    @Test fun similarExcludesSeedAndDifferent() {
        val seed = calm("S", "seed")
        val library = listOf(seed, calm("A", "a"), calm("B", "b"), loud("C", "c"))
        val found = MusicRecommender.similar(seed, library, 5)
        assertFalse(found.any { it.track.title == "seed" })
        assertEquals(setOf("a", "b"), found.map { it.track.title }.toSet())
    }

    @Test fun presets() {
        val library = listOf(calm("A", "a"), loud("B", "b"))
        assertEquals(listOf("a"), MusicRecommender.preset("sleep", library, now).map { it.track.title })
        assertEquals(listOf("b"), MusicRecommender.preset("workout", library, now).map { it.track.title })
        assertEquals("morning", MusicRecommender.presetForHour(7))
        assertEquals("sleep", MusicRecommender.presetForHour(2))
    }

    @Test fun discoveriesAndForgotten() {
        val old = now - 40L * 86_400_000L
        val library = listOf(
            calm("A", "fav", liked = 1, stats = TrackStats(plays = 5, completes = 5, lastPlayedMs = old)),
            calm("B", "never"),
            loud("C", "neverLoud"),
            calm("D", "recent", stats = TrackStats(plays = 1, completes = 1, lastPlayedMs = now))
        )
        assertEquals("never", MusicRecommender.discoveries(library, 1, now).first().track.title)
        assertEquals(listOf("fav"), MusicRecommender.forgotten(library, 5, now).map { it.track.title })
    }

    @Test fun listenOutcome() {
        assertEquals(ListenStats.Outcome.SKIP, ListenStats.outcome(5_000, 200_000, false))
        assertEquals(ListenStats.Outcome.COMPLETE, ListenStats.outcome(170_000, 200_000, false))
        assertEquals(ListenStats.Outcome.COMPLETE, ListenStats.outcome(10_000, 200_000, true))
        assertEquals(ListenStats.Outcome.PARTIAL, ListenStats.outcome(90_000, 200_000, false))
    }

    @Test fun tasteDescription() {
        val library = listOf(calm("Moby", "a", liked = 1), calm("Moby", "b", liked = 1))
        val text = MusicRecommender.describeTaste(library, now)
        assertTrue(text, text.contains("спокойная"))
        assertTrue(text, text.contains("Moby"))
    }
}
