package com.frogweather.phase4

import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportType
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.CalendarRepository
import com.frogweather.calendar.EventRescheduler
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

class CalendarAndReschedulerTest {

    private val baseEpoch = 1_783_209_600_000L // 2026-07-05T00:00:00Z
    private val oneHour = 3_600_000L

    private fun hourPoint(
        offsetHours: Int,
        tempC: Double = 19.0,
        precipMm: Double = 0.0,
        precipProb: Int = 5,
        windKmh: Double = 8.0,
        gustKmh: Double = 14.0,
        uvIndex: Double = 2.5,
        capeJkg: Double = 40.0,
        weatherCode: Int = 1
    ): HourlyWeatherPoint = HourlyWeatherPoint(
        epochMillis = baseEpoch + offsetHours * oneHour,
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
        pressureHpa = 1016.0
    )

    @Test
    fun handleQueryResult_deniedPermissionReturnsEmptyGracefully() {
        val res = CalendarRepository.handleQueryResult(
            hasReadPermission = false,
            queriedEvents = emptyList()
        )
        assertFalse(res.permissionGranted)
        assertTrue(res.events.isEmpty())
        assertFalse(res.errorMessage.isNullOrBlank())
    }

    @Test
    fun resolveEventLocation_missingOrVirtualFallsBackToActiveCity() {
        val missingLocEvent = CalendarEvent(
            id = 1L,
            title = "Joggen",
            description = "",
            location = null,
            startMillis = baseEpoch,
            endMillis = baseEpoch + oneHour
        )
        val virtualLocEvent = missingLocEvent.copy(id = 2L, location = "Zoom")
        val realLocEvent = missingLocEvent.copy(id = 3L, location = "Englischer Garten")

        val resMissing = CalendarRepository.resolveEventLocation(missingLocEvent, "München", 48.1351, 11.5820)
        val resVirtual = CalendarRepository.resolveEventLocation(virtualLocEvent, "München", 48.1351, 11.5820)
        val resReal = CalendarRepository.resolveEventLocation(realLocEvent, "München", 48.1351, 11.5820)

        assertTrue(resMissing.usedFallback)
        assertEquals("München", resMissing.cityName)
        assertTrue(resVirtual.usedFallback)
        assertFalse(resReal.usedFallback)
        assertEquals("Englischer Garten", resReal.cityName)
    }

    @Test
    fun normalizeInstanceTimes_convertsUtcMidnightAllDayToLocalDayBoundaries() {
        val berlinZone = ZoneId.of("Europe/Berlin")
        val utcMidnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcEndMidnight = LocalDate.of(2026, 7, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val (localStart, localEnd) = CalendarRepository.normalizeInstanceTimes(
            beginMillis = utcMidnight,
            endMillis = utcEndMidnight,
            allDay = true,
            localZone = berlinZone
        )

        val expectedStart = LocalDate.of(2026, 7, 5).atStartOfDay(berlinZone).toInstant().toEpochMilli()
        val expectedEnd = LocalDate.of(2026, 7, 6).atStartOfDay(berlinZone).toInstant().toEpochMilli()
        assertEquals(expectedStart, localStart)
        assertEquals(expectedEnd, localEnd)
        assertEquals(LocalDate.of(2026, 7, 5), Instant.ofEpochMilli(localStart).atZone(berlinZone).toLocalDate())
    }

    @Test
    fun parseInstanceRow_preservesAccountMetadataAndFallbacksBlankTitle() {
        val evt = CalendarRepository.parseInstanceRow(
            eventId = 42L,
            title = "  ",
            description = "Draußen grillen",
            location = "Stadtpark",
            beginMillis = baseEpoch,
            endMillis = baseEpoch + 2 * oneHour,
            allDayInt = 0,
            calendarDisplayName = "Privat",
            accountName = "user@outlook.com",
            accountType = "com.microsoft.office365"
        )
        assertEquals("(Ohne Titel)", evt.title)
        assertEquals("com.microsoft.office365", evt.accountType)
        assertFalse(evt.allDay)
    }

    @Test
    fun classifySensitivity_recognizesGermanEnglishOutdoorAndRejectsIndoorAndParkhaus() {
        val outdoor = listOf(
            Triple("Laufrunde im Park", "10km Joggen draußen", "Tiergarten"),
            Triple("Familien-Picknick", "Wiese", "Schlosspark"),
            Triple("Hiking Trip", "Alpen", "Tegernsee"),
            Triple("Grillen", "", ""),
            Triple("Radtour", "Rennrad", "Elbe")
        )
        outdoor.forEach { (title, desc, loc) ->
            assertTrue("Expected weather-sensitive: $title", EventRescheduler.classifySensitivity(title, desc, loc).isWeatherSensitive)
        }

        val indoor = listOf(
            Triple("Sprint Planning Call", "Zoom-Meeting im Büro", "Konferenzraum B / Online"),
            Triple("Architektur-Meeting", "Video-Call", "Office"),
            Triple("Zahnarzt", "Routine", "Praxis"),
            Triple("Besprechung", "Bitte im Parkhaus parken", "Parkplatz Nord")
        )
        indoor.forEach { (title, desc, loc) ->
            assertFalse("Expected NOT weather-sensitive: $title", EventRescheduler.classifySensitivity(title, desc, loc).isWeatherSensitive)
        }
    }

    @Test
    fun suggestReschedules_flagsRainConflictAndSelectsDrySlotBySport() {
        val hours = (0 until 24).map { idx ->
            when (idx) {
                in 2..3 -> hourPoint(idx, precipMm = 8.0, precipProb = 90, windKmh = 28.0, weatherCode = 65)
                in 6..12 -> hourPoint(idx, tempC = 20.0, precipMm = 0.0, precipProb = 0, windKmh = 6.0, weatherCode = 0)
                else -> hourPoint(idx, tempC = 14.0, precipMm = 3.5, precipProb = 75, windKmh = 36.0, weatherCode = 63)
            }
        }
        val event = CalendarEvent(
            id = 10L,
            title = "Feierabend-Wanderung",
            description = "Isar-Tour",
            location = "München",
            startMillis = baseEpoch + 2 * oneHour,
            endMillis = baseEpoch + 4 * oneHour
        )
        val suggestions = EventRescheduler.suggestReschedules(listOf(event), hours, SportType.HIKING, baseEpoch)

        assertEquals(1, suggestions.size)
        val s = suggestions.single()
        assertTrue(s.requiresReschedule)
        assertTrue(s.hasBetterSlot)
        assertNotNull(s.suggestedStartMillis)
        assertTrue(s.suggestedStartMillis!! in (baseEpoch + 6 * oneHour)..(baseEpoch + 11 * oneHour))
    }

    @Test
    fun suggestReschedules_excludesPastEventsAndHandlesAllStormyAndInvertedTimes() {
        val stormyHours = (0 until 24).map { idx ->
            hourPoint(idx, precipMm = 15.0, precipProb = 95, windKmh = 55.0, gustKmh = 85.0, capeJkg = 1800.0, weatherCode = 95)
        }
        val pastEvent = CalendarEvent(
            id = 1L,
            title = "Fußballspiel",
            description = "",
            location = "Sportplatz",
            startMillis = baseEpoch - 4 * oneHour,
            endMillis = baseEpoch - 1 * oneHour
        )
        val futureEvent = CalendarEvent(
            id = 2L,
            title = "Grillen im Park",
            description = "BBQ",
            location = "Park",
            startMillis = baseEpoch + 2 * oneHour,
            endMillis = baseEpoch + 4 * oneHour
        )
        val invertedEvent = CalendarEvent(
            id = 3L,
            title = "Joggen",
            description = "",
            location = "Park",
            startMillis = baseEpoch + 5 * oneHour,
            endMillis = baseEpoch + 2 * oneHour
        )

        val res = EventRescheduler.suggestReschedules(
            events = listOf(pastEvent, futureEvent, invertedEvent),
            hourlyPoints = stormyHours,
            sport = SportType.WALKING,
            nowMillis = baseEpoch
        )

        assertTrue(res.none { it.eventId == 1L })
        val futureSug = res.first { it.eventId == 2L }
        assertFalse(futureSug.hasBetterSlot)
        assertNull(futureSug.suggestedStartMillis)
        assertTrue(futureSug.message.isNotBlank())
    }

    @Test
    fun normalizeInstanceTimes_handlesSpringDstTransitionWithoutOneHourOverflow() {
        val berlinZone = ZoneId.of("Europe/Berlin")
        val springDstUtcStart = LocalDate.of(2026, 3, 29).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val springDstUtcEnd = LocalDate.of(2026, 3, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val (localStart, localEnd) = CalendarRepository.normalizeInstanceTimes(
            beginMillis = springDstUtcStart,
            endMillis = springDstUtcEnd,
            allDay = true,
            localZone = berlinZone
        )

        val expectedStart = LocalDate.of(2026, 3, 29).atStartOfDay(berlinZone).toInstant().toEpochMilli()
        val expectedEnd = LocalDate.of(2026, 3, 30).atStartOfDay(berlinZone).toInstant().toEpochMilli()
        assertEquals(expectedStart, localStart)
        assertEquals(expectedEnd, localEnd)
        assertEquals(23 * oneHour, localEnd - localStart)
    }

    @Test
    fun resolveEventLocation_treatsVirtualUrlsAndMeetingNamesAsFallback() {
        val base = CalendarEvent(
            id = 50L,
            title = "Sync",
            description = "",
            location = null,
            startMillis = baseEpoch,
            endMillis = baseEpoch + oneHour
        )
        listOf(
            "https://zoom.us/j/123456",
            "Zoom Meeting",
            "https://meet.google.com/abc-defg-hij",
            "Teams Video Call",
            "Raum 4 (https://custom-jitsi.example.org/room)"
        ).forEach { virtualLoc ->
            val res = CalendarRepository.resolveEventLocation(base.copy(location = virtualLoc), "Berlin", 52.52, 13.405)
            assertTrue("Expected fallback for '$virtualLoc'", res.usedFallback)
            assertEquals("Berlin", res.cityName)
        }

        val real = CalendarRepository.resolveEventLocation(base.copy(location = "Teamsport Arena"), "Berlin", 52.52, 13.405)
        assertFalse(real.usedFallback)
        assertEquals("Teamsport Arena", real.cityName)
    }

    @Test
    fun classifySensitivity_physicalIndoorOverridesSportUnlessExplicitOutdoor() {
        assertFalse(EventRescheduler.classifySensitivity("Laufrunde im Fitnessstudio", "", "Gym").isWeatherSensitive)
        assertFalse(EventRescheduler.classifySensitivity("Fußball Indoor", "", "Sporthalle").isWeatherSensitive)
        assertFalse(EventRescheduler.classifySensitivity("Training in der Sporthalle", "", "").isWeatherSensitive)
        assertTrue(EventRescheduler.classifySensitivity("Outdoor Training vor dem Fitnessstudio", "", "").isWeatherSensitive)
    }

    @Test
    fun suggestReschedules_prefersDaytimeSlotAndClampsPartialFitWindowEnd() {
        val localZone = ZoneId.systemDefault()
        val dayStart = LocalDateTime.of(2026, 7, 10, 0, 0).atZone(localZone).toInstant().toEpochMilli()

        // 01..04 local overnight is clean (4h), 10..12 local daytime is clean (3h), 15..16 local event is rainy
        val daytimePrefHours = (0 until 24).map { h ->
            val clean = (h in 1..4) || (h in 10..12)
            HourlyWeatherPoint(
                epochMillis = dayStart + h * oneHour,
                tempC = 19.0,
                precipMm = if (clean) 0.0 else 8.0,
                precipProb = if (clean) 5 else 90,
                windKmh = if (clean) 8.0 else 35.0,
                gustKmh = if (clean) 12.0 else 50.0,
                uvIndex = 2.5,
                capeJkg = 40.0,
                weatherCode = if (clean) 1 else 65,
                humidityPct = 55,
                cloudCoverPct = 20,
                pressureHpa = 1016.0
            )
        }
        val daytimeEvent = CalendarEvent(
            id = 201L,
            title = "Picknick im Park",
            description = "",
            location = "Stadtpark",
            startMillis = dayStart + 15 * oneHour,
            endMillis = dayStart + 17 * oneHour
        )
        val daytimeSug = EventRescheduler.suggestReschedules(listOf(daytimeEvent), daytimePrefHours, SportType.WALKING, dayStart).single()
        assertTrue(daytimeSug.hasBetterSlot)
        val pickedHour = Instant.ofEpochMilli(daytimeSug.suggestedStartMillis!!).atZone(localZone).hour
        assertTrue("Expected daytime slot in 07..21, got $pickedHour", pickedHour in 7..21)

        // Partial fit: 3-hour event, only 1 clean daytime hour at 11:00 (12:00..23:00 all stormy)
        val partialHours = (0 until 24).map { h ->
            val clean = h == 11
            HourlyWeatherPoint(
                epochMillis = dayStart + h * oneHour,
                tempC = 19.0,
                precipMm = if (clean) 0.0 else 10.0,
                precipProb = if (clean) 5 else 95,
                windKmh = if (clean) 8.0 else 48.0,
                gustKmh = if (clean) 12.0 else 70.0,
                uvIndex = 2.5,
                capeJkg = 40.0,
                weatherCode = if (clean) 1 else 65,
                humidityPct = 55,
                cloudCoverPct = 20,
                pressureHpa = 1016.0
            )
        }
        val threeHourEvent = CalendarEvent(
            id = 202L,
            title = "Wanderung",
            description = "",
            location = "Berg",
            startMillis = dayStart + 14 * oneHour,
            endMillis = dayStart + 17 * oneHour
        )
        val partialSug = EventRescheduler.suggestReschedules(listOf(threeHourEvent), partialHours, SportType.HIKING, dayStart).single()
        assertTrue(partialSug.hasBetterSlot)
        assertEquals(dayStart + 11 * oneHour, partialSug.suggestedStartMillis)
        assertEquals(dayStart + 12 * oneHour, partialSug.suggestedEndMillis)

        // Overnight fallback ONLY when zero daytime clean hours exist
        val overnightOnlyHours = (0 until 24).map { h ->
            val clean = h in 2..3
            HourlyWeatherPoint(
                epochMillis = dayStart + h * oneHour,
                tempC = 19.0,
                precipMm = if (clean) 0.0 else 10.0,
                precipProb = if (clean) 5 else 95,
                windKmh = if (clean) 8.0 else 48.0,
                gustKmh = if (clean) 12.0 else 70.0,
                uvIndex = 2.5,
                capeJkg = 40.0,
                weatherCode = if (clean) 1 else 65,
                humidityPct = 55,
                cloudCoverPct = 20,
                pressureHpa = 1016.0
            )
        }
        val overnightSug = EventRescheduler.suggestReschedules(listOf(daytimeEvent), overnightOnlyHours, SportType.WALKING, dayStart).single()
        assertTrue(overnightSug.hasBetterSlot)
        assertTrue(overnightSug.message.contains("Nacht", ignoreCase = true))
    }
}
