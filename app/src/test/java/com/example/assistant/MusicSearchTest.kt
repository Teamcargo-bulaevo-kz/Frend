package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSearchTest {

    private val numb = MusicTrack(1, "Numb", "Linkin Park", "Meteora")
    private val theEnd = MusicTrack(2, "In the End", "Linkin Park", "Hybrid Theory")
    private val believer = MusicTrack(3, "Believer", "Imagine Dragons", "Evolve")
    private val volny = MusicTrack(4, "Волны", "Баста", "Баста 4")
    private val sansara = MusicTrack(5, "Сансара", "Баста", "Баста 1")
    private val kino = MusicTrack(6, "Звезда по имени Солнце", "Кино", "Группа крови")
    private val recording = MusicTrack(7, "Запись разговора", "", "", isMusic = false)

    private val index = MusicIndex(listOf(numb, theEnd, believer, volny, sansara, kino, recording))

    private fun ids(query: String, limit: Int = 50) = index.search(query, limit).map { it.id }

    @Test fun findsByTitle() {
        assertEquals(listOf(1L), ids("numb"))
        assertEquals(listOf(4L), ids("волны"))
        assertEquals(listOf(4L), ids("Волны"))
    }

    @Test fun findsByArtist() {
        assertEquals(setOf(1L, 2L), ids("linkin park").toSet())
        assertEquals(setOf(4L, 5L), ids("баста").toSet())
    }

    @Test fun findsByArtistAndTitleTogether() {
        assertEquals(listOf(1L), ids("linkin park numb"))
    }

    @Test fun cyrillicQueryFindsLatinNames() {
        assertEquals(setOf(1L, 2L), ids("линкин парк").toSet())
        assertEquals(listOf(3L), ids("имаджин драгонс"))
    }

    @Test fun toleratesTypos() {
        assertEquals(setOf(4L, 5L), ids("басту").toSet())
        assertEquals(listOf(3L), ids("имеджин драгонс"))
    }

    @Test fun exactTitleComesFirst() {
        val tracks = listOf(
            MusicTrack(10, "Love Story Remix", "A"),
            MusicTrack(11, "Story", "B"),
            MusicTrack(12, "A Story", "C")
        )
        assertEquals(11L, MusicIndex(tracks).search("story", 10).first().id)
    }

    @Test fun nothingFound() {
        assertTrue(ids("zzzzqqqq").isEmpty())
    }

    @Test fun emptyQueryGivesOnlyRealMusic() {
        val all = ids("")
        assertTrue(7L !in all)
        assertEquals(6, all.size)
    }

    @Test fun respectsLimit() {
        assertEquals(1, ids("linkin park", limit = 1).size)
    }

    @Test fun artistsAreCounted() {
        val artists = index.artists()
        assertTrue(artists.any { it.first == "Linkin Park" && it.second == 2 })
        assertTrue(artists.any { it.first == "Баста" && it.second == 2 })
        assertTrue(artists.none { it.first.isBlank() })
        assertEquals(artists.sortedByDescending { it.second }, artists)
    }
}
