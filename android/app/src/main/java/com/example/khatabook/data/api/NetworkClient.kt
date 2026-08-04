package com.example.khatabook.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    // For physical device: PC's WiFi IP. For emulator use: http://10.0.2.2:3000/
    private const val BASE_URL = "http://10.237.29.153:3000/"

    /**
     * Set this before the first network call (e.g. in MainActivity.onCreate).
     * The lambda is called on every request so the latest token is always used.
     */
    private var tokenProvider: () -> String? = { null }

    fun init(tokenProvider: () -> String?) {
        NetworkClient.tokenProvider = tokenProvider
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // OkHttpClient is built lazily; `tokenProvider` is captured by reference
    // so updating it via init() is reflected in every subsequent request.
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { tokenProvider() })
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: KhatabookApiService by lazy {
        retrofit.create(KhatabookApiService::class.java)
    }
}
