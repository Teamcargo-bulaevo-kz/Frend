package com.example.assistant

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt

class LocalMoodAnalyzerTest {
    @Test fun moodScoresAlwaysTotalOneHundred() {
        val cases = listOf(
            AudioFeatures(0.02, 900.0, 0.55, 0.03, 0.08, 0.01, 0.05, 72, 1.2, 22050),
            AudioFeatures(0.24, 4200.0, 0.18, 0.22, 0.38, 0.7, 0.65, 128, 3.4, 22050),
            AudioFeatures(0.08, 1800.0, 0.35, 0.08, 0.16, 0.12, 0.32, 105, 1.8, 22050)
        )
        for (features in cases) {
            val result = LocalMoodAnalyzer.classify(features)
            assertEquals(100, result.scores.values.sum())
            assertTrue(result.scores.values.all { it in 0..100 })
            assertTrue(result.energy in 1..5)
            assertTrue(result.confidence in 40..88)
        }
    }

    @Test fun extractsFeaturesFromPcmSignal() {
        val rate = 22050
        val samples = FloatArray(rate * 2) { i -> (0.2 * sin(2.0 * PI * 440.0 * i / rate)).toFloat() }
        val features = LocalMoodAnalyzer.extractFeatures(samples, rate)
        assertTrue(features.rms > 0.1)
        assertTrue(features.centroid > 100.0)
        assertTrue(features.count > 0)
        assertEquals(2.0, features.durationSeconds, 0.1)
    }

    @Test fun splitsLongTrackIntoOverlappingMoodSegments() {
        val rate = 11025
        val samples = FloatArray(rate * 45) { i ->
            val amplitude = if (i < rate * 20) 0.04 else 0.30
            (amplitude * sin(2.0 * PI * 220.0 * i / rate)).toFloat()
        }
        val segments = LocalMoodAnalyzer.analyzeSegments(samples, rate)
        assertEquals(4, segments.size)
        assertEquals(0, segments.first().startSeconds)
        assertEquals(45, segments.last().endSeconds)
        assertTrue(segments.all { it.endSeconds > it.startSeconds })
        assertTrue(segments.all { it.scores.values.sum() == 100 })
        assertTrue(segments.all { it.valence in 0..100 && it.arousal in 0..100 && it.tension in 0..100 })
    }
    @Test fun spectralOnsetsProduceTempoEstimateForRegularPulses() {
        val rate = 22050
        val samples = FloatArray(rate * 8) { i ->
            val time = i.toDouble() / rate
            val phase = time % 0.5
            val pulse = if (phase < 0.075) 0.32 else 0.008
            (pulse * sin(2.0 * PI * 880.0 * time)).toFloat()
        }
        val features = LocalMoodAnalyzer.extractFeatures(samples, rate)
        assertTrue("Regular spectral attacks should produce a BPM estimate", features.bpm > 0)
    }

    @Test fun tempoEstimateAvoidsOctaveErrorsForRegularPulse() {
        val rate = 22050
        for (period in listOf(0.5, 0.6666667, 0.4)) {
            val samples = FloatArray(rate * 12) { i ->
                val time = i.toDouble() / rate
                val phase = time % period
                val pulse = if (phase < 0.06) 0.35 else 0.006
                (pulse * sin(2.0 * PI * 880.0 * time)).toFloat()
            }
            val bpm = LocalMoodAnalyzer.extractFeatures(samples, rate).bpm
            val expected = (60.0 / period).roundToInt()
            assertTrue("Expected tempo near $expected BPM, got $bpm", bpm in (expected - 8)..(expected + 8))
        }
    }

    @Test fun tempoEstimatorDoesNotBiasAmbiguousPeaksToward120Bpm() {
        val rate = 22050
        val periods = listOf(0.75, 0.5, 0.4)
        val expectedBpms = listOf(80, 120, 150)
        for ((period, expected) in periods.zip(expectedBpms)) {
            val samples = FloatArray(rate * 12) { i ->
                val time = i.toDouble() / rate
                val phase = time % period
                val pulse = if (phase < 0.055) 0.32 else 0.004
                (pulse * sin(2.0 * PI * 880.0 * time)).toFloat()
            }
            val actual = LocalMoodAnalyzer.extractFeatures(samples, rate).bpm
            assertTrue("Expected $expected BPM, got $actual", actual in (expected - 8)..(expected + 8))
        }
    }

    @Test fun silentAudioDoesNotInventTempo() {
        val features = LocalMoodAnalyzer.extractFeatures(FloatArray(22050 * 4), 22050)
        assertEquals(0, features.bpm)
    }

    @Test fun detectsTrackLevelDynamicsAcrossLongerAudio() {
        val rate = 22050
        val samples = FloatArray(rate * 8) { i ->
            val amplitude = if (i < rate * 4) 0.04 else 0.35
            (amplitude * sin(2.0 * PI * 440.0 * i / rate)).toFloat()
        }
        val features = LocalMoodAnalyzer.extractFeatures(samples, rate)
        assertEquals(8.0, features.durationSeconds, 0.1)
        assertTrue("Expected a change in dynamics between sections", features.variation > 0.2)
        val result = LocalMoodAnalyzer.classify(features)
        assertTrue(result.valence in 0..100)
        assertTrue(result.arousal in 0..100)
        assertTrue(result.tension in 0..100)
        assertTrue(result.danceability in 0..100)
    }
    @Test fun detectsStrongEnergyTransitionsBetweenAdjacentSegments() {
        val segments = listOf(
            MoodSegment(0, 20, "calm", 50, 20, 10, mapOf("calm" to 100)),
            MoodSegment(10, 30, "energetic", 50, 45, 20, mapOf("energetic" to 100)),
            MoodSegment(20, 40, "energetic", 50, 75, 30, mapOf("energetic" to 100)),
            MoodSegment(30, 45, "calm", 50, 50, 15, mapOf("calm" to 100))
        )
        val transitions = LocalMoodAnalyzer.transitionLabels(segments)
        assertEquals(2, transitions.size)
        assertTrue(transitions[0].contains("нарастание энергии"))
        assertTrue(transitions[1].contains("спад энергии"))
    }

    @Test fun strongBeatAccentExceedsWeakOffbeatPulse() {
        val pulses = List(240) { index ->
            when (index % 5) {
                0 -> 1.0
                2 -> 0.2
                else -> 0.0
            }
        }
        val accent = LocalMoodAnalyzer.estimateBeatAccent(pulses, 10.0, 120)
        assertTrue("Expected strong beat contrast, got $accent", accent > 0.35)
        assertEquals(0.0, LocalMoodAnalyzer.estimateBeatAccent(pulses, 10.0, 0), 0.0)
    }

    @Test fun findsSimilarNonOverlappingMusicalFragments() {
        val repeated = mapOf("dance" to 70, "energetic" to 30)
        val different = mapOf("calm" to 80, "focus" to 20)
        val segments = listOf(
            MoodSegment(0, 20, "dance", 70, 75, 20, repeated),
            MoodSegment(10, 30, "calm", 35, 20, 15, different),
            MoodSegment(20, 40, "dance", 70, 75, 20, repeated),
            MoodSegment(30, 50, "energetic", 55, 60, 35, mapOf("energetic" to 100))
        )
        val matches = LocalMoodAnalyzer.findRepeatedFragments(segments)
        assertEquals(1, matches.size)
        assertEquals(0, matches[0].firstStartSeconds)
        assertEquals(20, matches[0].secondStartSeconds)
        assertTrue(matches[0].similarity >= 90)
    }

    @Test fun overlappingSegmentsAreWeightedByUniqueTimelineCoverage() {
        val segments = listOf(
            MoodSegment(0, 20, "calm", 50, 20, 10, mapOf("calm" to 100)),
            MoodSegment(10, 30, "energetic", 50, 70, 30, mapOf("energetic" to 100)),
            MoodSegment(20, 40, "energetic", 50, 70, 30, mapOf("energetic" to 100)),
            MoodSegment(30, 45, "calm", 50, 20, 10, mapOf("calm" to 100))
        )
        val weights = LocalMoodAnalyzer.segmentWeights(segments, 45)
        assertEquals(4, weights.size)
        assertEquals(45.0, weights.sum(), 0.0001)
        assertTrue(weights.all { it > 0.0 })
        // Перекрытые секунды не должны учитываться повторно.
        assertEquals(listOf(15.0, 10.0, 8.75, 11.25), weights)
    }

    private fun progression(roots: List<Int>, third: Int, rate: Int): FloatArray {
        val perChord = rate * 5
        val out = FloatArray(perChord * roots.size)
        for ((c, root) in roots.withIndex()) {
            val freqs = listOf(root - 12, root, root + third, root + 7).map { 440.0 * Math.pow(2.0, (it - 69) / 12.0) }
            for (i in 0 until perChord) {
                var v = 0.0
                for (fq in freqs) v += sin(2.0 * PI * fq * i / rate)
                out[c * perChord + i] = (v * 0.1).toFloat()
            }
        }
        return out
    }

    @Test fun detectsMajorAndMinorMode() {
        val rate = 11025
        val major = LocalMoodAnalyzer.estimateMode(progression(listOf(60, 65, 67, 60), 4, rate), rate)
        val minor = LocalMoodAnalyzer.estimateMode(progression(listOf(57, 62, 64, 57), 3, rate), rate)
        assertTrue("major=${major.first}", major.first > 0.7 && major.second > 0.5)
        assertTrue("minor=${minor.first}", minor.first < 0.4 && minor.second > 0.5)
    }

    @Test fun noiseHasNoClearTonality() {
        val rate = 11025
        val random = java.util.Random(1)
        val noise = FloatArray(rate * 20) { (random.nextGaussian() * 0.1).toFloat() }
        assertTrue(LocalMoodAnalyzer.estimateMode(noise, rate).second < 0.3)
    }

    @Test fun majorModeFavoursHappyAndMinorFavoursSad() {
        val base = AudioFeatures(0.06, 2200.0, 0.3, 0.08, 0.12, 0.1, 0.3, 100, 2.0, 22050, 60.0)
        val major = LocalMoodAnalyzer.classify(base.copy(modeMajor = 0.95, tonalClarity = 1.0))
        val minor = LocalMoodAnalyzer.classify(base.copy(modeMajor = 0.05, tonalClarity = 1.0))
        assertTrue(major.scores.getValue("happy") > minor.scores.getValue("happy"))
        assertTrue(minor.scores.getValue("sad") > major.scores.getValue("sad"))
        assertTrue(major.valence > minor.valence)
        assertEquals(100, major.scores.values.sum())
        assertEquals(100, minor.scores.values.sum())
    }
    @Test fun tempoIsRefinedToWithinThreeBpm() {
        val rate = 22050
        for ((period, expected) in listOf(0.5 to 120, 0.4 to 150)) {
            val samples = FloatArray(rate * 20) { i ->
                val time = i.toDouble() / rate
                val pulse = if (time % period < 0.055) 0.32 else 0.004
                (pulse * sin(2.0 * PI * 880.0 * time)).toFloat()
            }
            val actual = LocalMoodAnalyzer.extractFeatures(samples, rate).bpm
            assertTrue("Expected $expected +-3 BPM, got $actual", actual in (expected - 3)..(expected + 3))
        }
    }
    @Test fun softSteadyBeatStaysCalm() {
        // Спокойная песня с ровным мягким битом, записанная громко: бит регулярный, но удары мягкие.
        val calmBeat = AudioFeatures(0.10, 1800.0, 0.35, 0.03, 0.08, 0.15, 0.7, 95, 2.5, 22050, 60.0, 0.5, 0.4, attack = 0.06)
        val result = LocalMoodAnalyzer.classify(calmBeat)
        val s = result.scores
        assertTrue("calm=${s["calm"]} energetic=${s["energetic"]}", s.getValue("calm") > s.getValue("energetic"))
        assertTrue("calm=${s["calm"]} happy=${s["happy"]}", s.getValue("calm") > s.getValue("happy"))
        assertTrue("arousal=${result.arousal}", result.arousal < 50)
    }

    @Test fun hardDrivingBeatStaysEnergetic() {
        val hard = AudioFeatures(0.18, 4200.0, 0.25, 0.15, 0.30, 0.25, 0.6, 150, 3.0, 22050, 60.0, 0.8, 0.5, attack = 0.24)
        val s = LocalMoodAnalyzer.classify(hard).scores
        assertTrue("energetic=${s["energetic"]} calm=${s["calm"]}", s.getValue("energetic") > s.getValue("calm"))
        assertTrue(s.getValue("energetic") >= 15)
    }

    @Test fun attackIsLowForSoftBeatAndHighForHardBeat() {
        val rate = 11025
        val random = java.util.Random(2)
        fun signal(bpm: Double, amp: Double, decay: Double, noise: Double) = FloatArray(rate * 15) { i ->
            val t = i.toDouble() / rate
            val ph = t % (60.0 / bpm)
            val pad = 0.1 * (sin(2.0 * PI * 262.0 * t) + sin(2.0 * PI * 330.0 * t) + sin(2.0 * PI * 392.0 * t))
            (pad + amp * exp(-ph * decay) * sin(2.0 * PI * 60.0 * ph) + noise * random.nextGaussian() * (if (ph < 0.02) 1.0 else 0.0)).toFloat()
        }
        val soft = LocalMoodAnalyzer.extractFeatures(signal(90.0, 0.15, 12.0, 0.0), rate).attack
        val hard = LocalMoodAnalyzer.extractFeatures(signal(140.0, 0.6, 25.0, 0.1), rate).attack
        assertTrue("soft=$soft hard=$hard", soft >= 0.0 && hard > soft * 2)
    }
    @Test fun calmMajorSongIsNotHappy() {
        // Мажор и не тёмный тембр есть и у спокойной музыки: мало атак в секунду, мягко, без верхов.
        val calmMajor = AudioFeatures(0.06, 2600.0, 0.30, 0.05, 0.10, 0.10, 0.6, 100, 2.5, 22050, 60.0, 0.5, 0.4, 0.95, 1.0, 0.05, 1.5, 0.01)
        val s = LocalMoodAnalyzer.classify(calmMajor).scores
        assertTrue("calm=${s["calm"]} happy=${s["happy"]}", s.getValue("calm") > s.getValue("happy") * 1.5)
    }

    @Test fun livelyMajorSongIsHappy() {
        val lively = AudioFeatures(0.13, 3300.0, 0.20, 0.10, 0.15, 0.15, 0.6, 125, 2.5, 22050, 60.0, 0.6, 0.5, 0.9, 0.8, 0.15, 4.5, 0.08)
        val s = LocalMoodAnalyzer.classify(lively).scores
        assertTrue("happy=${s["happy"]} calm=${s["calm"]}", s.getValue("happy") > s.getValue("calm"))
    }

    @Test fun onsetRateCountsAttacksPerSecond() {
        val fps = 21.5
        val frames = (fps * 20).toInt()
        val flux = MutableList(frames) { 0.1 }
        var k = 0
        while ((k * fps * 0.5).toInt() < frames) { flux[(k * fps * 0.5).toInt()] = 1.0; k++ }
        val rate = LocalMoodAnalyzer.estimateOnsetRate(flux, fps)
        assertTrue("rate=$rate", rate in 1.6..2.4)
        assertEquals(0.0, LocalMoodAnalyzer.estimateOnsetRate(MutableList(frames) { 0.3 }, fps), 0.01)
    }
}
