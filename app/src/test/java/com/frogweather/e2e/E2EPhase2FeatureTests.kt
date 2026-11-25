package com.frogweather.e2e

import com.frogweather.analysis.ConfidenceResult
import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.HistoricalNormalCalculator
import com.frogweather.analysis.HistoryGraphData
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.analysis.SportVerdict
import com.frogweather.analysis.SportWindow
import com.frogweather.data.ForecastSnapshot
import com.frogweather.data.ObservedWeatherPoint
import com.frogweather.data.TrackRecordRepository
import com.frogweather.data.TrackRecordSummary
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelHourly
import com.frogweather.network.OpenMeteoModelIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Stage A E2E Opaque-Box Feature Tests — Phase 2 (Features 5–7).
 *
 * Derived strictly from:
 * - ORIGINAL_REQUEST.md (R2 & Phase 2 Acceptance Criteria)
 * - PROJECT.md § Interface Contracts (§ 4 Sport & Weather Analysis Contract)
 * - TEST_INFRA.md (Phase 2 Tier 1 & Tier 2 Design Matrix)
 * - Verified Open-Meteo Forecast/Multi-Model/Archive API specifications
 *
 * Coverage:
 * - Feature 5: Historical Normal Curve & History View (6 Tier 1 + 6 Tier 2 = 12 tests)
 * - Feature 6: UV Index & "Hours to Skip" — 8 Sports (8 Tier 1 + 7 Tier 2 = 15 tests)
 * - Feature 7: Multi-Model Forecast Confidence & Track Record (6 Tier 1 + 7 Tier 2 = 13 tests)
 * Total: 40 @Test methods (20 Tier 1 >= 19, 20 Tier 2 >= 17).
 *
 * Stage B Note: Contract entry points (`HistoricalNormalCalculator`, `SportAdvisor`, `SportType`,
 * `HourlyWeatherPoint`, `ForecastConfidenceEngine`, `TrackRecordRepository`) match PROJECT.md § 4;
 * auxiliary DTO property names marked with `// Stage B reconcile` should be aligned against final
 * M2 public signatures if M2 chose different field names.
 */
class E2EPhase2FeatureTests {

    // =========================================================================
    // FEATURE 5: Historical Normal Curve & History View — Tier 1 (6 Tests)
    // =========================================================================

    @Test
    fun f5_tier1_sinusoidalNormalApproachesDailyMinAtEarlyMorning() {
        val dailyMinC = 8.0
        val dailyMaxC = 22.0
        val midpoint = (dailyMinC + dailyMaxC) / 2.0

        val hour5 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, 5.0)
        val hour6 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, 6.0)
        val fullDayTemps = (0..23).map { h ->
            HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, h.toDouble())
        }

        assertTrue("Early morning (05:00) normal ($hour5) must be below daily midpoint ($midpoint)", hour5 < midpoint)
        assertTrue("Early morning (06:00) normal ($hour6) must be below daily midpoint ($midpoint)", hour6 < midpoint)
        assertTrue("05:00/06:00 normal should approach dailyMinC ($dailyMinC)", minOf(hour5, hour6) <= dailyMinC + 3.0)
        assertEquals("24h minimum of sinusoidal curve must approach dailyMinC", dailyMinC, fullDayTemps.min(), 0.75)
    }

    @Test
    fun f5_tier1_sinusoidalNormalApproachesDailyMaxAtAfternoon() {
        val dailyMinC = 8.0
        val dailyMaxC = 22.0
        val midpoint = (dailyMinC + dailyMaxC) / 2.0

        val hour14 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, 14.0)
        val hour15 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, 15.0)
        val fullDayTemps = (0..23).map { h ->
            HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, h.toDouble())
        }

        assertTrue("Afternoon (14:00) normal ($hour14) must be above daily midpoint ($midpoint)", hour14 > midpoint)
        assertTrue("Afternoon (15:00) normal ($hour15) must be above daily midpoint ($midpoint)", hour15 > midpoint)
        assertTrue("14:00/15:00 normal should approach dailyMaxC ($dailyMaxC)", maxOf(hour14, hour15) >= dailyMaxC - 3.0)
        assertEquals("24h maximum of sinusoidal curve must approach dailyMaxC", dailyMaxC, fullDayTemps.max(), 0.75)
    }

    @Test
    fun f5_tier1_sinusoidalNormalIntermediateHoursStrictlyBetweenMinAndMax() {
        val dailyMinC = 10.0
        val dailyMaxC = 20.0

        val morning10 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, 10.0)
        val evening20 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, 20.0)

        assertTrue("10:00 normal ($morning10) must lie strictly between min and max", morning10 > dailyMinC && morning10 < dailyMaxC)
        assertTrue("20:00 normal ($evening20) must lie strictly between min and max", evening20 > dailyMinC && evening20 < dailyMaxC)

        for (h in 0..23) {
            val t = HistoricalNormalCalculator.computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, h.toDouble())
            assertFalse("Hour $h normal must not be NaN", t.isNaN())
            assertTrue("Hour $h normal ($t) must stay within [$dailyMinC, $dailyMaxC]", t in (dailyMinC - 1e-6)..(dailyMaxC + 1e-6))
        }
    }

    @Test
    fun f5_tier1_buildHistorySeries7DaysPreservesLatest7Points() {
        val fixture = E2ETestFixtures.sampleDailyHistoryData(days = 30, baseMinC = 10.0, baseMaxC = 22.0)

        // Stage B reconcile: HistoryGraphData.points
        val graph: HistoryGraphData = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = fixture.dates,
            mins = fixture.mins,
            maxs = fixture.maxs,
            means = fixture.means,
            days = 7
        )

        assertEquals("7-day history graph must contain 7 points", 7, graph.points.size)
        assertEquals("7-day slice should end at latest date", fixture.dates.last(), graph.points.last().date)
        assertEquals("7-day slice should start 7 days from end", fixture.dates[23], graph.points.first().date)
    }

    @Test
    fun f5_tier1_buildHistorySeries14DaysPreserves14Points() {
        val fixture = E2ETestFixtures.sampleDailyHistoryData(days = 30, baseMinC = 9.0, baseMaxC = 21.0)

        val graph: HistoryGraphData = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = fixture.dates,
            mins = fixture.mins,
            maxs = fixture.maxs,
            means = fixture.means,
            days = 14
        )

        assertEquals("14-day history graph must contain 14 points", 14, graph.points.size)
        assertEquals(fixture.dates[16], graph.points.first().date)
        assertEquals(fixture.dates.last(), graph.points.last().date)
        assertTrue(
            "Every history point should satisfy min <= mean <= max",
            graph.points.all { it.minTempC <= it.meanTempC + 1e-6 && it.meanTempC <= it.maxTempC + 1e-6 }
        )
    }

    @Test
    fun f5_tier1_buildHistorySeries30DaysPreserves30Points() {
        val fixture = E2ETestFixtures.sampleDailyHistoryData(days = 30, baseMinC = 11.0, baseMaxC = 24.0)

        val graph: HistoryGraphData = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = fixture.dates,
            mins = fixture.mins,
            maxs = fixture.maxs,
            means = fixture.means,
            days = 30
        )

        assertEquals("30-day history graph must contain 30 points", 30, graph.points.size)
        assertEquals(fixture.dates.first(), graph.points.first().date)
        assertEquals(fixture.dates.last(), graph.points.last().date)
        assertEquals(fixture.means.first(), graph.points.first().meanTempC, 1e-6)
    }

    // =========================================================================
    // FEATURE 5: Historical Normal Curve & History View — Tier 2 (6 Tests)
    // =========================================================================

    @Test
    fun f5_tier2_equalMinAndMaxYieldsConstantTempWithoutNaN() {
        val constantTemp = 12.5
        val hours = listOf(0.0, 5.0, 12.0, 15.0, 23.0)

        hours.forEach { hour ->
            val result = HistoricalNormalCalculator.computeSinusoidalNormalTemp(constantTemp, constantTemp, hour)
            assertFalse("Result at hour $hour must not be NaN", result.isNaN())
            assertTrue("Result at hour $hour must be finite", result.isFinite())
            assertEquals("Equal min/max must produce constant temperature", constantTemp, result, 1e-6)
        }
    }

    @Test
    fun f5_tier2_invertedMinGreaterThanMaxHandledGracefullyWithoutCrash() {
        val result = HistoricalNormalCalculator.computeSinusoidalNormalTemp(
            dailyMinC = 20.0,
            dailyMaxC = 10.0,
            hourOfDay = 14.0
        )

        assertFalse("Inverted min/max must not yield NaN", result.isNaN())
        assertTrue("Inverted min/max must yield finite temperature", result.isFinite())
        assertTrue("Result ($result) must stay bounded within [10.0, 20.0]", result in 10.0..20.0)
    }

    @Test
    fun f5_tier2_hourOfDayWrapAroundIsPeriodicAndBounded() {
        val minC = 5.0
        val maxC = 19.0

        val at0 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(minC, maxC, 0.0)
        val at24 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(minC, maxC, 24.0)
        val atMinus2 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(minC, maxC, -2.0)
        val at22 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(minC, maxC, 22.0)
        val at26 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(minC, maxC, 26.0)
        val at2 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(minC, maxC, 2.0)

        assertEquals("0.0h and 24.0h must wrap periodically", at0, at24, 1e-4)
        assertEquals("-2.0h and 22.0h must wrap periodically", atMinus2, at22, 1e-4)
        assertEquals("26.0h and 2.0h must wrap periodically", at26, at2, 1e-4)
        assertTrue(atMinus2 in (minC - 1e-6)..(maxC + 1e-6))
        assertTrue(at26 in (minC - 1e-6)..(maxC + 1e-6))
    }

    @Test
    fun f5_tier2_shorterApiListThanRequested30DaysSlicesSafely() {
        val shortFixture = E2ETestFixtures.sampleDailyHistoryData(days = 10)

        val graph: HistoryGraphData = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = shortFixture.dates,
            mins = shortFixture.mins,
            maxs = shortFixture.maxs,
            means = shortFixture.means,
            days = 30
        )

        assertEquals("Requesting 30 days from 10-day API list must return 10 points safely", 10, graph.points.size)
        assertEquals(shortFixture.dates.first(), graph.points.first().date)
        assertEquals(shortFixture.dates.last(), graph.points.last().date)
    }

    @Test
    fun f5_tier2_emptyHistoryInputsReturnEmptyUnavailableGraphState() {
        val graph: HistoryGraphData = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = emptyList(),
            mins = emptyList(),
            maxs = emptyList(),
            means = emptyList(),
            days = 7
        )

        assertTrue("Empty input series must produce empty graph points", graph.points.isEmpty())
    }

    @Test
    fun f5_tier2_mismatchedInputLengthsAndZeroDaysHandledSafely() {
        val dates = listOf("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05")
        val mins = listOf(5.0, 6.0, 7.0) // shortest = 3
        val maxs = listOf(15.0, 16.0, 17.0, 18.0, 19.0)
        val means = listOf(10.0, 11.0, 12.0, 13.0)

        val mismatchedGraph = HistoricalNormalCalculator.buildHistorySeries(dates, mins, maxs, means, days = 14)
        assertTrue("Mismatched lists must clamp to shortest available length (<= 3)", mismatchedGraph.points.size <= 3)

        val zeroDaysGraph = HistoricalNormalCalculator.buildHistorySeries(dates, mins, maxs, means, days = 0)
        assertTrue("days <= 0 must return empty points safely", zeroDaysGraph.points.isEmpty())
    }

    // =========================================================================
    // FEATURE 6: UV Index & "Hours to Skip" — 8 Sports — Tier 1 (8 Tests)
    // =========================================================================

    @Test
    fun f6_tier1_allEightSportTypesHaveExactIdsAndGermanDisplayNames() {
        val allSports = SportType.values()
        assertEquals("Exactly 8 SportType entries required per R2", 8, allSports.size)

        val expected = mapOf(
            SportType.RUNNING to ("running" to "Laufen/Joggen"),
            SportType.CYCLING to ("cycling" to "Radfahren"),
            SportType.HIKING to ("hiking" to "Wandern"),
            SportType.OUTDOOR_FITNESS to ("outdoor_fitness" to "Outdoor-Fitness"),
            SportType.TEAM_SPORT to ("team_sport" to "Fußball/Teamsport"),
            SportType.SWIMMING to ("swimming" to "Schwimmen/Wassersport"),
            SportType.WINTER_SPORT to ("winter_sport" to "Wintersport"),
            SportType.WALKING to ("walking" to "Spazieren")
        )

        expected.forEach { (sport, pair) ->
            assertEquals("Unexpected id for $sport", pair.first, sport.id)
            assertEquals("Unexpected German displayName for $sport", pair.second, sport.displayName)
        }
    }

    @Test
    fun f6_tier1_highUvMiddayFlagsSkipWarningWhileMorningRemainsSafe() {
        val hours = E2ETestFixtures.sample24HourSeries(
            minTempC = 15.0,
            maxTempC = 25.0,
            peakUv = 4.0,
            highUvHours = setOf(11, 12, 13, 14, 15) // sets uvIndex >= 8.5
        )

        // Stage B reconcile: SportVerdict.hoursToSkip
        val verdict: SportVerdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, hours)
        val skippedEpochs = verdict.hoursToSkip.map { it.hour.epochMillis }.toSet()

        for (h in 11..15) {
            val epoch = hours[h].epochMillis
            assertTrue("High-UV midday hour $h must be flagged in hoursToSkip", epoch in skippedEpochs)
        }
        assertFalse("Mild morning hour 8 (low UV) should NOT be flagged", hours[8].epochMillis in skippedEpochs)
        assertTrue(
            "Flagged UV hour should include non-blank reason",
            verdict.hoursToSkip.first { it.hour.epochMillis == hours[12].epochMillis }.reasonLabels.any { it.isNotBlank() }
        )
    }

    @Test
    fun f6_tier1_heavyRainAndThunderstormFlagHoursToSkipWithReasons() {
        val hours = E2ETestFixtures.sample24HourSeries(
            minTempC = 14.0,
            maxTempC = 22.0,
            thunderstormHours = setOf(14, 15, 16)
        )

        val hikingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.HIKING, hours)
        val swimmingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.SWIMMING, hours)

        val hikingSkipped = hikingVerdict.hoursToSkip.map { it.hour.epochMillis }.toSet()
        val swimmingSkipped = swimmingVerdict.hoursToSkip.map { it.hour.epochMillis }.toSet()

        for (h in 14..16) {
            assertTrue("Hiking must skip thunderstorm hour $h", hours[h].epochMillis in hikingSkipped)
            assertTrue("Swimming must skip thunderstorm hour $h", hours[h].epochMillis in swimmingSkipped)
        }
        assertTrue(
            "Every skipped hour must provide a non-blank explanation reason",
            hikingVerdict.hoursToSkip.all { it.reasonLabels.isNotEmpty() && it.reasonLabels.all { label -> label.isNotBlank() } }
        )
    }

    @Test
    fun f6_tier1_cyclingFlagsHighWindGustsStrongerThanWalking() {
        // Dry, mild temperature, strong gusts (38 km/h wind, 58 km/h gusts) in hours 10..15
        val hours = (0 until 24).map { h ->
            val windy = h in 10..15
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = 18.0,
                precipMm = 0.0,
                precipProb = 5,
                windKmh = if (windy) 38.0 else 10.0,
                gustKmh = if (windy) 58.0 else 15.0,
                uvIndex = 3.0,
                capeJkg = 30.0
            )
        }

        val cyclingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, hours)
        val walkingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WALKING, hours)

        val cyclingSkipped = cyclingVerdict.hoursToSkip.map { it.hour.epochMillis }.toSet()
        assertTrue("Cycling must flag strong-gust hours 10..15", (10..15).all { hours[it].epochMillis in cyclingSkipped })
        assertTrue(
            "Cycling should flag more (or at least stricter) wind hours than Walking",
            cyclingVerdict.hoursToSkip.size >= walkingVerdict.hoursToSkip.size
        )
    }

    @Test
    fun f6_tier1_winterSportAcceptsSubZeroSnowWhereCyclingAndSwimmingWarn() {
        val hours = (0 until 24).map { h ->
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = -4.0,
                precipMm = 0.5,
                precipProb = 60,
                windKmh = 10.0,
                gustKmh = 16.0,
                uvIndex = 2.0,
                capeJkg = 0.0,
                weatherCode = 71 // light snow
            )
        }

        val winterVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, hours)
        val cyclingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, hours)
        val swimmingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.SWIMMING, hours)

        assertTrue(
            "Winter sport must allow more sub-zero snow hours than Cycling (${winterVerdict.hoursToSkip.size} vs ${cyclingVerdict.hoursToSkip.size})",
            winterVerdict.hoursToSkip.size < cyclingVerdict.hoursToSkip.size
        )
        assertTrue(
            "Winter sport must allow more sub-zero snow hours than Swimming",
            winterVerdict.hoursToSkip.size < swimmingVerdict.hoursToSkip.size
        )
        assertNotNull("Winter sport should find a usable window on mild -4°C snow day", SportAdvisor.findBestSportWindow(SportType.WINTER_SPORT, hours))
    }

    @Test
    fun f6_tier1_findBestSportWindowIdentifiesContiguousSafeDryWindow() {
        val hours = E2ETestFixtures.sample24HourSeries(
            minTempC = 12.0,
            maxTempC = 20.0,
            peakUv = 4.0,
            rainHours = (0..9).toSet(),
            thunderstormHours = (16..23).toSet()
        )

        // Stage B reconcile: SportWindow(startEpochMillis, endEpochMillis, ...)
        val bestWindow: SportWindow? = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)

        assertNotNull("Safe dry block 10..15 should yield a non-null SportWindow", bestWindow)
        val windowStart = bestWindow!!.startEpochMillis
        val windowEnd = bestWindow.endEpochMillis
        assertTrue("Best window start must be >= hour 10", windowStart >= hours[10].epochMillis)
        assertTrue("Best window last hour must be <= hour 15", bestWindow.hours.last().epochMillis <= hours[15].epochMillis)
        assertTrue("Best window exclusive end must be <= hour 16", windowEnd <= hours[16].epochMillis)
        assertTrue("Best window end must be >= start", windowEnd >= windowStart)
    }

    @Test
    fun f6_tier1_uvIndexFallbackSelectsGfsAndBestMatchWhenEcmwfAndIconD2AreNull() {
        // Matches live Open-Meteo probe: ecmwf_ifs025 & icon_d2 return null uv_index;
        // gfs_global / best_match provide populated UV values via OpenMeteoForecastData.toHourlyWeatherPoints.
        val timestamps = (0 until 24).map { h -> E2ETestFixtures.BASE_EPOCH_MILLIS + h * E2ETestFixtures.HOUR_MILLIS }
        val forecastData = OpenMeteoForecastData(
            latitude = 52.52,
            longitude = 13.405,
            utcOffsetSeconds = 0,
            timezone = "GMT",
            timestampsEpochMillis = timestamps,
            models = mapOf(
                OpenMeteoModelIds.ECMWF to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.ECMWF,
                    temperature2m = List(24) { 21.0 },
                    uvIndex = List(24) { null }
                ),
                OpenMeteoModelIds.ICON_D2 to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.ICON_D2,
                    temperature2m = List(24) { 21.0 },
                    uvIndex = List(24) { null }
                ),
                OpenMeteoModelIds.GFS to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.GFS,
                    temperature2m = List(24) { 21.0 },
                    uvIndex = List(24) { h -> if (h in 12..14) 9.2 else 2.0 }
                ),
                OpenMeteoModelIds.BEST_MATCH to OpenMeteoModelHourly(
                    modelId = OpenMeteoModelIds.BEST_MATCH,
                    temperature2m = List(24) { 21.0 },
                    uvIndex = List(24) { h -> if (h in 12..14) 9.0 else 2.0 }
                )
            )
        )

        val hoursWithResolvedUv = forecastData.toHourlyWeatherPoints(primaryModel = OpenMeteoModelIds.ECMWF)
        val resolvedUvs: List<Double> = hoursWithResolvedUv.map { it.uvIndex }
        assertEquals(24, resolvedUvs.size)
        assertTrue("Hour 12 UV must fall back to gfs_global/best_match (>= 8.5), not 0.0", resolvedUvs[12] >= 8.5)

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.OUTDOOR_FITNESS, hoursWithResolvedUv)
        val skippedEpochs = verdict.hoursToSkip.map { it.hour.epochMillis }.toSet()

        assertTrue("High fallback UV at hour 12 must trigger skip warning", hoursWithResolvedUv[12].epochMillis in skippedEpochs)
    }

    @Test
    fun f6_tier1_teamSportAndOutdoorFitnessEvaluateMixedWeatherDay() {
        val hours = E2ETestFixtures.sample24HourSeries(
            minTempC = 13.0,
            maxTempC = 23.0,
            rainHours = setOf(17, 18),
            highUvHours = setOf(13)
        )

        val teamVerdict = SportAdvisor.evaluateHoursToSkip(SportType.TEAM_SPORT, hours)
        val fitnessVerdict = SportAdvisor.evaluateHoursToSkip(SportType.OUTDOOR_FITNESS, hours)

        assertTrue("Team sport should flag rainy evening hours", teamVerdict.hoursToSkip.isNotEmpty())
        assertTrue("Outdoor fitness should flag hazardous hours", fitnessVerdict.hoursToSkip.isNotEmpty())
        assertTrue("Summary must be non-blank", teamVerdict.summaryText.isNotBlank())
        assertTrue("Summary must be non-blank", fitnessVerdict.summaryText.isNotBlank())
    }

    // =========================================================================
    // FEATURE 6: UV Index & "Hours to Skip" — 8 Sports — Tier 2 (7 Tests)
    // =========================================================================

    @Test
    fun f6_tier2_emptyHourlyListReturnsSafeEmptyVerdictAndNullWindow() {
        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, emptyList())
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.RUNNING, emptyList())

        assertTrue("Empty hours must produce 0 skipped hours", verdict.hoursToSkip.isEmpty())
        assertNull("Empty hours must yield null bestSportWindow", bestWindow)
    }

    @Test
    fun f6_tier2_all24HoursHazardousYieldsNullBestWindowAndAll24Skipped() {
        val allStormHours = E2ETestFixtures.sample24HourSeries(
            thunderstormHours = (0..23).toSet()
        )

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, allStormHours)
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.CYCLING, allStormHours)

        assertEquals("All 24 thunderstorm hours must be flagged", 24, verdict.hoursToSkip.size)
        assertNull("All-hazardous day must yield null bestSportWindow", bestWindow)
    }

    @Test
    fun f6_tier2_zeroHazardMildDayYieldsZeroHoursToSkip() {
        val idealHours = (0 until 24).map { h ->
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = 18.0,
                precipMm = 0.0,
                precipProb = 0,
                windKmh = 7.0,
                gustKmh = 12.0,
                uvIndex = 2.5,
                capeJkg = 40.0,
                weatherCode = 1
            )
        }

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.WALKING, idealHours)
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.WALKING, idealHours)

        assertTrue("Ideal mild day must have 0 skipped hours for Walking", verdict.hoursToSkip.isEmpty())
        assertNotNull("Ideal mild day must yield a non-null SportWindow", bestWindow)
    }

    @Test
    fun f6_tier2_negativeAndNaNUvIndexHandledSafelyWithoutSpuriousSkip() {
        val weirdUvHours = listOf(
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS,
                tempC = 18.0,
                uvIndex = -3.0
            ),
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + E2ETestFixtures.HOUR_MILLIS,
                tempC = 18.0,
                uvIndex = Double.NaN
            )
        )

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, weirdUvHours)

        assertTrue(
            "Negative or NaN UV on mild dry hour must not trigger spurious skip warning",
            verdict.hoursToSkip.isEmpty()
        )
    }

    @Test
    fun f6_tier2_extremeHeat40PlusFlagsOutdoorLandSports() {
        val heatHours = (0 until 24).map { h ->
            val scorching = h in 12..16
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = if (scorching) 41.0 else 22.0,
                uvIndex = if (scorching) 6.0 else 1.0
            )
        }

        val sportsChecked = listOf(
            SportType.RUNNING,
            SportType.CYCLING,
            SportType.HIKING,
            SportType.OUTDOOR_FITNESS,
            SportType.TEAM_SPORT
        )
        sportsChecked.forEach { sport ->
            val skipped = SportAdvisor.evaluateHoursToSkip(sport, heatHours).hoursToSkip.map { it.hour.epochMillis }.toSet()
            assertTrue(
                "Extreme heat 41°C at hour 14 must be skipped for $sport",
                heatHours[14].epochMillis in skipped
            )
        }
    }

    @Test
    fun f6_tier2_extremeColdMinus20OrLowerFlagsEvenWinterSportAndWalking() {
        val arcticHours = (0 until 24).map { h ->
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = -23.0,
                windKmh = 30.0,
                gustKmh = 50.0,
                uvIndex = 1.0,
                weatherCode = 71
            )
        }

        val winterVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, arcticHours)
        val walkingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WALKING, arcticHours)

        assertTrue("Extreme cold -23°C must flag hours even for Winter Sport", winterVerdict.hoursToSkip.isNotEmpty())
        assertTrue("Extreme cold -23°C must flag hours for Walking", walkingVerdict.hoursToSkip.isNotEmpty())
    }

    @Test
    fun f6_tier2_boundaryThresholdsUv79Vs80AndLowVsHighCapeTransitionDeterministically() {
        val uv79 = E2ETestFixtures.sampleHourlyPoint(
            epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS,
            tempC = 20.0,
            uvIndex = 7.9,
            capeJkg = 100.0
        )
        val uv80 = E2ETestFixtures.sampleHourlyPoint(
            epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + E2ETestFixtures.HOUR_MILLIS,
            tempC = 20.0,
            uvIndex = 8.0,
            capeJkg = 100.0
        )
        val lowCape = E2ETestFixtures.sampleHourlyPoint(
            epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + 2 * E2ETestFixtures.HOUR_MILLIS,
            tempC = 20.0,
            uvIndex = 3.0,
            capeJkg = 200.0,
            weatherCode = 1
        )
        val highCapeStorm = E2ETestFixtures.sampleHourlyPoint(
            epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + 3 * E2ETestFixtures.HOUR_MILLIS,
            tempC = 20.0,
            uvIndex = 3.0,
            capeJkg = 1500.0,
            weatherCode = 95
        )

        val verdict = SportAdvisor.evaluateHoursToSkip(
            SportType.RUNNING,
            listOf(uv79, uv80, lowCape, highCapeStorm)
        )
        val skippedEpochs = verdict.hoursToSkip.map { it.hour.epochMillis }.toSet()

        assertFalse("UV 7.9 just below 8.0 threshold should remain safe", uv79.epochMillis in skippedEpochs)
        assertTrue("UV 8.0 at threshold must be flagged", uv80.epochMillis in skippedEpochs)
        assertFalse("Low CAPE 200 J/kg clear hour should remain safe", lowCape.epochMillis in skippedEpochs)
        assertTrue("High CAPE 1500 J/kg storm hour must be flagged", highCapeStorm.epochMillis in skippedEpochs)
    }

    // =========================================================================
    // FEATURE 7: Multi-Model Forecast Confidence & Track Record — Tier 1 (6 Tests)
    // =========================================================================

    @Test
    fun f7_tier1_tightlyAlignedFourModelsYieldHighConfidenceHoch() {
        val fixture = E2ETestFixtures.sampleMultiModelSeries(
            hours = 24,
            includeIconD2 = true,
            tempSpreadC = 0.3,
            precipSpreadMm = 0.0,
            windSpreadKmh = 1.0
        )

        val result: ConfidenceResult = ForecastConfidenceEngine.computeConfidence(
            modelTemps = fixture.modelTemps,
            modelPrecips = fixture.modelPrecips,
            modelWinds = fixture.modelWinds
        )

        assertTrue("Tightly aligned models must yield >= 80% confidence (got ${result.percent})", result.percent >= 80)
        assertEquals("Tightly aligned models must yield tierLabel 'Hoch'", "Hoch", result.tierLabel)
    }

    @Test
    fun f7_tier1_moderateSpreadYieldsMediumConfidenceMittel() {
        val baseTemps = List(24) { 16.0 }
        val modelTemps = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to baseTemps,
            "gfs_global" to List(24) { 19.5 },
            "icon_d2" to List(24) { 14.5 },
            "owm" to List(24) { 17.5 }
        )
        val modelPrecips = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 0.2 },
            "gfs_global" to List(24) { 3.5 },
            "icon_d2" to List(24) { 1.0 },
            "owm" to List(24) { 1.8 }
        )
        val modelWinds = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 14.0 },
            "gfs_global" to List(24) { 26.0 },
            "icon_d2" to List(24) { 18.0 },
            "owm" to List(24) { 21.0 }
        )

        val result = ForecastConfidenceEngine.computeConfidence(modelTemps, modelPrecips, modelWinds)

        assertTrue("Moderate spread must yield percent in 50..79 (got ${result.percent})", result.percent in 50..79)
        assertEquals("Moderate spread must yield tierLabel 'Mittel'", "Mittel", result.tierLabel)
    }

    @Test
    fun f7_tier1_largeDivergenceYieldsUnsicherWithNonEmptyExplanation() {
        val modelTemps = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 24.0 },
            "gfs_global" to List(24) { 10.0 },
            "icon_d2" to List(24) { 15.0 },
            "owm" to List(24) { 22.0 }
        )
        val modelPrecips = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 0.0 },
            "gfs_global" to List(24) { 18.0 },
            "icon_d2" to List(24) { 6.0 },
            "owm" to List(24) { 0.5 }
        )
        val modelWinds = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 10.0 },
            "gfs_global" to List(24) { 52.0 },
            "icon_d2" to List(24) { 28.0 },
            "owm" to List(24) { 14.0 }
        )

        val result = ForecastConfidenceEngine.computeConfidence(modelTemps, modelPrecips, modelWinds)

        assertTrue("Large divergence must yield < 50% confidence (got ${result.percent})", result.percent < 50)
        assertEquals("Large divergence must yield tierLabel 'Unsicher'", "Unsicher", result.tierLabel)
        assertTrue("Divergence explanation must be non-blank", result.divergenceExplanation.isNotBlank())
    }

    private fun makeSnapshot(
        locationKey: String,
        cityName: String,
        latitude: Double,
        longitude: Double,
        targetEpochMillis: Long,
        predictedTempC: Double,
        predictedPrecipMm: Double,
        predictedDrySportWindow: Boolean,
        modelPredictedTemps: Map<String, Double>
    ): ForecastSnapshot = ForecastSnapshot(
        id = "${locationKey}_$targetEpochMillis",
        locationKey = locationKey,
        cityName = cityName,
        latitude = latitude,
        longitude = longitude,
        capturedAtMillis = targetEpochMillis - E2ETestFixtures.HOUR_MILLIS,
        targetEpochMillis = targetEpochMillis,
        predictedTempC = predictedTempC,
        predictedPrecipMm = predictedPrecipMm,
        predictedPrecipProb = if (predictedPrecipMm >= 0.1) 80 else 10,
        predictedWindKmh = 12.0,
        predictedDrySportWindow = predictedDrySportWindow,
        modelPredictedTemps = modelPredictedTemps
    )

    @Test
    fun f7_tier1_trackRecordSavesSnapshotAndComputesMeanTempErrorOnActualMatch() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = TrackRecordRepository(store)
        val t1 = E2ETestFixtures.BASE_EPOCH_MILLIS + 6 * E2ETestFixtures.HOUR_MILLIS
        val t2 = E2ETestFixtures.BASE_EPOCH_MILLIS + 12 * E2ETestFixtures.HOUR_MILLIS
        val berlinLat = 52.5200
        val berlinLon = 13.4050

        repo.recordForecastSnapshot(
            makeSnapshot(
                locationKey = "52.52,13.41",
                cityName = "Berlin",
                latitude = berlinLat,
                longitude = berlinLon,
                targetEpochMillis = t1,
                predictedTempC = 20.0,
                predictedPrecipMm = 0.0,
                predictedDrySportWindow = true,
                modelPredictedTemps = mapOf("ecmwf_ifs025" to 20.5, "gfs_global" to 19.0)
            )
        )
        repo.recordForecastSnapshot(
            makeSnapshot(
                locationKey = "52.52,13.41",
                cityName = "Berlin",
                latitude = berlinLat,
                longitude = berlinLon,
                targetEpochMillis = t2,
                predictedTempC = 15.0,
                predictedPrecipMm = 0.0,
                predictedDrySportWindow = true,
                modelPredictedTemps = mapOf("ecmwf_ifs025" to 14.8, "gfs_global" to 16.5)
            )
        )

        // Actuals: |21.5 - 20.0| = 1.5, |14.5 - 15.0| = 0.5 -> Mean Absolute Error = 1.0°C
        repo.verifyWithObservations(
            latitude = berlinLat,
            longitude = berlinLon,
            observations = listOf(
                ObservedWeatherPoint(epochMillis = t1, tempC = 21.5, precipMm = 0.0, windKmh = 12.0),
                ObservedWeatherPoint(epochMillis = t2, tempC = 14.5, precipMm = 0.0, windKmh = 12.0)
            )
        )

        val summary: TrackRecordSummary = repo.getTrackRecordSummary()
        assertEquals("Verified count must be 2", 2, summary.verifiedCount)
        assertNotNull(summary.meanTempErrorC)
        assertEquals("Mean temperature error must equal 1.0 °C", 1.0, summary.meanTempErrorC!!, 0.05)
    }

    @Test
    fun f7_tier1_trackRecordComputesRainHitRateAndDrySportWindowAccuracy() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = TrackRecordRepository(store)
        val munichLat = 48.1351
        val munichLon = 11.5820

        // 4 snapshots:
        // #1: predicted rain (4.0mm, not dry window), actual rain (3.0mm) -> rain HIT
        // #2: predicted dry (0.0mm, dry window), actual dry (0.0mm) -> rain/dry HIT + dry window HIT
        // #3: predicted rain (5.0mm, not dry window), actual dry (0.0mm) -> rain MISS
        // #4: predicted dry (0.0mm, dry window), actual dry (0.0mm) -> rain/dry HIT + dry window HIT
        // Overall rain/dry hit rate = 3/4 = 75%; dry sport window accuracy = 2/2 = 100%
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val cases = listOf(
            Triple(base + 1 * E2ETestFixtures.HOUR_MILLIS, 4.0 to false, 3.0),
            Triple(base + 2 * E2ETestFixtures.HOUR_MILLIS, 0.0 to true, 0.0),
            Triple(base + 3 * E2ETestFixtures.HOUR_MILLIS, 5.0 to false, 0.0),
            Triple(base + 4 * E2ETestFixtures.HOUR_MILLIS, 0.0 to true, 0.0)
        )

        cases.forEach { (epoch, pred, actualPrecip) ->
            repo.recordForecastSnapshot(
                makeSnapshot(
                    locationKey = "48.14,11.58",
                    cityName = "München",
                    latitude = munichLat,
                    longitude = munichLon,
                    targetEpochMillis = epoch,
                    predictedTempC = 18.0,
                    predictedPrecipMm = pred.first,
                    predictedDrySportWindow = pred.second,
                    modelPredictedTemps = emptyMap()
                )
            )
            repo.verifyWithObservations(
                latitude = munichLat,
                longitude = munichLon,
                observations = listOf(
                    ObservedWeatherPoint(
                        epochMillis = epoch,
                        tempC = 18.0,
                        precipMm = actualPrecip,
                        windKmh = 12.0
                    )
                )
            )
        }

        val summary = repo.getTrackRecordSummary()
        assertEquals(4, summary.verifiedCount)
        assertNotNull(summary.rainHitRatePct)
        assertEquals("Rain hit rate should be 75% (3/4)", 75.0, summary.rainHitRatePct!!.toDouble(), 1.0)
        assertNotNull(summary.drySportWindowAccuracyPct)
        assertEquals("Dry sport window accuracy should be 100% (2/2)", 100.0, summary.drySportWindowAccuracyPct!!.toDouble(), 1.0)
    }

    @Test
    fun f7_tier1_trackRecordIdentifiesBestModelAcrossMultiModelSnapshots() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = TrackRecordRepository(store)
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val berlinLat = 52.5200
        val berlinLon = 13.4050

        // ecmwf_ifs025 is consistently within 0.2°C, gfs_global off by 4.0°C, icon_d2 off by 2.5°C
        for (i in 1..3) {
            val epoch = base + i * E2ETestFixtures.HOUR_MILLIS
            val actualTemp = 15.0 + i
            repo.recordForecastSnapshot(
                makeSnapshot(
                    locationKey = "52.52,13.41",
                    cityName = "Berlin",
                    latitude = berlinLat,
                    longitude = berlinLon,
                    targetEpochMillis = epoch,
                    predictedTempC = actualTemp + 0.5,
                    predictedPrecipMm = 0.0,
                    predictedDrySportWindow = true,
                    modelPredictedTemps = mapOf(
                        "ecmwf_ifs025" to actualTemp + 0.2,
                        "gfs_global" to actualTemp + 4.0,
                        "icon_d2" to actualTemp - 2.5
                    )
                )
            )
            repo.verifyWithObservations(
                latitude = berlinLat,
                longitude = berlinLon,
                observations = listOf(
                    ObservedWeatherPoint(
                        epochMillis = epoch,
                        tempC = actualTemp,
                        precipMm = 0.0,
                        windKmh = 12.0
                    )
                )
            )
        }

        val summary = repo.getTrackRecordSummary()
        assertNotNull("Best model should be populated when multi-model snapshots are verified", summary.bestModel)
        assertTrue(
            "Best model (${summary.bestModel}) should identify ecmwf_ifs025/ECMWF",
            summary.bestModel!!.contains("ecmwf", ignoreCase = true)
        )
    }

    // =========================================================================
    // FEATURE 7: Multi-Model Forecast Confidence & Track Record — Tier 2 (7 Tests)
    // =========================================================================

    @Test
    fun f7_tier2_tokyoProbeAllNullIconD2FilteredDynamicallyWithoutZeroBias() {
        // Matches live Open-Meteo probe for Tokyo (35.68, 139.77): icon_d2 returns all nulls.
        val tokyoFixture = E2ETestFixtures.sampleMultiModelSeries(
            hours = 24,
            includeIconD2 = false,
            tempSpreadC = 0.3,
            precipSpreadMm = 0.0,
            windSpreadKmh = 1.0
        )

        val result = ForecastConfidenceEngine.computeConfidence(
            modelTemps = tokyoFixture.modelTemps,
            modelPrecips = tokyoFixture.modelPrecips,
            modelWinds = tokyoFixture.modelWinds
        )

        assertTrue(
            "Filtering all-null icon_d2 must keep high confidence for aligned remaining models (got ${result.percent})",
            result.percent >= 80
        )
        assertEquals("Hoch", result.tierLabel)
        assertFalse(
            "Filtered all-null icon_d2 must not be reported as diverging against valid models",
            result.divergenceExplanation.contains("icon_d2", ignoreCase = true)
        )
    }

    @Test
    fun f7_tier2_singleValidModelDegradesGracefullyWithoutCrash() {
        val singleModelTemps = mapOf<String, List<Double?>>(
            "gfs_global" to List(24) { 18.0 },
            "ecmwf_ifs025" to List(24) { null },
            "icon_d2" to emptyList()
        )
        val singleModelPrecips = mapOf<String, List<Double?>>(
            "gfs_global" to List(24) { 0.0 }
        )
        val singleModelWinds = mapOf<String, List<Double?>>(
            "gfs_global" to List(24) { 12.0 }
        )

        val result = ForecastConfidenceEngine.computeConfidence(singleModelTemps, singleModelPrecips, singleModelWinds)

        assertTrue("Single-model confidence must stay within [0, 100]", result.percent in 0..100)
        assertTrue("Single-model (<2 valid models) should degrade below Hoch (< 80%)", result.percent < 80)
        assertTrue("Explanation must describe limited/single model availability", result.divergenceExplanation.isNotBlank())
    }

    @Test
    fun f7_tier2_emptyOrAllNullModelMapsHandledSafely() {
        val emptyResult = ForecastConfidenceEngine.computeConfidence(emptyMap(), emptyMap(), emptyMap())
        assertTrue(emptyResult.percent in 0..100)
        assertTrue(emptyResult.tierLabel.isNotBlank())

        val allNullTemps = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { null },
            "gfs_global" to List(24) { null }
        )
        val allNullResult = ForecastConfidenceEngine.computeConfidence(allNullTemps, allNullTemps, allNullTemps)
        assertTrue(allNullResult.percent in 0..100)
        assertTrue(allNullResult.tierLabel.isNotBlank())
    }

    @Test
    fun f7_tier2_emptyTrackRecordReturnsInitialNoHistoryStateWithoutDivByZero() {
        val repo = TrackRecordRepository(E2ETestFixtures.InMemoryKeyValueStore())
        val summary = repo.getTrackRecordSummary()

        assertEquals(0, summary.verifiedCount)
        assertNull("Empty Track Record should have null bestModel", summary.bestModel)
        if (summary.meanTempErrorC != null) {
            assertFalse("meanTempErrorC must not be NaN", summary.meanTempErrorC!!.isNaN())
        }
        assertNull("Empty Track Record should have null rainHitRatePct", summary.rainHitRatePct)
    }

    @Test
    fun f7_tier2_duplicateSnapshotTimestampDeduplicatesOrUpdatesCleanly() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = TrackRecordRepository(store)
        val targetEpoch = E2ETestFixtures.BASE_EPOCH_MILLIS + 6 * E2ETestFixtures.HOUR_MILLIS
        val berlinLat = 52.5200
        val berlinLon = 13.4050

        repo.recordForecastSnapshot(
            makeSnapshot(
                locationKey = "52.52,13.41",
                cityName = "Berlin",
                latitude = berlinLat,
                longitude = berlinLon,
                targetEpochMillis = targetEpoch,
                predictedTempC = 18.0,
                predictedPrecipMm = 0.0,
                predictedDrySportWindow = true,
                modelPredictedTemps = emptyMap()
            )
        )
        // Duplicate save for same city + timestamp with updated temperature 20.0
        repo.recordForecastSnapshot(
            makeSnapshot(
                locationKey = "52.52,13.41",
                cityName = "Berlin",
                latitude = berlinLat,
                longitude = berlinLon,
                targetEpochMillis = targetEpoch,
                predictedTempC = 20.0,
                predictedPrecipMm = 0.0,
                predictedDrySportWindow = true,
                modelPredictedTemps = emptyMap()
            )
        )

        repo.verifyWithObservations(
            latitude = berlinLat,
            longitude = berlinLon,
            observations = listOf(
                ObservedWeatherPoint(
                    epochMillis = targetEpoch,
                    tempC = 21.0,
                    precipMm = 0.0,
                    windKmh = 12.0
                )
            )
        )

        val summary = repo.getTrackRecordSummary()
        assertEquals("Duplicate snapshot for same city & timestamp must not double-count", 1, summary.verifiedCount)
        assertEquals("Updated snapshot (20.0°C vs 21.0°C actual) should yield error 1.0°C", 1.0, summary.meanTempErrorC!!, 0.05)
    }

    @Test
    fun f7_tier2_extremeOutlierModelValueClampsConfidenceWithinZeroTo100() {
        val modelTemps = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 15.0 },
            "gfs_global" to List(24) { 999.0 }
        )
        val modelPrecips = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 0.0 },
            "gfs_global" to List(24) { 500.0 }
        )
        val modelWinds = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to List(24) { 10.0 },
            "gfs_global" to List(24) { 300.0 }
        )

        val result = ForecastConfidenceEngine.computeConfidence(modelTemps, modelPrecips, modelWinds)

        assertTrue("Extreme outlier must clamp confidence inside [0, 100] (got ${result.percent})", result.percent in 0..100)
        assertEquals("Unsicher", result.tierLabel)
        assertTrue(abs(result.percent) <= 100)
    }

    @Test
    fun f7_tier2_trackRecordPersistsVerifiedMetricsAcrossReinstantiation() {
        val sharedStore = E2ETestFixtures.InMemoryKeyValueStore()
        val epoch = E2ETestFixtures.BASE_EPOCH_MILLIS + 9 * E2ETestFixtures.HOUR_MILLIS
        val hamburgLat = 53.5511
        val hamburgLon = 9.9937

        val repo1 = TrackRecordRepository(sharedStore)
        repo1.recordForecastSnapshot(
            makeSnapshot(
                locationKey = "53.55,9.99",
                cityName = "Hamburg",
                latitude = hamburgLat,
                longitude = hamburgLon,
                targetEpochMillis = epoch,
                predictedTempC = 14.0,
                predictedPrecipMm = 2.5,
                predictedDrySportWindow = false,
                modelPredictedTemps = mapOf("ecmwf_ifs025" to 14.0, "gfs_global" to 17.0)
            )
        )
        repo1.verifyWithObservations(
            latitude = hamburgLat,
            longitude = hamburgLon,
            observations = listOf(
                ObservedWeatherPoint(
                    epochMillis = epoch,
                    tempC = 14.5,
                    precipMm = 3.0,
                    windKmh = 12.0
                )
            )
        )

        val repo2 = TrackRecordRepository(sharedStore)
        val restoredSummary = repo2.getTrackRecordSummary()

        assertEquals("Restored repository must preserve verified count", 1, restoredSummary.verifiedCount)
        assertEquals("Restored repository must preserve meanTempErrorC", 0.5, restoredSummary.meanTempErrorC!!, 0.05)
        assertEquals("Restored repository must preserve rainHitRatePct", 100.0, restoredSummary.rainHitRatePct!!.toDouble(), 0.5)
    }
}
