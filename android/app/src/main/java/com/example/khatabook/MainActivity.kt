package com.example.khatabook

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.example.khatabook.utils.SmsSender
import android.os.Bundle
import android.view.WindowManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.ui.components.BusinessSwitcherDialog
import com.example.khatabook.ui.components.BusinessSwitchingOverlay
import com.example.khatabook.ui.components.MultiSessionAccountSwitcherDialog
import com.example.khatabook.ui.components.AnimatedScreenTransition
import com.example.khatabook.ui.components.ProfileLockVerificationDialog

import com.example.khatabook.ui.screens.AboutUsScreen
import com.example.khatabook.ui.screens.AddPartyScreen
import com.example.khatabook.ui.screens.AddStaffScreen
import com.example.khatabook.ui.screens.AppLockScreen
import com.example.khatabook.ui.screens.BackupInfoScreen
import com.example.khatabook.ui.screens.BillsScreen
import com.example.khatabook.ui.screens.BusinessStatisticsScreen
import com.example.khatabook.ui.screens.CashbookReportScreen
import com.example.khatabook.ui.screens.CashbookScreen
import com.example.khatabook.ui.screens.CustomerContactPickerScreen
import com.example.khatabook.ui.screens.CustomerLedgerScreen
import com.example.khatabook.ui.screens.DeleteKhataScreen
import com.example.khatabook.ui.screens.BookProfileScreen
import com.example.khatabook.ui.screens.HelpSupportScreen
import com.example.khatabook.ui.screens.ItemsScreen
import com.example.khatabook.ui.screens.LanguageSelectionScreen
import com.example.khatabook.ui.screens.MobileAuthScreen
import com.example.khatabook.ui.screens.MoreScreen
import com.example.khatabook.ui.screens.OtpVerificationScreen
import com.example.khatabook.ui.screens.PartiesScreen
import com.example.khatabook.ui.screens.PermissionsScreen
import com.example.khatabook.ui.screens.RecycleBinScreen
import com.example.khatabook.ui.screens.SetCollectionDateScreen
import com.example.khatabook.ui.screens.SettingsScreen
import com.example.khatabook.ui.screens.SmsSettingsScreen
import com.example.khatabook.ui.screens.StaffContactPickerScreen
import com.example.khatabook.ui.screens.UserDetailsScreen
import com.example.khatabook.ui.screens.ViewReportsScreen
import com.example.khatabook.ui.screens.availableLanguages
import kotlinx.coroutines.launch

/**
 * Removes every trace of the signed-out account's credentials.
 *
 * All of these matter: `jwt_token_<phone>` is what AuthInterceptor reads,
 * `jwt_token` is a legacy global copy that would otherwise be picked up as a
 * fallback, and `auth_phone` is what selects the active account on next launch.
 * Other signed-in accounts in [loggedInPhones] are left intact.
 */
internal fun clearSession(
    prefs: android.content.SharedPreferences,
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
 * Adds a contact to the active business, returning a message to show.
 *
 * Errors are mapped rather than surfaced raw: the server distinguishes an
 * already-present member from a rejected role, and both are things the person
 * tapping can act on.
 */
internal suspend fun addStaffMember(
    contact: com.example.khatabook.ui.screens.ContactItem,
    role: String,
): String {
    return try {
        val response = com.example.khatabook.data.api.NetworkClient.apiService.addStaff(
            com.example.khatabook.data.api.AddStaffRequest(
                phoneNumber = contact.phone,
                name = contact.name,
                role = role,
            )
        )
        when {
            response.isSuccessful -> "${contact.name} added as $role"
            response.code() == 400 -> {
                val body = response.errorBody()?.string().orEmpty()
                when {
                    body.contains("already a member") -> "${contact.name} is already in this business"
                    body.contains("owner") -> "Owners cannot be added this way"
                    body.contains("phone") -> "That contact has no valid phone number"
                    else -> "Could not add ${contact.name}"
                }
            }
            response.code() == 401 -> "Session expired. Please log in again."
            response.code() == 403 -> "Only owners and managers can add staff"
            else -> "Could not add staff (HTTP ${response.code()})"
        }
    } catch (e: Exception) {
        "Could not reach the server. Check your connection."
    }
}

enum class AppScreen {
    LANGUAGE_SELECTION,
    PERMISSIONS,
    MOBILE_AUTH,
    OTP_VERIFICATION,
    USER_DETAILS,
    PARTIES,
    CUSTOMER_LEDGER,
    CUSTOMER_PROFILE,
    CUSTOMER_CONTACT_PICKER,
    ADD_PARTY,
    CASHBOOK,
    CASHBOOK_REPORT,
    VIEW_REPORTS,
    BUSINESS_STATISTICS,
    MORE,
    SETTINGS,
    SMS_SETTINGS,
    RECYCLE_BIN,
    BACKUP_INFO,
    DELETE_KHATA,
    ADD_STAFF,
    STAFF_CONTACT_PICKER,
    SET_COLLECTION_DATE,
    BILLS,
    ITEMS,
    HELP_SUPPORT,
    ABOUT_US,
    EDIT_PROFILE
}

/** Lets [AppScreen]'s back stack survive process death via rememberSaveable. */
private val navStackSaver = androidx.compose.runtime.saveable.listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateList<AppScreen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { saved -> saved.map { AppScreen.valueOf(it) }.toMutableStateList() }
)

class MainActivity : AppCompatActivity() {

    private lateinit var repository: KhatabookRepository

    /**
     * Requests SEND_SMS a single time.
     *
     * Deliberately fire-and-forget: a refusal must not block the app, and every
     * send path reports PermissionMissing with a message telling the user how to
     * grant it later.
     */
    private fun requestSmsPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val missingPermissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.SEND_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 101)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        repository = KhatabookRepository(applicationContext)

        // Ask for SMS once, at startup, rather than at each send. Customer
        // balance updates and reminders now go out silently via SmsManager, so
        // without this the first reminder would fail with nothing to explain it.
        requestSmsPermissionOnce()

        // Init NetworkClient with a JWT token provider that reads from
        // EncryptedSharedPreferences on every request — so login is
        // automatically picked up without rebuilding the HTTP client.
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val securePrefs = EncryptedSharedPreferences.create(
            applicationContext,
            "khatabook_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        NetworkClient.init(
            tokenProvider = {
            val activePhone = securePrefs.getString("auth_phone", null)
            if (activePhone != null) {
                securePrefs.getString("jwt_token_$activePhone", securePrefs.getString("jwt_token", null))
            } else {
                securePrefs.getString("jwt_token", null)
            }
            },
            // The repository owns which business is active; the interceptor
            // reads it per request so a switch takes effect immediately.
            businessIdProvider = { repository.currentBusinessId() },
        )
        setContent {
            MaterialTheme(typography = com.example.khatabook.ui.theme.KhatabookTypography) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val prefs = remember { 
                        val masterKey = MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        EncryptedSharedPreferences.create(
                            context,
                            "khatabook_secure_prefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                    }

                    var isUserLoggedIn by remember {
                        mutableStateOf(prefs.getBoolean("is_logged_in", false))
                    }

                    // True Multi-Level Nested Backstack.
                    // rememberSaveable (not plain remember) so the user's place
                    // in the app survives process death — the far more common
                    // trigger being backgrounding under memory pressure, not
                    // just rotation — instead of always bouncing back to
                    // Parties/Language Selection.
                    val navStack = rememberSaveable(saver = navStackSaver) {
                        mutableStateListOf(
                            if (isUserLoggedIn) AppScreen.PARTIES else AppScreen.LANGUAGE_SELECTION
                        )
                    }

                    val currentScreen = navStack.lastOrNull() ?: AppScreen.PARTIES

                    fun navigateTo(screen: AppScreen) {
                        if (currentScreen != screen) {
                            if ((currentScreen == AppScreen.PARTIES && screen == AppScreen.MORE) ||
                                (currentScreen == AppScreen.MORE && screen == AppScreen.PARTIES)) {
                                navStack.clear()
                                navStack.add(screen)
                            } else {
                                navStack.add(screen)
                            }
                        }
                    }

                    fun navigateBack() {
                        if (navStack.size > 1) {
                            navStack.removeAt(navStack.size - 1)
                        } else if (currentScreen == AppScreen.MORE) {
                            // MORE is reached by clearing the stack (see navigateTo's
                            // Parties<->More tab-switch special case), so it is always
                            // alone at size 1 — the same size a truly rootless stack
                            // would be. Without this, back from the More tab fell
                            // through to the OS default and exited the app instead of
                            // returning to Parties, the actual home screen.
                            navStack.clear()
                            navStack.add(AppScreen.PARTIES)
                        } else {
                            this@MainActivity.finish()
                        }
                    }

                    // HARDWARE SYSTEM BACK HANDLER - POPS PREVIOUS SCREEN IN NESTED STACK
                    BackHandler(enabled = navStack.size > 1 || currentScreen == AppScreen.MORE) {
                        navigateBack()
                    }

                    val parties by repository.parties.collectAsState()
                    val entries by repository.ledgerEntries.collectAsState()
                    val failedOperationCount by repository.failedOperationCountFlow.collectAsState()
                    val isRestoringFromCloud by repository.isRestoringFromCloud.collectAsState()

                    fun normalizePhoneForKey(value: String): String {
                        val digits = value.replace(Regex("\\D"), "")
                        if (digits.length == 10) return digits
                        if (digits.length == 11 && digits.startsWith("0")) return digits.substring(1)
                        if (digits.length == 12 && digits.startsWith("91")) return digits.substring(2)
                        if (digits.length == 13 && digits.startsWith("091")) return digits.substring(3)
                        return digits
                    }

                    var authenticatedPhone by remember { mutableStateOf(normalizePhoneForKey(prefs.getString("auth_phone", "") ?: "")) }
                    val loggedInPhones = remember {
                        val str = prefs.getString("logged_in_phones_list", null)
                        val phones = if (str != null && str.isNotBlank()) {
                            str.split(",").map { normalizePhoneForKey(it) }.filter { it.isNotBlank() }.distinct()
                        } else {
                            if (authenticatedPhone.isNotBlank()) listOf(authenticatedPhone) else emptyList()
                        }
                        mutableStateListOf(*phones.toTypedArray())
                    }
                    var showAccountSwitcher by remember { mutableStateOf(false) }

                    // Add-staff flow. The contact picker used to just navigate
                    // back without contacting the server at all.
                    var staffContactPending by remember {
                        mutableStateOf<com.example.khatabook.ui.screens.ContactItem?>(null)
                    }
                    var staffRefreshKey by remember { mutableStateOf(0) }
                    var staffResultMessage by remember { mutableStateOf<String?>(null) }
                    // Saveable: navStack can restore straight onto OTP_VERIFICATION
                    // after process death, and this is the phone number that screen
                    // verifies against.
                    var phoneNumberToVerify by rememberSaveable { mutableStateOf("") }

                    fun userPrefKey(key: String, phone: String?): String {
                        return if (!phone.isNullOrBlank()) "${key}_$phone" else key
                    }

                    fun getUserPref(key: String, phone: String?, default: String): String {
                        val perUserKey = userPrefKey(key, phone)
                        val perUserValue = prefs.getString(perUserKey, null)
                        if (!perUserValue.isNullOrBlank()) return perUserValue
                        // Migrate legacy global value for the currently authenticated account.
                        val globalValue = prefs.getString(key, null)
                        if (!globalValue.isNullOrBlank() && phone != null && phone == prefs.getString("auth_phone", null)) {
                            prefs.edit().putString(perUserKey, globalValue).apply()
                            return globalValue
                        }
                        return prefs.getString(key, default) ?: default
                    }

                    fun setUserPref(key: String, phone: String?, value: String) {
                        if (!phone.isNullOrBlank()) {
                            prefs.edit().putString(userPrefKey(key, phone), value).apply()
                        } else {
                            prefs.edit().putString(key, value).apply()
                        }
                    }

                    LaunchedEffect(authenticatedPhone) {
                        if (authenticatedPhone.isNotBlank()) {
                            repository.switchUser(authenticatedPhone)
                        }
                    }

                    var userName by remember(authenticatedPhone) { mutableStateOf(getUserPref("user_name", authenticatedPhone, "User")) }
                    // Saveable, not plain remember: navStack itself survives process
                    // death (see navStackSaver above) and can restore straight onto
                    // CUSTOMER_LEDGER/CUSTOMER_PROFILE. A plain remember here would
                    // reset to null on that restore, silently falling back to
                    // parties.firstOrNull() below — showing whichever party happens
                    // to sort first, not the one the user actually had open, with a
                    // new transaction then posting against the wrong customer.
                    var activePartyId by rememberSaveable { mutableStateOf<String?>(null) }

                    // App Lock State
                    var isAppLockEnabled by remember(authenticatedPhone) { mutableStateOf(prefs.getBoolean("app_lock_enabled_$authenticatedPhone", false)) }
                    var appLockPin by remember(authenticatedPhone) { mutableStateOf(prefs.getString("app_lock_pin_$authenticatedPhone", "") ?: "") }
                    var isAppUnlocked by remember { mutableStateOf(false) }

                    // Re-lock on backgrounding, not only on process death. Without
                    // this, isAppUnlocked stayed true for the rest of the Activity's
                    // life once entered correctly once — App Lock's entire purpose is
                    // keeping someone else who picks up the phone out, and the Home
                    // button/app switcher (no process death) is the far more common
                    // way that happens, not a killed process.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, isAppLockEnabled) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP && isAppLockEnabled) {
                                isAppUnlocked = false
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    var showBusinessSwitcher by remember { mutableStateOf(false) }

                    // Account/business/logout transition overlay state. These three
                    // transitions each get their own overlay instance, all driven by real
                    // suspend work — not a fixed timer — and all declared at this top level
                    // (rather than inside whichever screen's `when` branch triggers them) so
                    // their LaunchedEffects survive navStack.clear() tearing down the screen
                    // that started them. A rememberCoroutineScope() tied to that screen would
                    // be cancelled the instant that happens (see
                    // KhatabookRepository.applyPendingBusinessProfile for the same failure
                    // mode caught elsewhere: a fast local PATCH can still land, but the
                    // follow-up refresh that updates on-screen state never runs).
                    val switcherScope = rememberCoroutineScope()
                    var switchingBusinessTarget by remember { mutableStateOf<BusinessAccount?>(null) }
                    var businessSwitchComplete by remember { mutableStateOf(false) }
                    var pendingBusinessLockTarget by remember { mutableStateOf<Pair<BusinessAccount, String>?>(null) }
                    var switchingAccountTarget by remember { mutableStateOf<String?>(null) }
                    var accountSwitchComplete by remember { mutableStateOf(false) }
                    var isLoggingOut by remember { mutableStateOf(false) }
                    var logoutComplete by remember { mutableStateOf(false) }

                    val transitionInProgress = switchingBusinessTarget != null || switchingAccountTarget != null || isLoggingOut

                    LaunchedEffect(switchingBusinessTarget) {
                        val biz = switchingBusinessTarget ?: return@LaunchedEffect
                        repository.switchBusiness(biz.id)
                        businessSwitchComplete = true
                    }

                    LaunchedEffect(switchingAccountTarget) {
                        val phone = switchingAccountTarget ?: return@LaunchedEffect
                        // Must happen before repository.switchUser()/loadBusinesses() below,
                        // and before any suspension point in this effect: NetworkClient's
                        // tokenProvider reads auth_phone on every single request to decide
                        // whose JWT to send, while businessIdProvider reads
                        // repository.currentBusinessId() — which switchUser() updates
                        // immediately. Writing auth_phone here too (rather than only in the
                        // overlay's onFinished, once the switch is visually complete) keeps
                        // both in lockstep for every request this effect makes. Previously
                        // there was a window where a request went out carrying the OUTGOING
                        // account's token together with the INCOMING account's business id —
                        // a pairing the backend's membership check (JwtAuthGuard) correctly
                        // rejects with 401 "You no longer have access to this business."
                        // NetworkClient's global 401 handler (see the sessionExpired
                        // LaunchedEffect near updateLoginState) then read that as "this
                        // session is dead" and deleted the OUTGOING account's own — perfectly
                        // valid — stored token, so switching accounts could silently sign a
                        // second account out and later demand OTP again to return to it.
                        prefs.edit().putString("auth_phone", phone).apply()
                        repository.switchUser(phone)
                        // Also await the network refresh, so the overlay does not clear onto
                        // an empty/stale business list for the newly-active account.
                        repository.loadBusinesses()
                        // A no-op if this account already completed a cloud restore on
                        // this device; otherwise rebuilds its local parties/ledger from
                        // PostgreSQL — an account switched into for the first time on
                        // this device must not land on an empty Parties screen.
                        repository.restoreFromCloudIfNeeded()
                        accountSwitchComplete = true
                    }

                    LaunchedEffect(isLoggingOut) {
                        if (!isLoggingOut) return@LaunchedEffect
                        // Revoke first, while the token is still present for the interceptor
                        // to send. Best effort — if offline, the local credential is cleared
                        // regardless.
                        runCatching { NetworkClient.apiService.logout() }
                        clearSession(prefs, authenticatedPhone, loggedInPhones)
                        logoutComplete = true
                    }

                    // Profile lock PIN per business, keyed by the real business
                    // UUID and persisted. It was held in a remember{} keyed by an
                    // invented id, so every lock was silently lost on process
                    // death — and keyed to a business that did not exist.
                    val profileLocksMap = remember(authenticatedPhone) {
                        val stored = mutableStateMapOf<String, String>()
                        prefs.getStringSet("profile_locks_$authenticatedPhone", emptySet())
                            ?.forEach { entry ->
                                val parts = entry.split(":", limit = 2)
                                if (parts.size == 2) stored[parts[0]] = parts[1]
                            }
                        stored
                    }

                    fun persistProfileLocks() {
                        prefs.edit().putStringSet(
                            "profile_locks_$authenticatedPhone",
                            profileLocksMap.map { "${it.key}:${it.value}" }.toSet(),
                        ).apply()
                    }

                    // Real businesses, fetched from the server. These were a
                    // hardcoded "biz_1" that corresponded to nothing, so the
                    // switcher and every per-business filter keyed off a value
                    // the backend had never heard of.
                    val businesses by repository.businesses.collectAsState()
                    val activeBusinessFromRepo by repository.activeBusiness.collectAsState()
                    val selectedBusinessId = activeBusinessFromRepo?.id ?: ""
                    val activeBusiness = activeBusinessFromRepo
                        ?: BusinessAccount(
                            id = "",
                            name = getUserPref("business_name", authenticatedPhone, "My Business"),
                        )

                    // UI-only gating: the server (RolesGuard) is the actual
                    // authority and re-checks every one of these on every
                    // request regardless of what this hides or shows. A null
                    // role (not yet loaded) is treated as permissive rather
                    // than restrictive, so a legitimate OWNER/MANAGER is
                    // never wrongly blocked while the businesses list is
                    // still loading — the worst case is identical to the
                    // status quo before this existed.
                    val currentRole = activeBusinessFromRepo?.role
                    val canManageParty = currentRole != "CASHIER"
                    val canManageLedgerEntries = currentRole != "CASHIER"
                    val canManageStaff = currentRole != "CASHIER"
                    val canManageInventory = currentRole != "CASHIER"
                    val isBusinessOwner = currentRole == null || currentRole == "OWNER"

                    // Load them once the user is known, and again when the
                    // account changes. A remembered-or-first business is
                    // already selected synchronously inside the repository's
                    // loadData() (from cache) the moment switchUser() runs, so
                    // this only needs to refresh from the network — there is
                    // no longer a separate "pick businesses.first() if nothing
                    // is selected yet" effect here: that used to race the
                    // repository's own remembered-business selection, with
                    // whichever finished last silently overriding the other.
                    LaunchedEffect(authenticatedPhone) {
                        if (authenticatedPhone.isNotBlank()) {
                            repository.loadBusinesses()
                        }
                    }

                    // App language selection (persisted per-user)
                    var selectedLanguage by remember(authenticatedPhone) {
                        val savedLangId = getUserPref("app_language", authenticatedPhone, availableLanguages[0].id)
                        mutableStateOf(availableLanguages.find { it.id == savedLangId } ?: availableLanguages[0])
                    }

                    // Saveable: navStack can restore straight onto ADD_PARTY after
                    // process death, and these are what pre-fill it from the contact
                    // picker.
                    var addPartyInitialName by rememberSaveable { mutableStateOf("") }
                    var addPartyInitialPhone by rememberSaveable { mutableStateOf("") }

                    fun updateLoginState(loggedIn: Boolean) {
                        isUserLoggedIn = loggedIn
                        prefs.edit().putBoolean("is_logged_in", loggedIn).apply()
                    }

                    // Centralized 401 handling: any endpoint (outside /auth/)
                    // refusing the current token means the session is dead —
                    // clear it and return to login from one place, rather
                    // than each screen deciding for itself what a 401 means.
                    LaunchedEffect(Unit) {
                        NetworkClient.sessionExpired.collect {
                            if (isUserLoggedIn) {
                                clearSession(prefs, authenticatedPhone, loggedInPhones)
                                updateLoginState(false)
                                navStack.clear()
                                navStack.add(AppScreen.MOBILE_AUTH)
                            }
                        }
                    }

                    if (isAppLockEnabled && !isAppUnlocked) {
                        AppLockScreen(
                            correctPin = appLockPin,
                            onUnlockSuccess = { isAppUnlocked = true }
                        )
                    } else {
                        AnimatedScreenTransition(targetState = currentScreen) { screen ->
                            when (screen) {
                                AppScreen.LANGUAGE_SELECTION -> {
                                    LanguageSelectionScreen(
                                        onLanguageSelected = { lang ->
                                            selectedLanguage = lang
                                            setUserPref("app_language", authenticatedPhone, lang.id)
                                            if (isUserLoggedIn) {
                                                navStack.clear()
                                                navStack.add(AppScreen.PARTIES)
                                            } else {
                                                // Only first-run needs the permissions screen. Without this
                                                // check, a returning user (e.g. after an explicit logout, which
                                                // clears isUserLoggedIn but not permissions_shown) saw it again
                                                // on every re-login even though Android already has their answer.
                                                val permissionsAlreadyShown = prefs.getBoolean("permissions_shown", false)
                                                if (!permissionsAlreadyShown) {
                                                    navigateTo(AppScreen.PERMISSIONS)
                                                } else {
                                                    navigateTo(AppScreen.MOBILE_AUTH)
                                                }
                                            }
                                        }
                                    )
                                }

                                AppScreen.PERMISSIONS -> {
                                    PermissionsScreen(
                                        onPermissionsHandled = {
                                            prefs.edit().putBoolean("permissions_shown", true).apply()
                                            navStack.clear()
                                            // If user already set up their details → go to Parties
                                            // If brand new user coming from Language Selection → go to Mobile Auth
                                            if (isUserLoggedIn) {
                                                navStack.add(AppScreen.PARTIES)
                                            } else {
                                                navStack.add(AppScreen.MOBILE_AUTH)
                                            }
                                        }
                                    )
                                }

                                AppScreen.MOBILE_AUTH -> {
                                    MobileAuthScreen(
                                        onBackClick = { navigateBack() },
                                        onSendOtp = { phone ->
                                            phoneNumberToVerify = phone
                                            navigateTo(AppScreen.OTP_VERIFICATION)
                                        }
                                    )
                                }

                                AppScreen.OTP_VERIFICATION -> {
                                    val coroutineScope = rememberCoroutineScope()
                                    OtpVerificationScreen(
                                        phoneNumber = phoneNumberToVerify,
                                        onBackClick = { navigateBack() },
                                        onVerifySuccess = { accessToken, isNewUser ->
                                            val normalizedPhone = normalizePhoneForKey(phoneNumberToVerify)
                                            // Persist JWT so NetworkClient picks it up immediately.
                                            // Do NOT persist `auth_phone` yet — read per-user prefs first so
                                            // legacy global values are NOT migrated into a brand-new phone.
                                            prefs.edit()
                                                .putString("jwt_token_$normalizedPhone", accessToken)
                                                .putString("jwt_token", accessToken)
                                                .apply()

                                            // Update in-memory authenticated phone and read per-user prefs
                                            // before mutating `auth_phone` in storage (prevents accidental
                                            // migration of global values belonging to a different user).
                                            authenticatedPhone = normalizedPhone
                                            userName = getUserPref("user_name", authenticatedPhone, "User")
                                            // Business names come from the server now.

                                            // Now persist the active auth phone in secure prefs
                                            prefs.edit().putString("auth_phone", normalizedPhone).apply()

                                            if (!loggedInPhones.contains(normalizedPhone)) {
                                                loggedInPhones.add(normalizedPhone)
                                                prefs.edit().putString("logged_in_phones_list", loggedInPhones.joinToString(",")).apply()
                                            }

                                            repository.switchUser(authenticatedPhone)
                                            updateLoginState(true)
                                            // Pull the latest party list from the server
                                            coroutineScope.launch {
                                                // A fresh token can turn a
                                                // previously session-expired
                                                // failure into a deliverable
                                                // one, so give those another
                                                // shot before the normal
                                                // flush, then pull, so the
                                                // merge sees an accurate
                                                // picture of what is unsynced.
                                                repository.retryFailedOperations()
                                                repository.flushOutbox()
                                                // A no-op if this account already completed
                                                // a cloud restore on this device before —
                                                // otherwise rebuilds the local database from
                                                // PostgreSQL (fresh install / cleared storage /
                                                // new device). See restoreFromCloudIfNeeded()
                                                // and PartiesScreen's isRestoringFromCloud check.
                                                repository.restoreFromCloudIfNeeded()
                                                repository.syncPartiesFromServer()
                                            }
                                            navStack.clear()
                                            val hasSavedProfile = getUserPref("user_name", authenticatedPhone, "").isNotBlank()
                                            if (isNewUser && !hasSavedProfile) {
                                                navStack.add(AppScreen.USER_DETAILS)
                                            } else {
                                                navStack.add(AppScreen.PARTIES)
                                            }
                                        }
                                    )
                                }

                                AppScreen.USER_DETAILS -> {
                                    UserDetailsScreen(
                                        onDetailsSubmitted = { name, bizName, category ->
                                            userName = name
                                            setUserPref("user_name", authenticatedPhone, name)
                                            // Persists to the server's own User.name column — the
                                            // signal AuthService.verifyOtp's is_new_user actually
                                            // checks. Without this, is_new_user stays true forever
                                            // for this account (nothing else ever wrote it), so a
                                            // reinstall/cleared app — which wipes the local
                                            // "hasSavedProfile" flag above — would route a genuine
                                            // returning user back through registration. See
                                            // applyPendingUserName's doc comment.
                                            repository.applyPendingUserName(name)
                                            updateLoginState(true)
                                            if (bizName.isNotBlank()) {
                                                // Kept for the pre-login placeholder; the
                                                // authoritative name is the server's.
                                                setUserPref("business_name", authenticatedPhone, bizName)
                                                // verifyOtp auto-provisions a business named
                                                // "My Khata Business" for new users — apply what
                                                // was actually typed here so it isn't silently
                                                // discarded. Runs on the repository's own
                                                // long-lived scope, NOT rememberCoroutineScope():
                                                // the navStack.clear() below tears down this
                                                // composable immediately, which would cancel a
                                                // UI-scoped coroutine before the post-update
                                                // refresh finishes (see applyPendingBusinessProfile).
                                                repository.applyPendingBusinessProfile(bizName, category)
                                            }
                                            navStack.clear()
                                            // Show permissions after user details if not already shown
                                            val permissionsAlreadyShown = prefs.getBoolean("permissions_shown", false)
                                            if (!permissionsAlreadyShown) {
                                                navStack.add(AppScreen.PERMISSIONS)
                                            } else {
                                                navStack.add(AppScreen.PARTIES)
                                            }
                                        }
                                    )
                                }

                                AppScreen.PARTIES -> {
                                    val partiesScope = rememberCoroutineScope()
                                    PartiesScreen(
                                        parties = parties,
                                        businesses = businesses,
                                        selectedBusinessId = selectedBusinessId,
                                        profileLocksMap = profileLocksMap,
                                        isRestoringFromCloud = isRestoringFromCloud,
                                        // Opens the existing BusinessSwitcherDialog below, which already
                                        // handles selection (with profile-lock verification) and creating
                                        // a new business — this was previously unreachable from any screen.
                                        onBusinessHeaderClick = { showBusinessSwitcher = true },
                                        onAddPartyClick = { navigateTo(AppScreen.CUSTOMER_CONTACT_PICKER) },
                                        onCashbookClick = { navigateTo(AppScreen.CASHBOOK) },
                                        onViewReportsClick = { navigateTo(AppScreen.VIEW_REPORTS) },
                                        onBusinessStatisticsClick = { navigateTo(AppScreen.BUSINESS_STATISTICS) },
                                        onMoreTabClick = { navigateTo(AppScreen.MORE) },
                                        onPartyClick = { selectedParty ->
                                            activePartyId = selectedParty.id
                                            navigateTo(AppScreen.CUSTOMER_LEDGER)
                                        },
                                        onDeleteParty = { partyId ->
                                            repository.deleteParty(partyId)
                                        },
                                        onSheetRecordBulkSync = { records, date ->
                                            records.forEach { rec ->
                                                repository.addOrUpdateSheetRecord(
                                                    customerCodeOrName = rec.customerCodeOrName,
                                                    amountCollected = rec.amountCollected,
                                                    date = date,
                                                    businessId = selectedBusinessId,
                                                    isNewCustomer = rec.isNewCustomer,
                                                    newPhone = rec.newPhone,
                                                    newAddress = rec.newAddress
                                                )
                                            }
                                        },
                                        canManageParty = canManageParty,
                                        failedOperationCount = failedOperationCount,
                                        onRetryFailedOperations = {
                                            partiesScope.launch {
                                                repository.retryFailedOperations()
                                                repository.flushOutbox()
                                            }
                                        }
                                    )
                                }

                                AppScreen.CUSTOMER_LEDGER -> {
                                    val currentParty = parties.find { it.id == activePartyId } ?: parties.firstOrNull() ?: Party(id = "cust_1", name = "Customer", phone = "+91 99999 88888")
                                    val updatedParty = parties.find { it.id == currentParty.id } ?: currentParty

                                    // syncLedgerEntriesFromServer existed but was
                                    // never called, so this screen only ever showed
                                    // whatever happened to be on this device.
                                    LaunchedEffect(updatedParty.id) {
                                        repository.flushOutbox()
                                        repository.syncLedgerEntriesFromServer(updatedParty.id)
                                    }

                                    CustomerLedgerScreen(
                                        party = updatedParty,
                                        entries = entries,
                                        onBackClick = { navigateBack() },
                                        onAddEntry = { partyId, amount, type, note ->
                                            repository.addLedgerEntry(partyId, amount, type, note)
                                        },
                                        onDeleteParty = { partyId ->
                                            repository.deleteParty(partyId)
                                        },
                                        onEditEntry = { entryId, newAmount, newNote ->
                                            repository.updateLedgerEntry(entryId, newAmount, newNote)
                                        },
                                        onDeleteEntry = { entryId ->
                                            repository.deleteLedgerEntry(entryId)
                                        },
                                        onViewProfileClick = { navigateTo(AppScreen.CUSTOMER_PROFILE) },
                                        onSendBulkReminders = {
                                            val message = "Dear ${updatedParty.name}, your total outstanding balance is ₹ ${kotlin.math.abs(updatedParty.currentBalance).toInt()}. Please clear your dues on Khatabook."
                                            SmsSender.send(context, updatedParty.phone, message, updatedParty.businessId)
                                        },
                                        canManageLedgerEntries = canManageLedgerEntries
                                    )
                                }

                                AppScreen.CUSTOMER_PROFILE -> {
                                    val currentParty = parties.find { it.id == activePartyId } ?: parties.firstOrNull() ?: Party(id = "cust_1", name = "Customer", phone = "+91 99999 88888")
                                    val updatedParty = parties.find { it.id == currentParty.id } ?: currentParty

                                    com.example.khatabook.ui.screens.CustomerProfileScreen(
                                        party = updatedParty,
                                        onBackClick = { navigateBack() },
                                        onDeleteParty = { partyId ->
                                            repository.deleteParty(partyId)
                                            navStack.removeAll { it == AppScreen.CUSTOMER_PROFILE || it == AppScreen.CUSTOMER_LEDGER }
                                            if (navStack.isEmpty()) navStack.add(AppScreen.PARTIES)
                                        },
                                        onUpdateInterest = { enabled, rate, periodValue, unit ->
                                            repository.updatePartyInterest(updatedParty.id, enabled, rate, periodValue, unit)
                                        },
                                        onApplyInterest = {
                                            repository.applyInterest(updatedParty.id)?.amount
                                        },
                                        canManageParty = canManageParty
                                    )
                                }

                                AppScreen.CUSTOMER_CONTACT_PICKER -> {
                                    CustomerContactPickerScreen(
                                        onBackClick = { navigateBack() },
                                        onAddCustomerManualClick = { 
                                            addPartyInitialName = ""
                                            addPartyInitialPhone = ""
                                            navigateTo(AppScreen.ADD_PARTY) 
                                        },
                                        onContactSelectConfirmed = { contact, _, _ ->
                                            addPartyInitialName = contact.name
                                            addPartyInitialPhone = contact.phone
                                            navigateTo(AppScreen.ADD_PARTY)
                                        }
                                    )
                                }

                                AppScreen.ADD_PARTY -> {
                                    AddPartyScreen(
                                        initialName = addPartyInitialName,
                                        initialPhone = addPartyInitialPhone,
                                        existingParties = parties.filter { it.businessId == selectedBusinessId },
                                        onBackClick = { navigateBack() },
                                        onSaveParty = { name, phone, type, freq, gstin, flat, area, pincode, city, state, isSame,
                                                        interestEnabled, interestRate, interestPeriodValue, interestPeriodUnit ->
                                            repository.addParty(
                                                name = name,
                                                phone = phone,
                                                type = type,
                                                address = flat,
                                                frequency = freq,
                                                businessId = selectedBusinessId,
                                                interestEnabled = interestEnabled,
                                                interestRate = interestRate,
                                                interestPeriodValue = interestPeriodValue,
                                                interestPeriodUnit = interestPeriodUnit
                                            )
                                            navStack.clear()
                                            navStack.add(AppScreen.PARTIES)
                                        }
                                    )
                                }

                                AppScreen.CASHBOOK -> {
                                    CashbookScreen(
                                        onBackClick = { navigateBack() },
                                        parties = parties.filter { it.businessId == selectedBusinessId },
                                        onAddEntry = { partyId, amount, type, note ->
                                            repository.addLedgerEntry(partyId, amount, type, note)
                                        },
                                        onViewReportClick = { navigateTo(AppScreen.CASHBOOK_REPORT) },
                                        repository = repository
                                    )
                                }

                                AppScreen.CASHBOOK_REPORT -> {
                                    CashbookReportScreen(
                                        onBackClick = { navigateBack() },
                                        businessName = activeBusiness.name,
                                        repository = repository
                                    )
                                }

                                AppScreen.VIEW_REPORTS -> {
                                    ViewReportsScreen(
                                        onBackClick = { navigateBack() },
                                        repository = repository
                                    )
                                }

                                AppScreen.BUSINESS_STATISTICS -> {
                                    BusinessStatisticsScreen(
                                        onBackClick = { navigateBack() },
                                        repository = repository
                                    )
                                }

                                AppScreen.MORE -> {
                                    MoreScreen(
                                        onPartiesTabClick = { navigateTo(AppScreen.PARTIES) },
                                        onCashbookClick = { navigateTo(AppScreen.CASHBOOK) },
                                        onStaffClick = { navigateTo(AppScreen.ADD_STAFF) },
                                        onCollectionClick = { navigateTo(AppScreen.SET_COLLECTION_DATE) },
                                        onSettingsClick = { navigateTo(AppScreen.SETTINGS) },
                                        onSmsSettingsClick = { navigateTo(AppScreen.SMS_SETTINGS) },
                                        onSwitchAccountClick = { showAccountSwitcher = true },
                                        onChangeLanguageClick = { navigateTo(AppScreen.LANGUAGE_SELECTION) },
                                        onEditProfileClick = { navigateTo(AppScreen.EDIT_PROFILE) },
                                        onBillsClick = { navigateTo(AppScreen.BILLS) },
                                        onItemsClick = { navigateTo(AppScreen.ITEMS) },
                                        onHelpSupportClick = { navigateTo(AppScreen.HELP_SUPPORT) },
                                        onAboutUsClick = { navigateTo(AppScreen.ABOUT_US) },
                                        businessName = activeBusiness.name,
                                        userName = userName,
                                        phoneNumber = authenticatedPhone,
                                        selectedLanguageName = selectedLanguage.nameInNative,
                                        canManageStaff = canManageStaff
                                    )
                                }

                                AppScreen.SETTINGS -> {
                                    SettingsScreen(
                                        onBackClick = { navigateBack() },
                                        onSmsSettingsClick = { navigateTo(AppScreen.SMS_SETTINGS) },
                                        onPaymentSettingsClick = { },
                                        onRecycleBinClick = { navigateTo(AppScreen.RECYCLE_BIN) },
                                        onBackupInfoClick = { navigateTo(AppScreen.BACKUP_INFO) },
                                        onDeleteKhataClick = { navigateTo(AppScreen.DELETE_KHATA) },
                                        onChangeLanguageClick = { navigateTo(AppScreen.LANGUAGE_SELECTION) },
                                        onHelpSupportClick = { navigateTo(AppScreen.HELP_SUPPORT) },
                                        onAboutUsClick = { navigateTo(AppScreen.ABOUT_US) },
                                        canDeleteBusiness = isBusinessOwner,
                                        onLogoutClick = {
                                            // Logout previously only flipped a local flag: the JWT
                                            // stayed in encrypted prefs and stayed valid server-side
                                            // for its full 7 days, so a "logged out" phone was still
                                            // a working credential. The actual revoke + session clear
                                            // now runs in the top-level isLoggingOut LaunchedEffect
                                            // (see below), on the repository/app's own lifetime, not
                                            // this screen's — guarded here against a second tap.
                                            if (!isLoggingOut) {
                                                logoutComplete = false
                                                isLoggingOut = true
                                            }
                                        },
                                        isAppLockEnabled = isAppLockEnabled,
                                        onToggleAppLock = { enabled, pin ->
                                            isAppLockEnabled = enabled
                                            prefs.edit().putBoolean("app_lock_enabled_$authenticatedPhone", enabled).apply()
                                            if (pin != null) {
                                                appLockPin = pin
                                                prefs.edit().putString("app_lock_pin_$authenticatedPhone", pin).apply()
                                            }
                                        },
                                        isProfileLockEnabled = profileLocksMap.containsKey(selectedBusinessId),
                                        profileLockPin = profileLocksMap[selectedBusinessId] ?: "",
                                        onToggleProfileLock = { enabled, pin ->
                                            if (selectedBusinessId.isNotBlank()) {
                                                if (enabled && pin != null) {
                                                    profileLocksMap[selectedBusinessId] = pin
                                                } else {
                                                    profileLocksMap.remove(selectedBusinessId)
                                                }
                                                persistProfileLocks()
                                            }
                                        }
                                    )
                                }

                                AppScreen.SMS_SETTINGS -> {
                                    SmsSettingsScreen(
                                        onBackClick = { navigateBack() },
                                        repository = repository
                                    )
                                }

                                AppScreen.RECYCLE_BIN -> {
                                    RecycleBinScreen(
                                        onBackClick = { navigateBack() }
                                    )
                                }

                                AppScreen.BACKUP_INFO -> {
                                    BackupInfoScreen(
                                        onBackClick = { navigateBack() }
                                    )
                                }

                                AppScreen.DELETE_KHATA -> {
                                    DeleteKhataScreen(
                                        businessName = activeBusiness.name,
                                        onBackClick = { navigateBack() },
                                        onDeleteConfirmed = {
                                            navigateBack()
                                        }
                                    )
                                }

                                AppScreen.ADD_STAFF -> {
                                    AddStaffScreen(
                                        onBackClick = { navigateBack() },
                                        onAddStaffClick = { navigateTo(AppScreen.STAFF_CONTACT_PICKER) },
                                        refreshKey = staffRefreshKey,
                                        repository = repository
                                    )
                                }

                                AppScreen.STAFF_CONTACT_PICKER -> {
                                    StaffContactPickerScreen(
                                        onBackClick = { navigateBack() },
                                        onContactSelect = { contact ->
                                            // Choose a role before sending: the
                                            // server accepts MANAGER or CASHIER
                                            // only, and refuses OWNER outright.
                                            staffContactPending = contact
                                        }
                                    )
                                }

                                AppScreen.SET_COLLECTION_DATE -> {
                                    SetCollectionDateScreen(
                                        parties = parties,
                                        onBackClick = { navigateBack() }
                                    )
                                }
                                
                                AppScreen.BILLS -> {
                                    BillsScreen(
                                        onBackClick = { navigateBack() },
                                        parties = parties.filter { it.businessId == selectedBusinessId },
                                        repository = repository
                                    )
                                }

                                AppScreen.ITEMS -> {
                                    ItemsScreen(
                                        onBackClick = { navigateBack() },
                                        canManageInventory = canManageInventory,
                                        repository = repository
                                    )
                                }
                                
                                AppScreen.HELP_SUPPORT -> {
                                    HelpSupportScreen(onBackClick = { navigateBack() })
                                }
                                
                                AppScreen.ABOUT_US -> {
                                    AboutUsScreen(onBackClick = { navigateBack() })
                                }
                                
                                AppScreen.EDIT_PROFILE -> {
                                    BookProfileScreen(
                                        onBackClick = { navigateBack() },
                                        repository = repository
                                    )
                                }
                            }
                        }
                    }

                    // Instagram-style Business Switcher Dialog Modal
                    if (showBusinessSwitcher) {
                        BusinessSwitcherDialog(
                            businesses = businesses,
                            activeBusinessId = selectedBusinessId,
                            onBusinessSelect = { biz ->
                                showBusinessSwitcher = false
                                // Guards against a second tap landing while a switch/lock
                                // check from the first tap is still in flight.
                                if (biz.id != selectedBusinessId && !transitionInProgress && pendingBusinessLockTarget == null) {
                                    val profilePin = profileLocksMap[biz.id]
                                    if (profilePin != null) {
                                        // Profile lock is active → require PIN verification
                                        pendingBusinessLockTarget = Pair(biz, profilePin)
                                    } else {
                                        // No profile lock set → switch directly, no dialog
                                        businessSwitchComplete = false
                                        switchingBusinessTarget = biz
                                    }
                                }
                            },
                            onCreateNewBusiness = { name ->
                                showBusinessSwitcher = false
                                switcherScope.launch {
                                    val created = repository.createBusiness(name, "Retail Store")
                                    if (created != null && !transitionInProgress) {
                                        businessSwitchComplete = false
                                        switchingBusinessTarget = created
                                    }
                                }
                            },
                            onDismiss = { showBusinessSwitcher = false }
                        )
                    }

                    if (pendingBusinessLockTarget != null) {
                        ProfileLockVerificationDialog(
                            targetBusinessName = pendingBusinessLockTarget!!.first.name,
                            correctPin = pendingBusinessLockTarget!!.second,
                            onPinSuccess = {
                                val biz = pendingBusinessLockTarget!!.first
                                pendingBusinessLockTarget = null
                                if (!transitionInProgress) {
                                    businessSwitchComplete = false
                                    switchingBusinessTarget = biz
                                }
                            },
                            onDismiss = {
                                pendingBusinessLockTarget = null
                            }
                        )
                    }

                    if (switchingBusinessTarget != null) {
                        BusinessSwitchingOverlay(
                            mode = com.example.khatabook.ui.components.AccountTransitionMode.BUSINESS_SWITCH,
                            targetLabel = switchingBusinessTarget!!.name,
                            isOperationComplete = businessSwitchComplete,
                            onFinished = {
                                switchingBusinessTarget = null
                                businessSwitchComplete = false
                            }
                        )
                    }

                    if (switchingAccountTarget != null) {
                        BusinessSwitchingOverlay(
                            mode = com.example.khatabook.ui.components.AccountTransitionMode.ACCOUNT_SWITCH,
                            targetLabel = switchingAccountTarget!!,
                            isOperationComplete = accountSwitchComplete,
                            onFinished = {
                                val phone = switchingAccountTarget!!
                                // authenticatedPhone (the *visible* identity) is applied only
                                // now, once the new account's data is already loaded behind
                                // this still-visible overlay — so the screen underneath never
                                // shows a frame with the old account's data under the new
                                // account's identity. auth_phone itself (what selects which
                                // JWT NetworkClient sends) was already written before this
                                // effect's network calls ran — see the switchingAccountTarget
                                // LaunchedEffect above — so it is not repeated here.
                                authenticatedPhone = phone
                                navStack.clear()
                                navStack.add(AppScreen.PARTIES)
                                switchingAccountTarget = null
                                accountSwitchComplete = false
                            }
                        )
                    }

                    if (isLoggingOut) {
                        BusinessSwitchingOverlay(
                            mode = com.example.khatabook.ui.components.AccountTransitionMode.LOGOUT,
                            targetLabel = "",
                            isOperationComplete = logoutComplete,
                            onFinished = {
                                authenticatedPhone = ""
                                updateLoginState(false)
                                this@MainActivity.finish()
                            }
                        )
                    }

                    staffContactPending?.let { contact ->
                        val staffScope = rememberCoroutineScope()
                        AlertDialog(
                            onDismissRequest = { staffContactPending = null },
                            title = { Text("Add ${contact.name} as staff") },
                            text = {
                                Text(
                                    "Managers can view reports and manage staff. " +
                                        "Cashiers can record transactions only."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    staffContactPending = null
                                    staffScope.launch {
                                        staffResultMessage = addStaffMember(contact, "MANAGER")
                                        staffRefreshKey++
                                    }
                                    navigateBack()
                                }) { Text("MANAGER") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    staffContactPending = null
                                    staffScope.launch {
                                        staffResultMessage = addStaffMember(contact, "CASHIER")
                                        staffRefreshKey++
                                    }
                                    navigateBack()
                                }) { Text("CASHIER") }
                            },
                        )
                    }

                    staffResultMessage?.let { message ->
                        LaunchedEffect(message) {
                            android.widget.Toast
                                .makeText(context, message, android.widget.Toast.LENGTH_LONG)
                                .show()
                            staffResultMessage = null
                        }
                    }

                    if (showAccountSwitcher) {
                        MultiSessionAccountSwitcherDialog(
                            phoneNumbers = loggedInPhones,
                            // "" default (not getUserPref's own "User" default) so a
                            // truly-unset name is unambiguous to the dialog, which
                            // applies its own "User Account" fallback — a real user
                            // literally named "User" is otherwise indistinguishable
                            // from "nothing was ever saved for this account".
                            accountNames = loggedInPhones.associateWith { phone -> getUserPref("user_name", phone, "") },
                            activePhoneNumber = authenticatedPhone,
                            onPhoneSelect = { phone ->
                                showAccountSwitcher = false
                                // Guarded against a second tap while the previous selection is
                                // still switching. authenticatedPhone/navStack are applied by
                                // the account-switch overlay's onFinished, only once the new
                                // account's data has actually loaded (see switchingAccountTarget
                                // above) — not here, and not before that finishes.
                                if (phone != authenticatedPhone && !transitionInProgress) {
                                    accountSwitchComplete = false
                                    switchingAccountTarget = phone
                                }
                            },
                            onAddNewAccount = {
                                navStack.add(AppScreen.MOBILE_AUTH)
                            },
                            onDismiss = { showAccountSwitcher = false }
                        )
                    }
                }
            }
        }
    }
}
