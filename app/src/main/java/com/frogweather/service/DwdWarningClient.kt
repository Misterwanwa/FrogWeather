package com.frogweather.service

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// DWD Warning API Models
data class DwdWarningsResponse(
    val features: List<DwdWarningFeature> = emptyList()
)

data class DwdWarningFeature(
    val properties: DwdWarningProperties
)

data class DwdWarningProperties(
    val event: String,
    val description: String,
    val severity: String, // Minor, Moderate, Severe, Extreme
    val onset: String,
    val expires: String,
    val headline: String,
    val instruction: String?,
    val areaDesc: String
)

enum class WarningSeverity(val level: Int, val color: Long, val germanName: String) {
    MINOR(1, 0xFF4FC3F7, "Gering"),      // Blue
    MODERATE(2, 0xFFFFA726, "Mittel"),   // Orange
    SEVERE(3, 0xFFEF5350, "Schwer"),     // Red
    EXTREME(4, 0xFFAB47BC, "Extrem"),    // Purple
    UNKNOWN(0, 0xFF9E9E9E, "Unbekannt"); // Gray

    companion object {
        fun fromString(severity: String): WarningSeverity {
            return when (severity.uppercase()) {
                "MINOR" -> MINOR
                "MODERATE" -> MODERATE
                "SEVERE" -> SEVERE
                "EXTREME" -> EXTREME
                else -> UNKNOWN
            }
        }
    }
}

// Warning display data
data class WeatherWarning(
    val title: String,
    val description: String,
    val severity: WarningSeverity,
    val area: String,
    val startTime: String,
    val endTime: String,
    val instruction: String?,
    val onsetInstant: java.time.Instant? = null,
    val expiresInstant: java.time.Instant? = null
)

interface DwdWarningService {
    @GET("alerts.json")
    suspend fun getWarningsByCoords(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): DwdWarningsResponse
}

object DwdWarningClient {
    private const val BASE_URL = "https://api.brightsky.dev/" // Alternative: Open-Meteo or DWD direct
    
    // Using DWD's unofficial but public GeoJSON endpoint
    private const val DWD_WARNINGS_URL = "https://maps.dwd.de/geoserver/dwd/ows?service=WFS&version=2.0.0&request=GetFeature&typeName=dwd:Warnungen_Gemeinden&outputFormat=application/json"
    
    private val logging by lazy {
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    private val moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.brightsky.dev/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val service: DwdWarningService by lazy { retrofit.create(DwdWarningService::class.java) }
}

// Alternative implementation using direct DWD GeoJSON
object DwdWarningFetcher {
    
    private val client = OkHttpClient()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(DwdWarningsResponse::class.java)
    
    suspend fun fetchWarnings(lat: Double, lon: Double): List<WeatherWarning> = withContext(Dispatchers.IO) {
        try {
            // Use Bright Sky API (German weather API that wraps DWD data)
            val url = "https://api.brightsky.dev/alerts?lat=$lat&lon=$lon"
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val warningsResponse = adapter.fromJson(body) ?: return@withContext emptyList()
                
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                
                warningsResponse.features.map { feature ->
                    val props = feature.properties
                    val severity = WarningSeverity.fromString(props.severity)
                    
                    val onsetInstant = try { Instant.parse(props.onset) } catch (e: Exception) { null }
                    val expiresInstant = try { Instant.parse(props.expires) } catch (e: Exception) { null }
                    
                    // Parse ISO timestamps
                    val start = try {
                        Instant.parse(props.onset)
                            .atZone(ZoneId.systemDefault())
                            .format(formatter)
                    } catch (e: Exception) { "--:--" }
                    
                    val end = try {
                        Instant.parse(props.expires)
                            .atZone(ZoneId.systemDefault())
                            .format(formatter)
                    } catch (e: Exception) { "--:--" }
                    
                    WeatherWarning(
                        title = props.event,
                        description = props.description,
                        severity = severity,
                        area = props.areaDesc,
                        startTime = start,
                        endTime = end,
                        instruction = props.instruction,
                        onsetInstant = onsetInstant,
                        expiresInstant = expiresInstant
                    )
                }.sortedByDescending { it.severity.level }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
