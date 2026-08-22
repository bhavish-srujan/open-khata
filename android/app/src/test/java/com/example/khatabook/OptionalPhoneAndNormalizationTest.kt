package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.CreatePartyRequest
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.utils.IsoTime
import com.google.gson.Gson
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
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
 * A party is identified by name; a phone number is optional contact detail.
 * Also covers the canonical-name form and the transaction-SMS default.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OptionalPhoneAndNormalizationTest {

    private val ownerPhone = "9000000001"
    private val businessId = "test-business"
    private lateinit var db: com.example.khatabook.data.local.KhatabookDatabase
    private lateinit var repository: KhatabookRepository
    private val gson = Gson()

    @Before
    fun setup() {
        db = buildInMemoryTestDatabase()
        val editor = mock<SharedPreferences.Editor> {
            on { putString(any(), any()) } doReturn it
            on { putBoolean(any(), any()) } doReturn it
        }
        // No stored SMS preference: getBoolean must return the supplied default.
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

    // ─── Optional phone ─────────────────────────────────────────

    @Test
    fun `a customer can be created with a name and no phone number`() = runTest {
        val party = repository.addParty("RAMESH", "", PartyType.CUSTOMER, businessId = businessId)

        assertEquals("", party.phone)
        assertTrue(repository.parties.value.any { it.id == party.id })
    }

    @Test
    fun `a party without a phone stores blank, never a fabricated placeholder`() = runTest {
        val party = repository.addParty("RAMESH", "   ", PartyType.CUSTOMER, businessId = businessId)

        assertEquals("", party.phone)
        assertFalse(party.phone.contains("0000000000"))
    }

    @Test
    fun `the queued create for a phoneless party carries a blank phone`() = runTest {
        val party = repository.addParty("RAMESH", "", PartyType.CUSTOMER, businessId = businessId)

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.targetId == party.id }
        val sent = gson.fromJson(row.payload, CreatePartyRequest::class.java)
        assertEquals("", sent.phone)
    }

    @Test
    fun `ledger entries and balances work normally for a party with no phone`() = runTest {
        val party = repository.addParty("RAMESH", "", PartyType.CUSTOMER, businessId = businessId)

        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE)

        assertNotNull(entry)
        assertEquals(500.0, repository.parties.value.first { it.id == party.id }.currentBalance)
    }

    @Test
    fun `an existing customer's phone number is preserved exactly`() = runTest {
        val party = repository.addParty("SUNITA", "+91 98765 43210", PartyType.CUSTOMER, businessId = businessId)

        assertEquals("+91 98765 43210", party.phone)
    }

    // ─── Canonical names ────────────────────────────────────────

    @Test
    fun `names are stored uppercase regardless of how they were typed`() = runTest {
        val a = repository.addParty("bhavish", "", PartyType.CUSTOMER, businessId = businessId)
        val b = repository.addParty("Ravi Kumar", "", PartyType.CUSTOMER, businessId = businessId)

        assertEquals("BHAVISH", a.name)
        assertEquals("RAVI KUMAR", b.name)
    }

    @Test
    fun `the canonical name is what gets queued for the server`() = runTest {
        val party = repository.addParty("bhavish", "", PartyType.CUSTOMER, businessId = businessId)

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.targetId == party.id }
        assertEquals("BHAVISH", gson.fromJson(row.payload, CreatePartyRequest::class.java).name)
    }

    @Test
    fun `creating a party does not change its id when the name is normalised`() = runTest {
        val party = repository.addParty("bhavish", "", PartyType.CUSTOMER, businessId = businessId)

        // The id is minted once and never re-derived from the name.
        assertEquals(party.id, repository.parties.value.first { it.name == "BHAVISH" }.id)
    }

    // ─── Restore preserves real transaction dates ───────────────

    @Test
    fun `server timestamps are parsed rather than replaced with the restore time`() {
        val serverIso = "2026-03-15T08:30:00.000Z"
        val parsed = IsoTime.parseOrDefault(serverIso, 999L)

        // Round-trips to the same instant, and is emphatically not "now".
        assertEquals(serverIso, IsoTime.format(parsed))
        assertTrue(parsed < System.currentTimeMillis())
    }

    @Test
    fun `timestamps without milliseconds and with offsets are still understood`() {
        val fallback = 42L
        assertTrue(IsoTime.parseOrDefault("2026-03-15T08:30:00Z", fallback) != fallback)
        assertTrue(IsoTime.parseOrDefault("2026-03-15T14:00:00+05:30", fallback) != fallback)
    }

    @Test
    fun `an unusable timestamp falls back instead of aborting a restore`() {
        assertEquals(7L, IsoTime.parseOrDefault("not-a-date", 7L))
        assertEquals(7L, IsoTime.parseOrDefault("", 7L))
        assertEquals(7L, IsoTime.parseOrDefault(null, 7L))
    }

    @Test
    fun `entries restored from different months keep distinct dates`() {
        // The bug this pins: every restored row used to collapse onto "now",
        // so daily/weekly/monthly reports all saw a single day.
        val jan = IsoTime.parseOrDefault("2026-01-10T10:00:00.000Z", 0L)
        val mar = IsoTime.parseOrDefault("2026-03-10T10:00:00.000Z", 0L)

        assertTrue(jan < mar)
        // ~59 days apart, so they land in different days, weeks and months.
        assertTrue((mar - jan) > 50L * 24 * 60 * 60 * 1000)
    }

    // ─── Transaction SMS default ────────────────────────────────

    @Test
    fun `transaction SMS is off for an account that never configured it`() = runTest {
        val party = repository.addParty("RAMESH", "9876543210", PartyType.CUSTOMER, businessId = businessId)

        repository.addLedgerEntry(party.id, 100.0, TransactionType.GAVE)

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.type == "CREATE_ENTRY" }
        val sent = gson.fromJson(row.payload, com.example.khatabook.data.api.CreateLedgerEntryRequest::class.java)
        assertEquals(false, sent.notifyCustomer)
    }
}
