package com.frogweather.map

import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.RainViewerMapsData
import com.frogweather.network.RainViewerParser
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos

data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
)

data class GridSamplePlan(
    val rows: Int,
    val cols: Int,
    val lats: List<Double>,
    val lons: List<Double>,
    val coordinates: List<Pair<Double, Double>>
)

data class LayerVariableSpec(
    val primaryVariable: String,
    val secondaryVariable: String? = null,
    val isPressureLevelSupported: Boolean,
    val unit: String
)

data class ScalarGridField(
    val rows: Int,
    val cols: Int,
    val lats: List<Double>,
    val lons: List<Double>,
    val values: List<Double?>,
    val unit: String,
    val isSurfaceFallback: Boolean
)

data class WindGridField(
    val rows: Int,
    val cols: Int,
    val lats: List<Double>,
    val lons: List<Double>,
    val speedsKmh: List<Double?>,
    val directionsDeg: List<Double?>,
    val isSurfaceFallback: Boolean
)

data class WindSample(
    val speedKmh: Double,
    val directionDeg: Double,
    val u: Double,
    val v: Double
)

sealed interface ResolvedLayerOverlay {
    val layer: MapWeatherLayer

    data class RasterTiles(
        override val layer: MapWeatherLayer,
        val urlTemplate: String,
        val maxZoom: Int,
        val minZoom: Int = 0,
        val opacity: Float,
        val sourceLabel: String
    ) : ResolvedLayerOverlay

    data class ScalarGrid(
        override val layer: MapWeatherLayer,
        val field: ScalarGridField,
        val sourceLabel: String
    ) : ResolvedLayerOverlay

    data class WindVectors(
        val field: WindGridField,
        val sourceLabel: String
    ) : ResolvedLayerOverlay {
        override val layer: MapWeatherLayer = MapWeatherLayer.WIND
    }

    data class Unavailable(
        override val layer: MapWeatherLayer,
        val reason: String
    ) : ResolvedLayerOverlay
}

data class MapLayerData(
    val rainViewer: RainViewerMapsData? = null,
    val gridPlan: GridSamplePlan? = null,
    val gridForecasts: List<OpenMeteoForecastData> = emptyList(),
    val loadedLat: Double? = null,
    val loadedLon: Double? = null,
    val loadedModel: MapForecastModel? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdatedEpochMillis: Long? = null
)

object MapLayerDataProvider {

    private val timeOnlyFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    private val dayTimeFormatter = DateTimeFormatter.ofPattern("EE HH:mm", Locale.GERMAN)

    val SURFACE_VARIABLES: List<String> = listOf(
        "temperature_2m",
        "relative_humidity_2m",
        "precipitation",
        "cloud_cover",
        "surface_pressure",
        "wind_speed_10m",
        "wind_direction_10m",
        "wind_gusts_10m",
        "cape"
    )

    val ALL_M3_HOURLY_VARIABLES: List<String> = SURFACE_VARIABLES + listOf(
        "temperature_850hPa",
        "wind_speed_850hPa",
        "wind_direction_850hPa",
        "relative_humidity_850hPa",
        "temperature_500hPa",
        "wind_speed_500hPa",
        "wind_direction_500hPa",
        "relative_humidity_500hPa",
        "temperature_250hPa",
        "wind_speed_250hPa",
        "wind_direction_250hPa",
        "relative_humidity_250hPa"
    )

    fun isValidOwmKey(apiKey: String?): Boolean {
        val trimmed = apiKey?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed != "YOUR_API_KEY_HERE"
    }

    fun buildSampleGrid(
        bounds: GeoBounds,
        rows: Int = 4,
        cols: Int = 4,
        minSpanDeg: Double = 0.6
    ): GridSamplePlan {
        val safeRows = rows.coerceAtLeast(2)
        val safeCols = cols.coerceAtLeast(2)
        val safeMinSpan = (if (minSpanDeg.isFinite()) minSpanDeg else 0.6).coerceAtLeast(0.05)

        val inSouth = (if (bounds.south.isFinite()) bounds.south else 0.0).coerceIn(-85.0, 85.0)
        val inNorth = (if (bounds.north.isFinite()) bounds.north else 0.0).coerceIn(-85.0, 85.0)
        val inWest = (if (bounds.west.isFinite()) bounds.west else 0.0).coerceIn(-180.0, 180.0)
        val inEast = (if (bounds.east.isFinite()) bounds.east else 0.0).coerceIn(-180.0, 180.0)

        val rawSouth = minOf(inSouth, inNorth)
        val rawNorth = maxOf(inSouth, inNorth)
        val centerLat = ((rawSouth + rawNorth) / 2.0).coerceIn(-85.0, 85.0)
        val latSpan = maxOf(rawNorth - rawSouth, safeMinSpan).coerceAtMost(170.0)

        var south = centerLat - latSpan / 2.0
        var north = centerLat + latSpan / 2.0
        if (south < -85.0) {
            north = (north + (-85.0 - south)).coerceAtMost(85.0)
            south = -85.0
        }
        if (north > 85.0) {
            south = (south - (north - 85.0)).coerceAtLeast(-85.0)
            north = 85.0
        }

        val lats = (0 until safeRows).map { r ->
            south + (north - south) * (r.toDouble() / (safeRows - 1).toDouble())
        }

        val crossesAntimeridian = inWest > inEast && (inWest - inEast) > 180.0
        val lons = if (crossesAntimeridian) {
            val unwrappedEast = inEast + 360.0
            val centerLon = (inWest + unwrappedEast) / 2.0
            val maxPeriodicSpan = 360.0 * (safeCols - 1).toDouble() / safeCols.toDouble()
            val lonSpan = maxOf(unwrappedEast - inWest, safeMinSpan).coerceAtMost(maxPeriodicSpan)
            val west = centerLon - lonSpan / 2.0
            val east = centerLon + lonSpan / 2.0
            (0 until safeCols).map { c ->
                normalizeLonDeg(west + (east - west) * (c.toDouble() / (safeCols - 1).toDouble()))
            }
        } else {
            val rawWest = minOf(inWest, inEast)
            val rawEast = maxOf(inWest, inEast)
            val centerLon = ((rawWest + rawEast) / 2.0).coerceIn(-180.0, 180.0)
            val lonSpan = maxOf(rawEast - rawWest, safeMinSpan).coerceAtMost(360.0)

            var west = centerLon - lonSpan / 2.0
            var east = centerLon + lonSpan / 2.0
            if (west < -180.0) {
                east = (east + (-180.0 - west)).coerceAtMost(180.0)
                west = -180.0
            }
            if (east > 180.0) {
                west = (west - (east - 180.0)).coerceAtLeast(-180.0)
                east = 180.0
            }

            (0 until safeCols).map { c ->
                west + (east - west) * (c.toDouble() / (safeCols - 1).toDouble())
            }
        }

        val coordinates = ArrayList<Pair<Double, Double>>(safeRows * safeCols)
        for (lat in lats) {
            for (lon in lons) {
                coordinates.add(lat to lon)
            }
        }

        return GridSamplePlan(
            rows = safeRows,
            cols = safeCols,
            lats = lats,
            lons = lons,
            coordinates = coordinates
        )
    }

    fun resolveLayerVariables(layer: MapWeatherLayer, level: PressureLevel): LayerVariableSpec {
        return when (layer) {
            MapWeatherLayer.TEMPERATURE -> LayerVariableSpec(
                primaryVariable = if (level == PressureLevel.SFC) "temperature_2m" else "temperature${level.openMeteoSuffix}",
                isPressureLevelSupported = true,
                unit = "°C"
            )
            MapWeatherLayer.WIND -> LayerVariableSpec(
                primaryVariable = if (level == PressureLevel.SFC) "wind_speed_10m" else "wind_speed${level.openMeteoSuffix}",
                secondaryVariable = if (level == PressureLevel.SFC) "wind_direction_10m" else "wind_direction${level.openMeteoSuffix}",
                isPressureLevelSupported = true,
                unit = "km/h"
            )
            MapWeatherLayer.HUMIDITY -> LayerVariableSpec(
                primaryVariable = if (level == PressureLevel.SFC) "relative_humidity_2m" else "relative_humidity${level.openMeteoSuffix}",
                isPressureLevelSupported = true,
                unit = "%"
            )
            MapWeatherLayer.GUSTS -> LayerVariableSpec(
                primaryVariable = "wind_gusts_10m",
                isPressureLevelSupported = false,
                unit = "km/h"
            )
            MapWeatherLayer.CAPE -> LayerVariableSpec(
                primaryVariable = "cape",
                isPressureLevelSupported = false,
                unit = "J/kg"
            )
            MapWeatherLayer.PRECIPITATION, MapWeatherLayer.RADAR -> LayerVariableSpec(
                primaryVariable = "precipitation",
                isPressureLevelSupported = false,
                unit = "mm/h"
            )
            MapWeatherLayer.CLOUDS, MapWeatherLayer.SATELLITE -> LayerVariableSpec(
                primaryVariable = "cloud_cover",
                isPressureLevelSupported = false,
                unit = "%"
            )
            MapWeatherLayer.PRESSURE -> LayerVariableSpec(
                primaryVariable = "surface_pressure",
                isPressureLevelSupported = false,
                unit = "hPa"
            )
        }
    }

    fun requiredHourlyVariablesFor(level: PressureLevel): List<String> {
        if (level == PressureLevel.SFC) return SURFACE_VARIABLES
        val suffix = level.openMeteoSuffix
        return (SURFACE_VARIABLES + listOf(
            "temperature$suffix",
            "wind_speed$suffix",
            "wind_direction$suffix",
            "relative_humidity$suffix"
        )).distinct()
    }

    fun interpolateScalar(field: ScalarGridField, lat: Double, lon: Double): Double? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (field.rows <= 0 || field.cols <= 0 || field.lats.size != field.rows || field.lons.size != field.cols) {
            return null
        }
        val expectedCells = field.rows.toLong() * field.cols.toLong()
        if (expectedCells > Int.MAX_VALUE || field.values.size < expectedCells.toInt()) return null
        if (field.lats.any { !it.isFinite() } || field.lons.any { !it.isFinite() }) return null

        val effectiveLons = unwrapAntimeridianAxisIfNeeded(field.lons)
        val minLat = minOf(field.lats.first(), field.lats.last())
        val maxLat = maxOf(field.lats.first(), field.lats.last())
        val minLon = minOf(effectiveLons.first(), effectiveLons.last())
        val maxLon = maxOf(effectiveLons.first(), effectiveLons.last())
        val effectiveLonQuery = if (effectiveLons !== field.lons) {
            listOf(lon, lon + 360.0, lon - 360.0).minByOrNull { candidate ->
                when {
                    candidate < minLon -> minLon - candidate
                    candidate > maxLon -> candidate - maxLon
                    else -> 0.0
                }
            } ?: lon
        } else {
            lon
        }

        val clampedLat = lat.coerceIn(minLat, maxLat)
        val clampedLon = effectiveLonQuery.coerceIn(minLon, maxLon)

        val (r0, r1, ty) = locateAxisBracket(field.lats, clampedLat)
        val (c0, c1, tx) = locateAxisBracket(effectiveLons, clampedLon)

        val v00 = field.values[r0 * field.cols + c0]?.takeIf { it.isFinite() }
        val v01 = field.values[r0 * field.cols + c1]?.takeIf { it.isFinite() }
        val v10 = field.values[r1 * field.cols + c0]?.takeIf { it.isFinite() }
        val v11 = field.values[r1 * field.cols + c1]?.takeIf { it.isFinite() }

        if (v00 != null && v01 != null && v10 != null && v11 != null) {
            val top = v00 * (1.0 - tx) + v01 * tx
            val bottom = v10 * (1.0 - tx) + v11 * tx
            return top * (1.0 - ty) + bottom * ty
        }

        val cellSamples = listOf(
            Triple(0.0, 0.0, v00),
            Triple(0.0, 1.0, v01),
            Triple(1.0, 0.0, v10),
            Triple(1.0, 1.0, v11)
        ).filter { it.third != null }

        if (cellSamples.isNotEmpty()) {
            var weightedSum = 0.0
            var weightSum = 0.0
            for ((sy, sx, value) in cellSamples) {
                val dy = ty - sy
                val dx = tx - sx
                val distSq = dy * dy + dx * dx
                if (distSq <= 1e-12) return value
                val w = 1.0 / distSq
                weightedSum += value!! * w
                weightSum += w
            }
            if (weightSum > 0.0) return weightedSum / weightSum
        }

        // Global IDW fallback if the enclosing cell had all nulls
        var weightedSum = 0.0
        var weightSum = 0.0
        for (r in 0 until field.rows) {
            for (c in 0 until field.cols) {
                val value = field.values[r * field.cols + c]?.takeIf { it.isFinite() } ?: continue
                val dy = clampedLat - field.lats[r]
                val dx = clampedLon - effectiveLons[c]
                val distSq = dy * dy + dx * dx
                if (distSq <= 1e-12) return value
                val w = 1.0 / distSq
                weightedSum += value * w
                weightSum += w
            }
        }
        return if (weightSum > 0.0) weightedSum / weightSum else null
    }

    fun interpolateWind(field: WindGridField, lat: Double, lon: Double): WindSample? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (field.rows <= 0 || field.cols <= 0 || field.lats.size != field.rows || field.lons.size != field.cols) {
            return null
        }
        val expectedLong = field.rows.toLong() * field.cols.toLong()
        if (expectedLong > Int.MAX_VALUE) return null
        val expected = expectedLong.toInt()
        if (field.speedsKmh.size < expected || field.directionsDeg.size < expected) return null

        val uValues = MutableList<Double?>(expected) { null }
        val vValues = MutableList<Double?>(expected) { null }
        for (i in 0 until expected) {
            val speed = field.speedsKmh[i]?.takeIf { it.isFinite() && it >= 0.0 }
            val dir = field.directionsDeg[i]?.takeIf { it.isFinite() }
            if (speed != null && dir != null) {
                val rad = Math.toRadians(dir)
                uValues[i] = -speed * sin(rad)
                vValues[i] = -speed * cos(rad)
            }
        }

        val uField = ScalarGridField(
            rows = field.rows,
            cols = field.cols,
            lats = field.lats,
            lons = field.lons,
            values = uValues,
            unit = "km/h",
            isSurfaceFallback = field.isSurfaceFallback
        )
        val vField = uField.copy(values = vValues)

        val u = interpolateScalar(uField, lat, lon) ?: return null
        val v = interpolateScalar(vField, lat, lon) ?: return null
        val speedKmh = hypot(u, v)
        val directionDeg = if (speedKmh < 1e-6) {
            0.0
        } else {
            (Math.toDegrees(atan2(-u, -v)) + 360.0) % 360.0
        }
        return WindSample(
            speedKmh = speedKmh,
            directionDeg = directionDeg,
            u = u,
            v = v
        )
    }

    fun buildTimelineFrames(
        rainViewer: RainViewerMapsData?,
        forecastTimestampsMillis: List<Long>,
        nowEpochMillis: Long,
        zoneOffsetSeconds: Int = 0,
        maxForecastHours: Int = 48
    ): Pair<List<TimelineFrame>, Int> {
        val zoneOffset = runCatching { ZoneOffset.ofTotalSeconds(zoneOffsetSeconds) }
            .getOrDefault(ZoneOffset.UTC)
        val frames = mutableListOf<TimelineFrame>()

        if (rainViewer != null) {
            val sortedPast = rainViewer.radarPast.sortedBy { it.epochMillis }
            for (past in sortedPast) {
                if (frames.isNotEmpty() && past.epochMillis <= frames.last().epochMillis) continue
                val nearestForecastIdx = findNearestForecastIndex(forecastTimestampsMillis, past.epochMillis)
                frames.add(
                    TimelineFrame(
                        epochMillis = past.epochMillis,
                        label = formatTimestamp(past.epochMillis, zoneOffset, includeDay = false),
                        source = TimelineFrameSource.RADAR_PAST,
                        radarPath = past.path,
                        forecastHourIndex = nearestForecastIdx
                    )
                )
            }
            val lastPastMillis = frames.lastOrNull()?.epochMillis
            val sortedNowcast = rainViewer.radarNowcast.sortedBy { it.epochMillis }
            for (nowcast in sortedNowcast) {
                if (lastPastMillis != null && nowcast.epochMillis <= lastPastMillis) continue
                if (frames.isNotEmpty() && nowcast.epochMillis <= frames.last().epochMillis) continue
                val nearestForecastIdx = findNearestForecastIndex(forecastTimestampsMillis, nowcast.epochMillis)
                frames.add(
                    TimelineFrame(
                        epochMillis = nowcast.epochMillis,
                        label = formatTimestamp(nowcast.epochMillis, zoneOffset, includeDay = false),
                        source = TimelineFrameSource.RADAR_NOWCAST,
                        radarPath = nowcast.path,
                        forecastHourIndex = nearestForecastIdx
                    )
                )
            }
        }

        val lastRadarMillis = frames.lastOrNull()?.epochMillis
        val minForecastAfterRadarMillis = lastRadarMillis?.let { last ->
            if (last > Long.MAX_VALUE - 300_000L) null else last + 300_000L
        }
        val forecastCutoffMillis = if (nowEpochMillis < Long.MIN_VALUE + 3_600_000L) {
            Long.MIN_VALUE
        } else {
            nowEpochMillis - 3_600_000L
        }
        var addedForecast = 0
        val sortedForecasts = forecastTimestampsMillis.withIndex().sortedBy { it.value }
        for ((idx, ts) in sortedForecasts) {
            if (addedForecast >= maxForecastHours) break
            if (ts < forecastCutoffMillis) continue
            if (lastRadarMillis != null && (minForecastAfterRadarMillis == null || ts < minForecastAfterRadarMillis)) {
                continue
            }
            val lastAddedMillis = frames.lastOrNull()?.epochMillis
            if (lastAddedMillis != null && ts <= lastAddedMillis) continue
            frames.add(
                TimelineFrame(
                    epochMillis = ts,
                    label = formatTimestamp(ts, zoneOffset, includeDay = true),
                    source = TimelineFrameSource.FORECAST_MODEL,
                    radarPath = null,
                    forecastHourIndex = idx
                )
            )
            addedForecast++
        }

        if (frames.isEmpty()) return emptyList<TimelineFrame>() to 0

        val latestPastIdx = frames.indexOfLast { it.source == TimelineFrameSource.RADAR_PAST }
        val defaultIndex = if (latestPastIdx >= 0 && absDiffULong(nowEpochMillis, frames[latestPastIdx].epochMillis) <= 5_400_000UL) {
            latestPastIdx
        } else {
            frames.indices.minByOrNull { absDiffULong(frames[it].epochMillis, nowEpochMillis) } ?: 0
        }

        return frames to defaultIndex
    }

    fun extractScalarField(
        layer: MapWeatherLayer,
        level: PressureLevel,
        gridForecasts: List<OpenMeteoForecastData>,
        plan: GridSamplePlan,
        modelId: String,
        hourIndex: Int
    ): ScalarGridField? {
        if (plan.rows <= 0 || plan.cols <= 0 || plan.lats.size != plan.rows || plan.lons.size != plan.cols) return null
        val expectedCountLong = plan.rows.toLong() * plan.cols.toLong()
        if (expectedCountLong > Int.MAX_VALUE) return null
        val expectedCount = expectedCountLong.toInt()
        if (gridForecasts.size < expectedCount) return null

        val spec = resolveLayerVariables(layer, level)
        val values = ArrayList<Double?>(expectedCount)
        var hasAnyFinite = false

        for (i in 0 until expectedCount) {
            val data = gridForecasts[i]
            val modelHourly = data.models[modelId] ?: data.models.values.firstOrNull()
            val series = modelHourly?.getSeries(spec.primaryVariable)
                ?: when (spec.primaryVariable) {
                    "temperature_2m" -> modelHourly?.temperature2m
                    "relative_humidity_2m" -> modelHourly?.relativeHumidity2m
                    "precipitation" -> modelHourly?.precipitation
                    "cloud_cover" -> modelHourly?.cloudCover
                    "surface_pressure" -> modelHourly?.surfacePressure
                    "wind_gusts_10m" -> modelHourly?.windGusts10m
                    "cape" -> modelHourly?.cape
                    else -> null
                }
            val value = series?.getOrNull(hourIndex)?.takeIf { it.isFinite() }
            if (value != null) hasAnyFinite = true
            values.add(value)
        }

        if (!hasAnyFinite) return null
        return ScalarGridField(
            rows = plan.rows,
            cols = plan.cols,
            lats = plan.lats,
            lons = plan.lons,
            values = values,
            unit = spec.unit,
            isSurfaceFallback = !spec.isPressureLevelSupported && level != PressureLevel.SFC
        )
    }

    fun extractWindField(
        level: PressureLevel,
        gridForecasts: List<OpenMeteoForecastData>,
        plan: GridSamplePlan,
        modelId: String,
        hourIndex: Int
    ): WindGridField? {
        if (plan.rows <= 0 || plan.cols <= 0 || plan.lats.size != plan.rows || plan.lons.size != plan.cols) return null
        val expectedCountLong = plan.rows.toLong() * plan.cols.toLong()
        if (expectedCountLong > Int.MAX_VALUE) return null
        val expectedCount = expectedCountLong.toInt()
        if (gridForecasts.size < expectedCount) return null

        val spec = resolveLayerVariables(MapWeatherLayer.WIND, level)
        val speedVar = spec.primaryVariable
        val dirVar = spec.secondaryVariable ?: return null

        val speeds = ArrayList<Double?>(expectedCount)
        val dirs = ArrayList<Double?>(expectedCount)
        var hasAnyFinite = false

        for (i in 0 until expectedCount) {
            val data = gridForecasts[i]
            val modelHourly = data.models[modelId] ?: data.models.values.firstOrNull()
            val speedSeries = modelHourly?.getSeries(speedVar)
                ?: if (speedVar == "wind_speed_10m") modelHourly?.windSpeed10m else null
            val dirSeries = modelHourly?.getSeries(dirVar)
                ?: if (dirVar == "wind_direction_10m") modelHourly?.windDirection10m else null

            val speed = speedSeries?.getOrNull(hourIndex)?.takeIf { it.isFinite() }
            val dir = dirSeries?.getOrNull(hourIndex)?.takeIf { it.isFinite() }
            if (speed != null && dir != null) hasAnyFinite = true
            speeds.add(speed)
            dirs.add(dir)
        }

        if (!hasAnyFinite) return null
        return WindGridField(
            rows = plan.rows,
            cols = plan.cols,
            lats = plan.lats,
            lons = plan.lons,
            speedsKmh = speeds,
            directionsDeg = dirs,
            isSurfaceFallback = false
        )
    }

    fun resolveLayerOverlay(
        layer: MapWeatherLayer,
        level: PressureLevel,
        model: MapForecastModel,
        frame: TimelineFrame?,
        rainViewer: RainViewerMapsData?,
        gridForecasts: List<OpenMeteoForecastData>,
        gridPlan: GridSamplePlan?,
        owmApiKey: String?,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): ResolvedLayerOverlay {
        val validKey = isValidOwmKey(owmApiKey)
        val hourIndex = (frame?.forecastHourIndex ?: 0).coerceAtLeast(0)
        val isNearCurrent = frame == null ||
            frame.source != TimelineFrameSource.FORECAST_MODEL ||
            absDiffULong(frame.epochMillis, nowEpochMillis) <= 5_400_000UL

        return when (layer) {
            MapWeatherLayer.RADAR -> {
                if (rainViewer != null && frame?.radarPath != null) {
                    ResolvedLayerOverlay.RasterTiles(
                        layer = layer,
                        urlTemplate = RainViewerParser.buildTileUrlTemplate(
                            host = rainViewer.host,
                            path = frame.radarPath
                        ),
                        maxZoom = RainViewerParser.MAX_ZOOM,
                        opacity = 0.72f,
                        sourceLabel = if (frame.source == TimelineFrameSource.RADAR_NOWCAST) {
                            "RainViewer Nowcast"
                        } else {
                            "RainViewer Radar"
                        }
                    )
                } else {
                    val precipField = gridPlan?.let {
                        extractScalarField(
                            layer = MapWeatherLayer.PRECIPITATION,
                            level = level,
                            gridForecasts = gridForecasts,
                            plan = it,
                            modelId = model.id,
                            hourIndex = hourIndex
                        )
                    }
                    if (precipField != null) {
                        ResolvedLayerOverlay.ScalarGrid(
                            layer = layer,
                            field = precipField,
                            sourceLabel = "${model.displayName} Niederschlag"
                        )
                    } else if (validKey) {
                        ResolvedLayerOverlay.RasterTiles(
                            layer = layer,
                            urlTemplate = buildOwmTileUrl("precipitation_new", owmApiKey!!.trim()),
                            maxZoom = 12,
                            opacity = 0.65f,
                            sourceLabel = "OpenWeatherMap"
                        )
                    } else {
                        ResolvedLayerOverlay.Unavailable(layer, "Keine Radardaten verfügbar")
                    }
                }
            }

            MapWeatherLayer.SATELLITE -> {
                val irFrame = if (rainViewer != null && rainViewer.hasSatelliteInfrared && isNearCurrent) {
                    val targetMillis = frame?.epochMillis ?: nowEpochMillis
                    rainViewer.satelliteInfrared.minByOrNull { absDiffULong(it.epochMillis, targetMillis) }
                } else {
                    null
                }
                if (irFrame != null && rainViewer != null) {
                    ResolvedLayerOverlay.RasterTiles(
                        layer = layer,
                        urlTemplate = RainViewerParser.buildTileUrlTemplate(
                            host = rainViewer.host,
                            path = irFrame.path
                        ),
                        maxZoom = RainViewerParser.MAX_ZOOM,
                        opacity = 0.68f,
                        sourceLabel = "RainViewer IR"
                    )
                } else if (validKey && isNearCurrent) {
                    ResolvedLayerOverlay.RasterTiles(
                        layer = layer,
                        urlTemplate = buildOwmTileUrl("clouds_new", owmApiKey!!.trim()),
                        maxZoom = 12,
                        opacity = 0.65f,
                        sourceLabel = "OWM Satellit / Wolken"
                    )
                } else {
                    val cloudField = gridPlan?.let {
                        extractScalarField(
                            layer = MapWeatherLayer.CLOUDS,
                            level = level,
                            gridForecasts = gridForecasts,
                            plan = it,
                            modelId = model.id,
                            hourIndex = hourIndex
                        )
                    }
                    if (cloudField != null) {
                        ResolvedLayerOverlay.ScalarGrid(
                            layer = layer,
                            field = cloudField,
                            sourceLabel = "${model.displayName} Wolken"
                        )
                    } else {
                        ResolvedLayerOverlay.Unavailable(layer, "Keine Satellitendaten verfügbar")
                    }
                }
            }

            MapWeatherLayer.WIND -> {
                val windField = gridPlan?.let {
                    extractWindField(
                        level = level,
                        gridForecasts = gridForecasts,
                        plan = it,
                        modelId = model.id,
                        hourIndex = hourIndex
                    )
                }
                if (windField != null) {
                    ResolvedLayerOverlay.WindVectors(
                        field = windField,
                        sourceLabel = "${model.displayName} ${level.hpaLabel}"
                    )
                } else if (validKey && level == PressureLevel.SFC) {
                    ResolvedLayerOverlay.RasterTiles(
                        layer = layer,
                        urlTemplate = buildOwmTileUrl("wind_new", owmApiKey!!.trim()),
                        maxZoom = 12,
                        opacity = 0.6f,
                        sourceLabel = "OpenWeatherMap Wind"
                    )
                } else {
                    ResolvedLayerOverlay.Unavailable(layer, "Keine Winddaten verfügbar")
                }
            }

            MapWeatherLayer.TEMPERATURE,
            MapWeatherLayer.PRECIPITATION,
            MapWeatherLayer.CLOUDS,
            MapWeatherLayer.PRESSURE -> {
                val scalarField = gridPlan?.let {
                    extractScalarField(
                        layer = layer,
                        level = level,
                        gridForecasts = gridForecasts,
                        plan = it,
                        modelId = model.id,
                        hourIndex = hourIndex
                    )
                }
                if (scalarField != null) {
                    val levelTag = if (scalarField.isSurfaceFallback) "Sfc" else level.hpaLabel
                    ResolvedLayerOverlay.ScalarGrid(
                        layer = layer,
                        field = scalarField,
                        sourceLabel = "${model.displayName} $levelTag"
                    )
                } else if (validKey && level == PressureLevel.SFC && isNearCurrent) {
                    val owmCode = when (layer) {
                        MapWeatherLayer.TEMPERATURE -> "temp_new"
                        MapWeatherLayer.PRECIPITATION -> "precipitation_new"
                        MapWeatherLayer.CLOUDS -> "clouds_new"
                        MapWeatherLayer.PRESSURE -> "pressure_new"
                        else -> ""
                    }
                    ResolvedLayerOverlay.RasterTiles(
                        layer = layer,
                        urlTemplate = buildOwmTileUrl(owmCode, owmApiKey!!.trim()),
                        maxZoom = 12,
                        opacity = 0.6f,
                        sourceLabel = "OpenWeatherMap"
                    )
                } else {
                    ResolvedLayerOverlay.Unavailable(layer, "Keine Rasterdaten verfügbar")
                }
            }

            MapWeatherLayer.GUSTS,
            MapWeatherLayer.HUMIDITY,
            MapWeatherLayer.CAPE -> {
                val scalarField = gridPlan?.let {
                    extractScalarField(
                        layer = layer,
                        level = level,
                        gridForecasts = gridForecasts,
                        plan = it,
                        modelId = model.id,
                        hourIndex = hourIndex
                    )
                }
                if (scalarField != null) {
                    val levelTag = if (scalarField.isSurfaceFallback) "Sfc" else level.hpaLabel
                    ResolvedLayerOverlay.ScalarGrid(
                        layer = layer,
                        field = scalarField,
                        sourceLabel = "${model.displayName} $levelTag"
                    )
                } else {
                    ResolvedLayerOverlay.Unavailable(layer, "Keine Modelldaten verfügbar")
                }
            }
        }
    }

    private fun buildOwmTileUrl(layerCode: String, apiKey: String): String =
        "https://tile.openweathermap.org/map/$layerCode/{z}/{x}/{y}.png?appid=$apiKey"

    private fun normalizeLonDeg(lon: Double): Double {
        if (!lon.isFinite()) return 0.0
        var v = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (v == -180.0 && lon > 0.0) v = 180.0
        return v.coerceIn(-180.0, 180.0)
    }

    private fun unwrapAntimeridianAxisIfNeeded(lons: List<Double>): List<Double> {
        if (lons.size < 2) return lons
        var hasWrap = false
        for (i in 0 until lons.size - 1) {
            val step = lons[i + 1] - lons[i]
            if (step < -180.0 || step > 180.0) {
                hasWrap = true
                break
            }
        }
        if (!hasWrap) return lons
        val out = ArrayList<Double>(lons.size)
        var offset = 0.0
        out.add(lons[0])
        for (i in 1 until lons.size) {
            val diff = (lons[i] + offset) - out[i - 1]
            if (diff < -180.0) {
                offset += 360.0
            } else if (diff > 180.0) {
                offset -= 360.0
            }
            out.add(lons[i] + offset)
        }
        return out
    }

    private fun absDiffULong(a: Long, b: Long): ULong =
        if (a >= b) a.toULong() - b.toULong() else b.toULong() - a.toULong()

    private fun locateAxisBracket(axis: List<Double>, value: Double): Triple<Int, Int, Double> {
        if (axis.size == 1) return Triple(0, 0, 0.0)
        val ascending = axis.last() >= axis.first()
        var lower = 0
        if (ascending) {
            for (i in 0 until axis.size - 1) {
                if (value >= axis[i]) lower = i
            }
        } else {
            for (i in 0 until axis.size - 1) {
                if (value <= axis[i]) lower = i
            }
        }
        val upper = (lower + 1).coerceAtMost(axis.lastIndex)
        val span = axis[upper] - axis[lower]
        val t = if (abs(span) < 1e-9) 0.0 else ((value - axis[lower]) / span).coerceIn(0.0, 1.0)
        return Triple(lower, upper, t)
    }

    private fun findNearestForecastIndex(timestamps: List<Long>, targetMillis: Long): Int? {
        if (timestamps.isEmpty()) return null
        return timestamps.indices.minByOrNull { absDiffULong(timestamps[it], targetMillis) }
    }

    private fun formatTimestamp(epochMillis: Long, offset: ZoneOffset, includeDay: Boolean): String {
        val zdt = Instant.ofEpochMilli(epochMillis).atOffset(offset)
        return if (includeDay) dayTimeFormatter.format(zdt) else timeOnlyFormatter.format(zdt)
    }
}
