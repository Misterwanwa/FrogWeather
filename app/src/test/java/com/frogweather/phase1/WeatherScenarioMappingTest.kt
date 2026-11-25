package com.frogweather.phase1

import com.frogweather.WeatherType
import com.frogweather.data.WeatherScenario
import com.frogweather.ui.LiveEffectMode
import com.frogweather.ui.resolveLiveEffectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherScenarioMappingTest {

    @Test
    fun fromWeatherType_mapsDayAndNightScenariosCorrectly() {
        assertEquals(WeatherScenario.SUNNY, WeatherScenario.fromWeatherType("Clear", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLEAR, WeatherScenario.fromWeatherType("Clear", isNight = true))
        assertEquals(WeatherScenario.CLOUDY, WeatherScenario.fromWeatherType("Clouds", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLOUDY, WeatherScenario.fromWeatherType("Clouds", isNight = true))
        assertEquals(WeatherScenario.PARTLY_CLOUDY, WeatherScenario.fromWeatherType("Few clouds", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLOUDY, WeatherScenario.fromWeatherType("PARTLY_CLOUDY", isNight = true))
        assertEquals(WeatherScenario.RAIN, WeatherScenario.fromWeatherType("Rain"))
        assertEquals(WeatherScenario.RAIN, WeatherScenario.fromWeatherType("Drizzle"))
        assertEquals(WeatherScenario.RAIN, WeatherScenario.fromWeatherType("RAINY"))
        assertEquals(WeatherScenario.LIGHT_RAIN, WeatherScenario.fromWeatherType("LIGHT_RAIN"))
        assertEquals(WeatherScenario.HEAVY_RAIN, WeatherScenario.fromWeatherType("HEAVY_RAIN"))
        assertEquals(WeatherScenario.THUNDERSTORM, WeatherScenario.fromWeatherType("Thunderstorm"))
        assertEquals(WeatherScenario.LIGHT_SNOW, WeatherScenario.fromWeatherType("LIGHT_SNOW"))
        assertEquals(WeatherScenario.SNOW, WeatherScenario.fromWeatherType("Snow"))
        assertEquals(WeatherScenario.BLIZZARD, WeatherScenario.fromWeatherType("BLIZZARD"))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("Mist"))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("Fog"))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("Haze"))
    }

    @Test
    fun isNightTime_evaluatesSunriseSunsetAndFallbacks() {
        val sunrise = 1_700_020_000L
        val sunset = 1_700_060_000L

        assertTrue(WeatherScenario.isNightTime(sunrise - 100L, sunrise, sunset))
        assertFalse(WeatherScenario.isNightTime(sunrise + 1000L, sunrise, sunset))
        assertTrue(WeatherScenario.isNightTime(sunset + 100L, sunrise, sunset))

        // Invalid/missing sunrise-sunset falls back to solar elevation
        assertTrue(WeatherScenario.isNightTime(1_700_000_000L, null, null, fallbackSolarElevationDeg = -5.0))
        assertFalse(WeatherScenario.isNightTime(1_700_000_000L, null, null, fallbackSolarElevationDeg = 12.0))

        // Local hour fallback
        assertTrue(WeatherScenario.isNightTime(1_700_000_000L, 0L, 0L, fallbackLocalHour = 22))
        assertFalse(WeatherScenario.isNightTime(1_700_000_000L, 0L, 0L, fallbackLocalHour = 14))
    }

    @Test
    fun resolveLiveEffectMode_mapsScenariosAndFallbackWeatherTypes() {
        assertEquals(LiveEffectMode.SUNNY_GLOW, resolveLiveEffectMode(WeatherType.SUNNY, WeatherScenario.SUNNY, isNight = false))
        assertEquals(LiveEffectMode.CLEAR_NIGHT, resolveLiveEffectMode(WeatherType.SUNNY, WeatherScenario.NIGHT_CLEAR, isNight = true))
        assertEquals(LiveEffectMode.FOG, resolveLiveEffectMode(WeatherType.OTHER, WeatherScenario.FOG, isNight = false))
        assertEquals(LiveEffectMode.LIGHT_RAIN, resolveLiveEffectMode(WeatherType.RAINY, WeatherScenario.LIGHT_RAIN, isNight = false))
        assertEquals(LiveEffectMode.RAIN, resolveLiveEffectMode(WeatherType.RAINY, WeatherScenario.RAIN, isNight = false))
        assertEquals(LiveEffectMode.HEAVY_RAIN, resolveLiveEffectMode(WeatherType.RAINY, WeatherScenario.HEAVY_RAIN, isNight = false))
        assertEquals(LiveEffectMode.THUNDERSTORM, resolveLiveEffectMode(WeatherType.THUNDERSTORM, WeatherScenario.THUNDERSTORM, isNight = false))
        assertEquals(LiveEffectMode.SNOW, resolveLiveEffectMode(WeatherType.SNOW, WeatherScenario.SNOW, isNight = false))
        assertEquals(LiveEffectMode.BLIZZARD, resolveLiveEffectMode(WeatherType.SNOW, WeatherScenario.BLIZZARD, isNight = false))

        // Fallback without scenario
        assertEquals(LiveEffectMode.NONE, resolveLiveEffectMode(WeatherType.OTHER, null, isNight = false))
        assertEquals(LiveEffectMode.CLEAR_NIGHT, resolveLiveEffectMode(WeatherType.OTHER, null, isNight = true))
    }
}
