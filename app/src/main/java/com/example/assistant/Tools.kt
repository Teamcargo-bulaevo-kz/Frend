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
}
