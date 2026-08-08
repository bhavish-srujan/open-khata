package com.example.khatabook.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that appends a JWT Bearer token to every request.
 * The [tokenProvider] lambda is called on each request so it always reads
 * the most up-to-date token (e.g. from EncryptedSharedPreferences).
 *
 * Sends the active business as x-business-id when one is selected, so a user
 * who belongs to several can switch without re-authenticating. This header once
 * carried a hardcoded "biz_1" that was not a real id at all; it now carries a
 * server-issued UUID, and the server verifies membership before honouring it.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val businessIdProvider: () -> String? = { null },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider() ?: return chain.proceed(chain.request())
        if (token.isBlank()) return chain.proceed(chain.request())

        val builder = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")

        // The active business, when it differs from the one the token was
        // issued for. The server honours this only after checking membership,
        // so it grants nothing the token does not already allow.
        businessIdProvider()?.takeIf { it.isNotBlank() }?.let {
            builder.addHeader("x-business-id", it)
        }

        return chain.proceed(builder.build())
    }
}
