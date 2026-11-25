package com.frogweather.e2e

import com.frogweather.e2e.E2ETestFixtures.BASE_EPOCH_MILLIS
import com.frogweather.e2e.E2ETestFixtures.HOUR_MILLIS
import com.frogweather.e2e.E2ETestFixtures.InMemoryKeyValueStore
import com.frogweather.e2e.E2ETestFixtures.sample24HourSeries
import com.frogweather.e2e.E2ETestFixtures.sampleHourlyPoint
import com.frogweather.WeatherType
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.briefing.BriefingComposer
import com.frogweather.briefing.BriefingScheduler
import com.frogweather.briefing.ClothingAdvisor
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.CalendarRepository
import com.frogweather.calendar.EventRescheduler
import com.frogweather.data.WeatherScenario
import com.frogweather.network.OsrmRouteParser
import com.frogweather.route.RoutePoint
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherCalculator
import com.frogweather.service.BootReceiver
import com.frogweather.service.WeatherNotificationService
import com.frogweather.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage A Opaque-Box E2E Tests for Phase 4 (Features 11-14).
 *
 * Derived strictly from:
 * - ORIGINAL_REQUEST.md (R4)
 * - PROJECT.md (§ Interface Contracts § 4 & § 6)
 * - TEST_INFRA.md (Phase 4 Tier 1 & Tier 2 Test Matrix)
 * - Verified external specs (Android CalendarContract, OSRM, Android 14 Notification/Widget contracts)
 *
 * Uses shared fixtures (`InMemoryKeyValueStore`, `sampleHourlyPoint`, `sample24HourSeries`)
 * from `E2ETestFixtures.kt`. Note: Stage B reconciles final parameter/data-class names against
 * completed M4 implementations before copying into `app/src/test/java/com/frogweather/e2e/`.
 */
class E2EPhase4FeatureTests {

    private companion object {
        const val ONE_HOUR_MS = HOUR_MILLIS
        const val BASE_EPOCH_MS = BASE_EPOCH_MILLIS
    }

    // =========================================================================
    // FEATURE 11: CalendarContract Integration & Rescheduling (6 Tier 1 + 6 Tier 2 = 12 Tests)
    // =========================================================================

    @Test
    fun f11_t1_outdoorSportOrPicnicEventFlaggedAsWeatherSensitive() {
        val runResult = EventRescheduler.classifySensitivity(
            title = "Laufrunde im Park",
            description = "10km Joggen draußen",
            location = "Englischer Garten, München"
        )
        val picnicResult = EventRescheduler.classifySensitivity(
            title = "Familien-Picknick",
            description = "Decke und Snacks auf der Wiese",
            location = "Tiergarten"
        )

        assertTrue("Outdoor running event must be flagged weather-sensitive", runResult.isWeatherSensitive)
        assertTrue("Outdoor picnic event must be flagged weather-sensitive", picnicResult.isWeatherSensitive)
        assertTrue(runResult.reason.isNotBlank())
    }

    @Test
    fun f11_t1_indoorMeetingOrCallNotFlaggedAsWeatherSensitive() {
        val result = EventRescheduler.classifySensitivity(
            title = "Sprint Planning Call",
            description = "Wöchentliches Zoom-Meeting im Büro",
            location = "Konferenzraum B / Online"
        )

        assertFalse("Indoor office call must NOT be flagged weather-sensitive", result.isWeatherSensitive)
    }

    @Test
    fun f11_t1_heavyRainDuringOutdoorEventTriggersRescheduleSuggestion() {
        val hours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).mapIndexed { idx, pt ->
            when (idx) {
                2, 3 -> pt.copy(tempC = 14.0, precipMm = 8.5, precipProb = 90, windKmh = 28.0, weatherCode = 65)
                in 6..10 -> pt.copy(tempC = 19.0, precipMm = 0.0, precipProb = 5, windKmh = 8.0, uvIndex = 3.0, weatherCode = 1)
                else -> pt
            }
        }
        val outdoorEvent = CalendarEvent(
            id = 101L,
            title = "Feierabend-Wanderung",
            description = "Tour entlang der Isar",
            location = "München",
            startMillis = BASE_EPOCH_MS + 2 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS + 4 * ONE_HOUR_MS,
            allDay = false
        )

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(outdoorEvent),
            hourlyPoints = hours,
            sport = SportType.HIKING,
            nowMillis = BASE_EPOCH_MS
        )

        assertEquals(1, suggestions.size)
        val suggestion = suggestions.first()
        assertEquals(101L, suggestion.eventId)
        assertNotNull("Dry slot should be suggested for heavy rain conflict", suggestion.suggestedStartMillis)
        assertNotEquals(outdoorEvent.startMillis, suggestion.suggestedStartMillis)
    }

    @Test
    fun f11_t1_rescheduleChoosesAlternativeSlotWithGoodWeatherAndSportScore() {
        val hours = List(24) { idx ->
            when (idx) {
                in 0..5 -> sampleHourlyPoint(
                    epochMillis = BASE_EPOCH_MS + idx * ONE_HOUR_MS,
                    tempC = 13.0,
                    precipMm = 10.0,
                    precipProb = 95,
                    windKmh = 35.0,
                    gustKmh = 55.0,
                    capeJkg = 1200.0,
                    weatherCode = 95
                )
                in 6..11 -> sampleHourlyPoint(
                    epochMillis = BASE_EPOCH_MS + idx * ONE_HOUR_MS,
                    tempC = 20.0,
                    precipMm = 0.0,
                    precipProb = 0,
                    windKmh = 7.0,
                    gustKmh = 12.0,
                    uvIndex = 3.0,
                    capeJkg = 50.0,
                    weatherCode = 0
                )
                else -> sampleHourlyPoint(
                    epochMillis = BASE_EPOCH_MS + idx * ONE_HOUR_MS,
                    tempC = 14.0,
                    precipMm = 4.0,
                    precipProb = 75,
                    windKmh = 42.0,
                    gustKmh = 65.0,
                    weatherCode = 63
                )
            }
        }
        val cyclingEvent = CalendarEvent(
            id = 102L,
            title = "Rennrad-Training",
            description = "Ausfahrt 40km",
            location = "Berlin",
            startMillis = BASE_EPOCH_MS + 1 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS + 3 * ONE_HOUR_MS,
            allDay = false
        )

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(cyclingEvent),
            hourlyPoints = hours,
            sport = SportType.CYCLING,
            nowMillis = BASE_EPOCH_MS
        )

        val suggestion = suggestions.single()
        val bestStart = suggestion.suggestedStartMillis!!
        val dryWindowStart = BASE_EPOCH_MS + 6 * ONE_HOUR_MS
        val dryWindowEnd = BASE_EPOCH_MS + 11 * ONE_HOUR_MS
        assertTrue(
            "Suggested slot $bestStart must fall inside dry calm window [$dryWindowStart, $dryWindowEnd]",
            bestStart in dryWindowStart..dryWindowEnd
        )
    }

    @Test
    fun f11_t1_missingEventLocationFallsBackToActiveCityCoordinates() {
        val hours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).mapIndexed { idx, pt ->
            if (idx in 1..2) pt.copy(precipMm = 7.5, precipProb = 90, weatherCode = 65)
            else pt.copy(precipMm = 0.0, precipProb = 5, weatherCode = 1)
        }
        val eventWithoutLocation = CalendarEvent(
            id = 103L,
            title = "Outdoor-Fitness Kurs",
            description = "Park-Workout",
            location = null,
            startMillis = BASE_EPOCH_MS + 1 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS + 2 * ONE_HOUR_MS,
            allDay = false
        )

        val resolvedLocation = CalendarRepository.resolveEventLocation(
            event = eventWithoutLocation,
            fallbackCityName = "Hamburg",
            fallbackLat = 53.5511,
            fallbackLon = 9.9937
        )
        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(eventWithoutLocation),
            hourlyPoints = hours,
            sport = SportType.OUTDOOR_FITNESS,
            nowMillis = BASE_EPOCH_MS
        )

        assertEquals("Hamburg", resolvedLocation.cityName)
        assertEquals(53.5511, resolvedLocation.latitude, 0.0001)
        assertEquals(9.9937, resolvedLocation.longitude, 0.0001)
        assertEquals(1, suggestions.size)
    }

    @Test
    fun f11_t1_dryMildOutdoorEventNeedsNoReschedule() {
        val mildDryHours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).map {
            it.copy(tempC = 19.0, precipMm = 0.0, precipProb = 0, windKmh = 6.0, gustKmh = 10.0, uvIndex = 3.0, capeJkg = 0.0, weatherCode = 1)
        }
        val outdoorEvent = CalendarEvent(
            id = 104L,
            title = "Spaziergang im Schlosspark",
            description = "Gemütliche Runde",
            location = "Potsdam",
            startMillis = BASE_EPOCH_MS + 2 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS + 4 * ONE_HOUR_MS,
            allDay = false
        )

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(outdoorEvent),
            hourlyPoints = mildDryHours,
            sport = SportType.WALKING,
            nowMillis = BASE_EPOCH_MS
        )

        assertTrue(
            "Weather-safe outdoor event should produce no forced reschedule",
            suggestions.isEmpty() || suggestions.none { it.requiresReschedule }
        )
    }

    @Test
    fun f11_t2_permissionDeniedOrEmptyCalendarProducesEmptyResultWithoutCrash() {
        val hours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS)

        val deniedEvents = CalendarRepository.handleQueryResult(
            hasReadPermission = false,
            queriedEvents = emptyList()
        )
        val suggestions = EventRescheduler.suggestReschedules(
            events = deniedEvents.events,
            hourlyPoints = hours,
            sport = SportType.RUNNING,
            nowMillis = BASE_EPOCH_MS
        )

        assertFalse(deniedEvents.permissionGranted)
        assertTrue(deniedEvents.events.isEmpty())
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun f11_t2_allDayEventHandledGracefullyWithoutInvalidDurationMath() {
        val hours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).map {
            it.copy(precipMm = 6.0, precipProb = 85, weatherCode = 63)
        }
        val allDayOutdoor = CalendarEvent(
            id = 105L,
            title = "Outdoor Festival",
            description = "Ganztägig im Freien",
            location = "Köln",
            startMillis = BASE_EPOCH_MS,
            endMillis = BASE_EPOCH_MS + 24 * ONE_HOUR_MS,
            allDay = true
        )

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(allDayOutdoor),
            hourlyPoints = hours,
            sport = SportType.WALKING,
            nowMillis = BASE_EPOCH_MS
        )

        // All-day events must not crash duration math or propose invalid negative/sub-zero windows
        suggestions.forEach { s ->
            if (s.suggestedStartMillis != null && s.suggestedEndMillis != null) {
                assertTrue(s.suggestedEndMillis!! >= s.suggestedStartMillis!!)
            }
        }
    }

    @Test
    fun f11_t2_pastEventsExcludedFromFutureRescheduling() {
        val stormyHours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).mapIndexed { idx, pt ->
            if (idx == 0) pt.copy(precipMm = 12.0, precipProb = 95, capeJkg = 1500.0, weatherCode = 95)
            else pt.copy(precipMm = 0.0, precipProb = 0, weatherCode = 0)
        }
        val pastEvent = CalendarEvent(
            id = 106L,
            title = "Fußballspiel",
            description = "Bereits vorbei",
            location = "Sportplatz",
            startMillis = BASE_EPOCH_MS - 3 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS - 1 * ONE_HOUR_MS,
            allDay = false
        )

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(pastEvent),
            hourlyPoints = stormyHours,
            sport = SportType.TEAM_SPORT,
            nowMillis = BASE_EPOCH_MS
        )

        assertTrue("Past events must be excluded from future rescheduling", suggestions.isEmpty())
    }

    @Test
    fun f11_t2_allHazardousForecastReportsNoBetterSlotAvailable() {
        val allStormyHours = List(24) { idx ->
            sampleHourlyPoint(
                epochMillis = BASE_EPOCH_MS + idx * ONE_HOUR_MS,
                tempC = 12.0,
                precipMm = 14.0,
                precipProb = 98,
                windKmh = 55.0,
                gustKmh = 85.0,
                capeJkg = 1800.0,
                weatherCode = 95
            )
        }
        val outdoorEvent = CalendarEvent(
            id = 107L,
            title = "Grillen am See",
            description = "BBQ mit Freunden",
            location = "Wannsee",
            startMillis = BASE_EPOCH_MS + 2 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS + 4 * ONE_HOUR_MS,
            allDay = false
        )

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(outdoorEvent),
            hourlyPoints = allStormyHours,
            sport = SportType.WALKING,
            nowMillis = BASE_EPOCH_MS
        )

        assertEquals(1, suggestions.size)
        val s = suggestions.first()
        assertNull("No dry better slot exists when all 24 hours are stormy", s.suggestedStartMillis)
        assertFalse(s.hasBetterSlot)
        assertTrue(s.message.isNotBlank())
    }

    @Test
    fun f11_t2_caseInsensitiveGermanAndEnglishOutdoorKeywordsRecognized() {
        val keywords = listOf("grillen", "WANDERUNG", "fußball", "Picknick", "OUTDOOR", "Hiking Trip", "Radtour")

        keywords.forEach { kw ->
            val verdict = EventRescheduler.classifySensitivity(
                title = kw,
                description = "",
                location = ""
            )
            assertTrue("Keyword '$kw' must classify as weather-sensitive", verdict.isWeatherSensitive)
        }
    }

    @Test
    fun f11_t2_zeroOrInvertedEventEndMillisHandledSafely() {
        val hours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).map {
            it.copy(precipMm = 8.0, precipProb = 90, weatherCode = 65)
        }
        val invertedEvent = CalendarEvent(
            id = 108L,
            title = "Joggen",
            description = "Korrupte Zeitspanne",
            location = "Park",
            startMillis = BASE_EPOCH_MS + 3 * ONE_HOUR_MS,
            endMillis = BASE_EPOCH_MS + 1 * ONE_HOUR_MS, // end < start
            allDay = false
        )

        // Must execute without NegativeArraySizeException / infinite loop / IllegalArgumentException
        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(invertedEvent),
            hourlyPoints = hours,
            sport = SportType.RUNNING,
            nowMillis = BASE_EPOCH_MS
        )

        assertNotNull(suggestions)
    }

    // =========================================================================
    // FEATURE 12: Route Weather & Departure Calculator (5 Tier 1 + 5 Tier 2 = 10 Tests)
    // =========================================================================

    @Test
    fun f12_t1_computeRouteWeatherAcrossOriginMidpointDestinationSegments() {
        val origin = RoutePoint(name = "Berlin Hbf", latitude = 52.5251, longitude = 13.3694, offsetMinutesFromStart = 0)
        val midpoint = RoutePoint(name = "Magdeburg", latitude = 52.1205, longitude = 11.6276, offsetMinutesFromStart = 90)
        val destination = RoutePoint(name = "Hannover Hbf", latitude = 52.3767, longitude = 9.7410, offsetMinutesFromStart = 180)
        val forecasts = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).mapIndexed { idx, pt ->
            pt.copy(tempC = 14.0 + idx * 0.5, precipMm = if (idx == 1) 2.0 else 0.0)
        }

        val result = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = origin.name,
            destination = destination.name,
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = BASE_EPOCH_MS + 4 * ONE_HOUR_MS,
            routePoints = listOf(origin, midpoint, destination),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )

        assertTrue(result.segments.size >= 3)
        assertEquals("Berlin Hbf", result.segments.first().pointName)
        assertEquals("Hannover Hbf", result.segments.last().pointName)
        result.segments.forEach { seg ->
            assertTrue(seg.tempC.isFinite())
            assertTrue(seg.windKmh >= 0.0)
        }
    }

    @Test
    fun f12_t1_computeArrivalWeatherAtTargetArrivalMillis() {
        val origin = RoutePoint("Start", 48.1351, 11.5820, offsetMinutesFromStart = 0)
        val destination = RoutePoint("Garmisch", 47.4917, 11.0955, offsetMinutesFromStart = 60)
        val arrivalHourIdx = 5
        val forecasts = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).mapIndexed { idx, pt ->
            if (idx == arrivalHourIdx) pt.copy(tempC = 9.5, precipMm = 4.2, precipProb = 85, windKmh = 22.0)
            else pt.copy(tempC = 18.0, precipMm = 0.0, precipProb = 5, windKmh = 6.0)
        }

        val result = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "München",
            destination = "Garmisch",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = BASE_EPOCH_MS + arrivalHourIdx * ONE_HOUR_MS,
            routePoints = listOf(origin, destination),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )

        assertNotNull(result.arrivalWeather)
        assertEquals(9.5, result.arrivalWeather!!.tempC, 0.5)
        assertEquals(4.2, result.arrivalWeather!!.precipMm, 0.5)
    }

    @Test
    fun f12_t1_suggestOptimalDepartureShiftWhenRainHitsDefaultDeparture() {
        val origin = RoutePoint("Zuhause", 52.52, 13.405, offsetMinutesFromStart = 0)
        val destination = RoutePoint("Büro", 52.51, 13.38, offsetMinutesFromStart = 45)
        // Heavy rain at hour 2..3 (default departure for 03:00 arrival), dry at hour 0..1 and 5..6
        val forecasts = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS).mapIndexed { idx, pt ->
            if (idx in 2..3) pt.copy(precipMm = 9.0, precipProb = 95, windKmh = 35.0, weatherCode = 65)
            else pt.copy(precipMm = 0.0, precipProb = 0, windKmh = 8.0, weatherCode = 1)
        }
        val targetArrivalMillis = BASE_EPOCH_MS + 3 * ONE_HOUR_MS

        val result = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Zuhause",
            destination = "Büro",
            mode = RouteTransportMode.CYCLING,
            targetArrivalMillis = targetArrivalMillis,
            routePoints = listOf(origin, destination),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )

        val defaultDeparture = targetArrivalMillis - 45 * 60_000L
        assertNotNull(result.recommendedDepartureMillis)
        assertNotEquals(
            "Optimal departure should shift away from rainy default departure",
            defaultDeparture,
            result.recommendedDepartureMillis
        )
        assertTrue(result.departureReason.isNotBlank())
    }

    @Test
    fun f12_t1_buildMapsHandoffUriSupportsDrivingCyclingAndFoot() {
        val drivingUri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = 52.5200,
            originLon = 13.4050,
            destLat = 48.1351,
            destLon = 11.5820,
            destLabel = "München",
            mode = RouteTransportMode.DRIVING
        )
        val cyclingUri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = 52.5200,
            originLon = 13.4050,
            destLat = 52.4000,
            destLon = 13.0600,
            destLabel = "Potsdam",
            mode = RouteTransportMode.CYCLING
        )
        val footUri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = 52.5200,
            originLon = 13.4050,
            destLat = 52.5163,
            destLon = 13.3777,
            destLabel = "Brandenburger Tor",
            mode = RouteTransportMode.FOOT
        )

        listOf(drivingUri, cyclingUri, footUri).forEach { uri ->
            assertTrue(
                "URI '$uri' must use https://www.google.com/maps, google.navigation:, or geo: scheme",
                uri.startsWith("https://") || uri.startsWith("google.navigation:") || uri.startsWith("geo:")
            )
        }
        assertTrue(drivingUri.contains("driving", ignoreCase = true) || drivingUri.contains("mode=d", ignoreCase = true))
        assertTrue(cyclingUri.contains("bicycling", ignoreCase = true) || cyclingUri.contains("cycling", ignoreCase = true) || cyclingUri.contains("mode=b", ignoreCase = true))
        assertTrue(footUri.contains("walking", ignoreCase = true) || footUri.contains("foot", ignoreCase = true) || footUri.contains("mode=w", ignoreCase = true))
    }

    @Test
    fun f12_t1_parseOsrmRouteGeometryAndDurationCleanly() {
        val osrmJson = """
            {
              "code": "Ok",
              "routes": [
                {
                  "duration": 2700.0,
                  "distance": 32500.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [13.4050, 52.5200],
                      [13.2350, 52.4600],
                      [13.0645, 52.4009]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = OsrmRouteParser.parse(osrmJson)

        assertTrue(parsed.isSuccess)
        assertEquals(2700.0, parsed.durationSeconds, 0.1)
        assertEquals(32500.0, parsed.distanceMeters, 0.1)
        assertEquals(3, parsed.points.size)
        // GeoJSON coordinates are [lon, lat]; verify converted properly to (lat, lon)
        assertEquals(52.5200, parsed.points.first().latitude, 0.0001)
        assertEquals(13.4050, parsed.points.first().longitude, 0.0001)
        assertEquals(52.4009, parsed.points.last().latitude, 0.0001)
        assertEquals(13.0645, parsed.points.last().longitude, 0.0001)
    }

    @Test
    fun f12_t2_emptyRoutePointsOrSinglePointZeroDistanceHandledSafely() {
        val forecasts = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS)

        val emptyResult = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Start",
            destination = "Ziel",
            mode = RouteTransportMode.FOOT,
            targetArrivalMillis = BASE_EPOCH_MS + 2 * ONE_HOUR_MS,
            routePoints = emptyList(),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )
        val singlePointResult = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Start",
            destination = "Start",
            mode = RouteTransportMode.FOOT,
            targetArrivalMillis = BASE_EPOCH_MS + 2 * ONE_HOUR_MS,
            routePoints = listOf(RoutePoint("Start", 52.52, 13.405, offsetMinutesFromStart = 0)),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )

        assertNotNull(emptyResult)
        assertNotNull(singlePointResult)
        assertEquals(1, singlePointResult.segments.size)
    }

    @Test
    fun f12_t2_unreachableOsrmOrMalformedPayloadProducesClearErrorResult() {
        val noRouteJson = """{"code": "NoRoute", "routes": []}"""
        val invalidJson = """{not-valid-json"""

        val noRouteParsed = OsrmRouteParser.parse(noRouteJson)
        val invalidParsed = OsrmRouteParser.parse(invalidJson)

        assertFalse(noRouteParsed.isSuccess)
        assertTrue(noRouteParsed.errorMessage.isNotBlank())
        assertFalse(invalidParsed.isSuccess)
        assertTrue(invalidParsed.errorMessage.isNotBlank())
    }

    @Test
    fun f12_t2_specialCharactersAndSpacesUrlEncodedInMapsHandoffUri() {
        val uri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = 48.1402,
            originLon = 11.5583,
            destLat = 48.1372,
            destLon = 11.5756,
            destLabel = "München Hbf & Café am See",
            mode = RouteTransportMode.FOOT
        )

        assertFalse("Raw spaces must be URL-encoded", uri.contains(" "))
        assertFalse("Unencoded '&' inside label must not leak as extra query param", uri.contains("Hbf & Café"))
        assertTrue(uri.contains("M%C3%BCnchen", ignoreCase = true) || uri.contains("48.1372"))
    }

    @Test
    fun f12_t2_targetArrivalInPastClampedOrFlaggedSafely() {
        val forecasts = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS)
        val origin = RoutePoint("A", 52.52, 13.40, 0)
        val dest = RoutePoint("B", 52.50, 13.35, 30)

        val result = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "A",
            destination = "B",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = BASE_EPOCH_MS - 2 * ONE_HOUR_MS,
            routePoints = listOf(origin, dest),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )

        assertTrue(result.recommendedDepartureMillis >= BASE_EPOCH_MS || result.isPastTargetArrival)
    }

    @Test
    fun f12_t2_extremeRouteDurationBeyondForecastHorizonUsesNearestAvailablePoint() {
        val forecasts = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS) // 24 hours only
        val origin = RoutePoint("Berlin", 52.52, 13.40, offsetMinutesFromStart = 0)
        val destination = RoutePoint("Lissabon", 38.72, -9.14, offsetMinutesFromStart = 36 * 60)

        val result = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Berlin",
            destination = "Lissabon",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = BASE_EPOCH_MS + 36 * ONE_HOUR_MS,
            routePoints = listOf(origin, destination),
            hourlyForecasts = forecasts,
            nowMillis = BASE_EPOCH_MS
        )

        assertNotNull(result.arrivalWeather)
        assertEquals(forecasts.last().tempC, result.arrivalWeather!!.tempC, 0.5)
    }

    // =========================================================================
    // FEATURE 13: Morning/Evening Briefings & Local German Free-Text (7 Tier 1 + 6 Tier 2 = 13 Tests)
    // =========================================================================

    @Test
    fun f13_t1_briefingComposerGuaranteesAllFiveMandatoryComponentsNonEmpty() {
        val clothing = ClothingAdvisor.recommendClothing(
            tempC = 11.0,
            feelsLikeC = 8.5,
            precipProb = 80,
            windKmh = 25.0,
            uvIndex = 2.0
        )
        val hours = sample24HourSeries(startEpochMillis = BASE_EPOCH_MS)
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)

        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Donnerstag, 20.11.",
            dailySummary = "11 °C, wechselhaft mit Schauern am Nachmittag",
            clothing = clothing,
            bestSportWindow = bestWindow,
            calendarWarnings = listOf("15:00 Grillen im Park: Regenrisiko 80 %"),
            routeCheck = "Pendelstrecke trocken bis 08:30 Uhr, Abfahrt 07:45 empfohlen"
        )

        assertTrue("Component 1 (daily weather) must be non-empty", briefing.dailyWeatherText.isNotBlank())
        assertTrue("Component 2 (clothing advice) must be non-empty", briefing.clothingText.isNotBlank())
        assertTrue("Component 3 (best sport window) must be non-empty", briefing.sportWindowText.isNotBlank())
        assertTrue("Component 4 (calendar warnings) must be non-empty", briefing.calendarWarningsText.isNotBlank())
        assertTrue("Component 5 (route/commute check) must be non-empty", briefing.routeCheckText.isNotBlank())
    }

    @Test
    fun f13_t1_briefingSchedulerDefaultsMorningTo0700AndSupportsCustomTime() {
        val store = InMemoryKeyValueStore()
        val scheduler1 = BriefingScheduler(store)

        val defaultMorning = scheduler1.getMorningSchedule()
        assertEquals(7, defaultMorning.hour)
        assertEquals(0, defaultMorning.minute)

        scheduler1.setMorningSchedule(hour = 6, minute = 30, enabled = true)
        scheduler1.setEveningCheckSchedule(hour = 20, minute = 15, enabled = true)

        val scheduler2 = BriefingScheduler(store)
        val persistedMorning = scheduler2.getMorningSchedule()
        val persistedEvening = scheduler2.getEveningCheckSchedule()
        assertEquals(6, persistedMorning.hour)
        assertEquals(30, persistedMorning.minute)
        assertTrue(persistedMorning.enabled)
        assertEquals(20, persistedEvening.hour)
        assertEquals(15, persistedEvening.minute)
        assertTrue(persistedEvening.enabled)
    }

    @Test
    fun f13_t1_clothingAdvisorRecommendsRainGearColdJacketAndSunProtection() {
        val rainyCold = ClothingAdvisor.recommendClothing(
            tempC = 4.0,
            feelsLikeC = 0.0,
            precipProb = 90,
            windKmh = 32.0,
            uvIndex = 1.0
        )
        val hotSunnyHighUv = ClothingAdvisor.recommendClothing(
            tempC = 30.0,
            feelsLikeC = 32.0,
            precipProb = 0,
            windKmh = 6.0,
            uvIndex = 9.0
        )

        assertTrue(
            "Rainy cold recommendation must mention jacket/rain protection",
            rainyCold.summary.contains("Regen", ignoreCase = true) ||
                rainyCold.summary.contains("Jacke", ignoreCase = true) ||
                rainyCold.summary.contains("Schirm", ignoreCase = true)
        )
        assertTrue(
            "Hot high-UV recommendation must mention sun protection or light clothing",
            hotSunnyHighUv.summary.contains("Sonnen", ignoreCase = true) ||
                hotSunnyHighUv.summary.contains("UV", ignoreCase = true) ||
                hotSunnyHighUv.summary.contains("leicht", ignoreCase = true)
        )
        assertTrue(rainyCold.layers.isNotEmpty())
        assertTrue(hotSunnyHighUv.layers.isNotEmpty())
    }

    @Test
    fun f13_t1_germanFreeTextParserClassifiesWeatherQueryLocally() {
        val intent = GermanFreeTextParser.parseIntent("Wie wird das Wetter heute in Berlin?")

        assertEquals(QueryIntentCategory.WEATHER_QUERY, intent.category)
        assertTrue(intent.responseHint.isNotBlank())
    }

    @Test
    fun f13_t1_germanFreeTextParserClassifiesSportAndClothingLocally() {
        val sportIntent = GermanFreeTextParser.parseIntent("Wann kann ich heute joggen gehen?")
        val clothingIntent = GermanFreeTextParser.parseIntent("Was soll ich heute anziehen?")

        assertEquals(QueryIntentCategory.SPORT_WINDOW, sportIntent.category)
        assertEquals(QueryIntentCategory.CLOTHING, clothingIntent.category)
    }

    @Test
    fun f13_t1_germanFreeTextParserClassifiesEventAndRouteLocally() {
        val eventIntent = GermanFreeTextParser.parseIntent("Fällt mein Grillen im Kalender morgen ins Wasser?")
        val routeIntent = GermanFreeTextParser.parseIntent("Wann muss ich für meine Route nach München losfahren?")

        assertEquals(QueryIntentCategory.EVENT_PLANNING, eventIntent.category)
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, routeIntent.category)
    }

    @Test
    fun f13_t1_briefingNotificationPayloadIncludesAllFiveComponents() {
        val clothing = ClothingAdvisor.recommendClothing(17.0, 17.0, 10, 8.0, 4.0)
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Freitag, 21.11.",
            dailySummary = "17 °C, sonnig bis leicht bewölkt",
            clothing = clothing,
            bestSportWindow = SportAdvisor.findBestSportWindow(
                SportType.CYCLING,
                sample24HourSeries(startEpochMillis = BASE_EPOCH_MS)
            ),
            calendarWarnings = listOf("Keine wetterkritischen Termine"),
            routeCheck = "Freie Fahrt ohne Regen auf dem Arbeitsweg"
        )

        val payload = briefing.toNotificationBody()

        assertTrue(payload.contains("17 °C"))
        assertTrue(payload.contains(briefing.clothingText))
        assertTrue(payload.contains(briefing.sportWindowText))
        assertTrue(payload.contains(briefing.calendarWarningsText))
        assertTrue(payload.contains(briefing.routeCheckText))
    }

    @Test
    fun f13_t2_unrecognizedOrEnglishInputReturnsUnknownWithHelpfulGermanHint() {
        val gibberish = GermanFreeTextParser.parseIntent("xyzzy blubb 12345 ???")
        val englishNonDomain = GermanFreeTextParser.parseIntent("Calculate quantum eigenvalues please")

        assertEquals(QueryIntentCategory.UNKNOWN, gibberish.category)
        assertEquals(QueryIntentCategory.UNKNOWN, englishNonDomain.category)
        assertTrue(gibberish.responseHint.isNotBlank())
        assertTrue(englishNonDomain.responseHint.isNotBlank())
    }

    @Test
    fun f13_t2_blankAndWhitespaceQueryHandledSafely() {
        val empty = GermanFreeTextParser.parseIntent("")
        val whitespace = GermanFreeTextParser.parseIntent("   \n\t   ")

        assertEquals(QueryIntentCategory.UNKNOWN, empty.category)
        assertEquals(QueryIntentCategory.UNKNOWN, whitespace.category)
        assertTrue(empty.responseHint.isNotBlank())
    }

    @Test
    fun f13_t2_ambiguousQueryMapsDeterministically() {
        val query = "Regnet es auf dem Weg zum Fußball?"
        val first = GermanFreeTextParser.parseIntent(query)
        val second = GermanFreeTextParser.parseIntent(query)

        assertEquals(first.category, second.category)
        assertNotEquals(QueryIntentCategory.UNKNOWN, first.category)
    }

    @Test
    fun f13_t2_missingCalendarAndMissingRouteStillProduceNonEmptyDefaultsInBriefing() {
        val clothing = ClothingAdvisor.recommendClothing(15.0, 15.0, 20, 12.0, 3.0)
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Samstag",
            dailySummary = "15 °C, trocken",
            clothing = clothing,
            bestSportWindow = null,
            calendarWarnings = emptyList(),
            routeCheck = null
        )

        assertTrue(briefing.dailyWeatherText.isNotBlank())
        assertTrue(briefing.clothingText.isNotBlank())
        assertTrue("Null sport window must still produce non-empty German fallback", briefing.sportWindowText.isNotBlank())
        assertTrue("Empty calendar warnings must still produce non-empty default text", briefing.calendarWarningsText.isNotBlank())
        assertTrue("Null route check must still produce non-empty default text", briefing.routeCheckText.isNotBlank())
    }

    @Test
    fun f13_t2_briefingSchedulerClampsInvalidHourAndMinute() {
        val store = InMemoryKeyValueStore()
        val scheduler = BriefingScheduler(store)

        scheduler.setMorningSchedule(hour = 25, minute = 99, enabled = true)
        val highClamped = scheduler.getMorningSchedule()
        assertTrue("Hour 25 must clamp into [0, 23]", highClamped.hour in 0..23)
        assertTrue("Minute 99 must clamp into [0, 59]", highClamped.minute in 0..59)

        scheduler.setMorningSchedule(hour = -3, minute = -15, enabled = true)
        val lowClamped = scheduler.getMorningSchedule()
        assertTrue("Hour -3 must clamp into [0, 23]", lowClamped.hour in 0..23)
        assertTrue("Minute -15 must clamp into [0, 59]", lowClamped.minute in 0..59)
    }

    @Test
    fun f13_t2_briefingSchedulerFallsBackWhenExactAlarmsDisallowed() {
        val store = InMemoryKeyValueStore()
        val scheduler = BriefingScheduler(store)

        val exactPlan = scheduler.planScheduleStrategy(canScheduleExactAlarms = true)
        val fallbackPlan = scheduler.planScheduleStrategy(canScheduleExactAlarms = false)

        assertTrue(exactPlan.usesExactAlarm)
        assertFalse(fallbackPlan.usesExactAlarm)
        assertTrue(fallbackPlan.usesInexactOrWorkManagerFallback)
    }

    // =========================================================================
    // FEATURE 14: Lock-Screen Widget & Live Updates (5 Tier 1 + 5 Tier 2 = 10 Tests)
    // =========================================================================

    @Test
    fun f14_t1_widgetPrefsResolutionFallsBackToSharedFrogWeatherPrefs() {
        val widgetStore = InMemoryKeyValueStore() // No widget-specific lat_1/lon_1
        val frogPrefs = InMemoryKeyValueStore().apply {
            putFloat("last_city_lat", 52.52f)
            putFloat("last_city_lon", 13.405f)
            putString("last_city_name", "Berlin")
            putString("last_city_country", "DE")
        }

        val resolved = WeatherWidgetProvider.resolveWidgetLocation(
            appWidgetId = 1,
            widgetStore = widgetStore,
            sharedAppStore = frogPrefs
        )

        assertNotNull(resolved)
        assertEquals(52.52, resolved!!.latitude, 0.01)
        assertEquals(13.405, resolved.longitude, 0.01)
        assertEquals("Berlin", resolved.cityName)
    }

    @Test
    fun f14_t1_widgetResolvesLocalCityImagePathNeverUnsplashUrl() {
        val localImages = mapOf(WeatherScenario.SUNNY to "berlin_sunny_1234.jpg")

        val resolvedFilename = WeatherWidgetProvider.resolveLocalCityImageFilename(
            scenario = WeatherScenario.SUNNY,
            cityImages = localImages
        )

        assertEquals("berlin_sunny_1234.jpg", resolvedFilename)
        assertFalse(resolvedFilename!!.contains("unsplash.com"))
    }

    @Test
    fun f14_t1_liveNotificationStateIncludesTempConditionLocationAndPublicVisibility() {
        val state = WeatherNotificationService.buildLiveNotificationState(
            tempC = 18.4,
            condition = "Sonnig",
            locationName = "München",
            weatherType = WeatherType.SUNNY
        )

        assertTrue(state.title.contains("18°"))
        assertTrue(state.title.contains("Sonnig"))
        assertTrue(state.contentText.contains("München"))
        assertTrue(state.ongoing)
        assertEquals(1 /* NotificationCompat.VISIBILITY_PUBLIC */, state.visibility)
    }

    @Test
    fun f14_t1_bootReceiverTriggersRescheduleOnBootCompleted() {
        var rescheduled = false

        val handled = BootReceiver.handleIntentAction(
            action = "android.intent.action.BOOT_COMPLETED",
            onBootCompleted = { rescheduled = true }
        )

        assertTrue(handled)
        assertTrue(rescheduled)
    }

    @Test
    fun f14_t1_widgetMetadataDeclaresHomeScreenAndKeyguardCategory() {
        val widgetCategorySpec = WeatherWidgetProvider.REQUIRED_WIDGET_CATEGORY

        assertTrue(widgetCategorySpec.contains("home_screen"))
        assertTrue(widgetCategorySpec.contains("keyguard"))
    }

    @Test
    fun f14_t2_nullIslandZeroZeroFilteredInWidgetAndNotificationService() {
        val widgetStoreWithZero = InMemoryKeyValueStore().apply {
            putFloat("lat_1", 0.0f)
            putFloat("lon_1", 0.0f)
        }
        val validSharedStore = InMemoryKeyValueStore().apply {
            putFloat("last_city_lat", 48.1351f)
            putFloat("last_city_lon", 11.5820f)
            putString("last_city_name", "München")
        }

        val widgetResolved = WeatherWidgetProvider.resolveWidgetLocation(
            appWidgetId = 1,
            widgetStore = widgetStoreWithZero,
            sharedAppStore = validSharedStore
        )
        val notifResolved = WeatherNotificationService.resolveCoordinates(
            intentLat = 0.0,
            intentLon = 0.0,
            savedNotifLat = 0.0,
            savedNotifLon = 0.0,
            savedCityLat = 0.0,
            savedCityLon = 0.0
        )

        assertNotNull(widgetResolved)
        assertEquals(48.1351, widgetResolved!!.latitude, 0.01)
        assertNull("All (0.0, 0.0) coordinates must be filtered as Null Island", notifResolved)
    }

    @Test
    fun f14_t2_missingLocalCityImageFallsBackCleanlyWithoutCrash() {
        val resolvedMissingScenario = WeatherWidgetProvider.resolveLocalCityImageFilename(
            scenario = WeatherScenario.BLIZZARD,
            cityImages = mapOf(WeatherScenario.SUNNY to "berlin_sunny.jpg")
        )
        val resolvedEmptyMap = WeatherWidgetProvider.resolveLocalCityImageFilename(
            scenario = WeatherScenario.RAIN,
            cityImages = emptyMap()
        )

        assertNull(resolvedMissingScenario)
        assertNull(resolvedEmptyMap)
    }

    @Test
    fun f14_t2_missingFrogWeatherPrefsRendersSafePlaceholderTemp() {
        val emptyWidgetStore = InMemoryKeyValueStore()
        val emptySharedStore = InMemoryKeyValueStore()

        val resolved = WeatherWidgetProvider.resolveWidgetLocation(
            appWidgetId = 42,
            widgetStore = emptyWidgetStore,
            sharedAppStore = emptySharedStore
        )
        val placeholder = WeatherWidgetProvider.buildPlaceholderDisplayState()

        assertNull(resolved)
        assertEquals("--°", placeholder.temperatureText)
        assertEquals("FrogWeather", placeholder.cityText)
        assertEquals("🐸", placeholder.emoji)
    }

    @Test
    fun f14_t2_bootReceiverIgnoresNonBootCompletedIntent() {
        var rescheduled = false

        val handledOther = BootReceiver.handleIntentAction(
            action = "android.intent.action.AIRPLANE_MODE",
            onBootCompleted = { rescheduled = true }
        )
        val handledNull = BootReceiver.handleIntentAction(
            action = null,
            onBootCompleted = { rescheduled = true }
        )

        assertFalse(handledOther)
        assertFalse(handledNull)
        assertFalse(rescheduled)
    }

    @Test
    fun f14_t2_coldStartEmptyOrNaNCachedNotificationTempHandledSafely() {
        val stateNull = WeatherNotificationService.buildLiveNotificationState(
            tempC = null,
            condition = null,
            locationName = null,
            weatherType = WeatherType.OTHER
        )
        val stateNaN = WeatherNotificationService.buildLiveNotificationState(
            tempC = Double.NaN,
            condition = "",
            locationName = "",
            weatherType = WeatherType.OTHER
        )

        assertFalse(stateNull.title.contains("NaN"))
        assertFalse(stateNaN.title.contains("NaN"))
        assertTrue(stateNull.title.isNotBlank())
        assertTrue(stateNaN.title.isNotBlank())
    }
}
