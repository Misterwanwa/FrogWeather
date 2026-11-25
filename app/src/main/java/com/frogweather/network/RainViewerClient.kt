package com.frogweather.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

interface RainViewerService {
    @GET("public/weather-maps.json")
    suspend fun getWeatherMaps(): RainViewerRawResponse
}

object RainViewerClient {
    const val BASE_URL = "https://api.rainviewer.com/"
    const val CACHE_TTL_MS = 300_000L

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

    val service: RainViewerService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RainViewerService::class.java)
    }

    private val cacheMutex = Mutex()
    @Volatile
    private var cachedData: RainViewerMapsData? = null
    @Volatile
    private var cachedAtMillis: Long = 0L

    suspend fun fetchWeatherMaps(
        forceRefresh: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): RainViewerMapsData? = cacheMutex.withLock {
        val existing = cachedData
        if (!forceRefresh && existing != null && nowMillis >= cachedAtMillis && (nowMillis - cachedAtMillis) < CACHE_TTL_MS) {
            return@withLock existing
        }

        return@withLock try {
            val raw = service.getWeatherMaps()
            val parsed = RainViewerParser.parseResponse(raw)
            cachedData = parsed
            cachedAtMillis = nowMillis
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() {
        cachedData = null
        cachedAtMillis = 0L
    }
}
