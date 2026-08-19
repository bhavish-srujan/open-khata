package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.InventoryItemResponse
import com.example.khatabook.data.api.KhatabookApiService
import com.example.khatabook.data.api.LedgerEntryResponse
import com.example.khatabook.data.api.PartyResponse
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.local.KhatabookDatabase
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response

/**
 * The repository-owned caches added for Cashbook/Bills/Items/Staff/Reports
 * (see [KhatabookRepository.syncItems] and its siblings), plus the two
 * pre-existing gaps closed alongside them:
 *
 * 1. A background GET racing a still-queued UPDATE_ENTRY/DELETE_ENTRY/
 *    DELETE_PARTY must not overwrite the optimistic local state with the
 *    server's still-old view — see [KhatabookRepository.syncPartiesFromServer]
 *    and [KhatabookRepository.syncLedgerEntriesFromServer].
 * 2. A successfully-delivered CREATE_ENTRY/UPDATE_ENTRY must reconcile local
 *    state from the server's confirmed response rather than trusting the
 *    optimistic client-side arithmetic forever — see
 *    [KhatabookRepository.reconcileEntryFromServer], invoked from
 *    [KhatabookRepository.flushOutbox]'s SENT branch.
 *
 * Uses the same mock-SharedPreferences + NetworkClient.setApiServiceForTesting
 * pattern as OutboxRollbackTest.
 */
@RunWith(RobolectricTestRunner::class)
// See OutboxRollbackTest's identical annotation: background flushes touch
// Room from a different thread than the test itself, and Robolectric's
// legacy SQLite shadow is not safe for that.
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CacheReconciliationTest {

    private lateinit var repository: KhatabookRepository
    // Held directly (not just wired via databaseFactory) so a test can
    // assert on what actually landed in Room for a business that isn't the
    // active one — see the cross-business reconciliation test below.
    private lateinit var db: KhatabookDatabase

    @Before
    fun setup() {
        // Secondary prefs the repository reads directly via the context (e.g.
        // SMS settings) — unrelated to the main persistence store, which is
        // Room (see [db] below).
        val mockEditor = mock<SharedPreferences.Editor> {
            on { putString(any(), any()) } doReturn it
        }
        val mockPrefs = mock<SharedPreferences> {
            on { getString(any(), any()) } doReturn null
            on { edit() } doReturn mockEditor
        }
        val mockContext = mock<Context> {
            on { getSharedPreferences(any(), any()) } doReturn mockPrefs
        }
        // Real, deterministic in-memory database, so cache hit/miss and
        // reconciliation logic runs against actual saved state instead of a
        // Mockito stub that discards everything written to it.
        db = buildInMemoryTestDatabase()
        repository = KhatabookRepository(mockContext, prefsFactory = { FakeSharedPreferences() }, databaseFactory = { db })
        // A non-blank business id is required for a queued op to ever reach
        // send() at all — see setActiveBusinessIdForTesting's doc comment.
        repository.switchUser("9000000003")
        repository.setActiveBusinessIdForTesting("test-business")
    }

    // ── cache hit / miss / failed-refresh (items, representative of the
    //    identically-shaped ledger-feed/staff/bills caches) ────────────────

    @Test
    fun `cache miss calls the network and populates the cache`() = runTest {
        val item = InventoryItemResponse(
            id = "item-1", name = "Rice 5kg", sku = null,
            sellingPrice = 480.0, purchasePrice = 410.0, currentStock = 10.0, lowStockThreshold = 2.0
        )
        val fakeApi = mock<KhatabookApiService> {
            onBlocking { getInventoryItems() } doReturn Response.success(listOf(item))
        }
        NetworkClient.setApiServiceForTesting(fakeApi)

        assertTrue("cache should start empty", repository.items.value.isEmpty())
        assertTrue(repository.syncItems())
        assertEquals(1, repository.items.value.size)
        assertEquals("Rice 5kg", repository.items.value.first().name)
    }

    @Test
    fun `a cache hit does not require the network to succeed`() = runTest {
        val item = InventoryItemResponse(
            id = "item-1", name = "Rice 5kg", sku = null,
            sellingPrice = 480.0, purchasePrice = 410.0, currentStock = 10.0, lowStockThreshold = 2.0
        )
        val okApi = mock<KhatabookApiService> {
            onBlocking { getInventoryItems() } doReturn Response.success(listOf(item))
        }
        NetworkClient.setApiServiceForTesting(okApi)
        assertTrue(repository.syncItems())

        // The cache is now populated. A second, unforced call must return
        // true from the cache alone — it must not matter that the network
        // would fail if it were actually awaited.
        val brokenApi = mock<KhatabookApiService> {
            onBlocking { getInventoryItems() } doReturn Response.error(
                500, okhttp3.ResponseBody.create(null, "{}")
            )
        }
        NetworkClient.setApiServiceForTesting(brokenApi)
        assertTrue("a cache hit must not depend on the network", repository.syncItems())
        assertEquals(1, repository.items.value.size)
    }

    @Test
    fun `a failed forced refresh preserves the existing cache rather than clearing it`() = runTest {
        val item = InventoryItemResponse(
            id = "item-1", name = "Rice 5kg", sku = null,
            sellingPrice = 480.0, purchasePrice = 410.0, currentStock = 10.0, lowStockThreshold = 2.0
        )
        val okApi = mock<KhatabookApiService> {
            onBlocking { getInventoryItems() } doReturn Response.success(listOf(item))
        }
        NetworkClient.setApiServiceForTesting(okApi)
        repository.syncItems()
        assertEquals(1, repository.items.value.size)

        val brokenApi = mock<KhatabookApiService> {
            onBlocking { getInventoryItems() } doReturn Response.error(
                500, okhttp3.ResponseBody.create(null, "{}")
            )
        }
        NetworkClient.setApiServiceForTesting(brokenApi)
        val ok = repository.syncItems(force = true)

        assertFalse("a failed refresh must report failure", ok)
        assertEquals("existing cache must survive a failed refresh", 1, repository.items.value.size)
    }

    // ── gap 1: a pending mutation must survive a racing background GET ─────

    @Test
    fun `a pending UPDATE_ENTRY survives a ledger sync that still reflects the pre-update server value`() = runTest {
        val party = repository.addParty("Vikram Traders", "+919876500030", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "initial")!!

        // Edit is made while the create/update queue for this business is
        // otherwise stalled — the edit itself stays PENDING, never delivered.
        repository.updateLedgerEntry(entry.id, 900.0, "corrected")
        assertEquals(900.0, repository.ledgerEntries.value.first { it.id == entry.id }.amount, 0.001)

        // A GET races ahead of that queued update and returns what the
        // server still actually has: the pre-edit row.
        val staleRow = LedgerEntryResponse(
            id = entry.id, partyId = party.id, amount = 500.0, type = "GAVE", note = "initial",
            paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
        )
        val racingApi = mock<KhatabookApiService> {
            onBlocking { getLedgerEntries(eq(party.id)) } doReturn Response.success(listOf(staleRow))
        }
        NetworkClient.setApiServiceForTesting(racingApi)

        repository.syncLedgerEntriesFromServer(party.id)

        assertEquals(
            "the optimistic edit must survive a GET that raced ahead of its delivery",
            900.0,
            repository.ledgerEntries.value.first { it.id == entry.id }.amount,
            0.001,
        )
    }

    @Test
    fun `a pending DELETE_ENTRY is not resurrected by a racing ledger sync`() = runTest {
        val party = repository.addParty("Priya Traders", "+919876500031", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!

        repository.deleteLedgerEntry(entry.id)
        assertTrue(repository.ledgerEntries.value.none { it.id == entry.id })

        // The server, from this racing GET's point of view, has not
        // processed the delete yet.
        val stillPresent = LedgerEntryResponse(
            id = entry.id, partyId = party.id, amount = 500.0, type = "GAVE", note = "loan",
            paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
        )
        val racingApi = mock<KhatabookApiService> {
            onBlocking { getLedgerEntries(eq(party.id)) } doReturn Response.success(listOf(stillPresent))
        }
        NetworkClient.setApiServiceForTesting(racingApi)

        repository.syncLedgerEntriesFromServer(party.id)

        assertTrue(
            "a locally-deleted entry must not be resurrected by a racing GET",
            repository.ledgerEntries.value.none { it.id == entry.id },
        )
    }

    @Test
    fun `a pending DELETE_PARTY is not resurrected by a racing parties sync`() = runTest {
        val party = repository.addParty("Amit Traders", "+919876500032", PartyType.CUSTOMER)

        repository.deleteParty(party.id)
        assertTrue(repository.parties.value.none { it.id == party.id })

        val stillPresent = PartyResponse(
            id = party.id, name = party.name, phone = party.phone, type = "CUSTOMER",
            gstin = null, currentBalance = 0.0, businessId = "", createdAt = "2026-01-01T00:00:00.000Z"
        )
        val racingApi = mock<KhatabookApiService> {
            onBlocking { getParties(anyOrNull()) } doReturn Response.success(listOf(stillPresent))
        }
        NetworkClient.setApiServiceForTesting(racingApi)

        repository.syncPartiesFromServer()

        assertTrue(
            "a locally-deleted party must not be resurrected by a racing GET",
            repository.parties.value.none { it.id == party.id },
        )
    }

    // ── gap 2: a successful flush must reconcile from the server response ──

    @Test
    fun `a successful UPDATE_ENTRY flush reconciles the entry and balance from the server's confirmed amount`() = runTest {
        val party = repository.addParty("Karan Traders", "+919876500033", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!

        repository.updateLedgerEntry(entry.id, 900.0, "corrected")
        assertEquals(900.0, repository.parties.value.first { it.id == party.id }.currentBalance, 0.001)

        // The server confirms a different figure than what was requested
        // (validation/rounding, or simply a stand-in for float drift).
        val serverConfirmed = LedgerEntryResponse(
            id = entry.id, partyId = party.id, amount = 850.0, type = "GAVE", note = "corrected",
            paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
        )
        // The business's queue is CREATE_PARTY, then CREATE_ENTRY, then
        // UPDATE_ENTRY — flushOutbox() only advances one business's queue
        // head at a time, so both earlier creates need a real response too,
        // or the queue never reaches the UPDATE_ENTRY this test is about.
        val flushApi = mock<KhatabookApiService> {
            onBlocking { createParty(any(), any()) } doReturn Response.success(
                PartyResponse(
                    id = party.id, name = party.name, phone = party.phone, type = "CUSTOMER",
                    gstin = null, currentBalance = 0.0, businessId = "test-business", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { createLedgerEntry(any(), any()) } doReturn Response.success(
                LedgerEntryResponse(
                    id = entry.id, partyId = party.id, amount = 500.0, type = "GAVE", note = "loan",
                    paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { updateLedgerEntry(eq(entry.id), any(), any()) } doReturn Response.success(serverConfirmed)
        }
        NetworkClient.setApiServiceForTesting(flushApi)

        assertTrue(repository.flushOutbox())

        assertEquals(
            "the entry must show the server-confirmed amount, not the requested one",
            850.0,
            repository.ledgerEntries.value.first { it.id == entry.id }.amount,
            0.001,
        )
        assertEquals(
            "the balance must be corrected to match the server-confirmed amount",
            850.0,
            repository.parties.value.first { it.id == party.id }.currentBalance,
            0.001,
        )
    }

    @Test
    fun `a successful flush whose server amount matches the request leaves the balance unchanged`() = runTest {
        val party = repository.addParty("Sunita Traders", "+919876500034", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!
        repository.updateLedgerEntry(entry.id, 900.0, "corrected")

        // The ordinary case: the server simply confirms what was sent.
        val serverConfirmed = LedgerEntryResponse(
            id = entry.id, partyId = party.id, amount = 900.0, type = "GAVE", note = "corrected",
            paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
        )
        val flushApi = mock<KhatabookApiService> {
            onBlocking { createParty(any(), any()) } doReturn Response.success(
                PartyResponse(
                    id = party.id, name = party.name, phone = party.phone, type = "CUSTOMER",
                    gstin = null, currentBalance = 0.0, businessId = "test-business", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { createLedgerEntry(any(), any()) } doReturn Response.success(
                LedgerEntryResponse(
                    id = entry.id, partyId = party.id, amount = 500.0, type = "GAVE", note = "loan",
                    paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { updateLedgerEntry(eq(entry.id), any(), any()) } doReturn Response.success(serverConfirmed)
        }
        NetworkClient.setApiServiceForTesting(flushApi)

        assertTrue(repository.flushOutbox())

        assertEquals(900.0, repository.ledgerEntries.value.first { it.id == entry.id }.amount, 0.001)
        assertEquals(900.0, repository.parties.value.first { it.id == party.id }.currentBalance, 0.001)
    }

    // ── reconciliation for an op whose business isn't the one active now ──

    @Test
    fun `a flush that finally delivers a queued write for a non-active business reconciles that business's on-disk cache, not the active one`() = runTest {
        val party = repository.addParty("Deepa Traders", "+919876500035", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!
        repository.updateLedgerEntry(entry.id, 900.0, "corrected")

        // Each add*/update* above also fires its own fire-and-forget
        // background flush via enqueue() (a real, non-test dispatcher) — left
        // running, it could race the controlled flush below and reconcile
        // against an unrelated/unmocked response at an unpredictable moment.
        // This test drives flushOutbox() explicitly and deterministically, so
        // that background copy is cancelled first.
        repository.cancelBackgroundWorkForTesting()

        // The user has since moved on to a different business — flushOutbox()
        // still owes "test-business" a delivery for its queue head, and must
        // reconcile *that* business's cache, not whatever's active now.
        repository.setActiveBusinessIdForTesting("other-business")

        val serverConfirmed = LedgerEntryResponse(
            id = entry.id, partyId = party.id, amount = 850.0, type = "GAVE", note = "corrected",
            paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
        )
        val flushApi = mock<KhatabookApiService> {
            onBlocking { createParty(any(), any()) } doReturn Response.success(
                PartyResponse(
                    id = party.id, name = party.name, phone = party.phone, type = "CUSTOMER",
                    gstin = null, currentBalance = 0.0, businessId = "test-business", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { createLedgerEntry(any(), any()) } doReturn Response.success(
                LedgerEntryResponse(
                    id = entry.id, partyId = party.id, amount = 500.0, type = "GAVE", note = "loan",
                    paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { updateLedgerEntry(eq(entry.id), any(), any()) } doReturn Response.success(serverConfirmed)
        }
        NetworkClient.setApiServiceForTesting(flushApi)

        assertTrue(repository.flushOutbox())

        // setActiveBusinessIdForTesting only flips the field — unlike a real
        // switchBusiness() it does not clear the in-memory StateFlows — so
        // they still hold "test-business"'s rows here. The point of this
        // assertion is that reconciliation must NOT have touched them: the
        // pre-reconciliation, optimistic 900.0 must still be exactly what's
        // in memory, since flushOutbox() correctly recognised this op no
        // longer belongs to the (now different) active business and left
        // memory alone, reconciling only the on-disk cache checked below.
        assertEquals(
            "in-memory state must be untouched by a reconciliation for a non-active business",
            900.0,
            repository.parties.value.first { it.id == party.id }.currentBalance,
            0.001,
        )
        assertEquals(
            900.0,
            repository.ledgerEntries.value.first { it.id == entry.id }.amount,
            0.001,
        )

        // "test-business"'s persisted row must reflect the server-confirmed
        // amount, even though it was never the active business during the
        // flush — queried straight from Room rather than the active
        // business's StateFlows, which (as asserted above) must NOT reflect
        // it.
        val storedParty = db.partyDao().getByIdSync("9000000003", "test-business", party.id)
        val storedEntry = db.ledgerEntryDao().getByIdSync(entry.id)

        assertEquals(
            "test-business's persisted balance must be corrected to the server-confirmed amount",
            850.0,
            storedParty!!.currentBalance,
            0.001,
        )
        assertEquals(
            "test-business's persisted entry must show the server-confirmed amount",
            850.0,
            storedEntry!!.amount,
            0.001,
        )
    }
}
