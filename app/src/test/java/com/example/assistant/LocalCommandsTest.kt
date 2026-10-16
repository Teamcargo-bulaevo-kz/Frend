package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LocalCommandsTest {

    /** Фиксированное «сейчас»: 14:35. */
    private val now: Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 14)
        set(Calendar.MINUTE, 35)
    }

    private fun cmd(text: String): LocalCommand? = LocalCommands.parse(text, now)

    private fun assertCmd(text: String, name: String, vararg args: Pair<String, Any?>) {
        val c = cmd(text)
        assertNotNull("«$text» должна разбираться правилами", c)
        assertEquals("«$text»: функция", name, c!!.name)
        assertEquals("«$text»: аргументы", args.toMap(), c.args)
    }

    private fun assertNone(text: String) {
        assertNull("«$text» должна уйти к модели, а правила дали ${cmd(text)}", cmd(text))
    }

    private fun alarm(text: String, hour: Int, minute: Int) =
        assertCmd(text, "set_alarm", "hour" to hour, "minute" to minute)

    // ---------------------------------------------------------------- будильник

    @Test fun alarmAbsolute() {
        alarm("разбуди в 7", 7, 0)
        alarm("Поставь будильник на 7:30", 7, 30)
        alarm("разбуди меня в семь тридцать утра", 7, 30)
        alarm("разбуди в 7 вечера", 19, 0)
        alarm("будильник на 12 ночи", 0, 0)
        alarm("поставь будильник на 2 дня", 14, 0)
        alarm("будильник на 8 часов утра", 8, 0)
        alarm("разбуди в 19 45", 19, 45)
        alarm("поставь будильник на 6 утра", 6, 0)
        alarm("заведи будильник на 5 15", 5, 15)
    }

    @Test fun alarmRelative() {
        alarm("разбуди через полчаса", 15, 5)
        alarm("поставь будильник через 2 часа 15 минут", 16, 50)
        alarm("поставь будильник на 5 минут", 14, 40)
        alarm("будильник через час", 15, 35)
        alarm("разбуди через 10 минут", 14, 45)
        alarm("разбуди через полтора часа", 16, 5)
    }

    @Test fun alarmNotHandled() {
        assertNone("удали будильник")
        assertNone("отмени будильник на 7")
        assertNone("разбуди меня")
    }

    // ---------------------------------------------------------------- устройство

    @Test fun flashlight() {
        assertCmd("включи фонарик", "flashlight", "on" to true)
        assertCmd("Включи фонарь", "flashlight", "on" to true)
        assertCmd("выключи фонарик", "flashlight", "on" to false)
        assertNone("фонарик")
        assertNone("что такое фонарик")
        assertNone("я включил фонарик вчера")
    }

    @Test fun screenshotAndCamera() {
        assertCmd("сделай скриншот", "take_screenshot")
        assertCmd("сделай снимок экрана", "take_screenshot")
        assertCmd("сделай фото", "open_camera", "mode" to "photo")
        assertCmd("сними видео", "open_camera", "mode" to "video")
        assertCmd("запиши видео", "open_camera", "mode" to "video")
        assertCmd("открой камеру", "open_camera", "mode" to "photo")
        assertCmd("включи камеру", "open_camera", "mode" to "photo")
    }

    @Test fun volume() {
        assertCmd("громче", "set_volume", "action" to "up")
        assertCmd("потише", "set_volume", "action" to "down")
        assertCmd("сделай погромче", "set_volume", "action" to "up")
        assertCmd("убавь громкость", "set_volume", "action" to "down")
        assertCmd("громкость на 50", "set_volume", "action" to "set", "percent" to 50)
        assertCmd("громкость 70", "set_volume", "action" to "set", "percent" to 70)
        assertCmd("без звука", "set_volume", "action" to "mute")
        assertCmd("выключи звук", "set_volume", "action" to "mute")
        assertCmd("поставь звук на максимум", "set_volume", "action" to "max")
        assertCmd("прибавь звук на 20", "set_volume", "action" to "up", "percent" to 20)
    }

    // ---------------------------------------------------------------- музыка

    @Test fun playerControl() {
        assertCmd("пауза", "music_control", "action" to "pause")
        assertCmd("поставь на паузу", "music_control", "action" to "pause")
        assertCmd("дальше", "music_control", "action" to "next")
        assertCmd("следующий трек", "music_control", "action" to "next")
        assertCmd("включи следующую песню", "music_control", "action" to "next")
        assertCmd("предыдущая песня", "music_control", "action" to "previous")
        assertCmd("назад", "music_control", "action" to "previous")
        assertCmd("стоп", "music_control", "action" to "stop")
        assertCmd("останови музыку", "music_control", "action" to "stop")
        assertCmd("выключи музыку", "music_control", "action" to "stop")
        assertCmd("продолжи", "music_control", "action" to "play")
        assertNone("выключи")
        assertNone("дальше расскажи")
    }

    @Test fun playMusic() {
        assertCmd("включи музыку", "play_music", "query" to "", "shuffle" to true)
        assertCmd("поставь что-нибудь", "play_music", "query" to "", "shuffle" to true)
        assertCmd("включи песню волны", "play_music", "query" to "волны", "shuffle" to false)
        assertCmd("включи исполнителя баста", "play_music", "query" to "баста", "shuffle" to false)
        assertCmd("включи плейлист для бега", "playlist", "action" to "play", "name" to "для бега", "shuffle" to false)
        assertCmd("включи плейлист для бега вперемешку", "playlist", "action" to "play", "name" to "для бега", "shuffle" to true)
        assertCmd("какие у меня плейлисты", "playlist", "action" to "list")
        assertCmd("какая у меня музыка", "music_library")
    }

    @Test fun musicCandidate() {
        assertEquals("imagine dragons", LocalCommands.musicCandidate("Включи Imagine Dragons"))
        assertEquals("баста", LocalCommands.musicCandidate("поставь мне баста"))
        assertNull(LocalCommands.musicCandidate("включи свет"))
        assertNull(LocalCommands.musicCandidate("включи фонарик"))
        assertNull(LocalCommands.musicCandidate("привет"))
    }

    // ---------------------------------------------------------------- приложения, сайты, звонки

    @Test fun settings() {
        assertCmd("открой настройки wifi", "open_settings", "section" to "wifi")
        assertCmd("открой настройки", "open_settings", "section" to "main")
        assertCmd("открой вайфай", "open_settings", "section" to "wifi")
        assertCmd("открой настройки блютуз", "open_settings", "section" to "bluetooth")
        assertCmd("открой настройки экрана", "open_settings", "section" to "display")
    }

    @Test fun webAndDial() {
        assertCmd("открой youtube.com", "open_browser", "url" to "youtube.com")
        assertCmd("открой сайт example.com", "open_browser", "url" to "example.com")
        assertCmd("загугли погоду в астане", "open_browser", "search_query" to "погоду в астане")
        assertCmd("найди в интернете рецепт борща", "open_browser", "search_query" to "рецепт борща")
        assertNone("найди рецепт борща")
        assertCmd("позвони 8 777 123 45 67", "open_dialer", "number" to "87771234567")
        assertCmd("набери +7 777 123 45 67", "open_dialer", "number" to "+77771234567")
        assertNone("позвони маме")
    }

    @Test fun openApp() {
        assertCmd("открой ватсап", "open_app", "name" to "ватсап")
        assertCmd("запусти ютуб", "open_app", "name" to "ютуб")
        assertCmd("зайди в телеграм", "open_app", "name" to "телеграм")
        assertCmd("открой телефон", "open_app", "name" to "телефон")
        assertCmd("открой файлы", "open_app", "name" to "файлы")
        assertNone("открой последнее фото")
        assertNone("открой файл отчет")
        assertNone("открой сайт гугл")
    }

    // ---------------------------------------------------------------- заметки, время, прочее

    @Test fun notes() {
        assertCmd("запомни два по 78", "create_note", "text" to "два по 78")
        assertCmd("Запомни что завтра встреча в 10", "create_note", "text" to "завтра встреча в 10")
        assertCmd(
            "уплотнитель 5 м степлер и молоко концентрированное в железной банке запомни",
            "create_note",
            "text" to "уплотнитель 5 м степлер и молоко концентрированное в железной банке"
        )
        assertCmd("покажи заметки", "list_notes")
        assertNone("запомни")
    }

    @Test fun timeAndDate() {
        assertCmd("который час", "say", "text" to "Сейчас 14:35")
        val date = cmd("какое сегодня число")
        assertNotNull(date)
        assertEquals("say", date!!.name)
        assertTrue((date.args["text"] as String).startsWith("Сегодня "))
    }

    @Test fun everythingElseGoesToModel() {
        assertNone("расскажи анекдот")
        assertNone("привет")
        assertNone("сколько будет 78 плюс 180 на 2")
        assertNone("516 см это сколько метров")
        assertNone("включи фонарик и поставь будильник на 7")
        assertNone("")
    }

    // ---------------------------------------------------------------- общая папка

    @Test fun sharedFolderList() {
        assertCmd("что во входящих", "shared_folder", "action" to "list", "folder" to "Входящие")
        assertCmd("покажи входящие", "shared_folder", "action" to "list", "folder" to "Входящие")
        assertCmd("что в исходящих", "shared_folder", "action" to "list", "folder" to "Исходящие")
        assertCmd("покажи общую папку", "shared_folder", "action" to "list", "folder" to "все")
    }

    @Test fun sharedFolderReadGoesToModel() {
        assertNone("прочитай файл clients.csv")
        assertNone("найди в базе Иванова")
        assertNone("открой clients.csv")
    }
}
