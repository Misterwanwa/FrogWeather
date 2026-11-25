package com.frogweather.route

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.frogweather.analysis.HourlyWeatherPoint
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class RouteTransportMode(
    val osrmProfile: String,
    val displayName: String,
    val googleTravelMode: String,
    val googleNavCode: String
) {
    DRIVING("driving", "Auto", "driving", "d"),
    CYCLING("cycling", "Fahrrad", "bicycling", "b"),
    FOOT("foot", "Zu Fuß", "walking", "w")
}

data class RoutePoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val offsetMinutesFromStart: Int
)

data class RouteSegmentWeather(
    val pointName: String,
    val latitude: Double,
    val longitude: Double,
    val offsetMinutesFromStart: Int,
    val timestampEpochMillis: Long,
    val tempC: Double,
    val precipMm: Double,
    val precipProb: Int,
    val windKmh: Double,
    val gustKmh: Double,
    val weatherCode: Int,
    val isHazardous: Boolean
)

data class RouteWeatherResult(
    val origin: String,
    val destination: String,
    val mode: RouteTransportMode,
    val targetArrivalMillis: Long,
    val totalDurationMinutes: Int,
    val defaultDepartureMillis: Long,
    val recommendedDepartureMillis: Long,
    val segments: List<RouteSegmentWeather>,
    val arrivalWeather: RouteSegmentWeather?,
    val departureReason: String,
    val isPastTargetArrival: Boolean,
    val hasWeatherHazard: Boolean
)

object RouteWeatherCalculator {

    private const val STEP_MILLIS = 30 * 60_000L
    private const val SEARCH_WINDOW_STEPS = 6 // +/- 3 hours in 30-min increments
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

    fun computeRouteAndDeparture(
        origin: String,
        destination: String,
        mode: RouteTransportMode,
        targetArrivalMillis: Long,
        routePoints: List<RoutePoint>,
        hourlyForecasts: List<HourlyWeatherPoint>,
        nowMillis: Long = System.currentTimeMillis()
    ): RouteWeatherResult {
        return computeRouteWithSegmentForecasts(
            origin = origin,
            destination = destination,
            mode = mode,
            targetArrivalMillis = targetArrivalMillis,
            routePoints = routePoints,
            segmentForecasts = emptyList(),
            fallbackForecasts = hourlyForecasts,
            nowMillis = nowMillis
        )
    }

    fun computeRouteWithSegmentForecasts(
        origin: String,
        destination: String,
        mode: RouteTransportMode,
        targetArrivalMillis: Long,
        routePoints: List<RoutePoint>,
        segmentForecasts: List<List<HourlyWeatherPoint>>,
        fallbackForecasts: List<HourlyWeatherPoint>,
        nowMillis: Long = System.currentTimeMillis()
    ): RouteWeatherResult {
        val sortedFallback = dedupeWorstCaseByEpoch(fallbackForecasts)
        val sortedPerSegment = segmentForecasts.map { list -> dedupeWorstCaseByEpoch(list) }

        val totalDurationMinutes = routePoints.maxOfOrNull { it.offsetMinutesFromStart.coerceAtLeast(0) } ?: 0
        val durationMillis = totalDurationMinutes * 60_000L
        val isPastTargetArrival = targetArrivalMillis < nowMillis

        val rawDefaultDeparture = targetArrivalMillis - durationMillis
        val clampedDefaultDeparture = maxOf(nowMillis, rawDefaultDeparture)

        if (routePoints.isEmpty()) {
            val arrivalForecast = findNearestForecast(sortedFallback, targetArrivalMillis)
            val arrivalSeg = arrivalForecast?.let { pt ->
                toSegmentWeather(
                    point = RoutePoint(
                        name = destination.ifBlank { "Ziel" },
                        latitude = 0.0,
                        longitude = 0.0,
                        offsetMinutesFromStart = 0
                    ),
                    timestampMillis = targetArrivalMillis,
                    forecast = pt,
                    mode = mode
                )
            }
            return RouteWeatherResult(
                origin = origin,
                destination = destination,
                mode = mode,
                targetArrivalMillis = targetArrivalMillis,
                totalDurationMinutes = 0,
                defaultDepartureMillis = clampedDefaultDeparture,
                recommendedDepartureMillis = clampedDefaultDeparture,
                segments = emptyList(),
                arrivalWeather = arrivalSeg,
                departureReason = if (isPastTargetArrival) {
                    "Gewünschte Ankunftszeit liegt in der Vergangenheit – sofortige Abfahrt empfohlen."
                } else {
                    "Keine Wegpunkte übergeben – geplante Abfahrt beibehalten."
                },
                isPastTargetArrival = isPastTargetArrival,
                hasWeatherHazard = arrivalSeg?.isHazardous ?: false
            )
        }

        // Planned trip segments & arrivalWeather evaluated at the user's planned schedule (targetArrivalMillis)
        val plannedDeparture = targetArrivalMillis - durationMillis
        val plannedSegments = evaluateTripSegments(
            departureMillis = plannedDeparture,
            routePoints = routePoints,
            sortedPerSegment = sortedPerSegment,
            sortedFallback = sortedFallback,
            mode = mode
        )
        val arrivalWeather = plannedSegments.lastOrNull()
        val hasWeatherHazard = plannedSegments.any { it.isHazardous }

        // Evaluate candidate departures starting >= nowMillis
        val candidates = buildList {
            add(clampedDefaultDeparture)
            for (step in -SEARCH_WINDOW_STEPS..SEARCH_WINDOW_STEPS) {
                val cand = rawDefaultDeparture + step * STEP_MILLIS
                if (cand >= nowMillis) add(cand)
            }
        }.distinct().sorted()

        val scoredCandidates = candidates.map { candDep ->
            val segs = evaluateTripSegments(
                departureMillis = candDep,
                routePoints = routePoints,
                sortedPerSegment = sortedPerSegment,
                sortedFallback = sortedFallback,
                mode = mode
            )
            val penalty = scoreTripPenalty(
                segments = segs,
                candidateDepartureMillis = candDep,
                referenceDepartureMillis = rawDefaultDeparture
            )
            Triple(candDep, segs, penalty)
        }

        val defaultTrip = scoredCandidates.firstOrNull { it.first == clampedDefaultDeparture }
            ?: scoredCandidates.first()
        val bestTrip = scoredCandidates.minByOrNull { it.third } ?: defaultTrip

        val defaultHazardous = defaultTrip.second.any { it.isHazardous }
        val defaultMaxPrecip = defaultTrip.second.maxOfOrNull { it.precipMm } ?: 0.0

        val recommendedDepartureMillis = if (!defaultHazardous && defaultMaxPrecip < 0.5) {
            clampedDefaultDeparture
        } else {
            bestTrip.first
        }

        val departureReason = buildDepartureReason(
            isPastTargetArrival = isPastTargetArrival,
            clampedDefaultDeparture = clampedDefaultDeparture,
            recommendedDeparture = recommendedDepartureMillis,
            defaultSegments = defaultTrip.second,
            recommendedSegments = bestTrip.second,
            mode = mode
        )

        return RouteWeatherResult(
            origin = origin,
            destination = destination,
            mode = mode,
            targetArrivalMillis = targetArrivalMillis,
            totalDurationMinutes = totalDurationMinutes,
            defaultDepartureMillis = clampedDefaultDeparture,
            recommendedDepartureMillis = recommendedDepartureMillis,
            segments = plannedSegments,
            arrivalWeather = arrivalWeather,
            departureReason = departureReason,
            isPastTargetArrival = isPastTargetArrival,
            hasWeatherHazard = hasWeatherHazard
        )
    }

    fun buildMapsHandoffUri(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        destLabel: String? = null,
        mode: RouteTransportMode = RouteTransportMode.DRIVING
    ): String {
        val oLat = formatLat(originLat)
        val oLon = formatLon(originLon)
        val dLat = formatLat(destLat)
        val dLon = formatLon(destLon)
        val encodedLabel = destLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { label ->
            URLEncoder.encode(label, "UTF-8").replace("+", "%20")
        }
        val base = "https://www.google.com/maps/dir/?api=1&origin=$oLat,$oLon&destination=$dLat,$dLon&travelmode=${mode.googleTravelMode}"
        return if (encodedLabel != null) "$base&destination_name=$encodedLabel" else base
    }

    fun launchExternalMaps(
        context: Context,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        destLabel: String? = null,
        mode: RouteTransportMode = RouteTransportMode.DRIVING
    ): Boolean {
        val dLat = formatLat(destLat)
        val dLon = formatLon(destLon)
        val encodedLabel = URLEncoder.encode(destLabel?.trim().orEmpty().ifBlank { "$dLat,$dLon" }, "UTF-8")

        val candidates = listOf(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=$dLat,$dLon&mode=${mode.googleNavCode}")
            ).setPackage("com.google.android.apps.maps"),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(buildMapsHandoffUri(originLat, originLon, destLat, destLon, destLabel, mode))
            ),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:$dLat,$dLon?q=$dLat,$dLon($encodedLabel)")
            )
        )

        val pm = context.packageManager
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) {
                return try {
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    private fun evaluateTripSegments(
        departureMillis: Long,
        routePoints: List<RoutePoint>,
        sortedPerSegment: List<List<HourlyWeatherPoint>>,
        sortedFallback: List<HourlyWeatherPoint>,
        mode: RouteTransportMode
    ): List<RouteSegmentWeather> {
        return routePoints.mapIndexed { idx, pt ->
            val ts = departureMillis + pt.offsetMinutesFromStart.coerceAtLeast(0) * 60_000L
            val series = sortedPerSegment.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: sortedFallback
            val forecast = findNearestForecast(series, ts)
            if (forecast != null) {
                toSegmentWeather(pt, ts, forecast, mode)
            } else {
                RouteSegmentWeather(
                    pointName = pt.name,
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    offsetMinutesFromStart = pt.offsetMinutesFromStart,
                    timestampEpochMillis = ts,
                    tempC = 15.0,
                    precipMm = 0.0,
                    precipProb = 0,
                    windKmh = 0.0,
                    gustKmh = 0.0,
                    weatherCode = 1,
                    isHazardous = false
                )
            }
        }
    }

    private fun toSegmentWeather(
        point: RoutePoint,
        timestampMillis: Long,
        forecast: HourlyWeatherPoint,
        mode: RouteTransportMode
    ): RouteSegmentWeather {
        val temp = if (forecast.tempC.isFinite()) forecast.tempC else 15.0
        val precipMm = if (forecast.precipMm.isFinite() && forecast.precipMm >= 0.0) forecast.precipMm else 0.0
        val precipProb = forecast.precipProb.coerceIn(0, 100)
        val wind = if (forecast.windKmh.isFinite() && forecast.windKmh >= 0.0) forecast.windKmh else 0.0
        val gust = if (forecast.gustKmh.isFinite() && forecast.gustKmh >= 0.0) forecast.gustKmh else wind
        val cape = if (forecast.capeJkg.isFinite() && forecast.capeJkg >= 0.0) forecast.capeJkg else 0.0

        return RouteSegmentWeather(
            pointName = point.name,
            latitude = point.latitude,
            longitude = point.longitude,
            offsetMinutesFromStart = point.offsetMinutesFromStart,
            timestampEpochMillis = timestampMillis,
            tempC = temp,
            precipMm = precipMm,
            precipProb = precipProb,
            windKmh = wind,
            gustKmh = gust,
            weatherCode = forecast.weatherCode,
            isHazardous = isSegmentHazardous(
                mode = mode,
                precipMm = precipMm,
                precipProb = precipProb,
                windKmh = wind,
                gustKmh = gust,
                capeJkg = cape,
                weatherCode = forecast.weatherCode
            )
        )
    }

    private fun isSegmentHazardous(
        mode: RouteTransportMode,
        precipMm: Double,
        precipProb: Int,
        windKmh: Double,
        gustKmh: Double,
        capeJkg: Double,
        weatherCode: Int
    ): Boolean {
        val thunderstorm = weatherCode in setOf(95, 96, 99) || capeJkg >= 1200.0
        if (thunderstorm) return true
        val severeCode = weatherCode in setOf(57, 67, 75, 82, 86)
        if (severeCode) return true

        return when (mode) {
            RouteTransportMode.DRIVING ->
                precipMm >= 4.0 || gustKmh >= 75.0 || (precipProb >= 80 && precipMm >= 1.5)
            RouteTransportMode.CYCLING ->
                precipMm >= 0.8 || precipProb >= 65 || windKmh >= 32.0 || gustKmh >= 48.0
            RouteTransportMode.FOOT ->
                precipMm >= 1.0 || precipProb >= 65 || windKmh >= 45.0 || gustKmh >= 65.0
        }
    }

    private fun scoreTripPenalty(
        segments: List<RouteSegmentWeather>,
        candidateDepartureMillis: Long,
        referenceDepartureMillis: Long
    ): Double {
        val hazardPenalty = segments.count { it.isHazardous } * 1000.0
        val precipPenalty = segments.sumOf { it.precipMm * 40.0 + it.precipProb * 0.4 }
        val windPenalty = segments.sumOf { (it.windKmh - 20.0).coerceAtLeast(0.0) * 1.5 }
        val shiftHours = abs(candidateDepartureMillis - referenceDepartureMillis).toDouble() / 3_600_000.0
        // Slight preference for earlier departure over later departure on equal weather
        val lateBias = if (candidateDepartureMillis > referenceDepartureMillis) 0.25 else 0.0
        return hazardPenalty + precipPenalty + windPenalty + shiftHours * 4.0 + lateBias
    }

    private fun findNearestForecast(
        sortedForecasts: List<HourlyWeatherPoint>,
        targetMillis: Long
    ): HourlyWeatherPoint? {
        if (sortedForecasts.isEmpty()) return null
        return sortedForecasts.minByOrNull { absDiffULong(it.epochMillis, targetMillis) }
    }

    private fun dedupeWorstCaseByEpoch(forecasts: List<HourlyWeatherPoint>): List<HourlyWeatherPoint> {
        if (forecasts.size <= 1) return forecasts
        val byEpoch = linkedMapOf<Long, HourlyWeatherPoint>()
        for (pt in forecasts) {
            val existing = byEpoch[pt.epochMillis]
            if (existing == null) {
                byEpoch[pt.epochMillis] = pt
            } else {
                byEpoch[pt.epochMillis] = mergeWorstCase(existing, pt)
            }
        }
        return byEpoch.values.sortedBy { it.epochMillis }
    }

    private fun mergeWorstCase(a: HourlyWeatherPoint, b: HourlyWeatherPoint): HourlyWeatherPoint {
        val aPrecip = if (a.precipMm.isFinite() && a.precipMm >= 0.0) a.precipMm else 0.0
        val bPrecip = if (b.precipMm.isFinite() && b.precipMm >= 0.0) b.precipMm else 0.0
        val aWind = if (a.windKmh.isFinite() && a.windKmh >= 0.0) a.windKmh else 0.0
        val bWind = if (b.windKmh.isFinite() && b.windKmh >= 0.0) b.windKmh else 0.0
        val aGust = if (a.gustKmh.isFinite() && a.gustKmh >= 0.0) a.gustKmh else aWind
        val bGust = if (b.gustKmh.isFinite() && b.gustKmh >= 0.0) b.gustKmh else bWind
        val aCape = if (a.capeJkg.isFinite() && a.capeJkg >= 0.0) a.capeJkg else 0.0
        val bCape = if (b.capeJkg.isFinite() && b.capeJkg >= 0.0) b.capeJkg else 0.0
        val aUv = if (a.uvIndex.isFinite() && a.uvIndex >= 0.0) a.uvIndex else 0.0
        val bUv = if (b.uvIndex.isFinite() && b.uvIndex >= 0.0) b.uvIndex else 0.0
        val worstCode = if (weatherCodeSeverity(b.weatherCode) > weatherCodeSeverity(a.weatherCode)) {
            b.weatherCode
        } else {
            a.weatherCode
        }
        val bWorseOverall = weatherCodeSeverity(b.weatherCode) > weatherCodeSeverity(a.weatherCode) ||
            bPrecip > aPrecip || bGust > aGust
        val tempC = when {
            bWorseOverall && b.tempC.isFinite() -> b.tempC
            a.tempC.isFinite() -> a.tempC
            else -> b.tempC
        }
        val pressureHpa = when {
            bWorseOverall && b.pressureHpa.isFinite() -> b.pressureHpa
            a.pressureHpa.isFinite() -> a.pressureHpa
            else -> b.pressureHpa
        }
        return HourlyWeatherPoint(
            epochMillis = a.epochMillis,
            tempC = tempC,
            precipMm = maxOf(aPrecip, bPrecip),
            precipProb = maxOf(a.precipProb.coerceIn(0, 100), b.precipProb.coerceIn(0, 100)),
            windKmh = maxOf(aWind, bWind),
            gustKmh = maxOf(aGust, bGust),
            uvIndex = maxOf(aUv, bUv),
            capeJkg = maxOf(aCape, bCape),
            weatherCode = worstCode,
            humidityPct = maxOf(a.humidityPct, b.humidityPct),
            cloudCoverPct = maxOf(a.cloudCoverPct, b.cloudCoverPct),
            pressureHpa = pressureHpa
        )
    }

    private fun weatherCodeSeverity(code: Int): Int = when (code) {
        95, 96, 99 -> 3000 + code
        57, 67, 75, 82, 86 -> 2000 + code
        else -> code
    }

    private fun absDiffULong(a: Long, b: Long): ULong =
        if (a >= b) a.toULong() - b.toULong() else b.toULong() - a.toULong()

    private fun buildDepartureReason(
        isPastTargetArrival: Boolean,
        clampedDefaultDeparture: Long,
        recommendedDeparture: Long,
        defaultSegments: List<RouteSegmentWeather>,
        recommendedSegments: List<RouteSegmentWeather>,
        mode: RouteTransportMode
    ): String {
        val zone = ZoneId.systemDefault()
        val recTime = Instant.ofEpochMilli(recommendedDeparture).atZone(zone).format(TIME_FMT)
        if (isPastTargetArrival) {
            return "Gewünschte Ankunft liegt in der Vergangenheit – nächste Abfahrt ab $recTime Uhr."
        }

        val defaultHazard = defaultSegments.any { it.isHazardous }
        val recHazard = recommendedSegments.any { it.isHazardous }
        val diffMin = ((recommendedDeparture - clampedDefaultDeparture) / 60_000L).toInt()

        return when {
            diffMin == 0 && !defaultHazard ->
                "Route für ${mode.displayName} überwiegend trocken – planmäßige Abfahrt um $recTime Uhr optimal."
            diffMin == 0 && defaultHazard ->
                "Wetterwarnung entlang der Strecke (${mode.displayName}) – kein deutlich besseres Zeitfenster im Umfeld (±3 Std.)."
            diffMin < 0 && !recHazard ->
                "Wetterwarnung zur Standard-Abfahrt → ${abs(diffMin)} Min. früher um $recTime Uhr starten für bessere Bedingungen."
            diffMin > 0 && !recHazard ->
                "Niederschlag/Wind zur Standard-Abfahrt → $diffMin Min. später um $recTime Uhr starten für ruhigere Strecke."
            else ->
                "Empfohlene Abfahrt um $recTime Uhr reduziert Wetterrisiko für ${mode.displayName}."
        }
    }

    private fun formatLat(value: Double): String {
        val safe = if (value.isFinite()) value.coerceIn(-90.0, 90.0) else 0.0
        return String.format(Locale.US, "%.6f", safe)
    }

    private fun formatLon(value: Double): String {
        val safe = if (value.isFinite()) value.coerceIn(-180.0, 180.0) else 0.0
        return String.format(Locale.US, "%.6f", safe)
    }
}
