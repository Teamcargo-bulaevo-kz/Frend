package com.example.assistant

import android.content.Context
import java.io.File

/**
 * Готовит файлы для другого ИИ (ChatGPT, Gemini, Claude и т.д.): списки песен частями по 100 штук с готовой инструкцией.
 * Ответ ИИ копируется и вставляется обратно в «Музыку» (меню ⋮ → «Вставить ответ другого ИИ») или кладётся файлом во «Входящие».
 */
object MusicAiPack {

    const val PART_SIZE = 100
    const val FOLDER = "Музыка для ИИ"

    private val junkAlbums = setOf("download", "downloads", "whatsapp audio", "песни", "music", "telegram", "audio", "unknown", "<unknown>")

    /** Инструкция для внешнего ИИ. Колонки совпадают с таблицей Музыка.xlsx, поэтому ответ читается без доработок. */
    fun instruction(count: Int): String =
        "Ты музыкальный эксперт. Ниже список песен в формате «Исполнитель | Название». Для каждой песни определи характеристики " +
            "и верни ТОЛЬКО таблицу CSV с разделителем «;» (каждое текстовое поле в двойных кавычках), без пояснений и без markdown.\n" +
            "Первая строка — заголовок ровно такой:\n" +
            "Исполнитель;Название;Жанр;Настроение;Энергичность (1-5);Ритм / темп;Краткое описание;Уверенность\n\n" +
            "Правила:\n" +
            "- Исполнитель и Название скопируй из списка БЕЗ ИЗМЕНЕНИЙ, даже если там опечатки или мусор вроде [muzmo.ru]. " +
            "Если исполнитель в списке пуст, оставь поле пустым.\n" +
            "- Жанр: коротко по-русски, например «Латино-поп», «Фонк», «Русский рэп».\n" +
            "- Настроение: одно или два из списка: " + MusicTags.moodShort.values.joinToString(", ") +
            ". Если два, пиши через запятую внутри кавычек.\n" +
            "- Энергичность: число от 1 (очень спокойно) до 5 (очень энергично).\n" +
            "- Ритм / темп: «медленный (до 90 BPM)», «средний (90–110 BPM)» или «быстрый (120–140 BPM)».\n" +
            "- Краткое описание: до 10 слов.\n" +
            "- Уверенность: «Высокая», если песню знаешь; «Средняя»; «Низкая», если угадываешь по названию. Не выдумывай: лучше «Низкая».\n" +
            "- Если песню не нашёл, всё равно заполни строку по названию и поставь «Низкая».\n" +
            "- Строк с данными должно быть ровно $count, по одной на каждую песню, в том же порядке.\n" +
            "- Если у тебя есть поиск в интернете, пользуйся им.\n"

    /** Один файл-часть: инструкция и нумерованный список песен. */
    fun partText(songs: List<MusicTrack>, part: Int, parts: Int): String {
        val sb = StringBuilder()
        sb.append("ЧАСТЬ $part ИЗ $parts\n\n")
        sb.append(instruction(songs.size))
        sb.append("\nПесни (${songs.size}):\n")
        for (t in songs) {
            val album = t.album.takeIf { it.isNotBlank() && it.lowercase() !in junkAlbums && !it.contains('.') }
            sb.append(t.artist).append(" | ").append(t.title)
            if (album != null) sb.append("  (альбом: ").append(album).append(")")
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Песни, которым нужна разметка: не определены или определены неуверенно (и не правились руками). */
    fun songsToSend(context: Context, music: List<MusicTrack>): List<MusicTrack> =
        music.distinctBy { MusicTags.trackKey(it) }.filter { t ->
            val tg = MusicTags.get(context, t)
            !MusicTags.isTagged(tg) || (tg != null && tg.needsReview)
        }.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

    /** Создаёт части в Исходящие/«Музыка для ИИ». Блокирующий. Возвращает текст для пользователя. */
    fun build(context: Context): String {
        if (!SharedFolder.ensure()) return "Нет доступа к общей папке Assistant. Разреши доступ ко всем файлам в настройках."
        val music = MusicLibrary.index(context, force = true).allMusic()
        if (music.isEmpty()) return "Не вижу музыку на телефоне. Разреши доступ к аудио."
        val songs = songsToSend(context, music)
        if (songs.isEmpty()) return "Все песни уже размечены, отдавать другому ИИ нечего."
        val dir = File(SharedFolder.outbox(), FOLDER)
        dir.mkdirs()
        // Старые части заменяем новыми: список песен мог измениться
        dir.listFiles { f -> f.isFile && f.name.startsWith("часть ") && f.extension == "txt" }.orEmpty().forEach { it.delete() }
        val chunks = songs.chunked(PART_SIZE)
        chunks.forEachIndexed { i, chunk ->
            val name = "часть %02d из %02d.txt".format(i + 1, chunks.size)
            File(dir, name).writeText(partText(chunk, i + 1, chunks.size), Charsets.UTF_8)
        }
        return "Подготовил ${songs.size} песен в ${chunks.size} файлах: Assistant/Исходящие/$FOLDER. " +
            "Открой файл, скопируй всё в любой ИИ (ChatGPT, Gemini, Claude), скопируй его ответ и в «Музыке» нажми ⋮ → «Вставить ответ другого ИИ». " +
            "Или сохрани ответ как csv во «Входящие» и скажи «импортируй таблицу музыки»."
    }
}
