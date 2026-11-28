package com.example.assistant

/**
 * Расход заряда по приложениям из системной статистики батареи (`dumpsys batterystats`), чистый разбор — есть тесты.
 * Понимает и компактный формат (--checkin: строки «pwi»), и обычный текстовый («Estimated power use (mAh)»).
 */
object BatteryUsage {

    data class Item(val uid: Int, val mah: Double)

    /** «u0a234» → 10234, «1000» → 1000. */
    fun uidOf(token: String): Int? {
        Regex("u(\\d+)a(\\d+)").matchEntire(token)?.let { return it.groupValues[1].toInt() * 100_000 + 10_000 + it.groupValues[2].toInt() }
        Regex("u(\\d+)s(\\d+)").matchEntire(token)?.let { return it.groupValues[1].toInt() * 100_000 + 99_000 + it.groupValues[2].toInt() }
        return token.toIntOrNull()
    }

    /** Расход по приложениям (мА·ч), по убыванию; одинаковые uid суммируются. */
    fun parse(output: String): List<Item> {
        val sums = HashMap<Int, Double>()
        // Компактный формат: 9,10234,l,pwi,uid,12.34,...
        Regex("(?m)^\\d+,(\\d+),l,pwi,uid,([\\d.]+)").findAll(output).forEach { m ->
            val uid = m.groupValues[1].toIntOrNull() ?: return@forEach
            val v = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            sums[uid] = (sums[uid] ?: 0.0) + v
        }
        if (sums.isEmpty()) {
            // Текстовый формат: «UID u0a234: 12.5 ( cpu=… )» или «Uid u0a234: 12.5»
            val section = output.substringAfter("Estimated power use", output)
            Regex("(?im)^\\s*uid\\s+(u\\d+[as]\\d+|\\d+):\\s*([\\d.]+)").findAll(section).forEach { m ->
                val uid = uidOf(m.groupValues[1]) ?: return@forEach
                val v = m.groupValues[2].toDoubleOrNull() ?: return@forEach
                sums[uid] = (sums[uid] ?: 0.0) + v
            }
        }
        return sums.filter { it.value >= 0.05 }.map { Item(it.key, it.value) }.sortedByDescending { it.mah }
    }

    fun format(items: List<Pair<String, Double>>, limit: Int = 10): String {
        if (items.isEmpty()) return "Система пока не накопила статистику расхода заряда (она обнуляется после полной зарядки)."
        val total = items.sumOf { it.second }
        return "Больше всего заряда с последней зарядки потратили:\n" + items.take(limit).mapIndexed { i, (name, mah) ->
            val pct = if (total > 0) Math.round(mah * 100 / total) else 0
            "${i + 1}. $name — ${Math.round(mah)} мА·ч ($pct%)"
        }.joinToString("\n")
    }
}

/**
 * Страховка наглядности: если ответ — список с цифрами («1. WhatsApp — 233 МБ»), а картинки к нему нет,
 * рисуем столбцы сами. Чистый Kotlin, есть тесты.
 */
object AutoVisual {

    private val line = Regex("^\\s*(?:\\d+[.)]|[•\\-–])\\s*(.+?)\\s*[—–:-]\\s*([\\d][\\d\\s.,]*)\\s*(ГБ|МБ|КБ|%|мин|ч|мА·ч|шт\\.?)?(?:\\s.*)?$")

    /** Блок ```visual для списка или пустая строка. */
    fun forText(text: String): String {
        if (text.contains("```")) return ""
        val rows = text.lines().mapNotNull { l ->
            val m = line.matchEntire(l) ?: return@mapNotNull null
            val v = m.groupValues[2].replace(" ", "").replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            Triple(m.groupValues[1].trim().take(40), v, m.groupValues[3])
        }
        if (rows.size < 3) return ""
        // Разные единицы памяти приводим к МБ, чтобы столбцы были сравнимы
        val memory = rows.all { it.third in setOf("ГБ", "МБ", "КБ") }
        val data = rows.take(12).map { (name, v, u) ->
            name to if (memory) when (u) { "ГБ" -> v * 1024; "КБ" -> v / 1024; else -> v } else v
        }
        val unit = if (memory) "МБ" else rows.first().third
        return VisualSpec.block(VisualSpec.barJson("", data.map { it.first to Math.round(it.second * 10) / 10.0 }, unit))
    }
}
