package com.frogweather.tier5

import com.frogweather.WeatherType
import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.HistoricalNormalCalculator
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SkipReason
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.data.AppThemeId
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.FavoriteLocation
import com.frogweather.data.FavoritesRepository
import com.frogweather.data.ForecastSnapshot
import com.frogweather.data.KeyValueStore
import com.frogweather.data.ObservedWeatherPoint
import com.frogweather.data.ThemeRepository
import com.frogweather.data.TrackRecordRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.network.ForecastCity
import com.frogweather.network.ForecastItem
import com.frogweather.network.ForecastMain
import com.frogweather.network.ForecastRainInfo
import com.frogweather.network.ForecastResponse
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelHourly
import com.frogweather.network.OpenMeteoModelIds
import com.frogweather.network.OpenMeteoParser
import com.frogweather.network.WeatherCondition
import com.frogweather.network.WindInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Tier5Phase1And2AdversarialTests {

    private class InMemoryKeyValueStore : KeyValueStore {
        private val data = linkedMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = data[key] ?: defaultValue
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
    }

    private fun hourPoint(
        epochMillis: Long,
        tempC: Double = 18.0,
        precipMm: Double = 0.0,
        precipProb: Int = 10,
        windKmh: Double = 10.0,
        gustKmh: Double = 15.0,
        uvIndex: Double = 2.0,
        capeJkg: Double = 40.0,
        weatherCode: Int = 1
    ) = HourlyWeatherPoint(
        epochMillis = epochMillis,
        tempC = tempC,
        precipMm = precipMm,
        precipProb = precipProb,
        windKmh = windKmh,
        gustKmh = gustKmh,
        uvIndex = uvIndex,
        capeJkg = capeJkg,
        weatherCode = weatherCode,
        humidityPct = 55,
        cloudCoverPct = 20,
        pressureHpa = 1015.0
    )

    // =========================================================================
    // Hardening Tests for Previously Untested Phase 1–2 Paths (Expected PASS)
    // =========================================================================

    @Test
    fun t5_p1_cityImagePromptGenerator_formatCityLabel_edgeCasesAndBlankFallbacks() {
        assertEquals("Unknown City", CityImagePromptGenerator.formatCityLabel("   ", null))
        assertEquals("Unknown City", CityImagePromptGenerator.formatCityLabel("", "null"))
        assertEquals("Austin, Texas, US", CityImagePromptGenerator.formatCityLabel("Austin, Texas", "US"))
        assertEquals("Berlin, DE", CityImagePromptGenerator.formatCityLabel("Berlin, DE", "de"))
        assertEquals("San Marino", CityImagePromptGenerator.formatCityLabel("San Marino", "San Marino"))
        assertEquals("Dresden, DE", CityImagePromptGenerator.formatCityLabel("Dresden", " DE "))
    }

    @Test
    fun t5_p1_themeRepository_persistsAllThemesAndIsolatesKeysFromFavorites() {
        val store = InMemoryKeyValueStore()
        val favRepo = FavoritesRepository(store)
        val themeRepo = ThemeRepository(store)

        favRepo.addFavorite(FavoriteLocation("b1", "Berlin", "DE", 52.52, 13.41, 100L))
        favRepo.setActiveFavoriteId("b1")

        AppThemeId.entries.forEach { theme ->
            themeRepo.setSelectedTheme(theme)
            assertEquals(theme, ThemeRepository(store).getSelectedTheme())
        }

        store.putString("selected_app_theme", "  SOLAR_WARM\t")
        assertEquals(AppThemeId.SOLAR_WARM, themeRepo.getSelectedTheme())
        store.putString("selected_app_theme", "corrupt_theme")
        assertEquals(AppThemeId.AERO_CLASSIC, themeRepo.getSelectedTheme())

        assertEquals(1, favRepo.getFavorites().size)
        assertEquals("b1", favRepo.getActiveFavoriteId())
    }

    @Test
    fun t5_p1_weatherScenario_fromWeatherType_coversAllOwmAndNightCases() {
        assertEquals(WeatherScenario.RAIN, WeatherScenario.fromWeatherType("Drizzle", isNight = false))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("Mist", isNight = false))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("Haze", isNight = true))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("Fog", isNight = false))
        assertEquals(WeatherScenario.PARTLY_CLOUDY, WeatherScenario.fromWeatherType("Few clouds", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLOUDY, WeatherScenario.fromWeatherType("Few clouds", isNight = true))
        assertEquals(WeatherScenario.LIGHT_RAIN, WeatherScenario.fromWeatherType("LIGHT_RAIN", isNight = false))
        assertEquals(WeatherScenario.HEAVY_RAIN, WeatherScenario.fromWeatherType("HEAVY_RAIN", isNight = false))
        assertEquals(WeatherScenario.LIGHT_SNOW, WeatherScenario.fromWeatherType("LIGHT_SNOW", isNight = false))
        assertEquals(WeatherScenario.BLIZZARD, WeatherScenario.fromWeatherType("BLIZZARD", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLEAR, WeatherScenario.fromWeatherType("Clear", isNight = true))
        assertEquals(WeatherScenario.NIGHT_CLOUDY, WeatherScenario.fromWeatherType("UnknownCondition", isNight = true))
        assertEquals(WeatherScenario.CLOUDY, WeatherScenario.fromWeatherType("UnknownCondition", isNight = false))
        assertEquals(WeatherType.RAINY, WeatherType.fromString("Drizzle"))
        assertEquals(WeatherType.OTHER, WeatherType.fromString("Mist"))
    }

    @Test
    fun t5_p2_openMeteoParser_parseForecastBatchJson_handlesSingleObjectArrayAndNullElements() {
        val singleJson = """
            {"latitude": 52.52, "longitude": 13.41, "utc_offset_seconds": 3600,
             "hourly": {"time": [1795042800], "temperature_2m": [11.5]}}
        """.trimIndent()
        val singleParsed = OpenMeteoParser.parseForecastBatchJson(singleJson, listOf(OpenMeteoModelIds.GFS))
        assertEquals(1, singleParsed.size)
        assertEquals(11.5, singleParsed[0].models[OpenMeteoModelIds.GFS]!!.temperature2m[0]!!, 1e-6)

        val arrayWithNull = """
            [null, {"latitude": 48.13, "longitude": 11.58, "hourly": {"time": [1795042800], "temperature_2m": [9.0]}}]
        """.trimIndent()
        val arrayParsed = OpenMeteoParser.parseForecastBatchJson(arrayWithNull, listOf(OpenMeteoModelIds.ECMWF))
        assertEquals(1, arrayParsed.size)
        assertEquals(9.0, arrayParsed[0].models[OpenMeteoModelIds.ECMWF]!!.temperature2m[0]!!, 1e-6)

        assertTrue(OpenMeteoParser.parseForecastBatchJson("[null, null]").isEmpty())

        var threwOnBlank = false
        try {
            OpenMeteoParser.parseForecastBatchJson("   ")
        } catch (_: IllegalArgumentException) {
            threwOnBlank = true
        }
        assertTrue(threwOnBlank)
    }

    @Test
    fun t5_p2_openMeteoForecastData_validModelHelpers_filterAllNullAndNonFiniteModels() {
        val data = OpenMeteoForecastData(
            latitude = 52.52,
            longitude = 13.41,
            utcOffsetSeconds = 0,
            timezone = "GMT",
            timestampsEpochMillis = listOf(1_700_000_000_000L, 1_700_003_600_000L),
            models = mapOf(
                OpenMeteoModelIds.ECMWF to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.ECMWF,
                    temperature2m = listOf(14.0, 15.0),
                    precipitation = listOf(0.0, 0.2),
                    windSpeed10m = listOf(10.0, 12.0)
                ),
                OpenMeteoModelIds.GFS to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.GFS,
                    temperature2m = listOf(Double.NaN, null),
                    precipitation = listOf(null, null),
                    windSpeed10m = listOf(Double.POSITIVE_INFINITY, null)
                )
            )
        )

        assertEquals(setOf(OpenMeteoModelIds.ECMWF), data.validModelTemps().keys)
        assertEquals(setOf(OpenMeteoModelIds.ECMWF), data.validModelPrecips().keys)
        assertEquals(setOf(OpenMeteoModelIds.ECMWF), data.validModelWinds().keys)
    }

    @Test
    fun t5_p2_forecastConfidenceEngine_disjointModelHours_reportsInsufficientModels() {
        val result = ForecastConfidenceEngine.computeConfidence(
            modelTemps = mapOf(
                "ecmwf_ifs025" to listOf(15.0, null),
                "gfs_global" to listOf(null, 16.0)
            ),
            modelPrecips = emptyMap(),
            modelWinds = emptyMap()
        )
        assertFalse(result.hasEnoughModels)
        assertEquals(45, result.percent)
        assertEquals("Unsicher", result.tierLabel)
        assertEquals(2, result.hourlyConfidences.size)
    }

    @Test
    fun t5_p2_forecastConfidenceEngine_extremeMaxValueDivergence_staysFiniteAndBounded() {
        val result = ForecastConfidenceEngine.computeConfidence(
            modelTemps = mapOf("ecmwf_ifs025" to listOf(-Double.MAX_VALUE), "gfs_global" to listOf(Double.MAX_VALUE)),
            modelPrecips = mapOf("ecmwf_ifs025" to listOf(0.0), "gfs_global" to listOf(Double.MAX_VALUE)),
            modelWinds = mapOf("ecmwf_ifs025" to listOf(0.0), "gfs_global" to listOf(Double.MAX_VALUE))
        )
        assertTrue(result.percent in 0..100)
        assertEquals("Unsicher", result.tierLabel)
        assertTrue(result.divergenceExplanation.isNotBlank())
    }

    @Test
    fun t5_p2_trackRecordRepository_enforceRetention_capsAt200AndPreservesVerified() {
        val repo = TrackRecordRepository(InMemoryKeyValueStore())
        val base = 1_700_000_000_000L
        val snapshots = (1..240).map { i ->
            ForecastSnapshot(
                id = "s_$i",
                locationKey = "52.52,13.41",
                cityName = "Berlin",
                latitude = 52.52,
                longitude = 13.41,
                capturedAtMillis = base,
                targetEpochMillis = base + i * 3_600_000L,
                predictedTempC = 15.0,
                predictedPrecipMm = 0.0,
                predictedPrecipProb = 10,
                predictedWindKmh = 10.0,
                predictedDrySportWindow = true,
                verified = (i <= 50),
                verifiedAtMillis = if (i <= 50) base + i * 3_600_000L else null,
                observedTempC = if (i <= 50) 15.0 else null,
                observedPrecipMm = if (i <= 50) 0.0 else null,
                observedWindKmh = if (i <= 50) 10.0 else null
            )
        }

        val stored = repo.recordForecastSnapshots(snapshots)
        assertEquals(TrackRecordRepository.MAX_SNAPSHOTS, stored.size)
        assertEquals(50, stored.count { it.verified })
    }

    @Test
    fun t5_p2_trackRecordRepository_verifyCurrentObservation_exactBoundaryAndInvalidObs() {
        val repo = TrackRecordRepository(InMemoryKeyValueStore())
        val target = 1_700_100_000_000L
        repo.recordForecastSnapshot(
            ForecastSnapshot("s1", "52.52,13.41", "Berlin", 52.52, 13.41, target - 3_600_000L, target, 14.0, 0.0, 10, 10.0, true)
        )

        // NaN temp ignored
        assertEquals(0, repo.verifyCurrentObservation(52.52, 13.41, ObservedWeatherPoint(target, Double.NaN, 0.0, 10.0)))
        // Before target (-1ms) ignored
        assertEquals(0, repo.verifyCurrentObservation(52.52, 13.41, ObservedWeatherPoint(target - 1L, 14.0, 0.0, 10.0)))
        // Beyond 2h window (+2h + 1ms) ignored
        assertEquals(0, repo.verifyCurrentObservation(52.52, 13.41, ObservedWeatherPoint(target + 7_200_001L, 14.0, 0.0, 10.0)))
        // Exact 2h boundary accepted
        assertEquals(1, repo.verifyCurrentObservation(52.52, 13.41, ObservedWeatherPoint(target + 7_200_000L, 14.5, 0.0, 10.0)))
    }

    @Test
    fun t5_p2_historicalNormalCalculator_compareWithNormal_nonFiniteTempAndCurve24h() {
        val comp = HistoricalNormalCalculator.compareWithNormal(Double.NaN, 8.0, 20.0, 15.0)
        assertEquals(20.0, comp.normalTempC, 1e-6)
        assertEquals(0.0, comp.deltaC, 1e-6)
        assertEquals("Keine aktuelle Temperatur", comp.deltaLabel)

        val curve = HistoricalNormalCalculator.build24HourNormalCurve(6.0, 22.0)
        assertEquals(24, curve.size)
        assertEquals(6.0, curve[3].tempC, 1e-6)
        assertEquals(22.0, curve[15].tempC, 1e-6)
    }

    // =========================================================================
    // Empirical Bug-Reproducing Tests (Reproduced on Unpatched Production Code)
    // =========================================================================

    @Test
    fun t5_p1_favoritesRepository_nonFiniteCoordinates_rejectedWithoutMoshiCrash() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        val afterNan = repo.addFavorite(FavoriteLocation("nan1", "NaN City", "XX", Double.NaN, 10.0, 100L))
        val afterInf = repo.addFavorite(FavoriteLocation("inf1", "Inf City", "XX", 52.52, Double.POSITIVE_INFINITY, 200L))

        assertTrue(afterNan.isEmpty())
        assertTrue(afterInf.isEmpty())
        assertTrue(repo.getFavorites().isEmpty())
    }

    @Test
    fun t5_p1_favoritesRepository_corruptElementInJsonArray_preservesOtherValidFavorites() {
        val store = InMemoryKeyValueStore()
        store.putString(
            "favorites_json",
            """[
                {"id":"1","name":null,"country":"DE","latitude":52.52,"longitude":13.40,"addedAtMillis":100},
                {"id":"2","name":"München","country":"DE","latitude":48.13,"longitude":11.58,"addedAtMillis":200}
            ]"""
        )
        val repo = FavoritesRepository(store)
        val loaded = repo.getFavorites()

        assertEquals(1, loaded.size)
        assertEquals("München", loaded[0].name)
    }

    @Test
    fun t5_p1_weatherScenario_isNightTime_nanSolarElevationFallsBackToLocalHour() {
        val isNight = WeatherScenario.isNightTime(
            nowEpochSec = 1_700_000_000L,
            sunriseEpochSec = null,
            sunsetEpochSec = null,
            fallbackSolarElevationDeg = Double.NaN,
            fallbackLocalHour = 23
        )
        assertTrue("NaN solar elevation should fall back to local hour 23 -> night", isNight)
    }

    @Test
    fun t5_p2_openMeteoForecastData_alignOwmForecast_dryOwmProducesZeroPrecipAndGuardsLongOverflow() {
        val t0 = 1_700_000_000_000L
        val t1 = 1_700_003_600_000L
        val om = OpenMeteoForecastData(
            latitude = 52.52,
            longitude = 13.41,
            utcOffsetSeconds = 0,
            timezone = "GMT",
            timestampsEpochMillis = listOf(t0, t1, Long.MIN_VALUE),
            models = emptyMap()
        )
        val owm = ForecastResponse(
            list = listOf(
                ForecastItem(
                    dt = t0 / 1000L,
                    main = ForecastMain(16.0, 15.0, 17.0),
                    weather = listOf(WeatherCondition("Clear", "clear sky")),
                    wind = WindInfo(3.0, 180),
                    rain = null
                ),
                ForecastItem(
                    dt = t1 / 1000L,
                    main = ForecastMain(15.0, 14.0, 16.0),
                    weather = listOf(WeatherCondition("Rain", "light rain")),
                    wind = WindInfo(4.0, 180),
                    rain = ForecastRainInfo(3.0)
                ),
                ForecastItem(
                    dt = 0L,
                    main = ForecastMain(99.0, 99.0, 99.0),
                    weather = emptyList()
                )
            ),
            city = ForecastCity(0)
        )

        val aligned = om.alignOwmForecast(owm)
        assertNotNull("Dry OWM ForecastItem (rain=null) must align as 0.0 mm, not null", aligned.precips[0])
        assertEquals(0.0, aligned.precips[0]!!, 1e-6)
        assertEquals(1.0, aligned.precips[1]!!, 1e-6)
        assertNull("Long.MIN_VALUE must not falsely match dt=0 via signed Long overflow", aligned.temps[2])
    }

    @Test
    fun t5_p2_openMeteoParser_parseForecastJson_hugeUnixTimestampGuardsLongOverflow() {
        val json = """
            {
              "latitude": 52.52,
              "longitude": 13.41,
              "utc_offset_seconds": 0,
              "hourly": {
                "time": [9223372036854776, 1e30, 1795042800],
                "temperature_2m": [10.0, 12.0, 15.0]
              }
            }
        """.trimIndent()
        val parsed = OpenMeteoParser.parseForecastJson(json)
        assertTrue(
            "Overflowing unixtime values must be dropped rather than wrapping negative",
            parsed.timestampsEpochMillis.all { it > 0L }
        )
        assertEquals(listOf(1_795_042_800_000L), parsed.timestampsEpochMillis)
        assertEquals(listOf(15.0), parsed.toHourlyWeatherPoints().map { it.tempC })
    }

    @Test
    fun t5_p2_openMeteoForecastData_toHourlyWeatherPoints_emptyPrimaryModelFallsBackToPopulatedModel() {
        val om = OpenMeteoForecastData(
            latitude = 52.52,
            longitude = 13.41,
            utcOffsetSeconds = 0,
            timezone = "GMT",
            timestampsEpochMillis = listOf(1_700_000_000_000L),
            models = mapOf(
                OpenMeteoModelIds.BEST_MATCH to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.BEST_MATCH,
                    temperature2m = listOf(null),
                    uvIndex = listOf(4.0)
                ),
                OpenMeteoModelIds.GFS to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.GFS,
                    temperature2m = listOf(22.5),
                    precipitation = listOf(3.0),
                    windSpeed10m = listOf(18.0),
                    uvIndex = listOf(4.0)
                )
            )
        )
        val pts = om.toHourlyWeatherPoints(OpenMeteoModelIds.BEST_MATCH)
        assertEquals(1, pts.size)
        assertEquals(22.5, pts[0].tempC, 1e-6)
    }

    @Test
    fun t5_p2_sportAdvisor_evaluateHoursToSkip_deduplicatesEpochMillisWorstCase() {
        val base = 1_700_000_000_000L
        val clean10 = hourPoint(base, tempC = 18.0, precipMm = 0.0, capeJkg = 40.0, weatherCode = 1)
        val storm10 = hourPoint(base, tempC = 18.0, precipMm = 10.0, precipProb = 95, windKmh = 55.0, gustKmh = 75.0, capeJkg = 2000.0, weatherCode = 95)
        val clean11 = hourPoint(base + 3_600_000L, tempC = 18.0, precipMm = 0.0, capeJkg = 40.0, weatherCode = 1)

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, listOf(clean10, storm10, clean11))
        assertEquals(2, verdict.allEvaluations.size)
        assertEquals(1, verdict.skipHoursCount)
        assertEquals(1, verdict.goodHoursCount)
    }

    @Test
    fun t5_p2_trackRecordRepository_saveForecastSnapshotsFromForecast_duplicateTimestampAndLongOverflowSafe() {
        val base = 1_700_000_000_000L
        val dryStormAt1h = OpenMeteoForecastData(
            latitude = 52.52,
            longitude = 13.41,
            utcOffsetSeconds = 0,
            timezone = "GMT",
            timestampsEpochMillis = listOf(base + 3_600_000L, base + 3_600_000L),
            models = mapOf(
                OpenMeteoModelIds.BEST_MATCH to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.BEST_MATCH,
                    temperature2m = listOf(18.0, 18.0),
                    precipitation = listOf(0.0, 0.0),
                    precipitationProbability = listOf(20.0, 5.0),
                    windSpeed10m = listOf(55.0, 8.0),
                    windGusts10m = listOf(75.0, 12.0),
                    cape = listOf(2200.0, 20.0),
                    weatherCode = listOf(95.0, 1.0)
                )
            )
        )
        val repo = TrackRecordRepository(InMemoryKeyValueStore())
        val saved = repo.saveForecastSnapshotsFromForecast(
            latitude = 52.52,
            longitude = 13.41,
            cityName = "Berlin",
            forecast = dryStormAt1h,
            capturedAtMillis = base,
            leadHours = listOf(1)
        )
        assertEquals(1, saved.size)
        assertFalse(
            "Worst-case duplicate timestamp with CAPE 2200 / WMO 95 must NOT be saved as drySport=true",
            saved[0].predictedDrySportWindow
        )
    }

    @Test
    fun t5_p2_historicalNormalCalculator_extremeMaxBounds_neverReturnsNaN() {
        val result = HistoricalNormalCalculator.computeSinusoidalNormalTemp(-Double.MAX_VALUE, Double.MAX_VALUE, 9.0)
        assertTrue("Result must be finite, not NaN: $result", result.isFinite())
    }

    @Test
    fun t5_p2_sportAdvisor_winterSport_skipsLiquidRainWmoCodesAboveFreezing() {
        val base = 1_700_000_000_000L
        val rainAt1C = hourPoint(
            epochMillis = base,
            tempC = 1.0,
            precipMm = 4.5,
            precipProb = 95,
            weatherCode = 63 // moderate rain
        )
        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, listOf(rainAt1C))
        assertEquals(1, verdict.skipHoursCount)
        assertTrue(SkipReason.RAIN in verdict.hoursToSkip.first().reasons)
    }
}


