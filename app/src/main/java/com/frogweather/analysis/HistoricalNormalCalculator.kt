package com.frogweather.analysis

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class HourlyNormalPoint(
    val hour: Int,
    val tempC: Double
)

data class NormalComparison(
    val currentTempC: Double,
    val normalTempC: Double,
    val deltaC: Double,
    val normalAtTimeLabel: String,
    val deltaLabel: String
)

data class HistoryDayPoint(
    val date: String,
    val minTempC: Double,
    val maxTempC: Double,
    val meanTempC: Double
)

data class HistoryGraphData(
    val requestedDays: Int,
    val points: List<HistoryDayPoint>,
    val periodMinC: Double?,
    val periodMaxC: Double?,
    val periodMeanC: Double?
)

object HistoricalNormalCalculator {

    private const val HOURS_PER_DAY = 24.0
    private const val MIDPOINT_MORNING_HOUR = 9.0

    /**
     * Computes a smooth sinusoidal diurnal normal temperature curve with daily trough at 03:00
     * and daily peak at 15:00.
     */
    fun computeSinusoidalNormalTemp(
        dailyMinC: Double,
        dailyMaxC: Double,
        hourOfDay: Double
    ): Double {
        val minFinite = dailyMinC.isFinite()
        val maxFinite = dailyMaxC.isFinite()
        if (!minFinite && !maxFinite) return 0.0
        if (!minFinite) return dailyMaxC
        if (!maxFinite) return dailyMinC

        val lo = min(dailyMinC, dailyMaxC)
        val hi = max(dailyMinC, dailyMaxC)
        val mid = lo / 2.0 + hi / 2.0
        if (lo == hi || !hourOfDay.isFinite()) return mid

        val amp = hi / 2.0 - lo / 2.0
        val rawWrapped = ((hourOfDay % HOURS_PER_DAY) + HOURS_PER_DAY) % HOURS_PER_DAY
        val wrappedHour = if (rawWrapped >= HOURS_PER_DAY) 0.0 else rawWrapped
        val angle = 2.0 * PI * (wrappedHour - MIDPOINT_MORNING_HOUR) / HOURS_PER_DAY
        val value = mid + amp * sin(angle)
        return if (value.isFinite()) value.coerceIn(lo, hi) else mid
    }

    fun compareWithNormal(
        currentTempC: Double,
        dailyMinC: Double,
        dailyMaxC: Double,
        hourOfDay: Double
    ): NormalComparison {
        val normalTempC = computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, hourOfDay)
        val normalAtTimeLabel = String.format(
            Locale.GERMAN,
            "normalerweise %.1f °C um diese Zeit",
            normalTempC
        )

        if (!currentTempC.isFinite()) {
            return NormalComparison(
                currentTempC = currentTempC,
                normalTempC = normalTempC,
                deltaC = 0.0,
                normalAtTimeLabel = normalAtTimeLabel,
                deltaLabel = "Keine aktuelle Temperatur"
            )
        }

        val deltaC = currentTempC - normalTempC
        val deltaLabel = when {
            abs(deltaC) < 0.5 -> String.format(
                Locale.GERMAN,
                "Im Normalbereich (%+.1f °C)",
                deltaC
            )
            deltaC >= 0.5 -> String.format(
                Locale.GERMAN,
                "+%.1f °C wärmer als üblich",
                deltaC
            )
            else -> String.format(
                Locale.GERMAN,
                "%.1f °C kälter als üblich",
                deltaC
            )
        }

        return NormalComparison(
            currentTempC = currentTempC,
            normalTempC = normalTempC,
            deltaC = deltaC,
            normalAtTimeLabel = normalAtTimeLabel,
            deltaLabel = deltaLabel
        )
    }

    fun build24HourNormalCurve(dailyMinC: Double, dailyMaxC: Double): List<HourlyNormalPoint> =
        (0..23).map { hour ->
            HourlyNormalPoint(
                hour = hour,
                tempC = computeSinusoidalNormalTemp(dailyMinC, dailyMaxC, hour.toDouble())
            )
        }

    fun buildHistorySeries(
        dailyDates: List<String>,
        mins: List<Double>,
        maxs: List<Double>,
        means: List<Double>,
        days: Int
    ): HistoryGraphData {
        if (days <= 0) {
            return HistoryGraphData(
                requestedDays = days,
                points = emptyList(),
                periodMinC = null,
                periodMaxC = null,
                periodMeanC = null
            )
        }

        val count = minOf(dailyDates.size, mins.size, maxs.size)
        val validPoints = ArrayList<HistoryDayPoint>(count)
        for (i in 0 until count) {
            val date = dailyDates[i]
            val rawMin = mins[i]
            val rawMax = maxs[i]
            if (date.isBlank() || !rawMin.isFinite() || !rawMax.isFinite()) continue
            val lo = min(rawMin, rawMax)
            val hi = max(rawMin, rawMax)
            val mean = means.getOrNull(i)?.takeIf { it.isFinite() } ?: ((lo + hi) / 2.0)
            validPoints.add(
                HistoryDayPoint(
                    date = date,
                    minTempC = lo,
                    maxTempC = hi,
                    meanTempC = mean
                )
            )
        }

        val sliced = validPoints.takeLast(days)
        val periodMin = sliced.minOfOrNull { it.minTempC }
        val periodMax = sliced.maxOfOrNull { it.maxTempC }
        val periodMean = if (sliced.isEmpty()) null else sliced.map { it.meanTempC }.average()

        return HistoryGraphData(
            requestedDays = days,
            points = sliced,
            periodMinC = periodMin,
            periodMaxC = periodMax,
            periodMeanC = periodMean
        )
    }
}
