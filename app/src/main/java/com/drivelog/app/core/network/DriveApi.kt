package com.drivelog.app.core.network

import com.drivelog.app.core.drive.DrivePayload
import com.drivelog.app.core.drive.DriveSourceType
import com.google.gson.GsonBuilder
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class HealthResponse(val status: String, val service: String)

data class DriveRecord(
    val id: Long,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Long,
    val distanceKm: BigDecimal,
    val averageEfficiency: BigDecimal?,
    val efficiencyUnit: String?,
    val sourceType: DriveSourceType,
    val createdAt: String,
)

interface DriveApi {
    @GET("api/v1/health") suspend fun health(): HealthResponse
    @POST("api/v1/drives") suspend fun create(@Body request: DrivePayload): DriveRecord
    @GET("api/v1/drives") suspend fun recent(): List<DriveRecord>
    @GET("api/v1/drives/{id}") suspend fun detail(@Path("id") id: Long): DriveRecord
}

fun createDriveApi(baseUrl: String): DriveApi = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )
    .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
    .build()
    .create(DriveApi::class.java)
