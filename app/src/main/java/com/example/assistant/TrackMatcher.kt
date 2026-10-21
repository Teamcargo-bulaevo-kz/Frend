package com.example.assistant

/**
 * Нечёткое сопоставление песни из чужой таблицы с песней на телефоне. Файл от ИИ редко повторяет названия
 * буква в букву: «Believer (Official Audio)», «Imagine Dragons - Believer», «believer» — это одна песня.
 * Чистый Kotlin, есть тесты.
 */
object TrackMatcher {

    private val noise = Regex("[\\[(][^\\])]*[\\])]")
    private val feat = Regex("\\b(feat|ft|featuring|при уч|при участии)\\b.*$")

    internal fun simple(title: String): String =
        TextMatch.normalize(MusicTags.cleanTitle(title).replace(noise, " ").lowercase().replace(feat, " "))

    private fun words(s: String) = TextMatch.normalize(s).split(' ').filter { it.length >= 3 }.toSet()

    /** Возвращает функцию: (исполнитель, название из файла) → (исполнитель, название на телефоне) или null. */
    fun build(device: List<Pair<String, String>>): (String, String) -> Pair<String, String>? {
        val exact = HashMap<String, Pair<String, String>>()
        val byTitle = HashMap<String, MutableList<Pair<String, String>>>()
        for (d in device) {
            exact.putIfAbsent(MusicTags.trackKey(d.first, d.second), d)
            byTitle.getOrPut(simple(d.second)) { ArrayList() }.add(d)
        }
        return fun(artist: String, title: String): Pair<String, String>? {
            if (device.isEmpty()) return null
            exact[MusicTags.trackKey(artist, title)]?.let { return it }
            val t = simple(title)
            if (t.length < 2) return null
            val fileArtist = words(artist)

            fun artistFits(d: Pair<String, String>): Boolean {
                if (fileArtist.isEmpty()) return true
                val da = words(d.first) + words(d.second)
                return fileArtist.any { it in da } || da.any { w -> fileArtist.any { f -> w.length >= 4 && (w.startsWith(f) || f.startsWith(w)) } }
            }

            // 1) то же название; если вариантов несколько, выбирает исполнитель
            byTitle[t]?.let { list ->
                list.firstOrNull { artistFits(it) && fileArtist.isNotEmpty() }?.let { return it }
                if (list.size == 1 && (fileArtist.isEmpty() || artistFits(list[0]))) return list[0]
            }
            // 2) в названии файла «Исполнитель - Песня» или наоборот: название на телефоне входит в строку из файла
            val full = TextMatch.normalize("$artist $title")
            val hits = device.filter { d ->
                val dt = simple(d.second)
                dt.length >= 4 && (full.contains(dt) || (t.length >= 4 && dt.contains(t))) && artistFits(d)
            }
            if (hits.size == 1) return hits[0]
            return null
        }
    }
}
