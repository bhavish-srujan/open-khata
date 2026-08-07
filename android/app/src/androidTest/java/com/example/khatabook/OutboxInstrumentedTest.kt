package com.example.khatabook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Outbox, offline behaviour and merge, exercised on a real device against a real
 * backend.
 *
 * The unit suite can only check classification and serialisation in isolation.
 * This runs the actual repository with real SharedPreferences and real Retrofit,
 * which is the only way to see that a write survives being made offline and is
 * delivered later, and that a pull no longer destroys it.
 *
 * Requires the backend on the host at 10.0.2.2:3000, and a token passed in with
 * -e authToken <jwt>. The token is injected rather than obtained through the
 * OTP flow because verification consumes the code, so only the first of these
 * tests could authenticate — and the auth flow is not what is under test here.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OutboxInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val serverUrl = "http://10.0.2.2:3000/"
    private val unreachableUrl = "http://10.255.255.1:9/" // routes nowhere, fails fast
    private val phone = "+919000000009"

    private lateinit var repo: KhatabookRepository
    private var token: String? = null

    // Must declare Unit explicitly: an expression body returning runBlocking's
    // result gives JUnit a non-void @Before, which it rejects.
    @Before
    fun setUp(): Unit = runBlocking {
        // Start from a clean slate on the device.
        context.getSharedPreferences("khatabook_repository_prefs", 0).edit().clear().commit()

        NetworkClient.updateBaseUrl(serverUrl)
        NetworkClient.init { token }

        token = InstrumentationRegistry.getArguments().getString("authToken")
        assertNotNull("pass -e authToken <jwt>", token)

        // Prove the token actually works before relying on it.
        val probe = NetworkClient.apiService.getParties()
        assertTrue("auth probe failed: ${probe.code()}", probe.isSuccessful)

        repo = KhatabookRepository(context)
        repo.setServerUrl(serverUrl)
        repo.switchUser("9000000009")
        repo.flushOutbox()
    }

    @Test
    fun a_writeMadeOfflineIsQueuedAndSurvivesLocally() = runBlocking {
        repo.setServerUrl(unreachableUrl)

        val party = repo.addParty("Offline Customer", "+919876500011", PartyType.CUSTOMER)
        repo.flushOutbox()

        // Visible locally straight away…
        assertTrue(repo.parties.value.any { it.id == party.id })
        // …and still owed to the server.
        assertTrue("expected a queued operation", repo.pendingOperationCount() >= 1)
    }

    @Test
    fun b_queuedWriteIsDeliveredOnceTheServerIsReachable() = runBlocking {
        repo.setServerUrl(unreachableUrl)
        val party = repo.addParty("Deferred Customer", "+919876500012", PartyType.CUSTOMER)
        repo.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "offline entry")
        repo.flushOutbox()
        assertTrue(repo.pendingOperationCount() >= 2)

        repo.setServerUrl(serverUrl)
        val drained = repo.flushOutbox()

        assertTrue("outbox did not drain", drained)
        assertEquals(0, repo.pendingOperationCount())

        // The server must now hold both, with the balance applied.
        val remote = NetworkClient.apiService.getParties()
        assertTrue(remote.isSuccessful)
        val stored = remote.body()!!.firstOrNull { it.id == party.id }
        assertNotNull("party never reached the server", stored)
        assertEquals(500.0, stored!!.currentBalance, 0.001)
    }

    @Test
    fun c_pullPreservesAWriteThatHasNotSyncedYet() = runBlocking {
        // One party that reaches the server, one that cannot.
        val synced = repo.addParty("Synced Customer", "+919876500013", PartyType.CUSTOMER)
        repo.flushOutbox()

        repo.setServerUrl(unreachableUrl)
        val unsynced = repo.addParty("Unsynced Customer", "+919876500014", PartyType.CUSTOMER)
        repo.flushOutbox()

        // The pull that used to replace local state wholesale.
        repo.setServerUrl(serverUrl)
        repo.syncPartiesFromServer()

        assertTrue("synced party missing", repo.parties.value.any { it.id == synced.id })
        assertTrue(
            "unsynced party was destroyed by the pull",
            repo.parties.value.any { it.id == unsynced.id },
        )
    }

    @Test
    fun d_editAndDeleteReachTheServer() = runBlocking {
        val party = repo.addParty("Edit Customer", "+919876500015", PartyType.CUSTOMER)
        val entry = repo.addLedgerEntry(party.id, 500.0, TransactionType.GAVE, "initial")!!
        assertTrue(repo.flushOutbox())

        repo.updateLedgerEntry(entry.id, 800.0, "corrected")
        assertTrue("edit did not drain", repo.flushOutbox())

        var remote = NetworkClient.apiService.getParties()
        var stored = remote.body()!!.first { it.id == party.id }
        assertEquals("edit did not reach the server", 800.0, stored.currentBalance, 0.001)

        repo.deleteLedgerEntry(entry.id)
        assertTrue("delete did not drain", repo.flushOutbox())

        remote = NetworkClient.apiService.getParties()
        stored = remote.body()!!.first { it.id == party.id }
        assertEquals("delete did not reach the server", 0.0, stored.currentBalance, 0.001)
    }

    @Test
    fun e_queueSurvivesProcessRestart() = runBlocking {
        repo.setServerUrl(unreachableUrl)
        repo.addParty("Persisted Customer", "+919876500016", PartyType.CUSTOMER)
        repo.flushOutbox()
        assertTrue(repo.pendingOperationCount() >= 1)

        // A fresh repository stands in for the app being killed and reopened.
        val reopened = KhatabookRepository(context)
        reopened.switchUser("9000000009")

        assertTrue(
            "queued work did not survive a restart",
            reopened.pendingOperationCount() >= 1,
        )
    }
}
