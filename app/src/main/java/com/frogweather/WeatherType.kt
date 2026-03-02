package com.frogweather

/**
 * Simple enumeration of high-level weather types used by the animation layer.
 * You can expand this as you add more weather icons/animations.
 */
enum class WeatherType(val emoji: String, val label: String) {
    SUNNY("🐸😎", "sonnig"),
    RAINY("🐸☂️", "regnerisch"),
    CLOUDY("🐸☁️", "bewölkt"),
    OTHER("🐸", "unbekannt");

    companion object {
        fun fromString(value: String): WeatherType = when (value.lowercase()) {
            "clear" -> SUNNY
            "rain" -> RAINY
            "clouds" -> CLOUDY
            else -> OTHER
        }
    }
}
