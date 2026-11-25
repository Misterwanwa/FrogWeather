package com.frogweather.analysis

import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.KeyValueStore
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

enum class SportType(val id: String, val displayName: String) {
    RUNNING("running", "Laufen/Joggen"),
    CYCLING("cycling", "Radfahren"),
    HIKING("hiking", "Wandern"),
    OUTDOOR_FITNESS("outdoor_fitness", "Outdoor-Fitness"),
    TEAM_SPORT("team_sport", "Fußball/Teamsport"),
    SWIMMING("swimming", "Schwimmen/Wassersport"),
    WINTER_SPORT("winter_sport", "Wintersport"),
    WALKING("walking", "Spazieren");

    companion object {
        fun fromId(raw: String?): SportType {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return RUNNING
            return entries.firstOrNull {
                it.id.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: RUNNING
        }
    }
}

data class HourlyWeatherPoint(
    val epochMillis: Long,
    val tempC: Double,
    val precipMm: Double,
    val precipProb: Int,
    val windKmh: Double,
    val gustKmh: Double,
    val uvIndex: Double,
    val capeJkg: Double,
    val weatherCode: Int,
    val humidityPct: Int,
    val cloudCoverPct: Int,
    val pressureHpa: Double
)

enum class UvLevel(val label: String) {
    LOW("Niedrig"),
    MODERATE("Mäßig"),
    HIGH("Hoch"),
    VERY_HIGH("Sehr hoch"),
    EXTREME("Extrem")
}

enum class SkipReason(val label: String) {
    THUNDERSTORM("Gewitter-/Blitzrisiko"),
    HEAT("Hitze"),
    COLD("Kälte"),
    UV("Hoher UV-Index"),
    WIND("Starker Wind / Böen"),
    RAIN("Niederschlag / Regenrisiko")
}

data class HourSkipEvaluation(
    val hour: HourlyWeatherPoint,
    val shouldSkip: Boolean,
    val suitabilityScore: Int,
    val reasons: List<SkipReason>,
    val reasonLabels: List<String>
)

data class SportVerdict(
    val sport: SportType,
    val hoursToSkip: List<HourSkipEvaluation>,
    val allEvaluations: List<HourSkipEvaluation>,
    val goodHoursCount: Int,
    val skipHoursCount: Int,
    val peakUvIndex: Double,
    val peakUvLevel: UvLevel,
    val summaryText: String
)

data class SportWindow(
    val sport: SportType,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val hours: List<HourlyWeatherPoint>,
    val averageScore: Double,
    val maxUvIndex: Double,
    val summaryLabel: String
)

private data class SportThresholds(
    val heatTempC: Double,
    val coldTempC: Double,
    val rainMm: Double,
    val rainProbPct: Int,
    val windKmh: Double,
    val gustKmh: Double,
    val uvSkip: Double,
    val capeThunderJkg: Double = 1500.0,
    val humidHeatTempC: Double? = null,
    val humidHeatMinHumidity: Int = 75,
    val allowSnowWinter: Boolean = false
)

object SportAdvisor {

    private val THUNDERSTORM_WMO_CODES = setOf(95, 96, 99)
    private val SEVERE_PRECIP_WMO_CODES = setOf(57, 67, 75, 82, 86)
    private val LIQUID_RAIN_WMO_CODES = setOf(51, 53, 55, 61, 63, 65, 80, 81)
    private const val ONE_HOUR_MILLIS = 3_600_000L
    private const val WINDOW_GAP_TOLERANCE_MILLIS = 5_400_000L

    fun classifyUv(uvIndex: Double): UvLevel {
        val uv = when {
            uvIndex == Double.POSITIVE_INFINITY -> Double.MAX_VALUE
            uvIndex.isFinite() -> uvIndex.coerceAtLeast(0.0)
            else -> 0.0
        }
        return when {
            uv < 3.0 -> UvLevel.LOW
            uv < 6.0 -> UvLevel.MODERATE
            uv < 8.0 -> UvLevel.HIGH
            uv < 11.0 -> UvLevel.VERY_HIGH
            else -> UvLevel.EXTREME
        }
    }

    fun uvAdvice(uvIndex: Double): String = when (classifyUv(uvIndex)) {
        UvLevel.LOW -> "Geringer Schutz nötig."
        UvLevel.MODERATE -> "Sonnenbrille & LSF 30 empfohlen."
        UvLevel.HIGH -> "Sonnenschutz (LSF 30+), Kappe & Schatten suchen."
        UvLevel.VERY_HIGH -> "Mittagssonne meiden, LSF 50+ & Schutzkleidung."
        UvLevel.EXTREME -> "Draußen möglichst meiden, maximaler UV-Schutz."
    }

    fun sportDescription(sport: SportType): String = when (sport) {
        SportType.RUNNING -> "Sensibel bei Hitze (>29 °C), Starkregen, Gewitter & hohem UV"
        SportType.CYCLING -> "Streng bei Wind/Böen, nasser Straße & Frost (Glätte)"
        SportType.HIKING -> "Achtet auf Gewitter/CAPE, Böen, Starkregen & UV"
        SportType.OUTDOOR_FITNESS -> "Sehr sensibel auf Regen, Kälte (<2 °C) & UV"
        SportType.TEAM_SPORT -> "Platzverhältnisse, Böen, Hitze & Gewitterschutz"
        SportType.SWIMMING -> "Warm (>18 °C), windruhig & striktes Blitz-/CAPE-Limit"
        SportType.WINTER_SPORT -> "Schneefreundlich (-20 bis 8 °C), meidet Tauwetter/Regen"
        SportType.WALKING -> "Breiter Komfortbereich, meidet Regen, Sturm & Extrem-UV"
    }

    fun evaluateHoursToSkip(sport: SportType, hours: List<HourlyWeatherPoint>): SportVerdict {
        val evaluationsByEpoch = linkedMapOf<Long, HourSkipEvaluation>()
        for (point in hours) {
            val eval = evaluateHour(sport, point)
            val existing = evaluationsByEpoch[point.epochMillis]
            if (existing == null || compareWorstCaseEvaluation(eval, existing) > 0) {
                evaluationsByEpoch[point.epochMillis] = eval
            }
        }
        val evaluations = evaluationsByEpoch.values.sortedBy { it.hour.epochMillis }
        val skipped = evaluations.filter { it.shouldSkip }
        val goodCount = evaluations.size - skipped.size
        val peakUv = evaluations.maxOfOrNull { sanitizeNonNegative(it.hour.uvIndex) } ?: 0.0
        val peakLevel = classifyUv(peakUv)

        val summary = when {
            evaluations.isEmpty() -> "Keine Stundendaten für ${sport.displayName} verfügbar."
            skipped.isEmpty() -> "Alle ${evaluations.size} Stunden sind gut für ${sport.displayName} geeignet."
            goodCount == 0 -> "Alle ${evaluations.size} Stunden kritisch für ${sport.displayName} – besser verschieben."
            else -> "${skipped.size} von ${evaluations.size} Stunden für ${sport.displayName} meiden."
        }

        return SportVerdict(
            sport = sport,
            hoursToSkip = skipped,
            allEvaluations = evaluations,
            goodHoursCount = goodCount,
            skipHoursCount = skipped.size,
            peakUvIndex = peakUv,
            peakUvLevel = peakLevel,
            summaryText = summary
        )
    }

    fun findBestSportWindow(sport: SportType, hours: List<HourlyWeatherPoint>): SportWindow? =
        findBestSportWindow(sport, hours, utcOffsetSeconds = null)

    fun findBestSportWindow(
        sport: SportType,
        hours: List<HourlyWeatherPoint>,
        utcOffsetSeconds: Int?
    ): SportWindow? {
        if (hours.isEmpty()) return null
        val evaluationsByEpoch = linkedMapOf<Long, HourSkipEvaluation>()
        for (point in hours) {
            val eval = evaluateHour(sport, point)
            val existing = evaluationsByEpoch[point.epochMillis]
            if (existing == null || compareWorstCaseEvaluation(eval, existing) > 0) {
                evaluationsByEpoch[point.epochMillis] = eval
            }
        }
        val evaluations = evaluationsByEpoch.values.sortedBy { it.hour.epochMillis }

        val windows = mutableListOf<List<HourSkipEvaluation>>()
        var current = mutableListOf<HourSkipEvaluation>()

        for (eval in evaluations) {
            if (eval.shouldSkip) {
                if (current.isNotEmpty()) {
                    windows.add(current)
                    current = mutableListOf()
                }
            } else {
                if (current.isEmpty() ||
                    isWithinWindowGap(current.last().hour.epochMillis, eval.hour.epochMillis)
                ) {
                    current.add(eval)
                } else {
                    windows.add(current)
                    current = mutableListOf(eval)
                }
            }
        }
        if (current.isNotEmpty()) {
            windows.add(current)
        }

        if (windows.isEmpty()) return null

        val hasSolarSignal = evaluations.any { sanitizeNonNegative(it.hour.uvIndex) >= 0.5 }
        val explicitOffset = utcOffsetSeconds?.let { sec ->
            runCatching { ZoneOffset.ofTotalSeconds(sec) }.getOrDefault(ZoneOffset.UTC)
        }

        val bestBlock = windows.maxWithOrNull { a, b ->
            val scoreCmp = windowRankScore(a, hasSolarSignal, explicitOffset)
                .compareTo(windowRankScore(b, hasSolarSignal, explicitOffset))
            if (scoreCmp != 0) {
                scoreCmp
            } else {
                val lenCmp = a.size.compareTo(b.size)
                if (lenCmp != 0) {
                    lenCmp
                } else {
                    val avgCmp = a.map { it.suitabilityScore }.average()
                        .compareTo(b.map { it.suitabilityScore }.average())
                    if (avgCmp != 0) {
                        avgCmp
                    } else {
                        b.first().hour.epochMillis.compareTo(a.first().hour.epochMillis)
                    }
                }
            }
        } ?: return null

        val rawPoints = bestBlock.map { it.hour }
        val avgScore = bestBlock.map { it.suitabilityScore }.average()
        val maxUv = rawPoints.maxOf { sanitizeNonNegative(it.uvIndex) }
        val avgTemp = rawPoints.map { it.tempC }.average()
        val avgWind = rawPoints.map { sanitizeNonNegative(it.windKmh) }.average()
        val summaryLabel = String.format(
            Locale.GERMAN,
            "%d Std. optimal • Ø %.0f °C • Wind %.0f km/h",
            rawPoints.size,
            avgTemp,
            avgWind
        )

        val lastMillis = rawPoints.last().epochMillis
        val endEpochMillis = if (lastMillis > Long.MAX_VALUE - ONE_HOUR_MILLIS) {
            Long.MAX_VALUE
        } else {
            lastMillis + ONE_HOUR_MILLIS
        }

        return SportWindow(
            sport = sport,
            startEpochMillis = rawPoints.first().epochMillis,
            endEpochMillis = endEpochMillis,
            hours = rawPoints,
            averageScore = avgScore,
            maxUvIndex = maxUv,
            summaryLabel = summaryLabel
        )
    }

    private fun isWithinWindowGap(prevMillis: Long, currMillis: Long): Boolean =
        currMillis >= prevMillis &&
            (prevMillis > Long.MAX_VALUE - WINDOW_GAP_TOLERANCE_MILLIS ||
                currMillis <= prevMillis + WINDOW_GAP_TOLERANCE_MILLIS)

    private fun compareWorstCaseEvaluation(a: HourSkipEvaluation, b: HourSkipEvaluation): Int {
        val skipCmp = a.shouldSkip.compareTo(b.shouldSkip)
        if (skipCmp != 0) return skipCmp
        val scoreCmp = b.suitabilityScore.compareTo(a.suitabilityScore)
        if (scoreCmp != 0) return scoreCmp
        val reasonsCmp = a.reasons.size.compareTo(b.reasons.size)
        if (reasonsCmp != 0) return reasonsCmp
        val precipCmp = java.lang.Double.compare(a.hour.precipMm, b.hour.precipMm)
        if (precipCmp != 0) return precipCmp
        val probCmp = a.hour.precipProb.compareTo(b.hour.precipProb)
        if (probCmp != 0) return probCmp
        val gustCmp = java.lang.Double.compare(a.hour.gustKmh, b.hour.gustKmh)
        if (gustCmp != 0) return gustCmp
        val windCmp = java.lang.Double.compare(a.hour.windKmh, b.hour.windKmh)
        if (windCmp != 0) return windCmp
        val capeCmp = java.lang.Double.compare(a.hour.capeJkg, b.hour.capeJkg)
        if (capeCmp != 0) return capeCmp
        val uvCmp = java.lang.Double.compare(a.hour.uvIndex, b.hour.uvIndex)
        if (uvCmp != 0) return uvCmp
        val wmoCmp = a.hour.weatherCode.compareTo(b.hour.weatherCode)
        if (wmoCmp != 0) return wmoCmp
        val humCmp = a.hour.humidityPct.compareTo(b.hour.humidityPct)
        if (humCmp != 0) return humCmp
        val cloudCmp = a.hour.cloudCoverPct.compareTo(b.hour.cloudCoverPct)
        if (cloudCmp != 0) return cloudCmp
        val tempCmp = java.lang.Double.compare(a.hour.tempC, b.hour.tempC)
        if (tempCmp != 0) return tempCmp
        return java.lang.Double.compare(a.hour.pressureHpa, b.hour.pressureHpa)
    }

    private fun windowRankScore(
        block: List<HourSkipEvaluation>,
        hasSolarSignal: Boolean,
        explicitOffset: ZoneOffset?
    ): Double {
        val avgScore = block.map { it.suitabilityScore }.average()
        val durationFactor = min(block.size, 3) / 3.0
        val extraHours = (block.size - 4).coerceAtLeast(0)
        val lengthBonus = min(block.size, 4) * 2.5 + ln(1.0 + extraHours) * 4.5
        val activityWeights = block.map { eval ->
            hourActivityWeight(eval.hour, hasSolarSignal, explicitOffset)
        }
        val avgActivity = activityWeights.average()
        val totalActiveHours = activityWeights.sum()
        val daylightBonus = avgActivity * 6.0 + min(totalActiveHours, 6.0) * 0.5
        return avgScore * (0.65 + 0.35 * durationFactor) + lengthBonus + daylightBonus
    }

    private fun hourActivityWeight(
        point: HourlyWeatherPoint,
        hasSolarSignal: Boolean,
        explicitOffset: ZoneOffset?
    ): Double {
        val hasSolarUv = sanitizeNonNegative(point.uvIndex) >= 0.1
        if (explicitOffset != null) {
            val localHour = Instant.ofEpochMilli(point.epochMillis).atOffset(explicitOffset).hour
            return maxOf(clockActivityWeight(localHour), if (hasSolarUv) 1.0 else 0.0)
        }
        val utcHour = Instant.ofEpochMilli(point.epochMillis).atOffset(ZoneOffset.UTC).hour
        val utcClockWeight = clockActivityWeight(utcHour)
        return if (hasSolarSignal) {
            if (hasSolarUv) 1.0 else utcClockWeight * 0.35
        } else {
            utcClockWeight
        }
    }

    private fun clockActivityWeight(hourOfDay: Int): Double = when (hourOfDay) {
        in 7..20 -> 1.0
        6, 21 -> 0.5
        else -> 0.0
    }

    private fun evaluateHour(sport: SportType, point: HourlyWeatherPoint): HourSkipEvaluation {
        if (!point.tempC.isFinite()) {
            return HourSkipEvaluation(
                hour = point,
                shouldSkip = true,
                suitabilityScore = 0,
                reasons = listOf(SkipReason.COLD),
                reasonLabels = listOf("Ungültige Temperaturdaten")
            )
        }

        val thresholds = thresholdsFor(sport)
        val tempC = point.tempC
        val hasInvalidHazard = !point.precipMm.isFinite() ||
            !point.windKmh.isFinite() ||
            !point.gustKmh.isFinite() ||
            !point.capeJkg.isFinite() ||
            point.uvIndex.isInfinite()
        val precipMm = sanitizeNonNegative(point.precipMm)
        val precipProb = point.precipProb.coerceIn(0, 100)
        val windKmh = sanitizeNonNegative(point.windKmh)
        val gustKmh = sanitizeNonNegative(point.gustKmh)
        val uvIndex = sanitizeNonNegative(point.uvIndex)
        val capeJkg = sanitizeNonNegative(point.capeJkg)
        val humidityPct = point.humidityPct.coerceIn(0, 100)

        val reasons = mutableListOf<SkipReason>()
        val reasonLabels = mutableListOf<String>()
        var penalty = 0

        // 1. Thunderstorm / Lightning
        if (!point.capeJkg.isFinite() || point.weatherCode in THUNDERSTORM_WMO_CODES || capeJkg >= thresholds.capeThunderJkg) {
            reasons.add(SkipReason.THUNDERSTORM)
            val detail = when {
                !point.capeJkg.isFinite() -> "Ungültige CAPE-Daten"
                point.weatherCode in THUNDERSTORM_WMO_CODES -> "Gewitter-WMO ${point.weatherCode}"
                else -> String.format(Locale.GERMAN, "CAPE %.0f J/kg", capeJkg)
            }
            reasonLabels.add("${SkipReason.THUNDERSTORM.label} ($detail)")
            penalty += 60
        }

        // 2. Heat
        val humidHeatTriggered = thresholds.humidHeatTempC != null &&
            tempC >= thresholds.humidHeatTempC &&
            humidityPct >= thresholds.humidHeatMinHumidity
        if (tempC >= thresholds.heatTempC || humidHeatTriggered) {
            reasons.add(SkipReason.HEAT)
            reasonLabels.add(String.format(Locale.GERMAN, "Hitze (%.1f °C)", tempC))
            penalty += 30
        }

        // 3. Cold
        if (tempC <= thresholds.coldTempC) {
            reasons.add(SkipReason.COLD)
            reasonLabels.add(String.format(Locale.GERMAN, "Kälte (%.1f °C)", tempC))
            penalty += 30
        }

        // 4. Precipitation / Blizzard / Freezing rain
        if (!point.precipMm.isFinite()) {
            reasons.add(SkipReason.RAIN)
            reasonLabels.add("Ungültige Niederschlagsdaten")
            penalty += 28
        } else {
            val severeWmo = point.weatherCode in SEVERE_PRECIP_WMO_CODES
            val isLiquidRainWmo = point.weatherCode in LIQUID_RAIN_WMO_CODES
            val rainTriggered = if (thresholds.allowSnowWinter) {
                val liquidRain = (tempC > 2.0 || (tempC > 0.0 && isLiquidRainWmo)) &&
                    (precipMm >= thresholds.rainMm || precipProb >= thresholds.rainProbPct)
                val blizzard = precipMm >= 5.0 || point.weatherCode in setOf(75, 86)
                severeWmo || liquidRain || blizzard
            } else {
                severeWmo || precipMm >= thresholds.rainMm || precipProb >= thresholds.rainProbPct
            }
            if (rainTriggered) {
                reasons.add(SkipReason.RAIN)
                reasonLabels.add(
                    String.format(Locale.GERMAN, "Niederschlag (%.1f mm, %d %%)", precipMm, precipProb)
                )
                penalty += 28
            }
        }

        // 5. Wind / Gusts
        if (!point.windKmh.isFinite() || !point.gustKmh.isFinite()) {
            reasons.add(SkipReason.WIND)
            reasonLabels.add("Ungültige Wind-/Böendaten")
            penalty += 25
        } else if (windKmh >= thresholds.windKmh || gustKmh >= thresholds.gustKmh) {
            reasons.add(SkipReason.WIND)
            val maxWind = maxOf(windKmh, gustKmh)
            val kind = if (gustKmh >= thresholds.gustKmh && gustKmh > windKmh) "Böen" else "Wind"
            reasonLabels.add(String.format(Locale.GERMAN, "$kind (%.0f km/h)", maxWind))
            penalty += 25
        }

        // 6. UV Index
        if (point.uvIndex.isInfinite()) {
            reasons.add(SkipReason.UV)
            reasonLabels.add(
                if (point.uvIndex > 0.0) "UV-Index extrem (∞)" else "Ungültige UV-Daten"
            )
            penalty += 20
        } else if (uvIndex >= thresholds.uvSkip) {
            reasons.add(SkipReason.UV)
            reasonLabels.add(String.format(Locale.GERMAN, "UV-Index hoch (%.1f)", uvIndex))
            penalty += 20
        }

        // Soft suitability deductions even when not skipped
        if (reasons.isEmpty()) {
            if (uvIndex >= 6.0) penalty += 10
            if (precipProb >= 40) penalty += 10
            if (windKmh >= thresholds.windKmh * 0.7) penalty += 8
            if (tempC >= thresholds.heatTempC - 3.0) penalty += 8
            if (tempC <= thresholds.coldTempC + 3.0) penalty += 8
        }

        val score = if (hasInvalidHazard) 0 else (100 - penalty).coerceIn(0, 100)
        return HourSkipEvaluation(
            hour = point,
            shouldSkip = reasons.isNotEmpty(),
            suitabilityScore = score,
            reasons = reasons,
            reasonLabels = reasonLabels
        )
    }

    private fun sanitizeNonNegative(value: Double): Double =
        if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0

    private fun thresholdsFor(sport: SportType): SportThresholds = when (sport) {
        SportType.RUNNING -> SportThresholds(
            heatTempC = 29.0,
            coldTempC = -8.0,
            rainMm = 1.5,
            rainProbPct = 75,
            windKmh = 45.0,
            gustKmh = 60.0,
            uvSkip = 8.0,
            humidHeatTempC = 27.0,
            humidHeatMinHumidity = 75
        )
        SportType.CYCLING -> SportThresholds(
            heatTempC = 32.0,
            coldTempC = 0.0,
            rainMm = 0.8,
            rainProbPct = 65,
            windKmh = 32.0,
            gustKmh = 48.0,
            uvSkip = 8.5,
            capeThunderJkg = 1000.0
        )
        SportType.HIKING -> SportThresholds(
            heatTempC = 30.0,
            coldTempC = -5.0,
            rainMm = 1.2,
            rainProbPct = 70,
            windKmh = 38.0,
            gustKmh = 55.0,
            uvSkip = 8.0,
            capeThunderJkg = 1000.0
        )
        SportType.OUTDOOR_FITNESS -> SportThresholds(
            heatTempC = 29.0,
            coldTempC = 2.0,
            rainMm = 0.5,
            rainProbPct = 60,
            windKmh = 35.0,
            gustKmh = 50.0,
            uvSkip = 7.5
        )
        SportType.TEAM_SPORT -> SportThresholds(
            heatTempC = 30.0,
            coldTempC = -2.0,
            rainMm = 1.5,
            rainProbPct = 70,
            windKmh = 40.0,
            gustKmh = 58.0,
            uvSkip = 8.0
        )
        SportType.SWIMMING -> SportThresholds(
            heatTempC = 38.0,
            coldTempC = 18.0,
            rainMm = 2.0,
            rainProbPct = 75,
            windKmh = 30.0,
            gustKmh = 45.0,
            uvSkip = 8.0,
            capeThunderJkg = 1000.0
        )
        SportType.WINTER_SPORT -> SportThresholds(
            heatTempC = 8.0,
            coldTempC = -20.0,
            rainMm = 0.5,
            rainProbPct = 60,
            windKmh = 42.0,
            gustKmh = 65.0,
            uvSkip = 7.0,
            allowSnowWinter = true
        )
        SportType.WALKING -> SportThresholds(
            heatTempC = 33.0,
            coldTempC = -10.0,
            rainMm = 1.0,
            rainProbPct = 65,
            windKmh = 45.0,
            gustKmh = 65.0,
            uvSkip = 9.0
        )
    }
}

class SportPreferenceRepository(
    private val storage: KeyValueStore
) {
    fun getSelectedSport(): SportType =
        SportType.fromId(storage.getString(AppPrefsKeys.KEY_SELECTED_SPORT, null))

    fun setSelectedSport(sport: SportType) {
        storage.putString(AppPrefsKeys.KEY_SELECTED_SPORT, sport.id)
    }

    fun getAvailableSports(): List<SportType> = SportType.entries
}
