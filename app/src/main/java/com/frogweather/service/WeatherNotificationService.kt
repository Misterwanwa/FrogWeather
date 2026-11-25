package com.frogweather.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.frogweather.BuildConfig
import com.frogweather.MainActivity
import com.frogweather.R
import com.frogweather.WeatherType
import com.frogweather.data.AppPrefsKeys
import com.frogweather.network.RetrofitClient
import com.frogweather.widget.WeatherWidgetProvider
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

data class LiveNotificationState(
    val title: String,
    val contentText: String,
    val ticker: String,
    val visibility: Int,
    val ongoing: Boolean,
    val smallIconRes: Int
)

class WeatherNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    companion object {
        const val CHANNEL_ID = "weather_status_channel"
        const val BRIEFING_CHANNEL_ID = "weather_briefing_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_LAT = "extra_lat"
        private const val EXTRA_LON = "extra_lon"
        private const val EXTRA_FROM_BOOT = "extra_from_boot"

        private const val PREFS_NAME = AppPrefsKeys.PREFS_MAIN
        private const val KEY_NOTIF_LAT_BITS = "notification_lat_bits"
        private const val KEY_NOTIF_LON_BITS = "notification_lon_bits"
        private const val KEY_CITY_LAT = AppPrefsKeys.KEY_LAST_CITY_LAT
        private const val KEY_CITY_LON = AppPrefsKeys.KEY_LAST_CITY_LON
        private const val KEY_LAST_TEMP = AppPrefsKeys.KEY_LAST_WEATHER_TEMP
        private const val KEY_LAST_CONDITION = AppPrefsKeys.KEY_LAST_WEATHER_CONDITION
        private const val KEY_LAST_WEATHER_TYPE = AppPrefsKeys.KEY_LAST_WEATHER_TYPE

        fun startService(
            context: Context,
            lat: Double,
            lon: Double,
            fromBoot: Boolean = false
        ) {
            val intent = Intent(context, WeatherNotificationService::class.java).apply {
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
                putExtra(EXTRA_FROM_BOOT, fromBoot)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: IllegalStateException) {
                // Android 12+/14+ background FGS restriction fallback: keep widget & cache safe
            } catch (_: SecurityException) {
                // Ignore if OS rejects FGS start under strict background rules
            }
        }

        fun buildLiveNotificationState(
            tempC: Double?,
            condition: String?,
            locationName: String?,
            weatherType: WeatherType
        ): LiveNotificationState {
            val safeTemp = tempC?.takeIf { it.isFinite() }
            val safeCondition = condition?.trim()?.takeIf { it.isNotEmpty() } ?: weatherType.label
            val cleanCity = locationName?.trim()?.takeIf { it.isNotEmpty() }

            val title = if (safeTemp != null) {
                "${safeTemp.toInt()}° - $safeCondition"
            } else {
                safeCondition.ifBlank { "Wetter wird geladen..." }
            }
            val contentText = if (cleanCity != null) {
                "FrogWeather · $cleanCity"
            } else {
                "FrogWeather"
            }
            val ticker = if (safeTemp != null) "${safeTemp.toInt()}°" else "..."

            return LiveNotificationState(
                title = title,
                contentText = contentText,
                ticker = ticker,
                visibility = NotificationCompat.VISIBILITY_PUBLIC,
                ongoing = true,
                smallIconRes = getWeatherIconStatic(weatherType)
            )
        }

        fun showBriefingNotification(
            context: Context,
            title: String,
            summary: String,
            fullText: String,
            notificationId: Int = 1002
        ) {
            ensureBriefingChannel(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, BRIEFING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_weather_sunny)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
            } catch (_: SecurityException) {
                // Permission revoked mid-flight
            }
        }

        private fun ensureBriefingChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    BRIEFING_CHANNEL_ID,
                    "Morgen- & Abend-Briefing",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Tägliche Wetter-Briefings inkl. Kleidung, Sportfenster, Kalender & Route"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }
        }

        @JvmStatic
        fun resolveCoordinates(
            intentLat: Double?,
            intentLon: Double?,
            savedNotifLat: Double?,
            savedNotifLon: Double?,
            savedCityLat: Double?,
            savedCityLon: Double?
        ): Pair<Double, Double>? {
            val candidates = listOf(
                intentLat to intentLon,
                savedNotifLat to savedNotifLon,
                savedCityLat to savedCityLon
            )
            return candidates.firstOrNull { (lat, lon) ->
                isValidCoordinatePair(lat, lon)
            }?.let { (lat, lon) -> lat!! to lon!! }
        }

        private fun isValidCoordinatePair(lat: Double?, lon: Double?): Boolean =
            lat != null && lon != null &&
                lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(abs(lat) < 1e-6 && abs(lon) < 1e-6)

        private fun getWeatherIconStatic(weatherType: WeatherType): Int {
            return when (weatherType) {
                WeatherType.SUNNY -> R.drawable.ic_weather_sunny
                WeatherType.CLOUDY -> R.drawable.ic_weather_cloudy
                WeatherType.RAINY -> R.drawable.ic_weather_rainy
                WeatherType.THUNDERSTORM -> R.drawable.ic_weather_rainy
                WeatherType.SNOW -> R.drawable.ic_weather_other
                WeatherType.OTHER -> R.drawable.ic_weather_other
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false

        val hasIntentCoords = intent?.hasExtra(EXTRA_LAT) == true && intent.hasExtra(EXTRA_LON)
        val intentLat = if (hasIntentCoords) intent?.getDoubleExtra(EXTRA_LAT, 0.0) else null
        val intentLon = if (hasIntentCoords) intent?.getDoubleExtra(EXTRA_LON, 0.0) else null

        val (savedNotifLat, savedNotifLon) = readNotificationCoords(prefs)
        val (savedCityLat, savedCityLon) = readCityCoords(prefs)

        val resolvedCoords = resolveCoordinates(
            intentLat = intentLat,
            intentLon = intentLon,
            savedNotifLat = savedNotifLat,
            savedNotifLon = savedNotifLon,
            savedCityLat = savedCityLat,
            savedCityLon = savedCityLon
        )

        if (isValidCoordinatePair(intentLat, intentLon)) {
            saveNotificationCoords(prefs, intentLat!!, intentLon!!)
        }

        createNotificationChannel()
        val initialNotification = buildInitialNotification(prefs)
        val hasLocationPerm = hasLocationPermission()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (resolvedCoords != null || !hasLocationPerm || fromBoot) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, fgsType)
        } catch (_: Exception) {
            // Android 14+ ForegroundServiceStartNotAllowedException / SecurityException fallback
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, initialNotification)
                } catch (_: SecurityException) {
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        var activeCoords = resolvedCoords
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                if (activeCoords == null) {
                    val gpsCoords = getLastKnownLocation()
                    if (gpsCoords != null) {
                        saveNotificationCoords(prefs, gpsCoords.first, gpsCoords.second)
                        activeCoords = gpsCoords
                    } else {
                        val (notifLat, notifLon) = readNotificationCoords(prefs)
                        val (cityLat, cityLon) = readCityCoords(prefs)
                        activeCoords = resolveCoordinates(null, null, notifLat, notifLon, cityLat, cityLon)
                    }
                }
                val currentCoords = activeCoords
                if (currentCoords != null) {
                    fetchAndNotify(currentCoords.first, currentCoords.second, prefs)
                }
                delay(30 * 60 * 1000L)
            }
        }

        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun readNotificationCoords(prefs: SharedPreferences): Pair<Double?, Double?> {
        if (!prefs.contains(KEY_NOTIF_LAT_BITS) || !prefs.contains(KEY_NOTIF_LON_BITS)) {
            return null to null
        }
        val lat = Double.fromBits(prefs.getLong(KEY_NOTIF_LAT_BITS, 0L))
        val lon = Double.fromBits(prefs.getLong(KEY_NOTIF_LON_BITS, 0L))
        return lat to lon
    }

    private fun readCityCoords(prefs: SharedPreferences): Pair<Double?, Double?> {
        if (!prefs.contains(KEY_CITY_LAT) || !prefs.contains(KEY_CITY_LON)) {
            return null to null
        }
        val lat = prefs.getFloat(KEY_CITY_LAT, 0f).toDouble()
        val lon = prefs.getFloat(KEY_CITY_LON, 0f).toDouble()
        return lat to lon
    }

    private fun saveNotificationCoords(prefs: SharedPreferences, lat: Double, lon: Double) {
        prefs.edit()
            .putLong(KEY_NOTIF_LAT_BITS, lat.toRawBits())
            .putLong(KEY_NOTIF_LON_BITS, lon.toRawBits())
            .apply()
    }

    private fun buildInitialNotification(prefs: SharedPreferences): Notification {
        val cityName = prefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)
        if (prefs.contains(KEY_LAST_TEMP)) {
            val cachedTemp = prefs.getFloat(KEY_LAST_TEMP, 0f).toDouble()
            val cachedCondition = prefs.getString(KEY_LAST_CONDITION, null)
            val cachedTypeName = prefs.getString(KEY_LAST_WEATHER_TYPE, null)
            val weatherType = cachedTypeName?.let { name ->
                WeatherType.entries.firstOrNull { it.name == name }
            } ?: WeatherType.OTHER
            if (cachedCondition != null) {
                return createNotification(cachedTemp, cachedCondition, cityName, weatherType)
            }
        }
        return createNotification(null, "Wetter wird geladen...", cityName, WeatherType.OTHER)
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        return suspendCancellableCoroutine { cont ->
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { location ->
                    if (cont.isActive) {
                        val coords = if (location != null && isValidCoordinatePair(location.latitude, location.longitude)) {
                            location.latitude to location.longitude
                        } else {
                            null
                        }
                        cont.resume(coords)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
                .addOnCanceledListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    private suspend fun fetchAndNotify(lat: Double, lon: Double, prefs: SharedPreferences) {
        if (!isValidCoordinatePair(lat, lon)) return
        try {
            val response = RetrofitClient.service.currentWeather(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
            val temp = response.main.temp
            val main = response.weather.firstOrNull()?.main.orEmpty()
            val weatherType = WeatherType.fromString(main)
            val condition = weatherType.label
            val cityName = prefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)
                ?.takeIf { it.isNotBlank() } ?: response.name
            val country = prefs.getString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, null)
                ?.takeIf { it.isNotBlank() } ?: response.sys?.country

            prefs.edit().apply {
                putFloat(KEY_LAST_TEMP, temp.toFloat())
                putString(KEY_LAST_CONDITION, condition)
                putString(KEY_LAST_WEATHER_TYPE, weatherType.name)
                putString(AppPrefsKeys.KEY_LAST_WEATHER_RAW_MAIN, main)
                response.sys?.sunrise?.let { putLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNRISE, it) }
                response.sys?.sunset?.let { putLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNSET, it) }
                putLong(AppPrefsKeys.KEY_LAST_WEATHER_UPDATED_AT, System.currentTimeMillis())
                if (prefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null).isNullOrBlank() && !cityName.isNullOrBlank()) {
                    putString(AppPrefsKeys.KEY_LAST_CITY_NAME, cityName)
                    if (!country.isNullOrBlank()) putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, country)
                }
                apply()
            }

            val notification = createNotification(temp, condition, cityName, weatherType)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)

            WeatherWidgetProvider.updateAllWidgets(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(
        temp: Double?,
        condition: String?,
        locationName: String?,
        weatherType: WeatherType
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val state = buildLiveNotificationState(
            tempC = temp,
            condition = condition,
            locationName = locationName,
            weatherType = weatherType
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.title)
            .setContentText(state.contentText)
            .setTicker(state.ticker)
            .setSmallIcon(state.smallIconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(state.ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(state.visibility)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wetterbenachrichtigung",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Zeigt aktuelle Temperatur und Wetter in der Statusleiste und auf dem Sperrbildschirm"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            ensureBriefingChannel(this)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
