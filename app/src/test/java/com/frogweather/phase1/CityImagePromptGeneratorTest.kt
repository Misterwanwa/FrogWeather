package com.frogweather.phase1

import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.WeatherScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityImagePromptGeneratorTest {

    @Test
    fun buildPrompt_matchesExactTemplateFromOriginalRequestForTokyoPartlyCloudy() {
        val expected = """
            |CITY=Tokyo, Japan
            |TEMPERATURE=16° C
            |WEATHER = Partly Clouded
            |ADDITIONAL_LANDMARKS (optional): NONE
            |
            |Present a clear, 45° top-down isometric miniature 3D cartoon scene of CITY, featuring its most iconic landmarks and architectural elements. Add ADDITIONAL_LANDMARKS if filled. Use soft, refined textures with realistic PBR materials and gentle, lifelike lighting and shadows. Integrate the current weather conditions directly into the city environment to create an immersive atmospheric mood.
            |Use a clean, minimalistic composition with a soft, solid-colored background.
            |
            |Weather is: TEMPERATURE + WEATHER
            |
            |No Text
            |Square 1080x1080 dimension.
        """.trimMargin()

        assertEquals(expected, CityImagePromptGenerator.buildPrompt("Tokyo, Japan", WeatherScenario.PARTLY_CLOUDY, null))
        assertEquals(expected, CityImagePromptGenerator.buildPrompt("Tokyo, Japan", WeatherScenario.PARTLY_CLOUDY, ""))
        assertEquals(expected, CityImagePromptGenerator.buildPrompt("Tokyo, Japan", WeatherScenario.PARTLY_CLOUDY, "   "))
    }

    @Test
    fun buildPrompt_substitutesTrimmedAdditionalLandmarks() {
        val prompt = CityImagePromptGenerator.buildPrompt(
            cityLabel = "Berlin, DE",
            scenario = WeatherScenario.SUNNY,
            additionalLandmarks = "  Fernsehturm, Brandenburger Tor  "
        )
        assertTrue(prompt.contains("CITY=Berlin, DE\n"))
        assertTrue(prompt.contains("TEMPERATURE=26° C\n"))
        assertTrue(prompt.contains("WEATHER = Sunny\n"))
        assertTrue(prompt.contains("ADDITIONAL_LANDMARKS (optional): Fernsehturm, Brandenburger Tor\n"))
        assertTrue(prompt.contains("Weather is: TEMPERATURE + WEATHER"))
    }

    @Test
    fun typicalTemperaturesAndLabels_coverAllThirteenScenarios() {
        assertEquals(26, CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.SUNNY))
        assertEquals(16, CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.PARTLY_CLOUDY))
        assertEquals(-2, CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.SNOW))
        assertEquals("Partly Clouded", CityImagePromptGenerator.englishWeatherLabel(WeatherScenario.PARTLY_CLOUDY))
        assertEquals("Sunny", CityImagePromptGenerator.englishWeatherLabel(WeatherScenario.SUNNY))

        WeatherScenario.entries.forEach { scenario ->
            val label = CityImagePromptGenerator.englishWeatherLabel(scenario)
            assertTrue("Expected non-blank label for $scenario", label.isNotBlank())
        }
        assertTrue(CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.LIGHT_SNOW) <= 0)
        assertTrue(CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.SNOW) <= 0)
        assertTrue(CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.BLIZZARD) < 0)
    }

    @Test
    fun buildPromptsForMissing_deduplicatesAndPreservesOrder() {
        val missing = listOf(
            WeatherScenario.RAIN,
            WeatherScenario.SUNNY,
            WeatherScenario.RAIN,
            WeatherScenario.FOG
        )
        val prompts = CityImagePromptGenerator.buildPromptsForMissing("Hamburg, DE", missing)
        assertEquals(3, prompts.size)
        assertEquals(listOf(WeatherScenario.RAIN, WeatherScenario.SUNNY, WeatherScenario.FOG), prompts.map { it.scenario })
        assertEquals(emptyList<Any>(), CityImagePromptGenerator.buildPromptsForMissing("Hamburg, DE", emptyList()))

        val allThirteen = CityImagePromptGenerator.buildPromptsForMissing("Hamburg, DE", WeatherScenario.entries)
        assertEquals(13, allThirteen.size)
    }

    @Test
    fun formatAllPrompts_handlesEmptyAndIncludesAllPrompts() {
        assertEquals("", CityImagePromptGenerator.formatAllPrompts(emptyList()))

        val prompts = CityImagePromptGenerator.buildPromptsForMissing(
            "München, DE",
            listOf(WeatherScenario.SUNNY, WeatherScenario.SNOW)
        )
        val formatted = CityImagePromptGenerator.formatAllPrompts(prompts)
        prompts.forEach { entry ->
            assertTrue(formatted.contains(entry.prompt))
        }
    }

    @Test
    fun formatCityLabel_handlesCountryNullBlankAndDuplicate() {
        assertEquals("Tokyo, Japan", CityImagePromptGenerator.formatCityLabel("Tokyo", "Japan"))
        assertEquals("Tokyo, Japan", CityImagePromptGenerator.formatCityLabel("Tokyo, Japan", "Japan"))
        assertEquals("Tokyo, JP", CityImagePromptGenerator.formatCityLabel("Tokyo, JP", "JP"))
        assertEquals("Berlin", CityImagePromptGenerator.formatCityLabel("Berlin", null))
        assertEquals("Berlin", CityImagePromptGenerator.formatCityLabel("Berlin", "null"))
        assertEquals("Unknown City, Japan", CityImagePromptGenerator.formatCityLabel("  ", "Japan"))
    }

    @Test
    fun formatCityLabel_doesNotStripIsoCountryCodeSubstringInCityName() {
        assertEquals("Dresden, DE", CityImagePromptGenerator.formatCityLabel("Dresden", "DE"))
        assertEquals("Frankfurt, FR", CityImagePromptGenerator.formatCityLabel("Frankfurt", "FR"))
        assertEquals("Heidelberg, DE", CityImagePromptGenerator.formatCityLabel("Heidelberg", "DE"))
        assertEquals("Essen, ES", CityImagePromptGenerator.formatCityLabel("Essen", "ES"))
        assertEquals("Bremen, BR", CityImagePromptGenerator.formatCityLabel("Bremen", "BR"))
        assertEquals("Austin, Texas, US", CityImagePromptGenerator.formatCityLabel("Austin, Texas", "US"))
        assertEquals("Berlin, DE", CityImagePromptGenerator.formatCityLabel("Berlin, DE", "de"))
    }
}
