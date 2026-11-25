package com.frogweather.phase4

import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.briefing.BriefingComposer
import com.frogweather.briefing.BriefingScheduleRepository
import com.frogweather.briefing.BriefingScheduler
import com.frogweather.briefing.BriefingTimeCalculator
import com.frogweather.briefing.BriefingType
import com.frogweather.briefing.ClothingAdvisor
import com.frogweather.briefing.ThermalTier
import com.frogweather.phase1.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ClothingAndBriefingTest {

    @Test
    fun clothingAdvisor_coversAllSixThermalTiersAndProtections() {
        val freezing = ClothingAdvisor.recommendClothing(-5.0, -9.0, 20, 15.0, 1.0)
        val cold = ClothingAdvisor.recommendClothing(4.0, 2.0, 80, 48.0, 1.0)
        val cool = ClothingAdvisor.recommendClothing(11.0, 11.0, 35, 18.0, 2.0)
        val mild = ClothingAdvisor.recommendClothing(18.0, 18.0, 10, 10.0, 4.0)
        val warm = ClothingAdvisor.recommendClothing(24.0, 24.0, 5, 8.0, 6.0)
        val hot = ClothingAdvisor.recommendClothing(31.0, 32.0, 0, 5.0, 9.0)

        assertEquals(ThermalTier.FREEZING, freezing.thermalTier)
        assertTrue(freezing.needsColdProtection)
        assertEquals(ThermalTier.COLD, cold.thermalTier)
        assertTrue(cold.needsRainGear)
        assertTrue(cold.needsWindProtection)
        assertFalse(cold.rainGear.contains("Regenschirm")) // >= 45 km/h warns against umbrella
        assertEquals(ThermalTier.COOL, cool.thermalTier)
        assertEquals(ThermalTier.MILD, mild.thermalTier)
        assertEquals(ThermalTier.WARM, warm.thermalTier)
        assertEquals(ThermalTier.HOT, hot.thermalTier)
        assertTrue(hot.needsSunProtection)
        assertTrue(hot.sunOrColdProtection!!.contains("LSF 50+"))
    }

    @Test
    fun clothingAdvisor_omitsUmbrellaForModerateRainUnderStrongWind() {
        val rec = ClothingAdvisor.recommendClothing(
            tempC = 12.0,
            feelsLikeC = 10.0,
            precipProb = 35,
            windKmh = 50.0,
            uvIndex = 2.0
        )
        assertTrue(rec.needsRainGear)
        assertTrue(rec.rainGear.none { it.contains("Taschenschirm", ignoreCase = true) })
        assertTrue(rec.rainGear.any { it.contains("Kapuzenjacke", ignoreCase = true) })
        assertTrue(rec.windAdvice!!.contains("Schirm meiden", ignoreCase = true))
    }

    @Test
    fun clothingAdvisor_sanitizesNaNAndNegativeInputs() {
        val rec = ClothingAdvisor.recommendClothing(Double.NaN, Double.NaN, 150, -10.0, -3.0)
        assertTrue(rec.effectiveTempC.isFinite())
        assertTrue(rec.summary.isNotBlank())
        assertTrue(rec.layers.isNotEmpty())
    }

    @Test
    fun briefingComposer_guaranteesAllFiveComponentsInNotificationAndCardEvenWithMissingOptionals() {
        val clothing = ClothingAdvisor.recommendClothing(14.0, 13.0, 25, 12.0, 3.0)
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = " ",
            dailySummary = " ",
            clothing = clothing,
            bestSportWindow = null,
            calendarWarnings = emptyList(),
            routeCheck = null,
            briefingType = BriefingType.EVENING
        )

        assertEquals(5, briefing.card.items.size)
        briefing.card.items.forEach { item ->
            assertTrue("Card item ${item.key} must not be blank", item.text.isNotBlank())
        }
        assertTrue(briefing.dailyWeatherText.isNotBlank())
        assertTrue(briefing.clothingText.isNotBlank())
        assertTrue(briefing.sportWindowText.isNotBlank())
        assertTrue(briefing.calendarWarningsText.isNotBlank())
        assertTrue(briefing.routeCheckText.isNotBlank())

        val body = briefing.toNotificationBody()
        assertTrue(body.contains(briefing.dailyWeatherText))
        assertTrue(body.contains(briefing.clothingText))
        assertTrue(body.contains(briefing.sportWindowText))
        assertTrue(body.contains(briefing.calendarWarningsText))
        assertTrue(body.contains(briefing.routeCheckText))
    }

    @Test
    fun briefingComposer_formatsBestSportWindowWithOffset() {
        val base = 1_783_209_600_000L
        val hours = (0 until 6).map { h ->
            HourlyWeatherPoint(
                epochMillis = base + h * 3_600_000L,
                tempC = 19.0,
                precipMm = 0.0,
                precipProb = 0,
                windKmh = 8.0,
                gustKmh = 12.0,
                uvIndex = 3.0,
                capeJkg = 30.0,
                weatherCode = 0,
                humidityPct = 50,
                cloudCoverPct = 10,
                pressureHpa = 1018.0
            )
        }
        val win = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        val clothing = ClothingAdvisor.recommendClothing(19.0, 19.0, 0, 8.0, 3.0)
        val composed = BriefingComposer.composeBriefing(
            dateLabel = "Sonntag",
            dailySummary = "19 °C sonnig",
            clothing = clothing,
            bestSportWindow = win,
            calendarWarnings = listOf("15:00 Picknick: Gewitter"),
            routeCheck = "Freie Fahrt",
            utcOffsetSeconds = 7200
        )

        assertTrue(composed.sportWindowText.contains("Laufen/Joggen"))
        assertTrue(composed.calendarWarningsText.contains("Picknick"))
        assertTrue(composed.routeCheckText.contains("Freie Fahrt"))
    }

    @Test
    fun briefingScheduler_defaultsPersistsClampsAndPlansExactFallback() {
        val store = InMemoryKeyValueStore()
        val s1 = BriefingScheduler(store)
        assertEquals(7, s1.getMorningSchedule().hour)
        assertEquals(0, s1.getMorningSchedule().minute)
        assertTrue(s1.getMorningSchedule().enabled)
        assertEquals(20, s1.getEveningCheckSchedule().hour)
        assertEquals(30, s1.getEveningCheckSchedule().minute)

        s1.setMorningSchedule(hour = 99, minute = -5, enabled = false)
        val s2 = BriefingScheduler(store)
        assertEquals(23, s2.getMorningSchedule().hour)
        assertEquals(0, s2.getMorningSchedule().minute)
        assertFalse(s2.getMorningSchedule().enabled)

        val exact = s2.planScheduleStrategy(true)
        val fallback = s2.planScheduleStrategy(false)
        assertTrue(exact.usesExactAlarm)
        assertFalse(fallback.usesExactAlarm)
        assertTrue(fallback.usesInexactOrWorkManagerFallback)

        val repo = BriefingScheduleRepository(store)
        repo.setEveningSchedule(true, 19, 45)
        assertEquals(19, repo.getConfig().eveningHour)
        assertEquals(45, repo.getConfig().eveningMinute)
    }

    @Test
    fun briefingTimeCalculator_rollsOverToNextDayWhenTimePassed() {
        val zone = ZoneId.of("UTC")
        val beforeMorning = LocalDateTime.of(2026, 7, 5, 6, 30).atZone(zone).toInstant().toEpochMilli()
        val afterMorning = LocalDateTime.of(2026, 7, 5, 7, 30).atZone(zone).toInstant().toEpochMilli()

        val sameDay = BriefingTimeCalculator.computeNextTriggerMillis(7, 0, beforeMorning, zone)
        val nextDay = BriefingTimeCalculator.computeNextTriggerMillis(7, 0, afterMorning, zone)

        assertEquals(LocalDateTime.of(2026, 7, 5, 7, 0).atZone(zone).toInstant().toEpochMilli(), sameDay)
        assertEquals(LocalDateTime.of(2026, 7, 6, 7, 0).atZone(zone).toInstant().toEpochMilli(), nextDay)
    }
}
