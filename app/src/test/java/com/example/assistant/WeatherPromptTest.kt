package com.example.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPromptTest {

    private fun forecast(hi: Double = 3.0, lo: Double = -4.0, cur: Double = -1.0) = JSONObject(
        """{"current":{"temperature_2m":$cur,"apparent_temperature":-6.0,"weather_code":3,"wind_speed_10m":5.2},
        "daily":{"time":["2026-10-20","2026-10-21","2026-10-22"],
        "temperature_2m_max":[$hi,5.0,6.0],"temperature_2m_min":[$lo,-2.0,-1.0],
        "weather_code":[3,61,0],"precipitation_probability_max":[10,70,0],"wind_speed_10m_max":[6.0,9.4,3.0]}}"""
    )

    @Test fun queryParsing() {
        assertEquals(Weather.Query(null, 0, false), Weather.parseQuery("какая погода"))
        assertEquals(Weather.Query(null, 1, false), Weather.parseQuery("какая погода завтра"))
        assertEquals(Weather.Query("петропавловске", 0, false), Weather.parseQuery("погода в Петропавловске сегодня"))
        assertEquals(Weather.Query("астане", 2, false), Weather.parseQuery("погода в Астане послезавтра"))
        assertTrue(Weather.parseQuery("погода на неделю").week)
        assertEquals("москва", Weather.parseQuery("погода москва").city)
        assertTrue(Weather.isWeatherQuery("Какая температура на улице"))
        assertFalse(Weather.isWeatherQuery("курс доллара"))
    }

    @Test fun cityEndings() {
        assertEquals(listOf("петропавловске", "петропавловск", "петропавловс"), Weather.cityVariants("петропавловске"))
    }

    @Test fun todayReport() {
        val t = Weather.format("Булаево", forecast(), Weather.Query(null, 0, false))!!
        assertTrue(t, t.startsWith("Булаево, сегодня: от -4° до 3°, пасмурно"))
        assertTrue(t, t.contains("Сейчас -1°") && t.contains("ощущается как -6°") && t.contains("ветер 5 м/с"))
    }

    @Test fun tomorrowReport() {
        val t = Weather.format("Булаево", forecast(), Weather.Query(null, 1, false))!!
        assertTrue(t, t.contains("завтра") && t.contains("небольшой дождь") && t.contains("осадки 70%") && t.contains("ветер до 9 м/с"))
    }

    @Test fun weekReport() {
        val t = Weather.format("Булаево", forecast(), Weather.Query(null, 0, true))!!
        assertTrue(t, t.contains("вт:") && t.contains("ср:"))
    }

    @Test fun absurdTemperatureIsRejected() {
        assertNull(Weather.format("Булаево", forecast(hi = 65.0), Weather.Query(null, 0, false)))
        assertNull(Weather.format("Булаево", forecast(cur = 99.0), Weather.Query(null, 0, false)))
    }

    @Test fun sanityCheck() {
        assertTrue(Sanity.implausibleTemperature("Погода в Булаево: 65 градусов по Цельсию"))
        assertTrue(Sanity.implausibleTemperature("На улице +72°C"))
        assertFalse(Sanity.implausibleTemperature("На улице -25°, ветер 7 м/с"))
        assertFalse(Sanity.implausibleTemperature("Запекай при 180 градусах 40 минут"))
    }

    @Test fun promptIsDetailedButCompact() {
        val p = AssistantPrompt.build("20.10.2026 10:00, вторник", "Asia/Oral")
        assertTrue(p, p.contains("20.10.2026") && p.contains("Asia/Oral"))
        for (word in listOf("СТИЛЬ", "ДЕЙСТВИЯ", "ФАКТЫ", "ПРОВЕРЬ СЕБЯ", "ГРАНИЦЫ", "Булаево")) assertTrue(word, p.contains(word))
        assertTrue("слишком длинный: ${p.length}", p.length < 3200)
    }
}
