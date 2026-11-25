package com.frogweather.tier5

import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.calendar.EventRescheduler
import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.ResolvedLayerOverlay
import com.frogweather.map.TimelineFrame
import com.frogweather.map.TimelineFrameSource
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelHourly
import com.frogweather.network.RainViewerFrame
import com.frogweather.network.RainViewerMapsData
import com.frogweather.route.RoutePoint
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 5 Round 2 Closure Regression Tests covering Challenger 2 R2 findings (M1, M2, L3, L4, L5).
 */
class Tier5Round2ClosureTests {

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

    // Medium 1: EventRescheduler.classifySensitivity must not block "Teamsport" as MS Teams
    @Test
    fun t5_r2_eventRescheduler_teamsportEventsAreWeatherSensitive_virtualTeamsStillBlocked() {
        val teamsportPark = EventRescheduler.classifySensitivity(
            title = "Fußball/Teamsport im Stadtpark",
            description = "",
            location = "Stadtpark"
        )
        assertTrue(teamsportPark.isWeatherSensitive)

        val teamsportTurnier = EventRescheduler.classifySensitivity(
            title = "Teamsport-Turnier draußen",
            description = "",
            location = "Sportplatz"
        )
        assertTrue(teamsportTurnier.isWeatherSensitive)

        val teamsportArena = EventRescheduler.classifySensitivity(
            title = "Fußball",
            description = "Training",
            location = "Teamsport Arena"
        )
        assertTrue(teamsportArena.isWeatherSensitive)

        // Genuine virtual Microsoft Teams meetings remain blocked
        assertFalse(EventRescheduler.classifySensitivity("Teams", "", "").isWeatherSensitive)
        assertFalse(EventRescheduler.classifySensitivity("Sync", "", "Teams").isWeatherSensitive)
        assertFalse(
            EventRescheduler.classifySensitivity(
                "Fußball Besprechung",
                "Microsoft Teams",
                "Stadtpark"
            ).isWeatherSensitive
        )
        assertFalse(
            EventRescheduler.classifySensitivity(
                "Training",
                "MS Teams Link",
                "Park"
            ).isWeatherSensitive
        )
        assertFalse(
            EventRescheduler.classifySensitivity(
                "Teams-Meeting",
                "",
                "Park"
            ).isWeatherSensitive
        )
    }

    // Medium 2: GermanFreeTextParser must not hijack normal "von" weather queries as ROUTE_WEATHER
    @Test
    fun t5_r2_germanFreeTextParser_vonWeatherQueriesStayWeather_routeVonNachRemainsRoute() {
        val weatherQueriesWithVon = listOf(
            "Regnet es von 14 bis 18 Uhr?",
            "Wind von Westen heute?",
            "Wetterbericht von heute",
            "Schnee von gestern?",
            "Ist es kalt von morgens bis abends?"
        )
        for (query in weatherQueriesWithVon) {
            val parsed = GermanFreeTextParser.parseIntent(query)
            assertEquals(
                "Expected WEATHER_QUERY for '$query', got ${parsed.category}",
                QueryIntentCategory.WEATHER_QUERY,
                parsed.category
            )
        }

        val explicitRoute = GermanFreeTextParser.parseIntent("Route von Berlin nach Hamburg")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, explicitRoute.category)
        assertEquals("Berlin", explicitRoute.routeOrigin)
        assertEquals("Hamburg", explicitRoute.routeDestination)

        val implicitVonNachRoute = GermanFreeTextParser.parseIntent("Wie wird das Wetter von Berlin nach Hamburg?")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, implicitVonNachRoute.category)
        assertEquals("Berlin", implicitVonNachRoute.routeOrigin)
        assertEquals("Hamburg", implicitVonNachRoute.routeDestination)
    }

    // Low 3: MapLayerDataProvider.resolveLayerOverlay must not overflow abs(Long.MIN_VALUE - 0L)
    @Test
    fun t5_r2_resolveLayerOverlay_longMinValueTimestampDoesNotOverflowToNearCurrent() {
        val plan = MapLayerDataProvider.buildSampleGrid(GeoBounds(50.0, 10.0, 52.0, 12.0), rows = 2, cols = 2)
        val gridForecasts = plan.coordinates.map { (lat, lon) ->
            OpenMeteoForecastData(
                latitude = lat,
                longitude = lon,
                utcOffsetSeconds = 0,
                timezone = "GMT",
                timestampsEpochMillis = listOf(baseEpoch),
                models = mapOf(
                    MapForecastModel.GFS.id to OpenMeteoModelHourly(
                        modelId = MapForecastModel.GFS.id,
                        rawSeries = mapOf("cloud_cover" to listOf(75.0))
                    )
                )
            )
        }
        val rv = RainViewerMapsData(
            version = "2.0",
            generatedEpochMillis = 0L,
            host = "https://tilecache.rainviewer.com",
            radarPast = emptyList(),
            radarNowcast = emptyList(),
            satelliteInfrared = listOf(
                RainViewerFrame(epochMillis = 0L, path = "/v2/satellite/0", isNowcast = false),
                RainViewerFrame(epochMillis = Long.MAX_VALUE, path = "/v2/satellite/max", isNowcast = false)
            )
        )
        val extremePastFrame = TimelineFrame(
            epochMillis = Long.MIN_VALUE,
            label = "ExtremePast",
            source = TimelineFrameSource.FORECAST_MODEL,
            forecastHourIndex = 0
        )

        val overlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.SATELLITE,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = extremePastFrame,
            rainViewer = rv,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = null,
            nowEpochMillis = 0L
        )

        assertTrue(
            "Long.MIN_VALUE frame at now=0L must NOT be treated as near-current RainViewer IR, got $overlay",
            overlay is ResolvedLayerOverlay.ScalarGrid
        )
    }

    // Low 4: MapLayerDataProvider.buildTimelineFrames must remain strictly monotonic on unsorted inputs
    @Test
    fun t5_r2_buildTimelineFrames_unsortedPastNowcastAndForecast_remainsStrictlyMonotonic() {
        val now = baseEpoch + 12 * oneHour
        val rv = RainViewerMapsData(
            version = "2.0",
            generatedEpochMillis = now,
            host = "https://tilecache.rainviewer.com",
            radarPast = listOf(
                RainViewerFrame(baseEpoch + 12 * oneHour, "/v2/radar/12", isNowcast = false),
                RainViewerFrame(baseEpoch + 10 * oneHour, "/v2/radar/10", isNowcast = false),
                RainViewerFrame(baseEpoch + 11 * oneHour, "/v2/radar/11", isNowcast = false)
            ),
            radarNowcast = listOf(
                RainViewerFrame(baseEpoch + 13 * oneHour, "/v2/radar/nc13", isNowcast = true),
                // Must be excluded since 11h <= past.last (12h)
                RainViewerFrame(baseEpoch + 11 * oneHour, "/v2/radar/nc11", isNowcast = true),
                RainViewerFrame(baseEpoch + 12 * oneHour + 1_800_000L, "/v2/radar/nc1230", isNowcast = true)
            ),
            satelliteInfrared = emptyList()
        )
        val unsortedForecasts = listOf(
            baseEpoch + 15 * oneHour,
            baseEpoch + 13 * oneHour,
            baseEpoch + 14 * oneHour,
            baseEpoch + 12 * oneHour
        )

        val (frames, defaultIndex) = MapLayerDataProvider.buildTimelineFrames(
            rainViewer = rv,
            forecastTimestampsMillis = unsortedForecasts,
            nowEpochMillis = now
        )

        assertTrue(frames.isNotEmpty())
        assertTrue(defaultIndex in frames.indices)
        for (i in 1 until frames.size) {
            assertTrue(
                "Expected strictly monotonic timeline at index $i: ${frames[i - 1]} -> ${frames[i]}",
                frames[i].epochMillis > frames[i - 1].epochMillis
            )
        }
        // Verify original forecastHourIndex mapping was preserved after sorting
        val forecastFrames = frames.filter { it.source == TimelineFrameSource.FORECAST_MODEL }
        assertEquals(listOf(baseEpoch + 14 * oneHour, baseEpoch + 15 * oneHour), forecastFrames.map { it.epochMillis })
        assertEquals(listOf(2, 0), forecastFrames.map { it.forecastHourIndex })
    }

    // Low 5: RouteWeatherCalculator must worst-case deduplicate duplicate epochMillis forecasts
    @Test
    fun t5_r2_routeWeatherCalculator_duplicateEpochForecasts_preservesWorstCaseHazard() {
        val targetArrival = baseEpoch + 2 * oneHour
        val points = listOf(
            RoutePoint("Start", 52.52, 13.40, offsetMinutesFromStart = 0),
            RoutePoint("Ziel", 52.50, 13.35, offsetMinutesFromStart = 60)
        )
        val duplicateFallback = listOf(
            hourPoint(baseEpoch + oneHour, precipMm = 0.0, precipProb = 0, weatherCode = 1),
            hourPoint(baseEpoch + 2 * oneHour, precipMm = 0.0, precipProb = 0, weatherCode = 1),
            hourPoint(
                baseEpoch + 2 * oneHour,
                precipMm = 25.0,
                precipProb = 95,
                windKmh = 55.0,
                gustKmh = 80.0,
                capeJkg = 1800.0,
                weatherCode = 95
            )
        )

        val result = RouteWeatherCalculator.computeRouteWithSegmentForecasts(
            origin = "Start",
            destination = "Ziel",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = targetArrival,
            routePoints = points,
            segmentForecasts = emptyList(),
            fallbackForecasts = duplicateFallback,
            nowMillis = baseEpoch
        )

        assertTrue("Duplicate thunderstorm at 2h must flag hasWeatherHazard=true", result.hasWeatherHazard)
        val arrivalSeg = result.arrivalWeather
        assertTrue(arrivalSeg != null && arrivalSeg.isHazardous)
        assertEquals(25.0, arrivalSeg!!.precipMm, 0.01)
        assertEquals(95, arrivalSeg.weatherCode)

        // Also verify segment-specific duplicate forecasts deduplicate worst-case
        val seg1Dup = listOf(
            hourPoint(baseEpoch + oneHour, precipMm = 0.0, precipProb = 0, windKmh = 5.0, weatherCode = 1),
            hourPoint(baseEpoch + oneHour, precipMm = 12.0, precipProb = 90, windKmh = 45.0, weatherCode = 65)
        )
        val segResult = RouteWeatherCalculator.computeRouteWithSegmentForecasts(
            origin = "Start",
            destination = "Ziel",
            mode = RouteTransportMode.CYCLING,
            targetArrivalMillis = targetArrival,
            routePoints = points,
            segmentForecasts = listOf(seg1Dup, listOf(hourPoint(targetArrival))),
            fallbackForecasts = emptyList(),
            nowMillis = baseEpoch
        )
        assertTrue(segResult.hasWeatherHazard)
        assertTrue(segResult.segments.first().isHazardous)
        assertEquals(12.0, segResult.segments.first().precipMm, 0.01)
    }
}
