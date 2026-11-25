package com.frogweather.phase4

import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.network.OsrmClient
import com.frogweather.network.OsrmCoordPoint
import com.frogweather.network.OsrmRouteParser
import com.frogweather.route.RoutePoint
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmAndRouteWeatherTest {

    private val baseEpoch = 1_783_209_600_000L
    private val oneHour = 3_600_000L

    private fun sampleHours(count: Int = 24): List<HourlyWeatherPoint> = (0 until count).map { idx ->
        HourlyWeatherPoint(
            epochMillis = baseEpoch + idx * oneHour,
            tempC = 17.0,
            precipMm = 0.0,
            precipProb = 5,
            windKmh = 10.0,
            gustKmh = 16.0,
            uvIndex = 3.0,
            capeJkg = 40.0,
            weatherCode = 1,
            humidityPct = 55,
            cloudCoverPct = 20,
            pressureHpa = 1016.0
        )
    }

    @Test
    fun formatCoordinates_usesLocaleUsDotAndLonLatOrder() {
        val formatted = OsrmClient.formatCoordinates(
            originLat = 52.5200,
            originLon = 13.4050,
            destLat = 48.1351,
            destLon = 11.5820
        )
        assertEquals("13.40500,52.52000;11.58200,48.13510", formatted)
        assertFalse(formatted.contains(", "))
    }

    @Test
    fun parseOsrmRoute_swapsGeoJsonLonLatToLatitudeLongitude() {
        val json = """
            {
              "code": "Ok",
              "routes": [
                {
                  "duration": 1800.0,
                  "distance": 15200.0,
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

        val result = OsrmRouteParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(1800.0, result.durationSeconds, 0.1)
        assertEquals(15200.0, result.distanceMeters, 0.1)
        assertEquals(3, result.points.size)
        assertEquals(52.5200, result.points.first().latitude, 0.0001)
        assertEquals(13.4050, result.points.first().longitude, 0.0001)
    }

    @Test
    fun parseOsrmRoute_handlesNoRouteEmptyAndMalformedJson() {
        val noRoute = OsrmRouteParser.parse("""{"code": "NoRoute", "routes": []}""")
        val empty = OsrmRouteParser.parse("   ")
        val malformed = OsrmRouteParser.parse("{bad-json")

        assertFalse(noRoute.isSuccess)
        assertTrue(noRoute.errorMessage.isNotBlank())
        assertFalse(empty.isSuccess)
        assertFalse(malformed.isSuccess)
    }

    @Test
    fun sampleWaypoints_handlesEmptySingleAndMultiPointRoutes() {
        assertTrue(OsrmRouteParser.sampleWaypoints(emptyList(), 1200.0, "A", "B").isEmpty())

        val single = OsrmRouteParser.sampleWaypoints(
            points = listOf(OsrmCoordPoint(52.52, 13.405)),
            durationSeconds = 0.0,
            originName = "Berlin",
            destinationName = "Berlin"
        )
        assertEquals(1, single.size)
        assertEquals(0, single.first().offsetMinutesFromStart)

        val multi = OsrmRouteParser.sampleWaypoints(
            points = (0..10).map { i -> OsrmCoordPoint(52.0 + i * 0.01, 13.0 + i * 0.01) },
            durationSeconds = 3600.0,
            originName = "Start",
            destinationName = "Ziel",
            maxSamples = 5
        )
        assertEquals(5, multi.size)
        assertEquals("Start", multi.first().name)
        assertEquals("Ziel", multi.last().name)
        assertEquals(60, multi.last().offsetMinutesFromStart)
    }

    @Test
    fun computeRouteAndDeparture_reportsArrivalWeatherAtTargetArrivalAndShiftsRainyDeparture() {
        val hours = sampleHours().mapIndexed { idx, pt ->
            when (idx) {
                2 -> pt.copy(precipMm = 6.5, precipProb = 90, windKmh = 35.0, weatherCode = 65)
                5 -> pt.copy(tempC = 9.5, precipMm = 4.2, precipProb = 85)
                else -> pt
            }
        }
        val points = listOf(
            RoutePoint("Start", 52.52, 13.405, offsetMinutesFromStart = 0),
            RoutePoint("Mitte", 52.46, 13.235, offsetMinutesFromStart = 30),
            RoutePoint("Ziel", 52.40, 13.065, offsetMinutesFromStart = 60)
        )

        val arrivalRes = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Start",
            destination = "Ziel",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = baseEpoch + 5 * oneHour,
            routePoints = points,
            hourlyForecasts = hours,
            nowMillis = baseEpoch
        )
        assertNotNull(arrivalRes.arrivalWeather)
        assertEquals(9.5, arrivalRes.arrivalWeather!!.tempC, 0.2)
        assertEquals(4.2, arrivalRes.arrivalWeather!!.precipMm, 0.2)

        val shiftRes = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Start",
            destination = "Ziel",
            mode = RouteTransportMode.CYCLING,
            targetArrivalMillis = baseEpoch + 3 * oneHour,
            routePoints = points,
            hourlyForecasts = hours,
            nowMillis = baseEpoch
        )
        val defaultDep = baseEpoch + 3 * oneHour - 60 * 60_000L
        assertNotEquals(defaultDep, shiftRes.recommendedDepartureMillis)
        assertTrue(shiftRes.departureReason.isNotBlank())
    }

    @Test
    fun computeRouteAndDeparture_clampsPastArrivalAndHandlesEmptyPoints() {
        val hours = sampleHours()
        val emptyPointsRes = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "A",
            destination = "B",
            mode = RouteTransportMode.FOOT,
            targetArrivalMillis = baseEpoch + 2 * oneHour,
            routePoints = emptyList(),
            hourlyForecasts = hours,
            nowMillis = baseEpoch
        )
        assertNotNull(emptyPointsRes.arrivalWeather)

        val pastRes = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "A",
            destination = "B",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = baseEpoch - 2 * oneHour,
            routePoints = listOf(RoutePoint("A", 52.5, 13.4, 0), RoutePoint("B", 52.4, 13.3, 30)),
            hourlyForecasts = hours,
            nowMillis = baseEpoch
        )
        assertTrue(pastRes.isPastTargetArrival)
        assertTrue(pastRes.recommendedDepartureMillis >= baseEpoch)
    }

    @Test
    fun buildMapsHandoffUri_encodesSpecialCharsAndSupportsAllModes() {
        val uri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = 48.1402,
            originLon = 11.5583,
            destLat = 48.1372,
            destLon = 11.5756,
            destLabel = "München Hbf & Café am See",
            mode = RouteTransportMode.CYCLING
        )
        assertTrue(uri.startsWith("https://www.google.com/maps/dir/"))
        assertFalse(uri.contains(" "))
        assertFalse(uri.contains("& Café"))
        assertTrue(uri.contains("travelmode=bicycling"))
    }
}
