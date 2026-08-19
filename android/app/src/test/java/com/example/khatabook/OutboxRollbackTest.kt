package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.KhatabookApiService
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.api.PartyResponse
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.data.repository.MutationSnapshot
import com.example.khatabook.data.repository.OpStatus
import com.example.khatabook.data.repository.PendingOp
import com.example.khatabook.data.repository.PendingOpType
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response

/**
 * A 403 from the server (a role change since the write was queued, most
 * commonly) must not leave the device permanently disagreeing with the
 * server about whether a party or entry still exists.
 *
 * Before this, deleteParty/deleteLedgerEntry/updateLedgerEntry applied their
 * change to local state unconditionally and the queued write's eventual
 * rejection was never reflected back — the row stayed deleted (or edited) on
 * this device forever, correct nowhere else. This suite pins that the
 * pre-mutation snapshot is captured accurately and that replaying it via
 * [KhatabookRepository.rollbackMutation] restores exactly what the server
 * still has.
 */
@RunWith(RobolectricTestRunner::class)
// The real Outbox flush path runs Room queries from a background
// (Dispatchers.IO) coroutine concurrently with the test's own thread —
// exactly like production. Robolectric's legacy SQLite shadow is not safe
// for that; NATIVE mode uses the real, thread-safe SQLite engine, matching
// how an actual device behaves.
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OutboxRollbackTest {

    private lateinit var repository: KhatabookRepository
    private val gson = Gson()

    @Before
    fun setup() {
        // Secondary prefs the repository reads directly via the context (e.g.
        // SMS settings) — unrelated to the main persistence store, which is
        // now injected below via FakeSharedPreferences instead of going
        // through the context at all.
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
        // Real, deterministic in-memory storage, so rollback/snapshot logic
        // runs against actual saved state instead of a Mockito stub that
        // discards everything written to it.
        repository = KhatabookRepository(
            mockContext,
            prefsFactory = { FakeSharedPreferences() },
            databaseFactory = { buildInMemoryTestDatabase() },
        )
        // A non-blank business id is required for a queued op to ever reach
        // send() at all — see setActiveBusinessIdForTesting's doc comment.
        repository.switchUser("9000000002")
        repository.setActiveBusinessIdForTesting("test-business")
    }

    private fun forbidden(): Response<Unit> =
        Response.error(403, "{}".toResponseBody("application/json".toMediaType()))

    // ── snapshot capture ───────────────────────────────────────────────
    @Test
    fun `deleting a party captures its pre-delete state for rollback`() = runTest {
        val party = repository.addParty("Rahul Traders", "+919876500001", PartyType.CUSTOMER)
        repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "opening")

        repository.deleteParty(party.id)

        val op = repository.peekOutbox().first { it.type == PendingOpType.DELETE_PARTY }
        val snapshot = gson.fromJson(op.rollbackPayload, MutationSnapshot::class.java)
        assertEquals(party.id, snapshot.party?.id)
        assertEquals(1, snapshot.relatedEntries.size)
    }

    @Test
    fun `deleting an entry captures the entry and the party's balance before reversal`() = runTest {
        val party = repository.addParty("Priya Traders", "+919876500002", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!

        repository.deleteLedgerEntry(entry.id)

        val op = repository.peekOutbox().first { it.type == PendingOpType.DELETE_ENTRY }
        val snapshot = gson.fromJson(op.rollbackPayload, MutationSnapshot::class.java)
        assertEquals(entry.id, snapshot.entry?.id)
        assertEquals(500.0, snapshot.party?.currentBalance ?: 0.0, 0.001)
    }

    @Test
    fun `editing an entry captures its old amount and the party's old balance`() = runTest {
        val party = repository.addParty("Vikram Traders", "+919876500003", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!

        repository.updateLedgerEntry(entry.id, 900.0, "corrected")

        val op = repository.peekOutbox().first { it.type == PendingOpType.UPDATE_ENTRY }
        val snapshot = gson.fromJson(op.rollbackPayload, MutationSnapshot::class.java)
        assertEquals(500.0, snapshot.entry?.amount ?: 0.0, 0.001)
        assertEquals(500.0, snapshot.party?.currentBalance ?: 0.0, 0.001)
        // The optimistic mutation itself still applied immediately.
        assertEquals(900.0, repository.parties.value.first { it.id == party.id }.currentBalance, 0.001)
    }

    // ── replaying the snapshot ─────────────────────────────────────────
    @Test
    fun `rollback restores a party and its entries after a rejected delete`() = runTest {
        val party = repository.addParty("Amit Traders", "+919876500004", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!
        repository.deleteParty(party.id)

        assertTrue(repository.parties.value.none { it.id == party.id })

        val op = repository.peekOutbox().first { it.type == PendingOpType.DELETE_PARTY }
        repository.rollbackMutation(op)

        assertTrue(repository.parties.value.any { it.id == party.id })
        assertTrue(repository.ledgerEntries.value.any { it.id == entry.id })
    }

    @Test
    fun `rollback restores a deleted entry and reverses the balance change back`() = runTest {
        val party = repository.addParty("Sunita Traders", "+919876500005", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!
        repository.deleteLedgerEntry(entry.id)
        assertEquals(0.0, repository.parties.value.first { it.id == party.id }.currentBalance, 0.001)

        val op = repository.peekOutbox().first { it.type == PendingOpType.DELETE_ENTRY }
        repository.rollbackMutation(op)

        assertTrue(repository.ledgerEntries.value.any { it.id == entry.id })
        assertEquals(500.0, repository.parties.value.first { it.id == party.id }.currentBalance, 0.001)
    }

    @Test
    fun `rollback restores an edited entry's original amount and balance`() = runTest {
        val party = repository.addParty("Karan Traders", "+919876500006", PartyType.CUSTOMER)
        val entry = repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "loan")!!
        repository.updateLedgerEntry(entry.id, 900.0, "typo fix")

        val op = repository.peekOutbox().first { it.type == PendingOpType.UPDATE_ENTRY }
        repository.rollbackMutation(op)

        assertEquals(500.0, repository.ledgerEntries.value.first { it.id == entry.id }.amount, 0.001)
        assertEquals(500.0, repository.parties.value.first { it.id == party.id }.currentBalance, 0.001)
    }

    @Test
    fun `rollback is a no-op for operation types with nothing to undo`() = runTest {
        val op = PendingOp(type = PendingOpType.CREATE_PARTY, targetId = "x", businessId = "biz-a")
        // Must not throw despite there being no rollbackPayload.
        repository.rollbackMutation(op)
    }

    // ── end-to-end through flushOutbox: a real 403 triggers the rollback ──
    @Test
    fun `a 403 from the server rolls back the optimistic delete and marks the op failed, not lost`() = runTest {
        val party = repository.addParty("Deepak Traders", "+919876500007", PartyType.CUSTOMER)
        // The business's queue is CREATE_PARTY, then DELETE_PARTY —
        // flushOutbox() only advances one business's queue head at a time,
        // so the create needs a real response too, or the queue never
        // reaches the delete this test is about.
        val fakeApi = mock<KhatabookApiService> {
            onBlocking { createParty(any(), any()) } doReturn Response.success(
                PartyResponse(
                    id = party.id, name = party.name, phone = party.phone, type = "CUSTOMER",
                    gstin = null, currentBalance = 0.0, businessId = "test-business", createdAt = "2026-01-01T00:00:00.000Z"
                )
            )
            onBlocking { deleteParty(any(), any()) } doReturn forbidden()
        }
        NetworkClient.setApiServiceForTesting(fakeApi)

        repository.deleteParty(party.id)
        assertTrue(repository.parties.value.none { it.id == party.id })

        repository.flushOutbox()

        // The server refused it — the party must reappear, exactly as the
        // server still has it — and the failure must be visible, not silent.
        assertTrue("party was not restored after a 403", repository.parties.value.any { it.id == party.id })
        assertEquals(1, repository.failedOperationCountFlow.first())
        val op = repository.peekOutbox().first { it.type == PendingOpType.DELETE_PARTY }
        assertEquals(OpStatus.FAILED, op.status)
    }
}
