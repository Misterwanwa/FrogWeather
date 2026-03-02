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
    val name: String?
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
    val temp: Double
)

// one call API structures

data class OneCallResponse(
    val timezone_offset: Int,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>
)

data class HourlyForecast(
    val dt: Long,
    val temp: Double,
    val weather: List<WeatherCondition>
)

data class DailyForecast(
    val dt: Long,
    val temp: DailyTemp,
    val weather: List<WeatherCondition>
)

data class DailyTemp(
    val min: Double,
    val max: Double
)
