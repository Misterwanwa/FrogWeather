package com.frogweather.network

import com.frogweather.route.RoutePoint
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import kotlin.math.roundToInt

data class OsrmRawResponse(
    val code: String? = null,
    val message: String? = null,
    val routes: List<OsrmRawRoute>? = null
)

data class OsrmRawRoute(
    val duration: Double? = null,
    val distance: Double? = null,
    val geometry: OsrmRawGeometry? = null,
    val legs: List<OsrmRawLeg>? = null
)

data class OsrmRawGeometry(
    val type: String? = null,
    val coordinates: List<List<Double?>?>? = null
)

data class OsrmRawLeg(
    val duration: Double? = null,
    val distance: Double? = null,
    val summary: String? = null,
    val steps: List<OsrmRawStep>? = null
)

data class OsrmRawStep(
    val duration: Double? = null,
    val distance: Double? = null,
    val name: String? = null
)

data class OsrmCoordPoint(
    val latitude: Double,
    val longitude: Double
)

data class OsrmRouteResult(
    val isSuccess: Boolean,
    val code: String,
    val durationSeconds: Double,
    val distanceMeters: Double,
    val points: List<OsrmCoordPoint>,
    val errorMessage: String
)

object OsrmRouteParser {

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val adapter by lazy { moshi.adapter(OsrmRawResponse::class.java) }

    fun parse(json: String): OsrmRouteResult {
        if (json.isBlank()) {
            return OsrmRouteResult(
                isSuccess = false,
                code = "EmptyResponse",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "Leere Antwort vom OSRM-Routingdienst."
            )
        }
        return try {
            val raw = adapter.fromJson(json)
            if (raw == null) {
                OsrmRouteResult(
                    isSuccess = false,
                    code = "NullResponse",
                    durationSeconds = 0.0,
                    distanceMeters = 0.0,
                    points = emptyList(),
                    errorMessage = "Ungültige Antwort vom OSRM-Routingdienst."
                )
            } else {
                parseResponse(raw)
            }
        } catch (e: JsonDataException) {
            OsrmRouteResult(
                isSuccess = false,
                code = "ParseError",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "OSRM-Antwort konnte nicht gelesen werden: ${e.message ?: "JSON-Fehler"}"
            )
        } catch (e: IOException) {
            OsrmRouteResult(
                isSuccess = false,
                code = "ParseError",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "OSRM-Antwort konnte nicht verarbeitet werden: ${e.message ?: "IO-Fehler"}"
            )
        } catch (e: Exception) {
            OsrmRouteResult(
                isSuccess = false,
                code = "ParseError",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "Fehler bei der Routenanalyse: ${e.message ?: "Unbekannter Fehler"}"
            )
        }
    }

    fun parseResponse(raw: OsrmRawResponse): OsrmRouteResult {
        val code = raw.code?.trim().orEmpty().ifBlank { "Unknown" }
        if (!code.equals("Ok", ignoreCase = true)) {
            val detail = raw.message?.takeIf { it.isNotBlank() } ?: when (code) {
                "NoRoute" -> "Keine Route zwischen Start und Ziel gefunden."
                "NoSegment" -> "Start oder Ziel liegt außerhalb des Straßen-/Wegenetzes."
                else -> "OSRM-Fehler ($code)."
            }
            return OsrmRouteResult(
                isSuccess = false,
                code = code,
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = detail
            )
        }

        val firstRoute = raw.routes?.firstOrNull()
            ?: return OsrmRouteResult(
                isSuccess = false,
                code = "NoRoute",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "Keine Route in der OSRM-Antwort enthalten."
            )

        val duration = firstRoute.duration?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val distance = firstRoute.distance?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

        // GeoJSON coordinates are [longitude, latitude] -> convert to OsrmCoordPoint(latitude, longitude)
        val rawCoords = firstRoute.geometry?.coordinates.orEmpty()
        val points = rawCoords.mapNotNull { pair ->
            if (pair == null || pair.size < 2) return@mapNotNull null
            val lon = pair[0] ?: return@mapNotNull null
            val lat = pair[1] ?: return@mapNotNull null
            if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            OsrmCoordPoint(latitude = lat, longitude = lon)
        }

        if (points.isEmpty()) {
            return OsrmRouteResult(
                isSuccess = false,
                code = "EmptyGeometry",
                durationSeconds = duration,
                distanceMeters = distance,
                points = emptyList(),
                errorMessage = "Die Route enthält keine gültigen Koordinaten."
            )
        }

        return OsrmRouteResult(
            isSuccess = true,
            code = "Ok",
            durationSeconds = duration,
            distanceMeters = distance,
            points = points,
            errorMessage = ""
        )
    }

    fun sampleWaypoints(
        points: List<OsrmCoordPoint>,
        durationSeconds: Double,
        originName: String,
        destinationName: String,
        maxSamples: Int = 5
    ): List<RoutePoint> {
        if (points.isEmpty()) return emptyList()
        val totalMinutes = if (durationSeconds.isFinite() && durationSeconds > 0.0) {
            (durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
        } else {
            0
        }

        if (points.size == 1) {
            val pt = points.first()
            return listOf(
                RoutePoint(
                    name = originName.ifBlank { "Start" },
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    offsetMinutesFromStart = 0
                )
            )
        }

        val count = maxSamples.coerceIn(2, points.size)
        val indices = (0 until count).map { step ->
            val fraction = step.toDouble() / (count - 1).toDouble()
            (fraction * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
        }.distinct()

        return indices.mapIndexed { idx, ptIdx ->
            val pt = points[ptIdx]
            val fraction = ptIdx.toDouble() / (points.size - 1).toDouble()
            val label = when (idx) {
                0 -> originName.ifBlank { "Start" }
                indices.lastIndex -> destinationName.ifBlank { "Ziel" }
                else -> "Wegpunkt ${(fraction * 100).roundToInt()}%"
            }
            RoutePoint(
                name = label,
                latitude = pt.latitude,
                longitude = pt.longitude,
                offsetMinutesFromStart = (totalMinutes * fraction).roundToInt().coerceAtLeast(0)
            )
        }
    }
}
