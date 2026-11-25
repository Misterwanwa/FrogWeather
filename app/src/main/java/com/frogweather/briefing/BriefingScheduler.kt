package com.frogweather.briefing

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.frogweather.WeatherType
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportPreferenceRepository
import com.frogweather.calendar.CalendarRepository
import com.frogweather.calendar.EventRescheduler
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.KeyValueStore
import com.frogweather.data.SharedPreferencesKeyValueStore
import com.frogweather.network.OpenMeteoClient
import com.frogweather.service.WeatherNotificationService
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class BriefingSlotSchedule(
    val hour: Int,
    val minute: Int,
    val enabled: Boolean
)

data class BriefingScheduleConfig(
    val morningEnabled: Boolean = true,
    val morningHour: Int = 7,
    val morningMinute: Int = 0,
    val eveningEnabled: Boolean = true,
    val eveningHour: Int = 20,
    val eveningMinute: Int = 30
)

data class BriefingSchedulePlan(
    val usesExactAlarm: Boolean,
    val usesInexactOrWorkManagerFallback: Boolean,
    val strategyLabel: String
)

object BriefingTimeCalculator {
    fun clampHour(hour: Int): Int = hour.coerceIn(0, 23)
    fun clampMinute(minute: Int): Int = minute.coerceIn(0, 59)

    fun computeNextTriggerMillis(
        hour: Int,
        minute: Int,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val clampedHour = clampHour(hour)
        val clampedMinute = clampMinute(minute)
        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var candidate = nowZdt
            .withHour(clampedHour)
            .withMinute(clampedMinute)
            .withSecond(0)
            .withNano(0)
        if (!candidate.toInstant().toEpochMilli().let { it > nowMillis }) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    fun computeDelayMillis(
        hour: Int,
        minute: Int,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val trigger = computeNextTriggerMillis(hour, minute, nowMillis, zoneId)
        return (trigger - nowMillis).coerceAtLeast(1_000L)
    }

    fun formatTime(hour: Int, minute: Int): String =
        String.format(Locale.GERMAN, "%02d:%02d", clampHour(hour), clampMinute(minute))
}

class BriefingScheduleRepository(private val storage: KeyValueStore) {

    fun getConfig(): BriefingScheduleConfig {
        val morningEnabled = storage.getString(AppPrefsKeys.KEY_BRIEFING_MORNING_ENABLED, null)
            ?.toBooleanStrictOrNull() ?: true
        val morningHour = storage.getString(AppPrefsKeys.KEY_BRIEFING_MORNING_HOUR, null)
            ?.toIntOrNull()?.let(BriefingTimeCalculator::clampHour) ?: 7
        val morningMinute = storage.getString(AppPrefsKeys.KEY_BRIEFING_MORNING_MINUTE, null)
            ?.toIntOrNull()?.let(BriefingTimeCalculator::clampMinute) ?: 0

        val eveningEnabled = storage.getString(AppPrefsKeys.KEY_BRIEFING_EVENING_ENABLED, null)
            ?.toBooleanStrictOrNull() ?: true
        val eveningHour = storage.getString(AppPrefsKeys.KEY_BRIEFING_EVENING_HOUR, null)
            ?.toIntOrNull()?.let(BriefingTimeCalculator::clampHour) ?: 20
        val eveningMinute = storage.getString(AppPrefsKeys.KEY_BRIEFING_EVENING_MINUTE, null)
            ?.toIntOrNull()?.let(BriefingTimeCalculator::clampMinute) ?: 30

        return BriefingScheduleConfig(
            morningEnabled = morningEnabled,
            morningHour = morningHour,
            morningMinute = morningMinute,
            eveningEnabled = eveningEnabled,
            eveningHour = eveningHour,
            eveningMinute = eveningMinute
        )
    }

    fun setMorningSchedule(enabled: Boolean, hour: Int, minute: Int): BriefingScheduleConfig {
        val clampedHour = BriefingTimeCalculator.clampHour(hour)
        val clampedMinute = BriefingTimeCalculator.clampMinute(minute)
        storage.putString(AppPrefsKeys.KEY_BRIEFING_MORNING_ENABLED, enabled.toString())
        storage.putString(AppPrefsKeys.KEY_BRIEFING_MORNING_HOUR, clampedHour.toString())
        storage.putString(AppPrefsKeys.KEY_BRIEFING_MORNING_MINUTE, clampedMinute.toString())
        return getConfig()
    }

    fun setEveningSchedule(enabled: Boolean, hour: Int, minute: Int): BriefingScheduleConfig {
        val clampedHour = BriefingTimeCalculator.clampHour(hour)
        val clampedMinute = BriefingTimeCalculator.clampMinute(minute)
        storage.putString(AppPrefsKeys.KEY_BRIEFING_EVENING_ENABLED, enabled.toString())
        storage.putString(AppPrefsKeys.KEY_BRIEFING_EVENING_HOUR, clampedHour.toString())
        storage.putString(AppPrefsKeys.KEY_BRIEFING_EVENING_MINUTE, clampedMinute.toString())
        return getConfig()
    }
}

class BriefingScheduler(private val storage: KeyValueStore) {

    private val repo = BriefingScheduleRepository(storage)

    fun getMorningSchedule(): BriefingSlotSchedule {
        val cfg = repo.getConfig()
        return BriefingSlotSchedule(
            hour = cfg.morningHour,
            minute = cfg.morningMinute,
            enabled = cfg.morningEnabled
        )
    }

    fun getEveningCheckSchedule(): BriefingSlotSchedule {
        val cfg = repo.getConfig()
        return BriefingSlotSchedule(
            hour = cfg.eveningHour,
            minute = cfg.eveningMinute,
            enabled = cfg.eveningEnabled
        )
    }

    fun setMorningSchedule(hour: Int, minute: Int, enabled: Boolean): BriefingSlotSchedule {
        val updated = repo.setMorningSchedule(enabled = enabled, hour = hour, minute = minute)
        return BriefingSlotSchedule(
            hour = updated.morningHour,
            minute = updated.morningMinute,
            enabled = updated.morningEnabled
        )
    }

    fun setEveningCheckSchedule(hour: Int, minute: Int, enabled: Boolean): BriefingSlotSchedule {
        val updated = repo.setEveningSchedule(enabled = enabled, hour = hour, minute = minute)
        return BriefingSlotSchedule(
            hour = updated.eveningHour,
            minute = updated.eveningMinute,
            enabled = updated.eveningEnabled
        )
    }

    fun getConfig(): BriefingScheduleConfig = repo.getConfig()

    fun planScheduleStrategy(canScheduleExactAlarms: Boolean): BriefingSchedulePlan {
        return if (canScheduleExactAlarms) {
            BriefingSchedulePlan(
                usesExactAlarm = true,
                usesInexactOrWorkManagerFallback = false,
                strategyLabel = "Exakter Alarm + WorkManager-Backup"
            )
        } else {
            BriefingSchedulePlan(
                usesExactAlarm = false,
                usesInexactOrWorkManagerFallback = true,
                strategyLabel = "Inexakter Alarm + WorkManager-Fallback"
            )
        }
    }

    companion object {
        private const val WORK_MORNING = "frog_morning_briefing"
        private const val WORK_EVENING = "frog_evening_briefing"
        private const val WORK_IMMEDIATE = "frog_immediate_briefing"
        internal const val EXTRA_BRIEFING_TYPE = "extra_briefing_type"
        internal const val EXTRA_BYPASS_DEDUP = "extra_bypass_dedup"
        private const val KEY_LAST_SENT_PREFIX = "last_sent_date_"
        private const val REQ_CODE_MORNING = 4101
        private const val REQ_CODE_EVENING = 4102

        fun rescheduleAll(context: Context) {
            scheduleAll(context)
        }

        fun scheduleAll(context: Context) {
            val appCtx = context.applicationContext
            val scheduler = BriefingScheduler(
                SharedPreferencesKeyValueStore.forPrefs(appCtx, AppPrefsKeys.PREFS_BRIEFING)
            )
            val cfg = scheduler.getConfig()
            val workManager = WorkManager.getInstance(appCtx)
            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val nowMillis = System.currentTimeMillis()

            scheduleSlot(
                context = appCtx,
                workManager = workManager,
                alarmManager = alarmManager,
                workName = WORK_MORNING,
                requestCode = REQ_CODE_MORNING,
                type = BriefingType.MORNING,
                enabled = cfg.morningEnabled,
                hour = cfg.morningHour,
                minute = cfg.morningMinute,
                nowMillis = nowMillis,
                scheduler = scheduler
            )
            scheduleSlot(
                context = appCtx,
                workManager = workManager,
                alarmManager = alarmManager,
                workName = WORK_EVENING,
                requestCode = REQ_CODE_EVENING,
                type = BriefingType.EVENING,
                enabled = cfg.eveningEnabled,
                hour = cfg.eveningHour,
                minute = cfg.eveningMinute,
                nowMillis = nowMillis,
                scheduler = scheduler
            )
        }

        @JvmOverloads
        fun triggerImmediateBriefing(
            context: Context,
            type: BriefingType = BriefingType.MORNING,
            bypassDedup: Boolean = true
        ) {
            val appCtx = context.applicationContext
            val request = OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInputData(
                    workDataOf(
                        EXTRA_BRIEFING_TYPE to type.name,
                        EXTRA_BYPASS_DEDUP to bypassDedup
                    )
                )
                .build()
            WorkManager.getInstance(appCtx).enqueueUniqueWork(
                WORK_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun scheduleSlot(
            context: Context,
            workManager: WorkManager,
            alarmManager: AlarmManager?,
            workName: String,
            requestCode: Int,
            type: BriefingType,
            enabled: Boolean,
            hour: Int,
            minute: Int,
            nowMillis: Long,
            scheduler: BriefingScheduler
        ) {
            val pendingIntent = buildAlarmPendingIntent(context, requestCode, type)
            if (!enabled) {
                workManager.cancelUniqueWork(workName)
                alarmManager?.cancel(pendingIntent)
                return
            }

            val delayMillis = BriefingTimeCalculator.computeDelayMillis(hour, minute, nowMillis)
            val periodicRequest = PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(EXTRA_BRIEFING_TYPE to type.name))
                .build()
            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )

            if (alarmManager != null) {
                val triggerAt = BriefingTimeCalculator.computeNextTriggerMillis(hour, minute, nowMillis)
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
                val plan = scheduler.planScheduleStrategy(canExact)
                if (plan.usesExactAlarm) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    } catch (_: SecurityException) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            }
        }

        private fun buildAlarmPendingIntent(
            context: Context,
            requestCode: Int,
            type: BriefingType
        ): PendingIntent {
            val intent = Intent(context, BriefingAlarmReceiver::class.java).apply {
                putExtra(EXTRA_BRIEFING_TYPE, type.name)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

class BriefingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val typeName = intent?.getStringExtra(BriefingScheduler.EXTRA_BRIEFING_TYPE)
        val type = BriefingType.entries.firstOrNull { it.name == typeName } ?: BriefingType.MORNING
        BriefingScheduler.triggerImmediateBriefing(context, type, bypassDedup = false)
        // Re-arm next day's alarm & periodic worker
        BriefingScheduler.scheduleAll(context)
    }
}

class BriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val typeName = inputData.getString(BriefingScheduler.EXTRA_BRIEFING_TYPE)
        val type = BriefingType.entries.firstOrNull { it.name == typeName } ?: BriefingType.MORNING
        val bypassDedup = inputData.getBoolean(BriefingScheduler.EXTRA_BYPASS_DEDUP, false)

        val nowMillis = System.currentTimeMillis()
        val todayStamp = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val briefingPrefs = ctx.getSharedPreferences(AppPrefsKeys.PREFS_BRIEFING, Context.MODE_PRIVATE)
        val lastSentKey = "last_sent_date_${type.name}"
        if (!bypassDedup && briefingPrefs.getString(lastSentKey, null) == todayStamp) {
            return Result.success()
        }

        val mainPrefs = ctx.getSharedPreferences(AppPrefsKeys.PREFS_MAIN, Context.MODE_PRIVATE)
        val lat = mainPrefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble()
        val lon = mainPrefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        val cityName = mainPrefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: "Aktueller Ort"
        val cachedTemp = if (mainPrefs.contains(AppPrefsKeys.KEY_LAST_WEATHER_TEMP)) {
            mainPrefs.getFloat(AppPrefsKeys.KEY_LAST_WEATHER_TEMP, 15f).toDouble()
                .takeIf { it.isFinite() } ?: 15.0
        } else {
            15.0
        }
        val cachedCondition = mainPrefs.getString(AppPrefsKeys.KEY_LAST_WEATHER_CONDITION, null)
            ?: WeatherType.OTHER.label

        val sportRepo = SportPreferenceRepository(
            SharedPreferencesKeyValueStore.forPrefs(ctx, AppPrefsKeys.PREFS_SPORT)
        )
        val sport = sportRepo.getSelectedSport()

        val hasValidCoords = lat.isFinite() && lon.isFinite() && !(abs(lat) < 1e-6 && abs(lon) < 1e-6)
        val forecastData = if (hasValidCoords) {
            try {
                OpenMeteoClient.fetchMultiModelForecast(lat, lon, forecastDays = 3)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val hourlyPoints = forecastData?.toHourlyWeatherPoints().orEmpty()
        val targetPoints = if (type == BriefingType.EVENING) {
            hourlyPoints.filter { it.epochMillis in nowMillis..(nowMillis + 36 * 3_600_000L) }
        } else {
            hourlyPoints.filter { it.epochMillis in (nowMillis - 3_600_000L)..(nowMillis + 24 * 3_600_000L) }
        }.ifEmpty { hourlyPoints.take(24) }

        val finiteTemps = targetPoints.map { it.tempC }.filter { it.isFinite() }
        val representativeTemp = targetPoints.firstOrNull { it.tempC.isFinite() }?.tempC ?: cachedTemp
        val minTemp = finiteTemps.minOrNull() ?: representativeTemp
        val maxTemp = finiteTemps.maxOrNull() ?: representativeTemp
        val maxProb = targetPoints.maxOfOrNull { it.precipProb.coerceIn(0, 100) } ?: 15
        val maxWind = targetPoints.map { it.windKmh }.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 10.0
        val maxUv = targetPoints.map { it.uvIndex }.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 2.0
        val peakPrecipMm = targetPoints.map { it.precipMm }.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 0.0

        val clothing = ClothingAdvisor.recommendClothing(
            tempC = representativeTemp,
            feelsLikeC = representativeTemp,
            precipProb = maxProb,
            windKmh = maxWind,
            uvIndex = maxUv,
            precipMm = peakPrecipMm
        )
        val bestWindow = if (targetPoints.isNotEmpty()) {
            SportAdvisor.findBestSportWindow(sport, targetPoints, forecastData?.utcOffsetSeconds)
        } else {
            null
        }

        val calendarResult = CalendarRepository.queryUpcomingEvents(ctx, startMillis = nowMillis, daysAhead = 3)
        val reschedules = if (calendarResult.permissionGranted && targetPoints.isNotEmpty()) {
            EventRescheduler.suggestReschedules(calendarResult.events, targetPoints, sport, nowMillis)
        } else {
            emptyList()
        }
        val calendarWarnings = when {
            !calendarResult.permissionGranted ->
                listOf("Kalenderberechtigung noch nicht aktiv – im Planer prüfen.")
            reschedules.isNotEmpty() ->
                reschedules.map { "${it.eventTitle}: ${it.message}" }
            else -> emptyList()
        }

        val dailySummary = if (targetPoints.isNotEmpty()) {
            String.format(
                Locale.GERMAN,
                "%s: %.0f–%.0f °C, Regenrisiko max. %d %%, Wind bis %.0f km/h",
                cityName,
                minTemp,
                maxTemp,
                maxProb,
                maxWind
            )
        } else {
            "$cityName: ${cachedTemp.toInt()} °C ($cachedCondition)"
        }

        val utcOffsetSeconds = forecastData?.utcOffsetSeconds ?: 0
        val dateLabel = if (forecastData != null) {
            val offset = try {
                ZoneOffset.ofTotalSeconds(utcOffsetSeconds)
            } catch (_: Exception) {
                ZoneOffset.UTC
            }
            Instant.ofEpochMilli(nowMillis).atOffset(offset).format(
                DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
            )
        } else {
            ZonedDateTime.now().format(
                DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
            )
        }
        val composed = BriefingComposer.composeBriefing(
            dateLabel = dateLabel,
            dailySummary = dailySummary,
            clothing = clothing,
            bestSportWindow = bestWindow,
            calendarWarnings = calendarWarnings,
            routeCheck = mainPrefs.getString(AppPrefsKeys.KEY_LAST_WEATHER_HINT, null),
            briefingType = type,
            utcOffsetSeconds = utcOffsetSeconds
        )

        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (canNotify) {
            WeatherNotificationService.showBriefingNotification(
                context = ctx,
                title = composed.notification.title,
                summary = composed.notification.shortText,
                fullText = composed.notification.bigText,
                notificationId = if (type == BriefingType.MORNING) 1002 else 1003
            )
            briefingPrefs.edit().putString(lastSentKey, todayStamp).apply()
        }

        return Result.success()
    }
}
