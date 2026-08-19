package com.example.khatabook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.khatabook.data.api.CreateBusinessRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Multi-business, against a real backend on a real device.
 *
 * The app used to invent its own business ids — a hardcoded "biz_1" — so the
 * switcher, the profile locks and every per-business filter keyed off a value
 * the server had never heard of. Switching appeared to work while every request
 * still went to the single business named in the token.
 *
 * These tests create two genuine businesses through the API and assert that
 * switching between them actually changes which data the app sees.
 *
 * Requires the backend on the host at 10.0.2.2:3000 and -e authToken <jwt>.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BusinessSwitchingInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val serverUrl = "http://10.0.2.2:3000/"

    private lateinit var repo: KhatabookRepository
    private var token: String? = null

    private lateinit var businessA: String
    private lateinit var businessB: String

    @Before
    fun setUp(): Unit = runBlocking {
        // "khatabook_repository_prefs" (no suffix) is the legacy plaintext
        // file from before the EncryptedSharedPreferences migration —
        // KhatabookRepository actually reads/writes
        // "khatabook_repository_prefs_enc" for everything, so clearing only
        // the old name left state persisting across test methods.
        context.getSharedPreferences("khatabook_repository_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("khatabook_repository_prefs_enc", 0).edit().clear().commit()

        token = InstrumentationRegistry.getArguments().getString("authToken")
        assertNotNull("pass -e authToken <jwt>", token)

        repo = KhatabookRepository(context)
        NetworkClient.updateBaseUrl(serverUrl)
        // The interceptor must read the active business from the repository, or
        // every request would silently go to the token's business.
        NetworkClient.init({ token }, { repo.currentBusinessId() })

        repo.setServerUrl(serverUrl)
        repo.switchUser("9000000009")

        // Two genuine businesses, created through the API so each gets a real
        // UUID and an owner membership.
        val a = NetworkClient.apiService.createBusiness(CreateBusinessRequest("Shop A", "Retail"))
        val b = NetworkClient.apiService.createBusiness(CreateBusinessRequest("Shop B", "Electronics"))
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
        // this test's repo can still be executing when the next test starts.
        repo.cancelBackgroundWorkForTesting()
    }

    @Test
    fun a_businessesComeFromTheServerWithRealIds() = runBlocking {
        val ids = repo.businesses.value.map { it.id }

        assertTrue("Shop A missing", ids.contains(businessA))
        assertTrue("Shop B missing", ids.contains(businessB))
        // The invented id must be gone entirely.
        assertFalse("invented biz_1 still present", ids.contains("biz_1"))
        ids.forEach { assertTrue("not a UUID: $it", it.matches(Regex("^[0-9a-fA-F-]{36}$"))) }
    }

    @Test
    fun b_switchingChangesTheActiveBusiness() = runBlocking {
        repo.switchBusiness(businessA)
        assertEquals(businessA, repo.currentBusinessId())

        repo.switchBusiness(businessB)
        assertEquals(businessB, repo.currentBusinessId())
        assertNotEquals(businessA, repo.currentBusinessId())
    }

    @Test
    fun c_eachBusinessSeesOnlyItsOwnParties() = runBlocking {
        repo.switchBusiness(businessA)
        val partyA = repo.addParty("Customer In A", "+919876511111", PartyType.CUSTOMER)
        assertTrue("A did not sync", repo.flushOutbox())

        repo.switchBusiness(businessB)
        val partyB = repo.addParty("Customer In B", "+919876522222", PartyType.CUSTOMER)
        assertTrue("B did not sync", repo.flushOutbox())

        // Viewing B: only B's customer.
        repo.syncPartiesFromServer()
        var visible = repo.parties.value.map { it.id }
        assertTrue("B's own party missing", visible.contains(partyB.id))
        assertFalse("A's party leaked into B", visible.contains(partyA.id))

        // Switching back to A shows A's, and not B's.
        repo.switchBusiness(businessA)
        visible = repo.parties.value.map { it.id }
        assertTrue("A's party missing after switching back", visible.contains(partyA.id))
        assertFalse("B's party leaked into A", visible.contains(partyB.id))
    }

    @Test
    fun d_ledgerAndBalancesAreIsolatedPerBusiness() = runBlocking {
        repo.switchBusiness(businessA)
        val partyA = repo.addParty("Ledger A", "+919876533333", PartyType.CUSTOMER)
        repo.addLedgerEntry(partyA.id, 500.0, TransactionType.GAVE, "in A")
        assertTrue(repo.flushOutbox())

        repo.switchBusiness(businessB)
        val partyB = repo.addParty("Ledger B", "+919876544444", PartyType.CUSTOMER)
        repo.addLedgerEntry(partyB.id, 900.0, TransactionType.GAVE, "in B")
        assertTrue(repo.flushOutbox())

        // The cashbook feed is business-wide, so it is the sharpest check that
        // the server honoured the switch rather than the token.
        val feedB = NetworkClient.apiService.getBusinessLedger()
        assertTrue(feedB.isSuccessful)
        val amountsB = feedB.body()!!.map { it.amount }
        assertTrue("B's entry missing from B's feed", amountsB.contains(900.0))
        assertFalse("A's entry leaked into B's feed", amountsB.contains(500.0))

        repo.switchBusiness(businessA)
        val feedA = NetworkClient.apiService.getBusinessLedger()
        val amountsA = feedA.body()!!.map { it.amount }
        assertTrue("A's entry missing from A's feed", amountsA.contains(500.0))
        assertFalse("B's entry leaked into A's feed", amountsA.contains(900.0))
    }

    @Test
    fun e_cachedDataDoesNotBleedBetweenBusinesses() = runBlocking {
        repo.switchBusiness(businessA)
        val partyA = repo.addParty("Cached A", "+919876555555", PartyType.CUSTOMER)
        assertTrue(repo.flushOutbox())

        repo.switchBusiness(businessB)
        // Reopening the app while B is active must not surface A's cache.
        val reopened = KhatabookRepository(context)
        reopened.switchUser("9000000009")
        reopened.loadBusinesses()

        assertEquals(businessB, reopened.currentBusinessId())
        assertFalse(
            "A's cached party appeared under B after restart",
            reopened.parties.value.any { it.id == partyA.id },
        )
    }

    @Test
    fun f_serverRefusesABusinessTheUserDoesNotBelongTo() = runBlocking {
        // Directly forcing a foreign id must not grant access, whatever the
        // client sends: the server checks membership before honouring it.
        NetworkClient.init({ token }, { "00000000-0000-4000-8000-000000000000" })

        val res = NetworkClient.apiService.getParties()

        assertEquals(401, res.code())

        NetworkClient.init({ token }, { repo.currentBusinessId() })
    }
}
