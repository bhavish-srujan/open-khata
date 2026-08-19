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

enum class InterestPeriodUnit {
    DAYS,
    MONTHS
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
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Optional, per-party. When [interestEnabled] is false the other three
     * fields are meaningless leftovers (never read) — every party created
     * before this feature existed defaults to false/0/0/DAYS, so nothing
     * retroactively starts accruing interest. [interestRate] is a plain
     * percentage applied as-is to whatever [interestPeriodValue]/
     * [interestPeriodUnit] says (e.g. 5% / 100 Days), never annualized or
     * converted to a different period.
     */
    val interestEnabled: Boolean = false,
    val interestRate: Double = 0.0,
    val interestPeriodValue: Int = 0,
    val interestPeriodUnit: InterestPeriodUnit = InterestPeriodUnit.DAYS
)
