package com.example.khatabook

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.ui.components.BusinessSwitcherDialog
import com.example.khatabook.ui.components.BusinessSwitchingOverlay
import com.example.khatabook.ui.components.MultiSessionAccountSwitcherDialog
import com.example.khatabook.ui.components.AnimatedScreenTransition
import com.example.khatabook.ui.components.ProfileLockVerificationDialog

import com.example.khatabook.ui.screens.AboutUsScreen
import com.example.khatabook.ui.screens.AddPartyScreen
import com.example.khatabook.ui.screens.AddStaffScreen
import com.example.khatabook.ui.screens.AppLanguage
import com.example.khatabook.ui.screens.AppLockScreen
import com.example.khatabook.ui.screens.BackupInfoScreen
import com.example.khatabook.ui.screens.BillsScreen
import com.example.khatabook.ui.screens.BusinessStatisticsScreen
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
import com.example.khatabook.ui.screens.SingleCustomerReportScreen
import com.example.khatabook.ui.screens.SmsSettingsScreen
import com.example.khatabook.ui.screens.StaffContactPickerScreen
import com.example.khatabook.ui.screens.UserDetailsScreen
import com.example.khatabook.ui.screens.ViewReportsScreen
import com.example.khatabook.ui.screens.availableLanguages
import kotlinx.coroutines.launch

enum class AppScreen {
    LANGUAGE_SELECTION,
    PERMISSIONS,
    MOBILE_AUTH,
    OTP_VERIFICATION,
    USER_DETAILS,
    PARTIES,
    CUSTOMER_LEDGER,
    CUSTOMER_CONTACT_PICKER,
    ADD_PARTY,
    CASHBOOK,
    VIEW_REPORTS,
    BUSINESS_STATISTICS,
    SINGLE_CUSTOMER_REPORT,
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

class MainActivity : AppCompatActivity() {

    private lateinit var repository: KhatabookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent screenshots and screen recording
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        repository = KhatabookRepository(applicationContext)

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
        NetworkClient.init { 
            val activePhone = securePrefs.getString("auth_phone", null)
            if (activePhone != null) {
                securePrefs.getString("jwt_token_$activePhone", securePrefs.getString("jwt_token", null))
            } else {
                securePrefs.getString("jwt_token", null)
            }
        }
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

                    // True Multi-Level Nested Backstack
                    val navStack = remember {
                        mutableStateListOf<AppScreen>(
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
                        }
                    }

                    // HARDWARE SYSTEM BACK HANDLER - POPS PREVIOUS SCREEN IN NESTED STACK
                    BackHandler(enabled = navStack.size > 1) {
                        navigateBack()
                    }

                    val parties by repository.parties.collectAsState()
                    val entries by repository.ledgerEntries.collectAsState()

                    var selectedLanguage by remember { mutableStateOf<AppLanguage>(availableLanguages[0]) }
                    var authenticatedPhone by remember { mutableStateOf(prefs.getString("auth_phone", "") ?: "") }
                    val loggedInPhones = remember {
                        val str = prefs.getString("logged_in_phones_list", null)
                        val phones = if (str != null && str.isNotBlank()) {
                            str.split(",")
                        } else {
                            if (authenticatedPhone.isNotBlank()) listOf(authenticatedPhone) else emptyList()
                        }
                        mutableStateListOf(*phones.toTypedArray())
                    }
                    var showAccountSwitcher by remember { mutableStateOf(false) }
                    var phoneNumberToVerify by remember { mutableStateOf("") }
                    var firebaseVerificationId by remember { mutableStateOf("") }

                    LaunchedEffect(authenticatedPhone) {
                        if (authenticatedPhone.isNotBlank()) {
                            repository.switchUser(authenticatedPhone)
                        }
                    }

                    var userName by remember { mutableStateOf(prefs.getString("user_name", "User") ?: "User") }
                    var activePartyForLedger by remember { mutableStateOf<Party?>(null) }

                    // App Lock State
                    var isAppLockEnabled by remember { mutableStateOf(prefs.getBoolean("app_lock_enabled", false)) }
                    var appLockPin by remember { mutableStateOf(prefs.getString("app_lock_pin", "1234") ?: "1234") }
                    var isAppUnlocked by remember { mutableStateOf(false) }

                    var activeBusinessId by remember { mutableStateOf("biz_1") }
                    var showBusinessSwitcher by remember { mutableStateOf(false) }

                    // Profile Lock PIN Map per Business (bizId -> PIN)
                    val profileLocksMap = remember { mutableStateMapOf<String, String>() }

                    // Multiple Business Accounts
                    val businesses = remember {
                        mutableStateListOf(
                            BusinessAccount("biz_1", prefs.getString("business_name", "My Business") ?: "My Business", "Retail Store", true)
                        )
                    }
                    var selectedBusinessId by remember { mutableStateOf("biz_1") }
                    val activeBusiness = remember(businesses, selectedBusinessId) {
                        businesses.find { it.id == selectedBusinessId } ?: businesses.first()
                    }

                    fun updateLoginState(loggedIn: Boolean) {
                        isUserLoggedIn = loggedIn
                        prefs.edit().putBoolean("is_logged_in", loggedIn).apply()
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
                                            if (isUserLoggedIn) {
                                                navStack.clear()
                                                navStack.add(AppScreen.PARTIES)
                                            } else {
                                                // Show permissions onboarding before mobile auth
                                                navigateTo(AppScreen.PERMISSIONS)
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
                                        onSendOtp = { phone, verId ->
                                            phoneNumberToVerify = phone
                                            firebaseVerificationId = verId
                                            navigateTo(AppScreen.OTP_VERIFICATION)
                                        }
                                    )
                                }

                                AppScreen.OTP_VERIFICATION -> {
                                    val coroutineScope = rememberCoroutineScope()
                                    OtpVerificationScreen(
                                        phoneNumber = phoneNumberToVerify,
                                        verificationId = firebaseVerificationId,
                                        onBackClick = { navigateBack() },
                                        onVerifySuccess = { accessToken, isNewUser ->
                                            // Persist JWT so NetworkClient picks it up immediately
                                            prefs.edit()
                                                .putString("jwt_token_$phoneNumberToVerify", accessToken)
                                                .putString("jwt_token", accessToken)
                                                .putString("auth_phone", phoneNumberToVerify)
                                                .apply()
                                            
                                            authenticatedPhone = phoneNumberToVerify

                                            if (!loggedInPhones.contains(phoneNumberToVerify)) {
                                                loggedInPhones.add(phoneNumberToVerify)
                                                prefs.edit().putString("logged_in_phones_list", loggedInPhones.joinToString(",")).apply()
                                            }

                                            repository.switchUser(phoneNumberToVerify)
                                            updateLoginState(true)
                                            // Pull the latest party list from the server
                                            coroutineScope.launch {
                                                repository.syncPartiesFromServer()
                                            }
                                            navStack.clear()
                                            if (isNewUser) {
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
                                            prefs.edit().putString("user_name", name).apply()
                                            updateLoginState(true)
                                            if (bizName.isNotBlank()) {
                                                businesses[0] = businesses[0].copy(name = bizName)
                                                val b = businesses[0]
                                                businesses[0] = b.copy(name = bizName)
                                                prefs.edit().putString("business_name", bizName).apply()
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
                                    PartiesScreen(
                                        parties = parties,
                                        businesses = businesses,
                                        selectedBusinessId = selectedBusinessId,
                                        profileLocksMap = profileLocksMap,
                                        onBusinessSelected = { biz ->
                                            selectedBusinessId = biz.id
                                        },
                                        onAddNewBusinessClick = {
                                            val newBizId = "biz_${businesses.size + 1}"
                                            val newBiz = BusinessAccount(newBizId, "Shop ${businesses.size + 1} (New Store)")
                                            businesses.add(newBiz)
                                            selectedBusinessId = newBizId
                                        },
                                        onAddPartyClick = { navigateTo(AppScreen.CUSTOMER_CONTACT_PICKER) },
                                        onCashbookClick = { navigateTo(AppScreen.CASHBOOK) },
                                        onViewReportsClick = { navigateTo(AppScreen.VIEW_REPORTS) },
                                        onBusinessStatisticsClick = { navigateTo(AppScreen.BUSINESS_STATISTICS) },
                                        onMoreTabClick = { navigateTo(AppScreen.MORE) },
                                        onPartyClick = { selectedParty ->
                                            activePartyForLedger = selectedParty
                                            navigateTo(AppScreen.CUSTOMER_LEDGER)
                                        },
                                        onDeleteParty = { partyId ->
                                            repository.deleteParty(partyId)
                                        },
                                        onSheetRecordBulkSync = { records, date ->
                                            records.forEach { rec ->
                                                repository.addOrUpdateSheetRecord(rec.customerCodeOrName, rec.amountCollected, date, selectedBusinessId)
                                            }
                                        }
                                    )
                                }

                                AppScreen.CUSTOMER_LEDGER -> {
                                    val currentParty = activePartyForLedger ?: parties.firstOrNull() ?: Party(id = "cust_1", name = "Customer", phone = "+91 99999 88888")
                                    val updatedParty = parties.find { it.id == currentParty.id } ?: currentParty

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
                                        }
                                    )
                                }

                                AppScreen.CUSTOMER_CONTACT_PICKER -> {
                                    CustomerContactPickerScreen(
                                        onBackClick = { navigateBack() },
                                        onAddCustomerManualClick = { navigateTo(AppScreen.ADD_PARTY) },
                                        onContactSelectConfirmed = { contact, frequency, amountGiven ->
                                            val newParty = repository.addParty(
                                                name = contact.name,
                                                phone = contact.phone,
                                                type = PartyType.CUSTOMER,
                                                frequency = frequency,
                                                businessId = selectedBusinessId
                                            )
                                            if (amountGiven > 0) {
                                                repository.addLedgerEntry(newParty.id, amountGiven, TransactionType.GAVE, "Opening Balance Given")
                                            }
                                            navStack.clear()
                                            navStack.add(AppScreen.PARTIES)
                                        }
                                    )
                                }

                                AppScreen.ADD_PARTY -> {
                                    AddPartyScreen(
                                        onBackClick = { navigateBack() },
                                        onSaveParty = { name, phone, type, freq, gstin, flat, area, pincode, city, state, isSame ->
                                            repository.addParty(
                                                name = name,
                                                phone = phone,
                                                type = type,
                                                address = flat,
                                                frequency = freq,
                                                businessId = selectedBusinessId
                                            )
                                            navStack.clear()
                                            navStack.add(AppScreen.PARTIES)
                                        }
                                    )
                                }

                                AppScreen.CASHBOOK -> {
                                    CashbookScreen(
                                        onBackClick = { navigateBack() }
                                    )
                                }

                                AppScreen.VIEW_REPORTS -> {
                                    ViewReportsScreen(
                                        onBackClick = { navigateBack() }
                                    )
                                }

                                AppScreen.BUSINESS_STATISTICS -> {
                                    BusinessStatisticsScreen(
                                        onBackClick = { navigateBack() }
                                    )
                                }

                                AppScreen.SINGLE_CUSTOMER_REPORT -> {
                                    if (activePartyForLedger != null) {
                                        val currentParty = parties.find { it.id == activePartyForLedger?.id } ?: activePartyForLedger!!
                                        SingleCustomerReportScreen(
                                            party = currentParty,
                                            entries = entries,
                                            onBackClick = { navigateBack() }
                                        )
                                    } else {
                                        navigateBack()
                                    }
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
                                        onReAuthenticateClick = { navigateTo(AppScreen.MOBILE_AUTH) },
                                        onEditProfileClick = { navigateTo(AppScreen.EDIT_PROFILE) },
                                        onBillsClick = { navigateTo(AppScreen.BILLS) },
                                        onItemsClick = { navigateTo(AppScreen.ITEMS) },
                                        onHelpSupportClick = { navigateTo(AppScreen.HELP_SUPPORT) },
                                        onAboutUsClick = { navigateTo(AppScreen.ABOUT_US) },
                                        businessName = activeBusiness.name,
                                        selectedLanguageName = selectedLanguage.nameInNative
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
                                        onLogoutClick = {
                                            updateLoginState(false)
                                            this@MainActivity.finish()
                                        },
                                        isAppLockEnabled = isAppLockEnabled,
                                        onToggleAppLock = { enabled, pin ->
                                            isAppLockEnabled = enabled
                                            prefs.edit().putBoolean("app_lock_enabled", enabled).apply()
                                            if (pin != null) {
                                                appLockPin = pin
                                                prefs.edit().putString("app_lock_pin", pin).apply()
                                            }
                                        },
                                        isProfileLockEnabled = profileLocksMap.containsKey(selectedBusinessId),
                                        profileLockPin = profileLocksMap[selectedBusinessId] ?: "",
                                        onToggleProfileLock = { enabled, pin ->
                                            if (enabled && pin != null) {
                                                profileLocksMap[selectedBusinessId] = pin
                                            } else {
                                                profileLocksMap.remove(selectedBusinessId)
                                            }
                                        }
                                    )
                                }

                                AppScreen.SMS_SETTINGS -> {
                                    SmsSettingsScreen(
                                        onBackClick = { navigateBack() }
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
                                        onAddStaffClick = { navigateTo(AppScreen.STAFF_CONTACT_PICKER) }
                                    )
                                }

                                AppScreen.STAFF_CONTACT_PICKER -> {
                                    StaffContactPickerScreen(
                                        onBackClick = { navigateBack() },
                                        onContactSelect = { contact ->
                                            navigateBack()
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
                                    BillsScreen(onBackClick = { navigateBack() })
                                }
                                
                                AppScreen.ITEMS -> {
                                    ItemsScreen(onBackClick = { navigateBack() })
                                }
                                
                                AppScreen.HELP_SUPPORT -> {
                                    HelpSupportScreen(onBackClick = { navigateBack() })
                                }
                                
                                AppScreen.ABOUT_US -> {
                                    AboutUsScreen(onBackClick = { navigateBack() })
                                }
                                
                                AppScreen.EDIT_PROFILE -> {
                                    BookProfileScreen(
                                        onBackClick = { navStack.removeLast() }
                                    )
                                }
                            }
                        }
                    }

                    // Account Switching Overlay state for Instagram-style account switcher
                    var switchingBusinessTarget by remember { mutableStateOf<BusinessAccount?>(null) }
                    var pendingBusinessLockTarget by remember { mutableStateOf<Pair<BusinessAccount, String>?>(null) }

                    // Instagram-style Business Switcher Dialog Modal
                    if (showBusinessSwitcher) {
                        BusinessSwitcherDialog(
                            businesses = businesses,
                            activeBusinessId = activeBusinessId,
                            onBusinessSelect = { biz ->
                                showBusinessSwitcher = false
                                if (biz.id != activeBusinessId) {
                                    val profilePin = profileLocksMap[biz.id]
                                    if (profilePin != null) {
                                        // Profile lock is active → require PIN verification
                                        pendingBusinessLockTarget = Pair(biz, profilePin)
                                    } else {
                                        // No profile lock set → switch directly, no dialog
                                        switchingBusinessTarget = biz
                                    }
                                }
                            },
                            onCreateNewBusiness = { name ->
                                showBusinessSwitcher = false
                                val nextId = businesses.size + 1
                                val newBizId = "biz_$nextId"
                                val newBiz = BusinessAccount(newBizId, name, "Retail Store", false)
                                businesses.add(newBiz)
                                switchingBusinessTarget = newBiz
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
                                switchingBusinessTarget = biz
                            },
                            onDismiss = {
                                pendingBusinessLockTarget = null
                            }
                        )
                    }

                    if (switchingBusinessTarget != null) {
                        BusinessSwitchingOverlay(
                            targetBusinessName = switchingBusinessTarget!!.name,
                            onAnimationComplete = {
                                val biz = switchingBusinessTarget!!
                                switchingBusinessTarget = null
                                activeBusinessId = biz.id
                                selectedBusinessId = biz.id
                            }
                        )
                    }

                    if (showAccountSwitcher) {
                        MultiSessionAccountSwitcherDialog(
                            phoneNumbers = loggedInPhones,
                            activePhoneNumber = authenticatedPhone,
                            onPhoneSelect = { phone ->
                                authenticatedPhone = phone
                                prefs.edit().putString("auth_phone", phone).apply()
                                repository.switchUser(phone)
                                navStack.clear()
                                navStack.add(AppScreen.PARTIES)
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
