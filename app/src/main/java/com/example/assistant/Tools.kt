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
                    "shuffle" to flag("true — играть вперемешку")
                )
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
                "Плейлисты. create — создать из списка запросов (названия треков или исполнители), add — добавить треки, " +
                    "play — включить плейлист, show — показать треки, list — показать все плейлисты, delete — удалить.",
                listOf(
                    "action" to choice("Что сделать", listOf("create", "add", "play", "show", "list", "delete")),
                    "name" to text("Название плейлиста"),
                    "queries" to list("Для create и add: названия треков или исполнители, которые нужно найти на телефоне"),
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
        }
    }

    // ---------------------------------------------------------------- наборы функций для экономии токенов

    private val groups: Map<String, List<String>> = mapOf(
        "alarm" to listOf("set_alarm"),
        "music" to listOf("play_music", "music_control", "set_volume", "playlist", "music_library"),
        "notes" to listOf("create_note", "list_notes", "search_notes", "delete_note"),
        "phone" to listOf(
            "flashlight", "open_camera", "take_screenshot", "open_app", "open_browser",
            "open_settings", "open_dialer", "open_whatsapp"
        ),
        "files" to listOf("find_media", "find_files")
    )

    /** Признаки (начала слов) в тексте, по которым понятно, какие функции могут понадобиться. */
    private val groupKeys: Map<String, List<String>> = mapOf(
        "alarm" to listOf("будильник", "разбуд", "заведи"),
        "music" to listOf(
            "музык", "песн", "трек", "плейлист", "громк", "тише", "громче", "потише", "погромче", "пауз",
            "дальше", "следующ", "предыдущ", "играй", "альбом", "исполнител", "групп", "звук", "артист"
        ),
        "notes" to listOf("заметк", "запомн", "запиш", "сохран", "удал"),
        "phone" to listOf(
            "фонар", "камер", "скрин", "открой", "откр", "запусти", "запуск", "настройк", "wifi", "вайфай",
            "блютуз", "bluetooth", "браузер", "сайт", "интернет", "гугл", "ватсап", "whatsapp", "вацап",
            "позвони", "набери", "номер", "приложени", "сообщени", "напиши", "отправ", "фото", "видео"
        ),
        "files" to listOf(
            "файл", "папк", "загрузк", "документ", "фото", "видео", "галере", "снимок", "скачан", "картинк", "изображен"
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
