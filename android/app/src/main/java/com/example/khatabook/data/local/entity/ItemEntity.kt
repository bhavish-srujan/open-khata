package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "items", indices = [Index(value = ["ownerPhone", "businessId"])])
data class ItemEntity(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val businessId: String,
    val name: String,
    val sku: String?,
    val sellingPrice: Double,
    val purchasePrice: Double,
    val currentStock: Double,
    val lowStockThreshold: Double,
)
