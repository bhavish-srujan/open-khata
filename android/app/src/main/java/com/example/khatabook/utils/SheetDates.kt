package com.example.khatabook.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Parsing and formatting for the user-facing transaction date.
 *
 * Two sources feed this: what a user types into the date field, and what the
 * Scan Khata model reads off a photographed sheet. Neither can be trusted, so
 * everything here rejects rather than coerces — silently turning 31/02/2026
 * into 03/03/2026 would file money under a day the user never chose, and an
 * AI-invented "2026-99-99" must never reach the backend.
 */
object SheetDates {

    /** The format shown and accepted in the UI. */
    const val DISPLAY_PATTERN = "dd/MM/yyyy"

    /** Accepted inputs, widest-used first. The scanner emits ISO; humans type
     *  the display format; slash-free and dash variants are common OCR output. */
    private val PATTERNS = listOf(
        "yyyy-MM-dd",
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "yyyy/MM/dd",
    )

    /**
     * Epoch millis at the *start of that day* in the device's local zone, or
     * null when the value is absent or not a real calendar date.
     *
     * Start-of-day, not the parse instant: a business date is a day, not a
     * moment, and normalising here keeps day/week/month grouping from
     * depending on what time of day the row happened to be entered.
     */
    fun parseOrNull(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        for (pattern in PATTERNS) {
            val parsed = try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    // Non-lenient is the whole point: lenient mode rolls
                    // 31/02 forward into March instead of rejecting it.
                    isLenient = false
                }.parse(value)
            } catch (_: Exception) {
                null
            }
            if (parsed != null) return startOfDay(parsed.time)
        }
        return null
    }

    /** True when [raw] is a real calendar date this app will accept. */
    fun isValid(raw: String?): Boolean = parseOrNull(raw) != null

    /** Renders [millis] in the format the UI shows and accepts back. */
    fun format(millis: Long): String =
        SimpleDateFormat(DISPLAY_PATTERN, Locale.getDefault()).format(Date(millis))

    /** Today, normalised to the start of the day. */
    fun today(): Long = startOfDay(System.currentTimeMillis())

    /**
     * Rejects a date the user could not plausibly have transacted on.
     *
     * Only the future is refused, and only beyond today: backdating is the
     * entire point of this feature, so there is no lower bound — a shopkeeper
     * catching up on last month's book is a normal case, not an error.
     */
    fun isNotInFuture(millis: Long): Boolean = millis <= endOfToday()

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}
