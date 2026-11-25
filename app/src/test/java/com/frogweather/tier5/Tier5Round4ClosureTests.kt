package com.frogweather.tier5

import com.frogweather.WeatherType
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportType
import com.frogweather.assistant.AssistantContext
import com.frogweather.assistant.AssistantResponseEngine
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier 5 Round 4 Closure Regression Tests covering Challenger R4 Part B (`GermanFreeTextParser`
 * hyphenated Baden-* place-name boundary) and Reviewer 1 Unicode-letter boundary safety (`-berg`
 * with umlauts/eszett) while preserving `"baden"`, positive `berg*`, and hyphenated `"Ski-Urlaub"`.
 */
class Tier5Round4ClosureTests {

    private val oneHour = 3_600_000L
    private val baseEpoch = 1_783_209_600_000L // 2026-07-05T00:00:00Z

    private fun sampleHours(): List<HourlyWeatherPoint> = (0 until 24).map { idx ->
        HourlyWeatherPoint(
            epochMillis = baseEpoch + idx * oneHour,
            tempC = 14.0 + (idx % 4), // Deliberately < 18°C so SWIMMING vs WALKING diverges clearly
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
    fun t5_r4_badenHyphenatedPlaceNamesDoNotTriggerSwimmingOrHijackWalking() {
        val walkBadenBaden = GermanFreeTextParser.parseIntent(
            "Wann kann ich heute in Baden-Baden spazieren gehen?"
        )
        assertEquals(QueryIntentCategory.SPORT_WINDOW, walkBadenBaden.category)
        assertEquals(SportType.WALKING, walkBadenBaden.sportHint)

        val walkBadenWuerttemberg = GermanFreeTextParser.parseIntent(
            "Spaziergang in Baden-Württemberg?"
        )
        assertEquals(QueryIntentCategory.SPORT_WINDOW, walkBadenWuerttemberg.category)
        assertEquals(SportType.WALKING, walkBadenWuerttemberg.sportHint)

        val sportBadenWuerttemberg = GermanFreeTextParser.parseIntent(
            "Wann ist heute Sport in Baden-Württemberg?"
        )
        assertEquals(QueryIntentCategory.SPORT_WINDOW, sportBadenWuerttemberg.category)
        assertNull(sportBadenWuerttemberg.sportHint)

        val nonSportBadenBaden = GermanFreeTextParser.parseIntent("Morgen in Baden-Baden?")
        assertNotEquals(QueryIntentCategory.SPORT_WINDOW, nonSportBadenBaden.category)
        assertNull(nonSportBadenBaden.sportHint)

        val weatherBadenBaden = GermanFreeTextParser.parseIntent(
            "Wie wird das Wetter in Baden-Baden?"
        )
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherBadenBaden.category)
        assertNull(weatherBadenBaden.sportHint)

        val ctx = AssistantContext(
            cityName = "Baden-Baden",
            currentTempC = 15.0,
            feelsLikeC = 15.0,
            weatherType = WeatherType.CLOUDY,
            windKmh = 9.0,
            uvIndex = 3.0,
            selectedSport = SportType.RUNNING,
            hourlyPoints = sampleHours()
        )
        val walkAnswer = AssistantResponseEngine.answer(
            query = "Wann kann ich heute in Baden-Baden spazieren gehen?",
            context = ctx,
            nowMillis = baseEpoch
        )
        assertEquals(QueryIntentCategory.SPORT_WINDOW, walkAnswer.intent.category)
        assertEquals(SportType.WALKING, walkAnswer.intent.sportHint)
        assertEquals("Sportfenster (Spazieren)", walkAnswer.title)
    }

    @Test
    fun t5_r4_positiveBadenSwimmingStillDetected() {
        val canSwim = GermanFreeTextParser.parseIntent("Kann ich heute baden gehen?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, canSwim.category)
        assertEquals(SportType.SWIMMING, canSwim.sportHint)

        val whenSwim = GermanFreeTextParser.parseIntent("Wann kann ich baden?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, whenSwim.category)
        assertEquals(SportType.SWIMMING, whenSwim.sportHint)
    }

    @Test
    fun t5_r4_umlautAndEszettBergPlaceNamesDoNotTriggerHiking_whilePositiveBergAndSkiUrlaubWork() {
        val weatherWeissberg = GermanFreeTextParser.parseIntent(
            "Wie wird das Wetter in Weißberg?"
        )
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherWeissberg.category)
        assertNull(weatherWeissberg.sportHint)

        val footballWeissberg = GermanFreeTextParser.parseIntent("Fußball in Weißberg?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, footballWeissberg.category)
        assertEquals(SportType.TEAM_SPORT, footballWeissberg.sportHint)

        val weatherOberg = GermanFreeTextParser.parseIntent("Wie wird das Wetter in Öberg?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, weatherOberg.category)
        assertNull(weatherOberg.sportHint)

        val nonSportOberg = GermanFreeTextParser.parseIntent("Morgen in Öberg?")
        assertNotEquals(QueryIntentCategory.SPORT_WINDOW, nonSportOberg.category)
        assertNull(nonSportOberg.sportHint)

        // Positive berg* forms remain intact
        val amBerg = GermanFreeTextParser.parseIntent("Wann ist gutes Wetter am Berg?")
        assertEquals(SportType.HIKING, amBerg.sportHint)
        val inDenBergen = GermanFreeTextParser.parseIntent("Kann man morgen in den Bergen wandern?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, inDenBergen.category)
        assertEquals(SportType.HIKING, inDenBergen.sportHint)
        val bergtour = GermanFreeTextParser.parseIntent("Passt eine Bergtour heute?")
        assertEquals(SportType.HIKING, bergtour.sportHint)

        // Hyphenated Ski-Urlaub and positive ski forms remain intact; false positives remain rejected
        val skiUrlaub = GermanFreeTextParser.parseIntent("Kann man morgen Ski-Urlaub machen?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, skiUrlaub.category)
        assertEquals(SportType.WINTER_SPORT, skiUrlaub.sportHint)

        val skiFahren = GermanFreeTextParser.parseIntent("Kann man morgen ski fahren?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, skiFahren.category)
        assertEquals(SportType.WINTER_SPORT, skiFahren.sportHint)

        val skipQuery = GermanFreeTextParser.parseIntent("Bitte skip dieses Detail heute")
        assertNull(skipQuery.sportHint)
        val skizzeQuery = GermanFreeTextParser.parseIntent("Kannst du eine Skizze vom Wetter zeigen?")
        assertNull(skizzeQuery.sportHint)
        val riskierenQuery = GermanFreeTextParser.parseIntent("Wie riskieren wir keinen Regen?")
        assertNull(riskierenQuery.sportHint)
    }
}
