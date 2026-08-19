package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A business as this device knows it, scoped to [ownerPhone] — the
 * authenticated user's phone number on this device.
 *
 * [ownerPhone] is the account-isolation boundary: every query in
 * [com.example.khatabook.data.local.dao.BusinessDao] filters by it, so
 * switching accounts on the same device can never surface another account's
 * businesses. It is not a foreign key to a users table — the app has no
 * local user table, only the phone number that identified which
 * [KhatabookDatabase] rows belong to the currently signed-in account,
 * exactly as the old `businesses_data_$phone` SharedPreferences key did.
 */
@Entity(tableName = "businesses", indices = [Index(value = ["ownerPhone"])])
data class BusinessEntity(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val name: String,
    val type: String,
    val isPrimary: Boolean,
    val gstNumber: String?,
    val address: String?,
    val category: String?,
    val bankAccount: String?,
    val profileImageUrl: String?,
    val role: String?,
)
