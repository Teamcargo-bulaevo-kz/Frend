package com.example.assistant

/** Поиск по списку треков в памяти: части слов, транслит, опечатки. Чистый Kotlin, без Android. */
class MusicIndex(val tracks: List<MusicTrack>) {

    private class Entry(val track: MusicTrack) {
        val title = TextMatch.normalize(track.title)
        val artist = TextMatch.normalize(track.artist)
        val album = TextMatch.normalize(track.album)
        val blob = "$title $artist $album"
        val titleKey = TextMatch.key(track.title)
        val artistKey = TextMatch.key(track.artist)
        val albumKey = TextMatch.key(track.album)
        val blobKey = titleKey + artistKey + albumKey
    }

    private val entries = tracks.map { Entry(it) }

    /** Минимальная похожесть для нечёткого совпадения (имя произнесено или записано с ошибками). */
    private val fuzzyThreshold = 0.72

    /** Вся «настоящая» музыка (без длинных записей и прочего аудио). */
    fun allMusic(): List<MusicTrack> = tracks.filter { it.isMusic }

    fun search(query: String, limit: Int): List<MusicTrack> {
        val q = TextMatch.normalize(query)
        if (q.isEmpty()) return allMusic().take(limit)
        val tokens = q.split(' ')

        // 1. Все слова запроса есть в названии, исполнителе или альбоме
        val direct = entries.filter { e -> tokens.all { it in e.blob } }
        if (direct.isNotEmpty()) return rank(direct, q, limit)

        // 2. То же «на слух»: кириллица против латиницы
        val tokenKeys = tokens.map { TextMatch.key(it) }.filter { it.isNotEmpty() }
        if (tokenKeys.isNotEmpty()) {
            val byKey = entries.filter { e -> tokenKeys.all { it in e.blobKey } }
            if (byKey.isNotEmpty()) return rank(byKey, q, limit)
        }

        // 3. Опечатки и неточное произношение
        val whole = TextMatch.key(q)
        if (whole.length < 3) return emptyList()
        var best = 0.0
        val scored = entries.map { e ->
            val s = maxOf(
                similarity(whole, e.artistKey),
                similarity(whole, e.titleKey),
                similarity(whole, e.albumKey),
                similarity(whole, e.artistKey + e.titleKey)
            )
            if (s > best) best = s
            e to s
        }
        if (best < fuzzyThreshold) return emptyList()
        return scored.filter { it.second >= best - 0.001 }.map { it.first.track }.take(limit)
    }

    private fun similarity(a: String, b: String): Double =
        if (b.isEmpty()) 0.0 else TextMatch.similarity(a, b)

    private fun rank(found: List<Entry>, q: String, limit: Int): List<MusicTrack> =
        found.sortedBy { e ->
            when {
                e.title == q -> 0
                e.title.startsWith(q) -> 1
                e.title.contains(q) -> 2
                e.artist.contains(q) -> 3
                else -> 4
            }
        }.take(limit).map { it.track }

    /** Исполнители и число их треков, по убыванию. */
    fun artists(): List<Pair<String, Int>> =
        allMusic()
            .filter { it.artist.isNotBlank() && !it.artist.equals("<unknown>", ignoreCase = true) }
            .groupingBy { it.artist }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
}
