package com.example.khatabook.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.khatabook.core.session.SessionManager
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.data.repository.StaffRepository
import com.example.khatabook.navigation.AppNavigator
import com.example.khatabook.navigation.AppScreen
import com.example.khatabook.navigation.rememberAppNavigator
import com.example.khatabook.ui.components.AccountTransitionMode
import com.example.khatabook.ui.components.AnimatedScreenTransition
import com.example.khatabook.ui.components.BusinessSwitcherDialog
import com.example.khatabook.ui.components.BusinessSwitchingOverlay
import com.example.khatabook.ui.components.MultiSessionAccountSwitcherDialog
import com.example.khatabook.ui.components.ProfileLockVerificationDialog
import com.example.khatabook.ui.screens.*
import com.example.khatabook.utils.SmsSender
import kotlinx.coroutines.launch

/**
 * Root composable hosting navigation, transition overlays, authentication flow,
 * and top-level modals for Open Khata.
 */
@Composable
fun MainApp(
    repository: KhatabookRepository,
    sessionManager: SessionManager,
    staffRepository: StaffRepository,
    onFinish: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current

        var isUserLoggedIn by remember {
            mutableStateOf(sessionManager.isUserLoggedIn())
        }

        fun updateLoginState(loggedIn: Boolean) {
            isUserLoggedIn = loggedIn
            sessionManager.setUserLoggedIn(loggedIn)
        }

        val navigator = rememberAppNavigator(
            initialScreen = if (isUserLoggedIn) AppScreen.PARTIES else AppScreen.LANGUAGE_SELECTION
        )
        val currentScreen = navigator.currentScreen

        BackHandler(enabled = navigator.navStack.size > 1 || currentScreen == AppScreen.MORE) {
            navigator.navigateBack(onFinish)
        }

        val parties by repository.parties.collectAsState()
        val entries by repository.ledgerEntries.collectAsState()
        val failedOperationCount by repository.failedOperationCountFlow.collectAsState()
        val isRestoringFromCloud by repository.isRestoringFromCloud.collectAsState()
        val restoreFailed by repository.restoreFailed.collectAsState()

        var authenticatedPhone by remember {
            mutableStateOf(sessionManager.getActivePhone())
        }

        val loggedInPhones = remember {
            mutableStateListOf(*sessionManager.getLoggedInPhones().toTypedArray())
        }
        var showAccountSwitcher by remember { mutableStateOf(false) }

        var staffContactPending by remember { mutableStateOf<ContactItem?>(null) }
        var staffRefreshKey by remember { mutableStateOf(0) }
        var staffResultMessage by remember { mutableStateOf<String?>(null) }
        var phoneNumberToVerify by rememberSaveable { mutableStateOf("") }

        LaunchedEffect(authenticatedPhone) {
            if (authenticatedPhone.isNotBlank()) {
                repository.switchUser(authenticatedPhone)
            }
        }

        var userName by remember(authenticatedPhone) {
            mutableStateOf(sessionManager.getUserPref("user_name", authenticatedPhone, "User"))
        }
        var activePartyId by rememberSaveable { mutableStateOf<String?>(null) }

        // App Lock State
        var isAppLockEnabled by remember(authenticatedPhone) {
            mutableStateOf(sessionManager.isAppLockEnabled(authenticatedPhone))
        }
        var appLockPin by remember(authenticatedPhone) {
            mutableStateOf(sessionManager.getAppLockPin(authenticatedPhone))
        }
        var isAppUnlocked by remember { mutableStateOf(false) }

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
            sessionManager.setActivePhone(phone)
            repository.switchUser(phone)
            repository.loadBusinesses()
            repository.restoreFromCloudIfNeeded()
            accountSwitchComplete = true
        }

        LaunchedEffect(isLoggingOut) {
            if (!isLoggingOut) return@LaunchedEffect
            runCatching { NetworkClient.apiService.logout() }
            sessionManager.clearSession(authenticatedPhone, loggedInPhones)
            logoutComplete = true
        }

        val profileLocksMap = remember(authenticatedPhone) {
            val stored = mutableStateMapOf<String, String>()
            sessionManager.prefs.getStringSet("profile_locks_$authenticatedPhone", emptySet())
                ?.forEach { entry ->
                    val parts = entry.split(":", limit = 2)
                    if (parts.size == 2) stored[parts[0]] = parts[1]
                }
            stored
        }

        fun persistProfileLocks() {
            sessionManager.prefs.edit().putStringSet(
                "profile_locks_$authenticatedPhone",
                profileLocksMap.map { "${it.key}:${it.value}" }.toSet(),
            ).apply()
        }

        val businesses by repository.businesses.collectAsState()
        val activeBusinessFromRepo by repository.activeBusiness.collectAsState()
        val selectedBusinessId = activeBusinessFromRepo?.id ?: ""
        val activeBusiness = activeBusinessFromRepo
            ?: BusinessAccount(
                id = "",
                name = sessionManager.getUserPref("business_name", authenticatedPhone, "My Business"),
            )

        val currentRole = activeBusinessFromRepo?.role
        val canManageParty = currentRole != "CASHIER"
        val canManageLedgerEntries = currentRole != "CASHIER"
        val canManageStaff = currentRole != "CASHIER"
        val canManageInventory = currentRole != "CASHIER"
        val isBusinessOwner = currentRole == null || currentRole == "OWNER"

        LaunchedEffect(authenticatedPhone) {
            if (authenticatedPhone.isNotBlank()) {
                repository.loadBusinesses()
            }
        }

        var selectedLanguage by remember(authenticatedPhone) {
            val savedLangId = sessionManager.getUserPref("app_language", authenticatedPhone, availableLanguages[0].id)
            mutableStateOf(availableLanguages.find { it.id == savedLangId } ?: availableLanguages[0])
        }

        var addPartyInitialName by rememberSaveable { mutableStateOf("") }
        var addPartyInitialPhone by rememberSaveable { mutableStateOf("") }

        LaunchedEffect(Unit) {
            NetworkClient.sessionExpired.collect {
                if (isUserLoggedIn) {
                    sessionManager.clearSession(authenticatedPhone, loggedInPhones)
                    updateLoginState(false)
                    navigator.navStack.clear()
                    navigator.navStack.add(AppScreen.MOBILE_AUTH)
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
                                sessionManager.setUserPref("app_language", authenticatedPhone, lang.id)
                                if (isUserLoggedIn) {
                                    navigator.popToRoot(AppScreen.PARTIES)
                                } else {
                                    val permissionsAlreadyShown = sessionManager.prefs.getBoolean("permissions_shown", false)
                                    if (!permissionsAlreadyShown) {
                                        navigator.navigateTo(AppScreen.PERMISSIONS)
                                    } else {
                                        navigator.navigateTo(AppScreen.MOBILE_AUTH)
                                    }
                                }
                            }
                        )
                    }

                    AppScreen.PERMISSIONS -> {
                        PermissionsScreen(
                            onPermissionsHandled = {
                                sessionManager.prefs.edit().putBoolean("permissions_shown", true).apply()
                                navigator.navStack.clear()
                                if (isUserLoggedIn) {
                                    navigator.navStack.add(AppScreen.PARTIES)
                                } else {
                                    navigator.navStack.add(AppScreen.MOBILE_AUTH)
                                }
                            }
                        )
                    }

                    AppScreen.MOBILE_AUTH -> {
                        MobileAuthScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onSendOtp = { phone ->
                                phoneNumberToVerify = phone
                                navigator.navigateTo(AppScreen.OTP_VERIFICATION)
                            }
                        )
                    }

                    AppScreen.OTP_VERIFICATION -> {
                        val coroutineScope = rememberCoroutineScope()
                        OtpVerificationScreen(
                            phoneNumber = phoneNumberToVerify,
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onVerifySuccess = { accessToken, isNewUser ->
                                val normalizedPhone = sessionManager.normalizePhoneForKey(phoneNumberToVerify)
                                sessionManager.setJwtToken(accessToken, normalizedPhone)

                                authenticatedPhone = normalizedPhone
                                userName = sessionManager.getUserPref("user_name", authenticatedPhone, "User")
                                sessionManager.setActivePhone(normalizedPhone)

                                if (!loggedInPhones.contains(normalizedPhone)) {
                                    loggedInPhones.add(normalizedPhone)
                                    sessionManager.saveLoggedInPhones(loggedInPhones)
                                }

                                repository.switchUser(authenticatedPhone)
                                updateLoginState(true)

                                coroutineScope.launch {
                                    repository.retryFailedOperations()
                                    repository.flushOutbox()
                                    repository.restoreFromCloudIfNeeded()
                                    repository.syncPartiesFromServer()
                                }
                                navigator.navStack.clear()
                                val hasSavedProfile = sessionManager.getUserPref("user_name", authenticatedPhone, "").isNotBlank()
                                if (isNewUser && !hasSavedProfile) {
                                    navigator.navStack.add(AppScreen.USER_DETAILS)
                                } else {
                                    navigator.navStack.add(AppScreen.PARTIES)
                                }
                            }
                        )
                    }

                    AppScreen.USER_DETAILS -> {
                        UserDetailsScreen(
                            onDetailsSubmitted = { name, bizName, category ->
                                userName = name
                                sessionManager.setUserPref("user_name", authenticatedPhone, name)
                                repository.applyPendingUserName(name)
                                updateLoginState(true)
                                if (bizName.isNotBlank()) {
                                    sessionManager.setUserPref("business_name", authenticatedPhone, bizName)
                                    repository.applyPendingBusinessProfile(bizName, category)
                                }
                                navigator.navStack.clear()
                                val permissionsAlreadyShown = sessionManager.prefs.getBoolean("permissions_shown", false)
                                if (!permissionsAlreadyShown) {
                                    navigator.navStack.add(AppScreen.PERMISSIONS)
                                } else {
                                    navigator.navStack.add(AppScreen.PARTIES)
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
                            restoreFailed = restoreFailed,
                            onRetryRestore = {
                                partiesScope.launch { repository.retryRestoreFromCloud() }
                            },
                            onBusinessHeaderClick = { showBusinessSwitcher = true },
                            onAddPartyClick = { navigator.navigateTo(AppScreen.CUSTOMER_CONTACT_PICKER) },
                            onCashbookClick = { navigator.navigateTo(AppScreen.CASHBOOK) },
                            onViewReportsClick = { navigator.navigateTo(AppScreen.VIEW_REPORTS) },
                            onBusinessStatisticsClick = { navigator.navigateTo(AppScreen.BUSINESS_STATISTICS) },
                            onMoreTabClick = { navigator.navigateTo(AppScreen.MORE) },
                            onPartyClick = { selectedParty ->
                                activePartyId = selectedParty.id
                                navigator.navigateTo(AppScreen.CUSTOMER_LEDGER)
                            },
                            onDeleteParty = { partyId ->
                                repository.deleteParty(partyId)
                            },
                            onSheetRecordBulkSync = { records, date ->
                                var saved = 0
                                records.forEach { rec ->
                                    if (repository.addOrUpdateSheetRecord(
                                        customerCodeOrName = rec.customerCodeOrName,
                                        amountCollected = rec.amountCollected,
                                        date = rec.date.ifBlank { date },
                                        businessId = selectedBusinessId,
                                        isNewCustomer = rec.isNewCustomer,
                                        newPhone = rec.newPhone,
                                        newAddress = rec.newAddress
                                    )) saved++
                                }
                                val skipped = records.size - saved
                                Toast.makeText(
                                    context,
                                    when {
                                        saved == 0 -> "Couldn't add any entries from this sheet"
                                        skipped > 0 -> "Added $saved; $skipped needed a customer match"
                                        else -> "Added $saved ${if (saved == 1) "entry" else "entries"}"
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
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

                        LaunchedEffect(updatedParty.id) {
                            repository.flushOutbox()
                            repository.syncLedgerEntriesFromServer(updatedParty.id)
                        }

                        CustomerLedgerScreen(
                            party = updatedParty,
                            entries = entries,
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onAddEntry = { partyId, amount, type, note, transactionDate ->
                                repository.addLedgerEntry(
                                    partyId, amount, type, note,
                                    transactionDate = transactionDate
                                )
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
                            onViewProfileClick = { navigator.navigateTo(AppScreen.CUSTOMER_PROFILE) },
                            onSendBulkReminders = {
                                val message = "Dear ${updatedParty.name}, your total outstanding balance is ₹ ${kotlin.math.abs(updatedParty.currentBalance).toInt()}. Please clear your dues on Open Khata."
                                SmsSender.send(context, updatedParty.phone, message, updatedParty.businessId)
                            },
                            canManageLedgerEntries = canManageLedgerEntries
                        )
                    }

                    AppScreen.CUSTOMER_PROFILE -> {
                        val currentParty = parties.find { it.id == activePartyId } ?: parties.firstOrNull() ?: Party(id = "cust_1", name = "Customer", phone = "+91 99999 88888")
                        val updatedParty = parties.find { it.id == currentParty.id } ?: currentParty

                        CustomerProfileScreen(
                            party = updatedParty,
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onDeleteParty = { partyId ->
                                repository.deleteParty(partyId)
                                navigator.navStack.removeAll { it == AppScreen.CUSTOMER_PROFILE || it == AppScreen.CUSTOMER_LEDGER }
                                if (navigator.navStack.isEmpty()) navigator.navStack.add(AppScreen.PARTIES)
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
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onAddCustomerManualClick = {
                                addPartyInitialName = ""
                                addPartyInitialPhone = ""
                                navigator.navigateTo(AppScreen.ADD_PARTY)
                            },
                            onContactSelectConfirmed = { contact, _, _ ->
                                addPartyInitialName = contact.name
                                addPartyInitialPhone = contact.phone
                                navigator.navigateTo(AppScreen.ADD_PARTY)
                            }
                        )
                    }

                    AppScreen.ADD_PARTY -> {
                        AddPartyScreen(
                            initialName = addPartyInitialName,
                            initialPhone = addPartyInitialPhone,
                            existingParties = parties.filter { it.businessId == selectedBusinessId },
                            onBackClick = { navigator.navigateBack(onFinish) },
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
                                navigator.popToRoot(AppScreen.PARTIES)
                            }
                        )
                    }

                    AppScreen.CASHBOOK -> {
                        CashbookScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            parties = parties.filter { it.businessId == selectedBusinessId },
                            onAddEntry = { partyId, amount, type, note ->
                                repository.addLedgerEntry(partyId, amount, type, note)
                            },
                            onViewReportClick = { navigator.navigateTo(AppScreen.CASHBOOK_REPORT) },
                            repository = repository
                        )
                    }

                    AppScreen.CASHBOOK_REPORT -> {
                        CashbookReportScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            businessName = activeBusiness.name,
                            repository = repository
                        )
                    }

                    AppScreen.VIEW_REPORTS -> {
                        ViewReportsScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            repository = repository
                        )
                    }

                    AppScreen.BUSINESS_STATISTICS -> {
                        BusinessStatisticsScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            repository = repository
                        )
                    }

                    AppScreen.MORE -> {
                        MoreScreen(
                            onPartiesTabClick = { navigator.navigateTo(AppScreen.PARTIES) },
                            onCashbookClick = { navigator.navigateTo(AppScreen.CASHBOOK) },
                            onStaffClick = { navigator.navigateTo(AppScreen.ADD_STAFF) },
                            onCollectionClick = { navigator.navigateTo(AppScreen.SET_COLLECTION_DATE) },
                            onSettingsClick = { navigator.navigateTo(AppScreen.SETTINGS) },
                            onSmsSettingsClick = { navigator.navigateTo(AppScreen.SMS_SETTINGS) },
                            onSwitchAccountClick = { showAccountSwitcher = true },
                            onChangeLanguageClick = { navigator.navigateTo(AppScreen.LANGUAGE_SELECTION) },
                            onEditProfileClick = { navigator.navigateTo(AppScreen.EDIT_PROFILE) },
                            onBillsClick = { navigator.navigateTo(AppScreen.BILLS) },
                            onItemsClick = { navigator.navigateTo(AppScreen.ITEMS) },
                            onHelpSupportClick = { navigator.navigateTo(AppScreen.HELP_SUPPORT) },
                            onAboutUsClick = { navigator.navigateTo(AppScreen.ABOUT_US) },
                            businessName = activeBusiness.name,
                            userName = userName,
                            phoneNumber = authenticatedPhone,
                            selectedLanguageName = selectedLanguage.nameInNative,
                            canManageStaff = canManageStaff
                        )
                    }

                    AppScreen.SETTINGS -> {
                        SettingsScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onSmsSettingsClick = { navigator.navigateTo(AppScreen.SMS_SETTINGS) },
                            onPaymentSettingsClick = { },
                            onRecycleBinClick = { navigator.navigateTo(AppScreen.RECYCLE_BIN) },
                            onBackupInfoClick = { navigator.navigateTo(AppScreen.BACKUP_INFO) },
                            onDeleteKhataClick = { navigator.navigateTo(AppScreen.DELETE_KHATA) },
                            onChangeLanguageClick = { navigator.navigateTo(AppScreen.LANGUAGE_SELECTION) },
                            onHelpSupportClick = { navigator.navigateTo(AppScreen.HELP_SUPPORT) },
                            onAboutUsClick = { navigator.navigateTo(AppScreen.ABOUT_US) },
                            canDeleteBusiness = isBusinessOwner,
                            onLogoutClick = {
                                if (!isLoggingOut) {
                                    logoutComplete = false
                                    isLoggingOut = true
                                }
                            },
                            isAppLockEnabled = isAppLockEnabled,
                            onToggleAppLock = { enabled, pin ->
                                isAppLockEnabled = enabled
                                sessionManager.setAppLockEnabled(authenticatedPhone, enabled)
                                if (pin != null) {
                                    appLockPin = pin
                                    sessionManager.setAppLockPin(authenticatedPhone, pin)
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
                            onBackClick = { navigator.navigateBack(onFinish) },
                            repository = repository
                        )
                    }

                    AppScreen.RECYCLE_BIN -> {
                        RecycleBinScreen(
                            onBackClick = { navigator.navigateBack(onFinish) }
                        )
                    }

                    AppScreen.BACKUP_INFO -> {
                        BackupInfoScreen(
                            onBackClick = { navigator.navigateBack(onFinish) }
                        )
                    }

                    AppScreen.DELETE_KHATA -> {
                        DeleteKhataScreen(
                            businessName = activeBusiness.name,
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onDeleteConfirmed = {
                                navigator.navigateBack(onFinish)
                            }
                        )
                    }

                    AppScreen.ADD_STAFF -> {
                        AddStaffScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onAddStaffClick = { navigator.navigateTo(AppScreen.STAFF_CONTACT_PICKER) },
                            refreshKey = staffRefreshKey,
                            repository = repository
                        )
                    }

                    AppScreen.STAFF_CONTACT_PICKER -> {
                        StaffContactPickerScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            onContactSelect = { contact ->
                                staffContactPending = contact
                            }
                        )
                    }

                    AppScreen.SET_COLLECTION_DATE -> {
                        SetCollectionDateScreen(
                            parties = parties,
                            onBackClick = { navigator.navigateBack(onFinish) }
                        )
                    }

                    AppScreen.BILLS -> {
                        BillsScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            parties = parties.filter { it.businessId == selectedBusinessId },
                            repository = repository
                        )
                    }

                    AppScreen.ITEMS -> {
                        ItemsScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            canManageInventory = canManageInventory,
                            repository = repository
                        )
                    }

                    AppScreen.HELP_SUPPORT -> {
                        HelpSupportScreen(onBackClick = { navigator.navigateBack(onFinish) })
                    }

                    AppScreen.ABOUT_US -> {
                        AboutUsScreen(onBackClick = { navigator.navigateBack(onFinish) })
                    }

                    AppScreen.EDIT_PROFILE -> {
                        BookProfileScreen(
                            onBackClick = { navigator.navigateBack(onFinish) },
                            repository = repository
                        )
                    }
                }
            }
        }

        // Business Switcher Dialog Modal
        if (showBusinessSwitcher) {
            BusinessSwitcherDialog(
                businesses = businesses,
                activeBusinessId = selectedBusinessId,
                onBusinessSelect = { biz ->
                    showBusinessSwitcher = false
                    if (biz.id != selectedBusinessId && !transitionInProgress && pendingBusinessLockTarget == null) {
                        val profilePin = profileLocksMap[biz.id]
                        if (profilePin != null) {
                            pendingBusinessLockTarget = Pair(biz, profilePin)
                        } else {
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
                mode = AccountTransitionMode.BUSINESS_SWITCH,
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
                mode = AccountTransitionMode.ACCOUNT_SWITCH,
                targetLabel = switchingAccountTarget!!,
                isOperationComplete = accountSwitchComplete,
                onFinished = {
                    val phone = switchingAccountTarget!!
                    authenticatedPhone = phone
                    navigator.popToRoot(AppScreen.PARTIES)
                    switchingAccountTarget = null
                    accountSwitchComplete = false
                }
            )
        }

        if (isLoggingOut) {
            BusinessSwitchingOverlay(
                mode = AccountTransitionMode.LOGOUT,
                targetLabel = "",
                isOperationComplete = logoutComplete,
                onFinished = {
                    authenticatedPhone = ""
                    updateLoginState(false)
                    onFinish()
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
                            staffResultMessage = staffRepository.addStaffMember(contact, "MANAGER")
                            staffRefreshKey++
                        }
                        navigator.navigateBack(onFinish)
                    }) { Text("MANAGER") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        staffContactPending = null
                        staffScope.launch {
                            staffResultMessage = staffRepository.addStaffMember(contact, "CASHIER")
                            staffRefreshKey++
                        }
                        navigator.navigateBack(onFinish)
                    }) { Text("CASHIER") }
                },
            )
        }

        staffResultMessage?.let { message ->
            LaunchedEffect(message) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                staffResultMessage = null
            }
        }

        if (showAccountSwitcher) {
            MultiSessionAccountSwitcherDialog(
                phoneNumbers = loggedInPhones,
                accountNames = loggedInPhones.associateWith { phone ->
                    sessionManager.getUserPref("user_name", phone, "")
                },
                activePhoneNumber = authenticatedPhone,
                onPhoneSelect = { phone ->
                    showAccountSwitcher = false
                    if (phone != authenticatedPhone && !transitionInProgress) {
                        accountSwitchComplete = false
                        switchingAccountTarget = phone
                    }
                },
                onAddNewAccount = {
                    navigator.navigateTo(AppScreen.MOBILE_AUTH)
                },
                onDismiss = { showAccountSwitcher = false }
            )
        }
    }
}
