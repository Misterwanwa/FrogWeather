package com.frogweather.ui

import com.frogweather.WeatherType
import com.frogweather.data.WeatherScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrogVideoSelectorTest {

    @Test
    fun all56VideosAreMappedAndExistInAssets() {
        assertEquals(56, FrogVideoSelector.allVideos.size)
        val distinctNames = FrogVideoSelector.allVideos.map { it.fileName }.toSet()
        assertEquals(56, distinctNames.size)

        val assetsDir = listOf(
            File("src/main/assets/frog_videos"),
            File("app/src/main/assets/frog_videos")
        ).firstOrNull { it.exists() && it.isDirectory }
        assertTrue("assets/frog_videos directory must exist", assetsDir != null)

        val diskFiles = assetsDir!!.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        assertEquals(distinctNames, diskFiles)
    }

    @Test
    fun everyWeatherScenarioAndDayPhaseResolvesToMatchingVideo() {
        val sunrise = 1_700_000_000L + 6 * 3600L // 06:00
        val sunset = 1_700_000_000L + 18 * 3600L // 18:00
        val phaseTimes = mapOf(
            FrogDayPhase.DAWN to sunrise,
            FrogDayPhase.DAY to sunrise + 6 * 3600L,
            FrogDayPhase.DUSK to sunset,
            FrogDayPhase.NIGHT to sunrise - 3 * 3600L
        )

        for ((expectedPhase, time) in phaseTimes) {
            assertEquals(
                expectedPhase,
                FrogVideoSelector.resolveDayPhase(time, sunrise, sunset)
            )
            for (scenario in WeatherScenario.values()) {
                val chosen = FrogVideoSelector.selectVideo(
                    weatherType = WeatherType.OTHER,
                    scenario = scenario,
                    nowEpochSeconds = time,
                    sunriseEpochSeconds = sunrise,
                    sunsetEpochSeconds = sunset
                )
                assertTrue(chosen.fileName.endsWith(".mp4"))
                assertEquals(expectedPhase, chosen.dayPhase)
            }
        }
    }

    @Test
    fun windyAndColdNightAndRainbowContextsPrioritizeSpecialClips() {
        val sunrise = 1_700_000_000L + 6 * 3600L
        val sunset = 1_700_000_000L + 18 * 3600L
        val noon = sunrise + 6 * 3600L
        val midnight = sunrise - 3 * 3600L

        val windy = FrogVideoSelector.selectVideo(
            weatherType = WeatherType.CLOUDY,
            scenario = WeatherScenario.CLOUDY,
            nowEpochSeconds = noon,
            sunriseEpochSeconds = sunrise,
            sunsetEpochSeconds = sunset,
            windSpeedMs = 10.5
        )
        assertEquals("06_Windig_Wiese_Tag.mp4", windy.fileName)

        val coldNight = FrogVideoSelector.selectVideo(
            weatherType = WeatherType.SUNNY,
            scenario = WeatherScenario.NIGHT_CLEAR,
            nowEpochSeconds = midnight,
            sunriseEpochSeconds = sunrise,
            sunsetEpochSeconds = sunset,
            temperatureC = -3.0
        )
        assertEquals("14_Klar_Polarlichter_Schnee_Nacht.mp4", coldNight.fileName)

        val rainbow = FrogVideoSelector.selectVideo(
            weatherType = WeatherType.SUNNY,
            scenario = WeatherScenario.SUNNY,
            nowEpochSeconds = noon,
            sunriseEpochSeconds = sunrise,
            sunsetEpochSeconds = sunset,
            humidityPct = 82,
            rainMm = 0.5
        )
        assertEquals("46_Sonnig_Regenbogen_Pilzhaus_Tag.mp4", rainbow.fileName)
    }
}
