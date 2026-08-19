package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.CreateLedgerEntryRequest
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * Regression coverage for the "transaction SMS sent when the toggle is OFF"
 * bug. Root cause: LedgerService.create() on the backend sent its SMS
 * unconditionally — the client's local "Send transaction SMS" toggle never
 * reached it. The fix stamps the toggle's value into the queued
 * CreateLedgerEntryRequest as `notifyCustomer`, which is what the backend
 * now gates on. These tests verify the client half of that fix: what
 * actually gets queued for the server, and that everything else about
 * adding a transaction is unaffected.
 *
 * RobolectricTestRunner + NATIVE SQLite: same reasons as KhatabookRepositoryTest.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransactionSmsToggleTest {

    private val ownerPhone = "9000000001"
    private val businessId = "test-business"
    private lateinit var db: com.example.khatabook.data.local.KhatabookDatabase
    private val gson = Gson()

    /** Tracked so [tearDown] can stop the background flush coroutines this
     *  class starts; see that method for why it matters. */
    private var built: KhatabookRepository? = null

    /** Builds a repository whose "sms_settings" prefs report the given
     *  toggle value — everything else about the mock context is inert. */
    private fun buildRepository(sendTransactionSms: Boolean): KhatabookRepository {
        db = buildInMemoryTestDatabase()

        val mockEditor = mock<SharedPreferences.Editor> {
            on { putString(any(), any()) } doReturn it
            on { putBoolean(any(), any()) } doReturn it
        }
        // addLedgerEntry reads the toggle keyed by activeBusiness.value?.id, not
        // activeBusinessId — setActiveBusinessIdForTesting (the established
        // test-only shortcut used across this suite) only sets the latter, so
        // activeBusiness.value stays null here and the key's business-id
        // suffix is blank. This matches what this test harness actually
        // produces; enqueue()'s own businessId stamping uses activeBusinessId
        // instead and is unaffected.
        val mockSmsPrefs = mock<SharedPreferences> {
            on { getString(any(), anyOrNull()) } doReturn null
            on { getBoolean(eq("send_transaction_sms_"), any()) } doReturn sendTransactionSms
            on { edit() } doReturn mockEditor
        }
        val mockContext = mock<Context> {
            on { getSharedPreferences(any(), any()) } doReturn mockSmsPrefs
        }

        val repository = KhatabookRepository(
            mockContext,
            prefsFactory = { FakeSharedPreferences() },
            databaseFactory = { db },
        )
        repository.switchUser(ownerPhone)
        repository.setActiveBusinessIdForTesting(businessId)
        built = repository
        return repository
    }

    /**
     * Every addParty/addLedgerEntry call here fires a fire-and-forget
     * background flush on a real dispatcher. Left running past this class,
     * those coroutines race whichever test runs next against the
     * process-wide NetworkClient singleton — which is exactly how an
     * unrelated, previously-green test (OutboxRollbackTest) started timing
     * out when this class was added.
     */
    @After
    fun tearDown() = runBlocking {
        built?.cancelBackgroundWorkForTesting()
        built = null
    }

    private fun queuedNotifyCustomer(): Boolean? {
        val rows = db.outboxDao().getAllSync(ownerPhone)
        val createEntryRow = rows.last { it.type == "CREATE_ENTRY" }
        val request = gson.fromJson(createEntryRow.payload, CreateLedgerEntryRequest::class.java)
        return request.notifyCustomer
    }

    @Test
    fun `toggle ON queues a create request with notifyCustomer true`() = runTest {
        val repository = buildRepository(sendTransactionSms = true)
        val party = repository.addParty("Customer A", "+91 90000 00001", PartyType.CUSTOMER, businessId = businessId)

        repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE)

        assertEquals(true, queuedNotifyCustomer())
    }

    @Test
    fun `toggle OFF queues a create request with notifyCustomer false`() = runTest {
        val repository = buildRepository(sendTransactionSms = false)
        val party = repository.addParty("Customer B", "+91 90000 00002", PartyType.CUSTOMER, businessId = businessId)

        repository.addLedgerEntry(party.id, 500.0, TransactionType.GAVE)

        assertEquals(false, queuedNotifyCustomer())
    }

    @Test
    fun `toggle OFF still creates the transaction and updates the balance`() = runTest {
        val repository = buildRepository(sendTransactionSms = false)
        val party = repository.addParty("Customer C", "+91 90000 00003", PartyType.CUSTOMER, businessId = businessId)

        val entry = repository.addLedgerEntry(party.id, 750.0, TransactionType.GAVE)

        assertNotNull(entry)
        val updatedParty = repository.parties.value.find { it.id == party.id }!!
        assertEquals(750.0, updatedParty.currentBalance)
    }

    @Test
    fun `toggle OFF still queues the create for outbox sync`() = runTest {
        val repository = buildRepository(sendTransactionSms = false)
        val party = repository.addParty("Customer D", "+91 90000 00004", PartyType.CUSTOMER, businessId = businessId)

        repository.addLedgerEntry(party.id, 250.0, TransactionType.GAVE)

        // The transaction itself must still reach the server — only the SMS
        // side effect is suppressed, not the sync.
        val rows = db.outboxDao().getAllSync(ownerPhone)
        assertTrue(rows.any { it.type == "CREATE_ENTRY" })
        assertTrue(repository.totalQueuedOperationCount() > 0)
    }

    @Test
    fun `works with no network reachable, same as any offline transaction`() = runTest {
        // No real backend is running in this test environment — addLedgerEntry
        // must still succeed locally and leave the write durably queued,
        // regardless of the toggle.
        val repository = buildRepository(sendTransactionSms = false)
        val party = repository.addParty("Customer E", "+91 90000 00005", PartyType.CUSTOMER, businessId = businessId)

        val entry = repository.addLedgerEntry(party.id, 100.0, TransactionType.GAVE)

        assertNotNull(entry)
        assertFalse(db.outboxDao().getAllSync(ownerPhone).isEmpty())
    }
}
