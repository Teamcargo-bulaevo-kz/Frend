package com.example.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Погода без ИИ: бесплатный прогноз Open-Meteo (ключ не нужен). Быстро, не тратит токены и не выдумывает цифры.
 * Если сеть или город не нашлись, вызывающий код переходит на обычный поиск в интернете.
 */
object Weather {

    data class Query(val city: String?, val dayOffset: Int, val week: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Булаево по умолчанию
    private const val HOME_NAME = "Булаево"
    private const val HOME_LAT = 54.9
    private const val HOME_LON = 70.45

    private val stop = setOf(
        "сегодня", "завтра", "послезавтра", "сейчас", "на", "неделю", "недели", "выходные", "ближайшие", "дни", "день",
        "утром", "вечером", "ночью", "днем", "будет", "какая", "какой", "что", "там", "мне", "скажи", "узнай",
        "пожалуйста", "погода", "погоду", "погоды", "прогноз", "температура", "температуру", "градусов", "за", "окном",
        "на", "улице", "а", "и", "ли", "есть", "дождь", "снег", "ветер"
    )

    fun isWeatherQuery(query: String): Boolean {
        val n = TextMatch.normalize(query)
        return (n.contains("погод") || n.contains("прогноз") || n.contains("температур")) && !n.contains("курс")
    }

    fun parseQuery(query: String): Query {
        val tokens = TextMatch.normalize(query).split(' ').filter { it.isNotEmpty() }
        val offset = when {
            "послезавтра" in tokens -> 2
            "завтра" in tokens -> 1
            else -> 0
        }
        val week = tokens.any { it.startsWith("недел") || it == "7" || it.startsWith("выходн") }
        val prep = tokens.indexOfFirst { it == "в" || it == "во" || it == "для" || it == "по" }
        val cityTokens = if (prep >= 0) {
            tokens.drop(prep + 1).takeWhile { it !in stop }
        } else {
            tokens.filter { it !in stop }.take(2)
        }.take(2)
        val city = cityTokens.joinToString(" ").ifBlank { null }
        return Query(city, offset, week)
    }

    /** «Петропавловске» → «Петропавловск»: геокодер не знает падежей, пробуем укорачивать окончание. */
    internal fun cityVariants(city: String): List<String> =
        listOf(city, city.dropLast(1), city.dropLast(2)).filter { it.length >= 3 }.distinct()

    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "в основном ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55, 56, 57 -> "морось"
        61 -> "небольшой дождь"
        63 -> "дождь"
        65 -> "сильный дождь"
        66, 67 -> "ледяной дождь"
        71 -> "небольшой снег"
        73 -> "снег"
        75 -> "сильный снег"
        77 -> "снежная крупа"
        80, 81 -> "ливень"
        82 -> "сильный ливень"
        85, 86 -> "снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "без данных"
    }

    private fun deg(v: Double) = "${Math.round(v)}°"

    private fun dayName(date: String): String = try {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        cal.time = fmt.parse(date)!!
        listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")[cal.get(Calendar.DAY_OF_WEEK) - 1]
    } catch (e: Exception) {
        date
    }

    private val sane = -70.0..60.0

    /** Превращает ответ Open-Meteo в короткий текст. null — данные неполные или цифры неправдоподобны. */
    fun format(place: String, json: JSONObject, q: Query): String? {
        val daily = json.optJSONObject("daily") ?: return null
        val dates = daily.optJSONArray("time") ?: return null
        val maxT = daily.optJSONArray("temperature_2m_max") ?: return null
        val minT = daily.optJSONArray("temperature_2m_min") ?: return null
        val codes = daily.optJSONArray("weather_code")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val wind = daily.optJSONArray("wind_speed_10m_max")
        if (dates.length() == 0) return null

        fun line(i: Int): String? {
            if (i >= dates.length() || maxT.isNull(i) || minT.isNull(i)) return null
            val hi = maxT.getDouble(i)
            val lo = minT.getDouble(i)
            if (hi !in sane || lo !in sane || lo > hi + 0.01) return null
            val parts = ArrayList<String>()
            parts.add("от ${deg(lo)} до ${deg(hi)}")
            if (codes != null && !codes.isNull(i)) parts.add(describe(codes.getInt(i)))
            if (rain != null && !rain.isNull(i) && rain.getInt(i) >= 20) parts.add("осадки ${rain.getInt(i)}%")
            if (wind != null && !wind.isNull(i) && wind.getDouble(i) >= 8) parts.add("ветер до ${Math.round(wind.getDouble(i))} м/с")
            return parts.joinToString(", ")
        }

        if (q.week) {
            val rows = (0 until minOf(7, dates.length())).mapNotNull { i -> line(i)?.let { "${dayName(dates.getString(i))}: $it" } }
            if (rows.isEmpty()) return null
            return "$place, прогноз на неделю:\n" + rows.joinToString("\n")
        }

        val i = q.dayOffset
        val day = line(i) ?: return null
        val label = when (i) { 0 -> "сегодня"; 1 -> "завтра"; else -> "послезавтра" }
        val sb = StringBuilder("$place, $label: $day.")
        val cur = json.optJSONObject("current")
        if (i == 0 && cur != null && !cur.isNull("temperature_2m")) {
            val t = cur.getDouble("temperature_2m")
            if (t !in sane) return null
            val feels = if (cur.isNull("apparent_temperature")) null else cur.getDouble("apparent_temperature")
            val now = StringBuilder("\nСейчас ${deg(t)}")
            if (feels != null && feels in sane && Math.abs(feels - t) >= 2) now.append(", ощущается как ${deg(feels)}")
            if (!cur.isNull("weather_code")) now.append(", ${describe(cur.getInt("weather_code"))}")
            if (!cur.isNull("wind_speed_10m")) now.append(", ветер ${Math.round(cur.getDouble("wind_speed_10m"))} м/с")
            sb.append(now).append('.')
        }
        return sb.toString()
    }

    private fun get(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Готовый ответ о погоде или null, если не вышло (тогда работает обычный поиск). */
    suspend fun report(query: String): String? = withContext(Dispatchers.IO) {
        val q = parseQuery(query)
        var place = HOME_NAME
        var lat = HOME_LAT
        var lon = HOME_LON
        if (q.city != null && TextMatch.normalize(q.city) != "булаево") {
            var found = false
            for (variant in cityVariants(q.city)) {
                val geo = get(
                    "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(variant, "UTF-8") +
                        "&count=5&language=ru"
                )?.optJSONArray("results") ?: continue
                val list = (0 until geo.length()).map { geo.getJSONObject(it) }
                val pick = list.firstOrNull { it.optString("country_code") in setOf("KZ", "RU", "UA", "BY") } ?: list.firstOrNull() ?: continue
                place = pick.optString("name", variant)
                lat = pick.getDouble("latitude")
                lon = pick.getDouble("longitude")
                found = true
                break
            }
            if (!found) return@withContext null
        }
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=7"
        ) ?: return@withContext null
        format(place, json, q)
    }
}
