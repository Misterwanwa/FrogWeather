package com.frogweather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import com.frogweather.BuildConfig
import com.frogweather.MainActivity
import com.frogweather.R
import com.frogweather.WeatherType
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.CityImageRepository
import com.frogweather.data.KeyValueStore
import com.frogweather.data.SharedPreferencesKeyValueStore
import com.frogweather.data.WeatherScenario
import com.frogweather.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.math.abs

data class WidgetLocation(
    val latitude: Double,
    val longitude: Double,
    val cityName: String?,
    val country: String?
)

data class CachedWidgetWeather(
    val tempC: Double,
    val conditionLabel: String,
    val weatherType: WeatherType,
    val rawMain: String,
    val sunriseSec: Long?,
    val sunsetSec: Long?,
    val hint: String? = null
)

data class WidgetDisplayState(
    val cityText: String,
    val temperatureText: String,
    val emoji: String,
    val conditionText: String
)

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        val appCtx = context.applicationContext
        scope.launch {
            try {
                withTimeoutOrNull(8_000L) {
                    coroutineScope {
                        appWidgetIds.map { appWidgetId ->
                            async {
                                updateWidgetSuspend(appCtx, appWidgetManager, appWidgetId)
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        val pendingResult = goAsync()
        val appCtx = context.applicationContext
        scope.launch {
            try {
                withTimeoutOrNull(8_000L) {
                    updateWidgetSuspend(appCtx, appWidgetManager, appWidgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            for (id in appWidgetIds) {
                remove("lat_$id")
                remove("lon_$id")
                remove("city_$id")
            }
            apply()
        }
    }

    override fun onEnabled(context: Context) {
        // Widget added to home/lock screen
    }

    override fun onDisabled(context: Context) {
        // Last widget removed
    }

    companion object {
        private const val TAG = "WeatherWidget"
        private const val WIDGET_PREFS_NAME = "widget_prefs"
        const val REQUIRED_WIDGET_CATEGORY = "home_screen|keyguard"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun isValidCoordinatePair(lat: Double?, lon: Double?): Boolean {
            if (lat == null || lon == null) return false
            if (!lat.isFinite() || !lon.isFinite()) return false
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
            return !(abs(lat) < 1e-6 && abs(lon) < 1e-6)
        }

        fun resolveWidgetLocation(
            appWidgetId: Int,
            widgetStore: KeyValueStore,
            sharedAppStore: KeyValueStore
        ): WidgetLocation? {
            val wLat = readDoubleFromStore(widgetStore, "lat_$appWidgetId")
            val wLon = readDoubleFromStore(widgetStore, "lon_$appWidgetId")
            val wCity = widgetStore.getString("city_$appWidgetId", null)?.takeIf { it.isNotBlank() }

            if (isValidCoordinatePair(wLat, wLon)) {
                val sharedLat = readDoubleFromStore(sharedAppStore, AppPrefsKeys.KEY_LAST_CITY_LAT)
                val sharedLon = readDoubleFromStore(sharedAppStore, AppPrefsKeys.KEY_LAST_CITY_LON)
                val sharedCity = sharedAppStore.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)
                    ?.takeIf { it.isNotBlank() }
                val coordsClose = sharedLat != null && sharedLon != null &&
                    abs(wLat!! - sharedLat) <= 0.05 && abs(wLon!! - sharedLon) <= 0.05
                val cityMatches = wCity == null ||
                    (sharedCity != null && wCity.equals(sharedCity, ignoreCase = true))
                val matchesSharedCity = coordsClose && cityMatches
                return WidgetLocation(
                    latitude = wLat!!,
                    longitude = wLon!!,
                    cityName = wCity ?: if (matchesSharedCity) sharedCity else null,
                    country = if (matchesSharedCity) {
                        sharedAppStore.getString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, null)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                )
            }

            // Fallback to shared "frog_weather_prefs"
            val cityLat = readDoubleFromStore(sharedAppStore, AppPrefsKeys.KEY_LAST_CITY_LAT)
            val cityLon = readDoubleFromStore(sharedAppStore, AppPrefsKeys.KEY_LAST_CITY_LON)
            val cityName = sharedAppStore.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)?.takeIf { it.isNotBlank() }
            val country = sharedAppStore.getString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, null)?.takeIf { it.isNotBlank() }

            if (isValidCoordinatePair(cityLat, cityLon)) {
                return WidgetLocation(
                    latitude = cityLat!!,
                    longitude = cityLon!!,
                    cityName = cityName,
                    country = country
                )
            }
            return null
        }

        fun resolveLocalCityImageFilename(
            scenario: WeatherScenario,
            cityImages: Map<WeatherScenario, String>
        ): String? = cityImages[scenario]

        fun buildPlaceholderDisplayState(): WidgetDisplayState = WidgetDisplayState(
            cityText = "FrogWeather",
            temperatureText = "--°",
            emoji = "🐸",
            conditionText = ""
        )

        fun buildWidgetDisplayState(
            location: WidgetLocation?,
            cached: CachedWidgetWeather?,
            scenario: WeatherScenario? = null
        ): WidgetDisplayState {
            if (location == null) return buildPlaceholderDisplayState()
            val cityLabel = buildString {
                append(location.cityName?.takeIf { it.isNotBlank() } ?: "Standort")
                val c = location.country?.takeIf { it.isNotBlank() }
                if (c != null) {
                    append(", ")
                    append(c)
                }
            }
            return if (cached != null && cached.tempC.isFinite()) {
                WidgetDisplayState(
                    cityText = cityLabel,
                    temperatureText = "${cached.tempC.toInt()}°",
                    emoji = scenario?.emoji ?: cached.weatherType.emoji,
                    conditionText = cached.conditionLabel.ifBlank { cached.weatherType.label }
                )
            } else {
                WidgetDisplayState(
                    cityText = cityLabel,
                    temperatureText = "--°",
                    emoji = scenario?.emoji ?: "🐸",
                    conditionText = "Wird aktualisiert..."
                )
            }
        }

        fun updateAllWidgets(context: Context) {
            val appCtx = context.applicationContext
            scope.launch {
                updateAllWidgetsSuspend(appCtx)
            }
        }

        suspend fun updateAllWidgetsSuspend(context: Context) {
            val appCtx = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appCtx)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(appCtx, WeatherWidgetProvider::class.java)
            )
            if (widgetIds.isEmpty()) return
            coroutineScope {
                widgetIds.map { widgetId ->
                    async {
                        updateWidgetSuspend(appCtx, appWidgetManager, widgetId)
                    }
                }.awaitAll()
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val appCtx = context.applicationContext
            scope.launch {
                updateWidgetSuspend(appCtx, appWidgetManager, appWidgetId)
            }
        }

        private suspend fun updateWidgetSuspend(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val widgetStore = SharedPreferencesKeyValueStore.forPrefs(context, WIDGET_PREFS_NAME)
            val sharedStore = SharedPreferencesKeyValueStore.forPrefs(context, AppPrefsKeys.PREFS_MAIN)
            val mainPrefs = context.getSharedPreferences(AppPrefsKeys.PREFS_MAIN, Context.MODE_PRIVATE)

            val location = resolveWidgetLocation(appWidgetId, widgetStore, sharedStore)
            val cached = readCachedWeather(mainPrefs)

            // 1. Render cached/placeholder state immediately (supports offline & boot)
            val cachedScenario = cached?.let { resolveScenario(it) }
            val initialDisplay = buildWidgetDisplayState(location, cached, cachedScenario)
            val initialBitmap = if (location != null && cachedScenario != null) {
                CityImageRepository.getInstance(context).findBestImageForWeather(
                    cityName = location.cityName,
                    lat = location.latitude,
                    lon = location.longitude,
                    scenario = cachedScenario,
                    maxSizePx = 400
                )
            } else {
                null
            }
            val initialViews = buildRemoteViews(context, initialDisplay, initialBitmap)
            withContext(Dispatchers.Main) {
                appWidgetManager.updateAppWidget(appWidgetId, initialViews)
            }

            if (location == null) return

            // 2. Refresh live weather asynchronously and update unified cache
            try {
                val response = RetrofitClient.service.currentWeather(
                    location.latitude,
                    location.longitude,
                    BuildConfig.OPENWEATHER_API_KEY
                )
                val temp = response.main.temp
                val rawMain = response.weather.firstOrNull()?.main.orEmpty()
                val weatherType = WeatherType.fromString(rawMain)
                val condition = weatherType.label
                val city = location.cityName ?: response.name ?: "Standort"
                val country = location.country ?: response.sys?.country
                val sunrise = response.sys?.sunrise
                val sunset = response.sys?.sunset

                mainPrefs.edit().apply {
                    putFloat(AppPrefsKeys.KEY_LAST_WEATHER_TEMP, temp.toFloat())
                    putString(AppPrefsKeys.KEY_LAST_WEATHER_CONDITION, condition)
                    putString(AppPrefsKeys.KEY_LAST_WEATHER_TYPE, weatherType.name)
                    putString(AppPrefsKeys.KEY_LAST_WEATHER_RAW_MAIN, rawMain)
                    if (sunrise != null) putLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNRISE, sunrise)
                    if (sunset != null) putLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNSET, sunset)
                    putLong(AppPrefsKeys.KEY_LAST_WEATHER_UPDATED_AT, System.currentTimeMillis())
                    if (mainPrefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null).isNullOrBlank()) {
                        putString(AppPrefsKeys.KEY_LAST_CITY_NAME, city)
                        if (!country.isNullOrBlank()) {
                            putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, country)
                        }
                    }
                    apply()
                }

                val liveCached = CachedWidgetWeather(
                    tempC = temp,
                    conditionLabel = condition,
                    weatherType = weatherType,
                    rawMain = rawMain,
                    sunriseSec = sunrise,
                    sunsetSec = sunset
                )
                val liveScenario = resolveScenario(liveCached)
                val liveLocation = location.copy(cityName = city, country = country)
                val liveDisplay = buildWidgetDisplayState(liveLocation, liveCached, liveScenario)
                val liveBitmap = CityImageRepository.getInstance(context).findBestImageForWeather(
                    cityName = city,
                    lat = location.latitude,
                    lon = location.longitude,
                    scenario = liveScenario,
                    maxSizePx = 400
                )
                val updatedViews = buildRemoteViews(context, liveDisplay, liveBitmap)
                withContext(Dispatchers.Main) {
                    appWidgetManager.updateAppWidget(appWidgetId, updatedViews)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Offline/network fallback kept cached widget state", e)
            }
        }

        private fun resolveScenario(cached: CachedWidgetWeather): WeatherScenario {
            val nowSec = Instant.now().epochSecond
            val isNight = WeatherScenario.isNightTime(
                nowEpochSec = nowSec,
                sunriseEpochSec = cached.sunriseSec,
                sunsetEpochSec = cached.sunsetSec,
                fallbackLocalHour = java.time.LocalTime.now().hour
            )
            return WeatherScenario.fromWeatherType(
                weatherType = cached.rawMain.ifBlank { cached.weatherType.name },
                isNight = isNight
            )
        }

        private fun readCachedWeather(prefs: android.content.SharedPreferences): CachedWidgetWeather? {
            if (!prefs.contains(AppPrefsKeys.KEY_LAST_WEATHER_TEMP)) return null
            val temp = prefs.getFloat(AppPrefsKeys.KEY_LAST_WEATHER_TEMP, Float.NaN).toDouble()
            if (!temp.isFinite()) return null
            val typeName = prefs.getString(AppPrefsKeys.KEY_LAST_WEATHER_TYPE, null)
            val weatherType = WeatherType.entries.firstOrNull { it.name == typeName } ?: WeatherType.OTHER
            val condition = prefs.getString(AppPrefsKeys.KEY_LAST_WEATHER_CONDITION, null) ?: weatherType.label
            val rawMain = prefs.getString(AppPrefsKeys.KEY_LAST_WEATHER_RAW_MAIN, null) ?: weatherType.name
            val sunrise = if (prefs.contains(AppPrefsKeys.KEY_LAST_WEATHER_SUNRISE)) {
                prefs.getLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNRISE, 0L).takeIf { it > 0L }
            } else {
                null
            }
            val sunset = if (prefs.contains(AppPrefsKeys.KEY_LAST_WEATHER_SUNSET)) {
                prefs.getLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNSET, 0L).takeIf { it > 0L }
            } else {
                null
            }
            val hint = prefs.getString(AppPrefsKeys.KEY_LAST_WEATHER_HINT, null)
            return CachedWidgetWeather(
                tempC = temp,
                conditionLabel = condition,
                weatherType = weatherType,
                rawMain = rawMain,
                sunriseSec = sunrise,
                sunsetSec = sunset,
                hint = hint
            )
        }

        private fun buildRemoteViews(
            context: Context,
            display: WidgetDisplayState,
            cityBitmap: android.graphics.Bitmap?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_click_area, pendingIntent)
            views.setTextViewText(R.id.widget_city, display.cityText)
            views.setTextViewText(R.id.widget_temp, display.temperatureText)
            views.setTextViewText(R.id.widget_emoji, display.emoji)
            views.setTextViewText(R.id.widget_condition, display.conditionText)

            if (cityBitmap != null) {
                views.setImageViewBitmap(R.id.widget_city_image, cityBitmap)
            } else {
                views.setImageViewResource(R.id.widget_city_image, android.R.color.transparent)
            }
            return views
        }

        private fun readDoubleFromStore(store: KeyValueStore, key: String): Double? {
            if (store is SharedPreferencesKeyValueStore) {
                if (!store.contains(key)) return null
                val f = store.getFloat(key, Float.NaN)
                return if (f.isFinite()) f.toDouble() else null
            }
            store.getString(key, null)?.toDoubleOrNull()?.let { d ->
                if (d.isFinite()) return d
            }
            return try {
                val method = store.javaClass.getMethod("getFloat", String::class.java, Float::class.javaPrimitiveType)
                method.isAccessible = true
                val value = method.invoke(store, key, Float.NaN) as? Float
                if (value != null && value.isFinite()) value.toDouble() else null
            } catch (_: Exception) {
                null
            }
        }

        fun saveWidgetLocation(
            context: Context,
            appWidgetId: Int,
            lat: Double,
            lon: Double,
            cityName: String?
        ) {
            val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("lat_$appWidgetId", lat.toFloat())
                .putFloat("lon_$appWidgetId", lon.toFloat())
                .putString("city_$appWidgetId", cityName)
                .apply()

            updateAllWidgets(context)
        }
    }
}
