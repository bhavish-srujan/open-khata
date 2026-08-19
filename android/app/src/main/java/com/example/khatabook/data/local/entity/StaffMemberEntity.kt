package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "staff_members", indices = [Index(value = ["ownerPhone", "businessId"])])
data class StaffMemberEntity(
    @PrimaryKey val membershipId: String,
    val ownerPhone: String,
    val businessId: String,
    val userId: String,
    val name: String?,
    val phoneNumber: String,
    val role: String,
    val addedAt: String,
)
