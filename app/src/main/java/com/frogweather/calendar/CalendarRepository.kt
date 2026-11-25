package com.frogweather.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class CalendarEvent(
    val id: Long,
    val title: String,
    val description: String,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val calendarDisplayName: String? = null,
    val accountName: String? = null,
    val accountType: String? = null
)

data class ResolvedEventLocation(
    val cityName: String,
    val latitude: Double,
    val longitude: Double,
    val usedFallback: Boolean
)

data class CalendarQueryResult(
    val permissionGranted: Boolean,
    val events: List<CalendarEvent>,
    val errorMessage: String? = null
)

object CalendarRepository {

    private const val ONE_DAY_MILLIS = 86_400_000L

    private val VIRTUAL_URL_REGEX = Regex(
        """(?:https?://|www\.)|(?:zoom\.us|meet\.google\.com|teams\.microsoft\.com|webex\.com)""",
        RegexOption.IGNORE_CASE
    )
    private val VIRTUAL_KEYWORD_REGEX = Regex(
        """\b(?:online|zoom|microsoft\s+teams|ms\s+teams|teams[- ]?(?:meeting|call|video|link|raum|chat)|google\s+meet|webex|skype|discord|remote|homeoffice|home\s+office|virtuell|video-?call|telefonkonferenz)\b|^\s*teams\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun handleQueryResult(
        hasReadPermission: Boolean,
        queriedEvents: List<CalendarEvent>,
        errorMessage: String? = null
    ): CalendarQueryResult {
        if (!hasReadPermission) {
            return CalendarQueryResult(
                permissionGranted = false,
                events = emptyList(),
                errorMessage = errorMessage ?: "Kalenderberechtigung (READ_CALENDAR) nicht erteilt."
            )
        }
        return CalendarQueryResult(
            permissionGranted = true,
            events = queriedEvents.sortedBy { it.startMillis },
            errorMessage = errorMessage
        )
    }

    fun resolveEventLocation(
        event: CalendarEvent,
        fallbackCityName: String,
        fallbackLat: Double,
        fallbackLon: Double
    ): ResolvedEventLocation {
        val rawLocation = event.location?.trim().orEmpty()
        val isVirtual = rawLocation.isNotEmpty() && (
            VIRTUAL_URL_REGEX.containsMatchIn(rawLocation) ||
                VIRTUAL_KEYWORD_REGEX.containsMatchIn(rawLocation)
            )
        return if (rawLocation.isBlank() || isVirtual) {
            ResolvedEventLocation(
                cityName = fallbackCityName.ifBlank { "Aktueller Ort" },
                latitude = fallbackLat,
                longitude = fallbackLon,
                usedFallback = true
            )
        } else {
            ResolvedEventLocation(
                cityName = rawLocation,
                latitude = fallbackLat,
                longitude = fallbackLon,
                usedFallback = false
            )
        }
    }

    fun normalizeInstanceTimes(
        beginMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, Long> {
        if (!allDay) {
            val safeEnd = maxOf(beginMillis, endMillis)
            return beginMillis to safeEnd
        }

        // Android CalendarContract stores all-day BEGIN/END in UTC midnight.
        val startUtcDate = Instant.ofEpochMilli(beginMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        val localStart = startUtcDate.atStartOfDay(localZone).toInstant().toEpochMilli()

        val days = if (endMillis > beginMillis) {
            val endUtcDate = Instant.ofEpochMilli(endMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
            ChronoUnit.DAYS.between(startUtcDate, endUtcDate).coerceAtLeast(1L)
        } else {
            1L
        }
        val localEnd = startUtcDate.plusDays(days).atStartOfDay(localZone).toInstant().toEpochMilli()
        return localStart to localEnd
    }

    fun parseInstanceRow(
        eventId: Long,
        title: String?,
        description: String?,
        location: String?,
        beginMillis: Long,
        endMillis: Long,
        allDayInt: Int,
        calendarDisplayName: String? = null,
        accountName: String? = null,
        accountType: String? = null,
        localZone: ZoneId = ZoneId.systemDefault()
    ): CalendarEvent {
        val isAllDay = allDayInt != 0
        val (normStart, normEnd) = normalizeInstanceTimes(
            beginMillis = beginMillis,
            endMillis = endMillis,
            allDay = isAllDay,
            localZone = localZone
        )
        return CalendarEvent(
            id = eventId,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "(Ohne Titel)",
            description = description?.trim().orEmpty(),
            location = location?.trim()?.takeIf { it.isNotEmpty() },
            startMillis = normStart,
            endMillis = normEnd,
            allDay = isAllDay,
            calendarDisplayName = calendarDisplayName?.trim()?.takeIf { it.isNotEmpty() },
            accountName = accountName?.trim()?.takeIf { it.isNotEmpty() },
            accountType = accountType?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun queryUpcomingEvents(
        context: Context,
        startMillis: Long = System.currentTimeMillis(),
        daysAhead: Int = 5
    ): CalendarQueryResult {
        if (!hasCalendarPermission(context)) {
            return handleQueryResult(hasReadPermission = false, queriedEvents = emptyList())
        }

        val clampedDays = daysAhead.coerceIn(1, 14)
        val endMillis = startMillis + clampedDays * ONE_DAY_MILLIS

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)
        val instancesUri = builder.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )

        // Query all synced calendars (Google, Samsung, Outlook, Exchange, CalDAV, local) without filtering ACCOUNT_TYPE.
        val selection = "${CalendarContract.Instances.VISIBLE} = 1"
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        return try {
            val events = mutableListOf<CalendarEvent>()
            context.contentResolver.query(
                instancesUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
                val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val calNameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                val accNameIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val accTypeIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)

                while (cursor.moveToNext()) {
                    events.add(
                        parseInstanceRow(
                            eventId = cursor.getLong(idIdx),
                            title = cursor.getString(titleIdx),
                            description = cursor.getString(descIdx),
                            location = cursor.getString(locIdx),
                            beginMillis = cursor.getLong(beginIdx),
                            endMillis = cursor.getLong(endIdx),
                            allDayInt = cursor.getInt(allDayIdx),
                            calendarDisplayName = cursor.getString(calNameIdx),
                            accountName = if (accNameIdx >= 0) cursor.getString(accNameIdx) else null,
                            accountType = if (accTypeIdx >= 0) cursor.getString(accTypeIdx) else null
                        )
                    )
                }
            }
            handleQueryResult(hasReadPermission = true, queriedEvents = events)
        } catch (_: SecurityException) {
            handleQueryResult(
                hasReadPermission = false,
                queriedEvents = emptyList(),
                errorMessage = "Zugriff auf Kalender wurde vom System verweigert."
            )
        } catch (e: Exception) {
            handleQueryResult(
                hasReadPermission = true,
                queriedEvents = emptyList(),
                errorMessage = "Kalender konnte nicht gelesen werden: ${e.message ?: "Unbekannter Fehler"}"
            )
        }
    }
}
