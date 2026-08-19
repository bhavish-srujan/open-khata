package com.example.khatabook.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class ReportPeriod(val label: String) {
    THIS_MONTH("This Month"),
    SINGLE_DAY("Single Day"),
    LAST_WEEK("Last Week"),
    LAST_MONTH("Last Month"),
    ALL("All"),
    DATE_RANGE("Date Range"),
}

/** ISO-8601 UTC instant bounds for a report query — inclusive of both ends. */
data class DateRange(val startIso: String, val endIso: String)

/**
 * Turns a [ReportPeriod] into the `(startDate, endDate)` bounds the backend's
 * `/reports/cashflow` and `/reports/statement/pdf` endpoints expect, and the
 * bounds `getBusinessLedger(from, to)`-shaped client-side filtering uses for
 * the day-wise breakdown.
 *
 * Uses [Calendar] rather than `java.time` because minSdk is 24 and core
 * library desugaring is not enabled (same constraint as the existing
 * `startDateFor` helper in ViewReportsScreen.kt, which this generalizes and
 * extends with end-of-range bounds and the extra periods the Cashbook report
 * needs — Single Day / Last Week / Last Month / a validated custom range —
 * so both screens can share one implementation instead of two).
 */
object ReportPeriodUtils {

    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun utcCalendar(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    private fun startOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    /** End-of-day, inclusive — so a transaction recorded at 23:59:59 on the
     *  end date is not silently dropped by an exclusive/midnight bound. */
    private fun endOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
    }

    fun rangeFor(
        period: ReportPeriod,
        customStartIso: String? = null,
        customEndIso: String? = null,
        now: Calendar = utcCalendar(),
    ): DateRange {
        val format = isoFormat()
        val end = now.clone() as Calendar
        endOfDay(end)

        val start = now.clone() as Calendar
        when (period) {
            ReportPeriod.THIS_MONTH -> {
                start.set(Calendar.DAY_OF_MONTH, 1)
                startOfDay(start)
            }
            ReportPeriod.SINGLE_DAY -> {
                startOfDay(start)
            }
            ReportPeriod.LAST_WEEK -> {
                start.add(Calendar.DAY_OF_MONTH, -7)
                startOfDay(start)
            }
            ReportPeriod.LAST_MONTH -> {
                // The full previous calendar month, not "30 days ago" — distinct
                // from THIS_MONTH, which is the current one.
                start.add(Calendar.MONTH, -1)
                start.set(Calendar.DAY_OF_MONTH, 1)
                startOfDay(start)
                val lastMonthEnd = start.clone() as Calendar
                lastMonthEnd.set(Calendar.DAY_OF_MONTH, lastMonthEnd.getActualMaximum(Calendar.DAY_OF_MONTH))
                endOfDay(lastMonthEnd)
                return DateRange(format.format(start.time), format.format(lastMonthEnd.time))
            }
            ReportPeriod.ALL -> {
                start.timeInMillis = 0L
            }
            ReportPeriod.DATE_RANGE -> {
                return DateRange(
                    customStartIso ?: format.format(start.time),
                    customEndIso ?: format.format(end.time),
                )
            }
        }
        return DateRange(format.format(start.time), format.format(end.time))
    }

    /**
     * Validates a user-picked custom range before it is ever sent anywhere.
     * Returns a user-facing error message, or null if the range is valid.
     * Start == End is explicitly valid (a single-day custom range).
     */
    fun validateCustomRange(startEpochMs: Long, endEpochMs: Long): String? {
        return if (startEpochMs > endEpochMs) "Start date must not be after end date" else null
    }

    /** Truncates an ISO-8601 UTC instant to its `yyyy-MM-dd` calendar day, for
     *  grouping already-fetched entries into a day-wise breakdown. */
    fun dayKeyOf(isoInstant: String): String = isoInstant.take(10)
}
