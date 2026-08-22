package com.example.khatabook.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parsing for the ISO-8601 timestamps the backend returns on ledger entries.
 *
 * This exists because restoring a device from the server used to lose every
 * transaction's real date: the sync paths built their domain objects without
 * passing `createdAt`, so each one silently fell back to the model default of
 * "now". After clearing local data, a year of history all collapsed onto the
 * restore date — which made every day/week/month report wrong, since those
 * periods are derived from exactly this field.
 *
 * The server is the source of truth for when a transaction happened, so its
 * timestamp is preserved verbatim rather than re-derived on the device.
 *
 * Timezone handling matches the rest of the app: the wire format is UTC
 * ("...Z"), parsed to an absolute epoch-millis value, and only rendered in
 * the device's local zone at display time (see LedgerEntryCard /
 * EntryDetailsDialog, which format the same epoch value with a default-locale
 * SimpleDateFormat). Storing absolute time and localising at the edge keeps a
 * transaction's instant stable across timezone changes.
 */
object IsoTime {

    /** Formats accepted from the server, most-specific first. TypeORM/Postgres
     *  emit fractional seconds, but a value serialised without them (or with a
     *  +05:30-style offset instead of Z) must not be discarded. */
    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )

    /**
     * Epoch millis for [iso], or [fallback] when it is blank or unparseable.
     *
     * Falling back rather than throwing is deliberate: a single malformed
     * timestamp must not abort a whole restore and leave the device with no
     * data at all. The caller passes the value it would have used anyway.
     */
    fun parseOrDefault(iso: String?, fallback: Long): Long {
        val raw = iso?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        for (pattern in PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                // Only the two explicitly-Z patterns need a forced zone; the
                // XXX patterns carry their own offset.
                if (pattern.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.isLenient = false
                return fmt.parse(raw)?.time ?: continue
            } catch (_: Exception) {
                // Try the next pattern.
            }
        }
        return fallback
    }

    /** UTC ISO-8601 for [millis] — the shape the backend itself emits. */
    fun format(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
