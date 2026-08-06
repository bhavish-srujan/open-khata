package com.example.khatabook.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that appends a JWT Bearer token to every request.
 * The [tokenProvider] lambda is called on each request so it always reads
 * the most up-to-date token (e.g. from EncryptedSharedPreferences).
 */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (!token.isNullOrBlank()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("x-business-id", "biz_1")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
