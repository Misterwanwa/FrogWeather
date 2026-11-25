package com.frogweather.data

data class ScenarioPrompt(
    val scenario: WeatherScenario,
    val typicalTempCelsius: Int,
    val englishWeatherLabel: String,
    val prompt: String
)

object CityImagePromptGenerator {
    fun formatCityLabel(cityName: String, country: String? = null): String {
        val cleanCity = cityName.trim().ifEmpty { "Unknown City" }
        val cleanCountry = country?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        val hasCountryAlready = cleanCountry != null && (
            cleanCity.split(',').any { it.trim().equals(cleanCountry, ignoreCase = true) } ||
                Regex("(?i)(?:^|[,\\s])${Regex.escape(cleanCountry)}$").containsMatchIn(cleanCity)
            )
        return if (cleanCountry != null && !hasCountryAlready) {
            "$cleanCity, $cleanCountry"
        } else {
            cleanCity
        }
    }

    fun typicalTemperatureCelsius(scenario: WeatherScenario): Int = when (scenario) {
        WeatherScenario.SUNNY -> 26
        WeatherScenario.PARTLY_CLOUDY -> 16
        WeatherScenario.CLOUDY -> 14
        WeatherScenario.LIGHT_RAIN -> 13
        WeatherScenario.RAIN -> 12
        WeatherScenario.HEAVY_RAIN -> 11
        WeatherScenario.THUNDERSTORM -> 18
        WeatherScenario.LIGHT_SNOW -> 0
        WeatherScenario.SNOW -> -2
        WeatherScenario.BLIZZARD -> -8
        WeatherScenario.FOG -> 8
        WeatherScenario.NIGHT_CLEAR -> 12
        WeatherScenario.NIGHT_CLOUDY -> 10
    }

    fun englishWeatherLabel(scenario: WeatherScenario): String = when (scenario) {
        WeatherScenario.SUNNY -> "Sunny"
        WeatherScenario.PARTLY_CLOUDY -> "Partly Clouded"
        WeatherScenario.CLOUDY -> "Cloudy"
        WeatherScenario.LIGHT_RAIN -> "Light Rain"
        WeatherScenario.RAIN -> "Rainy"
        WeatherScenario.HEAVY_RAIN -> "Heavy Rain"
        WeatherScenario.THUNDERSTORM -> "Thunderstorm"
        WeatherScenario.LIGHT_SNOW -> "Light Snow"
        WeatherScenario.SNOW -> "Snow"
        WeatherScenario.BLIZZARD -> "Blizzard"
        WeatherScenario.FOG -> "Foggy"
        WeatherScenario.NIGHT_CLEAR -> "Clear Night"
        WeatherScenario.NIGHT_CLOUDY -> "Cloudy Night"
    }

    fun buildPrompt(
        cityLabel: String,
        scenario: WeatherScenario,
        additionalLandmarks: String? = null
    ): String {
        val resolvedCity = cityLabel.trim().ifEmpty { "Unknown City" }
        val tempC = typicalTemperatureCelsius(scenario)
        val weatherLabel = englishWeatherLabel(scenario)
        val landmarks = additionalLandmarks?.trim()?.takeIf { it.isNotEmpty() } ?: "NONE"

        return """
            |CITY=$resolvedCity
            |TEMPERATURE=$tempC° C
            |WEATHER = $weatherLabel
            |ADDITIONAL_LANDMARKS (optional): $landmarks
            |
            |Present a clear, 45° top-down isometric miniature 3D cartoon scene of CITY, featuring its most iconic landmarks and architectural elements. Add ADDITIONAL_LANDMARKS if filled. Use soft, refined textures with realistic PBR materials and gentle, lifelike lighting and shadows. Integrate the current weather conditions directly into the city environment to create an immersive atmospheric mood.
            |Use a clean, minimalistic composition with a soft, solid-colored background.
            |
            |Weather is: TEMPERATURE + WEATHER
            |
            |No Text
            |Square 1080x1080 dimension.
        """.trimMargin()
    }

    fun buildPromptsForMissing(
        cityLabel: String,
        missingScenarios: List<WeatherScenario>,
        additionalLandmarks: String? = null
    ): List<ScenarioPrompt> = missingScenarios.distinct().map { scenario ->
        ScenarioPrompt(
            scenario = scenario,
            typicalTempCelsius = typicalTemperatureCelsius(scenario),
            englishWeatherLabel = englishWeatherLabel(scenario),
            prompt = buildPrompt(cityLabel, scenario, additionalLandmarks)
        )
    }

    fun formatAllPrompts(prompts: List<ScenarioPrompt>): String {
        if (prompts.isEmpty()) return ""
        return prompts.joinToString("\n\n---\n\n") { entry ->
            "### ${entry.scenario.displayName} (${entry.englishWeatherLabel})\n${entry.prompt}"
        }
    }
}
