package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PhoneToolsTest {

    private val own = "com.example.assistant"

    @Test fun controlCommandsAllowed() {
        listOf(
            "svc wifi enable", "svc bluetooth disable", "svc data enable", "cmd connectivity airplane-mode enable",
            "cmd location set-location-enabled true", "cmd notification set_dnd priority",
            "pm grant $own android.permission.READ_CONTACTS", "appops set $own MANAGE_EXTERNAL_STORAGE allow",
            "settings put secure enabled_accessibility_services $own/$own.AssistantAccessibilityService",
            "settings get secure enabled_accessibility_services", "cmd deviceidle whitelist +$own",
            "cmd notification allow_listener $own/$own.NotificationReader", "am force-stop org.telegram.messenger",
            "input keyevent 26"
        ).forEach { assertTrue(it, ShellPolicy.isControl(it, own)) }
    }

    @Test fun dangerousControlRejected() {
        listOf(
            "pm grant com.other.app android.permission.READ_CONTACTS", "appops set com.other.app GET_USAGE_STATS allow",
            "pm uninstall com.x", "pm clear com.x", "rm -rf /sdcard", "settings put secure android_id 1",
            "am force-stop android", "am force-stop com.android.systemui", "am force-stop $own",
            "svc wifi enable; reboot", "reboot", "cmd package uninstall com.x", "settings put global adb_enabled 1"
        ).forEach { assertFalse(it, ShellPolicy.isControl(it, own)) }
        // Чтение по-прежнему не пускает команды управления
        assertFalse(ShellPolicy.isAllowed("svc wifi enable"))
        assertTrue(ShellPolicy.isAllowed("cat /sys/class/power_supply/battery/cycle_count"))
    }

    @Test fun toggleCommands() {
        assertEquals("svc wifi enable", ShizukuPower.toggleCommands("wifi", true)!!.first())
        assertEquals("svc data disable", ShizukuPower.toggleCommands("mobile_data", false)!!.first())
        assertNull(ShizukuPower.toggleCommands("hotspot", true))
        // все предлагаемые команды проходят политику
        for (k in listOf("wifi", "bluetooth", "mobile_data", "nfc", "airplane", "location", "dnd")) for (on in listOf(true, false))
            ShizukuPower.toggleCommands(k, on)!!.forEach { assertTrue(it, ShellPolicy.isControl(it, own)) }
    }

    @Test fun fileSortPlan() {
        val now = 1_800_000_000_000L
        val old = now - 3_600_000L
        val files = listOf(
            FileEntry("отчёт.pdf", 10, old), FileEntry("фото.JPG", 10, old), FileEntry("song.mp3", 10, old),
            FileEntry("app.apk", 10, old), FileEntry("data.xlsx", 10, old), FileEntry("x.unknown", 10, old),
            FileEntry("film.mp4.crdownload", 10, old), FileEntry(".hidden", 10, old), FileEntry("fresh.pdf", 10, now - 10_000)
        )
        val plan = FileSort.plan(files, FileSort.Mode.TYPE, now).associate { it.name to it.folder }
        assertEquals("PDF", plan["отчёт.pdf"])
        assertEquals("Картинки", plan["фото.JPG"])
        assertEquals("Музыка", plan["song.mp3"])
        assertEquals("Установщики", plan["app.apk"])
        assertEquals("Таблицы", plan["data.xlsx"])
        assertEquals(FileSort.OTHER, plan["x.unknown"])
        assertFalse("film.mp4.crdownload" in plan)
        assertFalse(".hidden" in plan)
        assertFalse("fresh.pdf" in plan)
        val c = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 5) }
        assertEquals("2026-03 март", FileSort.monthFolder(c.timeInMillis))
        assertTrue(FileSort.summary(FileSort.plan(files, FileSort.Mode.TYPE, now)).startsWith("6 файлов"))
    }

    @Test fun freeNameNeverOverwrites() {
        val existing = setOf("a.pdf", "a (2).pdf")
        assertEquals("a (3).pdf", FileSort.freeName("a.pdf") { it in existing })
        assertEquals("b.pdf", FileSort.freeName("b.pdf") { it in existing })
        assertEquals("README (2)", FileSort.freeName("README") { it == "README" })
    }

    @Test fun translateBatch() {
        assertTrue(TranslateBatch.needsTranslation("Settings", "русский"))
        assertFalse(TranslateBatch.needsTranslation("Настройки", "русский"))
        assertFalse(TranslateBatch.needsTranslation("12:45", "русский"))
        assertTrue(TranslateBatch.needsTranslation("Настройки", "английский"))
        assertEquals(listOf("Настройки", "Выход"), TranslateBatch.parse("Вот: [\"Настройки\", \"Выход\"]", 2))
        assertNull(TranslateBatch.parse("[\"один\"]", 2))
        assertEquals("английский", TranslateBatch.targetFrom("переведи экран на английский"))
        assertEquals("русский", TranslateBatch.targetFrom("переведи экран"))
        assertEquals(2, TranslateBatch.chunks(List(70) { "x" }).size)
    }

    @Test fun batteryWear() {
        assertTrue(BatteryWear.describe(312, 3_900_000, 5_000_000).contains("78%"))
        assertTrue(BatteryWear.describe(312, 3_900_000, 5_000_000).contains("циклов зарядки: 312"))
        assertEquals("Телефон не отдаёт данные об износе батареи.", BatteryWear.describe(null, null, null))
    }

    @Test fun notificationsFilterAndFormat() {
        val now = 1_800_000_000_000L
        val items = listOf(
            NoticeItem("1", "Telegram", "org.telegram.messenger", "Аня", "Привет!", now - 120_000),
            NoticeItem("2", "WhatsApp", "com.whatsapp", "Мама", "Позвони", now - 30_000)
        )
        assertEquals(listOf("1"), NoticeText.filter(items, "телеграме").map { it.key })
        assertEquals(listOf("2"), NoticeText.filter(items, "ватсап").map { it.key })
        val text = NoticeText.format(items, null, now = now)
        assertTrue(text, text.contains("Telegram: Аня — Привет!") && text.contains("2 мин назад"))
        assertEquals("Новых уведомлений нет.", NoticeText.format(emptyList(), null))
    }

    @Test fun screenQuestions() {
        assertTrue(ScreenAssist.aboutScreen("что тут написано"))
        assertTrue(ScreenAssist.aboutScreen("помоги ответить на это сообщение"))
        assertFalse(ScreenAssist.aboutScreen("поставь будильник на семь"))
    }
}
