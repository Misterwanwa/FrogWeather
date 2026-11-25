package com.frogweather.phase4

import com.frogweather.WeatherType
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportType
import com.frogweather.assistant.AssistantContext
import com.frogweather.assistant.AssistantResponseEngine
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.assistant.TemporalRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class GermanFreeTextParserTest {

    @Test
    fun parseIntent_classifiesAllFiveCategoriesAccurately() {
        assertEquals(
            QueryIntentCategory.WEATHER_QUERY,
            GermanFreeTextParser.parseIntent("Wie wird das Wetter morgen in Berlin?").category
        )
        assertEquals(
            QueryIntentCategory.SPORT_WINDOW,
            GermanFreeTextParser.parseIntent("Wann kann ich heute joggen gehen?").category
        )
        assertEquals(
            QueryIntentCategory.EVENT_PLANNING,
            GermanFreeTextParser.parseIntent("Fällt mein Grillen im Kalender morgen ins Wasser?").category
        )
        assertEquals(
            QueryIntentCategory.ROUTE_WEATHER,
            GermanFreeTextParser.parseIntent("Wann muss ich für meine Route nach München losfahren?").category
        )
        assertEquals(
            QueryIntentCategory.CLOTHING,
            GermanFreeTextParser.parseIntent("Was soll ich heute anziehen?").category
        )
    }

    @Test
    fun parseIntent_handlesCompoundPrecedenceAndUmlautFolding() {
        val clothingForRun = GermanFreeTextParser.parseIntent("Was soll ich morgen zum Laufen anziehen?")
        assertEquals(QueryIntentCategory.CLOTHING, clothingForRun.category)
        assertEquals(SportType.RUNNING, clothingForRun.sportHint)
        assertEquals(TemporalRef.TOMORROW, clothingForRun.temporalRef)

        val asciiFolded = GermanFreeTextParser.parseIntent("Wie wird das Wetter uebermorgen in Muenchen um 16 Uhr?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, asciiFolded.category)
        assertEquals(TemporalRef.SPECIFIC_HOUR, asciiFolded.temporalRef)
        assertEquals(16, asciiFolded.targetHour)
        assertEquals("Muenchen", asciiFolded.locationHint)
    }

    @Test
    fun parseIntent_extractsRouteOriginAndDestinationAndAllEightSports() {
        val route = GermanFreeTextParser.parseIntent("Wie ist das Wetter auf der Route von Berlin nach Potsdam morgen?")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, route.category)
        assertEquals("Berlin", route.routeOrigin)
        assertEquals("Potsdam", route.routeDestination)

        val sportQueries = mapOf(
            "Wann joggen?" to SportType.RUNNING,
            "Wann radfahren?" to SportType.CYCLING,
            "Wann wandern?" to SportType.HIKING,
            "Wann outdoor-fitness?" to SportType.OUTDOOR_FITNESS,
            "Wann fußball spielen?" to SportType.TEAM_SPORT,
            "Wann im freibad schwimmen?" to SportType.SWIMMING,
            "Wann ski fahren?" to SportType.WINTER_SPORT,
            "Wann spazieren gehen?" to SportType.WALKING
        )
        sportQueries.forEach { (q, expectedSport) ->
            val parsed = GermanFreeTextParser.parseIntent(q)
            assertEquals(expectedSport, parsed.sportHint)
        }
    }

    @Test
    fun parseIntent_returnsUnknownForBlankGibberishAndNonDomainInput() {
        listOf("", "   \t\n", "xyzzy blubb 12345 ???", "Calculate quantum eigenvalues").forEach { q ->
            val parsed = GermanFreeTextParser.parseIntent(q)
            assertEquals(QueryIntentCategory.UNKNOWN, parsed.category)
            assertTrue(parsed.responseHint.isNotBlank())
        }
    }

    @Test
    fun parseIntent_isDeterministicForAmbiguousQueries() {
        val q = "Regnet es auf dem Weg zum Fußball?"
        val first = GermanFreeTextParser.parseIntent(q)
        val second = GermanFreeTextParser.parseIntent(q)
        assertEquals(first.category, second.category)
        assertNotEquals(QueryIntentCategory.UNKNOWN, first.category)
    }

    @Test
    fun assistantResponseEngine_synthesizesAnswersOfflineForAllCategories() {
        val base = 1_783_209_600_000L
        val hours = (0 until 24).map { idx ->
            HourlyWeatherPoint(
                epochMillis = base + idx * 3_600_000L,
                tempC = 16.0 + (idx % 6),
                precipMm = if (idx == 8) 3.0 else 0.0,
                precipProb = if (idx == 8) 80 else 10,
                windKmh = 12.0,
                gustKmh = 18.0,
                uvIndex = 4.0,
                capeJkg = 40.0,
                weatherCode = if (idx == 8) 63 else 1,
                humidityPct = 55,
                cloudCoverPct = 20,
                pressureHpa = 1015.0
            )
        }
        val ctx = AssistantContext(
            cityName = "Berlin",
            currentTempC = 18.0,
            feelsLikeC = 17.0,
            weatherType = WeatherType.CLOUDY,
            windKmh = 12.0,
            uvIndex = 4.0,
            selectedSport = SportType.RUNNING,
            hourlyPoints = hours,
            calendarWarnings = listOf("Grillen im Park: Regenrisiko"),
            lastRouteSummary = "Berlin → Potsdam: Abfahrt 08:30 empfohlen"
        )

        listOf(
            "Wie wird das Wetter heute?",
            "Wann kann ich laufen gehen?",
            "Was soll ich anziehen?",
            "Welche Termine kollidieren im Kalender?",
            "Wie ist meine Route?",
            "blubb123"
        ).forEach { q ->
            val ans = AssistantResponseEngine.answer(q, ctx, nowMillis = base)
            assertNotNull(ans)
            assertTrue(ans.title.isNotBlank())
            assertTrue(ans.body.isNotBlank())
        }
    }

    @Test
    fun parseIntent_avoidsFalsePositiveNachWegenRadAndEventSubstringsAndParsesHhMmUhr() {
        val afterRain = GermanFreeTextParser.parseIntent("Geht es nach dem Regen?")
        assertNotEquals(QueryIntentCategory.ROUTE_WEATHER, afterRain.category)
        assertNull(afterRain.routeDestination)

        val afterWorkWeather = GermanFreeTextParser.parseIntent("Wie wird es nach der Arbeit?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, afterWorkWeather.category)
        assertNull(afterWorkWeather.routeDestination)

        val afterWorkDrive = GermanFreeTextParser.parseIntent("Wann fahre ich nach der Arbeit?")
        assertNotEquals(QueryIntentCategory.ROUTE_WEATHER, afterWorkDrive.category)
        assertNull(afterWorkDrive.routeDestination)

        val hhMmUhr = GermanFreeTextParser.parseIntent("Kann ich um 14:30 Uhr joggen gehen?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, hhMmUhr.category)
        assertEquals(TemporalRef.SPECIFIC_HOUR, hhMmUhr.temporalRef)
        assertEquals(14, hhMmUhr.targetHour)

        val geradeUnknown = GermanFreeTextParser.parseIntent("Was geht gerade?")
        assertEquals(QueryIntentCategory.UNKNOWN, geradeUnknown.category)
        assertNull(geradeUnknown.sportHint)

        val geradeGrad = GermanFreeTextParser.parseIntent("Sind es gerade 20 Grad?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, geradeGrad.category)
        assertNull(geradeGrad.sportHint)

        val wegenStorm = GermanFreeTextParser.parseIntent("Wie wird das Wetter wegen dem Sturm?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, wegenStorm.category)

        val sqlEvents = GermanFreeTextParser.parseIntent("'; DROP TABLE events; --")
        assertEquals(QueryIntentCategory.UNKNOWN, sqlEvents.category)
    }

    @Test
    fun assistantResponseEngine_sanitizesNaNAndSlicesFutureAndTomorrowProperly() {
        val offsetSec = 7200
        val offset = ZoneOffset.ofTotalSeconds(offsetSec)
        val day0Start = LocalDateTime.of(2026, 7, 10, 0, 0).toInstant(offset).toEpochMilli()
        val nowAfternoon = LocalDateTime.of(2026, 7, 10, 15, 0).toInstant(offset).toEpochMilli()

        val nanHours = listOf(
            HourlyWeatherPoint(
                epochMillis = nowAfternoon + 3_600_000L,
                tempC = Double.NaN,
                precipMm = Double.NaN,
                precipProb = 5,
                windKmh = Double.NaN,
                gustKmh = 10.0,
                uvIndex = Double.NaN,
                capeJkg = 0.0,
                weatherCode = 1,
                humidityPct = 50,
                cloudCoverPct = 20,
                pressureHpa = 1015.0
            )
        )
        val nanCtx = AssistantContext(
            cityName = "Berlin",
            currentTempC = Double.NaN,
            feelsLikeC = Double.NaN,
            weatherType = WeatherType.CLOUDY,
            windKmh = Double.NaN,
            uvIndex = Double.NaN,
            selectedSport = SportType.RUNNING,
            hourlyPoints = nanHours,
            utcOffsetSeconds = offsetSec
        )
        val nanAns = AssistantResponseEngine.answer("Wie wird das Wetter heute?", nanCtx, nowMillis = nowAfternoon)
        assertFalse(nanAns.body.contains("NaN"))
        assertFalse(nanAns.body.contains("aktuell 0 °C"))

        // 48h series: day 0 morning (06..10) dry, day 0 afternoon (15..23) rainy; day 1 (tomorrow) is 28..30 °C
        val twoDayHours = (0 until 48).map { h ->
            val isToday = h < 24
            val isGoodMorning = h in 6..10
            val isGoodEvening = h in 20..22
            HourlyWeatherPoint(
                epochMillis = day0Start + h * 3_600_000L,
                tempC = if (isToday) 16.0 else 29.0,
                precipMm = if (isToday && !isGoodMorning && !isGoodEvening) 6.0 else 0.0,
                precipProb = if (isToday && !isGoodMorning && !isGoodEvening) 90 else 5,
                windKmh = 10.0,
                gustKmh = 15.0,
                uvIndex = 3.0,
                capeJkg = 40.0,
                weatherCode = if (isToday && !isGoodMorning && !isGoodEvening) 65 else 1,
                humidityPct = 55,
                cloudCoverPct = 20,
                pressureHpa = 1016.0
            )
        }
        val twoDayCtx = AssistantContext(
            cityName = "Berlin",
            currentTempC = 16.0,
            feelsLikeC = 16.0,
            weatherType = WeatherType.RAINY,
            windKmh = 10.0,
            uvIndex = 3.0,
            selectedSport = SportType.RUNNING,
            hourlyPoints = twoDayHours,
            utcOffsetSeconds = offsetSec
        )

        val sportAns = AssistantResponseEngine.answer("Wann kann ich heute laufen gehen?", twoDayCtx, nowMillis = nowAfternoon)
        // Must pick 20:00..23:00 (not past 06:00..11:00)
        assertTrue("Expected evening future window 20:00, got: ${sportAns.body}", sportAns.body.contains("20:00"))
        assertFalse("Must not suggest past 06:00 window", sportAns.body.contains("06:00"))

        val tomorrowAns = AssistantResponseEngine.answer("Wie wird das Wetter morgen?", twoDayCtx, nowMillis = nowAfternoon)
        assertTrue("Expected tomorrow temp 29 °C, got: ${tomorrowAns.body}", tomorrowAns.body.contains("29–29 °C"))
    }

    @Test
    fun parseIntent_extractsEssenAndMultiNachDestinationWithoutFalsePositiveOnDemEssen() {
        val routeToEssen = GermanFreeTextParser.parseIntent("Wann muss ich für meine Route nach Essen losfahren?")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, routeToEssen.category)
        assertEquals("Essen", routeToEssen.routeDestination)

        val dortmundToEssen = GermanFreeTextParser.parseIntent("Route von Dortmund nach Essen")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, dortmundToEssen.category)
        assertEquals("Dortmund", dortmundToEssen.routeOrigin)
        assertEquals("Essen", dortmundToEssen.routeDestination)

        val afterMeal = GermanFreeTextParser.parseIntent("Geht es nach dem Essen?")
        assertNull(afterMeal.routeDestination)

        val afterRainToPotsdam = GermanFreeTextParser.parseIntent("Fahre ich nach dem Regen nach Potsdam?")
        assertEquals("Potsdam", afterRainToPotsdam.routeDestination)
    }
}
