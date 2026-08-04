package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class KhatabookRepositoryTest {

    private lateinit var repository: KhatabookRepository

    @Before
    fun setup() {
        // Mock SharedPreferences
        val mockEditor = mock<SharedPreferences.Editor> {
            on { putString(any(), any()) } doReturn it
            // apply() is void, Mockito handles it automatically
        }
        val mockPrefs = mock<SharedPreferences> {
            on { getString(any(), any()) } doReturn null
            on { edit() } doReturn mockEditor
        }
        val mockContext = mock<Context> {
            on { getSharedPreferences(any(), any()) } doReturn mockPrefs
        }
        repository = KhatabookRepository(mockContext)
    }

    @Test
    fun addParty_addsPartyToList() = runTest {
        val newParty = repository.addParty("Amit Traders", "+91 99999 88888", PartyType.CUSTOMER)
        val parties = repository.parties.first()
        
        assertNotNull(newParty)
        assertEquals("Amit Traders", parties.first().name)
    }

    @Test
    fun addLedgerEntry_calculatesAtomicRunningBalance() = runTest {
        // First add a party to test with
        val party = repository.addParty("Customer 1", "+91 123", PartyType.CUSTOMER)
        
        repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE)

        val updatedParty = repository.parties.first().find { it.id == party.id }!!
        assertEquals(500.0, updatedParty.currentBalance)
    }
}
