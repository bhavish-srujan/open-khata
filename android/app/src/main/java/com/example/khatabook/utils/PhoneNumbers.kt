package com.example.khatabook.utils

/**
 * Whether [phone] is a number the app may actually contact — dial or message.
 *
 * Two values are not contactable and must never reach a dialer or an SMS
 * gateway:
 *  - blank, now that a phone number is optional on a party;
 *  - the legacy placeholder some older Scan Khata parties still carry, which
 *    looks like a number but was invented by the app, not given by anyone.
 *
 * Kept in one place so every caller agrees; the check used to be repeated
 * inline at each send site, which is how a new call site would quietly get it
 * wrong.
 */
private const val LEGACY_PLACEHOLDER_PHONE = "+910000000000"

fun isDialable(phone: String?): Boolean {
    val trimmed = phone?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    if (trimmed == LEGACY_PLACEHOLDER_PHONE) return false
    // Needs enough digits to be a real subscriber number rather than a stray
    // fragment ("-", "+91") left over from an import.
    return trimmed.count { it.isDigit() } >= 10
}
