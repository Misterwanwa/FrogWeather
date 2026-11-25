package com.frogweather.network

import com.squareup.moshi.Json

/**
 * Minimal set of classes parsed from the OpenWeatherMap JSON response.  Only the first
 * `weather` condition is considered.  The response also exposes the `main` block to read
 * the temperature, which we display in the UI.
 */
data class WeatherResponse(
    val coord: Coord,
    val weather: List<WeatherCondition>,
    val main: TemperatureInfo,
    val name: String?,
    val wind: WindInfo?,
    val sys: SysInfo?,
    val visibility: Int?,
    val rain: RainInfo?,
    val clouds: CloudsInfo? = null
)

data class Coord(
    val lon: Double,
    val lat: Double
)

data class WeatherCondition(
    val main: String,
    val description: String
)

data class TemperatureInfo(
    val temp: Double,
    @Json(name = "feels_like") val feelsLike: Double,
    @Json(name = "temp_min") val tempMin: Double,
    @Json(name = "temp_max") val tempMax: Double,
    val pressure: Int = 0,
    val humidity: Int = 0
)

data class WindInfo(
    val speed: Double,
    val deg: Int
)

data class SysInfo(
    val country: String? = null,
    val sunrise: Long,
    val sunset: Long
)

data class RainInfo(
    @Json(name = "1h") val oneHour: Double = 0.0
)

// forecast API (data/2.5/forecast — 3h steps, 5 days)

data class ForecastResponse(
    val list: List<ForecastItem>,
    val city: ForecastCity
)

data class ForecastCity(
    val timezone: Int
)

data class ForecastItem(
    val dt: Long,
    val main: ForecastMain,
    val weather: List<WeatherCondition>,
    val pop: Double = 0.0,
    val wind: WindInfo? = null,
    val clouds: CloudsInfo? = null,
    val rain: ForecastRainInfo? = null
)

data class ForecastRainInfo(
    @Json(name = "3h") val threeHour: Double = 0.0
)

data class ForecastMain(
    val temp: Double,
    @Json(name = "temp_min") val tempMin: Double,
    @Json(name = "temp_max") val tempMax: Double,
    val pressure: Int = 0,
    val humidity: Int = 0
)

data class CloudsInfo(
    val all: Int = 0
)

// Air Pollution API (data/2.5/air_pollution — free tier)

data class AirPollutionResponse(
    val list: List<AirPollutionItem>
)

data class AirPollutionItem(
    val main: AirPollutionMain,
    val components: AirComponents
)

data class AirPollutionMain(
    val aqi: Int
)

data class AirComponents(
    val pm2_5: Double = 0.0,
    val pm10: Double = 0.0,
    val no2: Double = 0.0,
    val o3: Double = 0.0
)

// Data holder for the detail grid passed to the UI

data class WeatherDetails(
    val windSpeed: Double?,
    val windDeg: Int?,
    val sunrise: Long?,
    val sunset: Long?,
    val pressure: Int?,
    val humidity: Int?,
    val visibilityM: Int?,
    val rainMm: Double?,
    val aqi: Int?,
    val temperature: Double?,
    val uvIndex: Double? = null,
    val normalTempC: Double? = null
)
