package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.utils.collectionTotals
import junit.framework.TestCase.assertEquals
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
 * The summary against live repository state: a transaction recorded offline
 * has to move the filtered totals straight away, with no backend involved.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartiesSummaryOfflineTest {

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

    private fun totals(filter: CollectionFrequency?) =
        collectionTotals(repository.parties.value, businessId, filter)

    @Test
    fun `an offline transaction moves only its own collection's totals`() = runTest {
        val weekly = repository.addParty("W", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        repository.addParty("D", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.DAILY)

        // No backend is reachable here; this writes to Room and queues.
        repository.addLedgerEntry(weekly.id, 1200.0, TransactionType.GAVE)

        assertEquals(1200.0, totals(CollectionFrequency.WEEKLY).youWillGet)
        assertEquals(0.0, totals(CollectionFrequency.DAILY).youWillGet)
        assertEquals(1200.0, totals(null).youWillGet)
    }

    @Test
    fun `totals reflect the new balance immediately, without a sync`() = runTest {
        val p = repository.addParty("W", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        assertEquals(0.0, totals(CollectionFrequency.WEEKLY).youWillGet)

        repository.addLedgerEntry(p.id, 500.0, TransactionType.GAVE)

        assertEquals(500.0, totals(CollectionFrequency.WEEKLY).youWillGet)
    }

    @Test
    fun `a customer paying moves the amount to the give side of the same collection`() = runTest {
        val p = repository.addParty("W", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.MONTHLY)

        // Paid us more than they owed — now we hold their credit.
        repository.addLedgerEntry(p.id, 400.0, TransactionType.GOT)

        val monthly = totals(CollectionFrequency.MONTHLY)
        assertEquals(0.0, monthly.youWillGet)
        assertEquals(400.0, monthly.youWillGive)
        // And no other bucket picked it up.
        assertEquals(0.0, totals(CollectionFrequency.DAILY).youWillGive)
    }

    @Test
    fun `each collection stays independent as entries accumulate`() = runTest {
        val d = repository.addParty("D", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.DAILY)
        val w = repository.addParty("W", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        val m = repository.addParty("M", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.MONTHLY)

        repository.addLedgerEntry(d.id, 1000.0, TransactionType.GAVE)
        repository.addLedgerEntry(w.id, 2000.0, TransactionType.GAVE)
        repository.addLedgerEntry(m.id, 5000.0, TransactionType.GAVE)

        assertEquals(1000.0, totals(CollectionFrequency.DAILY).youWillGet)
        assertEquals(2000.0, totals(CollectionFrequency.WEEKLY).youWillGet)
        assertEquals(5000.0, totals(CollectionFrequency.MONTHLY).youWillGet)
        assertEquals(8000.0, totals(null).youWillGet)
    }

    @Test
    fun `another business's balances never leak into these totals`() = runTest {
        val mine = repository.addParty("MINE", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        repository.addLedgerEntry(mine.id, 100.0, TransactionType.GAVE)

        repository.setActiveBusinessIdForTesting("other-business")
        val theirs = repository.addParty("THEIRS", "", PartyType.CUSTOMER, businessId = "other-business", frequency = CollectionFrequency.WEEKLY)
        repository.addLedgerEntry(theirs.id, 9999.0, TransactionType.GAVE)

        // Scoped by businessId, so the other book is invisible to these totals.
        assertEquals(100.0, collectionTotals(repository.parties.value, businessId, CollectionFrequency.WEEKLY).youWillGet)
        assertEquals(9999.0, collectionTotals(repository.parties.value, "other-business", CollectionFrequency.WEEKLY).youWillGet)
    }

    @Test
    fun `the underlying party balance is unchanged by summarising it`() = runTest {
        val p = repository.addParty("W", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        repository.addLedgerEntry(p.id, 750.0, TransactionType.GAVE)

        val balanceBefore = repository.parties.value.first { it.id == p.id }.currentBalance
        totals(null); totals(CollectionFrequency.WEEKLY)

        assertEquals(balanceBefore, repository.parties.value.first { it.id == p.id }.currentBalance)
        assertEquals(750.0, balanceBefore)
    }
}
