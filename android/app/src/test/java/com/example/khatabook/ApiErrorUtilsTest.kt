package com.example.khatabook

import com.example.khatabook.utils.parseApiErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Screens used to show `response.errorBody()?.string()` verbatim, which is
 * NestJS's raw JSON exception body (`{"statusCode":..., "message":"...",
 * "error":"..."}`), not the clean message meant for the user. This pins the
 * shared helper that replaced those call sites.
 */
class ApiErrorUtilsTest {

    @Test
    fun `extracts message from a NestJS-shaped error body`() {
        val body = """{"statusCode":503,"message":"Failed to send OTP. Please try again later."}"""
        assertEquals("Failed to send OTP. Please try again later.", parseApiErrorMessage(body, "fallback"))
    }

    @Test
    fun `extracts message when an error field is also present`() {
        val body = """{"message":"Failed to process image","error":"Internal Server Error","statusCode":500}"""
        assertEquals("Failed to process image", parseApiErrorMessage(body, "fallback"))
    }

    @Test
    fun `falls back on null body`() {
        assertEquals("fallback", parseApiErrorMessage(null, "fallback"))
    }

    @Test
    fun `falls back on blank body`() {
        assertEquals("fallback", parseApiErrorMessage("   ", "fallback"))
    }

    @Test
    fun `falls back on non-JSON body`() {
        assertEquals("fallback", parseApiErrorMessage("<html>502 Bad Gateway</html>", "fallback"))
    }

    @Test
    fun `falls back when JSON has no message field`() {
        assertEquals("fallback", parseApiErrorMessage("""{"statusCode":500}""", "fallback"))
    }

    @Test
    fun `falls back when message is blank`() {
        assertEquals("fallback", parseApiErrorMessage("""{"message":""}""", "fallback"))
    }
}
