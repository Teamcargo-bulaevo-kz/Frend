package com.example.assistant

/**
 * Подтверждение опасных действий. Функция, которой нужно согласие пользователя, возвращает ask(...),
 * ассистент задаёт вопрос, а выполняет действие только после «да». Чистый Kotlin.
 */
object Confirm {

    const val MARK = "\u0001CONFIRM:"

    fun ask(question: String) = MARK + question
    fun isAsk(result: String) = result.startsWith(MARK)
    fun question(result: String) = result.removePrefix(MARK)

    private val yes = setOf(
        "да", "ага", "угу", "подтверждаю", "подтверди", "конечно", "давай", "ок", "окей", "ok", "yes",
        "удаляй", "удали", "делай", "звони", "перезапиши", "перезаписывай", "согласен", "валяй", "да удали",
        "да давай", "да делай", "да звони", "да перезапиши", "да удаляй", "да подтверждаю"
    )
    private val no = setOf(
        "нет", "не надо", "не нужно", "отмена", "отмени", "стоп", "не", "не удаляй", "не звони", "отбой", "no", "нет не надо"
    )

    fun isYes(text: String): Boolean = TextMatch.normalize(text).let { n ->
        val words = n.split(' ')
        // «да не надо» и «да нет» — это отказ
        if (words.any { it == "не" || it == "нет" }) false else n in yes || (n.startsWith("да ") && words.size <= 3)
    }
    fun isNo(text: String): Boolean = TextMatch.normalize(text).let { it in no || (it.startsWith("нет ") && it.split(' ').size <= 3) }
}
