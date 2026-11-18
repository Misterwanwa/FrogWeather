package com.frogweather.service

import com.frogweather.WeatherType
import com.frogweather.network.ForecastItem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.*

object WeatherPredictionModel {

    data class AnchorPoint(
        val time: Instant,
        val temp: Double,
        val pressure: Double,
        val humidity: Int,
        val pop: Double,      // 0.0 to 1.0
        val cloudiness: Int,  // 0 to 100
        val windSpeed: Double,
        val weatherType: WeatherType,
        val isExtreme: Boolean
    )

    data class PredictedHour(
        val time: Instant,
        val temp: Double,
        val pressure: Double,
        val humidity: Int,
        val pop: Double,
        val cloudiness: Int,
        val windSpeed: Double,
        val weatherType: WeatherType,
        val isInterpolated: Boolean,
        val isExtreme: Boolean,
        val debugSource: String
    )

    /**
     * Calculates the solar elevation angle in degrees for a given location and UTC time.
     */
    fun calculateSolarElevation(lat: Double, lon: Double, time: Instant): Double {
        // 1. Day of year (1-365)
        val localDateTime = LocalDateTime.ofInstant(time, ZoneOffset.UTC)
        val dayOfYear = localDateTime.dayOfYear
        
        // 2. Solar declination angle (radians)
        val declination = 0.40928 * sin(2.0 * Math.PI * (dayOfYear - 80) / 365.25)
        
        // 3. Hour of the day in UTC
        val hourUtc = localDateTime.hour + localDateTime.minute / 60.0 + localDateTime.second / 3600.0
        
        // 4. Local solar time (15 degrees of longitude = 1 hour)
        val solarTime = (hourUtc + lon / 15.0) % 24.0
        
        // 5. Hour angle (radians)
        val hourAngle = (solarTime - 12.0) * Math.PI / 12.0
        
        // 6. Latitude in radians
        val latRad = lat * Math.PI / 180.0
        
        // 7. Sine of solar elevation
        val sinElevation = sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)
        
        // 8. Solar elevation in degrees
        return asin(sinElevation.coerceIn(-1.0, 1.0)) * 180.0 / Math.PI
    }

    private fun getDailyTempRange(forecastItems: List<ForecastItem>): Double {
        if (forecastItems.isEmpty()) return 6.0
        val temps = forecastItems.map { it.main.temp }
        val maxTemp = temps.maxOrNull() ?: 20.0
        val minTemp = temps.minOrNull() ?: 10.0
        return (maxTemp - minTemp).coerceIn(4.0, 20.0)
    }

    private fun checkIsExtremeAnchor(
        temp: Double,
        windSpeed: Double,
        type: WeatherType,
        time: Instant,
        warnings: List<WeatherWarning>
    ): Boolean {
        // 1. DWD Warning check
        val activeWarning = warnings.firstOrNull { warning ->
            (warning.severity == WarningSeverity.SEVERE || warning.severity == WarningSeverity.EXTREME) &&
            warning.onsetInstant != null && warning.expiresInstant != null &&
            !time.isBefore(warning.onsetInstant) && !time.isAfter(warning.expiresInstant)
        }
        if (activeWarning != null) return true
        
        // 2. Physical thresholds
        return checkIsExtremeValues(temp, windSpeed, type)
    }

    private fun checkIsExtremeValues(temp: Double, windSpeed: Double, type: WeatherType): Boolean {
        return temp > 35.0 || temp < -10.0 || windSpeed > 15.0 || type == WeatherType.THUNDERSTORM
    }

    private fun mapWarningToWeatherType(event: String): WeatherType {
        val lower = event.lowercase()
        return when {
            lower.contains("gewitter") || lower.contains("thunderstorm") || lower.contains("sturm") -> WeatherType.THUNDERSTORM
            lower.contains("schnee") || lower.contains("snow") || lower.contains("frost") || lower.contains("glätte") -> WeatherType.SNOW
            lower.contains("regen") || lower.contains("rain") || lower.contains("niederschlag") -> WeatherType.RAINY
            else -> WeatherType.OTHER
        }
    }

    private fun checkDwdWarning(t: Instant, warnings: List<WeatherWarning>): WeatherWarning? {
        return warnings.firstOrNull { warning ->
            (warning.severity == WarningSeverity.SEVERE || warning.severity == WarningSeverity.EXTREME) &&
            warning.onsetInstant != null && warning.expiresInstant != null &&
            !t.isBefore(warning.onsetInstant) && !t.isAfter(warning.expiresInstant)
        }
    }

    private fun createPredictedHourFromAnchor(
        time: Instant,
        anchor: AnchorPoint,
        isInterpolated: Boolean,
        isExtreme: Boolean
    ): PredictedHour {
        return PredictedHour(
            time = time,
            temp = anchor.temp,
            pressure = anchor.pressure,
            humidity = anchor.humidity,
            pop = anchor.pop,
            cloudiness = anchor.cloudiness,
            windSpeed = anchor.windSpeed,
            weatherType = anchor.weatherType,
            isInterpolated = isInterpolated,
            isExtreme = isExtreme,
            debugSource = "Anchor Copy"
        )
    }

    fun predict24Hours(
        lat: Double,
        lon: Double,
        currentTemp: Double?,
        currentPressure: Double?,
        currentHumidity: Int?,
        currentWindSpeed: Double?,
        currentWeather: WeatherType?,
        currentClouds: Int?,
        currentPop: Double?,
        currentDt: Long?,
        forecastItems: List<ForecastItem>,
        warnings: List<WeatherWarning>
    ): List<PredictedHour> {
        val result = mutableListOf<PredictedHour>()
        
        // 1. Compile anchor points
        val anchors = mutableListOf<AnchorPoint>()
        
        // Add current weather as the first anchor if available
        if (currentTemp != null && currentDt != null) {
            val currentTime = Instant.ofEpochSecond(currentDt)
            val currentType = currentWeather ?: WeatherType.OTHER
            val isExtreme = checkIsExtremeAnchor(currentTemp, currentWindSpeed ?: 0.0, currentType, currentTime, warnings)
            anchors.add(
                AnchorPoint(
                    time = currentTime,
                    temp = currentTemp,
                    pressure = currentPressure ?: 1013.0,
                    humidity = currentHumidity ?: 50,
                    pop = currentPop ?: 0.0,
                    cloudiness = currentClouds ?: 50,
                    windSpeed = currentWindSpeed ?: 0.0,
                    weatherType = currentType,
                    isExtreme = isExtreme
                )
            )
        }
        
        // Add forecast items
        forecastItems.forEach { item ->
            val time = Instant.ofEpochSecond(item.dt)
            val temp = item.main.temp
            val pressure = item.main.pressure.toDouble()
            val humidity = item.main.humidity
            val pop = item.pop
            val cloudiness = item.clouds?.all ?: 50
            val windSpeed = item.wind?.speed ?: 0.0
            val type = WeatherType.fromString(item.weather.firstOrNull()?.main ?: "")
            val isExtreme = checkIsExtremeAnchor(temp, windSpeed, type, time, warnings)
            
            // Avoid duplicate timestamps
            if (anchors.none { it.time.epochSecond == time.epochSecond }) {
                anchors.add(
                    AnchorPoint(
                        time = time,
                        temp = temp,
                        pressure = pressure,
                        humidity = humidity,
                        pop = pop,
                        cloudiness = cloudiness,
                        windSpeed = windSpeed,
                        weatherType = type,
                        isExtreme = isExtreme
                    )
                )
            }
        }
        
        // Sort anchors by time
        anchors.sortBy { it.time }
        
        if (anchors.isEmpty()) {
            return emptyList()
        }
        
        // 2. Generate hourly predictions for the next 24 hours
        // Start from the current hour (rounded down to the nearest hour)
        val now = Instant.now()
        val startLocalDateTime = LocalDateTime.ofInstant(now, ZoneId.systemDefault()).withMinute(0).withSecond(0).withNano(0)
        val startTime = startLocalDateTime.atZone(ZoneId.systemDefault()).toInstant()
        
        val tempRange = getDailyTempRange(forecastItems)
        val kt = tempRange / 4.0 // Dynamic temperature scaling factor
        
        for (h in 0 until 24) {
            val targetTime = startTime.plusSeconds(h * 3600L)
            
            // Check if there is an active severe/extreme warning at this exact hour
            val activeWarning = checkDwdWarning(targetTime, warnings)
            val hasWarning = activeWarning != null
            
            // Find bounding anchors
            val anchorA = anchors.lastOrNull { !it.time.isAfter(targetTime) }
            val anchorB = anchors.firstOrNull { it.time.isAfter(targetTime) }
            
            val predicted = when {
                // If it falls exactly on or before the first anchor
                anchorA == null && anchorB != null -> {
                    createPredictedHourFromAnchor(targetTime, anchorB, isInterpolated = false, isExtreme = hasWarning || anchorB.isExtreme)
                }
                // If it falls exactly on or after the last anchor
                anchorA != null && anchorB == null -> {
                    createPredictedHourFromAnchor(targetTime, anchorA, isInterpolated = false, isExtreme = hasWarning || anchorA.isExtreme)
                }
                // Bounded between anchorA and anchorB
                anchorA != null && anchorB != null -> {
                    val ta = anchorA.time.epochSecond
                    val tb = anchorB.time.epochSecond
                    val t = targetTime.epochSecond
                    
                    if (ta == t) {
                        createPredictedHourFromAnchor(targetTime, anchorA, isInterpolated = false, isExtreme = hasWarning || anchorA.isExtreme)
                    } else if (tb == t) {
                        createPredictedHourFromAnchor(targetTime, anchorB, isInterpolated = false, isExtreme = hasWarning || anchorB.isExtreme)
                    } else {
                        // Interpolate!
                        val w = (t - ta).toDouble() / (tb - ta).toDouble()
                        
                        // Check if either bounding anchor is extreme
                        val extremeAnchor = when {
                            anchorA.isExtreme && anchorB.isExtreme -> if (w < 0.5) anchorA else anchorB
                            anchorA.isExtreme -> anchorA
                            anchorB.isExtreme -> anchorB
                            else -> null
                        }
                        
                        if (extremeAnchor != null) {
                            // Do not interpolate - take directly from the extreme anchor
                            PredictedHour(
                                time = targetTime,
                                temp = extremeAnchor.temp,
                                pressure = extremeAnchor.pressure,
                                humidity = extremeAnchor.humidity,
                                pop = extremeAnchor.pop,
                                cloudiness = extremeAnchor.cloudiness,
                                windSpeed = extremeAnchor.windSpeed,
                                weatherType = extremeAnchor.weatherType,
                                isInterpolated = false, // Directly taken
                                isExtreme = true,
                                debugSource = "Extreme Anchor Override"
                            )
                        } else {
                            // Normal interpolation
                            // A. Temperature (Solar lag-based)
                            val tempLinear = anchorA.temp + w * (anchorB.temp - anchorA.temp)
                            val sa = sin(calculateSolarElevation(lat, lon, anchorA.time.minusSeconds(7200)) * Math.PI / 180.0)
                            val sb = sin(calculateSolarElevation(lat, lon, anchorB.time.minusSeconds(7200)) * Math.PI / 180.0)
                            val st = sin(calculateSolarElevation(lat, lon, targetTime.minusSeconds(7200)) * Math.PI / 180.0)
                            val tempCorrection = kt * ((st - sa) - w * (sb - sa))
                            val tempInterpolated = tempLinear + tempCorrection
                            
                            // B. Air Pressure (Atmospheric tide-based)
                            val pressLinear = anchorA.pressure + w * (anchorB.pressure - anchorA.pressure)
                            val spa = cos(2.0 * Math.PI * (LocalDateTime.ofInstant(anchorA.time, ZoneId.systemDefault()).hour - 10) / 12.0)
                            val spb = cos(2.0 * Math.PI * (LocalDateTime.ofInstant(anchorB.time, ZoneId.systemDefault()).hour - 10) / 12.0)
                            val spt = cos(2.0 * Math.PI * (LocalDateTime.ofInstant(targetTime, ZoneId.systemDefault()).hour - 10) / 12.0)
                            val pressCorrection = 1.2 * ((spt - spa) - w * (spb - spa))
                            val pressInterpolated = pressLinear + pressCorrection
                            
                            // C. Pop & Cloudiness (Non-linear smoothstep)
                            val wSmooth = w * w * (3.0 - 2.0 * w)
                            val popInterpolated = anchorA.pop + wSmooth * (anchorB.pop - anchorA.pop)
                            val cloudsInterpolated = anchorA.cloudiness + wSmooth * (anchorB.cloudiness - anchorA.cloudiness)
                            
                            // D. Wind speed
                            val windInterpolated = anchorA.windSpeed + wSmooth * (anchorB.windSpeed - anchorA.windSpeed)
                            
                            // E. Weather condition (derived dynamically)
                            val interpolatedType = when {
                                popInterpolated > 0.35 && tempInterpolated < 1.0 -> WeatherType.SNOW
                                popInterpolated > 0.35 -> {
                                    if (anchorA.weatherType == WeatherType.THUNDERSTORM || anchorB.weatherType == WeatherType.THUNDERSTORM) {
                                        WeatherType.THUNDERSTORM
                                    } else {
                                        WeatherType.RAINY
                                    }
                                }
                                cloudsInterpolated > 50 -> WeatherType.CLOUDY
                                else -> WeatherType.SUNNY
                            }
                            
                            val isHourExtreme = hasWarning || checkIsExtremeValues(tempInterpolated, windInterpolated, interpolatedType)
                            
                            PredictedHour(
                                time = targetTime,
                                temp = tempInterpolated,
                                pressure = pressInterpolated,
                                humidity = (anchorA.humidity + wSmooth * (anchorB.humidity - anchorA.humidity)).toInt(),
                                pop = popInterpolated,
                                cloudiness = cloudsInterpolated.toInt(),
                                windSpeed = windInterpolated,
                                weatherType = if (isHourExtreme && hasWarning) {
                                    // Override weather type with extreme type from warning if possible
                                    mapWarningToWeatherType(activeWarning.title)
                                } else {
                                    interpolatedType
                                },
                                isInterpolated = true,
                                isExtreme = isHourExtreme,
                                debugSource = "Interpolated"
                            )
                        }
                    }
                }
                else -> {
                    // Fallback
                    PredictedHour(targetTime, 0.0, 1013.0, 50, 0.0, 0, 0.0, WeatherType.OTHER, isInterpolated = false, isExtreme = false, debugSource = "Fallback")
                }
            }
            result.add(predicted)
        }
        
        return result
    }
}
