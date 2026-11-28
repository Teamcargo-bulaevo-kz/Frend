package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BatteryUsageTest {

    @Test fun uids() {
        assertEquals(10234, BatteryUsage.uidOf("u0a234"))
        assertEquals(1000, BatteryUsage.uidOf("1000"))
    }

    @Test fun checkinFormat() {
        val out = "9,0,i,vers,36\n9,10234,l,pwi,uid,45.6,0,0,0\n9,10111,l,pwi,uid,12.0,0\n9,1000,l,pwi,uid,3.5\n9,0,l,pwi,scrn,80.1\n"
        val items = BatteryUsage.parse(out)
        assertEquals(listOf(10234, 10111, 1000), items.map { it.uid })
        assertEquals(45.6, items.first().mah, 0.01)
    }

    @Test fun textFormat() {
        val out = "Statistics since last charge:\n  Estimated power use (mAh):\n    Capacity: 5000, Computed drain: 900\n    Screen: 200\n    UID u0a234: 120.5 ( cpu=50 wifi=10 )\n    UID 1000: 30.2 ( cpu=30 )\n    Uid u0a99: 0.01\n"
        val items = BatteryUsage.parse(out)
        assertEquals(listOf(10234, 1000), items.map { it.uid })
        val text = BatteryUsage.format(listOf("Telegram" to 120.5, "Система Android" to 30.2))
        assertTrue(text, text.contains("1. Telegram — 121 мА·ч (80%)"))
    }

    @Test fun autoVisualForLists() {
        val answer = "Больше всего оперативной памяти занимают:\n1. Система Android — 239 МБ\n2. WhatsApp Business — 233 МБ\n3. Рабочий стол MIUI — 190 МБ\n4. Игра — 1,2 ГБ"
        val block = AutoVisual.forText(answer)
        val v = VisualSpec.parse(block.substringAfter("```visual\n").substringBefore("\n```")) as Visual.Chart
        assertEquals(4, v.labels.size)
        assertEquals(1228.8, v.series[0].values[3], 0.1)
        assertEquals("", AutoVisual.forText("Просто ответ без списка."))
        assertEquals("", AutoVisual.forText(answer + "\n```visual\n{}\n```"))
    }

    @Test fun phrases() {
        val now = Calendar.getInstance()
        for (t in listOf("какие приложения жрут батарею", "кто тратит заряд", "расход батареи по приложениям", "что съедает заряд"))
            assertEquals(t, "battery_apps", LocalCommands.parse(t, now)?.args?.get("section"))
        assertEquals("battery", LocalCommands.parse("сколько заряда", now)?.args?.get("section"))
    }
}
