package com.frogweather.phase2

import com.frogweather.analysis.HistoricalNormalCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalNormalCalculatorTest {

    @Test
    fun `computeSinusoidalNormalTemp hits exact trough at 03h, midpoints at 09h and 21h, and peak at 15h`() {
        val min = 6.0
        val max = 22.0
        val mid = 14.0

        assertEquals(min, HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 3.0), 1e-6)
        assertEquals(mid, HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 9.0), 1e-6)
        assertEquals(max, HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 15.0), 1e-6)
        assertEquals(mid, HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 21.0), 1e-6)
    }

    @Test
    fun `computeSinusoidalNormalTemp wraps negative and over-24 hours continuously across midnight`() {
        val min = 4.0
        val max = 18.0

        val at0 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 0.0)
        val at24 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 24.0)
        val atNeg24 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, -24.0)
        assertEquals(at0, at24, 1e-6)
        assertEquals(at0, atNeg24, 1e-6)

        val atNeg3 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, -3.0)
        val at21 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 21.0)
        assertEquals(at21, atNeg3, 1e-6)

        val at275 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 27.5)
        val at35 = HistoricalNormalCalculator.computeSinusoidalNormalTemp(min, max, 3.5)
        assertEquals(at35, at275, 1e-6)
    }

    @Test
    fun `computeSinusoidalNormalTemp handles equal, inverted, and non-finite bounds safely`() {
        assertEquals(12.5, HistoricalNormalCalculator.computeSinusoidalNormalTemp(12.5, 12.5, 11.0), 1e-6)

        val invertedPeak = HistoricalNormalCalculator.computeSinusoidalNormalTemp(25.0, 10.0, 15.0)
        assertEquals(25.0, invertedPeak, 1e-6)
        val invertedTrough = HistoricalNormalCalculator.computeSinusoidalNormalTemp(25.0, 10.0, 3.0)
        assertEquals(10.0, invertedTrough, 1e-6)

        val nanHour = HistoricalNormalCalculator.computeSinusoidalNormalTemp(10.0, 20.0, Double.NaN)
        assertEquals(15.0, nanHour, 1e-6)

        val bothNan = HistoricalNormalCalculator.computeSinusoidalNormalTemp(Double.NaN, Double.POSITIVE_INFINITY, 12.0)
        assertEquals(0.0, bothNan, 1e-6)
    }

    @Test
    fun `compareWithNormal formats German normal label and signed warmer, colder, and normal band labels`() {
        val warmer = HistoricalNormalCalculator.compareWithNormal(24.5, 10.0, 22.0, 15.0)
        assertEquals("normalerweise 22,0 °C um diese Zeit", warmer.normalAtTimeLabel)
        assertEquals("+2,5 °C wärmer als üblich", warmer.deltaLabel)
        assertEquals(2.5, warmer.deltaC, 1e-6)

        val colder = HistoricalNormalCalculator.compareWithNormal(8.2, 10.0, 22.0, 15.0)
        assertTrue(colder.deltaLabel.contains("kälter als üblich"))
        assertTrue(colder.deltaLabel.contains("-13,8"))

        val inBand = HistoricalNormalCalculator.compareWithNormal(22.3, 10.0, 22.0, 15.0)
        assertTrue(inBand.deltaLabel.startsWith("Im Normalbereich"))
    }

    @Test
    fun `build24HourNormalCurve returns 24 hourly points with valid range`() {
        val curve = HistoricalNormalCalculator.build24HourNormalCurve(5.0, 19.0)
        assertEquals(24, curve.size)
        assertEquals(0, curve.first().hour)
        assertEquals(23, curve.last().hour)
        assertTrue(curve.all { it.tempC in 5.0..19.0 })
    }

    @Test
    fun `buildHistorySeries slices 7, 14, and 30 days accurately`() {
        val dates = (1..35).map { String.format("2026-10-%02d", it) }
        val mins = (1..35).map { it.toDouble() }
        val maxs = (1..35).map { it.toDouble() + 10.0 }
        val means = (1..35).map { it.toDouble() + 5.0 }

        val s7 = HistoricalNormalCalculator.buildHistorySeries(dates, mins, maxs, means, 7)
        assertEquals(7, s7.points.size)
        assertEquals("2026-10-29", s7.points.first().date)
        assertEquals("2026-10-35", s7.points.last().date)
        assertEquals(29.0, s7.periodMinC!!, 1e-6)
        assertEquals(45.0, s7.periodMaxC!!, 1e-6)

        val s14 = HistoricalNormalCalculator.buildHistorySeries(dates, mins, maxs, means, 14)
        assertEquals(14, s14.points.size)
        assertEquals("2026-10-22", s14.points.first().date)

        val s30 = HistoricalNormalCalculator.buildHistorySeries(dates, mins, maxs, means, 30)
        assertEquals(30, s30.points.size)
        assertEquals("2026-10-06", s30.points.first().date)
    }

    @Test
    fun `buildHistorySeries handles mismatched lengths, blank dates, NaNs, and non-positive days`() {
        val empty = HistoricalNormalCalculator.buildHistorySeries(listOf("2026-11-01"), listOf(1.0), listOf(5.0), listOf(3.0), 0)
        assertTrue(empty.points.isEmpty())
        assertNull(empty.periodMinC)
        assertNull(empty.periodMaxC)
        assertNull(empty.periodMeanC)

        val dates = listOf("2026-11-01", " ", "2026-11-03", "2026-11-04")
        val mins = listOf(4.0, 5.0, Double.NaN, 12.0)
        val maxs = listOf(10.0, 12.0, 15.0, 6.0) // 4th has inverted bounds, recovered cleanly
        val means = listOf(Double.NaN) // shorter + NaN -> fallback (lo+hi)/2

        val series = HistoricalNormalCalculator.buildHistorySeries(dates, mins, maxs, means, 7)
        assertEquals(2, series.points.size)
        assertEquals("2026-11-01", series.points[0].date)
        assertEquals(7.0, series.points[0].meanTempC, 1e-6)
        assertEquals("2026-11-04", series.points[1].date)
        assertEquals(6.0, series.points[1].minTempC, 1e-6)
        assertEquals(12.0, series.points[1].maxTempC, 1e-6)
        assertEquals(9.0, series.points[1].meanTempC, 1e-6)
        assertNotNull(series.periodMeanC)
        assertEquals(8.0, series.periodMeanC!!, 1e-6)
    }
}
