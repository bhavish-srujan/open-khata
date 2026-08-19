package com.example.khatabook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.khatabook.data.api.CreateBusinessRequest
import com.example.khatabook.data.api.CreateItemRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The repository-owned caches added for Cashbook/Bills/Items/Staff/Reports,
 * against a real backend on a real device — the JVM unit suite cannot
 * instantiate [KhatabookRepository] at all (it needs a real AndroidKeyStore
 * for EncryptedSharedPreferences, unavailable in the plain JVM test sandbox;
 * see CacheReconciliationTest, which exercises the same scenarios but can
 * only prove they compile and reach that same wall). This is the real
 * verification for these caches, in the same style as
 * BusinessSwitchingInstrumentedTest/OutboxBusinessIsolationInstrumentedTest.
 *
 * Requires the backend on the host at 10.0.2.2:3000 and -e authToken <jwt>.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NewCachesInstrumentedTest {

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
        NetworkClient.init({ token }, { repo.currentBusinessId() })

        repo.setServerUrl(serverUrl)
        repo.switchUser("9000000099")

        val a = NetworkClient.apiService.createBusiness(CreateBusinessRequest("Cache Isolation A", "Retail"))
        val b = NetworkClient.apiService.createBusiness(CreateBusinessRequest("Cache Isolation B", "Retail"))
        assertTrue("create A failed: ${a.code()}", a.isSuccessful)
        assertTrue("create B failed: ${b.code()}", b.isSuccessful)
        businessA = a.body()!!.id
        businessB = b.body()!!.id

        assertTrue(repo.loadBusinesses())
    }

    @Test
    fun a_itemsCacheMissFetchesFromServerAndPopulatesTheStateFlow() = runBlocking {
        repo.switchBusiness(businessA)
        assertTrue(repo.items.value.isEmpty())

        val created = NetworkClient.apiService.createItem(
            CreateItemRequest(name = "Cache Test Item", sellingPrice = 100.0, currentStock = 5.0)
        )
        assertTrue("seed item create failed: ${created.code()}", created.isSuccessful)

        assertTrue("sync should succeed", repo.syncItems())
        assertTrue("items cache should now hold the seeded item", repo.items.value.any { it.name == "Cache Test Item" })
    }

    @Test
    fun b_itemsCacheSurvivesProcessRestart() = runBlocking {
        repo.switchBusiness(businessA)
        NetworkClient.apiService.createItem(CreateItemRequest(name = "Restart Item", sellingPrice = 50.0, currentStock = 1.0))
        assertTrue(repo.syncItems())
        assertTrue(repo.items.value.any { it.name == "Restart Item" })

        // A fresh repository stands in for the app being killed and reopened.
        val reopened = KhatabookRepository(context)
        reopened.switchUser("9000000099")
        NetworkClient.init({ token }, { reopened.currentBusinessId() })
        assertTrue(reopened.loadBusinesses())
        reopened.switchBusiness(businessA)

        assertTrue(
            "the items cache must be readable from disk without a network call",
            reopened.items.value.any { it.name == "Restart Item" },
        )
        NetworkClient.init({ token }, { repo.currentBusinessId() })
    }

    @Test
    fun c_businessASwitchBThenASeesOnlyEachBusinesssOwnItems() = runBlocking {
        repo.switchBusiness(businessA)
        NetworkClient.apiService.createItem(CreateItemRequest(name = "Only In A", sellingPrice = 10.0, currentStock = 1.0))
        assertTrue(repo.syncItems())
        assertTrue(repo.items.value.any { it.name == "Only In A" })

        repo.switchBusiness(businessB)
        assertTrue(
            "switching to B must not show A's cached items before B's own sync",
            repo.items.value.none { it.name == "Only In A" },
        )
        NetworkClient.apiService.createItem(CreateItemRequest(name = "Only In B", sellingPrice = 20.0, currentStock = 1.0))
        assertTrue(repo.syncItems())
        assertTrue(repo.items.value.any { it.name == "Only In B" })
        assertFalse("B must never see A's item", repo.items.value.any { it.name == "Only In A" })

        repo.switchBusiness(businessA)
        assertTrue("switching back to A must restore A's own cache", repo.items.value.any { it.name == "Only In A" })
        assertFalse("A must never see B's item", repo.items.value.any { it.name == "Only In B" })
    }

    @Test
    fun d_cachedItemsDoNotBleedBetweenBusinessesAcrossARestart() = runBlocking {
        repo.switchBusiness(businessA)
        NetworkClient.apiService.createItem(CreateItemRequest(name = "Persisted In A", sellingPrice = 30.0, currentStock = 1.0))
        assertTrue(repo.syncItems())

        repo.switchBusiness(businessB)

        val reopened = KhatabookRepository(context)
        reopened.switchUser("9000000099")
        NetworkClient.init({ token }, { reopened.currentBusinessId() })
        reopened.loadBusinesses()

        assertEquals(businessB, reopened.currentBusinessId())
        assertFalse(
            "A's cached item appeared under B after a restart",
            reopened.items.value.any { it.name == "Persisted In A" },
        )
        NetworkClient.init({ token }, { repo.currentBusinessId() })
    }

    @Test
    fun e_businessLedgerFeedIsOptimisticallyPatchedWithoutAnExtraFetch() = runBlocking {
        repo.switchBusiness(businessA)
        val party = repo.addParty("Feed Patch Party", "+919876600020", com.example.khatabook.data.model.PartyType.CUSTOMER)
        assertTrue(repo.flushOutbox())

        assertTrue("feed cache should start empty before any sync", repo.businessLedgerFeed.value.isEmpty())

        val entry = repo.addLedgerEntry(
            party.id, 250.0, com.example.khatabook.data.model.TransactionType.GOT, "optimistic feed patch",
        )
        assertNotNull(entry)

        // The feed must reflect the new entry immediately — from the local
        // optimistic patch, not from a round trip to the server.
        assertTrue(
            "the ledger feed cache must be patched optimistically on create",
            repo.businessLedgerFeed.value.any { it.id == entry!!.id && it.amount == 250.0 },
        )
    }

    @Test
    fun f_cashflowReportIsIsolatedPerBusiness() = runBlocking {
        repo.switchBusiness(businessA)
        val partyA = repo.addParty("Cashflow A", "+919876600030", com.example.khatabook.data.model.PartyType.CUSTOMER)
        repo.addLedgerEntry(partyA.id, 400.0, com.example.khatabook.data.model.TransactionType.GOT, "cashflow A in")
        assertTrue(repo.flushOutbox())
        assertTrue("cashflow sync for A should succeed", repo.syncCashflow())
        val totalInA = repo.cashflowReport.value?.totalIn
        assertNotNull(totalInA)

        repo.switchBusiness(businessB)
        assertTrue(
            "switching to B must not show A's cashflow numbers before B's own sync",
            repo.cashflowReport.value == null || repo.cashflowReport.value?.totalIn != totalInA,
        )
        val partyB = repo.addParty("Cashflow B", "+919876600031", com.example.khatabook.data.model.PartyType.CUSTOMER)
        repo.addLedgerEntry(partyB.id, 900.0, com.example.khatabook.data.model.TransactionType.GOT, "cashflow B in")
        assertTrue(repo.flushOutbox())
        assertTrue("cashflow sync for B should succeed", repo.syncCashflow(force = true))
        assertEquals(900.0, repo.cashflowReport.value?.totalIn)

        repo.switchBusiness(businessA)
        assertTrue("cashflow sync for A (again) should succeed", repo.syncCashflow(force = true))
        assertEquals(
            "switching back to A must show A's own cashflow, not B's",
            400.0,
            repo.cashflowReport.value?.totalIn,
        )
    }

    @Test
    fun g_cashflowCacheIsInvalidatedByALocalLedgerMutation() = runBlocking {
        repo.switchBusiness(businessA)
        val party = repo.addParty("Invalidation Party", "+919876600032", com.example.khatabook.data.model.PartyType.CUSTOMER)
        repo.addLedgerEntry(party.id, 100.0, com.example.khatabook.data.model.TransactionType.GOT, "first entry")
        assertTrue(repo.flushOutbox())
        assertTrue(repo.syncCashflow(force = true))
        val before = repo.cashflowReport.value?.totalIn
        assertEquals(100.0, before)

        // A second local mutation, without an explicit force refresh — the
        // cached report must be treated as stale by the mutation itself, not
        // only by the TTL expiring.
        repo.addLedgerEntry(party.id, 50.0, com.example.khatabook.data.model.TransactionType.GOT, "second entry")
        assertTrue(repo.flushOutbox())
        assertTrue(repo.syncCashflow())
        assertEquals(
            "a local ledger mutation must invalidate the cashflow cache, not leave the pre-mutation total cached",
            150.0,
            repo.cashflowReport.value?.totalIn,
        )
    }

    @Test
    fun h_dayWiseBreakdownGroupsRealEntriesByCalendarDayWithinBusinessIsolation() = runBlocking {
        repo.switchBusiness(businessA)
        val party = repo.addParty("DayWise Party", "+919876600033", com.example.khatabook.data.model.PartyType.CUSTOMER)
        repo.addLedgerEntry(party.id, 300.0, com.example.khatabook.data.model.TransactionType.GOT, "dayA in")
        repo.addLedgerEntry(party.id, 120.0, com.example.khatabook.data.model.TransactionType.GAVE, "dayA out")
        assertTrue(repo.flushOutbox())
        assertTrue(repo.syncBusinessLedgerFeed(force = true))

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val rows = repo.dayWiseBreakdown("$today" + "T00:00:00.000Z", "$today" + "T23:59:59.999Z")
        val todayRow = rows.firstOrNull { it.first == today }
        assertNotNull("today's entries should appear in the day-wise breakdown", todayRow)
        assertEquals(2, todayRow!!.second.size)

        repo.switchBusiness(businessB)
        val rowsForB = repo.dayWiseBreakdown("$today" + "T00:00:00.000Z", "$today" + "T23:59:59.999Z")
        assertTrue(
            "business B's day-wise breakdown must not include business A's entries",
            rowsForB.none { (_, entries) -> entries.any { it.note == "dayA in" || it.note == "dayA out" } },
        )
    }
}
