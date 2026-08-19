package com.example.khatabook.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Sends customer-facing SMS directly, without opening the Messages app.
 *
 * Every customer notification previously fired an ACTION_VIEW intent at an
 * `sms:` URI, which threw the user out of the app into the composer and left
 * them to press send. On a busy counter that is unusable: adding a transaction
 * meant leaving the ledger, sending, and navigating back.
 *
 * Login codes do not come through here — those go over TextBee (SMS) from the
 * server, not through this on-device sender. This is only for balance
 * updates and collection reminders.
 */
object SmsSender {

    private const val TAG = "SmsSender"

    /** Outcome of a send, so callers can tell the user something specific. */
    sealed interface Result {
        data object Sent : Result
        data object PermissionMissing : Result
        data class Failed(val reason: String) : Result
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, businessId: String): SmsManager {
        val prefs = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
        // Business-scoped, matching what SmsSettingsScreen actually saves
        // ("sim_index_$bizId" on Save) — this used to read the bare, never-written
        // "sim_index" key, so picking a non-default SIM in Settings had no effect;
        // every send silently used the device default regardless.
        val simIndex = prefs.getInt("sim_index_$businessId", 0) // 0=Default, 1=SIM1, 2=SIM2

        if (simIndex > 0) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    val subManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                    val activeSubs = subManager.activeSubscriptionInfoList
                    if (activeSubs != null && activeSubs.size >= simIndex) {
                        val subInfo = activeSubs[simIndex - 1] // 1 maps to index 0 (SIM1)
                        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subInfo.subscriptionId)
                        } else {
                            SmsManager.getSmsManagerForSubscriptionId(subInfo.subscriptionId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get SmsManager for SIM index $simIndex", e)
            }
        }

        // Fallback to default
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }

    /**
     * Sends [message] to [phoneNumber], returning why it failed rather than
     * throwing: a notification that cannot be delivered must never take down
     * the ledger write that triggered it.
     *
     * [businessId] selects the SIM configured for that business in SMS
     * Settings; blank falls back to the device default SIM (correct for any
     * call site not yet threading a business id through).
     */
    fun send(context: Context, phoneNumber: String, message: String, businessId: String = ""): Result {
        if (!hasPermission(context)) {
            return Result.PermissionMissing
        }

        val destination = normalize(phoneNumber)
        if (destination.isBlank()) {
            return Result.Failed("No valid phone number")
        }

        return try {
            val manager = smsManager(context, businessId)
            // Long messages must be split, or the send silently truncates.
            val parts = manager.divideMessage(message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(destination, null, parts, null, null)
            } else {
                manager.sendTextMessage(destination, null, message, null, null)
            }
            Result.Sent
        } catch (e: SecurityException) {
            // Permission revoked between the check above and the send.
            Log.w(TAG, "SMS permission revoked mid-send", e)
            Result.PermissionMissing
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "SMS rejected: bad number or empty body", e)
            Result.Failed("That number could not be messaged")
        } catch (e: Exception) {
            // No SIM, no signal, airplane mode, carrier rejection.
            Log.w(TAG, "SMS send failed", e)
            Result.Failed(e.message ?: "Message could not be sent")
        }
    }

    /**
     * Best-effort E.164 for Indian numbers, matching what the backend stores so
     * one saved number works for both paths.
     */
    fun normalize(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.substring(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            digits.length == 13 && digits.startsWith("091") -> "+${digits.substring(1)}"
            raw.startsWith("+") -> raw.filter { it.isDigit() || it == '+' }
            else -> "+$digits"
        }
    }
}
