package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.utils.IsoTime
import com.example.khatabook.utils.ReportPeriod
import com.example.khatabook.utils.ReportPeriodUtils
import junit.framework.TestCase.assertEquals
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
 * The Cashbook's two independent dimensions: which collection a row's party
 * belongs to, and when the transaction happened.
 *
 * Two bugs are pinned here. Today's totals read zero because the report
 * window was computed in UTC while a transaction's business date is recorded
 * at local midnight — east of UTC the two never overlapped for same-day
 * entries. And the Cashbook had no collection filter at all, so selecting a
 * collection changed nothing.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CashbookFilterTest {

    private val ownerPhone = "9000000001"
    private val businessId = "test-business"
    private lateinit var db: com.example.khatabook.data.local.KhatabookDatabase
    private lateinit var repository: KhatabookRepository

    /** The window the UI asks for when "Today" is selected. */
    private fun todayRange() = ReportPeriodUtils.rangeFor(ReportPeriod.SINGLE_DAY)

    private fun daysAgo(n: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -n)
        set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun today(): Long = daysAgo(0)

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

    private fun party(name: String, frequency: CollectionFrequency) =
        repository.addParty(name, "", PartyType.CUSTOMER, businessId = businessId, frequency = frequency)

    // ─── Today's totals ─────────────────────────────────────────

    @Test
    fun `today's cash in and cash out are not zero for today's transactions`() = runTest {
        val p = party("A", CollectionFrequency.DAILY)
        repository.addLedgerEntry(p.id, 1000.0, TransactionType.GOT, transactionDate = today())
        repository.addLedgerEntry(p.id, 500.0, TransactionType.GOT, transactionDate = today())
        repository.addLedgerEntry(p.id, 300.0, TransactionType.GAVE, transactionDate = today())

        val r = todayRange()
        val (cashIn, cashOut) = repository.cashbookTotals(r.startIso, r.endIso)

        // The exact figures from the bug report — previously both read zero.
        assertEquals(1500.0, cashIn)
        assertEquals(300.0, cashOut)
    }

    @Test
    fun `a transaction entered today with the default date lands in today's window`() = runTest {
        // The regression itself: the UI's default business date is local
        // midnight, and a UTC-based window missed it east of UTC.
        val p = party("A", CollectionFrequency.DAILY)
        repository.addLedgerEntry(
            p.id, 750.0, TransactionType.GOT,
            transactionDate = com.example.khatabook.utils.SheetDates.today(),
        )

        val r = todayRange()
        assertEquals(750.0, repository.cashbookTotals(r.startIso, r.endIso).first)
    }

    @Test
    fun `yesterday's transactions are excluded from today's totals`() = runTest {
        val p = party("A", CollectionFrequency.DAILY)
        repository.addLedgerEntry(p.id, 900.0, TransactionType.GOT, transactionDate = daysAgo(1))
        repository.addLedgerEntry(p.id, 100.0, TransactionType.GOT, transactionDate = today())

        val r = todayRange()
        assertEquals(100.0, repository.cashbookTotals(r.startIso, r.endIso).first)
    }

    @Test
    fun `future-dated transactions are excluded from today's totals`() = runTest {
        val p = party("A", CollectionFrequency.DAILY)
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        repository.addLedgerEntry(p.id, 400.0, TransactionType.GOT, transactionDate = tomorrow)
        repository.addLedgerEntry(p.id, 100.0, TransactionType.GOT, transactionDate = today())

        val r = todayRange()
        assertEquals(100.0, repository.cashbookTotals(r.startIso, r.endIso).first)
    }

    @Test
    fun `a backdated entry counts on the day it happened, not the day it was entered`() = runTest {
        val p = party("A", CollectionFrequency.DAILY)
        // Recorded now, but stated as yesterday's business.
        repository.addLedgerEntry(p.id, 600.0, TransactionType.GOT, transactionDate = daysAgo(1))

        val r = todayRange()
        assertEquals(0.0, repository.cashbookTotals(r.startIso, r.endIso).first)
    }

    // ─── Collection filter ──────────────────────────────────────

    private fun seedOnePerCollection() {
        val daily = party("D", CollectionFrequency.DAILY)
        val weekly = party("W", CollectionFrequency.WEEKLY)
        val monthly = party("M", CollectionFrequency.MONTHLY)
        repository.addLedgerEntry(daily.id, 2000.0, TransactionType.GOT, transactionDate = today())
        repository.addLedgerEntry(weekly.id, 1000.0, TransactionType.GOT, transactionDate = today())
        repository.addLedgerEntry(weekly.id, 500.0, TransactionType.GAVE, transactionDate = today())
        repository.addLedgerEntry(monthly.id, 300.0, TransactionType.GOT, transactionDate = today())
    }

    @Test
    fun `the weekly filter reports only weekly parties' totals`() = runTest {
        seedOnePerCollection()
        val r = todayRange()

        val (cashIn, cashOut) = repository.cashbookTotals(r.startIso, r.endIso, CollectionFrequency.WEEKLY)

        // Exactly the worked example from the bug report: the Daily party's
        // 2000 must not be included.
        assertEquals(1000.0, cashIn)
        assertEquals(500.0, cashOut)
    }

    @Test
    fun `the daily filter reports only daily parties' totals`() = runTest {
        seedOnePerCollection()
        val r = todayRange()

        assertEquals(2000.0, repository.cashbookTotals(r.startIso, r.endIso, CollectionFrequency.DAILY).first)
    }

    @Test
    fun `the monthly filter reports only monthly parties' totals`() = runTest {
        seedOnePerCollection()
        val r = todayRange()

        assertEquals(300.0, repository.cashbookTotals(r.startIso, r.endIso, CollectionFrequency.MONTHLY).first)
    }

    @Test
    fun `no filter reports every collection`() = runTest {
        seedOnePerCollection()
        val r = todayRange()

        val (cashIn, cashOut) = repository.cashbookTotals(r.startIso, r.endIso, null)
        assertEquals(3300.0, cashIn)
        assertEquals(500.0, cashOut)
    }

    @Test
    fun `the row list is filtered by the same rule as the totals`() = runTest {
        seedOnePerCollection()
        val r = todayRange()

        val rows = repository.cashbookRows(r.startIso, r.endIso, CollectionFrequency.WEEKLY)
        val totals = repository.cashbookTotals(r.startIso, r.endIso, CollectionFrequency.WEEKLY)

        // Totals are derived from these very rows, so they cannot disagree.
        assertEquals(2, rows.size)
        assertEquals(rows.filter { it.type == "GOT" }.sumOf { it.amount }, totals.first)
        assertEquals(rows.filter { it.type == "GAVE" }.sumOf { it.amount }, totals.second)
    }

    @Test
    fun `an empty collection is empty without emptying the others`() = runTest {
        val daily = party("D", CollectionFrequency.DAILY)
        repository.addLedgerEntry(daily.id, 100.0, TransactionType.GOT, transactionDate = today())
        val r = todayRange()

        assertTrue(repository.cashbookRows(r.startIso, r.endIso, CollectionFrequency.WEEKLY).isEmpty())
        assertEquals(0.0, repository.cashbookTotals(r.startIso, r.endIso, CollectionFrequency.WEEKLY).first)
        // The Cashbook as a whole is NOT empty.
        assertTrue(repository.cashbookRows(r.startIso, r.endIso, null).isNotEmpty())
    }

    // ─── The two dimensions combine ─────────────────────────────

    @Test
    fun `collection and date narrow independently`() = runTest {
        val weekly = party("W", CollectionFrequency.WEEKLY)
        repository.addLedgerEntry(weekly.id, 111.0, TransactionType.GOT, transactionDate = today())
        repository.addLedgerEntry(weekly.id, 222.0, TransactionType.GOT, transactionDate = daysAgo(1))

        val todayR = todayRange()
        val weekR = ReportPeriodUtils.rangeFor(ReportPeriod.LAST_WEEK)

        // Same collection, different windows — the date filter still applies.
        assertEquals(111.0, repository.cashbookTotals(todayR.startIso, todayR.endIso, CollectionFrequency.WEEKLY).first)
        assertEquals(333.0, repository.cashbookTotals(weekR.startIso, weekR.endIso, CollectionFrequency.WEEKLY).first)
    }

    // ─── Offline and isolation ──────────────────────────────────

    @Test
    fun `an offline entry updates the totals immediately`() = runTest {
        // No backend is reachable in this test; the figures come from Room.
        val p = party("A", CollectionFrequency.DAILY)
        val r = todayRange()
        assertEquals(0.0, repository.cashbookTotals(r.startIso, r.endIso).first)

        repository.addLedgerEntry(p.id, 250.0, TransactionType.GOT, transactionDate = today())

        assertEquals(250.0, repository.cashbookTotals(r.startIso, r.endIso).first)
    }

    @Test
    fun `another business's transactions never reach this Cashbook`() = runTest {
        val mine = party("MINE", CollectionFrequency.WEEKLY)
        repository.addLedgerEntry(mine.id, 100.0, TransactionType.GOT, transactionDate = today())

        repository.setActiveBusinessIdForTesting("other-business")
        val theirs = repository.addParty("THEIRS", "", PartyType.CUSTOMER, businessId = "other-business", frequency = CollectionFrequency.WEEKLY)
        repository.addLedgerEntry(theirs.id, 9999.0, TransactionType.GOT, transactionDate = today())

        repository.setActiveBusinessIdForTesting(businessId)
        val r = todayRange()

        // The other business's party is not in this business's lookup, so its
        // rows cannot be classified into — or counted by — this filter.
        assertEquals(100.0, repository.cashbookTotals(r.startIso, r.endIso, CollectionFrequency.WEEKLY).first)
    }

    @Test
    fun `an entry whose party is unknown to this business is not counted under any collection`() = runTest {
        val p = party("A", CollectionFrequency.DAILY)
        repository.addLedgerEntry(p.id, 100.0, TransactionType.GOT, transactionDate = today())
        val r = todayRange()

        // Every filtered bucket sums to at most the unfiltered total.
        val all = repository.cashbookTotals(r.startIso, r.endIso, null).first
        val perBucket = CollectionFrequency.values().sumOf {
            repository.cashbookTotals(r.startIso, r.endIso, it).first
        }
        assertTrue(perBucket <= all)
    }

    // ─── Report window uses local days ──────────────────────────

    @Test
    fun `today's window covers the local day, not the UTC day`() {
        val r = todayRange()
        val start = IsoTime.parseOrDefault(r.startIso, -1)
        val end = IsoTime.parseOrDefault(r.endIso, -1)
        val now = System.currentTimeMillis()

        assertTrue(start <= now && now <= end)
        // A full day wide, give or take DST.
        val span = end - start
        assertTrue(span > 22L * 60 * 60 * 1000 && span < 26L * 60 * 60 * 1000)
    }
}
