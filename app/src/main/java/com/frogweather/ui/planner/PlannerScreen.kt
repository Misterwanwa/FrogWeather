package com.frogweather.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.analysis.SportType
import com.frogweather.assistant.AssistantAnswer
import com.frogweather.briefing.BriefingScheduleConfig
import com.frogweather.briefing.BriefingTimeCalculator
import com.frogweather.briefing.ComposedBriefing
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.EventRescheduler
import com.frogweather.calendar.RescheduleSuggestion
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherResult
import com.frogweather.ui.theme.FrogTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ColorStatusOk = Color(0xFF81C784)
private val ColorStatusWarn = Color(0xFFFFB74D)
private val ColorStatusAlert = Color(0xFFFF9E96)

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm", Locale.GERMAN)
private val SHORT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

@Composable
fun PlannerScreen(
    cityName: String?,
    country: String?,
    latitude: Double?,
    longitude: Double?,
    selectedSport: SportType,
    briefing: ComposedBriefing,
    briefingSchedule: BriefingScheduleConfig,
    hasCalendarPermission: Boolean,
    calendarEvents: List<CalendarEvent>,
    calendarError: String?,
    rescheduleSuggestions: List<RescheduleSuggestion>,
    routeOriginInput: String,
    routeDestinationInput: String,
    routeMode: RouteTransportMode,
    routeArrivalOffsetHours: Int,
    routeResult: RouteWeatherResult?,
    routeLoading: Boolean,
    routeError: String?,
    assistantAnswer: AssistantAnswer?,
    onAskAssistant: (String) -> Unit,
    onUpdateMorningSchedule: (enabled: Boolean, hour: Int, minute: Int) -> Unit,
    onUpdateEveningSchedule: (enabled: Boolean, hour: Int, minute: Int) -> Unit,
    onTriggerTestBriefing: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRefreshCalendar: () -> Unit,
    onRouteOriginChange: (String) -> Unit,
    onRouteDestinationChange: (String) -> Unit,
    onRouteModeChange: (RouteTransportMode) -> Unit,
    onRouteArrivalOffsetChange: (Int) -> Unit,
    onCalculateRoute: () -> Unit,
    onOpenInMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    val scrollState = rememberScrollState()
    var queryText by remember { mutableStateOf("") }

    val placeLabel = listOfNotNull(
        cityName?.takeIf { it.isNotBlank() },
        country?.takeIf { it.isNotBlank() }
    ).joinToString(", ").ifBlank { "Kein Standort gewählt" }

    val coordSuffix = if (latitude != null && longitude != null) {
        " (${String.format(Locale.GERMAN, "%.2f°, %.2f°", latitude, longitude)})"
    } else {
        ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header
        PlannerCard {
            Text(
                text = "Tages- & Routenplaner",
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Standort: $placeLabel$coordSuffix • Sport: ${selectedSport.displayName}",
                color = palette.accent,
                fontSize = 12.sp
            )
        }

        // 2. Local German Free-Text Assistant (100% offline, no AI/API keys)
        PlannerCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Lokaler Freitext-Assistent",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "100 % lokal auf Deutsch ohne KI-Cloud oder API-Key.",
                color = palette.textSecondary,
                fontSize = 12.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = {
                        Text(
                            text = "z. B. Was soll ich heute anziehen?",
                            color = palette.textSecondary,
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    colors = plannerTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (queryText.isNotBlank()) onAskAssistant(queryText)
                        }
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onAskAssistant(queryText) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = palette.accent,
                        contentColor = palette.screenBackground
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Fragen",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val quickPrompts = listOf(
                "Was soll ich anziehen?",
                "Bestes Sportfenster?",
                "Termine prüfen",
                "Route nach Potsdam",
                "Wie wird das Wetter heute?"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPrompts.forEach { sample ->
                    PlannerChip(
                        label = sample,
                        selected = false,
                        onClick = {
                            queryText = sample
                            onAskAssistant(sample)
                        }
                    )
                }
            }

            if (assistantAnswer != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.tileSurfaceElevated)
                        .border(1.dp, palette.accent, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${assistantAnswer.title} (${assistantAnswer.intent.category.displayLabel})",
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = assistantAnswer.body,
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // 3. Morning & Evening Briefing Card + Schedule Settings
        PlannerCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Morgen- & Abend-Briefing",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onTriggerTestBriefing,
                    modifier = Modifier.heightIn(min = 36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = palette.tileSurfaceElevated,
                        contentColor = palette.accent
                    )
                ) {
                    Text(
                        text = "Jetzt testen",
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // All 5 mandatory components
            briefing.card.items.forEach { item ->
                val border = if (item.isAlert) ColorStatusWarn else palette.dividerColor
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.tileSurfaceElevated)
                        .border(1.dp, border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.label,
                        color = if (item.isAlert) ColorStatusWarn else palette.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = item.text,
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Divider(color = palette.dividerColor)

            ScheduleControlRow(
                label = "Morgen-Briefing",
                enabled = briefingSchedule.morningEnabled,
                hour = briefingSchedule.morningHour,
                minute = briefingSchedule.morningMinute,
                onUpdate = onUpdateMorningSchedule
            )
            ScheduleControlRow(
                label = "Abend-Check (nächster Tag)",
                enabled = briefingSchedule.eveningEnabled,
                hour = briefingSchedule.eveningHour,
                minute = briefingSchedule.eveningMinute,
                onUpdate = onUpdateEveningSchedule
            )
        }

        // 4. Calendar Integration & Auto-Rescheduling
        PlannerCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Kalender & Umplanung",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (hasCalendarPermission) {
                    IconButton(onClick = onRefreshCalendar) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Kalender aktualisieren",
                            tint = palette.accent
                        )
                    }
                }
            }

            if (!hasCalendarPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.tileSurfaceElevated)
                        .border(1.dp, ColorStatusWarn, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Kalenderberechtigung erforderlich, um synchronisierte Termine (Google, Samsung, Outlook) auf Wetterkonflikte zu prüfen.",
                        color = palette.textPrimary,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = onRequestCalendarPermission,
                        modifier = Modifier.heightIn(min = 40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = palette.accent,
                            contentColor = palette.screenBackground
                        )
                    ) {
                        Text(
                            text = "Kalender freigeben",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                if (!calendarError.isNullOrBlank()) {
                    Text(
                        text = calendarError,
                        color = ColorStatusWarn,
                        fontSize = 12.sp
                    )
                }

                if (rescheduleSuggestions.isNotEmpty()) {
                    Text(
                        text = "Wetterkritische Termine & Umplanungsvorschläge (${rescheduleSuggestions.size})",
                        color = ColorStatusWarn,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    rescheduleSuggestions.forEach { suggestion ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tileSurfaceElevated)
                                .border(1.dp, ColorStatusAlert, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = ColorStatusAlert,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = suggestion.eventTitle,
                                    color = palette.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = suggestion.message,
                                color = palette.textPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Keine Wetterkonflikte bei kommenden Outdoor-Terminen erkannt.",
                        color = ColorStatusOk,
                        fontSize = 13.sp
                    )
                }

                if (calendarEvents.isEmpty()) {
                    Text(
                        text = "Keine anstehenden Termine in den nächsten 5 Tagen gefunden.",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        text = "Nächste Termine (${calendarEvents.size})",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    calendarEvents.take(8).forEach { event ->
                        val sensitivity = remember(event.id, event.title, event.description, event.location) {
                            EventRescheduler.classifySensitivity(event.title, event.description, event.location)
                        }
                        val timeText = Instant.ofEpochMilli(event.startMillis)
                            .atZone(ZoneId.systemDefault())
                            .format(TIME_FORMATTER)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tileSurfaceElevated)
                                .border(
                                    1.dp,
                                    if (sensitivity.isWeatherSensitive) palette.accent else palette.dividerColor,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.title,
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = buildList {
                                    add(if (event.allDay) "$timeText (Ganztägig)" else timeText)
                                    event.location?.let { add(it) }
                                    event.calendarDisplayName?.let { add(it) }
                                }.joinToString(" • ")
                                Text(
                                    text = meta,
                                    color = palette.textSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.tileSurface)
                                    .border(
                                        1.dp,
                                        if (sensitivity.isWeatherSensitive) palette.accent else palette.dividerColor,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (sensitivity.isWeatherSensitive) "Outdoor" else "Indoor",
                                    color = if (sensitivity.isWeatherSensitive) palette.accent else palette.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Route Weather & Departure Calculator
        PlannerCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Directions,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Routenwetter & Abfahrtsrechner",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedTextField(
                value = routeOriginInput,
                onValueChange = onRouteOriginChange,
                label = { Text("Start (leer = ${cityName ?: "aktueller Ort"})", color = palette.textSecondary) },
                singleLine = true,
                colors = plannerTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = routeDestinationInput,
                onValueChange = onRouteDestinationChange,
                label = { Text("Zielort (z. B. Potsdam, München Hbf)", color = palette.textSecondary) },
                singleLine = true,
                colors = plannerTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCalculateRoute() }),
                modifier = Modifier.fillMaxWidth()
            )

            // Transport mode selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteTransportMode.entries.forEach { mode ->
                    PlannerChip(
                        label = mode.displayName,
                        selected = mode == routeMode,
                        onClick = { onRouteModeChange(mode) }
                    )
                }
            }

            // Arrival offset selector
            Text(
                text = "Gewünschte Ankunft:",
                color = palette.textSecondary,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 2, 3, 5, 8, 12).forEach { hoursAhead ->
                    PlannerChip(
                        label = "In $hoursAhead Std.",
                        selected = hoursAhead == routeArrivalOffsetHours,
                        onClick = { onRouteArrivalOffsetChange(hoursAhead) }
                    )
                }
            }

            Button(
                onClick = onCalculateRoute,
                enabled = !routeLoading && routeDestinationInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = palette.accent,
                    contentColor = palette.screenBackground,
                    disabledBackgroundColor = palette.tileSurfaceElevated,
                    disabledContentColor = palette.textSecondary
                )
            ) {
                if (routeLoading) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Route wird berechnet...")
                } else {
                    Text(
                        text = "Route & Abfahrt berechnen",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!routeError.isNullOrBlank()) {
                Text(
                    text = routeError,
                    color = ColorStatusAlert,
                    fontSize = 12.sp
                )
            }

            if (routeResult != null) {
                val depTime = Instant.ofEpochMilli(routeResult.recommendedDepartureMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(SHORT_TIME_FORMATTER)
                val border = if (routeResult.hasWeatherHazard) ColorStatusWarn else ColorStatusOk
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.tileSurfaceElevated)
                        .border(1.dp, border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${routeResult.origin} → ${routeResult.destination} (${routeResult.totalDurationMinutes} Min., ${routeResult.mode.displayName})",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Empfohlene Abfahrt: $depTime Uhr",
                        color = if (routeResult.hasWeatherHazard) ColorStatusWarn else ColorStatusOk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = routeResult.departureReason,
                        color = palette.textPrimary,
                        fontSize = 12.sp
                    )
                    routeResult.arrivalWeather?.let { arr ->
                        Text(
                            text = String.format(
                                Locale.GERMAN,
                                "Ankunftswetter: %.1f °C • Niederschlag %.1f mm (%d %%) • Wind %.0f km/h",
                                arr.tempC,
                                arr.precipMm,
                                arr.precipProb,
                                arr.windKmh
                            ),
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                    }

                    if (routeResult.segments.isNotEmpty()) {
                        Divider(color = palette.dividerColor)
                        routeResult.segments.forEach { seg ->
                            val segTime = Instant.ofEpochMilli(seg.timestampEpochMillis)
                                .atZone(ZoneId.systemDefault())
                                .format(SHORT_TIME_FORMATTER)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$segTime • ${seg.pointName}",
                                    color = if (seg.isHazardous) ColorStatusWarn else palette.textPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = String.format(
                                        Locale.GERMAN,
                                        "%.0f °C · %.1f mm · %.0f km/h",
                                        seg.tempC,
                                        seg.precipMm,
                                        seg.windKmh
                                    ),
                                    color = palette.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenInMaps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = palette.tileSurface,
                            contentColor = palette.accent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Navigation in Maps öffnen",
                            color = palette.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PlannerCard(
    content: @Composable () -> Unit
) {
    val palette = FrogTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.tileSurface)
            .border(1.dp, palette.dividerColor, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}

@Composable
private fun PlannerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = FrogTheme.palette
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) palette.accent else palette.tileSurfaceElevated)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.dividerColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) palette.screenBackground else palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ScheduleControlRow(
    label: String,
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onUpdate: (enabled: Boolean, hour: Int, minute: Int) -> Unit
) {
    val palette = FrogTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.tileSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${BriefingTimeCalculator.formatTime(hour, minute)} Uhr",
                    color = palette.accent,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onUpdate(it, hour, minute) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.accent,
                    checkedTrackColor = palette.accentSecondary
                )
            )
        }

        if (enabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepperMiniButton(
                    icon = Icons.Default.Remove,
                    desc = "Stunde minus",
                    onClick = {
                        val nextH = if (hour == 0) 23 else hour - 1
                        onUpdate(true, nextH, minute)
                    }
                )
                Text("Std: ${String.format(Locale.GERMAN, "%02d", hour)}", color = palette.textPrimary, fontSize = 12.sp)
                StepperMiniButton(
                    icon = Icons.Default.Add,
                    desc = "Stunde plus",
                    onClick = {
                        val nextH = (hour + 1) % 24
                        onUpdate(true, nextH, minute)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                StepperMiniButton(
                    icon = Icons.Default.Remove,
                    desc = "Minute minus",
                    onClick = {
                        val nextM = if (minute < 15) 45 else minute - 15
                        onUpdate(true, hour, nextM)
                    }
                )
                Text("Min: ${String.format(Locale.GERMAN, "%02d", minute)}", color = palette.textPrimary, fontSize = 12.sp)
                StepperMiniButton(
                    icon = Icons.Default.Add,
                    desc = "Minute plus",
                    onClick = {
                        val nextM = (minute + 15) % 60
                        onUpdate(true, hour, nextM)
                    }
                )
            }
        }
    }
}

@Composable
private fun StepperMiniButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    val palette = FrogTheme.palette
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.tileSurface)
            .border(1.dp, palette.dividerColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = palette.accent,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun plannerTextFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = FrogTheme.palette.textPrimary,
    cursorColor = FrogTheme.palette.accent,
    focusedBorderColor = FrogTheme.palette.accent,
    unfocusedBorderColor = FrogTheme.palette.dividerColor,
    focusedLabelColor = FrogTheme.palette.accent,
    unfocusedLabelColor = FrogTheme.palette.textSecondary,
    backgroundColor = FrogTheme.palette.tileSurfaceElevated
)
