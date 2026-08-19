package com.example.khatabook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.khatabook.data.api.CreateBusinessRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Registration (`UserDetailsScreen` -> `MainActivity.onDetailsSubmitted`) types a
 * business name and immediately clears the nav stack to move on — the same pattern
 * as every other "fire an update, then navigate" flow in the app. Routing that
 * update through a `rememberCoroutineScope()` tied to the screen being torn down
 * lets the navigation cancel it mid-flight: the PATCH can still land (fast
 * localhost round trip), but the follow-up refresh that updates [activeBusiness]
 * never runs, so the app keeps showing the server's auto-provisioned
 * "My Khata Business" forever even though the database has the real name.
 *
 * [KhatabookRepository.applyPendingBusinessProfile] exists specifically to avoid
 * that: it runs on the repository's own process-lifetime scope, not a caller's.
 * This pins that it completes — both the write and the local state refresh —
 * with nothing awaiting it, exactly as MainActivity calls it.
 *
 * Requires the backend on the host at 10.0.2.2:3000 and -e authToken <jwt>.
 */
@RunWith(AndroidJUnit4::class)
class BusinessProfileUpdateInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val serverUrl = "http://10.0.2.2:3000/"

    private lateinit var repo: KhatabookRepository
    private var token: String? = null
    private lateinit var businessId: String

    @Before
    fun setUp(): Unit = runBlocking {
        context.getSharedPreferences("khatabook_repository_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("khatabook_repository_prefs_enc", 0).edit().clear().commit()

        token = InstrumentationRegistry.getArguments().getString("authToken")
        assertNotNull("pass -e authToken <jwt>", token)

        repo = KhatabookRepository(context)
        NetworkClient.updateBaseUrl(serverUrl)
        NetworkClient.init({ token }, { repo.currentBusinessId() })

        repo.setServerUrl(serverUrl)
        repo.switchUser("9000000777")

        // Stands in for verifyOtp's auto-provisioned "My Khata Business".
        val created = NetworkClient.apiService.createBusiness(
            CreateBusinessRequest("My Khata Business", "General Store")
        )
        assertTrue("create failed: ${created.code()}", created.isSuccessful)
        businessId = created.body()!!.id

        assertTrue(repo.loadBusinesses())
        repo.switchBusiness(businessId)
    }

    @Test
    fun pendingBusinessProfileUpdateCompletesWithNothingAwaitingIt() = runBlocking {
        // Fire-and-forget, exactly like MainActivity.onDetailsSubmitted — no
        // caller ever suspends on this call.
        repo.applyPendingBusinessProfile("Ramu Traders", "Kirana / Grocery")

        var appliedLocally = false
        repeat(50) {
            if (repo.activeBusiness.value?.name == "Ramu Traders") {
                appliedLocally = true
                return@repeat
            }
            delay(100)
        }
        assertTrue(
            "activeBusiness should reflect the applied name once the background update finishes",
            appliedLocally,
        )

        // The local StateFlow update is only trustworthy if the server itself
        // actually has the real name — not just an optimistic local patch.
        val fromServer = NetworkClient.apiService.getBusinesses()
        assertTrue(fromServer.isSuccessful)
        val serverName = fromServer.body()?.firstOrNull { it.id == businessId }?.name
        assertEquals("Ramu Traders", serverName)
    }
}
