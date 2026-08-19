package com.example.khatabook

import com.example.khatabook.data.api.shouldSignalSessionExpiry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Centralized 401 handling (NetworkClient.sessionExpired) previously did not
 * exist at all: each screen that called the API directly decided for itself
 * whether a 401 meant anything, and most screens (Cashbook, Items, Bills,
 * Business Statistics) just showed static text with no way back to login,
 * while the Outbox retried a dead token forever. This pins the one predicate
 * that decides when the centralized redirect fires.
 */
class SessionExpiryTest {

    @Test
    fun `a 401 from an ordinary endpoint signals session expiry`() {
        assertTrue(shouldSignalSessionExpiry(401, "/api/v1/parties"))
        assertTrue(shouldSignalSessionExpiry(401, "/api/v1/ledger"))
        assertTrue(shouldSignalSessionExpiry(401, "/businesses"))
    }

    @Test
    fun `a 401 from an auth endpoint does not — that would loop the login screen`() {
        assertFalse(shouldSignalSessionExpiry(401, "/auth/request-otp"))
        assertFalse(shouldSignalSessionExpiry(401, "/auth/verify-otp"))
        assertFalse(shouldSignalSessionExpiry(401, "/auth/logout"))
    }

    @Test
    fun `any other status never signals session expiry`() {
        assertFalse(shouldSignalSessionExpiry(200, "/api/v1/parties"))
        assertFalse(shouldSignalSessionExpiry(403, "/api/v1/parties"))
        assertFalse(shouldSignalSessionExpiry(500, "/api/v1/parties"))
    }
}
