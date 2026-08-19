package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bills", indices = [Index(value = ["ownerPhone", "businessId"])])
data class BillEntity(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val businessId: String,
    val partyId: String,
    val totalAmount: Double,
    val isGst: Boolean,
    val receiptUrl: String?,
    val createdAt: String,
)
