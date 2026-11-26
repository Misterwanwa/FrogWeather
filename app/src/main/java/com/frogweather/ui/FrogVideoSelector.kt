package com.frogweather.ui

import com.frogweather.WeatherType
import com.frogweather.data.WeatherScenario
import java.time.Instant
import java.time.ZoneId

enum class FrogVideoCategory(val label: String) {
    CLEAR("Klar / Sonnig"),
    CLOUDY("Bewölkt"),
    FOG("Dunst / Nebel"),
    RAIN("Regen"),
    SNOW("Schneefall"),
    WINDY("Windig")
}

enum class FrogDayPhase(val label: String) {
    DAWN("Morgengrauen"),
    DAY("Tag"),
    DUSK("Dämmerung"),
    NIGHT("Nacht")
}

enum class FrogScene(val label: String) {
    WIESE("Wiese"),
    HUEGEL("Hügel"),
    PILZHAUS("Pilzhaus")
}

data class FrogVideoEntry(
    val fileName: String,
    val category: FrogVideoCategory,
    val dayPhase: FrogDayPhase,
    val scene: FrogScene,
    val isColdAurora: Boolean = false,
    val isRainbow: Boolean = false,
    val isIntro: Boolean = false,
    val isTent: Boolean = false
)

object FrogVideoSelector {

    /**
     * Complete 1:1 mapping of all 56 frog animation videos in assets/frog_videos/.
     * Every video is assigned to its exact weather category, day phase, and scene.
     */
    val allVideos: List<FrogVideoEntry> = listOf(
        // 01: Start / Intro
        FrogVideoEntry("01_Klar_Pilzhaus_Start.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAY, FrogScene.PILZHAUS, isIntro = true),

        // 02-06: Wiese Tag
        FrogVideoEntry("02_Bewoelkt_Wiese_Tag.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DAY, FrogScene.WIESE),
        FrogVideoEntry("03_Dunst_Rauch_Wiese_Tag.mp4", FrogVideoCategory.FOG, FrogDayPhase.DAY, FrogScene.WIESE),
        FrogVideoEntry("04_Regen_Wiese_Tag.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DAY, FrogScene.WIESE),
        FrogVideoEntry("05_Schneefall_Wiese_Tag.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DAY, FrogScene.WIESE),
        FrogVideoEntry("06_Windig_Wiese_Tag.mp4", FrogVideoCategory.WINDY, FrogDayPhase.DAY, FrogScene.WIESE),

        // 07-11: Wiese Dämmerung
        FrogVideoEntry("07_Bewoelkt_Wiese_Daemmerung.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DUSK, FrogScene.WIESE),
        FrogVideoEntry("08_Dunst_Rauch_Wiese_Daemmerung.mp4", FrogVideoCategory.FOG, FrogDayPhase.DUSK, FrogScene.WIESE),
        FrogVideoEntry("09_Regen_Wiese_Zelt.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DUSK, FrogScene.WIESE, isTent = true),
        FrogVideoEntry("10_Schneefall_Wiese_Daemmerung.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DUSK, FrogScene.WIESE),
        FrogVideoEntry("11_Klar_Wiese_Daemmerung.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DUSK, FrogScene.WIESE),

        // 12-15: Wiese Nacht
        FrogVideoEntry("12_Bewoelkt_Wiese_Nacht.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.NIGHT, FrogScene.WIESE),
        FrogVideoEntry("13_Dunst_Rauch_Wiese_Nacht.mp4", FrogVideoCategory.FOG, FrogDayPhase.NIGHT, FrogScene.WIESE),
        FrogVideoEntry("14_Klar_Polarlichter_Schnee_Nacht.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.NIGHT, FrogScene.WIESE, isColdAurora = true),
        FrogVideoEntry("15_Klar_Wiese_Nacht.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.NIGHT, FrogScene.WIESE),

        // 16-20: Wiese Morgengrauen / Sonnenaufgang
        FrogVideoEntry("16_Bewoelkt_Wiese_Morgengrauen.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DAWN, FrogScene.WIESE),
        FrogVideoEntry("17_Dunst_Rauch_Wiese_Morgengrauen.mp4", FrogVideoCategory.FOG, FrogDayPhase.DAWN, FrogScene.WIESE),
        FrogVideoEntry("18_Regen_Wiese_Morgengrauen.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DAWN, FrogScene.WIESE),
        FrogVideoEntry("19_Schneefall_Wiese_Morgengrauen.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DAWN, FrogScene.WIESE),
        FrogVideoEntry("20_Klar_Sonnig_Wiese_Sonnenaufgang.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAWN, FrogScene.WIESE),

        // 21-25: Hügel Tag
        FrogVideoEntry("21_Bewoelkt_Huegel_Tag.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DAY, FrogScene.HUEGEL),
        FrogVideoEntry("22_Dunst_Rauch_Huegel_Tag.mp4", FrogVideoCategory.FOG, FrogDayPhase.DAY, FrogScene.HUEGEL),
        FrogVideoEntry("23_Regen_Huegel_Tag.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DAY, FrogScene.HUEGEL),
        FrogVideoEntry("24_Schneefall_Huegel_Tag.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DAY, FrogScene.HUEGEL),
        FrogVideoEntry("25_Sonnig_Huegel_Tag.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAY, FrogScene.HUEGEL),

        // 26-30: Hügel Dämmerung
        FrogVideoEntry("26_Bewoelkt_Huegel_Daemmerung.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DUSK, FrogScene.HUEGEL),
        FrogVideoEntry("27_Dunst_Rauch_Huegel_Daemmerung.mp4", FrogVideoCategory.FOG, FrogDayPhase.DUSK, FrogScene.HUEGEL),
        FrogVideoEntry("28_Regen_Huegel_Daemmerung.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DUSK, FrogScene.HUEGEL),
        FrogVideoEntry("29_Schneefall_Huegel_Daemmerung.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DUSK, FrogScene.HUEGEL),
        FrogVideoEntry("30_Klar_Huegel_Daemmerung.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DUSK, FrogScene.HUEGEL),

        // 31-35: Hügel Nacht
        FrogVideoEntry("31_Bewoelkt_Huegel_Nacht.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.NIGHT, FrogScene.HUEGEL),
        FrogVideoEntry("32_Dunst_Rauch_Huegel_Nacht.mp4", FrogVideoCategory.FOG, FrogDayPhase.NIGHT, FrogScene.HUEGEL),
        FrogVideoEntry("33_Regen_Huegel_Nacht.mp4", FrogVideoCategory.RAIN, FrogDayPhase.NIGHT, FrogScene.HUEGEL),
        FrogVideoEntry("34_Schneefall_Huegel_Nacht.mp4", FrogVideoCategory.SNOW, FrogDayPhase.NIGHT, FrogScene.HUEGEL),
        FrogVideoEntry("35_Klar_Huegel_Nacht.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.NIGHT, FrogScene.HUEGEL),

        // 36: Hügel Sonnenaufgang
        FrogVideoEntry("36_Klar_Sonnig_Huegel_Sonnenaufgang.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAWN, FrogScene.HUEGEL),

        // 37-41: Pilzhaus Tag
        FrogVideoEntry("37_Bewoelkt_Pilzhaus_Tag.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DAY, FrogScene.PILZHAUS),
        FrogVideoEntry("38_Dunst_Rauch_Pilzhaus_Tag.mp4", FrogVideoCategory.FOG, FrogDayPhase.DAY, FrogScene.PILZHAUS),
        FrogVideoEntry("39_Regen_Pilzhaus_Tag.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DAY, FrogScene.PILZHAUS),
        FrogVideoEntry("40_Schneefall_Pilzhaus_Tag.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DAY, FrogScene.PILZHAUS),
        FrogVideoEntry("41_Sonnig_Pilzhaus_Tag.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAY, FrogScene.PILZHAUS),

        // 42-45: Pilzhaus Dämmerung
        FrogVideoEntry("42_Bewoelkt_Pilzhaus_Daemmerung.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DUSK, FrogScene.PILZHAUS),
        FrogVideoEntry("43_Dunst_Rauch_Pilzhaus_Daemmerung.mp4", FrogVideoCategory.FOG, FrogDayPhase.DUSK, FrogScene.PILZHAUS),
        FrogVideoEntry("44_Regen_Pilzhaus_Daemmerung.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DUSK, FrogScene.PILZHAUS),
        FrogVideoEntry("45_Schneefall_Pilzhaus_Daemmerung.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DUSK, FrogScene.PILZHAUS),

        // 46: Pilzhaus Tag Regenbogen
        FrogVideoEntry("46_Sonnig_Regenbogen_Pilzhaus_Tag.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAY, FrogScene.PILZHAUS, isRainbow = true),

        // 47-51: Pilzhaus Nacht
        FrogVideoEntry("47_Bewoelkt_Pilzhaus_Nacht.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.NIGHT, FrogScene.PILZHAUS),
        FrogVideoEntry("48_Dunst_Rauch_Pilzhaus_Nacht.mp4", FrogVideoCategory.FOG, FrogDayPhase.NIGHT, FrogScene.PILZHAUS),
        FrogVideoEntry("49_Regen_Pilzhaus_Nacht.mp4", FrogVideoCategory.RAIN, FrogDayPhase.NIGHT, FrogScene.PILZHAUS),
        FrogVideoEntry("50_Schneefall_Pilzhaus_Nacht.mp4", FrogVideoCategory.SNOW, FrogDayPhase.NIGHT, FrogScene.PILZHAUS),
        FrogVideoEntry("51_Klar_Pilzhaus_Nacht.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.NIGHT, FrogScene.PILZHAUS),

        // 52-56: Pilzhaus Morgengrauen / Sonnenaufgang
        FrogVideoEntry("52_Bewoelkt_Pilzhaus_Morgengrauen.mp4", FrogVideoCategory.CLOUDY, FrogDayPhase.DAWN, FrogScene.PILZHAUS),
        FrogVideoEntry("53_Dunst_Rauch_Pilzhaus_Morgengrauen.mp4", FrogVideoCategory.FOG, FrogDayPhase.DAWN, FrogScene.PILZHAUS),
        FrogVideoEntry("54_Regen_Pilzhaus_Morgengrauen.mp4", FrogVideoCategory.RAIN, FrogDayPhase.DAWN, FrogScene.PILZHAUS),
        FrogVideoEntry("55_Schneefall_Pilzhaus_Morgengrauen.mp4", FrogVideoCategory.SNOW, FrogDayPhase.DAWN, FrogScene.PILZHAUS),
        FrogVideoEntry("56_Klar_Sonnig_Pilzhaus_Sonnenaufgang.mp4", FrogVideoCategory.CLEAR, FrogDayPhase.DAWN, FrogScene.PILZHAUS)
    )

    fun resolveDayPhase(
        nowEpochSeconds: Long,
        sunriseEpochSeconds: Long?,
        sunsetEpochSeconds: Long?,
        isNightFallback: Boolean = false
    ): FrogDayPhase {
        if (sunriseEpochSeconds != null && sunsetEpochSeconds != null && sunsetEpochSeconds > sunriseEpochSeconds) {
            val dawnStart = sunriseEpochSeconds - 45 * 60L
            val dawnEnd = sunriseEpochSeconds + 60 * 60L
            val duskStart = sunsetEpochSeconds - 45 * 60L
            val duskEnd = sunsetEpochSeconds + 60 * 60L

            return when {
                nowEpochSeconds in dawnStart..dawnEnd -> FrogDayPhase.DAWN
                nowEpochSeconds in (dawnEnd + 1) until duskStart -> FrogDayPhase.DAY
                nowEpochSeconds in duskStart..duskEnd -> FrogDayPhase.DUSK
                else -> FrogDayPhase.NIGHT
            }
        }
        val hour = Instant.ofEpochSecond(nowEpochSeconds).atZone(ZoneId.systemDefault()).hour
        return when {
            isNightFallback && hour !in 5..20 -> FrogDayPhase.NIGHT
            hour in 5..7 -> FrogDayPhase.DAWN
            hour in 8..17 -> FrogDayPhase.DAY
            hour in 18..20 -> FrogDayPhase.DUSK
            else -> FrogDayPhase.NIGHT
        }
    }

    fun resolveCategory(
        weatherType: WeatherType?,
        scenario: WeatherScenario?,
        windSpeedMs: Double?
    ): FrogVideoCategory {
        val base = when (scenario) {
            WeatherScenario.FOG -> FrogVideoCategory.FOG
            WeatherScenario.LIGHT_RAIN,
            WeatherScenario.RAIN,
            WeatherScenario.HEAVY_RAIN,
            WeatherScenario.THUNDERSTORM -> FrogVideoCategory.RAIN
            WeatherScenario.LIGHT_SNOW,
            WeatherScenario.SNOW,
            WeatherScenario.BLIZZARD -> FrogVideoCategory.SNOW
            WeatherScenario.CLOUDY,
            WeatherScenario.PARTLY_CLOUDY,
            WeatherScenario.NIGHT_CLOUDY -> FrogVideoCategory.CLOUDY
            WeatherScenario.SUNNY,
            WeatherScenario.NIGHT_CLEAR -> FrogVideoCategory.CLEAR
            null -> when (weatherType) {
                WeatherType.RAINY, WeatherType.THUNDERSTORM -> FrogVideoCategory.RAIN
                WeatherType.SNOW -> FrogVideoCategory.SNOW
                WeatherType.CLOUDY -> FrogVideoCategory.CLOUDY
                WeatherType.SUNNY, WeatherType.OTHER, null -> FrogVideoCategory.CLEAR
            }
        }
        val isWindy = (windSpeedMs ?: 0.0) >= 8.0
        return if (isWindy && (base == FrogVideoCategory.CLEAR || base == FrogVideoCategory.CLOUDY)) {
            FrogVideoCategory.WINDY
        } else {
            base
        }
    }

    fun selectCandidates(
        weatherType: WeatherType?,
        scenario: WeatherScenario? = null,
        nowEpochSeconds: Long = Instant.now().epochSecond,
        sunriseEpochSeconds: Long? = null,
        sunsetEpochSeconds: Long? = null,
        isNightFallback: Boolean = false,
        windSpeedMs: Double? = null,
        temperatureC: Double? = null,
        humidityPct: Int? = null,
        rainMm: Double? = null
    ): List<FrogVideoEntry> {
        val dayPhase = resolveDayPhase(nowEpochSeconds, sunriseEpochSeconds, sunsetEpochSeconds, isNightFallback)
        val category = resolveCategory(weatherType, scenario, windSpeedMs)

        // Exact category + dayPhase matches
        var matches = allVideos.filter { it.category == category && it.dayPhase == dayPhase }

        // If WINDY has no match for night/dawn/dusk, include WINDY day video plus base weather for that phase
        if (matches.isEmpty() && category == FrogVideoCategory.WINDY) {
            val baseCategory = resolveCategory(weatherType, scenario, windSpeedMs = 0.0)
            matches = allVideos.filter { it.category == FrogVideoCategory.WINDY } +
                allVideos.filter { it.category == baseCategory && it.dayPhase == dayPhase }
        }

        // Fallback to any dayPhase of same category
        if (matches.isEmpty()) {
            matches = allVideos.filter { it.category == category }
        }

        // Ultimate fallback
        if (matches.isEmpty()) {
            matches = allVideos.filter { it.category == FrogVideoCategory.CLEAR && it.dayPhase == dayPhase }
                .ifEmpty { allVideos }
        }

        // Contextual prioritization
        val isColdNight = dayPhase == FrogDayPhase.NIGHT && (temperatureC ?: 10.0) <= 2.0
        val isPostRainOrHumid = (rainMm ?: 0.0) > 0.0 || (humidityPct ?: 0) >= 75

        return matches.sortedWith(
            compareByDescending<FrogVideoEntry> { isColdNight && it.isColdAurora }
                .thenByDescending { isPostRainOrHumid && it.isRainbow }
                .thenBy { it.isIntro } // prefer main scene loops before start clip
                .thenBy { it.fileName }
        )
    }

    fun selectVideo(
        weatherType: WeatherType?,
        scenario: WeatherScenario? = null,
        nowEpochSeconds: Long = Instant.now().epochSecond,
        sunriseEpochSeconds: Long? = null,
        sunsetEpochSeconds: Long? = null,
        isNightFallback: Boolean = false,
        windSpeedMs: Double? = null,
        temperatureC: Double? = null,
        humidityPct: Int? = null,
        rainMm: Double? = null,
        variantIndex: Int = 0
    ): FrogVideoEntry {
        val candidates = selectCandidates(
            weatherType = weatherType,
            scenario = scenario,
            nowEpochSeconds = nowEpochSeconds,
            sunriseEpochSeconds = sunriseEpochSeconds,
            sunsetEpochSeconds = sunsetEpochSeconds,
            isNightFallback = isNightFallback,
            windSpeedMs = windSpeedMs,
            temperatureC = temperatureC,
            humidityPct = humidityPct,
            rainMm = rainMm
        )
        val safeIndex = ((variantIndex % candidates.size) + candidates.size) % candidates.size
        return candidates[safeIndex]
    }
}
