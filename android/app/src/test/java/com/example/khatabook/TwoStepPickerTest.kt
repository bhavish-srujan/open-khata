package com.example.khatabook

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * The two-step start/end date flow in the Cashbook report.
 *
 * Reported symptom: months navigate freely while choosing the start date,
 * then the arrows do nothing once the dialog moves on to the end date.
 *
 * Cause: both steps shared one DatePicker call site with only the `state`
 * argument swapped. DatePicker remembers its own month-scrolling state
 * internally, with no key, so that state survived the swap and desynced from
 * the incoming DatePickerState — the second step inherited the first step's
 * scroll position and stopped responding. Keying the call site on which step
 * is active disposes the first picker and composes the second fresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TwoStepPickerTest {

    @get:Rule val compose = createComposeRule()

    private val june2026 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.JUNE, 15)
    }.timeInMillis

    private fun nextMonth() {
        compose.onNodeWithContentDescription("Change to next month").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the end step starts on its own month and its arrows work`() {
        compose.setContent {
            var pickingStart by remember { mutableStateOf(true) }
            val startState = rememberDatePickerState(initialSelectedDateMillis = june2026)
            val endState = rememberDatePickerState(initialSelectedDateMillis = june2026)
            Column {
                TextButton(onClick = { pickingStart = false }) { Text("NEXT") }
                // The fix, mirroring CashbookReportScreen.
                key(pickingStart) {
                    DatePicker(
                        state = if (pickingStart) startState else endState,
                        showModeToggle = false,
                    )
                }
            }
        }

        // Start step navigates, as it always did.
        nextMonth()
        compose.onNodeWithText("July 2026").assertIsDisplayed()

        compose.onNodeWithText("NEXT").performClick()
        compose.waitForIdle()

        // The end step opens on its own month rather than inheriting the
        // start step's scroll position.
        compose.onNodeWithText("June 2026").assertIsDisplayed()

        // And this is the part that was frozen: the arrows must advance it.
        nextMonth()
        compose.onNodeWithText("July 2026").assertIsDisplayed()

        nextMonth()
        compose.onNodeWithText("August 2026").assertIsDisplayed()
    }

    @Test
    fun `the end step can be navigated back as well as forward`() {
        compose.setContent {
            var pickingStart by remember { mutableStateOf(true) }
            val startState = rememberDatePickerState(initialSelectedDateMillis = june2026)
            val endState = rememberDatePickerState(initialSelectedDateMillis = june2026)
            Column {
                TextButton(onClick = { pickingStart = false }) { Text("NEXT") }
                key(pickingStart) {
                    DatePicker(
                        state = if (pickingStart) startState else endState,
                        showModeToggle = false,
                    )
                }
            }
        }

        compose.onNodeWithText("NEXT").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Change to previous month").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("May 2026").assertIsDisplayed()
    }

    /**
     * Guards the regression directly: with the steps sharing one un-keyed call
     * site, the end step's month does not move. If this ever starts advancing,
     * the underlying Compose behaviour has changed and the key may no longer
     * be needed — but until then it is exactly why the key is there.
     */
    @Test
    fun `without the key, the end step's month stays put`() {
        compose.setContent {
            var pickingStart by remember { mutableStateOf(true) }
            val startState = rememberDatePickerState(initialSelectedDateMillis = june2026)
            val endState = rememberDatePickerState(initialSelectedDateMillis = june2026)
            Column {
                TextButton(onClick = { pickingStart = false }) { Text("NEXT") }
                DatePicker(
                    state = if (pickingStart) startState else endState,
                    showModeToggle = false,
                )
            }
        }

        nextMonth()
        compose.onNodeWithText("July 2026").assertIsDisplayed()

        compose.onNodeWithText("NEXT").performClick()
        compose.waitForIdle()
        nextMonth()

        // The reported bug, pinned: still June.
        compose.onNodeWithText("June 2026").assertIsDisplayed()
    }
}
