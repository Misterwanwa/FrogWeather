package com.frogweather

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.frogweather.BuildConfig
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.network.RetrofitClient
import com.frogweather.ui.theme.FrogWeatherTheme
import com.google.android.gms.location.LocationServices
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // exposed state for UI
    private val _weatherState = mutableStateOf<WeatherType?>(null)
    val weatherState: State<WeatherType?> = _weatherState
    private val _temperature = mutableStateOf<Double?>(null)
    val temperature: State<Double?> = _temperature
    private val _locationName = mutableStateOf<String?>(null)
    val locationName: State<String?> = _locationName
    private val _loading = mutableStateOf(true)
    val loading: State<Boolean> = _loading

    // hourly and daily forecast entries
    private val _hourlyEntries = mutableStateListOf<HourlyEntry>()
    val hourlyEntries: List<HourlyEntry> = _hourlyEntries
    private val _dailyEntries = mutableStateListOf<DailyEntry>()
    val dailyEntries: List<DailyEntry> = _dailyEntries

    // simple data holder classes
    data class HourlyEntry(val time: String, val weather: WeatherType)
    data class DailyEntry(val date: String, val weather: WeatherType)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) getLastLocation()
        else {
            _loading.value = false
            _weatherState.value = WeatherType.OTHER
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)

        setContent {
            FrogWeatherTheme {
                FrogWeatherApp(
                    currentWeather = weatherState.value,
                    temperature = temperature.value,
                    loading = loading.value,
                    hourly = hourlyEntries,
                    daily = dailyEntries,
                    locationName = locationName.value,
                    onRefreshLocation = { getLastLocation() },
                    onSearch = { city -> fetchWeatherByCity(city) }
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
                val main = response.weather.firstOrNull()?.main ?: ""
                _weatherState.value = WeatherType.fromString(main)
                _temperature.value = response.main.temp
                _locationName.value = response.name
                fetchHourlyAndDaily(response.coord.lat, response.coord.lon)
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
                val main = response.weather.firstOrNull()?.main ?: ""
                _weatherState.value = WeatherType.fromString(main)
                _temperature.value = response.main.temp
                _locationName.value = response.name
                fetchHourlyAndDaily(lat, lon)
            } catch (e: Exception) {
                e.printStackTrace()
                _weatherState.value = WeatherType.OTHER
            }
            _loading.value = false
        }
    }

    private suspend fun fetchHourlyAndDaily(lat: Double, lon: Double) {
        try {
            val response = RetrofitClient.service.oneCall(
                lat, lon, "current,minutely,alerts", BuildConfig.OPENWEATHER_API_KEY
            )

            _hourlyEntries.clear()
            response.hourly.take(24).forEach { hour ->
                val time = Instant.ofEpochSecond(hour.dt)
                    .atOffset(ZoneOffset.ofTotalSeconds(response.timezone_offset))
                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                val weather = WeatherType.fromString(hour.weather.firstOrNull()?.main ?: "")
                _hourlyEntries.add(HourlyEntry(time, weather))
            }

            _dailyEntries.clear()
            response.daily.forEach { day ->
                val date = Instant.ofEpochSecond(day.dt)
                    .atOffset(ZoneOffset.ofTotalSeconds(response.timezone_offset))
                    .format(DateTimeFormatter.ofPattern("dd.MM"))
                val weather = WeatherType.fromString(day.weather.firstOrNull()?.main ?: "")
                _dailyEntries.add(DailyEntry(date, weather))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun FrogWeatherApp(
    currentWeather: WeatherType?,
    temperature: Double?,
    loading: Boolean,
    hourly: List<MainActivity.HourlyEntry>,
    daily: List<MainActivity.DailyEntry>,
    locationName: String?,
    onRefreshLocation: () -> Unit,
    onSearch: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        // background gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D1B2A),
                            Color(0xFF1B263B)
                        )
                    )
                )
        )

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                locationName?.let { name ->
                    Text(text = name, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TopRow(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { onSearch(query) },
                    onRefresh = onRefreshLocation
                )

                Spacer(modifier = Modifier.height(32.dp))
                temperature?.let { temp ->
                    Text(
                        text = "${temp.toInt()}°",
                        fontSize = 72.sp,
                        color = Color.White
                    )
                }
                Text(
                    text = currentWeather?.label ?: "-",
                    fontSize = 24.sp,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(32.dp))
                FrogAnimation(currentWeather ?: WeatherType.OTHER)

                Spacer(modifier = Modifier.height(32.dp))
                HourlyForecast(hourly)

                Spacer(modifier = Modifier.height(24.dp))
                DailyForecast(daily)
            }
        }
    }
}

@Composable
fun TopRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Aktuellen Standort",
                tint = Color.White
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Ort suchen", color = Color.White.copy(alpha=0.7f)) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color.White)
                }
            },
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = Color.White,
                cursorColor = Color.White,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                backgroundColor = Color.Transparent
            )
        )

        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = Color.White
        )
    }
}

@Composable
fun HourlyForecast(items: List<MainActivity.HourlyEntry>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp))
            .padding(8.dp)
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.size) { idx ->
                val entry = items[idx]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = entry.time, color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = entry.weather.emoji, fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
fun DailyForecast(items: List<MainActivity.DailyEntry>) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items.size) { idx ->
            val entry = items[idx]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = entry.date, color = Color.White)
                Text(text = entry.weather.emoji, fontSize = 20.sp)
                Text(text = "--° / --°", color = Color.White)
            }
        }
    }
}

@Composable
fun FrogAnimation(type: WeatherType) {
    val infiniteTransition = rememberInfiniteTransition()
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .graphicsLayer { translationY = offsetY }
            .background(Color.White.copy(alpha = 0.3f), shape = RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = type.emoji, fontSize = 64.sp)
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewApp() {
    val sampleHourly = listOf(
        MainActivity.HourlyEntry("Jetzt", WeatherType.SUNNY),
        MainActivity.HourlyEntry("01:30", WeatherType.SUNNY),
        MainActivity.HourlyEntry("02:30", WeatherType.CLOUDY),
        MainActivity.HourlyEntry("03:00", WeatherType.RAINY),
        MainActivity.HourlyEntry("03:30", WeatherType.RAINY),
        MainActivity.HourlyEntry("04:00", WeatherType.CLOUDY),
        MainActivity.HourlyEntry("04:30", WeatherType.SUNNY),
        MainActivity.HourlyEntry("05:00", WeatherType.SUNNY)
    )
    val sampleDaily = listOf(
        MainActivity.DailyEntry("01.03", WeatherType.CLOUDY),
        MainActivity.DailyEntry("02.03", WeatherType.RAINY),
        MainActivity.DailyEntry("03.03", WeatherType.SUNNY),
        MainActivity.DailyEntry("04.03", WeatherType.CLOUDY),
        MainActivity.DailyEntry("05.03", WeatherType.SUNNY),
        MainActivity.DailyEntry("06.03", WeatherType.RAINY),
        MainActivity.DailyEntry("07.03", WeatherType.SUNNY)
    )
    FrogWeatherTheme(darkTheme = true) {
        FrogWeatherApp(
            currentWeather = WeatherType.SUNNY,
            temperature = 22.0,
            loading = false,
            hourly = sampleHourly,
            daily = sampleDaily,
            locationName = "Ort",
            onRefreshLocation = {},
            onSearch = {}
        )
    }
}
