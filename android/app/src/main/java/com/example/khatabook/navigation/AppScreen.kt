package com.example.khatabook.navigation

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * All navigable destinations across the Open Khata application.
 */
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

/**
 * Lets [AppScreen]'s back stack survive process death and configuration changes via rememberSaveable.
 */
val navStackSaver = listSaver<SnapshotStateList<AppScreen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { saved -> saved.map { AppScreen.valueOf(it) }.toMutableStateList() }
)
