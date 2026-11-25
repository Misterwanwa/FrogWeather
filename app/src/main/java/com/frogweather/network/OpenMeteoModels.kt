package com.frogweather.network

import com.frogweather.analysis.HourlyWeatherPoint
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

data class OpenMeteoRawForecastResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @Json(name = "utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val timezone: String? = null,
    val hourly: Map<String, Any?>? = null
)

data class OpenMeteoRawArchiveResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @Json(name = "utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val timezone: String? = null,
    val hourly: Map<String, Any?>? = null,
    val daily: Map<String, Any?>? = null
)

object OpenMeteoModelIds {
    const val BEST_MATCH = "best_match"
    const val ECMWF = "ecmwf_ifs025"
    const val GFS = "gfs_global"
    const val ICON_D2 = "icon_d2"
    const val OWM = "owm"

    val ALL_FORECAST_MODELS = listOf(BEST_MATCH, ECMWF, GFS, ICON_D2)
    val COMPARISON_MODELS = listOf(ECMWF, GFS, ICON_D2)
}

data class OpenMeteoModelHourly(
    val modelId: String,
    val temperature2m: List<Double?> = emptyList(),
    val relativeHumidity2m: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val precipitationProbability: List<Double?> = emptyList(),
    val cloudCover: List<Double?> = emptyList(),
    val surfacePressure: List<Double?> = emptyList(),
    val windSpeed10m: List<Double?> = emptyList(),
    val windDirection10m: List<Double?> = emptyList(),
    val windGusts10m: List<Double?> = emptyList(),
    val cape: List<Double?> = emptyList(),
    val uvIndex: List<Double?> = emptyList(),
    val weatherCode: List<Double?> = emptyList(),
    val rawSeries: Map<String, List<Double?>> = emptyMap()
) {
    fun hasAnyFiniteData(): Boolean =
        temperature2m.any { it != null && it.isFinite() } ||
            precipitation.any { it != null && it.isFinite() } ||
            windSpeed10m.any { it != null && it.isFinite() }

    fun getSeries(variable: String): List<Double?>? = rawSeries[variable]
}

data class AlignedOwmSeries(
    val temps: List<Double?>,
    val precips: List<Double?>,
    val windsKmh: List<Double?>
)

data class OpenMeteoForecastData(
    val latitude: Double,
    val longitude: Double,
    val utcOffsetSeconds: Int,
    val timezone: String?,
    val timestampsEpochMillis: List<Long>,
    val models: Map<String, OpenMeteoModelHourly>
) {
    fun toHourlyWeatherPoints(primaryModel: String = OpenMeteoModelIds.BEST_MATCH): List<HourlyWeatherPoint> {
        val primary = models[primaryModel]?.takeIf { it.hasAnyFiniteData() }
            ?: models.values.firstOrNull { it.hasAnyFiniteData() }
            ?: models[primaryModel]
            ?: return emptyList()
        val gfs = models[OpenMeteoModelIds.GFS]
        val bestMatch = models[OpenMeteoModelIds.BEST_MATCH]

        return timestampsEpochMillis.indices.map { i ->
            val tempC = finiteOrDefault(primary.temperature2m.getOrNull(i), 0.0)
            val precipMm = finiteOrDefault(primary.precipitation.getOrNull(i), 0.0).coerceAtLeast(0.0)
            val precipProb = finiteOrDefault(primary.precipitationProbability.getOrNull(i), 0.0)
                .roundToInt()
                .coerceIn(0, 100)
            val windKmh = finiteOrDefault(primary.windSpeed10m.getOrNull(i), 0.0).coerceAtLeast(0.0)
            val gustKmh = finiteOrDefault(primary.windGusts10m.getOrNull(i), windKmh).coerceAtLeast(0.0)
            val uv = primary.uvIndex.getOrNull(i)?.takeIf { it.isFinite() }
                ?: gfs?.uvIndex?.getOrNull(i)?.takeIf { it.isFinite() }
                ?: bestMatch?.uvIndex?.getOrNull(i)?.takeIf { it.isFinite() }
                ?: 0.0
            val cape = finiteOrDefault(primary.cape.getOrNull(i), 0.0).coerceAtLeast(0.0)
            val wmo = finiteOrDefault(primary.weatherCode.getOrNull(i), 0.0).roundToInt()
            val humidity = finiteOrDefault(primary.relativeHumidity2m.getOrNull(i), 0.0)
                .roundToInt()
                .coerceIn(0, 100)
            val clouds = finiteOrDefault(primary.cloudCover.getOrNull(i), 0.0)
                .roundToInt()
                .coerceIn(0, 100)
            val pressure = finiteOrDefault(primary.surfacePressure.getOrNull(i), 1013.25)

            HourlyWeatherPoint(
                epochMillis = timestampsEpochMillis[i],
                tempC = tempC,
                precipMm = precipMm,
                precipProb = precipProb,
                windKmh = windKmh,
                gustKmh = gustKmh,
                uvIndex = uv.coerceAtLeast(0.0),
                capeJkg = cape,
                weatherCode = wmo,
                humidityPct = humidity,
                cloudCoverPct = clouds,
                pressureHpa = pressure
            )
        }
    }

    fun validModelTemps(
        modelIds: List<String> = OpenMeteoModelIds.COMPARISON_MODELS
    ): Map<String, List<Double?>> = buildMap {
        for (modelId in modelIds) {
            val series = models[modelId]?.temperature2m
            if (series != null && series.any { it != null && it.isFinite() }) {
                put(modelId, series)
            }
        }
    }

    fun validModelPrecips(
        modelIds: List<String> = OpenMeteoModelIds.COMPARISON_MODELS
    ): Map<String, List<Double?>> = buildMap {
        for (modelId in modelIds) {
            val series = models[modelId]?.precipitation
            if (series != null && series.any { it != null && it.isFinite() }) {
                put(modelId, series)
            }
        }
    }

    fun validModelWinds(
        modelIds: List<String> = OpenMeteoModelIds.COMPARISON_MODELS
    ): Map<String, List<Double?>> = buildMap {
        for (modelId in modelIds) {
            val series = models[modelId]?.windSpeed10m
            if (series != null && series.any { it != null && it.isFinite() }) {
                put(modelId, series)
            }
        }
    }

    /**
     * Returns comparison model series including empty lists for omitted models (e.g. `icon_d2`
     * outside Central Europe) so [com.frogweather.analysis.ForecastConfidenceEngine] tracks them
     * in `excludedModels` while computing confidence over available models.
     */
    fun comparisonModelTemps(
        modelIds: List<String> = OpenMeteoModelIds.COMPARISON_MODELS
    ): Map<String, List<Double?>> = buildMap {
        for (modelId in modelIds) {
            put(modelId, models[modelId]?.temperature2m ?: emptyList())
        }
    }

    fun comparisonModelPrecips(
        modelIds: List<String> = OpenMeteoModelIds.COMPARISON_MODELS
    ): Map<String, List<Double?>> = buildMap {
        for (modelId in modelIds) {
            put(modelId, models[modelId]?.precipitation ?: emptyList())
        }
    }

    fun comparisonModelWinds(
        modelIds: List<String> = OpenMeteoModelIds.COMPARISON_MODELS
    ): Map<String, List<Double?>> = buildMap {
        for (modelId in modelIds) {
            put(modelId, models[modelId]?.windSpeed10m ?: emptyList())
        }
    }

    fun alignOwmForecast(
        owmForecast: ForecastResponse,
        toleranceMillis: Long = 1_800_000L
    ): AlignedOwmSeries {
        val temps = MutableList<Double?>(timestampsEpochMillis.size) { null }
        val precips = MutableList<Double?>(timestampsEpochMillis.size) { null }
        val winds = MutableList<Double?>(timestampsEpochMillis.size) { null }

        if (owmForecast.list.isEmpty() || timestampsEpochMillis.isEmpty() || toleranceMillis < 0L) {
            return AlignedOwmSeries(temps, precips, winds)
        }

        val toleranceULong = toleranceMillis.toULong()
        val minEpochSec = Long.MIN_VALUE / 1000L
        val maxEpochSec = Long.MAX_VALUE / 1000L

        for ((index, targetMillis) in timestampsEpochMillis.withIndex()) {
            var bestMatch: ForecastItem? = null
            var bestDiff = ULong.MAX_VALUE
            for (item in owmForecast.list) {
                if (item.dt < minEpochSec || item.dt > maxEpochSec) continue
                val itemMillis = item.dt * 1000L
                val diff = absDiffULong(itemMillis, targetMillis)
                if (diff <= toleranceULong && (bestMatch == null || diff < bestDiff)) {
                    bestMatch = item
                    bestDiff = diff
                }
            }
            val match = bestMatch ?: continue

            temps[index] = match.main.temp.takeIf { it.isFinite() }
            winds[index] = match.wind?.speed?.takeIf { it.isFinite() }?.times(3.6)
            precips[index] = if (match.rain == null) {
                0.0
            } else {
                match.rain.threeHour.takeIf { it.isFinite() }?.div(3.0)?.coerceAtLeast(0.0)
            }
        }

        return AlignedOwmSeries(temps, precips, winds)
    }

    private fun absDiffULong(a: Long, b: Long): ULong =
        if (a >= b) a.toULong() - b.toULong() else b.toULong() - a.toULong()

    private fun finiteOrDefault(value: Double?, default: Double): Double =
        if (value != null && value.isFinite()) value else default
}

data class OpenMeteoArchiveHistory(
    val dates: List<String>,
    val dailyMinC: List<Double?>,
    val dailyMaxC: List<Double?>,
    val dailyMeanC: List<Double?>,
    val hourlyEpochMillis: List<Long> = emptyList(),
    val hourlyTempC: List<Double?> = emptyList()
)

data class HistoricalBaselineData(
    val anchorDate: String,
    val yearsSampled: Int,
    val sampleDayCount: Int,
    val meanDailyMinC: Double,
    val meanDailyMaxC: Double,
    val meanDailyTempC: Double
)

object OpenMeteoParser {

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val forecastAdapter by lazy {
        moshi.adapter(OpenMeteoRawForecastResponse::class.java)
    }

    private val forecastBatchAdapter by lazy {
        val listType = Types.newParameterizedType(List::class.java, OpenMeteoRawForecastResponse::class.java)
        moshi.adapter<List<OpenMeteoRawForecastResponse?>>(listType)
    }

    private val archiveAdapter by lazy {
        moshi.adapter(OpenMeteoRawArchiveResponse::class.java)
    }

    private val knownVariables = listOf(
        "temperature_2m",
        "relative_humidity_2m",
        "precipitation",
        "precipitation_probability",
        "cloud_cover",
        "surface_pressure",
        "wind_speed_10m",
        "wind_direction_10m",
        "wind_gusts_10m",
        "cape",
        "uv_index",
        "weather_code"
    )

    fun parseForecastJson(
        json: String,
        requestedModels: List<String>? = null
    ): OpenMeteoForecastData {
        val raw = forecastAdapter.fromJson(json)
            ?: throw IllegalArgumentException("Empty or invalid Open-Meteo forecast JSON")
        return parseForecastResponse(raw, requestedModels)
    }

    fun parseForecastBatchJson(
        json: String,
        requestedModels: List<String>? = null
    ): List<OpenMeteoForecastData> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Empty Open-Meteo batch JSON")
        }
        val rawList: List<OpenMeteoRawForecastResponse> = if (trimmed.startsWith("[")) {
            (forecastBatchAdapter.fromJson(trimmed)
                ?: throw IllegalArgumentException("Empty or invalid Open-Meteo batch forecast JSON"))
                .filterNotNull()
        } else {
            val single = forecastAdapter.fromJson(trimmed)
                ?: throw IllegalArgumentException("Empty or invalid Open-Meteo single forecast JSON")
            listOf(single)
        }
        return rawList.map { parseForecastResponse(it, requestedModels) }
    }

    fun parseArchiveJson(json: String): OpenMeteoArchiveHistory {
        val raw = archiveAdapter.fromJson(json)
            ?: throw IllegalArgumentException("Empty or invalid Open-Meteo archive JSON")
        return parseArchiveResponse(raw)
    }

    fun parseForecastResponse(
        raw: OpenMeteoRawForecastResponse,
        requestedModels: List<String>? = null
    ): OpenMeteoForecastData {
        val hourly = raw.hourly.orEmpty()
        val nullableTimestamps = parseNullableTimeMillisList(hourly["time"], raw.utcOffsetSeconds)
        val validIndices = nullableTimestamps.indices.filter { nullableTimestamps[it] != null }
        val timestamps = validIndices.map { nullableTimestamps[it]!! }
        val needsRealignment = validIndices.size < nullableTimestamps.size

        fun alignSeries(series: List<Double?>): List<Double?> =
            if (!needsRealignment || series.isEmpty()) {
                series
            } else {
                validIndices.map { idx -> series.getOrNull(idx) }
            }

        val modelsWithSuffix = OpenMeteoModelIds.ALL_FORECAST_MODELS.filter { modelId ->
            hourly.keys.any { key -> key.endsWith("_$modelId") }
        }
        val extraRequestedSuffixModels = requestedModels.orEmpty().filter { modelId ->
            hourly.keys.any { key -> key.endsWith("_$modelId") }
        }
        val detectedSuffixedModels = (modelsWithSuffix + extraRequestedSuffixModels).distinct()

        val targetModels: List<String>
        val allowUnsuffixedFallback: Boolean

        if (detectedSuffixedModels.isNotEmpty()) {
            targetModels = if (requestedModels != null) {
                (requestedModels + detectedSuffixedModels).distinct()
            } else {
                detectedSuffixedModels
            }
            allowUnsuffixedFallback = false
        } else {
            val fallbackModel = requestedModels?.singleOrNull() ?: OpenMeteoModelIds.BEST_MATCH
            targetModels = listOf(fallbackModel)
            allowUnsuffixedFallback = true
        }

        val modelMap = linkedMapOf<String, OpenMeteoModelHourly>()
        for (modelId in targetModels) {
            val rawSeries = linkedMapOf<String, List<Double?>>()
            if (allowUnsuffixedFallback) {
                for ((key, value) in hourly) {
                    if (key != "time") {
                        extractDoubleList(value)?.let { rawSeries[key] = alignSeries(it) }
                    }
                }
            } else {
                val suffix = "_$modelId"
                for ((key, value) in hourly) {
                    if (key != "time" && key.endsWith(suffix)) {
                        val baseVar = key.removeSuffix(suffix)
                        extractDoubleList(value)?.let { rawSeries[baseVar] = alignSeries(it) }
                    }
                }
            }

            fun seriesFor(variable: String): List<Double?> =
                extractSeries(hourly, variable, modelId, allowUnsuffixedFallback)
                    ?.let { alignSeries(it) }
                    ?: emptyList()

            val modelHourly = OpenMeteoModelHourly(
                modelId = modelId,
                temperature2m = seriesFor("temperature_2m"),
                relativeHumidity2m = seriesFor("relative_humidity_2m"),
                precipitation = seriesFor("precipitation"),
                precipitationProbability = seriesFor("precipitation_probability"),
                cloudCover = seriesFor("cloud_cover"),
                surfacePressure = seriesFor("surface_pressure"),
                windSpeed10m = seriesFor("wind_speed_10m"),
                windDirection10m = seriesFor("wind_direction_10m"),
                windGusts10m = seriesFor("wind_gusts_10m"),
                cape = seriesFor("cape"),
                uvIndex = seriesFor("uv_index"),
                weatherCode = seriesFor("weather_code"),
                rawSeries = rawSeries
            )

            // Keep models that have actual data or were explicitly detected/requested in single-model mode;
            // omitted Central-Europe models (e.g. icon_d2 in Tokyo) remain absent from `models`
            if (rawSeries.isNotEmpty() || modelHourly.hasAnyFiniteData() || detectedSuffixedModels.contains(modelId)) {
                modelMap[modelId] = modelHourly
            }
        }

        return OpenMeteoForecastData(
            latitude = raw.latitude,
            longitude = raw.longitude,
            utcOffsetSeconds = raw.utcOffsetSeconds,
            timezone = raw.timezone,
            timestampsEpochMillis = timestamps,
            models = modelMap
        )
    }

    fun parseArchiveResponse(raw: OpenMeteoRawArchiveResponse): OpenMeteoArchiveHistory {
        val daily = raw.daily.orEmpty()
        val rawDateObjects = (daily["time"] as? List<*>).orEmpty()
        val rawDatesNullable: List<String?> = rawDateObjects.map { item ->
            (item as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val rawMins = extractDoubleList(daily["temperature_2m_min"]).orEmpty()
        val rawMaxs = extractDoubleList(daily["temperature_2m_max"]).orEmpty()
        val rawMeans = extractDoubleList(daily["temperature_2m_mean"]).orEmpty()

        val alignedDates = mutableListOf<String>()
        val alignedMins = mutableListOf<Double?>()
        val alignedMaxs = mutableListOf<Double?>()
        val alignedMeans = mutableListOf<Double?>()

        for (i in rawDatesNullable.indices) {
            val date = rawDatesNullable[i] ?: continue
            alignedDates.add(date)
            alignedMins.add(rawMins.getOrNull(i))
            alignedMaxs.add(rawMaxs.getOrNull(i))
            alignedMeans.add(rawMeans.getOrNull(i))
        }

        var lastValidIndex = alignedDates.size - 1
        while (lastValidIndex >= 0) {
            val minOk = alignedMins[lastValidIndex]?.isFinite() == true
            val maxOk = alignedMaxs[lastValidIndex]?.isFinite() == true
            val meanOk = alignedMeans[lastValidIndex]?.isFinite() == true
            if (minOk || maxOk || meanOk) break
            lastValidIndex--
        }

        val trimmedCount = (lastValidIndex + 1).coerceAtLeast(0)
        val dates = alignedDates.take(trimmedCount)
        val mins = alignedMins.take(trimmedCount)
        val maxs = alignedMaxs.take(trimmedCount)
        val means = alignedMeans.take(trimmedCount)

        val hourly = raw.hourly.orEmpty()
        val nullableHourlyTimes = parseNullableTimeMillisList(hourly["time"], raw.utcOffsetSeconds)
        val validHourlyIndices = nullableHourlyTimes.indices.filter { nullableHourlyTimes[it] != null }
        val hourlyTimes = validHourlyIndices.map { nullableHourlyTimes[it]!! }
        val rawHourlyTemps = extractDoubleList(hourly["temperature_2m"]).orEmpty()
        val hourlyTemps = if (validHourlyIndices.size < nullableHourlyTimes.size && rawHourlyTemps.isNotEmpty()) {
            validHourlyIndices.map { idx -> rawHourlyTemps.getOrNull(idx) }
        } else {
            rawHourlyTemps
        }

        return OpenMeteoArchiveHistory(
            dates = dates,
            dailyMinC = mins,
            dailyMaxC = maxs,
            dailyMeanC = means,
            hourlyEpochMillis = hourlyTimes,
            hourlyTempC = hourlyTemps
        )
    }

    fun extractSeries(
        hourly: Map<String, Any?>,
        variable: String,
        model: String?,
        allowUnsuffixedFallback: Boolean
    ): List<Double?>? {
        if (model != null) {
            val suffixed = extractDoubleList(hourly["${variable}_$model"])
            if (suffixed != null) return suffixed
        }
        return if (allowUnsuffixedFallback) {
            extractDoubleList(hourly[variable])
        } else {
            null
        }
    }

    private fun extractDoubleList(rawValue: Any?): List<Double?>? {
        val rawList = rawValue as? List<*> ?: return null
        if (rawList.isEmpty()) return emptyList()
        return rawList.map { item ->
            when (item) {
                is Number -> item.toDouble().takeIf { it.isFinite() }
                else -> null
            }
        }
    }

    private fun parseNullableTimeMillisList(rawTime: Any?, utcOffsetSeconds: Int): List<Long?> {
        val rawList = rawTime as? List<*> ?: return emptyList()
        val offset = try {
            ZoneOffset.ofTotalSeconds(utcOffsetSeconds)
        } catch (_: Exception) {
            ZoneOffset.UTC
        }
        return rawList.map { item -> parseSingleTimestampMillis(item, offset) }
    }

    private fun parseSingleTimestampMillis(item: Any?, offset: ZoneOffset): Long? = when (item) {
        is Number -> {
            val minSec = Long.MIN_VALUE / 1000L
            val maxSec = Long.MAX_VALUE / 1000L
            when (item) {
                is Long -> if (item in minSec..maxSec) item * 1000L else null
                is Int -> item.toLong() * 1000L
                else -> {
                    val d = item.toDouble()
                    if (d.isFinite() && d >= minSec.toDouble() && d <= maxSec.toDouble()) {
                        val sec = d.toLong()
                        if (sec in minSec..maxSec) sec * 1000L else null
                    } else {
                        null
                    }
                }
            }
        }
        is String -> {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                runCatching {
                    when {
                        trimmed.endsWith("Z", ignoreCase = true) ->
                            Instant.parse(trimmed).toEpochMilli()
                        hasExplicitZoneOffset(trimmed) ->
                            OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
                        else ->
                            LocalDateTime.parse(trimmed).toInstant(offset).toEpochMilli()
                    }
                }.getOrNull()
            }
        }
        else -> null
    }

    private fun hasExplicitZoneOffset(isoString: String): Boolean {
        val tIndex = isoString.indexOf('T').takeIf { it >= 0 }
            ?: isoString.indexOf('t').takeIf { it >= 0 }
            ?: return false
        return isoString.indexOf('+', tIndex) >= 0 || isoString.indexOf('-', tIndex) >= 0
    }
}
