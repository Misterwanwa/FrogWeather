package com.frogweather.phase2

import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SkipReason
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.analysis.UvLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SportAdvisorTest {

    private fun point(
        hourOffset: Int = 0,
        tempC: Double = 18.0,
        precipMm: Double = 0.0,
        precipProb: Int = 10,
        windKmh: Double = 10.0,
        gustKmh: Double = 15.0,
        uvIndex: Double = 2.0,
        capeJkg: Double = 50.0,
        weatherCode: Int = 1,
        humidityPct: Int = 55,
        cloudCoverPct: Int = 20,
        pressureHpa: Double = 1015.0
    ): HourlyWeatherPoint = HourlyWeatherPoint(
        epochMillis = 1_700_000_000_000L + hourOffset * 3_600_000L,
        tempC = tempC,
        precipMm = precipMm,
        precipProb = precipProb,
        windKmh = windKmh,
        gustKmh = gustKmh,
        uvIndex = uvIndex,
        capeJkg = capeJkg,
        weatherCode = weatherCode,
        humidityPct = humidityPct,
        cloudCoverPct = cloudCoverPct,
        pressureHpa = pressureHpa
    )

    @Test
    fun `SportType enum contains all 8 required sports verbatim`() {
        val expected = listOf(
            "running" to "Laufen/Joggen",
            "cycling" to "Radfahren",
            "hiking" to "Wandern",
            "outdoor_fitness" to "Outdoor-Fitness",
            "team_sport" to "Fußball/Teamsport",
            "swimming" to "Schwimmen/Wassersport",
            "winter_sport" to "Wintersport",
            "walking" to "Spazieren"
        )
        assertEquals(8, SportType.entries.size)
        assertEquals(expected, SportType.entries.map { it.id to it.displayName })
    }

    @Test
    fun `classifyUv maps all WHO tiers and handles negative or NaN values`() {
        assertEquals(UvLevel.LOW, SportAdvisor.classifyUv(-2.0))
        assertEquals(UvLevel.LOW, SportAdvisor.classifyUv(Double.NaN))
        assertEquals(UvLevel.LOW, SportAdvisor.classifyUv(2.9))
        assertEquals(UvLevel.MODERATE, SportAdvisor.classifyUv(3.0))
        assertEquals(UvLevel.HIGH, SportAdvisor.classifyUv(6.5))
        assertEquals(UvLevel.VERY_HIGH, SportAdvisor.classifyUv(9.2))
        assertEquals(UvLevel.EXTREME, SportAdvisor.classifyUv(11.0))
    }

    @Test
    fun `thunderstorm weather code or high CAPE skips all 8 sports`() {
        val stormHour = point(weatherCode = 95, capeJkg = 1800.0)
        for (sport in SportType.entries) {
            val verdict = SportAdvisor.evaluateHoursToSkip(sport, listOf(stormHour))
            assertEquals("Expected skip for $sport", 1, verdict.skipHoursCount)
            assertTrue(
                "Expected THUNDERSTORM reason for $sport",
                SkipReason.THUNDERSTORM in verdict.hoursToSkip.first().reasons
            )
        }
    }

    @Test
    fun `swimming rejects 15C cold water day while accepting 27C calm day`() {
        val coolHour = point(hourOffset = 0, tempC = 15.0)
        val warmHour = point(hourOffset = 1, tempC = 27.0, windKmh = 12.0)
        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.SWIMMING, listOf(coolHour, warmHour))

        assertEquals(1, verdict.skipHoursCount)
        assertEquals(1, verdict.goodHoursCount)
        assertTrue(SkipReason.COLD in verdict.hoursToSkip.first().reasons)
        assertEquals(warmHour.epochMillis, SportAdvisor.findBestSportWindow(SportType.SWIMMING, listOf(coolHour, warmHour))?.startEpochMillis)
    }

    @Test
    fun `winter sport allows cold snowy weather and skips warm rain`() {
        val snowyCold = point(hourOffset = 0, tempC = -5.0, precipMm = 1.2, precipProb = 80, weatherCode = 73)
        val warmRain = point(hourOffset = 1, tempC = 10.0, precipMm = 1.0, precipProb = 70, weatherCode = 61)

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, listOf(snowyCold, warmRain))
        assertEquals(1, verdict.skipHoursCount)
        assertEquals(warmRain.epochMillis, verdict.hoursToSkip.first().hour.epochMillis)
        assertTrue(SkipReason.HEAT in verdict.hoursToSkip.first().reasons)
        assertTrue(SkipReason.RAIN in verdict.hoursToSkip.first().reasons)
    }

    @Test
    fun `cycling skips 35 kmh wind while walking still tolerates it`() {
        val breezy = point(tempC = 17.0, windKmh = 35.0, gustKmh = 42.0)
        val cyclingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, listOf(breezy))
        val walkingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WALKING, listOf(breezy))

        assertEquals(1, cyclingVerdict.skipHoursCount)
        assertTrue(SkipReason.WIND in cyclingVerdict.hoursToSkip.first().reasons)
        assertEquals(0, walkingVerdict.skipHoursCount)
    }

    @Test
    fun `running, hiking, outdoor fitness, and team sport enforce distinct thresholds`() {
        val humidHeat = point(tempC = 27.5, humidityPct = 80)
        assertEquals(1, SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, listOf(humidHeat)).skipHoursCount)

        val lightRainAndCool = point(tempC = 1.0, precipMm = 0.6, precipProb = 65)
        val fitnessVerdict = SportAdvisor.evaluateHoursToSkip(SportType.OUTDOOR_FITNESS, listOf(lightRainAndCool))
        assertEquals(1, fitnessVerdict.skipHoursCount)
        assertTrue(SkipReason.COLD in fitnessVerdict.hoursToSkip.first().reasons)
        assertTrue(SkipReason.RAIN in fitnessVerdict.hoursToSkip.first().reasons)

        val exposedCape = point(tempC = 20.0, capeJkg = 1100.0)
        val hikingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.HIKING, listOf(exposedCape))
        assertEquals(1, hikingVerdict.skipHoursCount)
        assertTrue(SkipReason.THUNDERSTORM in hikingVerdict.hoursToSkip.first().reasons)

        val gustyPitch = point(tempC = 16.0, windKmh = 25.0, gustKmh = 60.0)
        val teamVerdict = SportAdvisor.evaluateHoursToSkip(SportType.TEAM_SPORT, listOf(gustyPitch))
        assertEquals(1, teamVerdict.skipHoursCount)
        assertTrue(SkipReason.WIND in teamVerdict.hoursToSkip.first().reasons)
    }

    @Test
    fun `findBestSportWindow picks best contiguous non-skip window and handles unsorted or empty inputs`() {
        val hours = listOf(
            point(hourOffset = 4, tempC = 18.0, uvIndex = 2.0, windKmh = 8.0),
            point(hourOffset = 0, tempC = 16.0, uvIndex = 1.0, windKmh = 10.0),
            point(hourOffset = 2, tempC = 32.0, uvIndex = 9.0), // skipped
            point(hourOffset = 3, tempC = 17.0, uvIndex = 2.0, windKmh = 8.0),
            point(hourOffset = 5, tempC = 19.0, uvIndex = 2.0, windKmh = 8.0)
        )

        val best = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(best)
        assertEquals(3, best!!.hours.size)
        assertEquals(1_700_000_000_000L + 3 * 3_600_000L, best.startEpochMillis)
        assertFalse(best.hours.any { it.tempC >= 29.0 })

        assertNull(SportAdvisor.findBestSportWindow(SportType.RUNNING, emptyList()))
        assertNull(SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(point(tempC = 35.0))))
    }

    @Test
    fun `winter sport skips freezing drizzle, freezing rain, and violent rain showers at 1C`() {
        for (wmoCode in listOf(57, 67, 82)) {
            val hazardHour = point(
                tempC = 1.0,
                precipMm = 2.0,
                precipProb = 85,
                weatherCode = wmoCode
            )
            val verdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, listOf(hazardHour))
            assertEquals("Expected skip for WMO $wmoCode", 1, verdict.skipHoursCount)
            assertTrue("Expected shouldSkip=true for WMO $wmoCode", verdict.hoursToSkip.first().shouldSkip)
            assertTrue(
                "Expected SkipReason.RAIN for WMO $wmoCode",
                SkipReason.RAIN in verdict.hoursToSkip.first().reasons
            )
        }

        // Light snow at 1.0C (WMO 71) must still be permitted
        val lightSnow = point(tempC = 1.0, precipMm = 1.0, precipProb = 70, weatherCode = 71)
        val snowVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, listOf(lightSnow))
        assertEquals(0, snowVerdict.skipHoursCount)
    }

    private fun utcDayPoint(
        dayMidnightMillis: Long,
        hour: Int,
        tempC: Double = 18.0,
        precipMm: Double = 0.0,
        precipProb: Int = 10,
        windKmh: Double = 10.0,
        gustKmh: Double = 15.0,
        uvIndex: Double = 0.0,
        weatherCode: Int = 1
    ): HourlyWeatherPoint = HourlyWeatherPoint(
        epochMillis = dayMidnightMillis + hour * 3_600_000L,
        tempC = tempC,
        precipMm = precipMm,
        precipProb = precipProb,
        windKmh = windKmh,
        gustKmh = gustKmh,
        uvIndex = uvIndex,
        capeJkg = 40.0,
        weatherCode = weatherCode,
        humidityPct = 55,
        cloudCoverPct = 20,
        pressureHpa = 1015.0
    )

    @Test
    fun `findBestSportWindow prefers longer daytime window over shorter midnight block`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = (0..23).map { h ->
            val rainy = h in 6..10
            utcDayPoint(
                dayMidnightMillis = midnight,
                hour = h,
                precipMm = if (rainy) 3.5 else 0.0,
                precipProb = if (rainy) 85 else 10,
                uvIndex = if (h in 8..18) 3.5 else 0.0,
                weatherCode = if (rainy) 63 else 1
            )
        }

        val best = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(best)
        assertEquals(midnight + 11 * 3_600_000L, best!!.startEpochMillis)
        assertEquals(13, best.hours.size)
    }

    @Test
    fun `findBestSportWindow prefers longer morning window with moderate UV over short after-dark block`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = (0..23).map { h ->
            val uv = when (h) {
                in 7..9 -> 4.0
                10 -> 6.4
                11 -> 7.8
                in 12..14 -> 8.8
                else -> 0.0
            }
            val storm = h in 15..18
            utcDayPoint(
                dayMidnightMillis = midnight,
                hour = h,
                tempC = if (h in 10..15) 23.0 else 16.0,
                precipMm = if (storm) 8.0 else 0.0,
                precipProb = if (storm) 95 else 10,
                windKmh = if (storm) 45.0 else 10.0,
                gustKmh = if (storm) 70.0 else 15.0,
                uvIndex = uv,
                weatherCode = if (storm) 95 else 1
            )
        }

        val best = SportAdvisor.findBestSportWindow(SportType.HIKING, hours)
        assertNotNull(best)
        assertEquals(midnight, best!!.startEpochMillis)
        assertEquals(midnight + 12 * 3_600_000L, best.endEpochMillis)
        assertEquals(12, best.hours.size)
    }

    @Test
    fun `findBestSportWindow breaks equal-length tie toward daytime window over midnight`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = (0..23).map { h ->
            val dry = h in 0..5 || h in 12..17
            utcDayPoint(
                dayMidnightMillis = midnight,
                hour = h,
                precipMm = if (dry) 0.0 else 4.0,
                precipProb = if (dry) 10 else 85,
                uvIndex = 0.0, // overcast day: no solar signal
                weatherCode = if (dry) 3 else 63
            )
        }

        val best = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(best)
        assertEquals(6, best!!.hours.size)
        assertEquals(midnight + 12 * 3_600_000L, best.startEpochMillis)
    }

    @Test
    fun `findBestSportWindow uses solar UV signal to prefer real daytime in non-UTC timezones`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Tokyo (UTC+9): 00..05 UTC is 09..14 JST (sunny, moderate UV 4..6.5);
        // 11..16 UTC is 20..01 JST (night, uv = 0).
        val hours = (0..23).map { h ->
            val tokyoDayBlock = h in 0..5
            val tokyoNightBlock = h in 11..16
            val dry = tokyoDayBlock || tokyoNightBlock
            val uv = when (h) {
                0 -> 4.0
                1, 2 -> 6.5
                3, 4 -> 5.0
                5 -> 3.0
                else -> 0.0
            }
            utcDayPoint(
                dayMidnightMillis = midnight,
                hour = h,
                precipMm = if (dry) 0.0 else 4.0,
                precipProb = if (dry) 10 else 90,
                uvIndex = uv,
                weatherCode = if (dry) 1 else 63
            )
        }

        val best = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(best)
        assertEquals(midnight, best!!.startEpochMillis)
        assertEquals(6, best.hours.size)
    }

    @Test
    fun `findBestSportWindow utcOffsetSeconds overload resolves local daytime even without solar UV`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = (0..23).map { h ->
            val blockA = h in 0..5 // 09..14 in UTC+9, 00..05 in UTC+0
            val blockB = h in 11..16 // 20..01 in UTC+9, 11..16 in UTC+0
            val dry = blockA || blockB
            utcDayPoint(
                dayMidnightMillis = midnight,
                hour = h,
                precipMm = if (dry) 0.0 else 4.0,
                precipProb = if (dry) 10 else 90,
                uvIndex = 0.0,
                weatherCode = if (dry) 3 else 63
            )
        }

        val tokyoBest = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours, utcOffsetSeconds = 9 * 3600)
        assertNotNull(tokyoBest)
        assertEquals(midnight, tokyoBest!!.startEpochMillis)

        val londonBest = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours, utcOffsetSeconds = 0)
        assertNotNull(londonBest)
        assertEquals(midnight + 11 * 3_600_000L, londonBest!!.startEpochMillis)
    }

    @Test
    fun `findBestSportWindow prevents long marginal window from beating clean daytime window`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // 00..19 (20h) marginal: precipProb=50 (-10), wind=33 (-8), temp=27.0 (-8) -> suitabilityScore = 74
        // 20 (1h) skipped rain
        // 21..23 (3h) or let's put 6h clean daytime 09..14 (score 100) vs 20h marginal across 2 days
        val hours = (0 until 32).map { h ->
            when {
                h in 9..14 -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    tempC = 19.0,
                    precipProb = 10,
                    windKmh = 8.0,
                    uvIndex = 3.0
                )
                h == 8 || h == 15 -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    precipMm = 3.0,
                    precipProb = 85,
                    weatherCode = 63
                )
                else -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    tempC = 27.0,
                    precipProb = 50,
                    windKmh = 33.0,
                    uvIndex = if (h in 24..31) 3.0 else 0.0
                )
            }
        }

        // Marginal block is hours 16..31 (16h, avgScore=74) vs clean daytime block 9..14 (6h, avgScore=100)
        val best = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(best)
        assertEquals(midnight + 9 * 3_600_000L, best!!.startEpochMillis)
        assertEquals(6, best.hours.size)
    }

    @Test
    fun `findBestSportWindow deduplicates identical epochMillis and prevents duplicate inflation`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val duplicatesAt12 = List(12) {
            utcDayPoint(dayMidnightMillis = midnight, hour = 12, tempC = 19.0, windKmh = 8.0, uvIndex = 3.0)
        }
        val skippedAt13 = utcDayPoint(
            dayMidnightMillis = midnight,
            hour = 13,
            precipMm = 6.0,
            precipProb = 95,
            weatherCode = 63
        )
        val realDaytime10h = (34..43).map { h ->
            utcDayPoint(dayMidnightMillis = midnight, hour = h, tempC = 19.0, windKmh = 8.0, uvIndex = 3.5)
        }

        val best = SportAdvisor.findBestSportWindow(
            SportType.RUNNING,
            duplicatesAt12 + listOf(skippedAt13) + realDaytime10h
        )
        assertNotNull(best)
        assertEquals(midnight + 34 * 3_600_000L, best!!.startEpochMillis)
        assertEquals(10, best.hours.size)

        val singleCollapsed = SportAdvisor.findBestSportWindow(SportType.RUNNING, duplicatesAt12)
        assertNotNull(singleCollapsed)
        assertEquals(1, singleCollapsed!!.hours.size)
        assertTrue(singleCollapsed.summaryLabel.startsWith("1 Std. optimal"))
    }

    @Test
    fun `findBestSportWindow resolves conflicting duplicate epochMillis deterministically regardless of order`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val goodAt10 = utcDayPoint(dayMidnightMillis = midnight, hour = 10, tempC = 18.0, windKmh = 8.0, uvIndex = 2.5)
        val badAt10 = utcDayPoint(
            dayMidnightMillis = midnight,
            hour = 10,
            precipMm = 5.0,
            precipProb = 95,
            weatherCode = 63
        )
        val goodAt11 = utcDayPoint(dayMidnightMillis = midnight, hour = 11, tempC = 19.0, windKmh = 8.0, uvIndex = 3.0)

        val order1 = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(goodAt10, badAt10, goodAt11))
        val order2 = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(badAt10, goodAt10, goodAt11))
        assertNotNull(order1)
        assertEquals(order1, order2)
        assertEquals(1, order1!!.hours.size)
        assertEquals(goodAt11.epochMillis, order1.startEpochMillis)

        // Two non-skip points at same timestamp: lower suitabilityScore (worst-case) wins regardless of order
        val marginalAt10 = utcDayPoint(dayMidnightMillis = midnight, hour = 10, tempC = 26.5, windKmh = 15.0, uvIndex = 6.5)
        val nonSkipOrder1 = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(goodAt10, marginalAt10, goodAt11))
        val nonSkipOrder2 = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(marginalAt10, goodAt10, goodAt11))
        assertNotNull(nonSkipOrder1)
        assertEquals(nonSkipOrder1, nonSkipOrder2)
        assertEquals(2, nonSkipOrder1!!.hours.size)
        assertEquals(marginalAt10, nonSkipOrder1.hours.first())
    }

    @Test
    fun `evaluateHoursToSkip flags non-finite hazard fields while preserving NaN UV handling`() {
        val invalidHazards = listOf(
            point(hourOffset = 0, precipMm = Double.POSITIVE_INFINITY) to SkipReason.RAIN,
            point(hourOffset = 1, precipMm = Double.NaN) to SkipReason.RAIN,
            point(hourOffset = 2, windKmh = Double.POSITIVE_INFINITY) to SkipReason.WIND,
            point(hourOffset = 3, gustKmh = Double.NaN) to SkipReason.WIND,
            point(hourOffset = 4, capeJkg = Double.POSITIVE_INFINITY) to SkipReason.THUNDERSTORM,
            point(hourOffset = 5, capeJkg = Double.NaN) to SkipReason.THUNDERSTORM,
            point(hourOffset = 6, uvIndex = Double.POSITIVE_INFINITY) to SkipReason.UV,
            point(hourOffset = 7, uvIndex = Double.NEGATIVE_INFINITY) to SkipReason.UV
        )

        for ((pt, expectedReason) in invalidHazards) {
            val verdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, listOf(pt))
            assertEquals("Expected skip for $pt", 1, verdict.skipHoursCount)
            assertTrue(verdict.hoursToSkip.first().shouldSkip)
            assertEquals(0, verdict.hoursToSkip.first().suitabilityScore)
            assertTrue(expectedReason in verdict.hoursToSkip.first().reasons)
            assertNull(SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(pt)))
        }

        // Combined corrupt point (Challenger Probe 2b) must not score 100
        val combinedCorrupt = point(
            precipMm = Double.POSITIVE_INFINITY,
            windKmh = Double.POSITIVE_INFINITY,
            gustKmh = Double.NaN,
            uvIndex = Double.NaN,
            capeJkg = Double.POSITIVE_INFINITY
        )
        val combinedVerdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, listOf(combinedCorrupt))
        assertEquals(1, combinedVerdict.skipHoursCount)
        assertEquals(0, combinedVerdict.hoursToSkip.first().suitabilityScore)
        assertNull(SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(combinedCorrupt)))

        // Winter sport must also skip non-finite precipMm even below 2C
        val winterInvalidRain = point(tempC = -5.0, precipMm = Double.NaN, weatherCode = 71)
        val winterVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, listOf(winterInvalidRain))
        assertEquals(1, winterVerdict.skipHoursCount)
        assertTrue(SkipReason.RAIN in winterVerdict.hoursToSkip.first().reasons)

        // Preserve NaN UV behavior: classifyUv(NaN) == LOW and mild NaN-UV hour is not skipped
        assertEquals(UvLevel.LOW, SportAdvisor.classifyUv(Double.NaN))
        assertEquals(UvLevel.EXTREME, SportAdvisor.classifyUv(Double.POSITIVE_INFINITY))
        val nanUvOnly = point(tempC = 18.0, uvIndex = Double.NaN)
        assertEquals(0, SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, listOf(nanUvOnly)).skipHoursCount)
    }

    @Test
    fun `findBestSportWindow guards Long overflow on extreme epochMillis boundaries`() {
        val minPoint = point().copy(epochMillis = Long.MIN_VALUE)
        val maxPoint = point().copy(epochMillis = Long.MAX_VALUE)

        val splitResult = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(minPoint, maxPoint))
        assertNotNull(splitResult)
        assertEquals(1, splitResult!!.hours.size)
        assertTrue(splitResult.endEpochMillis >= splitResult.startEpochMillis)

        val maxOnlyResult = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(maxPoint))
        assertNotNull(maxOnlyResult)
        assertEquals(Long.MAX_VALUE, maxOnlyResult!!.startEpochMillis)
        assertEquals(Long.MAX_VALUE, maxOnlyResult.endEpochMillis)

        val nearMaxPoint = point().copy(epochMillis = Long.MAX_VALUE - 5_400_000L)
        val mergedNearMax = SportAdvisor.findBestSportWindow(SportType.RUNNING, listOf(nearMaxPoint, maxPoint))
        assertNotNull(mergedNearMax)
        assertEquals(2, mergedNearMax!!.hours.size)
        assertEquals(Long.MAX_VALUE, mergedNearMax.endEpochMillis)
    }

    @Test
    fun `findBestSportWindow ignores sub-0_1 UV residual noise in daylight detection`() {
        val midnight = LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = (0..23).map { h ->
            when (h) {
                in 1..6 -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    uvIndex = 0.05 // sub-0.1 sensor/trig residual noise at night
                )
                in 8..13 -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    uvIndex = 0.0, // overcast daytime
                    weatherCode = 3
                )
                15 -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    precipMm = 4.0,
                    precipProb = 90,
                    uvIndex = 0.6, // triggers hasSolarSignal >= 0.5
                    weatherCode = 63
                )
                else -> utcDayPoint(
                    dayMidnightMillis = midnight,
                    hour = h,
                    precipMm = 4.0,
                    precipProb = 90,
                    weatherCode = 63
                )
            }
        }

        val best = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(best)
        assertEquals(6, best!!.hours.size)
        assertEquals(midnight + 8 * 3_600_000L, best.startEpochMillis)
    }
}
