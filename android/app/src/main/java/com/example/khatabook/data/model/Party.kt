package com.example.khatabook.data.model

import java.util.UUID

enum class PartyType {
    CUSTOMER,
    SUPPLIER
}

enum class CollectionFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}

@androidx.compose.runtime.Immutable
data class Party(
    val id: String = UUID.randomUUID().toString(),
    val businessId: String = "biz_default",
    val name: String,
    val phone: String,
    val type: PartyType = PartyType.CUSTOMER,
    val collectionFrequency: CollectionFrequency = CollectionFrequency.DAILY,
    val address: String = "",
    val gstin: String = "",
    val flatBuilding: String = "",
    val areaLocality: String = "",
    val pincode: String = "",
    val city: String = "",
    val state: String = "",
    val isShippingSame: Boolean = true,
    val currentBalance: Double = 0.0,
    val openingBalance: Double = 0.0,
    val creditLimit: Double = 5000.0,
    val tags: List<String> = emptyList(),
    val avatar: String = "",
    val lastTransactionTime: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)
