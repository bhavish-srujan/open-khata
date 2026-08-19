package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.UpdatePartyInterestRequest
import com.example.khatabook.data.model.InterestPeriodUnit
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.google.gson.Gson
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * Editing interest on an existing party, and charging it as real money.
 *
 * The invariant that matters most here: configuring a rate must never move a
 * balance by itself. Money only changes when an interest charge is explicitly
 * applied, and then only via a normal ledger entry — so it is visible,
 * auditable and deletable like any other transaction.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class InterestChargeTest {

    private val ownerPhone = "9000000001"
    private val businessId = "test-business"
    private lateinit var db: com.example.khatabook.data.local.KhatabookDatabase
    private lateinit var repository: KhatabookRepository
    private val gson = Gson()

    @Before
    fun setup() {
        db = buildInMemoryTestDatabase()
        val mockEditor = mock<SharedPreferences.Editor> {
            on { putString(any(), any()) } doReturn it
            on { putBoolean(any(), any()) } doReturn it
        }
        val mockPrefs = mock<SharedPreferences> {
            on { getString(any(), anyOrNull()) } doReturn null
            on { edit() } doReturn mockEditor
        }
        val mockContext = mock<Context> {
            on { getSharedPreferences(any(), any()) } doReturn mockPrefs
        }
        repository = KhatabookRepository(
            mockContext,
            prefsFactory = { FakeSharedPreferences() },
            databaseFactory = { db },
        )
        repository.switchUser(ownerPhone)
        repository.setActiveBusinessIdForTesting(businessId)
    }

    /**
     * Every add/update call here fires a fire-and-forget background flush on
     * a real dispatcher. Left running past this class, those coroutines race
     * whichever test runs next against the process-wide NetworkClient
     * singleton — which is exactly how an unrelated, previously-green test
     * (OutboxRollbackTest) started timing out when this class was added.
     */
    @After
    fun tearDown() = runBlocking {
        repository.cancelBackgroundWorkForTesting()
    }

    private fun customerOwing(amount: Double) =
        repository.addParty("Debtor", "+91 90000 00001", PartyType.CUSTOMER, businessId = businessId).also {
            repository.addLedgerEntry(it.id, amount, TransactionType.GAVE)
        }

    // ─── Editing ────────────────────────────────────────────────

    @Test
    fun `updating interest stores the new configuration locally`() = runTest {
        val party = repository.addParty("P", "+91 90000 00002", PartyType.CUSTOMER, businessId = businessId)

        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val updated = repository.parties.value.first { it.id == party.id }
        assertTrue(updated.interestEnabled)
        assertEquals(5.0, updated.interestRate)
        assertEquals(100, updated.interestPeriodValue)
        assertEquals(InterestPeriodUnit.DAYS, updated.interestPeriodUnit)
    }

    @Test
    fun `updating interest queues an UPDATE_PARTY_INTEREST op for the server`() = runTest {
        val party = repository.addParty("P", "+91 90000 00003", PartyType.CUSTOMER, businessId = businessId)

        repository.updatePartyInterest(party.id, true, 2.5, 3, InterestPeriodUnit.MONTHS)

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.type == "UPDATE_PARTY_INTEREST" }
        val sent = gson.fromJson(row.payload, UpdatePartyInterestRequest::class.java)
        assertEquals(true, sent.interestEnabled)
        assertEquals(2.5, sent.interestRate)
        assertEquals(3, sent.interestPeriodValue)
        assertEquals("MONTHS", sent.interestPeriodUnit)
    }

    @Test
    fun `turning interest off zeroes the rate and period`() = runTest {
        val party = repository.addParty("P", "+91 90000 00004", PartyType.CUSTOMER, businessId = businessId)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        repository.updatePartyInterest(party.id, false, 5.0, 100, InterestPeriodUnit.DAYS)

        val off = repository.parties.value.first { it.id == party.id }
        assertEquals(false, off.interestEnabled)
        assertEquals(0.0, off.interestRate)
        assertEquals(0, off.interestPeriodValue)
    }

    @Test
    fun `configuring interest never changes the balance by itself`() = runTest {
        val party = customerOwing(10000.0)
        val before = repository.parties.value.first { it.id == party.id }.currentBalance

        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val after = repository.parties.value.first { it.id == party.id }.currentBalance
        assertEquals(before, after)
    }

    // ─── Charging ───────────────────────────────────────────────

    @Test
    fun `applying interest creates a real ledger entry and moves the balance`() = runTest {
        val party = customerOwing(10000.0)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val entry = repository.applyInterest(party.id)

        // 5% of 10,000 = 500, charged once — not annualized, not prorated.
        assertNotNull(entry)
        assertEquals(500.0, entry!!.amount)
        assertEquals(TransactionType.GAVE, entry.type)
        assertEquals(10500.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    @Test
    fun `the interest entry records how it was calculated`() = runTest {
        val party = customerOwing(10000.0)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val entry = repository.applyInterest(party.id)

        assertEquals("Interest @ 5% / 100 Days", entry!!.note)
    }

    @Test
    fun `applying interest queues the charge for server sync like any entry`() = runTest {
        val party = customerOwing(10000.0)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val entry = repository.applyInterest(party.id)

        val queued = db.outboxDao().getAllSync(ownerPhone).filter { it.type == "CREATE_ENTRY" }
        assertTrue(queued.any { it.targetId == entry!!.id })
    }

    @Test
    fun `no charge when interest is not enabled`() = runTest {
        val party = customerOwing(10000.0)

        assertNull(repository.applyInterest(party.id))
        assertEquals(10000.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    @Test
    fun `no charge when the party owes nothing`() = runTest {
        val party = repository.addParty("Settled", "+91 90000 00005", PartyType.CUSTOMER, businessId = businessId)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        assertNull(repository.applyInterest(party.id))
        assertEquals(0.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    @Test
    fun `charging twice posts two separate entries rather than compounding silently`() = runTest {
        val party = customerOwing(10000.0)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val first = repository.applyInterest(party.id)
        val second = repository.applyInterest(party.id)

        // Each tap is one visible, deletable entry. The second is computed on
        // the new balance (10,500), so it is 525 — deliberate and inspectable,
        // not a hidden accrual.
        assertEquals(500.0, first!!.amount)
        assertEquals(525.0, second!!.amount)
        assertEquals(11025.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    @Test
    fun `interest on a customer in credit is not charged`() = runTest {
        val party = repository.addParty("Credit", "+91 90000 00006", PartyType.CUSTOMER, businessId = businessId)
        // Customer paid us in advance — they owe nothing, so there is nothing
        // to charge interest on. Charging here would bill someone for holding
        // credit with us.
        repository.addLedgerEntry(party.id, 2000.0, TransactionType.GOT)
        repository.updatePartyInterest(party.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        assertNull(repository.applyInterest(party.id))
        assertEquals(-2000.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    @Test
    fun `interest is not charged on a supplier we owe`() = runTest {
        val supplier = repository.addParty("Vendor", "+91 90000 00007", PartyType.SUPPLIER, businessId = businessId)
        // SUPPLIER + GOT is positive under this app's convention, meaning we
        // owe them — the mirror of a customer in credit.
        repository.addLedgerEntry(supplier.id, 3000.0, TransactionType.GOT)
        repository.updatePartyInterest(supplier.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        assertNull(repository.applyInterest(supplier.id))
    }

    @Test
    fun `interest is charged on a supplier who owes us`() = runTest {
        val supplier = repository.addParty("Vendor", "+91 90000 00008", PartyType.SUPPLIER, businessId = businessId)
        // SUPPLIER + GAVE is negative: we advanced them money, so they owe us.
        repository.addLedgerEntry(supplier.id, 4000.0, TransactionType.GAVE)
        repository.updatePartyInterest(supplier.id, true, 5.0, 100, InterestPeriodUnit.DAYS)

        val entry = repository.applyInterest(supplier.id)

        assertNotNull(entry)
        assertEquals(200.0, entry!!.amount)
        // GAVE deepens the debt owed to us under the supplier convention.
        assertEquals(-4200.0, repository.parties.value.first { it.id == supplier.id }.currentBalance)
    }
}
