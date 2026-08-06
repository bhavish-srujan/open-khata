package com.example.khatabook.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    // Default backend address for emulator testing. For a physical device, change this in Developer Settings.
    var BASE_URL = "http://10.0.2.2:3000/"
        private set

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

    private var retrofit: Retrofit = buildRetrofit(BASE_URL)

    private fun buildRetrofit(url: String): Retrofit {
        // Ensure URL ends with /
        val safeUrl = if (url.endsWith("/")) url else "$url/"
        return Retrofit.Builder()
            .baseUrl(safeUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    var apiService: KhatabookApiService = retrofit.create(KhatabookApiService::class.java)
        private set

    fun updateBaseUrl(newUrl: String) {
        BASE_URL = newUrl
        retrofit = buildRetrofit(newUrl)
        apiService = retrofit.create(KhatabookApiService::class.java)
    }
}
