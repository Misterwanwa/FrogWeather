package com.frogweather.briefing

import com.frogweather.analysis.SportWindow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class BriefingType(val label: String) {
    MORNING("Morgen-Briefing"),
    EVENING("Abend-Check")
}

data class BriefingNotificationPayload(
    val title: String,
    val shortText: String,
    val bigText: String
)

data class BriefingCardItem(
    val key: String,
    val label: String,
    val text: String,
    val isAlert: Boolean = false
)

data class BriefingCardData(
    val title: String,
    val subtitle: String,
    val items: List<BriefingCardItem>,
    val hasAlerts: Boolean
)

data class ComposedBriefing(
    val type: BriefingType,
    val dateLabel: String,
    val dailyWeatherText: String,
    val clothingText: String,
    val sportWindowText: String,
    val calendarWarningsText: String,
    val routeCheckText: String,
    val notification: BriefingNotificationPayload,
    val card: BriefingCardData
) {
    fun toNotificationBody(): String = notification.bigText
}

object BriefingComposer {

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

    fun composeBriefing(
        dateLabel: String,
        dailySummary: String,
        clothing: ClothingRecommendation,
        bestSportWindow: SportWindow?,
        calendarWarnings: List<String> = emptyList(),
        routeCheck: String? = null,
        briefingType: BriefingType = BriefingType.MORNING,
        utcOffsetSeconds: Int = 0
    ): ComposedBriefing {
        val safeDate = dateLabel.trim().ifBlank { "Heute" }
        val dailyWeatherText = dailySummary.trim()
            .ifBlank { "Tageswetter: Aktuelle Vorhersage wird geladen." }

        val clothingText = clothing.summary.trim()
            .ifBlank { clothing.headline.trim().ifBlank { "Bequeme Alltagskleidung empfohlen." } }

        val sportWindowText = if (bestSportWindow != null) {
            val offset = runCatching { ZoneOffset.ofTotalSeconds(utcOffsetSeconds) }.getOrDefault(ZoneOffset.UTC)
            val startStr = Instant.ofEpochMilli(bestSportWindow.startEpochMillis).atOffset(offset).format(TIME_FMT)
            val endStr = Instant.ofEpochMilli(bestSportWindow.endEpochMillis).atOffset(offset).format(TIME_FMT)
            val detail = bestSportWindow.summaryLabel.trim()
            if (detail.isNotBlank()) {
                "${bestSportWindow.sport.displayName}: $startStr–$endStr Uhr ($detail)"
            } else {
                "${bestSportWindow.sport.displayName}: $startStr–$endStr Uhr"
            }
        } else {
            "Kein ideales Outdoor-Sportfenster im Prognosezeitraum – ggf. Indoor-Einheit planen."
        }

        val cleanWarnings = calendarWarnings.map { it.trim() }.filter { it.isNotEmpty() }
        val hasCalendarAlert = cleanWarnings.isNotEmpty() && cleanWarnings.none {
            it.contains("keine wetterkritischen", ignoreCase = true)
        }
        val calendarWarningsText = when {
            cleanWarnings.isEmpty() -> "Keine wetterkritischen Kalendertermine erkannt."
            cleanWarnings.size <= 3 -> cleanWarnings.joinToString(" | ")
            else -> "${cleanWarnings.take(3).joinToString(" | ")} (+${cleanWarnings.size - 3} weitere)"
        }

        val routeCheckText = routeCheck?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Keine aktive Pendelstrecke – Start & Ziel im Planer prüfen."

        val notifTitle = "${briefingType.label} · $safeDate"
        val shortParts = buildList {
            add(dailyWeatherText.take(55))
            add(clothing.headline.take(45))
            if (hasCalendarAlert) add("Termin-Warnung")
        }
        val shortText = shortParts.joinToString(" • ").take(140)

        val bigText = buildString {
            appendLine("Wetter: $dailyWeatherText")
            appendLine("Kleidung: $clothingText")
            appendLine("Sport: $sportWindowText")
            appendLine("Kalender: $calendarWarningsText")
            append("Route: $routeCheckText")
        }

        val items = listOf(
            BriefingCardItem(
                key = "weather",
                label = "Tageswetter",
                text = dailyWeatherText,
                isAlert = false
            ),
            BriefingCardItem(
                key = "clothing",
                label = "Kleidung",
                text = clothingText,
                isAlert = clothing.needsRainGear || clothing.needsColdProtection
            ),
            BriefingCardItem(
                key = "sport",
                label = "Sportfenster",
                text = sportWindowText,
                isAlert = bestSportWindow == null
            ),
            BriefingCardItem(
                key = "calendar",
                label = "Kalender",
                text = calendarWarningsText,
                isAlert = hasCalendarAlert
            ),
            BriefingCardItem(
                key = "route",
                label = "Route & Pendeln",
                text = routeCheckText,
                isAlert = routeCheckText.contains("Warnung", ignoreCase = true)
            )
        )

        return ComposedBriefing(
            type = briefingType,
            dateLabel = safeDate,
            dailyWeatherText = dailyWeatherText,
            clothingText = clothingText,
            sportWindowText = sportWindowText,
            calendarWarningsText = calendarWarningsText,
            routeCheckText = routeCheckText,
            notification = BriefingNotificationPayload(
                title = notifTitle,
                shortText = shortText,
                bigText = bigText
            ),
            card = BriefingCardData(
                title = briefingType.label,
                subtitle = safeDate,
                items = items,
                hasAlerts = items.any { it.isAlert }
            )
        )
    }
}
