package com.frogweather.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frogweather.BuildConfig
import com.frogweather.MainActivity
import com.frogweather.R
import com.frogweather.WeatherType
import com.frogweather.network.RetrofitClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class WeatherNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "weather_status_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_LAT = "extra_lat"
        private const val EXTRA_LON = "extra_lon"

        private const val PREFS_NAME = "frog_weather_prefs"
        private const val KEY_NOTIF_LAT_BITS = "notification_lat_bits"
        private const val KEY_NOTIF_LON_BITS = "notification_lon_bits"
        private const val KEY_CITY_LAT = "last_city_lat"
        private const val KEY_CITY_LON = "last_city_lon"
        private const val KEY_LAST_TEMP = "notification_last_temp"
        private const val KEY_LAST_CONDITION = "notification_last_condition"
        private const val KEY_LAST_WEATHER_TYPE = "notification_last_weather_type"

        fun startService(context: Context, lat: Double, lon: Double) {
            val intent = Intent(context, WeatherNotificationService::class.java).apply {
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun resolveCoordinates(
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
            lat != null && lon != null && lat.isFinite() && lon.isFinite() && !(lat == 0.0 && lon == 0.0)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        startForeground(NOTIFICATION_ID, buildInitialNotification(prefs))

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
                delay(30 * 60 * 1000L) // 30 minutes
            }
        }

        return START_STICKY
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
        if (prefs.contains(KEY_LAST_TEMP)) {
            val cachedTemp = prefs.getFloat(KEY_LAST_TEMP, 0f).toDouble()
            val cachedCondition = prefs.getString(KEY_LAST_CONDITION, null)
            val cachedTypeName = prefs.getString(KEY_LAST_WEATHER_TYPE, null)
            val weatherType = cachedTypeName?.let { name ->
                WeatherType.entries.firstOrNull { it.name == name }
            } ?: WeatherType.OTHER
            if (cachedCondition != null) {
                return createNotification(cachedTemp, cachedCondition, weatherType)
            }
        }
        return createNotification(null, "Wetter wird geladen...", WeatherType.OTHER)
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) return null

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
        if (lat == 0.0 && lon == 0.0) return
        try {
            val response = RetrofitClient.service.currentWeather(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
            val temp = response.main.temp.toInt()
            val main = response.weather.firstOrNull()?.main ?: ""
            val weatherType = WeatherType.fromString(main)
            val condition = weatherType.label

            prefs.edit()
                .putFloat(KEY_LAST_TEMP, temp.toFloat())
                .putString(KEY_LAST_CONDITION, condition)
                .putString(KEY_LAST_WEATHER_TYPE, weatherType.name)
                .apply()

            val notification = createNotification(temp.toDouble(), condition, weatherType)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(temp: Double?, condition: String, weatherType: WeatherType): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (temp != null) "${temp.toInt()}° - $condition" else condition
        // For status bar (ticker) - very short text
        val statusBarText = if (temp != null) "${temp.toInt()}°" else "..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("FrogWeather")
            .setTicker(statusBarText) // This shows in status bar on some devices
            .setSmallIcon(getWeatherIcon(weatherType))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun getWeatherIcon(weatherType: WeatherType): Int {
        return when (weatherType) {
            WeatherType.SUNNY -> R.drawable.ic_weather_sunny
            WeatherType.CLOUDY -> R.drawable.ic_weather_cloudy
            WeatherType.RAINY -> R.drawable.ic_weather_rainy
            WeatherType.THUNDERSTORM -> R.drawable.ic_weather_rainy // Fallback to rainy
            WeatherType.SNOW -> R.drawable.ic_weather_other // Fallback
            WeatherType.OTHER -> R.drawable.ic_weather_other
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wetterbenachrichtigung",
                NotificationManager.IMPORTANCE_HIGH // High importance = visible in status bar
            ).apply {
                description = "Zeigt aktuelle Temperatur und Wetter in der Statusleiste"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
