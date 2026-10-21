package com.example.assistant

import android.content.Context

/** Что приложение узнало о пользователе: имя и домашний город. Хранится только на телефоне. */
object UserProfile {

    private const val PREFS = "assistant_profile"

    data class City(val name: String, val lat: Double, val lon: Double)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(context: Context): String? = prefs(context).getString("name", null)?.takeIf { it.isNotBlank() }

    fun setName(context: Context, value: String) {
        prefs(context).edit().putString("name", value.trim().take(40)).apply()
    }

    fun city(context: Context): City? {
        val p = prefs(context)
        val name = p.getString("city", null) ?: return null
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return City(name, lat, lon)
    }

    fun setCity(context: Context, city: City) {
        prefs(context).edit().putString("city", city.name).putString("lat", city.lat.toString()).putString("lon", city.lon.toString()).apply()
    }
}
