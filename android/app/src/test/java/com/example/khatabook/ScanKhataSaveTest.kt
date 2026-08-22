package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * Accepting scanned rows has to put them in the book.
 *
 * The reported failure was a save button that appeared to do nothing: rows
 * whose name resembled an existing customer were routed onto an internal
 * pending-review queue that no screen renders, so they silently never became
 * ledger entries. Confirming a row as a new customer in the scan dialog now
 * settles it, and the call reports whether the row actually landed so the
 * caller can say so rather than claim a silent success.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScanKhataSaveTest {

    private val ownerPhone = "9000000001"
    private val businessId = "test-business"
    private lateinit var db: com.example.khatabook.data.local.KhatabookDatabase
    private lateinit var repository: KhatabookRepository

    @Before
    fun setup() {
        db = buildInMemoryTestDatabase()
        val editor = mock<SharedPreferences.Editor> {
            on { putString(any(), any()) } doReturn it
            on { putBoolean(any(), any()) } doReturn it
        }
        val prefs = mock<SharedPreferences> {
            on { getString(any(), anyOrNull()) } doReturn null
            on { getBoolean(any(), any()) } doAnswer { inv -> inv.getArgument(1) }
            on { edit() } doReturn editor
        }
        val context = mock<Context> { on { getSharedPreferences(any(), any()) } doReturn prefs }
        repository = KhatabookRepository(
            context,
            prefsFactory = { FakeSharedPreferences() },
            databaseFactory = { db },
        )
        repository.switchUser(ownerPhone)
        repository.setActiveBusinessIdForTesting(businessId)
    }

    @After
    fun tearDown() = runBlocking { repository.cancelBackgroundWorkForTesting() }

    private fun entriesFor(name: String) =
        repository.parties.value.firstOrNull { it.name == name }
            ?.let { p -> repository.ledgerEntries.value.filter { it.partyId == p.id } }
            .orEmpty()

    @Test
    fun `a brand-new scanned customer is created with its entry`() = runTest {
        val ok = repository.addOrUpdateSheetRecord("RAMESH", 500.0, "22/08/2026", businessId, isNewCustomer = true)

        assertTrue(ok)
        assertEquals(1, entriesFor("RAMESH").size)
        assertEquals(500.0, entriesFor("RAMESH").first().amount)
    }

    @Test
    fun `a scanned name matching an existing customer attaches to it`() = runTest {
        repository.addParty("RAMESH", "", PartyType.CUSTOMER, businessId = businessId)

        val ok = repository.addOrUpdateSheetRecord("ramesh", 700.0, "22/08/2026", businessId, isNewCustomer = false)

        assertTrue(ok)
        // Attached, not duplicated.
        assertEquals(1, repository.parties.value.count { it.name == "RAMESH" })
        assertEquals(700.0, entriesFor("RAMESH").first().amount)
    }

    @Test
    fun `a confirmed new customer is created even when an existing name is similar`() = runTest {
        // The exact reported case: OCR reads "bhavish 10" while "BHAVISH"
        // already exists. This used to be swallowed by the ambiguity queue and
        // never reached the ledger.
        repository.addParty("BHAVISH", "", PartyType.CUSTOMER, businessId = businessId)

        val ok = repository.addOrUpdateSheetRecord("bhavish 10", 300.0, "22/08/2026", businessId, isNewCustomer = true)

        assertTrue("the confirmed row must be saved", ok)
        assertEquals(1, entriesFor("BHAVISH 10").size)
        // And the original customer is untouched.
        assertEquals(0, entriesFor("BHAVISH").size)
    }

    @Test
    fun `a whole sheet of new customers all land`() = runTest {
        val rows = listOf("BHAVISH" to 1000.0, "RAHUL" to 500.0, "RAJU" to 2000.0)

        val results = rows.map { (name, amt) ->
            repository.addOrUpdateSheetRecord(name, amt, "22/08/2026", businessId, isNewCustomer = true)
        }

        assertTrue(results.all { it })
        assertEquals(3, repository.parties.value.size)
        assertEquals(3, repository.ledgerEntries.value.size)
        assertEquals(3500.0, repository.ledgerEntries.value.sumOf { it.amount })
    }

    @Test
    fun `every scanned entry is recorded as money received`() = runTest {
        repository.addOrUpdateSheetRecord("RAMESH", 500.0, "22/08/2026", businessId, isNewCustomer = true)

        val entry = entriesFor("RAMESH").first()
        assertEquals(TransactionType.GOT, entry.type)
        // A collection sheet records money coming in, so the customer's debt
        // falls rather than rises.
        assertEquals(-500.0, repository.parties.value.first { it.name == "RAMESH" }.currentBalance)
    }

    @Test
    fun `the sheet date becomes the entry's business date`() = runTest {
        repository.addOrUpdateSheetRecord("RAMESH", 500.0, "18/08/2026", businessId, isNewCustomer = true)

        val entry = entriesFor("RAMESH").first()
        assertEquals(
            com.example.khatabook.utils.SheetDates.parseOrNull("18/08/2026"),
            entry.transactionDate,
        )
    }

    @Test
    fun `an unmatched row that was not confirmed reports failure rather than failing silently`() = runTest {
        // Nothing to attach to and not confirmed as new: the row cannot be
        // saved, and the caller is told so it can surface it.
        val ok = repository.addOrUpdateSheetRecord("SOMEONE", 500.0, "22/08/2026", businessId, isNewCustomer = false)

        assertFalse(ok)
        assertEquals(0, repository.ledgerEntries.value.size)
    }

    @Test
    fun `a zero-amount row reports failure`() = runTest {
        assertFalse(repository.addOrUpdateSheetRecord("RAMESH", 0.0, "22/08/2026", businessId, isNewCustomer = true))
        assertEquals(0, repository.ledgerEntries.value.size)
    }

    @Test
    fun `scanned rows are queued for sync like any other entry`() = runTest {
        repository.addOrUpdateSheetRecord("RAMESH", 500.0, "22/08/2026", businessId, isNewCustomer = true)

        val queued = db.outboxDao().getAllSync(ownerPhone)
        assertTrue(queued.any { it.type == "CREATE_PARTY" })
        assertTrue(queued.any { it.type == "CREATE_ENTRY" })
    }

    @Test
    fun `scanned rows land in the active business only`() = runTest {
        repository.addOrUpdateSheetRecord("RAMESH", 500.0, "22/08/2026", businessId, isNewCustomer = true)

        assertTrue(repository.parties.value.all { it.businessId == businessId })
    }
}
