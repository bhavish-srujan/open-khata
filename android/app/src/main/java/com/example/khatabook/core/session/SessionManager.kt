package com.example.khatabook.core.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages encrypted user credentials, multi-account sessions, and user-scoped preferences.
 */
class SessionManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Normalizes an Indian phone number for consistent session key lookup.
     */
    fun normalizePhoneForKey(value: String): String {
        val digits = value.replace(Regex("\\D"), "")
        if (digits.length == 10) return digits
        if (digits.length == 11 && digits.startsWith("0")) return digits.substring(1)
        if (digits.length == 12 && digits.startsWith("91")) return digits.substring(2)
        if (digits.length == 13 && digits.startsWith("091")) return digits.substring(3)
        return digits
    }

    fun isUserLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun setUserLoggedIn(isLoggedIn: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, isLoggedIn).apply()
    }

    fun getActivePhone(): String {
        return normalizePhoneForKey(prefs.getString(KEY_AUTH_PHONE, "") ?: "")
    }

    fun setActivePhone(phone: String) {
        val normalized = normalizePhoneForKey(phone)
        prefs.edit().putString(KEY_AUTH_PHONE, normalized).apply()
    }

    fun getJwtToken(phone: String? = null): String? {
        val targetPhone = if (!phone.isNullOrBlank()) normalizePhoneForKey(phone) else getActivePhone()
        return if (targetPhone.isNotBlank()) {
            prefs.getString("jwt_token_$targetPhone", prefs.getString("jwt_token", null))
        } else {
            prefs.getString("jwt_token", null)
        }
    }

    fun setJwtToken(token: String, phone: String? = null) {
        val editor = prefs.edit()
        val targetPhone = if (!phone.isNullOrBlank()) normalizePhoneForKey(phone) else getActivePhone()
        if (targetPhone.isNotBlank()) {
            editor.putString("jwt_token_$targetPhone", token)
        }
        editor.putString("jwt_token", token)
        editor.apply()
    }

    fun getLoggedInPhones(): List<String> {
        val str = prefs.getString(KEY_LOGGED_IN_PHONES_LIST, null)
        val active = getActivePhone()
        return if (str != null && str.isNotBlank()) {
            str.split(",")
                .map { normalizePhoneForKey(it) }
                .filter { it.isNotBlank() }
                .distinct()
        } else {
            if (active.isNotBlank()) listOf(active) else emptyList()
        }
    }

    fun saveLoggedInPhones(phones: List<String>) {
        val distinct = phones.map { normalizePhoneForKey(it) }.filter { it.isNotBlank() }.distinct()
        prefs.edit().putString(KEY_LOGGED_IN_PHONES_LIST, distinct.joinToString(",")).apply()
    }

    /**
     * Removes every trace of the signed-out account's credentials.
     * All of these matter: `jwt_token_<phone>` is what AuthInterceptor reads,
     * `jwt_token` is a legacy global copy, and `auth_phone` is what selects the active account.
     */
    fun clearSession(phone: String, loggedInPhones: MutableList<String>) {
        val editor = prefs.edit()
        val normalized = normalizePhoneForKey(phone)
        if (normalized.isNotBlank()) {
            editor.remove("jwt_token_$normalized")
        }
        editor.remove("jwt_token")
        editor.remove(KEY_AUTH_PHONE)

        loggedInPhones.remove(normalized)
        editor.putString(KEY_LOGGED_IN_PHONES_LIST, loggedInPhones.joinToString(","))
        editor.apply()
    }

    fun userPrefKey(key: String, phone: String?): String {
        return if (!phone.isNullOrBlank()) "${key}_${normalizePhoneForKey(phone)}" else key
    }

    fun getUserPref(key: String, phone: String?, default: String): String {
        val perUserKey = userPrefKey(key, phone)
        val perUserValue = prefs.getString(perUserKey, null)
        if (!perUserValue.isNullOrBlank()) return perUserValue

        val globalValue = prefs.getString(key, null)
        val activePhone = prefs.getString(KEY_AUTH_PHONE, null)
        if (!globalValue.isNullOrBlank() && phone != null && phone == activePhone) {
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

    fun isAppLockEnabled(phone: String): Boolean {
        val targetPhone = normalizePhoneForKey(phone)
        return prefs.getBoolean("app_lock_enabled_$targetPhone", false)
    }

    fun setAppLockEnabled(phone: String, enabled: Boolean) {
        val targetPhone = normalizePhoneForKey(phone)
        prefs.edit().putBoolean("app_lock_enabled_$targetPhone", enabled).apply()
    }

    fun getAppLockPin(phone: String): String {
        val targetPhone = normalizePhoneForKey(phone)
        return prefs.getString("app_lock_pin_$targetPhone", "") ?: ""
    }

    fun setAppLockPin(phone: String, pin: String) {
        val targetPhone = normalizePhoneForKey(phone)
        prefs.edit().putString("app_lock_pin_$targetPhone", pin).apply()
    }

    companion object {
        const val PREFS_NAME = "khatabook_secure_prefs"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
        const val KEY_AUTH_PHONE = "auth_phone"
        const val KEY_LOGGED_IN_PHONES_LIST = "logged_in_phones_list"
    }
}
