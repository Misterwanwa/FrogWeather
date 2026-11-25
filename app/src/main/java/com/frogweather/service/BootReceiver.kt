package com.frogweather.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.frogweather.briefing.BriefingScheduler
import com.frogweather.data.AppPrefsKeys
import com.frogweather.widget.WeatherWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        var pendingResult: PendingResult? = null
        val handled = handleIntentAction(action) {
            pendingResult = goAsync()
            val appCtx = context.applicationContext
            bootScope.launch {
                try {
                    BriefingScheduler.rescheduleAll(appCtx)
                    if (action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED) {
                        val prefs = appCtx.getSharedPreferences(AppPrefsKeys.PREFS_MAIN, Context.MODE_PRIVATE)
                        val lat = prefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble()
                        val lon = prefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
                        val hasValidCoords = lat.isFinite() && lon.isFinite() &&
                            !(abs(lat) < 1e-6 && abs(lon) < 1e-6)
                        if (hasValidCoords) {
                            WeatherNotificationService.startService(
                                context = appCtx,
                                lat = lat,
                                lon = lon,
                                fromBoot = true
                            )
                        }
                    }
                    withTimeoutOrNull(8_000L) {
                        WeatherWidgetProvider.updateAllWidgetsSuspend(appCtx)
                    }
                } finally {
                    pendingResult?.finish()
                }
            }
        }
        if (!handled) {
            pendingResult?.finish()
        }
    }

    companion object {
        private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
        const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
        const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"

        private val SUPPORTED_ACTIONS = setOf(
            ACTION_BOOT_COMPLETED,
            ACTION_MY_PACKAGE_REPLACED,
            ACTION_TIME_SET,
            ACTION_TIMEZONE_CHANGED
        )

        fun handleIntentAction(
            action: String?,
            onBootCompleted: () -> Unit
        ): Boolean {
            if (action == null || action !in SUPPORTED_ACTIONS) return false
            onBootCompleted()
            return true
        }
    }
}
