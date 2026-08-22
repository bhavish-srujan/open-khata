package com.example.khatabook

import com.example.khatabook.utils.SheetDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * How a Scan Khata sheet's date is resolved before it becomes money.
 *
 * These pin the decision rules the confirmation dialog applies: what the
 * displayed date defaults to, what counts as editable-and-valid, and which
 * values are refused outright. The rules live in [SheetDates] precisely so
 * they can be tested without standing up Compose.
 */
class ScanKhataDateTest {

    /** Mirrors the dialog: a real extracted date wins, otherwise today. */
    private fun resolveSheetDate(extracted: String?): Long =
        SheetDates.parseOrNull(extracted) ?: SheetDates.today()

    /** Mirrors MainActivity: a row's own date wins over the sheet date. */
    private fun resolveRowDate(rowDate: String, sheetDate: String): String =
        rowDate.ifBlank { sheetDate }

    @Test
    fun `a date read off the sheet is used`() {
        val resolved = resolveSheetDate("2026-08-21")

        assertEquals(SheetDates.parseOrNull("21/08/2026"), resolved)
    }

    @Test
    fun `no date on the sheet falls back to today`() {
        // The backend now returns "" rather than inventing a date.
        assertEquals(SheetDates.today(), resolveSheetDate(""))
        assertEquals(SheetDates.today(), resolveSheetDate(null))
    }

    @Test
    fun `an AI-hallucinated date falls back to today rather than being used`() {
        assertEquals(SheetDates.today(), resolveSheetDate("2026-99-99"))
        assertEquals(SheetDates.today(), resolveSheetDate("2026-02-31"))
        assertEquals(SheetDates.today(), resolveSheetDate("Unknown Date"))
    }

    @Test
    fun `the user can replace the extracted date with any valid past date`() {
        // Editing is just re-parsing what they typed.
        val edited = SheetDates.parseOrNull("20/08/2026")

        assertNotNull(edited)
        assertTrue(edited!! < SheetDates.parseOrNull("21/08/2026")!!)
    }

    @Test
    fun `an edited date that is not a real date is refused`() {
        // The dialog blocks accept on null rather than substituting a date.
        assertNull(SheetDates.parseOrNull("31/02/2026"))
        assertNull(SheetDates.parseOrNull("hello"))
    }

    @Test
    fun `a future sheet date is refused`() {
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

        assertTrue(!SheetDates.isNotInFuture(tomorrow))
    }

    @Test
    fun `a row without its own date follows the confirmed sheet date`() {
        assertEquals("21/08/2026", resolveRowDate("", "21/08/2026"))
    }

    @Test
    fun `a row carrying its own date keeps it`() {
        // A sheet listing different dates per line must not be flattened onto
        // one global date.
        assertEquals("19/08/2026", resolveRowDate("19/08/2026", "21/08/2026"))
    }

    @Test
    fun `every accepted sheet date is normalised to the start of its day`() {
        val resolved = resolveSheetDate("2026-08-21")
        val cal = Calendar.getInstance().apply { timeInMillis = resolved }

        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }
}
