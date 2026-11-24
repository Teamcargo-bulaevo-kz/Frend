package com.example.assistant

/** Износ батареи по данным ядра: текущая полная ёмкость против заводской и число циклов. Чистый Kotlin. */
object BatteryWear {
    fun describe(cycles: Long?, full: Long?, design: Long?): String {
        val parts = ArrayList<String>()
        if (full != null && design != null && design > 0 && full > 0) {
            val health = (full * 100 / design).coerceIn(1, 120)
            // Ёмкость в мкА·ч (обычно) или мА·ч: показываем мА·ч
            val mah = if (design > 100_000) design / 1000 else design
            val nowMah = if (full > 100_000) full / 1000 else full
            parts += "Ёмкость батареи $health% от заводской ($nowMah из $mah мА·ч)"
            if (health < 80) parts += "батарея заметно изношена, время работы будет меньше"
        }
        if (cycles != null && cycles >= 0) parts += "циклов зарядки: $cycles"
        return if (parts.isEmpty()) "Телефон не отдаёт данные об износе батареи." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}
