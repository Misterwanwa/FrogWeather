package com.frogweather

import com.frogweather.network.ForecastItem
import com.frogweather.network.ForecastMain
import com.frogweather.network.WeatherCondition
import com.frogweather.service.WeatherPredictionModel
import com.frogweather.service.WeatherWarning
import com.frogweather.service.WarningSeverity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WeatherPredictionModelTest {

    @Test
    fun testSolarElevation() {
        // Germany coords (approx 50.0 lat, 10.0 lon)
        // July 5th (Day 186)
        // Noon UTC = 12:00 UTC -> Local Solar time is 12 + 10/15 = 12.67 -> near noon
        val noonUtc = Instant.parse("2026-07-05T12:00:00Z")
        val elevationNoon = WeatherPredictionModel.calculateSolarElevation(50.0, 10.0, noonUtc)
        assertTrue("Sun elevation at noon in summer should be high", elevationNoon > 45.0)

        // Midnight UTC -> Sun should be below horizon
        val midnightUtc = Instant.parse("2026-07-05T00:00:00Z")
        val elevationMidnight = WeatherPredictionModel.calculateSolarElevation(50.0, 10.0, midnightUtc)
        assertTrue("Sun elevation at midnight in summer should be negative", elevationMidnight < 0.0)
    }

    @Test
    fun testPredict24HoursNormal() {
        val lat = 50.0
        val lon = 10.0
        val now = Instant.now()
        val nowSec = now.epochSecond

        // Bounding API points:
        // A: now
        // B: now + 3 hours
        // C: now + 6 hours
        val forecastItems = listOf(
            ForecastItem(
                dt = nowSec,
                main = ForecastMain(temp = 20.0, tempMin = 18.0, tempMax = 22.0, pressure = 1010, humidity = 60),
                weather = listOf(WeatherCondition("Clear", "clear sky")),
                pop = 0.1
            ),
            ForecastItem(
                dt = nowSec + 10800,
                main = ForecastMain(temp = 24.0, tempMin = 22.0, tempMax = 26.0, pressure = 1012, humidity = 50),
                weather = listOf(WeatherCondition("Clear", "clear sky")),
                pop = 0.2
            ),
            ForecastItem(
                dt = nowSec + 21600,
                main = ForecastMain(temp = 22.0, tempMin = 20.0, tempMax = 24.0, pressure = 1011, humidity = 55),
                weather = listOf(WeatherCondition("Clouds", "few clouds")),
                pop = 0.3
            )
        )

        val predictions = WeatherPredictionModel.predict24Hours(
            lat = lat,
            lon = lon,
            currentTemp = 20.0,
            currentPressure = 1010.0,
            currentHumidity = 60,
            currentWindSpeed = 3.0,
            currentWeather = WeatherType.SUNNY,
            currentClouds = 10,
            currentPop = 0.1,
            currentDt = nowSec,
            forecastItems = forecastItems,
            warnings = emptyList()
        )

        assertEquals("Should return exactly 24 hours of forecast", 24, predictions.size)

        // The first hour (h=0) corresponds to nowSec, which is exactly an API anchor point.
        val hour0 = predictions[0]
        assertFalse("Hour 0 should not be interpolated", hour0.isInterpolated)
        assertEquals(20.0, hour0.temp, 0.01)

        // Hour 1 (h=1) falls between nowSec and nowSec + 3h. It should be interpolated.
        val hour1 = predictions[1]
        assertTrue("Hour 1 should be interpolated", hour1.isInterpolated)
        assertFalse("Hour 1 should not be extreme", hour1.isExtreme)

        // Check non-linear smoothstep for pop:
        // pop goes from 0.1 (h=0) to 0.2 (h=3).
        // At h=1 (w = 1/3), pop should be non-linear.
        // Let's verify it is between 0.1 and 0.2
        assertTrue("Hour 1 pop should be between 0.1 and 0.2", hour1.pop in 0.1..0.2)
    }

    @Test
    fun testPredict24HoursExtremeOverride() {
        val lat = 50.0
        val lon = 10.0
        val now = Instant.now()
        val nowSec = now.epochSecond

        // Forecast item B (in 3 hours) is extreme (thunderstorm)
        val forecastItems = listOf(
            ForecastItem(
                dt = nowSec,
                main = ForecastMain(temp = 20.0, tempMin = 18.0, tempMax = 22.0, pressure = 1010, humidity = 60),
                weather = listOf(WeatherCondition("Clear", "clear sky")),
                pop = 0.1
            ),
            ForecastItem(
                dt = nowSec + 10800,
                main = ForecastMain(temp = 24.0, tempMin = 22.0, tempMax = 26.0, pressure = 1012, humidity = 50),
                weather = listOf(WeatherCondition("Thunderstorm", "severe thunderstorm")),
                pop = 0.9
            )
        )

        val predictions = WeatherPredictionModel.predict24Hours(
            lat = lat,
            lon = lon,
            currentTemp = 20.0,
            currentPressure = 1010.0,
            currentHumidity = 60,
            currentWindSpeed = 3.0,
            currentWeather = WeatherType.SUNNY,
            currentClouds = 10,
            currentPop = 0.1,
            currentDt = nowSec,
            forecastItems = forecastItems,
            warnings = emptyList()
        )

        // Hour 1 (h=1) and Hour 2 (h=2) are bounded by A (normal) and B (extreme).
        // Since B is extreme, they should adopt B's extreme values directly without smooth interpolation!
        val hour1 = predictions[1]
        val hour2 = predictions[2]

        assertTrue("Hour 1 should be flagged as extreme", hour1.isExtreme)
        assertEquals("Hour 1 weather type should be thunderstorm (from extreme anchor)", WeatherType.THUNDERSTORM, hour1.weatherType)
        assertEquals("Hour 1 temp should match extreme anchor", 24.0, hour1.temp, 0.01)
        assertEquals("Hour 1 pop should match extreme anchor", 0.9, hour1.pop, 0.01)

        assertTrue("Hour 2 should be flagged as extreme", hour2.isExtreme)
        assertEquals("Hour 2 weather type should be thunderstorm (from extreme anchor)", WeatherType.THUNDERSTORM, hour2.weatherType)
    }
}
