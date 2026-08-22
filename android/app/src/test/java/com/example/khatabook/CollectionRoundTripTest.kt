package com.example.khatabook

import android.content.Context
import android.content.SharedPreferences
import com.example.khatabook.data.api.CreatePartyRequest
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.repository.KhatabookRepository
import com.google.gson.Gson
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

/**
 * The classification a user chose has to survive the whole journey: chosen in
 * the UI, written to Room, sent to the server, and read back on a device that
 * has lost its local data.
 *
 * This exercises the two ends the unit tests cannot: what actually lands in
 * Room, and what actually goes on the wire.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CollectionRoundTripTest {

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

    @Test
    fun `each collection type is persisted to Room as chosen`() = runTest {
        repository.addParty("A", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.DAILY)
        repository.addParty("B", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        repository.addParty("C", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.MONTHLY)

        // Read straight from the database, not the in-memory StateFlow.
        val stored = db.partyDao().getForBusinessSync(ownerPhone, businessId)
            .associate { it.name to it.collectionFrequency }

        assertEquals("DAILY", stored["A"])
        assertEquals("WEEKLY", stored["B"])
        assertEquals("MONTHLY", stored["C"])
    }

    @Test
    fun `the classification is sent to the server on create`() = runTest {
        // Without this the server has nothing to restore from — the original
        // cause of every party coming back as Daily.
        val weekly = repository.addParty(
            "B", "", PartyType.CUSTOMER, businessId = businessId,
            frequency = CollectionFrequency.WEEKLY,
        )

        val row = db.outboxDao().getAllSync(ownerPhone).last { it.targetId == weekly.id }
        val sent = gson.fromJson(row.payload, CreatePartyRequest::class.java)

        assertEquals("WEEKLY", sent.collectionFrequency)
    }

    @Test
    fun `all three types reach the server distinctly`() = runTest {
        repository.addParty("A", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.DAILY)
        repository.addParty("B", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        repository.addParty("C", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.MONTHLY)

        val sentFrequencies = db.outboxDao().getAllSync(ownerPhone)
            .filter { it.type == "CREATE_PARTY" }
            .map { gson.fromJson(it.payload, CreatePartyRequest::class.java).collectionFrequency }

        assertEquals(listOf("DAILY", "WEEKLY", "MONTHLY"), sentFrequencies)
    }

    @Test
    fun `each classification survives switching away and back`() = runTest {
        repository.addParty("A", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.DAILY)
        repository.addParty("B", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.WEEKLY)
        repository.addParty("C", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.MONTHLY)

        // Switching accounts clears the in-memory state; the database is what
        // has to still be right afterwards, since that is what a reload — or a
        // reinstall-and-restore — reads back.
        repository.switchUser("9000000002")
        repository.switchUser(ownerPhone)

        val stored = db.partyDao().getForBusinessSync(ownerPhone, businessId)
            .associate { it.name to it.collectionFrequency }

        assertEquals("DAILY", stored["A"])
        assertEquals("WEEKLY", stored["B"])
        assertEquals("MONTHLY", stored["C"])
    }

    @Test
    fun `another account's parties never inherit this account's classification`() = runTest {
        repository.addParty("MINE", "", PartyType.CUSTOMER, businessId = businessId, frequency = CollectionFrequency.MONTHLY)

        repository.switchUser("9000000002")
        repository.setActiveBusinessIdForTesting("other-business")
        repository.addParty("THEIRS", "", PartyType.CUSTOMER, businessId = "other-business", frequency = CollectionFrequency.DAILY)

        // Each account's rows are scoped by ownerPhone + businessId, so
        // neither can see — let alone reclassify — the other's.
        val mine = db.partyDao().getForBusinessSync(ownerPhone, businessId)
        val theirs = db.partyDao().getForBusinessSync("9000000002", "other-business")

        assertTrue(mine.none { it.name == "THEIRS" })
        assertTrue(theirs.none { it.name == "MINE" })
        assertEquals("MONTHLY", mine.first { it.name == "MINE" }.collectionFrequency)
        assertEquals("DAILY", theirs.first { it.name == "THEIRS" }.collectionFrequency)
    }
}
