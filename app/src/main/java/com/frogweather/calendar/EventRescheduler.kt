package com.frogweather.calendar

import com.frogweather.analysis.HourSkipEvaluation
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

data class SensitivityClassification(
    val isWeatherSensitive: Boolean,
    val matchedKeywords: List<String> = emptyList(),
    val reason: String
)

data class RescheduleSuggestion(
    val eventId: Long,
    val eventTitle: String,
    val originalStartMillis: Long,
    val originalEndMillis: Long,
    val requiresReschedule: Boolean,
    val hasBetterSlot: Boolean,
    val suggestedStartMillis: Long?,
    val suggestedEndMillis: Long?,
    val matchedKeywords: List<String>,
    val reason: String,
    val message: String
)

object EventRescheduler {

    private const val ONE_HOUR_MILLIS = 3_600_000L
    private const val GAP_TOLERANCE_MILLIS = 5_400_000L

    private val STRONG_OUTDOOR_KEYWORDS = listOf(
        "outdoor", "draußen", "draussen", "im freien", "open air", "open-air", "freiluft",
        "picknick", "picnic", "grillen", "bbq", "barbecue",
        "laufrunde", "joggen", "jogging", "laufen", "running", "marathon",
        "wandern", "wanderung", "alpenwanderung", "hike", "hiking", "berg", "alpen",
        "radfahren", "radtour", "rennrad", "fahrrad", "cycling", "bike", "ausfahrt",
        "fußball", "fussball", "soccer", "tennis", "golf", "volleyball",
        "beach", "strand", "badesee", "freibad", "camping", "zelten", "klettern", "climbing",
        "biergarten", "terrasse", "gartenparty", "festival", "spazier", "walking",
        "ski", "snowboard", "schlitten", "rodeln", "sportplatz", "stadion",
        "tiergarten", "stadtpark", "schlosspark", "volkspark", "englischer garten", "park-workout"
    )

    private val GENERIC_OUTDOOR_KEYWORDS = listOf(
        "garten", "ausflug", "schwimmen", "training", "workout"
    )

    private val PARK_WORD_REGEX = Regex("""(?:^|[\s\-/(])[a-zäöüß]*park(?:$|[\s\-/),.!?:])""")
    private val SEE_WORD_REGEX = Regex("""(?:^|[\s\-/(])[a-zäöüß]*see(?:$|[\s\-/),.!?:])""")

    private val INDOOR_EXCLUSION_KEYWORDS = listOf(
        "zoom", "teams", "webex", "google meet", "online", "remote",
        "video-call", "videocall", "video call", "telefonkonferenz", "webinar",
        "sprint planning", "sprint review", "standup", "architektur-meeting",
        "konferenzraum", "meetingraum", "sitzungszimmer", "büro", "buero", "office",
        "indoor", "drinnen", "hallenbad", "fitnessstudio", "gym", "sporthalle", "turnhalle",
        "kino", "cinema", "theater", "museum", "call"
    )

    private val PHYSICAL_INDOOR_MARKERS = setOf(
        "indoor", "drinnen", "fitnessstudio", "gym", "hallenbad", "sporthalle", "turnhalle"
    )

    private val EXPLICIT_OUTDOOR_OVERRIDE_MARKERS = setOf(
        "outdoor", "draußen", "draussen", "im freien", "open air", "open-air"
    )

    private val CALL_WORD_REGEX = Regex("""\bcall\b""")
    private val VIRTUAL_TEAMS_REGEX = Regex(
        """\b(?:microsoft\s+teams|ms\s+teams|teams[- ]?(?:meeting|call|video|link|raum|chat|besprechung))\b|^\s*teams\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm", Locale.GERMAN)

    fun classifySensitivity(
        title: String,
        description: String = "",
        location: String? = null
    ): SensitivityClassification {
        val combined = listOf(title, description, location.orEmpty())
            .joinToString(" ")
            .lowercase(Locale.GERMAN)
            .trim()

        if (combined.isBlank()) {
            return SensitivityClassification(
                isWeatherSensitive = false,
                matchedKeywords = emptyList(),
                reason = "Keine wetterrelevanten Begriffe erkannt"
            )
        }

        // Guard against false-positive "park" in "Parkplatz" / "Parkhaus" / "parken"
        val sanitizedForPark = combined
            .replace("parkplatz", "")
            .replace("parkhaus", "")
            .replace("parken", "")
            .replace("geparkt", "")

        val matchedStrongOutdoor = STRONG_OUTDOOR_KEYWORDS.filter { sanitizedForPark.contains(it) }
        val matchedGenericOutdoor = GENERIC_OUTDOOR_KEYWORDS.filter { sanitizedForPark.contains(it) }.toMutableList().apply {
            if (PARK_WORD_REGEX.containsMatchIn(sanitizedForPark)) add("park")
            if (SEE_WORD_REGEX.containsMatchIn(sanitizedForPark)) add("see")
        }
        val lowerTitle = title.lowercase(Locale.GERMAN).trim()
        val lowerDesc = description.lowercase(Locale.GERMAN).trim()
        val lowerLoc = location.orEmpty().lowercase(Locale.GERMAN).trim()
        val matchedIndoor = INDOOR_EXCLUSION_KEYWORDS.filter { kw ->
            when (kw) {
                "call" -> CALL_WORD_REGEX.containsMatchIn(combined)
                "teams" -> VIRTUAL_TEAMS_REGEX.containsMatchIn(combined) ||
                    VIRTUAL_TEAMS_REGEX.containsMatchIn(lowerTitle) ||
                    VIRTUAL_TEAMS_REGEX.containsMatchIn(lowerDesc) ||
                    VIRTUAL_TEAMS_REGEX.containsMatchIn(lowerLoc)
                else -> combined.contains(kw)
            }
        }

        val hasExplicitVirtual = matchedIndoor.any {
            it in setOf(
                "zoom", "teams", "webex", "google meet", "online", "remote",
                "video-call", "videocall", "video call", "webinar", "telefonkonferenz"
            )
        }
        val hasPhysicalIndoor = matchedIndoor.any { it in PHYSICAL_INDOOR_MARKERS }
        val hasExplicitOutdoorOverride = EXPLICIT_OUTDOOR_OVERRIDE_MARKERS.any { sanitizedForPark.contains(it) }

        if (matchedStrongOutdoor.isNotEmpty() && !hasExplicitVirtual && !(hasPhysicalIndoor && !hasExplicitOutdoorOverride)) {
            val matched = (matchedStrongOutdoor + matchedGenericOutdoor).distinct()
            return SensitivityClassification(
                isWeatherSensitive = true,
                matchedKeywords = matched,
                reason = "Wetterempfindlicher Outdoor-Termin (${matched.take(3).joinToString(", ")})"
            )
        }

        if (matchedIndoor.isNotEmpty()) {
            return SensitivityClassification(
                isWeatherSensitive = false,
                matchedKeywords = emptyList(),
                reason = "Innen-/Online-Termin erkannt (${matchedIndoor.take(2).joinToString(", ")})"
            )
        }

        if (matchedGenericOutdoor.isNotEmpty()) {
            return SensitivityClassification(
                isWeatherSensitive = true,
                matchedKeywords = matchedGenericOutdoor,
                reason = "Möglicher Outdoor-Termin (${matchedGenericOutdoor.take(3).joinToString(", ")})"
            )
        }

        return SensitivityClassification(
            isWeatherSensitive = false,
            matchedKeywords = emptyList(),
            reason = "Keine Outdoor-Schlüsselwörter erkannt"
        )
    }

    fun suggestReschedules(
        events: List<CalendarEvent>,
        hourlyPoints: List<HourlyWeatherPoint>,
        sport: SportType = SportType.WALKING,
        nowMillis: Long = System.currentTimeMillis()
    ): List<RescheduleSuggestion> {
        if (events.isEmpty() || hourlyPoints.isEmpty()) return emptyList()

        val verdict = SportAdvisor.evaluateHoursToSkip(sport, hourlyPoints)
        val futureEvaluations = verdict.allEvaluations.filter { eval ->
            val hStart = eval.hour.epochMillis
            hStart > Long.MAX_VALUE - ONE_HOUR_MILLIS || hStart + ONE_HOUR_MILLIS > nowMillis
        }

        val suggestions = mutableListOf<RescheduleSuggestion>()

        for (event in events) {
            val effectiveEnd = maxOf(event.startMillis, event.endMillis)
            if (effectiveEnd <= nowMillis) continue

            val sensitivity = classifySensitivity(event.title, event.description, event.location)
            if (!sensitivity.isWeatherSensitive) continue

            val evalStart = maxOf(event.startMillis, nowMillis)
            val overlapping = verdict.allEvaluations.filter { eval ->
                val hStart = eval.hour.epochMillis
                val hEnd = if (hStart > Long.MAX_VALUE - ONE_HOUR_MILLIS) {
                    Long.MAX_VALUE
                } else {
                    hStart + ONE_HOUR_MILLIS
                }
                if (effectiveEnd == evalStart) {
                    absDiffULong(hStart, evalStart) <= ONE_HOUR_MILLIS.toULong()
                } else {
                    hStart < effectiveEnd && hEnd > evalStart
                }
            }

            val conflictHours = overlapping.filter { it.shouldSkip }
            if (conflictHours.isEmpty()) continue

            val conflictReasons = conflictHours
                .flatMap { it.reasonLabels }
                .distinct()
                .take(2)
                .joinToString(", ")
                .ifBlank { "Ungünstige Wetterbedingungen" }

            val rawDuration = if (event.startMillis < 0L && effectiveEnd > Long.MAX_VALUE + event.startMillis) {
                8 * ONE_HOUR_MILLIS
            } else {
                (effectiveEnd - event.startMillis).coerceAtLeast(ONE_HOUR_MILLIS)
            }
            val targetDuration = if (event.allDay) {
                minOf(rawDuration, 3 * ONE_HOUR_MILLIS)
            } else {
                rawDuration.coerceAtMost(8 * ONE_HOUR_MILLIS)
            }
            val neededHours = ceil(targetDuration.toDouble() / ONE_HOUR_MILLIS).toInt().coerceIn(1, 8)

            val zone = ZoneId.systemDefault()
            val eventLocalHour = Instant.ofEpochMilli(event.startMillis).atZone(zone).hour
            val preferDaytime = event.allDay || eventLocalHour in 7..21

            val candidateSelection = findBestCleanWindow(
                futureEvaluations = futureEvaluations,
                neededHours = neededHours,
                nowMillis = nowMillis,
                preferDaytime = preferDaytime,
                zone = zone
            )

            if (candidateSelection == null) {
                suggestions.add(
                    RescheduleSuggestion(
                        eventId = event.id,
                        eventTitle = event.title,
                        originalStartMillis = event.startMillis,
                        originalEndMillis = effectiveEnd,
                        requiresReschedule = true,
                        hasBetterSlot = false,
                        suggestedStartMillis = null,
                        suggestedEndMillis = null,
                        matchedKeywords = sensitivity.matchedKeywords,
                        reason = conflictReasons,
                        message = "Wetterwarnung ($conflictReasons) – kein besseres Zeitfenster im Vorhersagezeitraum verfügbar."
                    )
                )
            } else {
                val candidateWindow = candidateSelection.hours
                val isPartialFit = candidateWindow.size < neededHours
                val suggestedStart = candidateWindow.first().hour.epochMillis
                val rawSuggestedEnd = if (suggestedStart > Long.MAX_VALUE - targetDuration) {
                    Long.MAX_VALUE
                } else {
                    suggestedStart + targetDuration
                }
                val lastWindowStart = candidateWindow.last().hour.epochMillis
                val cleanWindowEnd = if (lastWindowStart > Long.MAX_VALUE - ONE_HOUR_MILLIS) {
                    Long.MAX_VALUE
                } else {
                    lastWindowStart + ONE_HOUR_MILLIS
                }
                val suggestedEnd = if (isPartialFit) {
                    minOf(rawSuggestedEnd, cleanWindowEnd)
                } else {
                    rawSuggestedEnd
                }
                val startLabel = formatSlotLabel(suggestedStart, event.startMillis, zone)
                val endLabel = Instant.ofEpochMilli(suggestedEnd).atZone(zone).format(TIME_FMT)
                val notes = buildList {
                    if (isPartialFit) add("gekürztes trockenes Fenster")
                    if (candidateSelection.isOvernightFallback) add("nur Nacht-Zeitfenster verfügbar")
                }
                val noteSuffix = if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else ""

                suggestions.add(
                    RescheduleSuggestion(
                        eventId = event.id,
                        eventTitle = event.title,
                        originalStartMillis = event.startMillis,
                        originalEndMillis = effectiveEnd,
                        requiresReschedule = true,
                        hasBetterSlot = true,
                        suggestedStartMillis = suggestedStart,
                        suggestedEndMillis = suggestedEnd,
                        matchedKeywords = sensitivity.matchedKeywords,
                        reason = conflictReasons,
                        message = "Wetterkonflikt ($conflictReasons) → besserer Termin: $startLabel–$endLabel Uhr$noteSuffix"
                    )
                )
            }
        }

        return suggestions
    }

    private data class CleanWindowSelection(
        val hours: List<HourSkipEvaluation>,
        val isOvernightFallback: Boolean
    )

    private fun findBestCleanWindow(
        futureEvaluations: List<HourSkipEvaluation>,
        neededHours: Int,
        nowMillis: Long,
        preferDaytime: Boolean,
        zone: ZoneId
    ): CleanWindowSelection? {
        val candidateHours = futureEvaluations.filter { it.hour.epochMillis >= nowMillis }
        if (candidateHours.isEmpty()) return null

        if (preferDaytime) {
            val daytimeHours = candidateHours.filter { eval ->
                val h = Instant.ofEpochMilli(eval.hour.epochMillis).atZone(zone).hour
                h in 7..21
            }
            val daytimeBest = pickBestRunSlice(buildCleanRuns(daytimeHours), neededHours)
            if (daytimeBest != null) {
                return CleanWindowSelection(hours = daytimeBest, isOvernightFallback = false)
            }
            val overnightBest = pickBestRunSlice(buildCleanRuns(candidateHours), neededHours) ?: return null
            return CleanWindowSelection(hours = overnightBest, isOvernightFallback = true)
        }

        val best = pickBestRunSlice(buildCleanRuns(candidateHours), neededHours) ?: return null
        return CleanWindowSelection(hours = best, isOvernightFallback = false)
    }

    private fun buildCleanRuns(candidateHours: List<HourSkipEvaluation>): List<List<HourSkipEvaluation>> {
        if (candidateHours.isEmpty()) return emptyList()
        val cleanRuns = mutableListOf<List<HourSkipEvaluation>>()
        var currentRun = mutableListOf<HourSkipEvaluation>()

        for (eval in candidateHours) {
            if (eval.shouldSkip) {
                if (currentRun.isNotEmpty()) {
                    cleanRuns.add(currentRun)
                    currentRun = mutableListOf()
                }
            } else {
                val prevMillis = currentRun.lastOrNull()?.hour?.epochMillis
                val currMillis = eval.hour.epochMillis
                if (prevMillis == null ||
                    (currMillis >= prevMillis &&
                        (prevMillis > Long.MAX_VALUE - GAP_TOLERANCE_MILLIS ||
                            currMillis <= prevMillis + GAP_TOLERANCE_MILLIS))
                ) {
                    currentRun.add(eval)
                } else {
                    cleanRuns.add(currentRun)
                    currentRun = mutableListOf(eval)
                }
            }
        }
        if (currentRun.isNotEmpty()) {
            cleanRuns.add(currentRun)
        }
        return cleanRuns
    }

    private fun pickBestRunSlice(
        cleanRuns: List<List<HourSkipEvaluation>>,
        neededHours: Int
    ): List<HourSkipEvaluation>? {
        if (cleanRuns.isEmpty()) return null

        // Prefer runs that accommodate the full duration; fall back to longest/cleanest run otherwise
        val fullFitRuns = cleanRuns.filter { it.size >= neededHours }
        val poolRuns = fullFitRuns.ifEmpty { cleanRuns }

        val bestRun = poolRuns.maxWithOrNull { a, b ->
            val rankA = runRankScore(a)
            val rankB = runRankScore(b)
            val cmp = rankA.compareTo(rankB)
            if (cmp != 0) cmp else b.first().hour.epochMillis.compareTo(a.first().hour.epochMillis)
        } ?: return null

        if (bestRun.size <= neededHours) return bestRun

        // Pick best sub-window of length neededHours inside the winning run
        var bestSlice: List<HourSkipEvaluation> = bestRun.take(neededHours)
        var bestSliceScore = Double.NEGATIVE_INFINITY
        for (startIdx in 0..(bestRun.size - neededHours)) {
            val slice = bestRun.subList(startIdx, startIdx + neededHours)
            val avg = slice.map { it.suitabilityScore }.average()
            // Bucket slightly so near-identical clean hours prefer earliest slot in the run
            val rounded = kotlin.math.floor(avg / 2.0) * 2.0
            if (rounded > bestSliceScore) {
                bestSliceScore = rounded
                bestSlice = slice
            }
        }
        return bestSlice
    }

    private fun runRankScore(run: List<HourSkipEvaluation>): Double {
        val avgScore = run.map { it.suitabilityScore }.average()
        val durationFactor = min(run.size, 6) / 6.0
        val lengthBonus = min(run.size, 12) * 1.5
        return avgScore * (0.65 + 0.35 * durationFactor) + lengthBonus
    }

    private fun absDiffULong(a: Long, b: Long): ULong =
        if (a >= b) a.toULong() - b.toULong() else b.toULong() - a.toULong()

    private fun formatSlotLabel(suggestedStart: Long, originalStart: Long, zone: ZoneId): String {
        val suggestedZdt = Instant.ofEpochMilli(suggestedStart).atZone(zone)
        val originalZdt = Instant.ofEpochMilli(originalStart).atZone(zone)
        return if (suggestedZdt.toLocalDate() == originalZdt.toLocalDate()) {
            suggestedZdt.format(TIME_FMT)
        } else {
            suggestedZdt.format(DATE_TIME_FMT)
        }
    }
}
