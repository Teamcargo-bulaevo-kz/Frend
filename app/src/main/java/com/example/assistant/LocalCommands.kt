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
        // В фразе есть имя файла (clients.csv): это для модели и общей папки, а не для правил
        if (text.lowercase().split(' ').any { tok -> tok.trimEnd('.', ',', '!', '?').substringAfterLast('.', "") in fileExtensions }) return null
        // Составная просьба («включи фонарик и поставь будильник») — это для модели: она вызовет несколько функций
        if (w.any { it == "и" || it == "потом" || it == "затем" }) return null

        parseTimeQuestion(t, now)?.let { return it }
        parseAlarm(t, w, now)?.let { return it }
        parseTimer(w)?.let { return it }
        parseScreenshot(t, w)?.let { return it }
        parseFlashlight(w)?.let { return it }
        parseCamera(t, w)?.let { return it }
        parseBrightness(t, w)?.let { return it }
        parseVolume(t, w)?.let { return it }
        parsePlayerControl(t)?.let { return it }
        parseRate(t)?.let { return it }
        parsePlaylistAndLibrary(t)?.let { return it }
        parseMusicPlay(t)?.let { return it }
        parseSharedList(t, w)?.let { return it }
        parseQuickToggle(t, w)?.let { return it }
        parseWebSearch(t)?.let { return it }
        parseSettings(t, w)?.let { return it }
        parseWeb(text)?.let { return it }
        parseDial(text)?.let { return it }
        parseCallContact(w)?.let { return it }
        parseFindContact(t)?.let { return it }
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

    // ---------------------------------------------------------------- контакты и таймер

    private val callVerbs = setOf("позвони", "позвонить", "набери", "вызови")

    /** «Позвони маме», «набери Ивана Петрова». Номера цифрами разбирает parseDial. */
    private fun parseCallContact(w: List<String>): LocalCommand? {
        if (w.size !in 2..4 || w[0] !in callVerbs) return null
        val rest = w.drop(1)
        if (rest.any { word -> word.any { it.isDigit() } || word.startsWith("номер") }) return null
        if (rest[0] in setOf("в", "на", "по", "мне", "домой")) return null
        return LocalCommand("call_contact", mapOf("name" to rest.joinToString(" ")))
    }

    private fun parseFindContact(t: String): LocalCommand? {
        for (prefix in listOf("какой номер у ", "найди контакт ", "покажи контакт ", "номер телефона ")) {
            if (t.startsWith(prefix)) {
                val name = t.removePrefix(prefix).trim()
                if (name.isNotEmpty() && name.split(' ').size <= 3) return LocalCommand("find_contact", mapOf("name" to name))
            }
        }
        return null
    }

    /** «Поставь таймер на 10 минут», «таймер на полчаса», «таймер 30 секунд». */
    private fun parseTimer(w: List<String>): LocalCommand? {
        if (w.none { it.startsWith("таймер") }) return null
        if (w.any { it in alarmBlockers || it.startsWith("останов") || it.startsWith("сброс") }) return null
        var seconds = 0
        var found = false
        var i = 0
        while (i < w.size) {
            val word = w[i]
            val n = number(w, i)
            val unit = if (n != null) w.getOrNull(i + n.second) else null
            when {
                word == "полчаса" -> { seconds += 1800; found = true; i++ }
                n != null && unit != null && (unit.startsWith("сек") || unit.startsWith("мин") || unit.startsWith("час")) -> {
                    seconds += n.first * when {
                        unit.startsWith("сек") -> 1
                        unit.startsWith("мин") -> 60
                        else -> 3600
                    }
                    found = true
                    i += n.second + 1
                }
                word.startsWith("секунд") -> { seconds += 1; found = true; i++ }
                word.startsWith("минут") -> { seconds += 60; found = true; i++ }
                word == "час" || word == "часа" -> { seconds += 3600; found = true; i++ }
                else -> i++
            }
        }
        return if (found && seconds > 0) LocalCommand("set_timer", mapOf("seconds" to seconds)) else null
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
        // Профиль: «мой город — Астана», «меня зовут Анна»
        Regex("^(?:запомни )?(?:что )?(?:мой (?:родной |домашний )?город(?: это)?|я живу в|живу в) ([\\p{L}][\\p{L} -]{1,40})$").matchEntire(t)?.let {
            return LocalCommand("set_profile", mapOf("field" to "city", "value" to it.groupValues[1].trim()))
        }
        Regex("^(?:запомни )?(?:что )?(?:меня зовут|зови меня|называй меня|мое имя) ([\\p{L}][\\p{L} -]{0,30})$").matchEntire(t)?.let {
            val name = it.groupValues[1].trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
            return LocalCommand("set_profile", mapOf("field" to "name", "value" to name))
        }
        // Повторная разметка: «разметь музыку заново», «переразметь», «разметь неопознанные», «повтори разметку»
        if (Regex("^пере(?:размет|маркир)\\p{L}*(?: мою| всю| мои| все)?(?: музык\\p{L}*| песн\\p{L}*| треки)?$").matches(t) ||
            Regex("^(?:размет\\p{L}*|проанализир\\p{L}*|размечай)(?: мою| всю| мои| все)?(?: музык\\p{L}*| песн\\p{L}*| треки)? (?:заново|снова|еще раз|по новой|с нуля)$").matches(t) ||
            Regex("^(?:сделай|начни|запусти|давай) разметк\\p{L}* (?:заново|снова|еще раз|с нуля|по новой)$").matches(t)
        ) {
            return LocalCommand("music_tags", mapOf("action" to "reanalyze"))
        }
        if (Regex("^(?:повтори|продолжи|дополни|доделай|доразметь|доразмечай)(?: мою| всю)? (?:размет\\p{L}*)(?: музык\\p{L}*| песен| песни)?$").matches(t) ||
            Regex("^(?:размет\\p{L}*|определи) неопознанн\\p{L}*(?: песни| песен| треки)?$").matches(t) ||
            t in setOf("доразметь музыку", "доразметь песни", "повтори неопознанные")
        ) {
            return LocalCommand("music_tags", mapOf("action" to "retry"))
        }
        if (Regex("^(проанализируй|размет\\p{L}*|определи (жанр|настроение))( мою| всю| мои)? (музык\\p{L}*|песн\\p{L}*|треки)( по (жанр|настроени)\\p{L}*)?$").matches(t) ||
            t in setOf("разметь музыку", "определи жанры и настроение песен", "проанализируй мою музыку")
        ) {
            return LocalCommand("music_tags", mapOf("action" to "analyze"))
        }
        if (Regex("^(подготовь|сделай|выгрузи|дай)( мне)? (музык\\p{L}*|песни|список песен|файлы|файл)( для| под)? (другого|стороннего|внешнего|друг\\p{L}*) (ии|ай|ai|нейросети|нейронки)$").matches(t)) {
            return LocalCommand("music_tags", mapOf("action" to "ai_pack"))
        }
        if (t in setOf("импортируй таблицу музыки", "импортируй таблицу", "импортируй разметку", "загрузи разметку", "подхвати таблицу музыки") ||
            Regex("^(?:импортируй|загрузи|подхвати|возьми|прочитай|используй|примени|добавь)(?: мою| мой| эту| этот| готовую| готовый| новую| новый| свою| свой)? (?:таблиц\\p{L}*|разметк\\p{L}*|файл\\p{L}*)(?: с разметкой| с характеристиками| от ии| от другого ии| музык\\p{L}*| песен| песни| характеристик\\p{L}*)*$").matches(t)
        ) {
            return LocalCommand("music_tags", mapOf("action" to "import"))
        }
        if (t in setOf("как идет разметка музыки", "статус разметки", "сколько песен размечено", "как идет разметка", "статистика музыки")) {
            return LocalCommand("music_tags", mapOf("action" to "status"))
        }
        if (t in setOf(
                "выгрузи список песен", "экспортируй список песен", "список всех песен в файл", "выгрузи мою музыку", "дай весь список песен",
                "покажи весь список песен", "выгрузи список музыки", "экспортируй список музыки", "список всех моих песен"
            )
        ) {
            return LocalCommand("music_tags", mapOf("action" to "export"))
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

    private val likePhrases = setOf(
        "лайк", "поставь лайк", "поставь лайк этой песне", "поставь лайк песне", "мне нравится эта песня", "нравится эта песня",
        "эта песня мне нравится", "мне нравится", "понравилась", "мне понравилась эта песня", "добавь в любимые",
        "в любимые", "добавь эту песню в любимые", "сердечко", "поставь сердечко"
    )
    private val dislikePhrases = setOf(
        "дизлайк", "поставь дизлайк", "не нравится", "мне не нравится", "не нравится эта песня", "эта песня мне не нравится",
        "мне не нравится эта песня", "не включай эту песню", "больше не включай эту песню", "больше не включай это", "не понравилась"
    )

    /** Лайк, дизлайк и «эта песня грустная». */
    private fun parseRate(t: String): LocalCommand? {
        if (t in likePhrases) return LocalCommand("music_rate", mapOf("action" to "like"))
        if (t in dislikePhrases) return LocalCommand("music_rate", mapOf("action" to "dislike"))
        val m = Regex("^(?:эта песня|эта|песня) (\\p{L}+)$").find(t) ?: return null
        val mood = MusicTags.moodFromWords(m.groupValues[1]) ?: return null
        return LocalCommand("music_rate", mapOf("action" to "tag", "moods" to listOf(mood)))
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
        // «включи любимое», «включи мои любимые песни», «включи любимое спокойное»
        if (rest.size <= 4 && (joined.contains("любим") || joined.contains("понравивш"))) {
            val args = mutableMapOf<String, Any?>("query" to "", "shuffle" to true, "liked" to true)
            MusicTags.moodFromWords(joined)?.let { args["mood"] = it }
            MusicTags.genreFromWords(joined)?.let { args["genre"] = it }
            return LocalCommand("play_music", args)
        }
        // «включи спокойную музыку», «поставь что-нибудь энергичное», «включи рок»
        if (rest.size <= 4 && rest[0] !in setOf("песню", "трек", "композицию", "альбом", "исполнителя", "группу")) {
            val mood = MusicTags.moodFromWords(joined)
            val genre = MusicTags.genreFromWords(joined)
            if (mood != null || genre != null) {
                val args = mutableMapOf<String, Any?>("query" to "", "shuffle" to true)
                mood?.let { args["mood"] = it }
                genre?.let { args["genre"] = it }
                return LocalCommand("play_music", args)
            }
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

    private val onVerbs = setOf("включи", "включить", "вруби", "включай", "активируй")
    private val offVerbs = setOf("выключи", "выключить", "отключи", "отключить", "отруби", "вырубай", "деактивируй")
    private val toggleWords = setOf("мне", "пожалуйста", "режим", "функцию", "на", "телефоне")

    /** «Включи вайфай», «выключи режим полёта», «отключи блютуз»: нажимает плитку в шторке. */
    private fun parseQuickToggle(t: String, w: List<String>): LocalCommand? {
        val on = w[0] in onVerbs
        if (!on && w[0] !in offVerbs) return null
        val rest = w.drop(1).filter { it !in toggleWords }
        if (rest.isEmpty() || rest.size > 3) return null
        if (rest.any { it in notQuickWords }) return null
        val joined = rest.joinToString(" ")
        val key = QuickSettings.resolve(joined) ?: return null
        // Сказали только про одну плитку, а не «включи интернет радио»
        if (QuickSettings.tiles.getValue(key).none { TextMatch.normalize(it) == joined || joined.startsWith(TextMatch.normalize(it)) || joined.endsWith(TextMatch.normalize(it)) }) return null
        return LocalCommand("quick_setting", mapOf("setting" to joined, "state" to if (on) "on" else "off"))
    }

    private val notQuickWords = setOf("радио", "музыку", "песню", "трек", "фонарик", "фонарь", "камеру", "свет")

    /** «Яркость на 50», «сделай ярче», «убавь яркость», «максимальная яркость», «автояркость». */
    private fun parseBrightness(t: String, w: List<String>): LocalCommand? {
        val mentions = w.any { it.startsWith("яркост") || it.startsWith("автояркост") } ||
            w.any { it in setOf("ярче", "поярче", "темнее", "потемнее") }
        if (!mentions) return null
        if (w.any { it.startsWith("автояркост") } || t.contains("автоматическ")) {
            return LocalCommand("set_brightness", mapOf("change" to "auto"))
        }
        for (i in w.indices) {
            val n = number(w, i) ?: continue
            if (n.first in 0..100) return LocalCommand("set_brightness", mapOf("percent" to maxOf(1, n.first)))
        }
        val change = when {
            w.any { it.startsWith("максимальн") || it == "максимум" || it == "полную" } -> "max"
            w.any { it.startsWith("минимальн") || it == "минимум" } -> "min"
            w.any { it in setOf("ярче", "поярче", "добавь", "увеличь", "повысь", "больше", "выше") } -> "up"
            w.any { it in setOf("темнее", "потемнее", "убавь", "уменьши", "понизь", "меньше", "ниже") } -> "down"
            else -> return null
        }
        return LocalCommand("set_brightness", mapOf("change" to change))
    }

    private val webSearchRe = Regex("^(?:найди|поищи|найти|узнай)(?: мне)? (?:в интернете|в сети) (?:информацию (?:о|про|об) )?(.+)$")

    /** Свежая информация без модели-посредника: новости, погода, курсы. */
    private fun parseWebSearch(t: String): LocalCommand? {
        webSearchRe.matchEntire(t)?.let { return LocalCommand("web_search", mapOf("query" to it.groupValues[1])) }
        if (t in setOf("новости", "какие новости", "что нового", "последние новости", "что в мире")) {
            return LocalCommand("web_search", mapOf("query" to "главные новости сегодня"))
        }
        if (t.split(' ').size in 2..6 && (t.startsWith("какая погода") || t.startsWith("погода") || t.startsWith("какой курс") || t.startsWith("курс "))) {
            return LocalCommand("web_search", mapOf("query" to t))
        }
        return null
    }

    private val searchRe = Regex("^(?:найди|поищи|найти|загугли|погугли|гугли)(?: мне)? (?:в гугле|в браузере|в google) (?:информацию (?:о|про|об) )?(.+)$")
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
