package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.CreateLedgerEntryRequest
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.utils.SheetDates
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import java.util.Calendar

/**
 * The user-chosen business date, end to end.
 *
 * The property under test throughout: transactionDate is what the user said
 * happened, createdAt is when the app recorded it, and the two never collapse
 * into each other — including across Room, the Outbox and sync.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransactionDateTest {

    private val ownerPhone = "9000000001"
    private val businessId = "test-business"
    private lateinit var db: com.example.khatabook.data.local.KhatabookDatabase
    private lateinit var repository: KhatabookRepository
    private val gson = Gson()

    private fun daysAgo(n: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -n)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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

    private fun newParty() =
        repository.addParty("RAMESH", "", PartyType.CUSTOMER, businessId = businessId)

    // ─── Defaults and explicit dates ────────────────────────────

    @Test
    fun `a new entry defaults its business date to now`() = runTest {
        val party = newParty()
        val before = System.currentTimeMillis()

        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE)!!

        assertTrue(entry.transactionDate >= before)
        assertTrue(entry.transactionDate <= System.currentTimeMillis())
    }

    @Test
    fun `a backdated entry keeps the chosen date`() = runTest {
        val party = newParty()
        val yesterday = daysAgo(1)

        val entry = repository.addLedgerEntry(
            party.id, 500.0, TransactionType.GAVE, transactionDate = yesterday
        )!!

        assertEquals(yesterday, entry.transactionDate)
    }

    @Test
    fun `createdAt remains the real recording time even when backdated`() = runTest {
        val party = newParty()
        val lastMonth = daysAgo(30)
        val before = System.currentTimeMillis()

        val entry = repository.addLedgerEntry(
            party.id, 500.0, TransactionType.GAVE, transactionDate = lastMonth
        )!!

        // The two must not collapse into one another.
        assertEquals(lastMonth, entry.transactionDate)
        assertTrue(entry.createdAt >= before)
        assertTrue(entry.transactionDate < entry.createdAt)
    }

    // ─── Persistence and Outbox ─────────────────────────────────

    @Test
    fun `Room persists the business date across a reload`() = runTest {
        val party = newParty()
        val yesterday = daysAgo(1)
        val entry = repository.addLedgerEntry(
            party.id, 500.0, TransactionType.GAVE, transactionDate = yesterday
        )!!

        // Straight from the database, not the in-memory StateFlow.
        val stored = db.ledgerEntryDao().getForBusinessSync(ownerPhone, businessId)
            .first { it.id == entry.id }

        assertEquals(yesterday, stored.transactionDate)
        assertTrue(stored.createdAt > stored.transactionDate)
    }

    @Test
    fun `the Outbox payload carries the chosen date`() = runTest {
        val party = newParty()
        val yesterday = daysAgo(1)
        val entry = repository.addLedgerEntry(
            party.id, 500.0, TransactionType.GAVE, transactionDate = yesterday
        )!!

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.targetId == entry.id }
        val sent = gson.fromJson(row.payload, CreateLedgerEntryRequest::class.java)

        assertNotNull(sent.transactionDate)
        // Round-trips to exactly the instant the user picked — the value is
        // never re-derived at sync time.
        assertEquals(yesterday, com.example.khatabook.utils.IsoTime.parseOrDefault(sent.transactionDate, -1L))
    }

    @Test
    fun `an ordinary entry omits the date so the request matches older builds`() = runTest {
        val party = newParty()

        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE)!!

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.targetId == entry.id }
        assertNull(gson.fromJson(row.payload, CreateLedgerEntryRequest::class.java).transactionDate)
    }

    // ─── Balances are unaffected ────────────────────────────────

    @Test
    fun `backdating does not change the balance effect`() = runTest {
        val party = newParty()

        repository.addLedgerEntry(
            party.id, 1000.0, TransactionType.GAVE, transactionDate = daysAgo(5)
        )

        assertEquals(1000.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    // ─── Date parsing and validation ────────────────────────────

    @Test
    fun `valid dates parse in every format the app accepts`() {
        assertNotNull(SheetDates.parseOrNull("2026-08-21"))
        assertNotNull(SheetDates.parseOrNull("21/08/2026"))
        assertNotNull(SheetDates.parseOrNull("21-08-2026"))
    }

    @Test
    fun `impossible dates are rejected, never rolled forward`() {
        // Lenient parsing would turn this into 3 March.
        assertNull(SheetDates.parseOrNull("31/02/2026"))
        assertNull(SheetDates.parseOrNull("2026-99-99"))
        assertNull(SheetDates.parseOrNull("not a date"))
        assertNull(SheetDates.parseOrNull(""))
        assertNull(SheetDates.parseOrNull(null))
    }

    @Test
    fun `a parsed date is normalised to the start of its day`() {
        val parsed = SheetDates.parseOrNull("21/08/2026")!!
        val cal = Calendar.getInstance().apply { timeInMillis = parsed }

        // Grouping by day must not depend on the time of entry.
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `format and parse round-trip`() {
        val today = SheetDates.today()
        assertEquals(today, SheetDates.parseOrNull(SheetDates.format(today)))
    }

    @Test
    fun `backdating is allowed but future dating is not`() {
        assertTrue(SheetDates.isNotInFuture(daysAgo(365)))
        assertTrue(SheetDates.isNotInFuture(SheetDates.today()))

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        assertTrue(!SheetDates.isNotInFuture(tomorrow))
    }

    // ─── Collections group by business date ─────────────────────

    @Test
    fun `entries land in the day they happened, not the day they were entered`() = runTest {
        val party = newParty()
        val yesterday = daysAgo(1)

        repository.addLedgerEntry(party.id, 100.0, TransactionType.GOT, transactionDate = yesterday)
        repository.addLedgerEntry(party.id, 200.0, TransactionType.GOT)

        val entries = repository.ledgerEntries.value.filter { it.partyId == party.id }
        val yesterdayTotal = entries.filter {
            it.transactionDate >= yesterday && it.transactionDate < SheetDates.today()
        }.sumOf { it.amount }
        val todayTotal = entries.filter { it.transactionDate >= SheetDates.today() }.sumOf { it.amount }

        // Both were entered today, but only one belongs to today's collection.
        assertEquals(100.0, yesterdayTotal)
        assertEquals(200.0, todayTotal)
    }
}
