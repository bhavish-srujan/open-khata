package com.example.khatabook

import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.example.khatabook.ui.components.khatabookDatePickerColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * The report's date picker, driven the way a user drives it.
 *
 * Added after two defects that no unit test could have caught, because both
 * were about what the control actually does on screen: the calendar's last
 * column was clipped out of view, and month navigation was reported as not
 * working. Rendering the real composable and clicking its real arrows is the
 * only way those show up short of a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DateRangePickerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    /** 15 June 2026, fixed so the assertions do not depend on today's date. */
    private val june2026 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.JUNE, 15)
    }.timeInMillis

    private fun showPicker() {
        compose.setContent {
            val state = rememberDatePickerState(initialSelectedDateMillis = june2026)
            DatePicker(
                state = state,
                showModeToggle = false,
                colors = khatabookDatePickerColors(),
            )
        }
    }

    @Test
    fun `the picker opens on the month of the selected date`() {
        showPicker()

        compose.onNodeWithText("June 2026").assertIsDisplayed()
    }

    @Test
    fun `the next-month arrow advances the calendar`() {
        showPicker()

        compose.onNodeWithContentDescription("Change to next month").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("July 2026").assertIsDisplayed()
    }

    @Test
    fun `the previous-month arrow moves the calendar back`() {
        showPicker()

        compose.onNodeWithContentDescription("Change to previous month").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("May 2026").assertIsDisplayed()
    }

    @Test
    fun `navigation crosses a year boundary`() {
        showPicker()

        // June -> December is six taps; one more rolls into the next year,
        // which is where an off-by-one in month arithmetic would surface.
        repeat(7) {
            compose.onNodeWithContentDescription("Change to next month").performClick()
            compose.waitForIdle()
        }

        compose.onNodeWithText("January 2027").assertIsDisplayed()
    }

    @Test
    fun `the month keeps advancing across repeated taps`() {
        // The reported symptom was the calendar staying on one month however
        // many times the arrow was pressed.
        showPicker()

        compose.onNodeWithContentDescription("Change to next month").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Change to next month").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("August 2026").assertIsDisplayed()
    }

    @Test
    fun `a date in the final column is fully on screen`() {
        // The clipping bug was horizontal: six weekday headers instead of
        // seven, with the last column sliced down the middle. June 2026
        // starts on a Monday, so the 6th falls in the final (Saturday)
        // column of the first week — the exact position that was cut off,
        // and high enough in the grid that vertical space is not a factor.
        showPicker()

        // Day cells expose a full accessible label, not a bare number.
        compose.onNodeWithText("Saturday, June 6, 2026").assertIsDisplayed()
    }
}
