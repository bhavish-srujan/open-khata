package com.example.khatabook.data.api

import com.example.khatabook.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    // Debug builds default to the emulator loopback alias; release builds get
    // an HTTPS URL injected at build time (see app/build.gradle.kts). Neither
    // path hardcodes a developer's LAN IP into checked-in config.
    var BASE_URL = BuildConfig.API_BASE_URL
        private set

    /**
     * Set this before the first network call (e.g. in MainActivity.onCreate).
     * The lambda is called on every request so the latest token is always used.
     */
    private var tokenProvider: () -> String? = { null }

    /** Active business, read per request so a switch takes effect immediately. */
    private var businessIdProvider: () -> String? = { null }

    fun init(
        tokenProvider: () -> String?,
        businessIdProvider: () -> String? = { null },
    ) {
        NetworkClient.tokenProvider = tokenProvider
        NetworkClient.businessIdProvider = businessIdProvider
    }

    /**
     * Emits once per 401 response from any endpoint except the auth ones
     * themselves, so the UI layer can react in one place instead of every
     * screen that happens to call the API separately deciding for itself
     * whether "Session expired" means anything actionable. Requests under
     * `/auth/` are excluded so a wrong OTP or an expired-but-not-yet-logged-in
     * state can never trigger a clear-and-redirect loop on the login screen.
     */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val sessionExpiryInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (shouldSignalSessionExpiry(response.code, request.url.encodedPath)) {
            _sessionExpired.tryEmit(Unit)
        }
        response
    }

    /**
     * Full request/response bodies are logged only in debug builds.
     *
     * Level.BODY was previously unconditional, so release builds wrote every
     * Authorization header and every OTP verification payload to logcat, where
     * a bug report or any log-reading process could pick them up.
     */
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    // OkHttpClient is built lazily; `tokenProvider` is captured by reference
    // so updating it via init() is reflected in every subsequent request.
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ tokenProvider() }, { businessIdProvider() }))
            .addInterceptor(sessionExpiryInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Matches connect/read: without this, OkHttp's 10s default applied to
            // the *write* phase, which is exactly the phase a multipart Scan Khata
            // image upload spends most of its time in on a slow connection — the
            // request could time out mid-upload well before the deliberately
            // generous 30s given to the rest of the round trip.
            .writeTimeout(30, TimeUnit.SECONDS)
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

    /**
     * Test-only seam: substitutes a fake [KhatabookApiService] so repository
     * unit tests can control exact HTTP response codes (e.g. a 403 from a
     * role check) without a real HTTP client or server.
     */
    internal fun setApiServiceForTesting(service: KhatabookApiService) {
        apiService = service
    }
}

/**
 * Whether a response should be treated as "the session is dead" rather than
 * an ordinary failure specific to one request. Requests under `/auth/` are
 * excluded: a wrong OTP or a not-yet-authenticated request must never trigger a
 * clear-and-redirect-to-login, which would be a loop on the login screen.
 */
internal fun shouldSignalSessionExpiry(code: Int, path: String): Boolean =
    code == 401 && !path.contains("/auth/")
