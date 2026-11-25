package com.frogweather.tier5

import com.frogweather.WeatherType
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportType
import com.frogweather.assistant.AssistantContext
import com.frogweather.assistant.AssistantResponseEngine
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.briefing.BriefingComposer
import com.frogweather.briefing.BriefingTimeCalculator
import com.frogweather.briefing.BriefingType
import com.frogweather.briefing.ClothingAdvisor
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.CalendarRepository
import com.frogweather.calendar.EventRescheduler
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.KeyValueStore
import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapStateController
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.ResolvedLayerOverlay
import com.frogweather.map.ScalarGridField
import com.frogweather.map.TimelineFrame
import com.frogweather.map.TimelineFrameSource
import com.frogweather.map.WindGridField
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelHourly
import com.frogweather.network.OsrmCoordPoint
import com.frogweather.network.OsrmRouteParser
import com.frogweather.network.RainViewerFrame
import com.frogweather.network.RainViewerMapsData
import com.frogweather.network.RainViewerParser
import com.frogweather.route.RoutePoint
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherCalculator
import com.frogweather.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tier 5 White-Box Adversarial & Edge-Case Hardening Tests for Phase 3 & Phase 4.
 * Proposed by White-Box Challenger 2 (M5 Phase 2 Round 1).
 */
class Tier5Phase3And4AdversarialTests {

    private class InMemoryStore : KeyValueStore {
        private val strings = linkedMapOf<String, String>()
        private val floats = linkedMapOf<String, Float>()

        override fun getString(key: String, defaultValue: String?): String? =
            strings[key] ?: defaultValue

        override fun putString(key: String, value: String?) {
            if (value == null) strings.remove(key) else strings[key] = value
        }

        override fun remove(key: String) {
            strings.remove(key)
            floats.remove(key)
        }

        fun putFloat(key: String, value: Float) {
            floats[key] = value
        }

        fun getFloat(key: String, defaultValue: Float): Float =
            floats[key] ?: defaultValue
    }

    private val oneHour = 3_600_000L
    private val baseEpoch = 1_783_209_600_000L // 2026-07-05T00:00:00Z

    private fun hourPoint(
        epochMillis: Long,
        tempC: Double = 20.0,
        precipMm: Double = 0.0,
        precipProb: Int = 5,
        windKmh: Double = 8.0,
        gustKmh: Double = 12.0,
        uvIndex: Double = 2.0,
        capeJkg: Double = 20.0,
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
    // Phase 3 — Bug-Exposing Adversarial Tests
    // =========================================================================

    @Test
    fun t5_p3_buildTimelineFrames_overlappingRainViewerAndForecast_remainsMonotonic() {
        val now = baseEpoch + 12 * oneHour
        val rv = RainViewerMapsData(
            version = "2.0",
            generatedEpochMillis = now,
            host = "https://tilecache.rainviewer.com",
            radarPast = listOf(
                RainViewerFrame(baseEpoch + 11 * oneHour, "/v2/radar/11", isNowcast = false),
                RainViewerFrame(baseEpoch + 12 * oneHour, "/v2/radar/12", isNowcast = false)
            ),
            radarNowcast = listOf(
                RainViewerFrame(baseEpoch + 12 * oneHour + 1_800_000L, "/v2/radar/nc1230", isNowcast = true)
            ),
            satelliteInfrared = emptyList()
        )
        val openMeteoTimestamps = (0 until 24).map { baseEpoch + it * oneHour }

        val (frames, defaultIndex) = MapLayerDataProvider.buildTimelineFrames(
            rainViewer = rv,
            forecastTimestampsMillis = openMeteoTimestamps,
            nowEpochMillis = now
        )

        assertTrue(frames.isNotEmpty())
        assertTrue(defaultIndex in frames.indices)
        for (i in 1 until frames.size) {
            assertTrue(
                "Timeline jumped backwards at index $i: ${frames[i - 1]} -> ${frames[i]}",
                frames[i].epochMillis >= frames[i - 1].epochMillis
            )
        }
    }

    @Test
    fun t5_p3_interpolateScalar_oversizedAxisLists_doesNotThrowIndexOutOfBounds() {
        val field = ScalarGridField(
            rows = 2,
            cols = 2,
            lats = listOf(50.0, 51.0, 52.0),
            lons = listOf(10.0, 11.0, 12.0),
            values = listOf(1.0, 2.0, 3.0, 4.0),
            unit = "°C",
            isSurfaceFallback = false
        )

        // Must return null or valid finite interpolation without IndexOutOfBoundsException
        val result = MapLayerDataProvider.interpolateScalar(field, 51.5, 11.5)
        if (result != null) {
            assertTrue(result.isFinite())
        }
    }

    @Test
    fun t5_p3_interpolateScalar_descendingAndWideAntimeridian_picksNearestEdge() {
        val descendingAntimeridian = ScalarGridField(
            rows = 2,
            cols = 4,
            lats = listOf(0.0, 2.0),
            lons = listOf(-170.0, -176.67, 176.67, 170.0),
            values = listOf(0.0, 10.0, 20.0, 30.0, 0.0, 10.0, 20.0, 30.0),
            unit = "°C",
            isSurfaceFallback = false
        )
        val midAtAntimeridian = MapLayerDataProvider.interpolateScalar(descendingAntimeridian, 1.0, 180.0)
        assertNotNull(midAtAntimeridian)
        assertEquals(15.0, midAtAntimeridian!!, 1.0)

        val wideAntimeridian = ScalarGridField(
            rows = 2,
            cols = 3,
            lats = listOf(0.0, 2.0),
            lons = listOf(10.0, 100.0, -170.0),
            values = listOf(1.0, 2.0, 3.0, 1.0, 2.0, 3.0),
            unit = "°C",
            isSurfaceFallback = false
        )
        // -160° is 10° east of -170° (value 3.0), not 170° west of +10° (value 1.0)
        val nearEastEdge = MapLayerDataProvider.interpolateScalar(wideAntimeridian, 1.0, -160.0)
        assertNotNull(nearEastEdge)
        assertEquals(3.0, nearEastEdge!!, 0.2)
    }

    @Test
    fun t5_p3_rainViewerParser_nullListElementsAndLargeEpoch_handledSafely() {
        val jsonWithNullElem = """
            {
              "version": "2.0",
              "generated": 1700000000,
              "host": "https://tilecache.rainviewer.com",
              "radar": {
                "past": [null, {"time": 1700000000, "path": "/v2/radar/p1"}],
                "nowcast": [null]
              },
              "satellite": { "infrared": [null] }
            }
        """.trimIndent()
        val parsed = RainViewerParser.parseJson(jsonWithNullElem)
        assertEquals(1, parsed.radarPast.size)
        assertEquals("/v2/radar/p1", parsed.radarPast.single().path)

        val overflowJson = """
            {
              "version": "2.0",
              "generated": 9223372036854776,
              "host": "https://tilecache.rainviewer.com",
              "radar": { "past": [{"time": 9223372036854776, "path": "/v2/radar/p1"}] }
            }
        """.trimIndent()
        val overflowParsed = RainViewerParser.parseJson(overflowJson)
        assertTrue(overflowParsed.generatedEpochMillis >= 0L)
        assertTrue(overflowParsed.radarPast.single().epochMillis >= 0L)
    }

    // =========================================================================
    // Phase 3 — Coverage Hardening Tests (Untested Paths)
    // =========================================================================

    @Test
    fun t5_p3_mapLayerDataProvider_windZeroSpeedAndDescendingAxes_noNaN() {
        val calmField = WindGridField(
            rows = 2,
            cols = 2,
            lats = listOf(52.0, 50.0),
            lons = listOf(12.0, 10.0),
            speedsKmh = listOf(0.0, 0.0, 0.0, 0.0),
            directionsDeg = listOf(180.0, 270.0, 90.0, 0.0),
            isSurfaceFallback = false
        )
        val sample = MapLayerDataProvider.interpolateWind(calmField, 51.0, 11.0)
        assertNotNull(sample)
        assertEquals(0.0, sample!!.speedKmh, 1e-6)
        assertEquals(0.0, sample.directionDeg, 1e-6)
        assertTrue(sample.u.isFinite() && sample.v.isFinite())
    }

    @Test
    fun t5_p3_mapLayerDataProvider_resolveAllTenLayersAcrossAllFourPressureLevels() {
        val plan = MapLayerDataProvider.buildSampleGrid(GeoBounds(50.0, 10.0, 52.0, 12.0), rows = 2, cols = 2)
        val now = baseEpoch
        val gridForecasts = plan.coordinates.map { (lat, lon) ->
            OpenMeteoForecastData(
                latitude = lat,
                longitude = lon,
                utcOffsetSeconds = 0,
                timezone = "GMT",
                timestampsEpochMillis = listOf(now),
                models = mapOf(
                    MapForecastModel.ECMWF.id to OpenMeteoModelHourly(
                        modelId = MapForecastModel.ECMWF.id,
                        rawSeries = MapLayerDataProvider.ALL_M3_HOURLY_VARIABLES.associateWith { listOf(12.3) }
                    )
                )
            )
        }
        val frame = TimelineFrame(
            epochMillis = now + 10_800_000L,
            label = "ECMWF",
            source = TimelineFrameSource.FORECAST_MODEL,
            forecastHourIndex = 0
        )

        for (level in PressureLevel.entries) {
            for (layer in MapWeatherLayer.entries) {
                val overlay = MapLayerDataProvider.resolveLayerOverlay(
                    layer = layer,
                    level = level,
                    model = MapForecastModel.ECMWF,
                    frame = frame,
                    rainViewer = null,
                    gridForecasts = gridForecasts,
                    gridPlan = plan,
                    owmApiKey = null,
                    nowEpochMillis = now
                )
                assertFalse(
                    "Layer $layer at $level should resolve when full M3 grid is present",
                    overlay is ResolvedLayerOverlay.Unavailable
                )
            }
        }
    }

    @Test
    fun t5_p3_mapStateController_emptyingFramesStopsPlaybackAndClampsIndex() {
        val controller = MapStateController()
        controller.setTimelineFrames(
            listOf(
                TimelineFrame(baseEpoch, "1", TimelineFrameSource.RADAR_PAST),
                TimelineFrame(baseEpoch + 600_000L, "2", TimelineFrameSource.RADAR_PAST)
            ),
            initialIndex = 1
        )
        controller.setPlaying(true)
        assertTrue(controller.state.value.isPlaying)

        controller.setTimelineFrames(emptyList())
        assertFalse(controller.state.value.isPlaying)
        assertEquals(0, controller.state.value.timelineIndex)
    }

    // =========================================================================
    // Phase 4 — Bug-Exposing Adversarial Tests
    // =========================================================================

    @Test
    fun t5_p4_eventRescheduler_duplicateCleanAndConflictingEpochs_deduplicatesWorstCase() {
        // Case A: 10 duplicate clean 10:00 entries + stormy 11:00..23:00 -> must NOT claim a 2h clean slot
        val dupCleanHours = List(10) { hourPoint(baseEpoch + 10 * oneHour) } +
            (11..23).map { h ->
                hourPoint(
                    baseEpoch + h * oneHour,
                    precipMm = 15.0,
                    precipProb = 95,
                    windKmh = 55.0,
                    gustKmh = 80.0,
                    capeJkg = 1800.0,
                    weatherCode = 95
                )
            }
        val event2h = CalendarEvent(1L, "Picknick im Park", "", "Stadtpark", baseEpoch + 14 * oneHour, baseEpoch + 16 * oneHour)
        val sugDupClean = EventRescheduler.suggestReschedules(
            listOf(event2h),
            dupCleanHours,
            SportType.WALKING,
            nowMillis = baseEpoch
        ).single()
        val start = sugDupClean.suggestedStartMillis
        val end = sugDupClean.suggestedEndMillis
        if (start != null && end != null) {
            assertTrue(
                "Partial 1h clean slot must end at 11:00, not span into 11:00 thunderstorm ($start..$end)",
                (end - start) <= oneHour
            )
        }

        // Case B: conflicting duplicate at 10:00 (clean + thunderstorm) -> worst-case thunderstorm wins
        val conflictHours = listOf(
            hourPoint(baseEpoch + 10 * oneHour, precipMm = 0.0, precipProb = 0, weatherCode = 1),
            hourPoint(baseEpoch + 10 * oneHour, precipMm = 20.0, precipProb = 100, capeJkg = 2000.0, weatherCode = 95),
            hourPoint(baseEpoch + 14 * oneHour, precipMm = 15.0, precipProb = 95, weatherCode = 65)
        )
        val event1h = CalendarEvent(2L, "Joggen", "", "Park", baseEpoch + 14 * oneHour, baseEpoch + 15 * oneHour)
        val sugConflict = EventRescheduler.suggestReschedules(
            listOf(event1h),
            conflictHours,
            SportType.WALKING,
            nowMillis = baseEpoch
        ).single()
        assertFalse("Conflicting thunderstorm duplicate at 10:00 must NOT be offered as clean slot", sugConflict.hasBetterSlot)
    }

    @Test
    fun t5_p4_eventRescheduler_longOverflowEpoch_doesNotMergeDistantRuns() {
        val farPast = -4_700_000_000_000_000_000L
        val farFuture = 4_700_000_000_000_000_000L + 10 * oneHour
        val localZone = ZoneId.systemDefault()
        val pastHour = Instant.ofEpochMilli(farPast).atZone(localZone).hour
        // Shift backward so eventLocalHour == 2 (preferDaytime == false in any timezone)
        val hoursBack = ((pastHour - 2 + 24) % 24).coerceAtLeast(3)
        val nightEventStart = farPast - hoursBack * oneHour
        val hours = listOf(
            hourPoint(nightEventStart, precipMm = 15.0, precipProb = 95, weatherCode = 65),
            hourPoint(nightEventStart + oneHour, precipMm = 15.0, precipProb = 95, weatherCode = 65),
            hourPoint(farPast),
            hourPoint(farFuture)
        )
        val event2h = CalendarEvent(3L, "Nachtwanderung", "", "Berg", nightEventStart, nightEventStart + 2 * oneHour)
        val suggestion = EventRescheduler.suggestReschedules(
            listOf(event2h),
            hours,
            SportType.HIKING,
            nowMillis = nightEventStart - oneHour
        ).single()

        // Signed subtraction (farFuture - farPast) overflows Long; must NOT produce a full 2h slot at farPast
        if (suggestion.suggestedStartMillis == farPast && suggestion.suggestedEndMillis != null) {
            assertTrue(
                "Distant epochs across Long overflow must not merge into 2h run",
                suggestion.suggestedEndMillis!! - farPast <= oneHour
            )
        }
    }

    @Test
    fun t5_p4_osrmRouteParser_nullCoordinateElement_skipsNullInsteadOfFailingRoute() {
        val jsonWithNullCoord = """
            {
              "code": "Ok",
              "routes": [
                {
                  "duration": 600.0,
                  "distance": 5000.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [[13.405, 52.520], null, [13.064, 52.400]]
                  }
                }
              ]
            }
        """.trimIndent()
        val res = OsrmRouteParser.parse(jsonWithNullCoord)
        assertTrue("Valid coordinates around a null entry should still parse route", res.isSuccess)
        assertEquals(2, res.points.size)
    }

    @Test
    fun t5_p4_assistantResponseEngine_dup2cAndDup2b_deduplicatesBeforeWindowSlicing() {
        val dup24 = List(24) {
            hourPoint(baseEpoch + 9 * oneHour, tempC = 14.0, precipMm = 7.0, precipProb = 95, weatherCode = 65)
        } + (10..15).map { h ->
            hourPoint(baseEpoch + h * oneHour, tempC = 22.0, precipMm = 0.0, precipProb = 5, weatherCode = 1)
        }
        val ctx = AssistantContext(
            cityName = "Berlin",
            currentTempC = 14.0,
            feelsLikeC = 14.0,
            weatherType = WeatherType.RAINY,
            windKmh = 8.0,
            uvIndex = 2.0,
            selectedSport = SportType.RUNNING,
            hourlyPoints = dup24
        )

        val weatherAns = AssistantResponseEngine.answer("Wie wird das Wetter heute?", ctx, nowMillis = baseEpoch)
        val sportAns = AssistantResponseEngine.answer("Wann kann ich heute laufen gehen?", ctx, nowMillis = baseEpoch)

        assertTrue("Weather summary must include post-duplicate 22 °C afternoon: ${weatherAns.body}", weatherAns.body.contains("22"))
        assertTrue("Sport answer must find 10:00–16:00 window after 24 dups: ${sportAns.body}", sportAns.body.contains("10:00"))
        assertFalse("Dup-2B verdict must not count duplicate 09:00 entries as 24 distinct hours: ${sportAns.body}", sportAns.body.contains("24 von"))
    }

    @Test
    fun t5_p4_germanFreeTextParser_avoidsSubstringsDavonWeitWegBildschirmAndExtractsCleanCities() {
        assertEquals(QueryIntentCategory.WEATHER_QUERY, GermanFreeTextParser.parseIntent("Wird man davon nass?").category)
        assertEquals(QueryIntentCategory.UNKNOWN, GermanFreeTextParser.parseIntent("Ist es weit weg?").category)
        assertEquals(QueryIntentCategory.UNKNOWN, GermanFreeTextParser.parseIntent("Funktioniert das auf dem Bildschirm?").category)

        val essenMitRad = GermanFreeTextParser.parseIntent("Route nach Essen mit dem Rad?")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, essenMitRad.category)
        assertEquals("Essen", essenMitRad.routeDestination)

        val frankfurtAmMain = GermanFreeTextParser.parseIntent("Route von Berlin nach Frankfurt am Main")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, frankfurtAmMain.category)
        assertEquals("Berlin", frankfurtAmMain.routeOrigin)
        assertEquals("Frankfurt Am Main", frankfurtAmMain.routeDestination)
    }

    @Test
    fun t5_p4_assistantResponseEngine_tomorrowClothing_usesTomorrowForecast() {
        val todayHotTomorrowSnow = (0 until 24).map { h ->
            hourPoint(baseEpoch + h * oneHour, tempC = 28.0, uvIndex = 7.0)
        } + (24 until 48).map { h ->
            hourPoint(baseEpoch + h * oneHour, tempC = -2.0, precipMm = 5.0, precipProb = 90, weatherCode = 75)
        }
        val ctx = AssistantContext(
            cityName = "Berlin",
            currentTempC = 28.0,
            feelsLikeC = 28.0,
            weatherType = WeatherType.SUNNY,
            windKmh = 5.0,
            uvIndex = 7.0,
            selectedSport = SportType.RUNNING,
            hourlyPoints = todayHotTomorrowSnow
        )

        val tomorrowClothing = AssistantResponseEngine.answer("Was soll ich morgen anziehen?", ctx, nowMillis = baseEpoch)
        assertTrue(
            "Tomorrow clothing query (-2 °C snow) must not recommend summer shorts/sunscreen: ${tomorrowClothing.body}",
            tomorrowClothing.body.contains("Winter", ignoreCase = true) ||
                tomorrowClothing.body.contains("Eisig", ignoreCase = true) ||
                tomorrowClothing.body.contains("Kälte", ignoreCase = true)
        )
    }

    @Test
    fun t5_p4_routeWeatherCalculator_buildMapsHandoffUri_clampsOutOfRangeCoordinates() {
        val uri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = 999.0,
            originLon = -999.0,
            destLat = -120.0,
            destLon = 250.0,
            destLabel = "Test",
            mode = RouteTransportMode.DRIVING
        )
        assertFalse(uri.contains("999.000000"))
        assertFalse(uri.contains("250.000000"))
    }

    // =========================================================================
    // Phase 4 — Coverage Hardening Tests (Untested Paths)
    // =========================================================================

    @Test
    fun t5_p4_briefingTimeCalculator_springAndFallDstTransitions_computeValidNextTrigger() {
        val berlin = ZoneId.of("Europe/Berlin")
        val beforeSpringGap = LocalDateTime.of(2026, 3, 29, 1, 30).atZone(berlin).toInstant().toEpochMilli()
        val springTrigger = BriefingTimeCalculator.computeNextTriggerMillis(2, 30, beforeSpringGap, berlin)
        assertTrue(springTrigger > beforeSpringGap)
        assertEquals(3, Instant.ofEpochMilli(springTrigger).atZone(berlin).hour)

        val fallFirst = LocalDateTime.of(2026, 10, 25, 2, 15).atZone(berlin).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val fallSecond = LocalDateTime.of(2026, 10, 25, 2, 15).atZone(berlin).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertTrue(BriefingTimeCalculator.computeNextTriggerMillis(2, 30, fallFirst, berlin) > fallFirst)
        assertTrue(BriefingTimeCalculator.computeNextTriggerMillis(2, 30, fallSecond, berlin) > fallSecond)
    }

    @Test
    fun t5_p4_briefingComposer_extremeTimestampsAndEmptyInputs_neverLeaksNaN() {
        val clothing = ClothingAdvisor.recommendClothing(Double.NaN, Double.POSITIVE_INFINITY, -10, Double.NaN, Double.NaN)
        val composed = BriefingComposer.composeBriefing(
            dateLabel = " ",
            dailySummary = " ",
            clothing = clothing,
            bestSportWindow = null,
            calendarWarnings = listOf(" ", ""),
            routeCheck = "   ",
            briefingType = BriefingType.EVENING,
            utcOffsetSeconds = 999_999
        )
        val body = composed.toNotificationBody()
        assertFalse(body.contains("NaN"))
        assertFalse(body.contains("Infinity"))
        assertEquals(5, composed.card.items.size)
        assertTrue(composed.card.items.all { it.text.isNotBlank() })
    }

    @Test
    fun t5_p4_calendarRepository_normalizeInstanceTimes_invertedAllDayAndMultiDay() {
        val berlin = ZoneId.of("Europe/Berlin")
        val day1 = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val (startSame, endSame) = CalendarRepository.normalizeInstanceTimes(day1, day1 - 1000L, allDay = true, localZone = berlin)
        assertTrue(endSame > startSame)

        val nonAllDayInverted = CalendarRepository.normalizeInstanceTimes(day1 + 5000L, day1, allDay = false, localZone = berlin)
        assertEquals(day1 + 5000L, nonAllDayInverted.first)
        assertEquals(day1 + 5000L, nonAllDayInverted.second)
    }

    @Test
    fun t5_p4_routeWeatherCalculator_segmentSpecificForecastsOverrideFallbackAndEmptySeries() {
        val points = listOf(
            RoutePoint("Start", 52.52, 13.40, 0),
            RoutePoint("Berg", 47.50, 11.10, 60)
        )
        val startForecast = listOf(hourPoint(baseEpoch, tempC = 20.0, precipMm = 0.0, precipProb = 0))
        val destForecast = listOf(hourPoint(baseEpoch + oneHour, tempC = 6.0, precipMm = 9.0, precipProb = 95, weatherCode = 65))
        val result = RouteWeatherCalculator.computeRouteWithSegmentForecasts(
            origin = "Start",
            destination = "Berg",
            mode = RouteTransportMode.FOOT,
            targetArrivalMillis = baseEpoch + oneHour,
            routePoints = points,
            segmentForecasts = listOf(startForecast, destForecast),
            fallbackForecasts = emptyList(),
            nowMillis = baseEpoch
        )
        assertEquals(2, result.segments.size)
        assertEquals(20.0, result.segments[0].tempC, 0.1)
        assertFalse(result.segments[0].isHazardous)
        assertEquals(6.0, result.segments[1].tempC, 0.1)
        assertTrue(result.segments[1].isHazardous)
        assertTrue(result.hasWeatherHazard)
    }

    @Test
    fun t5_p4_clothingAdvisor_precipMmTriggersRainGearEvenWithLowProb() {
        val rec = ClothingAdvisor.recommendClothing(
            tempC = 16.0,
            feelsLikeC = 16.0,
            precipProb = 15,
            windKmh = 10.0,
            uvIndex = 2.0,
            precipMm = 2.0
        )
        assertTrue(rec.needsRainGear)
        assertTrue(rec.rainGear.any { it.contains("Regenjacke", ignoreCase = true) })
    }

    @Test
    fun t5_p4_widgetLocation_nullIslandSharedFallbackReturnsNull() {
        val widgetStore = InMemoryStore().apply {
            putFloat("lat_1", 0.0f)
            putFloat("lon_1", 0.0f)
        }
        val sharedStore = InMemoryStore().apply {
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0.0f)
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0.0f)
        }
        assertNull(WeatherWidgetProvider.resolveWidgetLocation(1, widgetStore, sharedStore))
    }
}
