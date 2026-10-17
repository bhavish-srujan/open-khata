package com.example.khatabook.data.repository

import com.example.khatabook.data.api.AddStaffRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.ui.screens.ContactItem

/**
 * Handles staff member onboarding, role assignments, and member management.
 */
class StaffRepository {

    /**
     * Adds a contact to the active business, returning a user-facing status message.
     * Maps HTTP status codes to actionable feedback.
     */
    suspend fun addStaffMember(contact: ContactItem, role: String): String {
        return try {
            val response = NetworkClient.apiService.addStaff(
                AddStaffRequest(
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
}
