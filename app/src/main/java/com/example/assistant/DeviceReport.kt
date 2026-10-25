package com.example.assistant

/**
 * Данные о состоянии телефона и их запись человеческим языком. Чистый Kotlin, без Android: легко тестировать.
 * Сбор данных — в DeviceStatus.
 */
data class BatteryInfo(
    val percent: Int,
    val charging: Boolean,
    /** "usb", "ac", "wireless" или null */
    val source: String?,
    val full: Boolean,
    val tempC: Double?,
    /** "good", "overheat", "cold", "dead", "over_voltage", "failure" или null */
    val health: String?,
    /** Сколько осталось до полного заряда, минут (если система знает) */
    val minutesToFull: Int? = null,
    /** Текущая мощность, мА (положительная — заряд, отрицательная — расход), если телефон отдаёт */
    val currentMa: Int? = null,
    val powerSave: Boolean = false
)

data class NetworkInfo(
    val connected: Boolean,
    /** "wifi", "mobile", "ethernet", "vpn" или null */
    val type: String?,
    /** Интернет реально отвечает (а не только «подключено к сети») */
    val internetWorks: Boolean,
    val metered: Boolean,
    val downMbps: Int?,
    val upMbps: Int?,
    /** Сила Wi-Fi 0..4 */
    val wifiLevel: Int?,
    val wifiLinkMbps: Int?,
    val airplane: Boolean = false
)

data class MemoryInfoData(val totalBytes: Long, val availBytes: Long, val low: Boolean)

data class StorageInfoData(val totalBytes: Long, val freeBytes: Long)

data class AppStat(val label: String, val pkg: String, val bytes: Long = 0, val minutes: Int = 0)

object DeviceReport {

    /** 1536 МБ -> «1,5 ГБ», 300 МБ -> «300 МБ». */
    fun bytes(n: Long): String {
        val kb = 1024.0
        val v = n.toDouble()
        return when {
            v >= kb * kb * kb * 10 -> "${Math.round(v / (kb * kb * kb))} ГБ"
            v >= kb * kb * kb -> "${oneDecimal(v / (kb * kb * kb))} ГБ"
            v >= kb * kb -> "${Math.round(v / (kb * kb))} МБ"
            v >= kb -> "${Math.round(v / kb)} КБ"
            else -> "$n Б"
        }
    }

    private fun oneDecimal(x: Double): String = String.format(java.util.Locale("ru"), "%.1f", x)

    /** 95 -> «1 ч 35 мин», 40 -> «40 мин», 120 -> «2 ч». */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            else -> "$m мин"
        }
    }

    fun battery(b: BatteryInfo): String {
        val sb = StringBuilder("Заряд ${b.percent}%")
        when {
            b.full -> sb.append(", полностью заряжен")
            b.charging -> {
                sb.append(", заряжается")
                when (b.source) {
                    "usb" -> sb.append(" от USB")
                    "ac" -> sb.append(" от сети")
                    "wireless" -> sb.append(" без провода")
                }
                if (b.minutesToFull != null && b.minutesToFull > 0) sb.append(", до полного около ${duration(b.minutesToFull)}")
            }
            else -> sb.append(", не заряжается")
        }
        b.currentMa?.let { if (it != 0) sb.append(", ток ${Math.abs(it)} мА") }
        b.tempC?.let { sb.append(", температура ${oneDecimal(it)} °C") }
        when (b.health) {
            "overheat" -> sb.append(". Батарея перегрета, дай ей остыть")
            "cold" -> sb.append(". Батарея слишком холодная")
            "dead", "failure", "over_voltage" -> sb.append(". Состояние батареи плохое")
        }
        if (b.powerSave) sb.append(". Включена экономия заряда")
        if (b.tempC != null && b.tempC >= 42 && b.health != "overheat") sb.append(". Телефон заметно горячий")
        return sb.append(".").toString()
    }

    fun network(n: NetworkInfo): String {
        if (n.airplane) return "Включён режим полёта, сети нет."
        if (!n.connected) return "Сети нет: ни Wi-Fi, ни мобильного интернета."
        val kind = when (n.type) {
            "wifi" -> "Wi-Fi"
            "mobile" -> "мобильный интернет"
            "ethernet" -> "проводная сеть"
            "vpn" -> "VPN"
            else -> "сеть"
        }
        val sb = StringBuilder("Подключено: $kind")
        if (n.type == "wifi") {
            n.wifiLevel?.let { sb.append(", сигнал ${levelWord(it)}") }
            n.wifiLinkMbps?.let { if (it > 0) sb.append(", скорость соединения $it Мбит/с") }
        } else if (n.downMbps != null && n.downMbps > 0) {
            sb.append(", оценка скорости до ${n.downMbps} Мбит/с")
        }
        if (n.metered) sb.append(", трафик платный или ограниченный")
        sb.append(if (n.internetWorks) ". Интернет работает." else ". Но интернет не отвечает: проверь тариф, сигнал или точку доступа.")
        return sb.toString()
    }

    private fun levelWord(level: Int) = when {
        level >= 4 -> "отличный"
        level == 3 -> "хороший"
        level == 2 -> "средний"
        level == 1 -> "слабый"
        else -> "очень слабый"
    }

    fun memory(m: MemoryInfoData): String {
        val used = m.totalBytes - m.availBytes
        val pct = if (m.totalBytes > 0) (used * 100 / m.totalBytes).toInt() else 0
        val tail = if (m.low) " Памяти мало: закрой лишние приложения." else ""
        return "Оперативная память: занято ${bytes(used)} из ${bytes(m.totalBytes)} ($pct%), свободно ${bytes(m.availBytes)}.$tail"
    }

    fun storage(s: StorageInfoData): String {
        val used = s.totalBytes - s.freeBytes
        val pct = if (s.totalBytes > 0) (used * 100 / s.totalBytes).toInt() else 0
        val tail = if (s.totalBytes > 0 && s.freeBytes * 100 / s.totalBytes < 10) " Места мало, пора почистить." else ""
        return "Память телефона: занято ${bytes(used)} из ${bytes(s.totalBytes)} ($pct%), свободно ${bytes(s.freeBytes)}.$tail"
    }

    /** Список «1. Telegram — 2,3 ГБ». */
    fun appsByStorage(apps: List<AppStat>, limit: Int = 8): String {
        if (apps.isEmpty()) return "Не получилось узнать размеры приложений."
        val top = apps.sortedByDescending { it.bytes }.take(limit)
        return "Больше всего места занимают:\n" +
            top.mapIndexed { i, a -> "${i + 1}. ${a.label} — ${bytes(a.bytes)}" }.joinToString("\n")
    }

    /** Список «1. YouTube — 1 ч 20 мин». */
    fun appsByUsage(apps: List<AppStat>, hours: Int = 24, limit: Int = 8): String {
        val top = apps.filter { it.minutes > 0 }.sortedByDescending { it.minutes }.take(limit)
        if (top.isEmpty()) return "За последние $hours ч данных об использовании приложений нет."
        val total = top.sumOf { it.minutes }
        return "Чаще всего за последние $hours ч:\n" +
            top.mapIndexed { i, a -> "${i + 1}. ${a.label} — ${duration(a.minutes)}" }.joinToString("\n") +
            "\nВсего в этих приложениях: ${duration(total)}."
    }
}
