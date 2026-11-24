package com.example.assistant

/**
 * Распознавание слова активации («Пятница» по умолчанию, меняется в настройках) в тексте.
 * Чистый Kotlin. Офлайн-распознавание часто искажает слово, поэтому сравнение нечёткое.
 */
object WakeWord {

    const val DEFAULT = "пятница"

    /** Текущее слово активации. Выставляется при запуске приложения и при смене в настройках. */
    @Volatile var target: String = DEFAULT

    /** Приводит введённое слово к виду для сравнения. Пусто или короче 4 букв — берётся стандартное. */
    fun clean(word: String?): String {
        val w = TextMatch.normalize(word.orEmpty()).replace(" ", "")
        return if (w.length < 4) DEFAULT else w
    }

    /** Расстояние Левенштейна между двумя строками. */
    internal fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Сколько ошибок распознавания прощаем для слова такой длины. */
    private fun allowed(t: String) = if (t.length >= 7) 2 else 1

    /** Слово (или несколько слов, склеенных вместе) похоже на слово активации. */
    fun isWakeWord(word: String, wake: String = target): Boolean {
        val t = clean(wake)
        val w = TextMatch.normalize(word).replace(" ", "")
        if (w.isEmpty()) return false
        // Для «Пятницы» оставлены проверенные на телефоне варианты написания
        if (t == DEFAULT && (w.startsWith("пятниц") || w.startsWith("пятнит") || w.startsWith("пятни"))) return true
        if (w.length < t.length - 2) return false
        return distance(w, t) <= allowed(t)
    }

    /** Слова, после которых «пятница» — это день недели, а не обращение («в пятницу», «до пятницы», «эту пятницу»). */
    private val blockers = setOf(
        "в", "во", "на", "до", "по", "с", "со", "к", "ко", "от", "про", "за", "после", "между", "перед", "под", "через",
        "каждую", "каждой", "каждая", "эту", "этой", "эта", "ту", "той", "следующую", "следующей", "следующая",
        "прошлую", "прошлой", "прошлая", "ближайшую", "ближайшей", "черная", "черную", "черной", "страстная", "страстную",
        "какую", "какой", "какая", "одну", "одной", "всю", "всей", "до", "сегодня", "завтра", "вчера", "уже", "будет", "была", "был"
    )

    /** Слова, которые можно сказать перед обращением: «эй, пятница», «ну пятница». */
    private val fillers = setOf("эй", "ну", "слушай", "о", "так", "алло", "хей", "ой", "а", "привет", "скажи", "окей", "ок", "здравствуй", "добрый", "доброе", "утро", "день", "вечер")

    /** Строгое совпадение для слова не в начале фразы: почти точно и в именительном падеже. */
    private fun strict(word: String, wake: String): Boolean {
        val t = clean(wake)
        val w = TextMatch.normalize(word).replace(" ", "")
        if (t == DEFAULT) return w == "пятница" || (w.endsWith("а") && distance(w, t) <= 1)
        return distance(w, t) <= 1
    }

    /** Обращение ли это в позиции [i]: учитывает слова перед ним, чтобы день недели в речи не будил ассистента. */
    private fun addressed(words: List<String>, i: Int, candidate: String, wake: String): Boolean {
        val prev = words.getOrNull(i - 1)
        if (prev != null && prev in blockers) return false
        val before = words.take(i)
        if (before.all { it in fillers }) return true
        // Перед словом что-то значимое: принимаем только близко к началу и только точное обращение
        return i <= 3 && strict(candidate, wake)
    }

    /**
     * Если в тексте есть слово активации, возвращает команду после него (может быть пустой), иначе null.
     * Учитывает, что слово могло распознаться как два слова («пят ница»), и пропускает упоминания дня недели
     * в обычной речи («встретимся в пятницу»).
     */
    fun commandAfter(text: String, wake: String = target): String? {
        val words = TextMatch.normalize(text).split(' ').filter { it.isNotEmpty() }
        for (i in words.indices) {
            if (isWakeWord(words[i], wake) && addressed(words, i, words[i], wake)) return words.drop(i + 1).joinToString(" ")
            if (i + 1 < words.size && isWakeWord(words[i] + words[i + 1], wake) && addressed(words, i, words[i] + words[i + 1], wake)) {
                return words.drop(i + 2).joinToString(" ")
            }
        }
        return null
    }
}
