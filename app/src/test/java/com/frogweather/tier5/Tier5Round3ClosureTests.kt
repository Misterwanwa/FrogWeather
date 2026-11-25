package com.frogweather.tier5

import com.frogweather.WeatherType
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportType
import com.frogweather.assistant.AssistantContext
import com.frogweather.assistant.AssistantResponseEngine
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.calendar.EventRescheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 5 Round 3 Closure Regression Tests covering Challenger 1 R3 cross-feature finding
 * (`GermanFreeTextParser` x `SportType`/`SportAdvisor`) and `EventRescheduler` Teams-Besprechung polish.
 */
class Tier5Round3ClosureTests {

    private val oneHour = 3_600_000L
    private val baseEpoch = 1_783_209_600_000L // 2026-07-05T00:00:00Z

    private fun sampleHours(): List<HourlyWeatherPoint> = (0 until 24).map { idx ->
        HourlyWeatherPoint(
            epochMillis = baseEpoch + idx * oneHour,
            tempC = 18.0 + (idx % 5),
            precipMm = if (idx in 6..7) 5.0 else 0.0,
            precipProb = if (idx in 6..7) 85 else 5,
            windKmh = 9.0,
            gustKmh = 14.0,
            uvIndex = 3.0,
            capeJkg = 25.0,
            weatherCode = if (idx in 6..7) 63 else 1,
            humidityPct = 55,
            cloudCoverPct = 20,
            pressureHpa = 1016.0
        )
    }

    @Test
    fun t5_r3_germanFreeTextParser_bergCitiesDoNotHijackSportOrWeatherQueries() {
        val footballNuremberg = GermanFreeTextParser.parseIntent("Wann ist heute Fußball in Nürnberg?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, footballNuremberg.category)
        assertEquals(SportType.TEAM_SPORT, footballNuremberg.sportHint)

        val swimmingHeidelberg = GermanFreeTextParser.parseIntent("Schwimmen in Heidelberg heute?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, swimmingHeidelberg.category)
        assertEquals(SportType.SWIMMING, swimmingHeidelberg.sportHint)

        val winterBamberg = GermanFreeTextParser.parseIntent("Wintersport in Bamberg?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, winterBamberg.category)
        assertEquals(SportType.WINTER_SPORT, winterBamberg.sportHint)

        val weatherNuremberg = GermanFreeTextParser.parseIntent("Wie wird das Wetter in Nürnberg von 14 bis 18 Uhr?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherNuremberg.category)
        assertNull(weatherNuremberg.sportHint)

        val weatherWuerzburg = GermanFreeTextParser.parseIntent("Wie wird das Wetter in Würzburg heute?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherWuerzburg.category)
        assertNull(weatherWuerzburg.sportHint)

        val weatherUebergang = GermanFreeTextParser.parseIntent("Wie wird das Wetter am Übergang zum Abend?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherUebergang.category)
        assertNull(weatherUebergang.sportHint)

        val weatherWiesbaden = GermanFreeTextParser.parseIntent("Wie wird das Wetter in Wiesbaden?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherWiesbaden.category)
        assertNull(weatherWiesbaden.sportHint)
    }

    @Test
    fun t5_r3_germanFreeTextParser_riskierenDoesNotTriggerSkiWinterSport() {
        val parsed = GermanFreeTextParser.parseIntent("Wie riskieren wir keinen Regen?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, parsed.category)
        assertNull(parsed.sportHint)
    }

    @Test
    fun t5_r3_germanFreeTextParser_positiveBergAndSkiFormsAndExplicitSportPrecedence() {
        val mountainHike = GermanFreeTextParser.parseIntent("Kann ich morgen in den Bergen wandern?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, mountainHike.category)
        assertEquals(SportType.HIKING, mountainHike.sportHint)

        val bergtour = GermanFreeTextParser.parseIntent("Wann passt eine Bergtour am Wochenende?")
        assertEquals(SportType.HIKING, bergtour.sportHint)

        val skiing = GermanFreeTextParser.parseIntent("Wann ist gutes Wetter zum Skifahren?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, skiing.category)
        assertEquals(SportType.WINTER_SPORT, skiing.sportHint)

        val skiUrlaub = GermanFreeTextParser.parseIntent("Wann lohnt sich der Skiurlaub?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, skiUrlaub.category)
        assertEquals(SportType.WINTER_SPORT, skiUrlaub.sportHint)

        // Explicit sport keyword must take precedence over terrain hint ("alpen"/"berg")
        val alpineSkiing = GermanFreeTextParser.parseIntent("Wann kann ich in den Alpen Skifahren?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, alpineSkiing.category)
        assertEquals(SportType.WINTER_SPORT, alpineSkiing.sportHint)
    }

    @Test
    fun t5_r3_assistantResponseEngine_nurembergFootballAndRiskierenUseCorrectSportAndCategory() {
        val ctx = AssistantContext(
            cityName = "Nürnberg",
            currentTempC = 20.0,
            feelsLikeC = 20.0,
            weatherType = WeatherType.SUNNY,
            windKmh = 9.0,
            uvIndex = 3.0,
            selectedSport = SportType.RUNNING,
            hourlyPoints = sampleHours()
        )

        val footballAnswer = AssistantResponseEngine.answer(
            query = "Wann ist heute Fußball in Nürnberg?",
            context = ctx,
            nowMillis = baseEpoch
        )
        assertEquals(QueryIntentCategory.SPORT_WINDOW, footballAnswer.intent.category)
        assertEquals(SportType.TEAM_SPORT, footballAnswer.intent.sportHint)
        assertEquals("Sportfenster (Fußball/Teamsport)", footballAnswer.title)

        val riskierenAnswer = AssistantResponseEngine.answer(
            query = "Wie riskieren wir keinen Regen?",
            context = ctx,
            nowMillis = baseEpoch
        )
        assertEquals(QueryIntentCategory.WEATHER_QUERY, riskierenAnswer.intent.category)
        assertNull(riskierenAnswer.intent.sportHint)
        assertEquals("Wetter für Nürnberg", riskierenAnswer.title)
    }

    @Test
    fun t5_r3_eventRescheduler_virtualTeamsBesprechungIsExcludedWhileTeamsportStaysSensitive() {
        assertFalse(
            EventRescheduler.classifySensitivity(
                title = "Fußball Teams-Besprechung",
                description = "",
                location = "Stadtpark"
            ).isWeatherSensitive
        )
        assertTrue(
            EventRescheduler.classifySensitivity(
                title = "Teamsport-Turnier draußen",
                description = "",
                location = "Sportplatz"
            ).isWeatherSensitive
        )
    }
}
