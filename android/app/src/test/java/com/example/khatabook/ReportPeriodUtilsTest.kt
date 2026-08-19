package com.example.khatabook

import com.example.khatabook.utils.ReportPeriod
import com.example.khatabook.utils.ReportPeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReportPeriodUtilsTest {

    // 2026-08-14 15:30:00 UTC — a Friday, mid-month, mid-year.
    private fun fixedNow(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.AUGUST, 14, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `this month starts on the 1st at midnight and ends now`() {
        val range = ReportPeriodUtils.rangeFor(ReportPeriod.THIS_MONTH, now = fixedNow())
        assertEquals("2026-08-01T00:00:00.000Z", range.startIso)
        assertEquals("2026-08-14T23:59:59.999Z", range.endIso)
    }

    @Test
    fun `single day is today's full 24 hours`() {
        val range = ReportPeriodUtils.rangeFor(ReportPeriod.SINGLE_DAY, now = fixedNow())
        assertEquals("2026-08-14T00:00:00.000Z", range.startIso)
        assertEquals("2026-08-14T23:59:59.999Z", range.endIso)
    }

    @Test
    fun `last week is the 7 days before today, inclusive of today`() {
        val range = ReportPeriodUtils.rangeFor(ReportPeriod.LAST_WEEK, now = fixedNow())
        assertEquals("2026-08-07T00:00:00.000Z", range.startIso)
        assertEquals("2026-08-14T23:59:59.999Z", range.endIso)
    }

    @Test
    fun `last month is the full previous calendar month, not this month`() {
        val range = ReportPeriodUtils.rangeFor(ReportPeriod.LAST_MONTH, now = fixedNow())
        assertEquals("2026-07-01T00:00:00.000Z", range.startIso)
        assertEquals("2026-07-31T23:59:59.999Z", range.endIso)
    }

    @Test
    fun `last month handles a february-adjacent short month correctly`() {
        val marchNow = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2027, Calendar.MARCH, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val range = ReportPeriodUtils.rangeFor(ReportPeriod.LAST_MONTH, now = marchNow)
        assertEquals("2027-02-01T00:00:00.000Z", range.startIso)
        // 2027 is not a leap year.
        assertEquals("2027-02-28T23:59:59.999Z", range.endIso)
    }

    @Test
    fun `all uses an epoch floor, not the backend's own default of one month back`() {
        val range = ReportPeriodUtils.rangeFor(ReportPeriod.ALL, now = fixedNow())
        assertEquals("1970-01-01T00:00:00.000Z", range.startIso)
    }

    @Test
    fun `date range uses exactly the caller-supplied bounds`() {
        val range = ReportPeriodUtils.rangeFor(
            ReportPeriod.DATE_RANGE,
            customStartIso = "2026-01-05T00:00:00.000Z",
            customEndIso = "2026-01-10T23:59:59.999Z",
            now = fixedNow(),
        )
        assertEquals("2026-01-05T00:00:00.000Z", range.startIso)
        assertEquals("2026-01-10T23:59:59.999Z", range.endIso)
    }

    @Test
    fun `validateCustomRange rejects start after end`() {
        val start = fixedNow().timeInMillis
        val end = start - 1
        assertNotNull(ReportPeriodUtils.validateCustomRange(start, end))
    }

    @Test
    fun `validateCustomRange accepts start equal to end`() {
        val instant = fixedNow().timeInMillis
        assertNull(ReportPeriodUtils.validateCustomRange(instant, instant))
    }

    @Test
    fun `validateCustomRange accepts start before end`() {
        val start = fixedNow().timeInMillis
        val end = start + 86_400_000
        assertNull(ReportPeriodUtils.validateCustomRange(start, end))
    }

    @Test
    fun `dayKeyOf truncates an ISO instant to its calendar day`() {
        assertEquals("2026-08-14", ReportPeriodUtils.dayKeyOf("2026-08-14T23:59:59.999Z"))
    }
}
