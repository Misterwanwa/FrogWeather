package com.frogweather.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface PhotonService {
    @GET("api/")
    suspend fun search(
        @Query("q")     query: String,
        @Query("lang")  lang: String  = "de",
        @Query("limit") limit: Int    = 10
    ): PhotonResponse
}

object PhotonClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://photon.komoot.io/")
        .client(httpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val service: PhotonService = retrofit.create(PhotonService::class.java)
}
