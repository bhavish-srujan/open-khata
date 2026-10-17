package com.example.khatabook.core.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity

/**
 * Handles runtime permission requests for SMS delivery and phone state detection.
 */
object PermissionManager {

    const val SMS_PERMISSION_REQUEST_CODE = 101

    /**
     * Requests SEND_SMS and READ_PHONE_STATE permissions once at startup if missing.
     * Non-blocking: failures fall back gracefully to user messaging.
     */
    fun requestSmsPermissionOnce(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val missingPermissions = mutableListOf<String>()
        if (activity.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.SEND_SMS)
        }
        if (activity.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (missingPermissions.isNotEmpty()) {
            activity.requestPermissions(missingPermissions.toTypedArray(), SMS_PERMISSION_REQUEST_CODE)
        }
    }
}
