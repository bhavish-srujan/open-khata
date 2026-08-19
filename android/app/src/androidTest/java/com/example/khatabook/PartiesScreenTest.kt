package com.example.khatabook

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.ui.screens.PartiesScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PartiesScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val sampleParties = listOf(
        Party(id = "c1", name = "Dad", phone = "+91 98234 11223", type = PartyType.CUSTOMER, currentBalance = 0.0),
        Party(id = "c2", name = "Amma", phone = "+91 97400 62212", type = PartyType.CUSTOMER, currentBalance = 7000.0)
    )

    @Before
    fun setup() {
        composeTestRule.setContent {
            PartiesScreen(
                parties = sampleParties,
                onAddPartyClick = {},
                onCashbookClick = {},
                onViewReportsClick = {},
                onMoreTabClick = {},
                onPartyClick = {}
            )
        }
    }

    @Test
    fun businessHeader_isDisplayed() {
        // This rule supplies no `businesses` list, so PartiesScreen falls back
        // to its placeholder BusinessAccount("My Business") — the header renders
        // just the business name now, with no parenthetical owner suffix (that
        // was from an old, invented two-shop demo default that no longer exists).
        composeTestRule.onNodeWithText("My Business").assertIsDisplayed()
    }

    @Test
    fun addCustomerFab_isDisplayed() {
        composeTestRule.onNodeWithText("ADD CUSTOMER").assertIsDisplayed()
    }
}
