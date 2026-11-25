package com.frogweather.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.LocalDate
import java.util.concurrent.TimeUnit

interface OpenMeteoForecastService {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = OpenMeteoClient.DEFAULT_M2_HOURLY,
        @Query("models") models: String = OpenMeteoClient.DEFAULT_M2_MODELS,
        @Query("forecast_days") forecastDays: Int = 5,
        @Query("timezone") timezone: String = "auto",
        @Query("timeformat") timeformat: String = "unixtime"
    ): OpenMeteoRawForecastResponse

    @GET("v1/forecast")
    suspend fun forecastBatch(
        @Query("latitude") latitude: String,
        @Query("longitude") longitude: String,
        @Query("hourly") hourly: String,
        @Query("models") models: String,
        @Query("forecast_days") forecastDays: Int = 3,
        @Query("timezone") timezone: String = "GMT",
        @Query("timeformat") timeformat: String = "unixtime"
    ): List<OpenMeteoRawForecastResponse>
}

interface OpenMeteoArchiveService {
    @GET("v1/archive")
    suspend fun archive(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("daily") daily: String = "temperature_2m_max,temperature_2m_min,temperature_2m_mean",
        @Query("hourly") hourly: String? = null,
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoRawArchiveResponse
}

object OpenMeteoClient {

    const val FORECAST_BASE_URL = "https://api.open-meteo.com/"
    const val ARCHIVE_BASE_URL = "https://archive-api.open-meteo.com/"

    const val DEFAULT_M2_HOURLY =
        "temperature_2m,relative_humidity_2m,precipitation,precipitation_probability," +
            "cloud_cover,surface_pressure,wind_speed_10m,wind_direction_10m," +
            "wind_gusts_10m,cape,uv_index,weather_code"

    const val DEFAULT_M2_MODELS =
        "${OpenMeteoModelIds.BEST_MATCH},${OpenMeteoModelIds.ECMWF}," +
            "${OpenMeteoModelIds.GFS},${OpenMeteoModelIds.ICON_D2}"

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    val forecastService: OpenMeteoForecastService by lazy {
        Retrofit.Builder()
            .baseUrl(FORECAST_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoForecastService::class.java)
    }

    val archiveService: OpenMeteoArchiveService by lazy {
        Retrofit.Builder()
            .baseUrl(ARCHIVE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoArchiveService::class.java)
    }

    suspend fun fetchMultiModelForecast(
        lat: Double,
        lon: Double,
        forecastDays: Int = 5
    ): OpenMeteoForecastData {
        val raw = forecastService.forecast(
            latitude = lat,
            longitude = lon,
            hourly = DEFAULT_M2_HOURLY,
            models = DEFAULT_M2_MODELS,
            forecastDays = forecastDays,
            timezone = "auto",
            timeformat = "unixtime"
        )
        return OpenMeteoParser.parseForecastResponse(
            raw = raw,
            requestedModels = OpenMeteoModelIds.ALL_FORECAST_MODELS
        )
    }

    suspend fun fetchForecastForModel(
        lat: Double,
        lon: Double,
        modelId: String,
        hourlyVars: List<String>,
        forecastDays: Int = 3
    ): OpenMeteoForecastData {
        val raw = forecastService.forecast(
            latitude = lat,
            longitude = lon,
            hourly = hourlyVars.joinToString(","),
            models = modelId,
            forecastDays = forecastDays,
            timezone = "auto",
            timeformat = "unixtime"
        )
        return OpenMeteoParser.parseForecastResponse(
            raw = raw,
            requestedModels = listOf(modelId)
        )
    }

    suspend fun fetchForecastGridForModel(
        coordinates: List<Pair<Double, Double>>,
        modelId: String,
        hourlyVars: List<String>,
        forecastDays: Int = 3
    ): List<OpenMeteoForecastData> {
        if (coordinates.isEmpty()) return emptyList()
        val hourlyQuery = hourlyVars.joinToString(",")
        if (coordinates.size == 1) {
            val (lat, lon) = coordinates[0]
            val raw = forecastService.forecast(
                latitude = lat,
                longitude = lon,
                hourly = hourlyQuery,
                models = modelId,
                forecastDays = forecastDays,
                timezone = "GMT",
                timeformat = "unixtime"
            )
            return listOf(
                OpenMeteoParser.parseForecastResponse(
                    raw = raw,
                    requestedModels = listOf(modelId)
                )
            )
        }

        val latitudes = coordinates.joinToString(",") { "%.4f".format(java.util.Locale.US, it.first) }
        val longitudes = coordinates.joinToString(",") { "%.4f".format(java.util.Locale.US, it.second) }
        val rawList = forecastService.forecastBatch(
            latitude = latitudes,
            longitude = longitudes,
            hourly = hourlyQuery,
            models = modelId,
            forecastDays = forecastDays,
            timezone = "GMT",
            timeformat = "unixtime"
        )
        return rawList.map { raw ->
            OpenMeteoParser.parseForecastResponse(
                raw = raw,
                requestedModels = listOf(modelId)
            )
        }
    }

    suspend fun fetchRecentHistory(
        lat: Double,
        lon: Double,
        days: Int = 30,
        anchorDate: LocalDate = LocalDate.now()
    ): OpenMeteoArchiveHistory {
        val safeDays = days.coerceAtLeast(1)
        val start = anchorDate.minusDays(safeDays.toLong() + 5L).toString()
        val end = anchorDate.toString()
        val raw = archiveService.archive(
            latitude = lat,
            longitude = lon,
            startDate = start,
            endDate = end,
            daily = "temperature_2m_max,temperature_2m_min,temperature_2m_mean",
            hourly = "temperature_2m",
            timezone = "auto"
        )
        return OpenMeteoParser.parseArchiveResponse(raw)
    }

    suspend fun fetchSameDateWindowBaseline(
        lat: Double,
        lon: Double,
        anchorDate: LocalDate = LocalDate.now(),
        yearsBack: Int = 5,
        halfWindowDays: Int = 7
    ): HistoricalBaselineData? = coroutineScope {
        val safeYears = yearsBack.coerceAtLeast(1)
        val safeHalf = halfWindowDays.coerceAtLeast(0).toLong()

        val yearHistories = (1..safeYears).map { offsetYears ->
            async {
                try {
                    val center = anchorDate.minusYears(offsetYears.toLong())
                    val start = center.minusDays(safeHalf).toString()
                    val end = center.plusDays(safeHalf).toString()
                    val raw = archiveService.archive(
                        latitude = lat,
                        longitude = lon,
                        startDate = start,
                        endDate = end,
                        daily = "temperature_2m_max,temperature_2m_min,temperature_2m_mean",
                        hourly = null,
                        timezone = "auto"
                    )
                    OpenMeteoParser.parseArchiveResponse(raw)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()

        val minValues = mutableListOf<Double>()
        val maxValues = mutableListOf<Double>()
        val meanValues = mutableListOf<Double>()
        var yearsWithData = 0

        for (history in yearHistories) {
            var yearHadDay = false
            for (i in history.dates.indices) {
                val min = history.dailyMinC.getOrNull(i)?.takeIf { it.isFinite() }
                val max = history.dailyMaxC.getOrNull(i)?.takeIf { it.isFinite() }
                val mean = history.dailyMeanC.getOrNull(i)?.takeIf { it.isFinite() }
                if (min != null && max != null) {
                    minValues.add(min)
                    maxValues.add(max)
                    meanValues.add(mean ?: ((min + max) / 2.0))
                    yearHadDay = true
                }
            }
            if (yearHadDay) yearsWithData++
        }

        if (minValues.isEmpty() || maxValues.isEmpty()) return@coroutineScope null

        HistoricalBaselineData(
            anchorDate = anchorDate.toString(),
            yearsSampled = yearsWithData,
            sampleDayCount = minValues.size,
            meanDailyMinC = minValues.average(),
            meanDailyMaxC = maxValues.average(),
            meanDailyTempC = meanValues.average()
        )
    }
}
