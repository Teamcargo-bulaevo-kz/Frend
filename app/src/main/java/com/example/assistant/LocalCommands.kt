package com.example.assistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Команда, разобранная локально, без модели. name — имя функции из Tools, args — её аргументы. */
data class LocalCommand(val name: String, val args: Map<String, Any?> = emptyMap())

/**
 * Уровень 1 экономии токенов: типовые команды разбираются правилами (0 токенов).
 * Всё, в чём правила не уверены, возвращает null, и тогда вступает модель.
 * Чистый Kotlin, без Android, поэтому покрыт юнит-тестами.
 */
object LocalCommands {

    fun parse(raw: String, now: Calendar = Calendar.getInstance()): LocalCommand? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val t = TextMatch.normalize(text)
        if (t.isEmpty()) return null
        val w = t.split(' ')

        // Заметки: единственные команды, где текст может быть длинным
        parseNote(text, t)?.let { return it }

        if (w.size > 12) return null
        // Составная просьба («включи фонарик и поставь будильник») — это для модели: она вызовет несколько функций
        if (w.any { it == "и" || it == "потом" || it == "затем" }) return null

        parseTimeQuestion(t, now)?.let { return it }
        parseAlarm(t, w, now)?.let { return it }
        parseScreenshot(t, w)?.let { return it }
        parseFlashlight(w)?.let { return it }
        parseCamera(t, w)?.let { return it }
        parseVolume(t, w)?.let { return it }
        parsePlayerControl(t)?.let { return it }
        parsePlaylistAndLibrary(t)?.let { return it }
        parseMusicPlay(t)?.let { return it }
        parseSharedList(t, w)?.let { return it }
        parseSettings(t, w)?.let { return it }
        parseWeb(text)?.let { return it }
        parseDial(text)?.let { return it }
        parseOpenApp(t, w)?.let { return it }
        parseNotesList(t)?.let { return it }
        return null
    }

    /**
     * «Включи Imagine Dragons», «поставь Баста»: без слов «песню/трек» непонятно, музыка это или нет.
     * Возвращает кандидата на поисковый запрос, а приложение проверит его по библиотеке музыки.
     */
    fun musicCandidate(raw: String): String? {
        val t = TextMatch.normalize(raw)
        val w = t.split(' ')
        if (w.size !in 2..6) return null
        if (w[0] !in playVerbs) return null
        var rest = w.drop(1)
        if (rest.firstOrNull() == "мне") rest = rest.drop(1)
        if (rest.isEmpty()) return null
        if (rest.any { it in notMusicWords }) return null
        return rest.joinToString(" ")
    }

    // ---------------------------------------------------------------- слова

    private val playVerbs = setOf("включи", "поставь", "запусти", "играй", "врубай", "включить", "поставить", "запустить")
    private val openVerbs = setOf("открой", "открыть", "запусти", "запустить", "зайди", "перейди", "покажи")
    private val notMusicWords = setOf(
        "свет", "wifi", "wi", "fi", "вайфай", "вай", "фай", "блютуз", "bluetooth", "фонарик", "фонарь", "камеру",
        "камера", "режим", "интернет", "экран", "геолокацию", "авиарежим", "будильник", "звук", "тихий", "беззвучный",
        "приложение", "сайт", "яркость", "вибрацию", "нфс", "nfc", "заметки", "заметку"
    )

    private val numberWords: Map<String, Int> = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50
    )
    private val tens = setOf(20, 30, 40, 50)

    /** Число из токенов начиная с позиции i: цифры или слова ("семь", "двадцать пять"). Возвращает значение и сколько токенов занято. */
    private fun number(tokens: List<String>, i: Int): Pair<Int, Int>? {
        val token = tokens.getOrNull(i) ?: return null
        token.toIntOrNull()?.let { return it to 1 }
        val value = numberWords[token] ?: return null
        if (value in tens) {
            val next = numberWords[tokens.getOrNull(i + 1)]
            if (next != null && next in 1..9) return (value + next) to 2
        }
        return value to 1
    }

    // ---------------------------------------------------------------- время и дата

    private fun parseTimeQuestion(t: String, now: Calendar): LocalCommand? {
        val ru = Locale("ru")
        return when {
            t in setOf("который час", "сколько времени", "сколько сейчас времени", "который сейчас час", "сколько время") -> {
                val time = SimpleDateFormat("HH:mm", ru).format(now.time)
                LocalCommand("say", mapOf("text" to "Сейчас $time"))
            }
            t in setOf(
                "какое сегодня число", "какая сегодня дата", "какой сегодня день", "какой сегодня день недели",
                "какое число", "какая дата", "какое сегодня число и день недели"
            ) -> {
                val date = SimpleDateFormat("d MMMM yyyy 'года', EEEE", ru).format(now.time)
                LocalCommand("say", mapOf("text" to "Сегодня $date"))
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- общая папка

    /** «Что во входящих», «покажи общую папку»: список файлов без модели. */
    private fun parseSharedList(t: String, w: List<String>): LocalCommand? {
        if (w.none { it in setOf("что", "покажи", "список", "какие") }) return null
        val inbox = w.any { it.startsWith("входящ") }
        val outbox = w.any { it.startsWith("исходящ") }
        val shared = t.contains("общ") && w.any { it.startsWith("папк") }
        if (!inbox && !outbox && !shared) return null
        val folder = if (outbox) "Исходящие" else if (inbox) "Входящие" else "все"
        return LocalCommand("shared_folder", mapOf("action" to "list", "folder" to folder))
    }

    // ---------------------------------------------------------------- будильник

    private val alarmBlockers = setOf("удали", "удалить", "отмени", "отменить", "выключи", "отключи", "убери", "сними")

    private fun parseAlarm(t: String, w: List<String>, now: Calendar): LocalCommand? {
        val mentions = w.any { it.startsWith("будильник") || it.startsWith("разбуд") || it == "заведи" }
        if (!mentions) return null
        if (w.any { it in alarmBlockers }) return null

        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        relativeMinutes(w)?.let { delta ->
            val total = (minutesNow + delta) % (24 * 60)
            return alarm(total / 60, total % 60)
        }
        absoluteTime(w)?.let { (h, m) -> return alarm(h, m) }
        return null
    }

    private fun alarm(hour: Int, minute: Int) =
        LocalCommand("set_alarm", mapOf("hour" to hour, "minute" to minute))

    /** «через полчаса», «через 2 часа 15 минут», «на 5 минут». Возвращает минуты или null. */
    private fun relativeMinutes(w: List<String>): Int? {
        val through = w.indexOf("через")
        if (through >= 0) {
            var i = through + 1
            var total = 0
            var found = false
            while (i < w.size) {
                val word = w[i]
                when {
                    word == "полчаса" -> { total += 30; found = true; i++ }
                    word == "пол" && w.getOrNull(i + 1)?.startsWith("час") == true -> { total += 30; found = true; i += 2 }
                    word == "полтора" -> { total += 90; found = true; i += 2 }
                    word.startsWith("час") -> { total += 60; found = true; i++ }
                    word.startsWith("минут") -> { total += 1; found = true; i++ }
                    else -> {
                        val n = number(w, i) ?: break
                        val unit = w.getOrNull(i + n.second) ?: break
                        when {
                            unit.startsWith("час") -> total += n.first * 60
                            unit.startsWith("мин") -> total += n.first
                            else -> break
                        }
                        found = true
                        i += n.second + 1
                    }
                }
            }
            return if (found && total > 0) total else null
        }
        // «на 5 минут». «на 8 часов» — это время 8:00, его разбирает absoluteTime
        val on = w.indexOf("на")
        if (on >= 0) {
            val n = number(w, on + 1) ?: return null
            val unit = w.getOrNull(on + 1 + n.second) ?: return null
            return if (unit.startsWith("мин")) n.first else null
        }
        return null
    }

    /** «в 7», «на 7 30», «в семь тридцать утра», «в 19 45». Возвращает (часы, минуты). */
    private fun absoluteTime(w: List<String>): Pair<Int, Int>? {
        for (i in w.indices) {
            if (w[i] != "в" && w[i] != "на" && w[i] != "к") continue
            val h = number(w, i + 1) ?: continue
            var next = i + 1 + h.second
            var hour = h.first
            var minute = 0

            // «ноль пять» → 05
            if (w.getOrNull(next) == "ноль") {
                val m = number(w, next + 1)
                if (m != null && m.first in 0..9) { minute = m.first; next += 1 + m.second }
            } else {
                val m = number(w, next)
                if (m != null && m.first in 0..59 && (m.first >= 10 || w[next].toIntOrNull() != null || w[next] in numberWords)) {
                    // минуты — только если дальше не единица времени ("в 7 часов", "в 5 минут")
                    val after = w.getOrNull(next + m.second)
                    if (after == null || !after.startsWith("мин")) {
                        minute = m.first
                        next += m.second
                    }
                }
            }
            if (w.getOrNull(next)?.let { it.startsWith("час") || it == "ч" } == true) next++

            when (w.getOrNull(next)) {
                "утра" -> if (hour == 12) hour = 0
                "дня" -> if (hour in 1..11) hour += 12
                "вечера" -> if (hour in 1..11) hour += 12
                "ночи" -> if (hour == 12) hour = 0 else if (hour in 6..11) hour += 12
            }
            if (hour in 0..23 && minute in 0..59) return hour to minute
        }
        return null
    }

    // ---------------------------------------------------------------- скриншот, фонарик, камера

    private fun parseScreenshot(t: String, w: List<String>): LocalCommand? {
        if (w.size > 5) return null
        val hit = w.any { it.startsWith("скриншот") || it == "скрин" || it == "скрины" } ||
            t.contains("снимок экрана") || t.contains("снимок дисплея")
        return if (hit) LocalCommand("take_screenshot") else null
    }

    private val lightOn = setOf("включи", "зажги", "включай", "вруби", "посвети", "подсвети", "включить")
    private val lightOff = setOf("выключи", "погаси", "выруби", "отключи", "убери", "выключить", "гаси")

    private fun parseFlashlight(w: List<String>): LocalCommand? {
        if (w.size > 4) return null
        if (w.none { it.startsWith("фонар") }) return null
        return when {
            w.any { it in lightOff } -> LocalCommand("flashlight", mapOf("on" to false))
            w.any { it in lightOn } -> LocalCommand("flashlight", mapOf("on" to true))
            else -> null
        }
    }

    private fun parseCamera(t: String, w: List<String>): LocalCommand? {
        if (w.size > 5) return null
        val video = w.any { it.startsWith("видео") }
        val verbs = w.any { it in setOf("сними", "запиши", "снять", "записать", "сделай", "начни", "заснять") }
        if (video && verbs) return LocalCommand("open_camera", mapOf("mode" to "video"))

        val photo = t.startsWith("сфотографируй") || t.startsWith("сфоткай") ||
            t.startsWith("сделай фото") || t.startsWith("сними фото") || t.startsWith("сделай снимок") ||
            t.startsWith("сделай селфи") || t.startsWith("сделай фотку") || t.startsWith("сфоткай")
        if (photo) return LocalCommand("open_camera", mapOf("mode" to "photo"))

        val openCam = w.any { it.startsWith("камер") } && w.any { it in openVerbs || it in playVerbs }
        if (openCam) return LocalCommand("open_camera", mapOf("mode" to "photo"))
        return null
    }

    // ---------------------------------------------------------------- громкость и плеер

    private val volumeWords = setOf("громкость", "громкости", "звук", "звука", "громче", "тише", "погромче", "потише", "убавь", "прибавь", "заглуши", "громко")

    private fun parseVolume(t: String, w: List<String>): LocalCommand? {
        if (w.size > 6) return null
        if (w.none { it in volumeWords }) return null

        if (t.contains("без звука") || t.contains("выключи звук") || t.contains("отключи звук") ||
            w.contains("заглуши") || t.contains("беззвучный")
        ) {
            return LocalCommand("set_volume", mapOf("action" to "mute"))
        }
        if (t.contains("максимум") || t.contains("на максимальную") || t.contains("максимальная")) {
            return LocalCommand("set_volume", mapOf("action" to "max"))
        }
        val number = w.firstNotNullOfOrNull { it.toIntOrNull() }
        val absolute = w.any { it.startsWith("громкост") } && number != null && number in 0..100
        if (absolute) return LocalCommand("set_volume", mapOf("action" to "set", "percent" to number))

        val up = w.any { it == "громче" || it == "погромче" || it == "прибавь" || it == "добавь" || it == "увеличь" || it == "повысь" }
        val down = w.any { it == "тише" || it == "потише" || it == "убавь" || it == "уменьши" || it == "понизь" || it == "убери" }
        val step = if (number != null && number in 1..100) mapOf("percent" to number) else emptyMap()
        return when {
            up -> LocalCommand("set_volume", mapOf("action" to "up") + step)
            down -> LocalCommand("set_volume", mapOf("action" to "down") + step)
            else -> null
        }
    }

    private val filler = "(?:пожалуйста )?"
    private val trackWord = "(?: (?:трек|песню|песня|композицию|воспроизведение|музыку|плеер))?"
    private val trackWordRequired = " (?:трек|песню|композицию|воспроизведение|музыку|плеер)"
    private val pauseRe = Regex("^${filler}(?:пауза|на паузу|поставь на паузу|поставь паузу|приостанови|приостанови$trackWord|останови на паузу)(?: пожалуйста)?$")
    private val stopRe = Regex("^${filler}(?:стоп|останови$trackWord|выключи$trackWordRequired|отключи$trackWordRequired|хватит|хватит музыки|вырубай$trackWordRequired)(?: пожалуйста)?$")
    private val resumeRe = Regex("^${filler}(?:продолжи|продолжай|возобнови|продолжить|играй|плей|снимите с паузы|сними с паузы|продолжи$trackWord|возобнови$trackWord)(?: пожалуйста)?$")
    private val nextRe = Regex("^${filler}(?:(?:включи |переключи на )?следующ\\p{L}+|дальше|переключи|пропусти|next|другую|другой|переключай)$trackWord(?: пожалуйста)?$")
    private val prevRe = Regex("^${filler}(?:(?:включи |переключи на )?предыдущ\\p{L}+|назад|прошлую|прошлый|вернись|верни|верни назад|включи прошлую)$trackWord(?: пожалуйста)?$")

    private fun parsePlayerControl(t: String): LocalCommand? {
        val action = when {
            pauseRe.matches(t) -> "pause"
            nextRe.matches(t) -> "next"
            prevRe.matches(t) -> "previous"
            stopRe.matches(t) -> "stop"
            resumeRe.matches(t) -> "play"
            else -> null
        } ?: return null
        return LocalCommand("music_control", mapOf("action" to action))
    }

    // ---------------------------------------------------------------- музыка и плейлисты

    private val shuffleTails = listOf(" вперемешку", " в случайном порядке", " случайно", " рандом")

    private fun parsePlaylistAndLibrary(t: String): LocalCommand? {
        val w = t.split(' ')
        if (w.size >= 3 && w[0] in playVerbs) {
            val rest = w.drop(1).let { if (it.first() == "мне") it.drop(1) else it }
            if (rest.size >= 2 && rest[0].startsWith("плейлист")) {
                var name = rest.drop(1).joinToString(" ")
                var shuffle = false
                for (tail in shuffleTails) {
                    if (name.endsWith(tail)) { name = name.removeSuffix(tail); shuffle = true }
                }
                if (name.isNotBlank()) {
                    return LocalCommand("playlist", mapOf("action" to "play", "name" to name.trim(), "shuffle" to shuffle))
                }
            }
        }
        if (t in setOf("какие у меня плейлисты", "покажи плейлисты", "мои плейлисты", "список плейлистов", "какие есть плейлисты", "покажи мои плейлисты")) {
            return LocalCommand("playlist", mapOf("action" to "list"))
        }
        if (t in setOf(
                "какая у меня музыка", "какая музыка у меня есть", "какие у меня исполнители", "какие исполнители у меня есть",
                "сколько у меня песен", "сколько песен", "сколько у меня треков", "сколько треков", "что у меня из музыки",
                "какая музыка есть", "какие песни у меня есть", "что у меня есть из музыки", "покажи мою музыку"
            )
        ) {
            return LocalCommand("music_library")
        }
        return null
    }

    private val playKeywords = setOf("песню", "песни", "трек", "треки", "композицию", "альбом", "исполнителя", "группу", "музыку")

    private fun parseMusicPlay(t: String): LocalCommand? {
        val w = t.split(' ')
        if (w.isEmpty() || w[0] !in playVerbs) return null
        var rest = w.drop(1)
        if (rest.firstOrNull() == "мне") rest = rest.drop(1)
        if (rest.isEmpty()) return null

        val shuffle = shuffleTails.any { t.endsWith(it) }
        if (shuffle) {
            for (tail in shuffleTails) {
                val joined = rest.joinToString(" ")
                if (joined.endsWith(tail.trim())) {
                    rest = joined.removeSuffix(tail.trim()).trim().split(' ').filter { it.isNotEmpty() }
                    break
                }
            }
        }

        // «включи музыку», «поставь что-нибудь»
        val joined = rest.joinToString(" ")
        if (joined in setOf("музыку", "что нибудь", "что нить", "музон", "музыка")) {
            return LocalCommand("play_music", mapOf("query" to "", "shuffle" to true))
        }
        // «включи песню волны», «поставь исполнителя Баста»
        if (rest.size >= 2 && rest[0] in playKeywords) {
            val query = rest.drop(1).joinToString(" ")
            return LocalCommand("play_music", mapOf("query" to query, "shuffle" to shuffle))
        }
        return null
    }

    // ---------------------------------------------------------------- настройки, сайты, приложения, звонки

    private val sectionKeys: List<Pair<String, List<String>>> = listOf(
        "wifi" to listOf("wifi", "wi fi", "вайфай", "вай фай", "вай-фай"),
        "bluetooth" to listOf("bluetooth", "блютуз", "блютус", "блутус"),
        "nfc" to listOf("nfc", "нфс", "бесконтакт"),
        "airplane" to listOf("полет", "авиа"),
        "battery" to listOf("батаре", "энергосбереж", "аккумулятор"),
        "display" to listOf("экран", "яркост", "дисплей"),
        "sound" to listOf("звук"),
        "apps" to listOf("приложени"),
        "location" to listOf("геолокац", "местоположен", "локаци", "gps"),
        "storage" to listOf("памят", "хранилищ"),
        "date" to listOf("дата", "времени", "часовой"),
        "accessibility" to listOf("специальн", "доступност")
    )

    private fun parseSettings(t: String, w: List<String>): LocalCommand? {
        if (w.size > 5) return null
        if (w.none { it in openVerbs }) return null
        val hasSettings = w.any { it.startsWith("настройк") }
        val section = sectionKeys.firstOrNull { (_, keys) -> keys.any { t.contains(it) } }?.first
        val quickToggle = section == "wifi" || section == "bluetooth" || section == "nfc"
        if (!hasSettings && !quickToggle) return null
        return LocalCommand("open_settings", mapOf("section" to (section ?: "main")))
    }

    private val searchRe = Regex("^(?:найди|поищи|найти|загугли|погугли|гугли)(?: мне)? (?:в интернете|в гугле|в браузере|в сети|в google) (?:информацию (?:о|про|об) )?(.+)$")
    private val googleRe = Regex("^(?:загугли|погугли|гугли) (.+)$")
    private val fileExtensions = setOf(
        "csv", "txt", "json", "db", "sqlite", "xlsx", "xls", "pdf", "doc", "docx", "jpg", "jpeg", "png",
        "mp3", "mp4", "zip", "apk", "xml", "log", "sql"
    )

    private val siteRe = Regex("^(?:открой|открыть|запусти|зайди на|перейди на|перейди) (?:сайт )?((?:https?://)?[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(?:/\\S*)?)$")

    /** Работает по исходному тексту: в нормализованном точки пропадают. */
    private fun parseWeb(raw: String): LocalCommand? {
        val lower = raw.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        siteRe.matchEntire(lower)?.let {
            // «открой clients.csv» — это файл, а не сайт
            val ext = it.groupValues[1].substringAfterLast('.')
            if (ext in fileExtensions) return null
            return LocalCommand("open_browser", mapOf("url" to it.groupValues[1]))
        }
        val t = TextMatch.normalize(raw)
        (searchRe.matchEntire(t) ?: googleRe.matchEntire(t))?.let {
            return LocalCommand("open_browser", mapOf("search_query" to it.groupValues[1]))
        }
        return null
    }

    private val dialRe = Regex("^(?:позвони|набери|вызови|набрать|позвонить)(?: на номер| номер| по номеру)?\\s*(\\+?\\d[\\d\\s\\-()]{4,})$")

    private fun parseDial(raw: String): LocalCommand? {
        val lower = raw.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        val match = dialRe.matchEntire(lower) ?: return null
        val number = match.groupValues[1].filter { it.isDigit() || it == '+' }
        return LocalCommand("open_dialer", mapOf("number" to number))
    }

    private val openExclusions = setOf(
        "сайт", "ссылку", "файл", "папку", "последнее", "последний", "последние", "фотку", "снимок", "документ",
        "заметку", "заметки", "плейлист", "музыку", "песню", "трек", "альбом", "будильник", "сообщение", "чат", "фото", "видео"
    )

    private fun parseOpenApp(t: String, w: List<String>): LocalCommand? {
        if (w.size !in 2..4) return null
        if (w[0] !in setOf("открой", "открыть", "запусти", "запустить", "зайди")) return null
        var rest = w.drop(1)
        if (rest.firstOrNull() == "в" || rest.firstOrNull() == "приложение") rest = rest.drop(1)
        if (rest.firstOrNull() == "приложение") rest = rest.drop(1)
        if (rest.isEmpty() || rest.size > 3) return null
        if (rest[0] in openExclusions) return null
        if (rest[0].startsWith("настройк")) return null
        return LocalCommand("open_app", mapOf("name" to rest.joinToString(" ")))
    }

    // ---------------------------------------------------------------- заметки

    private val noteLead = Regex(
        "^(?:запомни|запиши|сохрани|создай заметку|добавь заметку|сделай заметку|заметка)(?:[:,]| что| заметку| в заметки)?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val noteTail = Regex(
        "^(.+?)[,\\s]+(?:запомни|запиши|сохрани|запиши в заметки|сохрани в заметки)[.!]*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private fun parseNote(raw: String, t: String): LocalCommand? {
        val trimmed = raw.trim().trimEnd('.', '!')
        // «запомни» без текста относится к предыдущим сообщениям: это для модели с историей
        if (t == "запомни" || t == "запиши" || t == "сохрани") return null
        if (t.startsWith("запиши видео") || t.startsWith("запиши аудио") || t.startsWith("запиши звук")) return null
        noteLead.matchEntire(trimmed)?.let { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) return LocalCommand("create_note", mapOf("text" to text))
        }
        noteTail.matchEntire(trimmed)?.let { m ->
            val text = m.groupValues[1].trim().trimEnd(',')
            if (text.isNotEmpty()) return LocalCommand("create_note", mapOf("text" to text))
        }
        return null
    }

    private fun parseNotesList(t: String): LocalCommand? =
        if (t in setOf("покажи заметки", "мои заметки", "список заметок", "все заметки", "открой заметки", "покажи мои заметки", "какие у меня заметки")) {
            LocalCommand("list_notes")
        } else {
            null
        }
}
