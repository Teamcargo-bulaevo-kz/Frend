package com.example.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Описание функций, которые модель может вызывать. Исполняет их AssistantRepository. */
object Tools {

    private fun prop(type: String, description: String, values: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("description", description)
            if (values != null) put("enum", JSONArray(values))
        }

    private fun text(description: String) = prop("string", description)
    private fun flag(description: String) = prop("boolean", description)
    private fun number(description: String) = prop("integer", description)
    private fun choice(description: String, values: List<String>) = prop("string", description, values)

    private fun list(description: String): JSONObject = JSONObject().apply {
        put("type", "array")
        put("description", description)
        put("items", JSONObject().put("type", "string"))
    }

    private fun tool(
        name: String,
        description: String,
        properties: List<Pair<String, JSONObject>> = emptyList(),
        required: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply { properties.forEach { (k, v) -> put(k, v) } })
                put("required", JSONArray(required))
            })
        })
    }

    val all: JSONArray by lazy {
        JSONArray().apply {
            // --- будильник и музыка
            put(tool(
                "set_alarm", "Ставит будильник на телефоне на указанное время. Будильник создаётся сразу, без подтверждения.",
                listOf(
                    "hour" to number("Час в 24-часовом формате, от 0 до 23"),
                    "minute" to number("Минуты, от 0 до 59"),
                    "label" to text("Необязательная подпись будильника, например 'тренировка'")
                ),
                listOf("hour", "minute")
            ))
            put(tool(
                "play_music",
                "Ищет музыку на телефоне по названию трека, исполнителю или альбому и включает её во встроенном плеере. " +
                    "Найденные треки встают в очередь.",
                listOf(
                    "query" to text("Название трека, исполнитель или альбом. Если пользователь просит просто включить музыку без уточнений, оставь пустым"),
                    "shuffle" to flag("true — играть вперемешку"),
                    "mood" to choice("Настроение музыки, если просят подобрать по настроению", MusicTags.moods.keys.toList()),
                    "genre" to choice("Жанр, если просят музыку определённого жанра", MusicTags.genres.keys.toList()),
                    "liked" to flag("true — только любимые песни (с лайком)")
                )
            ))
            put(tool(
                "music_rate",
                "Лайк, дизлайк и ручная правка характеристик песни. like — понравилась, dislike — не нравится (в подборки не попадёт), " +
                    "clear — снять оценку, info — что известно о песне, tag — изменить настроение, жанр, энергичность или темп. " +
                    "Если песня не названа, речь о той, что играет сейчас.",
                listOf(
                    "action" to choice("Что сделать", listOf("like", "dislike", "clear", "info", "tag")),
                    "query" to text("Название песни или исполнитель. Пусто — текущая песня"),
                    "moods" to list("Для tag: настроения из " + MusicTags.moods.keys.joinToString(", ")),
                    "genre" to choice("Для tag: жанр", MusicTags.genres.keys.toList()),
                    "energy" to number("Для tag: энергичность от 1 до 5"),
                    "tempo" to choice("Для tag: темп", MusicTags.tempos.keys.toList())
                ),
                listOf("action")
            ))
            put(tool(
                "music_tags",
                "Разметка музыки на телефоне: жанр, настроение, энергичность каждой песни. " +
                    "analyze — разметить неразмеченные песни (в фоне), status — сколько размечено и ход разметки, " +
                    "export — обновить таблицу Музыка.xlsx со всеми песнями, жанрами и настроениями в папке Assistant, " +
                    "import — подхватить готовые таблицы с разметкой (xlsx или csv из папки Входящие), " +
                    "ai_pack — подготовить списки песен с инструкцией для другого ИИ (ChatGPT, Gemini), чтобы он разметил их вместо встроенного.",
                listOf(
                    "action" to choice("Что сделать", listOf("analyze", "status", "export", "import", "ai_pack")),
                    "file" to text("Для import: имя xlsx-файла. Если не указано, берётся подходящий сам")
                ),
                listOf("action")
            ))
            put(tool(
                "music_control",
                "Управляет воспроизведением музыки: пауза, продолжить, следующий трек, предыдущий трек, стоп.",
                listOf("action" to choice("Что сделать", listOf("pause", "play", "next", "previous", "stop"))),
                listOf("action")
            ))
            put(tool(
                "set_volume",
                "Меняет громкость. set — выставить уровень в процентах, up/down — сделать громче/тише, mute — выключить звук, max — на максимум.",
                listOf(
                    "action" to choice("Что сделать", listOf("set", "up", "down", "mute", "max")),
                    "percent" to number("Для set: уровень от 0 до 100. Для up/down: на сколько процентов изменить (по умолчанию 10)"),
                    "stream" to choice("Какая громкость, по умолчанию music", listOf("music", "ring", "alarm", "notification"))
                ),
                listOf("action")
            ))
            put(tool(
                "playlist",
                "Плейлисты и папки («для бега», «для прогулки» — это то же самое). create — создать (можно пустую), " +
                    "add — добавить песни (если папки нет, она создастся), remove — убрать песни из папки, move — переложить песни " +
                    "из одной папки в другую, rename — переименовать, play — включить, show — показать песни, list — все папки, delete — удалить. " +
                    "Просьбу «убери из бега и добавь в прогулку» делай двумя вызовами (remove и add) или одним move.",
                listOf(
                    "action" to choice("Что сделать", listOf("create", "add", "remove", "move", "rename", "play", "show", "list", "delete")),
                    "name" to text("Название папки или плейлиста (для move — откуда)"),
                    "target" to text("Для move: в какую папку переложить. Для rename: новое название"),
                    "queries" to list("Названия песен или исполнители, которые нужно найти на телефоне"),
                    "current" to flag("true — взять песню, которая играет сейчас («эту песню», «текущую»)"),
                    "shuffle" to flag("Для play: true — играть вперемешку")
                ),
                listOf("action")
            ))

            put(tool(
                "music_library",
                "Показывает, какая музыка есть на телефоне: общая сводка или поиск по исполнителю или названию. " +
                    "Используй, когда спрашивают, есть ли песня или исполнитель, или что есть из музыки.",
                listOf("query" to text("Исполнитель или название. Пусто — общая сводка"))
            ))

            // --- заметки
            put(tool(
                "create_note", "Сохраняет новую текстовую заметку.",
                listOf("text" to text("Текст заметки, который нужно сохранить")), listOf("text")
            ))
            put(tool("list_notes", "Показывает все сохранённые заметки."))
            put(tool(
                "search_notes", "Ищет заметки по тексту.",
                listOf("query" to text("Текст или ключевое слово для поиска среди заметок")), listOf("query")
            ))
            put(tool(
                "delete_note", "Удаляет заметку (или несколько), которая подходит под поисковый запрос.",
                listOf("query" to text("Текст или ключевое слово, по которому нужно найти и удалить заметку")), listOf("query")
            ))

            // --- телефон
            put(tool(
                "flashlight", "Включает или выключает фонарик.",
                listOf("on" to flag("true — включить, false — выключить")), listOf("on")
            ))
            put(tool(
                "open_camera",
                "Открывает камеру в режиме фото или видео. Снимок или запись пользователь запускает сам кнопкой на экране.",
                listOf("mode" to choice("photo — фото, video — видео", listOf("photo", "video")))
            ))
            put(tool(
                "take_screenshot",
                "Делает скриншот экрана телефона (ассистент сначала сворачивается на рабочий стол)."
            ))
            put(tool(
                "open_app",
                "Открывает приложение по названию: телефон, камера, браузер, настройки, файлы, галерея, контакты, WhatsApp, YouTube, Telegram и любое другое установленное.",
                listOf("name" to text("Название приложения, лучше как оно написано на значке (WhatsApp, YouTube, Telegram)")),
                listOf("name")
            ))
            put(tool(
                "open_browser",
                "Открывает сайт по адресу или ищет в интернете. Без параметров просто открывает браузер.",
                listOf(
                    "url" to text("Адрес сайта, например example.com"),
                    "search_query" to text("Что искать в интернете, если адреса нет")
                )
            ))
            put(tool(
                "open_settings",
                "Открывает нужный раздел настроек телефона. Переключать настройки сама функция не умеет, только открывает экран.",
                listOf(
                    "section" to choice(
                        "Раздел настроек, main — главный экран настроек",
                        listOf("main", "wifi", "bluetooth", "display", "sound", "battery", "apps", "location",
                            "airplane", "storage", "date", "accessibility", "nfc")
                    )
                )
            ))
            put(tool(
                "open_dialer",
                "Открывает набор номера телефона. Звонок пользователь начинает сам кнопкой вызова.",
                listOf("number" to text("Номер телефона, если нужно набрать конкретный"))
            ))
            put(tool(
                "open_whatsapp",
                "Открывает WhatsApp. С номером открывает чат с этим человеком, с текстом подставляет сообщение. Отправляет сообщение сам пользователь.",
                listOf(
                    "phone" to text("Номер телефона в любом формате, например +7 777 123 45 67"),
                    "message" to text("Текст сообщения, который нужно подставить")
                )
            ))

            // --- фото, видео, файлы
            put(tool(
                "find_media",
                "Ищет фото или видео в галерее телефона. Без запроса показывает самые свежие. Может сразу открыть первый найденный файл.",
                listOf(
                    "kind" to choice("photo — фото, video — видео", listOf("photo", "video")),
                    "query" to text("Часть названия файла, если нужно найти конкретный"),
                    "limit" to number("Сколько файлов показать, по умолчанию 5"),
                    "open" to flag("true — открыть первый найденный файл (например, 'покажи последнее фото')")
                ),
                listOf("kind")
            ))
            put(tool(
                "find_files",
                "Ищет файлы и папки в памяти телефона по названию. Без запроса показывает содержимое папки. Может сразу открыть первый найденный файл.",
                listOf(
                    "query" to text("Часть названия файла"),
                    "folder" to text("Папка: Download, Documents, DCIM, Pictures, Movies, Music или своё название. По умолчанию вся память"),
                    "limit" to number("Сколько результатов показать, по умолчанию 10"),
                    "open" to flag("true — открыть первый найденный файл")
                )
            ))
            put(tool(
                "shared_folder",
                "Общая папка Assistant: пользователь кладёт файлы и базы во Входящие, ассистент сохраняет результаты в Исходящие. " +
                    "list — что в папке; read — прочитать файл (txt, csv, json, xlsx, docx; с query только строки со словом; для базы sqlite покажет таблицы); " +
                    "sql — запрос SELECT к базе sqlite (только чтение); write — сохранить текст в Исходящие; " +
                    "merge — объединить таблицы csv/xlsx из names в один csv (dedupe — без повторов); " +
                    "convert — xlsx в csv или docx в txt; " +
                    "ocr — распознать текст или таблицу на картинке (task — что сделать, save_as — имя файла для результата, csv для таблицы).",
                listOf(
                    "action" to choice("Действие", listOf("list", "read", "sql", "write", "merge", "convert", "ocr")),
                    "name" to text("Имя файла (для read, sql, write, convert, ocr; для ocr без имени берётся последняя картинка)"),
                    "names" to list("merge: имена объединяемых файлов"),
                    "output" to text("merge, convert: имя результата"),
                    "dedupe" to flag("merge: true — убрать одинаковые строки"),
                    "query" to text("read: слово или фраза для поиска по строкам файла"),
                    "sql" to text("sql: один запрос SELECT"),
                    "content" to text("write: текст файла"),
                    "append" to flag("write: true — дописать в конец, а не заменить"),
                    "folder" to text("list: Входящие, Исходящие или вложенная папка"),
                    "task" to text("ocr: что сделать с картинкой, например «перепиши весь текст» или «выпиши таблицу в формате csv»"),
                    "save_as" to text("ocr: сохранить результат в Исходящие под этим именем")
                ),
                listOf("action")
            ))

            // --- контакты
            put(tool(
                "call_contact",
                "Звонит контакту из телефонной книги по имени (имя можно в любом падеже: «маме», «Ивану»).",
                listOf("name" to text("Имя контакта")),
                listOf("name")
            ))
            put(tool(
                "message_contact",
                "Готовит сообщение контакту по имени в WhatsApp (по умолчанию) или SMS. Отправляет сам пользователь.",
                listOf(
                    "name" to text("Имя контакта"),
                    "text" to text("Текст сообщения"),
                    "app" to choice("whatsapp или sms", listOf("whatsapp", "sms"))
                ),
                listOf("name")
            ))
            put(tool(
                "find_contact",
                "Показывает номера телефонов контакта по имени.",
                listOf("name" to text("Имя контакта")),
                listOf("name")
            ))

            // --- интернет и автообновление
            put(tool(
                "web_search",
                "Ищет свежую информацию в интернете и отвечает с источниками: новости, погода, курсы валют, цены, результаты матчей, факты, которые могли измениться.",
                listOf("query" to text("Что найти, с уточнениями: город, дата, валюта")),
                listOf("query")
            ))
            put(tool(
                "schedule_digest",
                "Регулярная сводка: каждый день в указанное время ассистент сам ищет свежую информацию по теме и присылает уведомлением.",
                listOf("topic" to text("Тема, например «курс доллара и погода в Булаево»"), "time" to text("Время HH:mm")),
                listOf("topic", "time")
            ))
            put(tool("list_digests", "Показывает регулярные сводки."))
            put(tool(
                "cancel_digest",
                "Отменяет регулярную сводку по части темы; «все» отменяет все.",
                listOf("query" to text("Часть темы или «все»"))
            ))

            // --- переключатели шторки и яркость
            put(tool(
                "quick_setting",
                "Включает или выключает переключатель из шторки: Wi-Fi, Bluetooth, мобильный интернет, режим полёта, не беспокоить, " +
                    "геолокация, точка доступа, NFC, автоповорот, экономия заряда, тёмная тема, без звука и любой другой по названию плитки.",
                listOf(
                    "setting" to text("Название переключателя, например «Wi-Fi» или «режим полёта»"),
                    "state" to choice("on — включить, off — выключить, toggle — переключить", listOf("on", "off", "toggle"))
                ),
                listOf("setting", "state")
            ))
            put(tool(
                "set_brightness",
                "Меняет яркость экрана: percent — точное значение, change — ярче, темнее, максимум, минимум или автояркость.",
                listOf(
                    "percent" to number("Яркость в процентах от 1 до 100"),
                    "change" to choice("up — ярче, down — темнее, max, min, auto — автояркость", listOf("up", "down", "max", "min", "auto"))
                )
            ))

            // --- таймер, напоминания, календарь
            put(tool(
                "set_timer",
                "Запускает таймер обратного отсчёта.",
                listOf("seconds" to number("Длительность в секундах"), "label" to text("Название таймера")),
                listOf("seconds")
            ))
            put(tool(
                "set_reminder",
                "Ставит напоминание с уведомлением. Укажи либо in_minutes (через сколько минут), либо time (и date, если не сегодня).",
                listOf(
                    "text" to text("О чём напомнить"),
                    "in_minutes" to number("Через сколько минут"),
                    "date" to text("Дата yyyy-MM-dd, если не сегодня"),
                    "time" to text("Время HH:mm")
                ),
                listOf("text")
            ))
            put(tool("list_reminders", "Показывает запланированные напоминания."))
            put(tool(
                "cancel_reminder",
                "Отменяет напоминание, в тексте которого есть query; «все» отменяет все.",
                listOf("query" to text("Часть текста напоминания или «все»"))
            ))
            put(tool(
                "calendar_add",
                "Добавляет событие в календарь телефона. Без time событие на весь день.",
                listOf(
                    "title" to text("Название события"),
                    "date" to text("Дата yyyy-MM-dd (вычисли по текущей дате)"),
                    "time" to text("Время начала HH:mm"),
                    "duration_minutes" to number("Длительность в минутах, по умолчанию 60"),
                    "description" to text("Описание")
                ),
                listOf("title", "date")
            ))
            put(tool(
                "calendar_list",
                "Показывает события календаря за день или несколько дней.",
                listOf(
                    "date" to text("Дата yyyy-MM-dd, по умолчанию сегодня"),
                    "days" to number("Сколько дней показать, по умолчанию 1")
                )
            ))
        }
    }

    // ---------------------------------------------------------------- наборы функций для экономии токенов

    private val groups: Map<String, List<String>> = mapOf(
        "alarm" to listOf("set_alarm"),
        "music" to listOf("play_music", "music_control", "set_volume", "playlist", "music_library", "music_tags", "music_rate"),
        "notes" to listOf("create_note", "list_notes", "search_notes", "delete_note"),
        "phone" to listOf(
            "flashlight", "open_camera", "take_screenshot", "open_app", "open_browser",
            "open_settings", "open_dialer", "open_whatsapp"
        ),
        "files" to listOf("find_media", "find_files"),
        "shared" to listOf("shared_folder"),
        "contacts" to listOf("call_contact", "message_contact", "find_contact"),
        "time" to listOf("set_timer", "set_reminder", "list_reminders", "cancel_reminder"),
        "calendar" to listOf("calendar_add", "calendar_list"),
        "web" to listOf("web_search", "schedule_digest", "list_digests", "cancel_digest"),
        "system" to listOf("quick_setting", "set_brightness")
    )

    /** Признаки (начала слов) в тексте, по которым понятно, какие функции могут понадобиться. */
    private val groupKeys: Map<String, List<String>> = mapOf(
        "alarm" to listOf("будильник", "разбуд", "заведи"),
        "music" to listOf(
            "музык", "песн", "трек", "плейлист", "громк", "тише", "громче", "потише", "погромче", "пауз",
            "дальше", "следующ", "предыдущ", "играй", "альбом", "исполнител", "групп", "звук", "артист",
            "настроени", "жанр", "разметк", "размет", "импорт", "таблиц", "другого ии", "другой ии", "энергичн", "спокойн", "весел", "грустн",
            "лайк", "дизлайк", "нравит", "понравил", "любим", "папк", "коллекц", "эту песню", "эта песня"
        ),
        "notes" to listOf("заметк", "запомн", "запиш", "сохран", "удал"),
        "phone" to listOf(
            "фонар", "камер", "скрин", "открой", "откр", "запусти", "запуск", "настройк", "wifi", "вайфай",
            "блютуз", "bluetooth", "браузер", "сайт", "интернет", "гугл", "ватсап", "whatsapp", "вацап",
            "позвони", "набери", "номер", "приложени", "сообщени", "напиши", "отправ", "фото", "видео"
        ),
        "files" to listOf(
            "файл", "папк", "загрузк", "документ", "фото", "видео", "галере", "снимок", "скачан", "картинк", "изображен"
        ),
        "shared" to listOf(
            "файл", "папк", "баз", "таблиц", "csv", "sqlite", "входящ", "исходящ", "прочита", "строк", "экспорт", "excel", "json",
            "xlsx", "docx", "объедин", "склей", "сведи", "распозна", "картинк", "изображен", "фото", "скан", "конвертир", "переведи в"
        ),
        "contacts" to listOf(
            "позвони", "звон", "контакт", "напиши", "сообщени", "смс", "sms", "ватсап", "whatsapp", "вацап", "номер", "телефон"
        ),
        "time" to listOf("таймер", "напомни", "напомин", "отмени", "отмен"),
        "calendar" to listOf("календар", "встреч", "событи", "запланир", "расписан", "завтра", "послезавтра", "недел"),
        "web" to listOf(
            "интернет", "новост", "погод", "курс", "свеж", "актуальн", "поищи", "найди", "загугли", "сводк", "присылай",
            "каждое", "каждый", "цена", "стоит", "последн", "матч", "доллар", "евро", "тенге", "температур", "узнай"
        ),
        "system" to listOf(
            "вайфай", "wifi", "wi", "блютуз", "bluetooth", "яркост", "ярче", "темнее", "авиарежим", "полет", "полёт",
            "геолокац", "данные", "точка", "модем", "нфс", "nfc", "поворот", "энергосбереж", "беспокоить", "шторк",
            "включи", "выключи", "отключи"
        )
    )

    private val byName: Map<String, JSONObject> by lazy {
        val map = LinkedHashMap<String, JSONObject>()
        for (i in 0 until all.length()) {
            val tool = all.getJSONObject(i)
            map[tool.getJSONObject("function").getString("name")] = tool
        }
        map
    }

    /** Какие группы функций упоминаются в текстах (текущая фраза и недавний контекст). */
    fun groupsFor(texts: List<String>): Set<String> {
        val words = texts.flatMap { TextMatch.normalize(it).split(' ') }.filter { it.isNotEmpty() }
        return groupKeys.filter { (_, keys) -> words.any { w -> keys.any { w.startsWith(it) } } }.keys
    }

    /** Только функции из указанных групп (с полными описаниями). */
    fun subset(groupNames: Set<String>): JSONArray {
        val names = groupNames.flatMap { groups[it].orEmpty() }.toSet()
        return JSONArray().apply { byName.forEach { (name, tool) -> if (name in names) put(tool) } }
    }

    /** Все функции в сжатом виде: первое предложение описания и без описаний параметров. */
    val compact: JSONArray by lazy {
        JSONArray().apply {
            for (tool in byName.values) {
                val function = tool.getJSONObject("function")
                val params = function.getJSONObject("parameters")
                val props = JSONObject()
                val source = params.getJSONObject("properties")
                for (key in source.keys()) {
                    val original = source.getJSONObject(key)
                    props.put(key, JSONObject().apply {
                        put("type", original.getString("type"))
                        if (original.has("enum")) put("enum", original.getJSONArray("enum"))
                        if (original.has("items")) put("items", original.getJSONObject("items"))
                    })
                }
                put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", function.getString("name"))
                        put("description", function.getString("description").substringBefore(". ").take(120))
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", props)
                            put("required", params.getJSONArray("required"))
                        })
                    })
                })
            }
        }
    }
}
