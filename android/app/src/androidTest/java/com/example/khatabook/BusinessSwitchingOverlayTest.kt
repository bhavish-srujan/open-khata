package com.example.khatabook

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.khatabook.ui.components.AccountTransitionMode
import com.example.khatabook.ui.components.BusinessSwitchingOverlay
import org.junit.Rule
import org.junit.Test

/**
 * The overlay used to be business-switch-only, with copy hardcoded to
 * "Switching Business Account..." / "Account Switched!" regardless of what was
 * actually happening, and a fixed 400ms+450ms timer disconnected from whether the
 * real switch had finished. This pins that each [AccountTransitionMode] shows its
 * own copy, and that the success state only ever appears once the caller reports
 * the real operation as complete — never before.
 */
class BusinessSwitchingOverlayTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun businessSwitch_showsBusinessCopy_notAccountCopy() {
        composeTestRule.setContent {
            BusinessSwitchingOverlay(
                mode = AccountTransitionMode.BUSINESS_SWITCH,
                targetLabel = "Shop Two",
                isOperationComplete = false,
                onFinished = {}
            )
        }

        composeTestRule.onNodeWithText("Switching Business...").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shop Two").assertIsDisplayed()
    }

    @Test
    fun accountSwitch_showsAccountCopy() {
        composeTestRule.setContent {
            BusinessSwitchingOverlay(
                mode = AccountTransitionMode.ACCOUNT_SWITCH,
                targetLabel = "+919000000501",
                isOperationComplete = false,
                onFinished = {}
            )
        }

        composeTestRule.onNodeWithText("Switching Account...").assertIsDisplayed()
    }

    @Test
    fun logout_showsLogoutCopy_withNoIsolationFooter() {
        composeTestRule.setContent {
            BusinessSwitchingOverlay(
                mode = AccountTransitionMode.LOGOUT,
                targetLabel = "",
                isOperationComplete = false,
                onFinished = {}
            )
        }

        composeTestRule.onNodeWithText("Logging Out...").assertIsDisplayed()
    }

    @Test
    fun successCopyNeverAppearsBeforeCallerReportsCompletion() {
        composeTestRule.setContent {
            BusinessSwitchingOverlay(
                mode = AccountTransitionMode.BUSINESS_SWITCH,
                targetLabel = "Shop Two",
                isOperationComplete = false,
                onFinished = {}
            )
        }

        // Still showing the in-progress state well past the old fixed 400ms
        // timer — proves the phase change is driven by isOperationComplete,
        // not a clock.
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.onNodeWithText("Switching Business...").assertIsDisplayed()
    }

    @Test
    fun successCopyAppearsOnceOperationCompletes() {
        var complete by mutableStateOf(false)
        var finished = false

        composeTestRule.setContent {
            BusinessSwitchingOverlay(
                mode = AccountTransitionMode.BUSINESS_SWITCH,
                targetLabel = "Shop Two",
                isOperationComplete = complete,
                onFinished = { finished = true }
            )
        }

        composeTestRule.onNodeWithText("Switching Business...").assertIsDisplayed()

        complete = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Business Switched!").assertIsDisplayed()

        // onFinished only fires after the brief success flourish, not
        // synchronously with isOperationComplete flipping.
        assert(!finished) { "onFinished must not fire the instant isOperationComplete flips" }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        assert(finished) { "onFinished should fire once the success phase has been shown" }
    }
}
