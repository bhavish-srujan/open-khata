package com.example.khatabook

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Logout must destroy the local credential.
 *
 * It previously only flipped an `is_logged_in` boolean: the JWT stayed in
 * EncryptedSharedPreferences and stayed valid server-side for its full 7 days,
 * so a "logged out" handset was still a working credential for anyone holding
 * it. Revocation is the server's half; this is the device's half.
 */
class LogoutTest {

    private val removed = mutableListOf<String>()
    private val written = mutableMapOf<String, String?>()
    private lateinit var prefs: SharedPreferences

    private val phone = "9876543210"

    @Before
    fun setup() {
        removed.clear()
        written.clear()

        val editor = mock<SharedPreferences.Editor>()
        whenever(editor.remove(any())).thenAnswer { invocation ->
            removed.add(invocation.getArgument<String>(0))
            editor
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { invocation ->
            written[invocation.getArgument<String>(0)] =
                invocation.getArgument<String?>(1)
            editor
        }

        prefs = mock<SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)
    }

    @Test
    fun `removes the per-account token the interceptor reads`() {
        clearSession(prefs, phone, mutableListOf(phone))

        assertTrue("jwt_token_$phone must be removed", removed.contains("jwt_token_$phone"))
    }

    @Test
    fun `removes the legacy global token that would be used as a fallback`() {
        clearSession(prefs, phone, mutableListOf(phone))

        assertTrue("legacy jwt_token must be removed", removed.contains("jwt_token"))
    }

    @Test
    fun `clears the active account pointer`() {
        clearSession(prefs, phone, mutableListOf(phone))

        assertTrue("auth_phone must be removed", removed.contains("auth_phone"))
    }

    @Test
    fun `drops the account from the signed-in list`() {
        val phones = mutableListOf(phone)

        clearSession(prefs, phone, phones)

        assertFalse(phones.contains(phone))
        assertEquals("", written["logged_in_phones_list"])
    }

    @Test
    fun `leaves other signed-in accounts intact`() {
        val other = "9000000000"
        val phones = mutableListOf(phone, other)

        clearSession(prefs, phone, phones)

        assertEquals(listOf(other), phones)
        assertEquals(other, written["logged_in_phones_list"])
        // The other account's token must survive.
        assertFalse(removed.contains("jwt_token_$other"))
    }

    @Test
    fun `is safe when there is no active phone`() {
        clearSession(prefs, "", mutableListOf())

        // Still clears the global fallback and the pointer.
        assertTrue(removed.contains("jwt_token"))
        assertTrue(removed.contains("auth_phone"))
        // And does not remove a nonsense "jwt_token_" key.
        assertFalse(removed.contains("jwt_token_"))
    }
}
