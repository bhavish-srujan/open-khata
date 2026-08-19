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
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/** RobolectricTestRunner: KhatabookRepository now persists through Room,
 *  whose real SQLite implementation needs Robolectric's Android-framework
 *  shadow to run outside a device/emulator — see InMemoryTestDatabase.kt.
 *  NATIVE SQLite mode: every write also fires a background (Dispatchers.IO)
 *  Outbox flush attempt that touches Room from a different thread than the
 *  test itself — Robolectric's legacy SQLite shadow is not safe for that. */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KhatabookRepositoryTest {

    private lateinit var repository: KhatabookRepository

    @Before
    fun setup() {
        // Secondary prefs the repository reads directly via the context (e.g.
        // SMS settings) — unrelated to the main persistence store, which is
        // now injected below via FakeSharedPreferences instead of going
        // through the context at all.
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
        // Real, deterministic in-memory storage for KhatabookRepository's own
        // persistence (parties/entries/outbox/caches), so this test exercises
        // actual save/load logic instead of a Mockito stub that discards
        // everything written to it. Production still defaults to
        // EncryptedSharedPreferences — see KhatabookRepository's prefsFactory
        // parameter.
        repository = KhatabookRepository(
            mockContext,
            prefsFactory = { FakeSharedPreferences() },
            databaseFactory = { buildInMemoryTestDatabase() },
        )
        repository.switchUser("9000000001")
        repository.setActiveBusinessIdForTesting("test-business")
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

    @Test
    fun addLedgerEntry_roundsAmountToTwoDecimalPlaces() = runTest {
        // Scan Khata → Accept produced "1 change could not be saved to the
        // server": Gemini-extracted amounts are not guaranteed to be exactly
        // representable in IEEE 754 double (this value is a real example,
        // not a contrived one), and the backend's numeric(14,2) column plus
        // its @IsNumber({maxDecimalPlaces:2}) validation correctly rejects
        // anything with more than 2 decimal places with a permanent 400 —
        // which the outbox then reports as an unsendable change forever.
        val party = repository.addParty("Customer 2", "+91 456", PartyType.CUSTOMER)
        val noisyAmount = 449.99999999999994

        val entry = repository.addLedgerEntry(party.id, noisyAmount, TransactionType.GOT)

        assertNotNull(entry)
        assertEquals(450.0, entry!!.amount)
        // The locally-displayed balance must match what actually gets sent —
        // a mismatch here would be a silent, invisible discrepancy.
        val updatedParty = repository.parties.first().find { it.id == party.id }!!
        assertEquals(-450.0, updatedParty.currentBalance)
    }
}
