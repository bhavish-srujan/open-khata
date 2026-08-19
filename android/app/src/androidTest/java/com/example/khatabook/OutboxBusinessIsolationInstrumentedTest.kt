package com.example.khatabook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.khatabook.data.api.AddStaffRequest
import com.example.khatabook.data.api.CreateBusinessRequest
import com.example.khatabook.data.api.CreatePartyRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The offline outbox's business-isolation and no-data-loss guarantees,
 * against a real backend.
 *
 * The outbox previously derived which business a queued write belonged to
 * from whatever business happened to be active at send time, not the
 * business it was created in — so a write made offline under one business
 * could be delivered under a different one after a switch. It also deleted a
 * record outright once retries ran out, with no distinction between "will
 * never succeed" and "just needs a login or a reconnect". These tests are
 * the regression coverage for both: every queued write now carries a fixed
 * businessId set once at creation, and a failure is marked FAILED and kept,
 * never silently discarded.
 *
 * Requires the backend on the host at 10.0.2.2:3000 and -e authToken <jwt>.
 * The membership-removal test additionally needs a second, already
 * OTP-verified account passed as -e staffAuthToken <jwt> and
 * -e staffAuthPhone <phone> (digits only, no country code) — it is skipped
 * if these are not supplied, the same way the S3 integration spec on the
 * backend skips when MinIO is unreachable.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OutboxBusinessIsolationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val serverUrl = "http://10.0.2.2:3000/"
    private val unreachableUrl = "http://10.255.255.1:9/" // routes nowhere, fails fast
    private val phone = "+919000000019"

    private lateinit var repo: KhatabookRepository
    private var token: String? = null

    private lateinit var businessA: String
    private lateinit var businessB: String

    @Before
    fun setUp(): Unit = runBlocking {
        // "khatabook_repository_prefs" (no suffix) is the legacy plaintext
        // file from before the EncryptedSharedPreferences migration —
        // KhatabookRepository actually reads/writes
        // "khatabook_repository_prefs_enc" for everything (outbox included),
        // so clearing only the old name left every test method in this class
        // accumulating real, persisted outbox state across runs (same phone
        // number reused throughout the class) — the actual root cause of the
        // g/h/i flakiness this suite has been chasing.
        context.getSharedPreferences("khatabook_repository_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("khatabook_repository_prefs_enc", 0).edit().clear().commit()

        token = InstrumentationRegistry.getArguments().getString("authToken")
        assertNotNull("pass -e authToken <jwt>", token)

        repo = KhatabookRepository(context)
        NetworkClient.updateBaseUrl(serverUrl)
        NetworkClient.init({ token }, { repo.currentBusinessId() })

        repo.setServerUrl(serverUrl)
        repo.switchUser("9000000019")

        val a = NetworkClient.apiService.createBusiness(CreateBusinessRequest("Isolation A", "Retail"))
        val b = NetworkClient.apiService.createBusiness(CreateBusinessRequest("Isolation B", "Retail"))
        assertTrue("create A failed: ${a.code()}", a.isSuccessful)
        assertTrue("create B failed: ${b.code()}", b.isSuccessful)
        businessA = a.body()!!.id
        businessB = b.body()!!.id

        assertTrue(repo.loadBusinesses())
    }

    @After
    fun tearDown(): Unit = runBlocking {
        // NetworkClient is a process-wide singleton reconfigured by the next
        // test's setUp() — without this, a straggler background flush from
        // this test's repo (enqueue() fires one on every add*() call) can
        // still be executing when the next test starts, and would run against
        // that test's business/token instead of this one's. cancelAndJoin
        // (inside cancelBackgroundWorkForTesting) so this actually waits for
        // any in-flight attempt to stop, not just request cancellation.
        repo.cancelBackgroundWorkForTesting()
    }

    /** Parties visible under [businessId], fetched independently of whatever the repo has active. */
    private suspend fun partyIdsIn(businessId: String): List<String> {
        val previousToken = token
        NetworkClient.init({ previousToken }, { businessId })
        val response = NetworkClient.apiService.getParties()
        NetworkClient.init({ previousToken }, { repo.currentBusinessId() })
        assertTrue("fetching parties for $businessId failed: ${response.code()}", response.isSuccessful)
        return response.body()!!.map { it.id }
    }

    @Test
    fun a_offlineCreateThenSwitchBusinessThenReconnectStaysUnderOriginalBusiness() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        val party = repo.addParty("Offline Then Switched", "+919876600001", PartyType.CUSTOMER)
        repo.flushOutbox() // fails, stays queued — still offline

        // Switching business while the write is still undelivered must not
        // change which business it is eventually sent to.
        repo.switchBusiness(businessB)

        repo.setServerUrl(serverUrl)
        repo.flushOutbox()

        assertTrue("write did not reach business A", partyIdsIn(businessA).contains(party.id))
        assertFalse("write leaked into business B after the switch", partyIdsIn(businessB).contains(party.id))
    }

    @Test
    fun b_twoBusinessesWithPendingOperationsBothDeliverToTheCorrectBusiness() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        val partyA = repo.addParty("Pending In A", "+919876600002", PartyType.CUSTOMER)
        repo.flushOutbox()

        repo.switchBusiness(businessB) // best-effort flush inside this fails too — still offline
        val partyB = repo.addParty("Pending In B", "+919876600003", PartyType.CUSTOMER)
        repo.flushOutbox()

        assertTrue("both writes should still be queued", repo.totalQueuedOperationCount() >= 2)

        repo.setServerUrl(serverUrl)
        assertTrue("queue did not drain", repo.flushOutbox())

        assertTrue(partyIdsIn(businessA).contains(partyA.id))
        assertFalse(partyIdsIn(businessA).contains(partyB.id))
        assertTrue(partyIdsIn(businessB).contains(partyB.id))
        assertFalse(partyIdsIn(businessB).contains(partyA.id))
    }

    @Test
    fun c_appRestartWithPendingOperationsPreservesBusinessAttribution() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        val party = repo.addParty("Restart Preserves Business", "+919876600004", PartyType.CUSTOMER)
        repo.flushOutbox()
        assertTrue(repo.pendingOperationCount() >= 1)

        // A fresh repository stands in for the app being killed and reopened.
        val reopened = KhatabookRepository(context)
        reopened.switchUser("9000000019")
        NetworkClient.init({ token }, { reopened.currentBusinessId() })
        assertTrue("queued work did not survive a restart", reopened.totalQueuedOperationCount() >= 1)

        reopened.setServerUrl(serverUrl)
        reopened.flushOutbox()

        assertTrue("restarted write did not reach business A", partyIdsIn(businessA).contains(party.id))
        assertFalse("restarted write leaked into business B", partyIdsIn(businessB).contains(party.id))

        NetworkClient.init({ token }, { repo.currentBusinessId() })
    }

    @Test
    fun d_duplicateDeliveryIsIdempotentNotDoubleApplied() = runBlocking {
        repo.switchBusiness(businessA)
        val party = repo.addParty("Idempotent Replay", "+919876600005", PartyType.CUSTOMER)
        assertTrue(repo.flushOutbox())

        // Simulates a retry of a request whose success response never made it
        // back to the client: the exact same id is sent again.
        val replay = NetworkClient.apiService.createParty(
            CreatePartyRequest(id = party.id, name = party.name, phone = party.phone, type = "CUSTOMER"),
            businessA
        )
        assertTrue("idempotent replay should succeed, not conflict", replay.isSuccessful)

        val stored = NetworkClient.apiService.getParties().body()!!.filter { it.id == party.id }
        assertEquals("replay must not create a duplicate row", 1, stored.size)
    }

    @Test
    fun e_networkFailureKeepsOperationPendingNotLost() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        repo.addParty("Network Failure", "+919876600006", PartyType.CUSTOMER)
        val drained = repo.flushOutbox()

        assertFalse(drained)
        assertTrue("a merely-offline write must stay pending", repo.pendingOperationCount() >= 1)
        assertEquals("a merely-offline write must not be marked failed", 0, repo.failedOperationCount())
        assertTrue("nothing should be deleted", repo.totalQueuedOperationCount() >= 1)
    }

    @Test
    fun f_sessionExpiryKeepsOperationRecoverableNotLost() = runBlocking {
        repo.switchBusiness(businessA)

        // An invalid token stands in for an expired session: reachable
        // server, unauthorized request.
        NetworkClient.init({ "expired.invalid.token" }, { repo.currentBusinessId() })
        val party = repo.addParty("Session Expiry", "+919876600007", PartyType.CUSTOMER)
        repo.flushOutbox()

        assertTrue("a 401 must not delete the write", repo.pendingOperationCount() >= 1)

        // Push past the retry ceiling without a valid token: it must still
        // not be deleted, only parked as failed.
        repeat(10) { repo.flushOutbox() }
        assertTrue("write vanished instead of being marked failed", repo.totalQueuedOperationCount() >= 1)

        // Restoring a valid token stands in for the user logging back in.
        NetworkClient.init({ token }, { repo.currentBusinessId() })
        repo.retryFailedOperations()
        assertTrue("queue did not drain after recovering a valid session", repo.flushOutbox())

        assertTrue("write never reached the server after recovery", partyIdsIn(businessA).contains(party.id))
    }

    @Test
    fun g_permanentFailureIsKeptAsFailedNotDeleted() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        val phantomPartyId = "00000000-0000-4000-8000-000000000000"
        repo.addLedgerEntry(phantomPartyId, 100.0, TransactionType.GAVE, "references nothing")
        repo.flushOutbox()

        repo.setServerUrl(serverUrl)
        val drained = repo.flushOutbox()

        // flushOutbox()'s documented contract is "true once nothing PENDING
        // remains" — a permanent failure is parked FAILED, not PENDING, so
        // the call correctly reports drained=true here: there is nothing more
        // it can do on its own. Retrying a FAILED op needs an explicit
        // retryFailedOperations() first; this test's real invariant is that
        // the failure is kept and visible, not silently discarded — which the
        // three assertions below actually check.
        assertTrue("nothing PENDING should remain once the failure is parked", drained)
        assertEquals(1, repo.failedOperationCount())
        assertEquals(0, repo.pendingOperationCount())
        assertEquals("the failed write must still be present, not deleted", 1, repo.totalQueuedOperationCount())
    }

    @Test
    fun h_failedParentBlocksOnlyItsOwnDependentsNotOtherBusinesses() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        val phantomPartyId = "00000000-0000-4000-8000-000000000000"
        // Two entries against a party that will never exist: the first is
        // business A's queue head and will permanently fail; the second must
        // never even be attempted while it is blocked behind it.
        repo.addLedgerEntry(phantomPartyId, 100.0, TransactionType.GAVE, "first, will fail")
        repo.addLedgerEntry(phantomPartyId, 200.0, TransactionType.GAVE, "second, must stay blocked")
        repo.flushOutbox()

        repo.switchBusiness(businessB)
        val partyB = repo.addParty("Unaffected By A's Failure", "+919876600008", PartyType.CUSTOMER)
        repo.flushOutbox()

        repo.setServerUrl(serverUrl)
        repo.flushOutbox()

        assertEquals("only the blocked head should be failed", 1, repo.failedOperationCount())
        assertEquals("the second entry must remain pending, never attempted", 1, repo.pendingOperationCount())
        assertEquals("nothing in business A's queue should be deleted", 2, repo.totalQueuedOperationCount())
        assertTrue(
            "business B must be unaffected by business A's stuck operation",
            partyIdsIn(businessB).contains(partyB.id),
        )
    }

    @Test
    fun i_concurrentFlushDoesNotDuplicateDelivery() = runBlocking {
        repo.switchBusiness(businessA)
        repo.setServerUrl(unreachableUrl)
        val party = repo.addParty("Concurrent Flush", "+919876600009", PartyType.CUSTOMER)
        repo.flushOutbox()

        repo.setServerUrl(serverUrl)
        val results = listOf(
            async(Dispatchers.IO) { repo.flushOutbox() },
            async(Dispatchers.IO) { repo.flushOutbox() },
        ).awaitAll()

        assertTrue("at least one concurrent flush should report drained", results.any { it })
        assertEquals(0, repo.pendingOperationCount())

        val stored = partyIdsIn(businessA).count { it == party.id }
        assertEquals("concurrent flushes must not double-deliver", 1, stored)
    }

    @Test
    fun j_membershipRemovedWhilePendingIsNotSilentlyLost() = runBlocking {
        val staffToken = InstrumentationRegistry.getArguments().getString("staffAuthToken")
        val staffPhone = InstrumentationRegistry.getArguments().getString("staffAuthPhone")
        assumeTrue(
            "skipped: pass -e staffAuthToken <jwt> -e staffAuthPhone <phone> to run this test",
            staffToken != null && staffPhone != null,
        )

        // Ensure the staff account belongs to business A. Already being a
        // member is fine; either way is not what this test is checking.
        NetworkClient.init({ token }, { businessA })
        NetworkClient.apiService.addStaff(AddStaffRequest(staffPhone!!, "Staff", "CASHIER"))

        val staffRepo = KhatabookRepository(context)
        NetworkClient.init({ staffToken }, { staffRepo.currentBusinessId() })
        staffRepo.setServerUrl(serverUrl)
        staffRepo.switchUser(staffPhone)
        assertTrue(staffRepo.loadBusinesses())
        staffRepo.switchBusiness(businessA)

        staffRepo.setServerUrl(unreachableUrl)
        staffRepo.addParty("Membership Removed Mid-Flight", "+919876600010", PartyType.CUSTOMER)
        staffRepo.flushOutbox()
        assertTrue(staffRepo.pendingOperationCount() >= 1)

        // The owner revokes the staff member's access to business A while the
        // write is still queued on the staff member's device.
        NetworkClient.init({ token }, { businessA })
        val staff = NetworkClient.apiService.getStaff().body().orEmpty()
        staff.firstOrNull { it.phoneNumber == staffPhone }?.let {
            NetworkClient.apiService.removeStaff(it.membershipId)
        }

        // Reconnect as the now-removed staff member and try to deliver.
        NetworkClient.init({ staffToken }, { staffRepo.currentBusinessId() })
        staffRepo.setServerUrl(serverUrl)
        staffRepo.flushOutbox()

        assertTrue(
            "a write blocked by revoked membership must not be silently lost",
            staffRepo.totalQueuedOperationCount() >= 1,
        )

        NetworkClient.init({ token }, { repo.currentBusinessId() })
    }
}
