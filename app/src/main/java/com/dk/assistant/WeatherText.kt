package com.dk.assistant

import org.json.JSONObject

/**
 * v3.45: Open-Meteo (मुफ़्त, बिना key) के जवाब -> बोलने लायक हिंदी (Android import नहीं -> JVM unit test).
 * API: https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&current=temperature_2m,apparent_temperature,
 *      relative_humidity_2m,precipitation,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,
 *      precipitation_probability_max&timezone=auto&forecast_days=1
 */
object WeatherText {
    fun url(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1"

    /** WMO weather code -> हिंदी */
    fun sky(code: Int): String = when (code) {
        0 -> "साफ़ आसमान"
        1, 2 -> "हल्के बादल"
        3 -> "घने बादल"
        45, 48 -> "कोहरा"
        in 51..57 -> "बूंदाबांदी"
        61, 66, 80 -> "हल्की बारिश"
        63, 81 -> "बारिश"
        65, 67, 82 -> "तेज़ बारिश"
        in 71..77, 85, 86 -> "बर्फ़बारी"
        in 95..99 -> "गरज के साथ बारिश / तूफ़ान"
        else -> "मौसम सामान्य"
    }

    fun describe(json: String): String? = try {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val t = cur.optDouble("temperature_2m", Double.NaN)
        val feels = cur.optDouble("apparent_temperature", Double.NaN)
        val code = cur.optInt("weather_code", -1)
        val d = o.optJSONObject("daily")
        fun day(k: String) = d?.optJSONArray(k)?.optDouble(0, Double.NaN) ?: Double.NaN
        val hi = day("temperature_2m_max"); val lo = day("temperature_2m_min"); val rain = day("precipitation_probability_max")
        val dayCode = d?.optJSONArray("weather_code")?.optInt(0, -1) ?: -1
        buildString {
            if (!t.isNaN()) append("अभी ${t.toInt()}°C")
            if (!feels.isNaN() && kotlin.math.abs(feels - t) >= 3) append(" (महसूस ${feels.toInt()}°C)")
            if (code >= 0) append(", ${sky(code)}")
            if (!hi.isNaN() && !lo.isNaN()) append("; आज ${lo.toInt()}–${hi.toInt()}°C")
            if (dayCode >= 0 && dayCode != code) append(", दिन में ${sky(dayCode)}")
            if (!rain.isNaN() && rain >= 30) append(", बारिश की संभावना ${rain.toInt()}%")
        }.trim(',', ';', ' ').ifBlank { null }
    } catch (_: Exception) { null }
}
