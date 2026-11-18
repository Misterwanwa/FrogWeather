package com.frogweather

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.frogweather.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.frogweather.network.PhotonFeature
import com.frogweather.network.RetrofitClient
import com.frogweather.network.WeatherDetails
import com.frogweather.network.PhotonClient
import com.frogweather.network.ForecastResponse
import com.frogweather.data.CityImageRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WeatherNotificationService
import com.frogweather.service.WeatherWarning
import com.frogweather.ui.theme.FrogWeatherTheme
import com.frogweather.ui.WeatherMapTile
import com.frogweather.ui.WarningBanners
import com.frogweather.ui.MissingImageBanner
import com.frogweather.widget.WeatherWidgetProvider
import com.google.android.gms.location.LocationServices
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

class MainActivity : ComponentActivity() {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // exposed state for UI
    private val _weatherState = mutableStateOf<WeatherType?>(null)
    val weatherState: State<WeatherType?> = _weatherState
    private val _temperature = mutableStateOf<Double?>(null)
    val temperature: State<Double?> = _temperature
    private val _feelsLike = mutableStateOf<Double?>(null)
    val feelsLike: State<Double?> = _feelsLike
    private val _tempMin = mutableStateOf<Double?>(null)
    val tempMin: State<Double?> = _tempMin
    private val _tempMax = mutableStateOf<Double?>(null)
    val tempMax: State<Double?> = _tempMax
    private val _locationName = mutableStateOf<String?>(null)
    val locationName: State<String?> = _locationName
    private val _loading = mutableStateOf(true)
    val loading: State<Boolean> = _loading

    // hourly and daily forecast entries
    private val _hourlyEntries = mutableStateListOf<HourlyEntry>()
    val hourlyEntries: List<HourlyEntry> = _hourlyEntries
    private val _dailyEntries = mutableStateListOf<DailyEntry>()
    val dailyEntries: List<DailyEntry> = _dailyEntries

    // rain alert: time string of next expected rain, null if none
    private val _rainAlert = mutableStateOf<String?>(null)
    val rainAlert: State<String?> = _rainAlert

    // detail grid state
    private val _windSpeed = mutableStateOf<Double?>(null)
    val windSpeed: State<Double?> = _windSpeed
    private val _windDeg = mutableStateOf<Int?>(null)
    val windDeg: State<Int?> = _windDeg
    private val _sunrise = mutableStateOf<Long?>(null)
    val sunrise: State<Long?> = _sunrise
    private val _sunset = mutableStateOf<Long?>(null)
    val sunset: State<Long?> = _sunset
    private val _pressure = mutableStateOf<Int?>(null)
    val pressure: State<Int?> = _pressure
    private val _humidity = mutableStateOf<Int?>(null)
    val humidity: State<Int?> = _humidity
    private val _visibilityM = mutableStateOf<Int?>(null)
    val visibilityM: State<Int?> = _visibilityM
    private val _rainMm = mutableStateOf<Double?>(null)
    val rainMm: State<Double?> = _rainMm
    private val _aqi = mutableStateOf<Int?>(null)
    val aqi: State<Int?> = _aqi
    private val _clouds = mutableStateOf<Int?>(null)
    val clouds: State<Int?> = _clouds
    
    // map coordinates
    private val _latitude = mutableStateOf<Double?>(null)
    val latitude: State<Double?> = _latitude
    private val _longitude = mutableStateOf<Double?>(null)
    val longitude: State<Double?> = _longitude
    
    // DWD warnings
    private val _warnings = mutableStateListOf<WeatherWarning>()
    val warnings: List<WeatherWarning> = _warnings
    
    // Missing image tracking
    private val _missingImageCount = mutableStateOf(0)
    val missingImageCount: State<Int> = _missingImageCount
    private val _currentWeatherScenario = mutableStateOf<WeatherScenario?>(null)
    val currentWeatherScenario: State<WeatherScenario?> = _currentWeatherScenario

    // autocomplete suggestions from Photon API
    private val _suggestions = mutableStateListOf<PhotonFeature>()
    val suggestions: List<PhotonFeature> = _suggestions

    // simple data holder classes
    data class HourlyEntry(
        val time: String,
        val weather: WeatherType,
        val temp: Double,
        val pop: Double,
        val windSpeed: Double,
        val isInterpolated: Boolean,
        val isExtreme: Boolean
    )
    data class DailyEntry(val date: String, val dayName: String, val weather: WeatherType, val tempMin: Double, val tempMax: Double)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (locationGranted) getLastLocation()
        else {
            _loading.value = false
            _weatherState.value = WeatherType.OTHER
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request permissions based on Android version
        val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())

        setContent {
            FrogWeatherTheme {
                FrogWeatherApp(
                    currentWeather = weatherState.value,
                    temperature = temperature.value,
                    feelsLike = feelsLike.value,
                    tempMin = tempMin.value,
                    tempMax = tempMax.value,
                    rainAlert = rainAlert.value,
                    loading = loading.value,
                    hourly = hourlyEntries,
                    daily = dailyEntries,
                    locationName = locationName.value,
                    details = WeatherDetails(
                        windSpeed = windSpeed.value,
                        windDeg = windDeg.value,
                        sunrise = sunrise.value,
                        sunset = sunset.value,
                        pressure = pressure.value,
                        humidity = humidity.value,
                        visibilityM = visibilityM.value,
                        rainMm = rainMm.value,
                        aqi = aqi.value,
                        temperature = temperature.value
                    ),
                    latitude = latitude.value,
                    longitude = longitude.value,
                    warnings = warnings,
                    missingImageCount = missingImageCount.value,
                    currentWeatherScenario = currentWeatherScenario.value,
                    suggestions = suggestions,
                    onQueryChange = { fetchSuggestions(it) },
                    onSuggestionSelected = { feature ->
                        clearSuggestions()
                        fetchWeatherByCity(feature.cityQuery())
                    },
                    onRefreshLocation = { getLastLocation() },
                    onSearch = { city -> clearSuggestions(); fetchWeatherByCity(city) }
                )
            }
        }
    }

    @SuppressLint("MissingPermission") // permission is requested before call
    private fun getLastLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                fetchWeather(location.latitude, location.longitude)
            } else {
                // couldn't get a location; fall back to "other"
                _weatherState.value = WeatherType.OTHER
                _loading.value = false
            }
        }.addOnFailureListener {
            _weatherState.value = WeatherType.OTHER
            _loading.value = false
        }
    }

    private fun fetchWeatherByCity(city: String) {
        _loading.value = true
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.service.currentWeatherByCity(city, BuildConfig.OPENWEATHER_API_KEY)
                val mainLabel = response.weather.firstOrNull()?.main ?: ""
                val weather = WeatherType.fromString(mainLabel)
                _weatherState.value = weather
                _temperature.value = response.main.temp
                _feelsLike.value = response.main.feelsLike
                _tempMin.value = response.main.tempMin
                _tempMax.value = response.main.tempMax
                _locationName.value = response.name
                _windSpeed.value = response.wind?.speed
                _windDeg.value = response.wind?.deg
                _sunrise.value = response.sys?.sunrise
                _sunset.value = response.sys?.sunset
                _pressure.value = response.main.pressure.takeIf { it > 0 }
                _humidity.value = response.main.humidity.takeIf { it >= 0 }
                _visibilityM.value = response.visibility
                _rainMm.value = response.rain?.oneHour ?: 0.0
                _clouds.value = response.clouds?.all
                val lat = response.coord.lat
                val lon = response.coord.lon
                _latitude.value = lat
                _longitude.value = lon
                
                // Save last city before starting background components so prefs always have current coordinates
                saveLastCity(response.name, response.sys?.country, lat, lon)

                // Start notification service with location
                WeatherNotificationService.startService(this@MainActivity, lat, lon)
                
                // Update widget with new location
                updateWidget()
                
                // Check for missing images
                checkMissingImages(lat, lon, weather)
                
                // Fetch DWD warnings
                fetchDwdWarnings(lat, lon)
                
                fetchHourlyAndDailyByCity(city)
                fetchAirPollution(lat, lon)
            } catch (e: Exception) {
                e.printStackTrace()
                _weatherState.value = WeatherType.OTHER
            }
            _loading.value = false
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        _loading.value = true
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.service.currentWeather(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                val mainLabel = response.weather.firstOrNull()?.main ?: ""
                val weather = WeatherType.fromString(mainLabel)
                _weatherState.value = weather
                _temperature.value = response.main.temp
                _feelsLike.value = response.main.feelsLike
                _tempMin.value = response.main.tempMin
                _tempMax.value = response.main.tempMax
                _locationName.value = response.name
                _windSpeed.value = response.wind?.speed
                _windDeg.value = response.wind?.deg
                _sunrise.value = response.sys?.sunrise
                _sunset.value = response.sys?.sunset
                _pressure.value = response.main.pressure.takeIf { it > 0 }
                _humidity.value = response.main.humidity.takeIf { it >= 0 }
                _visibilityM.value = response.visibility
                _rainMm.value = response.rain?.oneHour ?: 0.0
                _clouds.value = response.clouds?.all
                _latitude.value = lat
                _longitude.value = lon
                
                // Save last city before starting background components so prefs always have current coordinates
                saveLastCity(response.name, response.sys?.country, lat, lon)

                // Start notification service with location
                WeatherNotificationService.startService(this@MainActivity, lat, lon)
                
                // Update widget with new location
                updateWidget()
                
                // Check for missing images
                checkMissingImages(lat, lon, weather)
                
                // Fetch DWD warnings
                fetchDwdWarnings(lat, lon)
                
                fetchHourlyAndDaily(lat, lon)
                fetchAirPollution(lat, lon)
            } catch (e: Exception) {
                e.printStackTrace()
                _weatherState.value = WeatherType.OTHER
            }
            _loading.value = false
        }
    }

    private fun fetchHourlyAndDailyByCity(city: String) {
        lifecycleScope.launch {
            try {
                val forecast = RetrofitClient.service.forecastByCity(city, BuildConfig.OPENWEATHER_API_KEY)
                updateForecastStates(forecast)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchHourlyAndDaily(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val forecast = RetrofitClient.service.forecast(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                updateForecastStates(forecast)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun updateForecastStates(forecast: ForecastResponse) {
        _hourlyEntries.clear()
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        
        val currentTimestamp = Instant.now().epochSecond
        val predictions = com.frogweather.service.WeatherPredictionModel.predict24Hours(
            lat = latitude.value ?: 0.0,
            lon = longitude.value ?: 0.0,
            currentTemp = temperature.value,
            currentPressure = pressure.value?.toDouble(),
            currentHumidity = humidity.value,
            currentWindSpeed = windSpeed.value,
            currentWeather = weatherState.value,
            currentClouds = clouds.value,
            currentPop = if (weatherState.value == WeatherType.RAINY || weatherState.value == WeatherType.THUNDERSTORM) 0.8 else 0.0,
            currentDt = currentTimestamp,
            forecastItems = forecast.list,
            warnings = warnings
        )

        val zoneId = try {
            ZoneOffset.ofTotalSeconds(forecast.city.timezone)
        } catch (e: Exception) {
            ZoneOffset.UTC
        }

        predictions.forEachIndexed { index, pred ->
            val time = if (index == 0) "Jetzt" else {
                pred.time.atZone(zoneId).format(formatter)
            }
            _hourlyEntries.add(
                HourlyEntry(
                    time = time,
                    weather = pred.weatherType,
                    temp = pred.temp,
                    pop = pred.pop,
                    windSpeed = pred.windSpeed,
                    isInterpolated = pred.isInterpolated,
                    isExtreme = pred.isExtreme
                )
            )
        }

        // daily: pick one entry per day for next 7 days
        _dailyEntries.clear()
        val dailyGroups = forecast.list.groupBy { 
            Instant.ofEpochSecond(it.dt).atZone(ZoneOffset.UTC).toLocalDate() 
        }
        val dayFormatter = DateTimeFormatter.ofPattern("dd.MM")
        val dayNameFormatter = DateTimeFormatter.ofPattern("E", Locale.GERMAN)
        
        dailyGroups.entries.take(7).forEach { (date, items) ->
            val min = items.minOf { it.main.tempMin }
            val max = items.maxOf { it.main.tempMax }
            val mainWeather = items.groupBy { it.weather.firstOrNull()?.main ?: "" }
                .maxByOrNull { it.value.size }?.key ?: ""
            
            _dailyEntries.add(DailyEntry(
                date.format(dayFormatter),
                date.format(dayNameFormatter),
                WeatherType.fromString(mainWeather),
                min,
                max
            ))
        }
        
        // simple rain alert
        val nextRain = forecast.list.firstOrNull { it.weather.any { w -> w.main.lowercase().contains("rain") } }
        _rainAlert.value = nextRain?.let { 
            val time = Instant.ofEpochSecond(it.dt).atZone(ZoneOffset.UTC).format(formatter)
            "Regen erwartet um $time"
        }
    }
    
    private fun fetchAirPollution(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.service.airPollution(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                _aqi.value = response.list.firstOrNull()?.main?.aqi
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun fetchDwdWarnings(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val dwdWarnings = DwdWarningFetcher.fetchWarnings(lat, lon)
                _warnings.clear()
                _warnings.addAll(dwdWarnings)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchSuggestions(query: String) {
        if (query.length < 2) {
            clearSuggestions()
            return
        }
        lifecycleScope.launch {
            try {
                val response = PhotonClient.service.search(query)
                _suggestions.clear()
                _suggestions.addAll(response.features)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun clearSuggestions() {
        _suggestions.clear()
    }

    private fun updateWidget() {
        WeatherWidgetProvider.updateAllWidgets(this)
    }
    
    private fun saveLastCity(name: String?, country: String?, lat: Double, lon: Double) {
        val prefs = getSharedPreferences("frog_weather_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_city_name", name)
            putString("last_city_country", country)
            putFloat("last_city_lat", lat.toFloat())
            putFloat("last_city_lon", lon.toFloat())
            apply()
        }
    }
    
    private fun checkMissingImages(lat: Double, lon: Double, weather: WeatherType) {
        lifecycleScope.launch {
            val repo = CityImageRepository(this@MainActivity)
            val scenario = WeatherScenario.fromWeatherType(weather.label)
            _currentWeatherScenario.value = scenario
            val city = repo.findCityByLocation(lat, lon)
            if (city != null) {
                _missingImageCount.value = repo.getMissingImages(city.id).size
            } else {
                _missingImageCount.value = WeatherScenario.entries.size
            }
        }
    }
}

@Composable
fun FrogWeatherApp(
    currentWeather: WeatherType?,
    temperature: Double?,
    feelsLike: Double?,
    tempMin: Double?,
    tempMax: Double?,
    rainAlert: String?,
    loading: Boolean,
    hourly: List<MainActivity.HourlyEntry>,
    daily: List<MainActivity.DailyEntry>,
    locationName: String?,
    details: WeatherDetails,
    latitude: Double?,
    longitude: Double?,
    warnings: List<WeatherWarning>,
    missingImageCount: Int,
    currentWeatherScenario: WeatherScenario?,
    suggestions: List<PhotonFeature>,
    onQueryChange: (String) -> Unit,
    onSuggestionSelected: (PhotonFeature) -> Unit,
    onRefreshLocation: () -> Unit,
    onSearch: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    
    Box(modifier = Modifier.fillMaxSize()) {
        // dynamic background based on weather
        val bgColors = when (currentWeather) {
            WeatherType.SUNNY -> listOf(Color(0xFF4A90E2), Color(0xFF87CEEB))
            WeatherType.RAINY -> listOf(Color(0xFF4B79A1), Color(0xFF283E51))
            WeatherType.CLOUDY -> listOf(Color(0xFF757F9A), Color(0xFFD7DDE8))
            else -> listOf(Color(0xFF616161), Color(0xFF9E9E9E))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(bgColors))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Search Bar
            SearchBar(
                suggestions = suggestions,
                onQueryChange = onQueryChange,
                onSuggestionSelected = onSuggestionSelected,
                onRefreshLocation = onRefreshLocation,
                onSearch = onSearch
            )

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                ) {
                    // Warnings if any
                    if (warnings.isNotEmpty()) {
                        WarningBanners(warnings = warnings)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    
                    // Missing image banner
                    if (missingImageCount > 0 && latitude != null && longitude != null && locationName != null && currentWeatherScenario != null) {
                        MissingImageBanner(
                            missingCount = missingImageCount,
                            highlightedScenario = currentWeatherScenario,
                            latitude = latitude,
                            longitude = longitude
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Location and Current Temp
                    Text(
                        text = locationName ?: "Unbekannter Ort",
                        style = MaterialTheme.typography.h4,
                        color = Color.White
                    )
                    Text(
                        text = "${temperature?.toInt() ?: "--"}°",
                        style = MaterialTheme.typography.h1,
                        color = Color.White
                    )
                    Text(
                        text = "${currentWeather?.label ?: ""} • Gefühlt ${feelsLike?.toInt() ?: "--"}°",
                        style = MaterialTheme.typography.subtitle1,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "H:${tempMax?.toInt() ?: "--"}°  L:${tempMin?.toInt() ?: "--"}°",
                        style = MaterialTheme.typography.subtitle1,
                        color = Color.White.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Frog Animation
                    FrogAnimation(type = currentWeather ?: WeatherType.OTHER)

                    Spacer(modifier = Modifier.height(24.dp))

                    // Rain Alert
                    if (rainAlert != null) {
                        Card(
                            backgroundColor = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            elevation = 0.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = rainAlert, color = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Hourly Forecast
                    HourlyForecast(items = hourly)

                    Spacer(modifier = Modifier.height(24.dp))

                    // 7-Day Forecast
                    DailyForecast(items = daily)

                    Spacer(modifier = Modifier.height(24.dp))

                    // Details Grid (Kacheln)
                    WeatherDetailsGrid(details = details)
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Weather Map (UNTER den Kacheln)
                    if (latitude != null && longitude != null) {
                        WeatherMapTile(latitude = latitude, longitude = longitude)
                    }
                    
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SearchBar(
    suggestions: List<PhotonFeature>,
    onQueryChange: (String) -> Unit,
    onSuggestionSelected: (PhotonFeature) -> Unit,
    onRefreshLocation: () -> Unit,
    onSearch: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = text,
                onValueChange = { 
                    text = it
                    onQueryChange(it)
                    expanded = it.isNotEmpty()
                },
                placeholder = { Text("Stadt suchen...", color = Color.White.copy(alpha = 0.6f)) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    textColor = Color.White
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { 
                    onSearch(text)
                    expanded = false
                })
            )
            IconButton(onClick = onRefreshLocation) {
                Icon(Icons.Default.LocationOn, contentDescription = "Standort", tint = Color.White)
            }
        }

        if (expanded && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                elevation = 8.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    suggestions.forEach { feature ->
                        ListItem(
                            modifier = Modifier.clickable {
                                text = feature.properties.name ?: ""
                                expanded = false
                                onSuggestionSelected(feature)
                            },
                            text = { Text(feature.properties.name ?: "") },
                            secondaryText = { Text("${feature.properties.state ?: ""}, ${feature.properties.country ?: ""}") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HourlyForecast(items: List<MainActivity.HourlyEntry>) {
    Card(
        backgroundColor = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "STÜNDLICHE VORHERSAGE (24 STUNDEN)",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { entry ->
                    val rowBgColor = when {
                        entry.isExtreme -> Color(0xFFEF5350).copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                    val borderModifier = if (entry.isExtreme) {
                        Modifier.border(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(rowBgColor)
                            .then(borderModifier)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. Time
                        Text(
                            text = entry.time,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.width(50.dp)
                        )
                        
                        // 2. Weather Emoji + Label
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(110.dp)
                        ) {
                            Text(text = entry.weather.forecastEmoji, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = entry.weather.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                        
                        // 3. Temp
                        Text(
                            text = "${entry.temp.toInt()}°",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.width(35.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        
                        // 4. Pop (Rain Prob)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(55.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(text = "☔", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${(entry.pop * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp
                            )
                        }
                        
                        // 5. Wind Speed
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(75.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(text = "💨", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${(entry.windSpeed * 3.6).toInt()} km/h",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                        
                        // 6. Source Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        entry.isExtreme -> Color(0xFFEF5350)
                                        !entry.isInterpolated -> Color(0xFF00B4D8)
                                        else -> Color(0xFF81D4FA).copy(alpha = 0.2f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    entry.isExtreme -> "EXTREM"
                                    !entry.isInterpolated -> "API"
                                    else -> "MODELL"
                                },
                                color = if (entry.isExtreme || !entry.isInterpolated) Color.White else Color.White.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                    if (entry != items.last()) {
                        Divider(color = Color.White.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}

@Composable
fun DailyForecast(items: List<MainActivity.DailyEntry>) {
    Card(
        backgroundColor = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "7-TAGE-VORHERSAGE",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.dayName,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp
                    )
                    Text(
                        text = entry.weather.forecastEmoji,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        Text(text = "${entry.tempMax.toInt()}°", color = Color.White, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${entry.tempMin.toInt()}°", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    }
                }
                if (entry != items.last()) {
                    Divider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }
    }
}

@Composable
fun FrogAnimation(type: WeatherType) {
    val context = LocalContext.current

    val videoRes: Int? = when (type) {
        WeatherType.RAINY  -> R.raw.rain
        WeatherType.SUNNY  -> R.raw.sunny
        WeatherType.CLOUDY -> R.raw.cloudy
        else -> null
    }

    if (videoRes != null) {
        val player = remember(videoRes) {
            ExoPlayer.Builder(context).build().apply {
                val uri = android.net.Uri.parse("android.resource://${context.packageName}/$videoRes")
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                prepare()
                playWhenReady = true
            }
        }

        DisposableEffect(player) {
            onDispose { player.release() }
        }

        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.White.copy(alpha = 0.3f), shape = RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = type.emoji, fontSize = 64.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewApp() {
    val sampleHourly = listOf(
        MainActivity.HourlyEntry("Jetzt", WeatherType.SUNNY, 22.0, 0.0, 5.0, false, false),
        MainActivity.HourlyEntry("01:00", WeatherType.SUNNY, 21.5, 0.0, 5.2, true, false),
        MainActivity.HourlyEntry("02:00", WeatherType.CLOUDY, 20.0, 0.1, 5.8, true, false),
        MainActivity.HourlyEntry("03:00", WeatherType.RAINY, 19.0, 0.6, 12.0, false, false),
        MainActivity.HourlyEntry("04:00", WeatherType.THUNDERSTORM, 18.0, 0.9, 22.0, true, true),
        MainActivity.HourlyEntry("05:00", WeatherType.CLOUDY, 18.5, 0.4, 15.0, true, false),
        MainActivity.HourlyEntry("06:00", WeatherType.SUNNY, 19.5, 0.1, 8.0, true, false),
        MainActivity.HourlyEntry("07:00", WeatherType.SUNNY, 21.0, 0.0, 6.0, false, false)
    )
    val sampleDaily = listOf(
        MainActivity.DailyEntry("01.03", "Mo", WeatherType.CLOUDY, 15.0, 22.0),
        MainActivity.DailyEntry("02.03", "Di", WeatherType.RAINY, 14.0, 18.0),
        MainActivity.DailyEntry("03.03", "Mi", WeatherType.SUNNY, 16.0, 24.0),
        MainActivity.DailyEntry("04.03", "Do", WeatherType.CLOUDY, 15.0, 21.0),
        MainActivity.DailyEntry("05.03", "Fr", WeatherType.SUNNY, 17.0, 25.0),
        MainActivity.DailyEntry("06.03", "Sa", WeatherType.RAINY, 13.0, 17.0),
        MainActivity.DailyEntry("07.03", "So", WeatherType.SUNNY, 15.0, 23.0)
    )
    FrogWeatherTheme(darkTheme = true) {
        FrogWeatherApp(
            currentWeather = WeatherType.SUNNY,
            temperature = 22.0,
            feelsLike = 21.0,
            tempMin = 18.0,
            tempMax = 25.0,
            rainAlert = null,
            loading = false,
            hourly = sampleHourly,
            daily = sampleDaily,
            locationName = "Ort",
            details = WeatherDetails(
                windSpeed = 5.0,
                windDeg = 180,
                sunrise = 1700000000L,
                sunset = 1700040000L,
                pressure = 1013,
                humidity = 50,
                visibilityM = 10000,
                rainMm = 0.0,
                aqi = 1,
                temperature = 22.0
            ),
            latitude = 50.0,
            longitude = 10.0,
            warnings = emptyList(),
            missingImageCount = 0,
            currentWeatherScenario = null,
            suggestions = emptyList(),
            onQueryChange = {},
            onSuggestionSelected = {},
            onRefreshLocation = {},
            onSearch = {}
        )
    }
}
