package com.example.khatabook.utils

import com.google.gson.Gson
import com.google.gson.JsonParseException

private data class ApiErrorBody(val message: String? = null)

/**
 * Extracts the clean `.message` from a NestJS-style JSON error body
 * (`{"statusCode":..., "message":"...", "error":"..."}`), falling back to
 * [fallback] when the body is missing or not parseable JSON. Without this,
 * screens were showing the raw JSON error body verbatim to the user.
 */
fun parseApiErrorMessage(rawBody: String?, fallback: String): String {
    if (rawBody.isNullOrBlank()) return fallback
    return try {
        Gson().fromJson(rawBody, ApiErrorBody::class.java)?.message?.takeIf { it.isNotBlank() } ?: fallback
    } catch (e: JsonParseException) {
        fallback
    }
}
