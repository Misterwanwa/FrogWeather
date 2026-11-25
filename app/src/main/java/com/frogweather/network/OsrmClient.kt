package com.frogweather.network

import com.frogweather.route.RouteTransportMode
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.TimeUnit

interface OsrmService {
    @GET("route/v1/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path("profile") profile: String,
        @Path("coordinates", encoded = true) coordinates: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "geojson",
        @Query("steps") steps: String = "true"
    ): Response<ResponseBody>
}

object OsrmClient {
    const val BASE_URL = "https://router.project-osrm.org/"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "FrogWeather/1.0")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(logging)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .build()

    val service: OsrmService = retrofit.create(OsrmService::class.java)

    fun formatCoordinates(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): String {
        return String.format(
            Locale.US,
            "%.5f,%.5f;%.5f,%.5f",
            originLon,
            originLat,
            destLon,
            destLat
        )
    }

    suspend fun fetchRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        mode: RouteTransportMode
    ): OsrmRouteResult {
        if (!isValidCoord(originLat, originLon) || !isValidCoord(destLat, destLon)) {
            return OsrmRouteResult(
                isSuccess = false,
                code = "InvalidCoordinates",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "Ungültige Start- oder Zielkoordinaten."
            )
        }

        return try {
            val coords = formatCoordinates(originLat, originLon, destLat, destLon)
            val response = service.getRoute(
                profile = mode.osrmProfile,
                coordinates = coords
            )
            val bodyString = response.body()?.string() ?: response.errorBody()?.string().orEmpty()
            if (bodyString.isBlank()) {
                OsrmRouteResult(
                    isSuccess = false,
                    code = "Http${response.code()}",
                    durationSeconds = 0.0,
                    distanceMeters = 0.0,
                    points = emptyList(),
                    errorMessage = "OSRM-Routingdienst lieferte HTTP ${response.code()}."
                )
            } else {
                OsrmRouteParser.parse(bodyString)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OsrmRouteResult(
                isSuccess = false,
                code = "NetworkError",
                durationSeconds = 0.0,
                distanceMeters = 0.0,
                points = emptyList(),
                errorMessage = "OSRM-Routingdienst nicht erreichbar: ${e.message ?: "Netzwerkfehler"}"
            )
        }
    }

    private fun isValidCoord(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}
