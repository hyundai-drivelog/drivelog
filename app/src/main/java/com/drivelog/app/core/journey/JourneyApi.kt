package com.drivelog.app.core.journey

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface JourneyApi {
    @POST("api/v1/link/codes") suspend fun createLinkCode(): LinkCodeV
    @GET("api/v1/link/codes/{poll}") suspend fun pollLink(@Path("poll") poll: String): LinkPollV
    @GET("api/v1/journey") suspend fun journey(): JourneyV
    @PUT("api/v1/goals/{id}") suspend fun putGoal(@Path("id") id: String, @Body goal: GoalV): GoalV
    @PUT("api/v1/goals/{goal}/places/{place}") suspend fun putPlace(@Path("goal") goal: String, @Path("place") place: String, @Body body: PlaceV): GoalV
    @POST("api/v1/goals/{id}/archive") suspend fun archiveGoal(@Path("id") id: String): GoalV
    @PUT("api/v1/records/{id}") suspend fun putRecord(@Path("id") id: String, @Body record: RecordV): RecordV
    @DELETE("api/v1/records/{id}") suspend fun deleteRecord(@Path("id") id: String)
    @PUT("api/v1/records/{id}/visit") suspend fun confirmVisit(@Path("id") id: String): GoalV
    @GET("api/v1/records/{id}/photo") suspend fun photo(@Path("id") id: String): ResponseBody
}

fun createJourneyApi(baseUrl: String, token: () -> String?): JourneyApi = Retrofit.Builder()
    // A release build without a configured server stays usable offline; every call simply fails.
    .baseUrl(baseUrl.ifBlank { "https://server-not-configured.invalid/" })
    .client(
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val bearer = token()
                chain.proceed(
                    if (bearer == null) chain.request()
                    else chain.request().newBuilder().header("Authorization", "Bearer $bearer").build(),
                )
            }
            .build(),
    )
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(JourneyApi::class.java)
