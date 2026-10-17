package com.example.khatabook

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.example.khatabook.core.permission.PermissionManager
import com.example.khatabook.core.session.SessionManager
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.data.repository.StaffRepository
import com.example.khatabook.ui.MainApp
import com.example.khatabook.ui.screens.ContactItem
import com.example.khatabook.ui.theme.KhatabookTypography

/**
 * Re-export AppScreen in the root package for backwards compatibility.
 */
typealias AppScreen = com.example.khatabook.navigation.AppScreen

/**
 * Re-export navStackSaver in the root package for backwards compatibility.
 */
val navStackSaver = com.example.khatabook.navigation.navStackSaver

/**
 * Removes every trace of the signed-out account's credentials.
 * Preserved in root package for unit test compatibility.
 */
internal fun clearSession(
    prefs: SharedPreferences,
    phone: String,
    loggedInPhones: MutableList<String>
) {
    val editor = prefs.edit()
    if (phone.isNotBlank()) {
        editor.remove("jwt_token_$phone")
    }
    editor.remove("jwt_token")
    editor.remove("auth_phone")

    loggedInPhones.remove(phone)
    editor.putString("logged_in_phones_list", loggedInPhones.joinToString(","))

    editor.apply()
}

/**
 * Adds a contact to the active business, delegating to [StaffRepository].
 */
internal suspend fun addStaffMember(
    contact: ContactItem,
    role: String
): String = StaffRepository().addStaffMember(contact, role)

/**
 * Main entry-point activity for the Open Khata Android application.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repository: KhatabookRepository
    private lateinit var sessionManager: SessionManager
    private val staffRepository = StaffRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager(applicationContext)
        repository = KhatabookRepository(applicationContext)

        // Request SMS permission once at startup
        PermissionManager.requestSmsPermissionOnce(this)

        // Initialize HTTP networking with reactive token and business ID providers
        NetworkClient.init(
            tokenProvider = { sessionManager.getJwtToken() },
            businessIdProvider = { repository.currentBusinessId() }
        )

        setContent {
            MaterialTheme(typography = KhatabookTypography) {
                MainApp(
                    repository = repository,
                    sessionManager = sessionManager,
                    staffRepository = staffRepository,
                    onFinish = { finish() }
                )
            }
        }
    }
}
