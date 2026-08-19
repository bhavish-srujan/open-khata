package com.example.khatabook

import com.example.khatabook.ui.components.resolveAccountDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the account-switcher bug: every row used to show
 * the hardcoded string "User Account" regardless of which account it was,
 * instead of that account's own stored name.
 */
class AccountSwitcherDisplayNameTest {

    @Test
    fun `account with a stored name shows that name`() {
        val names = mapOf("9111222333" to "Bhavish Srujan")

        assertEquals("Bhavish Srujan", resolveAccountDisplayName("9111222333", names))
    }

    @Test
    fun `multiple accounts each show their own name, not each other's`() {
        val names = mapOf(
            "9111222333" to "Bhavish Srujan",
            "9888777666" to "Priya Sharma"
        )

        assertEquals("Bhavish Srujan", resolveAccountDisplayName("9111222333", names))
        assertEquals("Priya Sharma", resolveAccountDisplayName("9888777666", names))
    }

    @Test
    fun `phone missing from the map falls back to the generic label`() {
        val names = mapOf("9111222333" to "Bhavish Srujan")

        // A second logged-in account this map was never told about — must
        // never silently borrow account 1's name.
        assertEquals("User Account", resolveAccountDisplayName("9888777666", names))
    }

    @Test
    fun `blank or whitespace-only stored name falls back to the generic label`() {
        val names = mapOf("9111222333" to "   ")

        assertEquals("User Account", resolveAccountDisplayName("9111222333", names))
    }

    @Test
    fun `stored name is trimmed`() {
        val names = mapOf("9111222333" to "  Bhavish Srujan  ")

        assertEquals("Bhavish Srujan", resolveAccountDisplayName("9111222333", names))
    }

    @Test
    fun `never fabricates a name from the phone number itself`() {
        val result = resolveAccountDisplayName("9111222333", emptyMap())

        assertEquals("User Account", result)
        assertEquals(false, result.contains("9111222333"))
    }
}
