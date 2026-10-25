package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportTest {

    @Test
    fun bytesAndDuration() {
        assertEquals("300 МБ", DeviceReport.bytes(300L * 1024 * 1024))
        assertEquals("1,5 ГБ", DeviceReport.bytes(1536L * 1024 * 1024))
        assertEquals("12 ГБ", DeviceReport.bytes(12L * 1024 * 1024 * 1024))
        assertEquals("1 ч 35 мин", DeviceReport.duration(95))
        assertEquals("40 мин", DeviceReport.duration(40))
        assertEquals("2 ч", DeviceReport.duration(120))
    }

    @Test
    fun batteryTexts() {
        val charging = DeviceReport.battery(BatteryInfo(40, true, "usb", false, 31.5, "good", minutesToFull = 95))
        assertTrue(charging, charging.startsWith("Заряд 40%, заряжается от USB, до полного около 1 ч 35 мин"))
        assertTrue(DeviceReport.battery(BatteryInfo(100, false, "ac", true, null, null)).contains("полностью заряжен"))
        assertTrue(DeviceReport.battery(BatteryInfo(20, false, null, false, 30.0, "good")).contains("не заряжается"))
        assertTrue(DeviceReport.battery(BatteryInfo(50, false, null, false, 46.0, "overheat")).contains("перегрета"))
    }

    @Test
    fun networkTexts() {
        assertTrue(DeviceReport.network(NetworkInfo(false, null, false, false, null, null, null, null, airplane = true)).contains("режим полёта"))
        assertTrue(DeviceReport.network(NetworkInfo(false, null, false, false, null, null, null, null)).contains("Сети нет"))
        val wifi = DeviceReport.network(NetworkInfo(true, "wifi", true, false, 100, 50, 3, 433))
        assertTrue(wifi, wifi.contains("Wi-Fi") && wifi.contains("хороший") && wifi.contains("433") && wifi.contains("Интернет работает"))
        val dead = DeviceReport.network(NetworkInfo(true, "mobile", false, true, 20, 5, null, null))
        assertTrue(dead, dead.contains("интернет не отвечает") && dead.contains("платный"))
    }

    @Test
    fun memoryAndStorage() {
        val gb = 1024L * 1024 * 1024
        val ram = DeviceReport.memory(MemoryInfoData(6 * gb, 2 * gb, false))
        assertTrue(ram, ram.contains("занято 4,0 ГБ из 6,0 ГБ (66%)"))
        assertTrue(DeviceReport.memory(MemoryInfoData(6 * gb, gb / 10, true)).contains("Памяти мало"))
        assertTrue(DeviceReport.storage(StorageInfoData(100 * gb, 5 * gb)).contains("Места мало"))
        assertTrue(DeviceReport.storage(StorageInfoData(100 * gb, 50 * gb)).contains("(50%)"))
    }

    @Test
    fun appLists() {
        val mb = 1024L * 1024
        val apps = listOf(AppStat("A", "a", bytes = 100 * mb), AppStat("B", "b", bytes = 2048 * mb), AppStat("C", "c", bytes = 5 * mb))
        val text = DeviceReport.appsByStorage(apps, 2)
        assertTrue(text, text.contains("1. B — 2,0 ГБ") && text.contains("2. A — 100 МБ") && !text.contains("C —"))
        val usage = DeviceReport.appsByUsage(listOf(AppStat("YouTube", "y", minutes = 80), AppStat("Chat", "c", minutes = 20), AppStat("Zero", "z", minutes = 0)))
        assertTrue(usage, usage.contains("1. YouTube — 1 ч 20 мин") && usage.contains("Всего в этих приложениях: 1 ч 40 мин") && !usage.contains("Zero"))
        assertTrue(DeviceReport.appsByUsage(emptyList()).contains("данных"))
    }

    @Test
    fun meminfoParser() {
        val dump = """
            Applications Memory Usage (in Kilobytes):
            Total PSS by process:
                410,324K: com.telegram.messenger (pid 4321 / activities)
                1,201,000K: system (pid 1234)
                  90,100K: com.android.phone (pid 900)
            Total PSS by OOM adjustment:
                 100K: Native
        """.trimIndent()
        val list = ProcessReport.parseMeminfo(dump)
        assertEquals(3, list.size)
        assertEquals("system", list[0].name)
        assertEquals(1201000L, list[0].kb)
        assertEquals(4321, list[1].pid)
        val text = ProcessReport.format(list, mapOf("com.telegram.messenger" to "Telegram"), 2)
        assertTrue(text, text.contains("1. system — 1,1 ГБ") && text.contains("2. Telegram — 401 МБ"))
        assertTrue(ProcessReport.parseMeminfo("мусор").isEmpty())
    }

    @Test
    fun localCommandsRouteToDeviceInfo() {
        fun section(s: String) = LocalCommands.parse(s)?.takeIf { it.name == "device_info" }?.args?.get("section")
        assertEquals("battery", section("сколько заряда"))
        assertEquals("battery", section("какой заряд батареи"))
        assertEquals("network", section("какая сеть"))
        assertEquals("network", section("работает интернет"))
        assertEquals("memory", section("сколько оперативной памяти"))
        assertEquals("memory_all", section("сколько свободной памяти"))
        assertEquals("apps_storage", section("что занимает память"))
        assertEquals("apps_storage", section("какие приложения занимают место"))
        assertEquals("apps_usage", section("какими приложениями я больше пользуюсь"))
        assertEquals("processes", section("покажи процессы"))
        assertEquals("all", section("какое состояние телефона"))
        // управление телефоном сюда не попадает
        assertNull(section("включи экономию заряда"))
        assertNull(section("открой настройки батареи"))
        assertNull(section("включи вайфай"))
        assertNotNull(LocalCommands.parse("включи вайфай"))
    }
}
