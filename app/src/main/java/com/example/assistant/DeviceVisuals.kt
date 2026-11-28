package com.example.assistant

import android.content.Context

/** Картинки к ответам о телефоне — строятся из тех же данных, без модели и без токенов. */
object DeviceVisuals {

    private fun gb(b: Long) = Math.round(b / 1024.0 / 1024.0 / 1024.0 * 10) / 10.0

    fun battery(b: BatteryInfo): Visual.Stat = Visual.Stat(
        "Заряд", "${b.percent}%", when { b.full -> "полный"; b.charging -> "заряжается"; else -> b.tempC?.let { "${it.toInt()} °C" } ?: "" },
        if (b.percent <= 15) "red" else if (b.percent <= 35) "yellow" else "green", b.percent.toDouble()
    )

    fun memory(m: MemoryInfoData): Visual.Stat {
        val used = m.totalBytes - m.availBytes
        val p = if (m.totalBytes > 0) used * 100.0 / m.totalBytes else 0.0
        return Visual.Stat("Оперативная память", "${Math.round(p)}%", "свободно ${DeviceReport.bytes(m.availBytes)}", if (p > 85) "red" else if (p > 70) "yellow" else "blue", p)
    }

    fun storage(s: StorageInfoData): Visual.Stat {
        val used = s.totalBytes - s.freeBytes
        val p = if (s.totalBytes > 0) used * 100.0 / s.totalBytes else 0.0
        return Visual.Stat("Память телефона", "${Math.round(p)}%", "свободно ${DeviceReport.bytes(s.freeBytes)}", if (p > 90) "red" else if (p > 75) "yellow" else "violet", p)
    }

    fun network(n: NetworkInfo): Visual.Stat = Visual.Stat(
        "Сеть", when { n.airplane -> "полёт"; !n.connected -> "нет"; n.type == "wifi" -> "Wi-Fi"; n.type == "mobile" -> "моб."; else -> n.type ?: "есть" },
        listOfNotNull(n.downMbps?.let { "↓$it Мбит/с" }, if (n.connected && !n.internetWorks) "интернет не отвечает" else null).joinToString(" "),
        if (!n.connected || !n.internetWorks) "red" else "teal"
    )

    /** Блок ```visual для раздела отчёта; пустая строка — картинка не нужна. */
    fun forSection(context: Context, section: String): String = try {
        val app = context.applicationContext
        when (section) {
            "battery" -> VisualSpec.block(VisualSpec.statsJson("", listOf(battery(DeviceStatus.battery(app)))))
            "memory", "storage", "memory_all" -> {
                val st = DeviceStatus.storage()
                VisualSpec.block(VisualSpec.pieJson("Память телефона, ГБ", listOf("Занято" to gb(st.totalBytes - st.freeBytes), "Свободно" to gb(st.freeBytes)), "ГБ")) +
                    VisualSpec.block(VisualSpec.statsJson("", listOf(memory(DeviceStatus.memory(app)), storage(st))))
            }
            "all", "full" -> VisualSpec.block(VisualSpec.statsJson("Телефон сейчас", listOf(
                battery(DeviceStatus.battery(app)), network(DeviceStatus.network(app)), memory(DeviceStatus.memory(app)), storage(DeviceStatus.storage())
            )))
            "apps_storage" -> if (!DeviceStatus.hasUsageAccess(app)) "" else
                VisualSpec.block(VisualSpec.barJson("Место по приложениям, МБ", DeviceStatus.appSizes(app).sortedByDescending { it.bytes }.take(8)
                    .map { it.label to Math.round(it.bytes / 1048576.0).toDouble() }, "МБ"))
            "apps_usage" -> if (!DeviceStatus.hasUsageAccess(app)) "" else
                VisualSpec.block(VisualSpec.barJson("Время в приложениях, мин", DeviceStatus.usage(app).sortedByDescending { it.minutes }.take(8)
                    .map { it.label to it.minutes.toDouble() }, "мин"))
            else -> ""
        }
    } catch (e: Exception) { "" }

    /** Файлы по типам — кольцо в ГБ. */
    fun files(byCategory: Map<String, Long>): String {
        val data = byCategory.entries.sortedByDescending { it.value }.filter { it.value > 0 }.map { it.key to Math.round(it.value / 1048576.0).toDouble() }
        return if (data.isEmpty()) "" else VisualSpec.block(VisualSpec.pieJson("Файлы по типам, МБ", data, "МБ"))
    }

    fun notifications(items: List<NoticeItem>): String {
        if (items.isEmpty()) return ""
        return VisualSpec.block(VisualSpec.listJson("Уведомления", "list", items.take(8).map {
            Visual.Item("${it.app}: ${it.title}".take(80), it.text.take(120))
        }))
    }
}
