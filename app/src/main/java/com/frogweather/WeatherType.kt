package com.frogweather

/**
 * Simple enumeration of high-level weather types used by the animation layer.
 * You can expand this as you add more weather icons/animations.
 */
enum class WeatherType(val emoji: String, val forecastEmoji: String, val label: String) {
    SUNNY("🐸😎", "☀️", "sonnig"),
    RAINY("🐸☂️", "🌧️", "regnerisch"),
    CLOUDY("🐸☁️", "☁️", "bewölkt"),
    THUNDERSTORM("🐸⚡", "⛈️", "Gewitter"),
    SNOW("🐸❄️", "🌨️", "Schneefall"),
    OTHER("🐸", "🌡️", "unbekannt");

    companion object {
        fun fromString(value: String): WeatherType = when (value.lowercase()) {
            "clear" -> SUNNY
            "rain", "drizzle" -> RAINY
            "clouds" -> CLOUDY
            "thunderstorm" -> THUNDERSTORM
            "snow" -> SNOW
            else -> OTHER
        }
    }
}
