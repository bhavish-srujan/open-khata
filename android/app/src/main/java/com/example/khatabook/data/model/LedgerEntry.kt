package com.example.khatabook.data.model

import java.util.UUID

enum class TransactionType {
    GAVE, // Credit given (You Gave - Red)
    GOT   // Debit received (You Got - Green)
}

enum class EntrySource {
    MANUAL,
    OCR
}

enum class EntryStatus {
    PENDING_REVIEW,
    CONFIRMED,
    REJECTED
}

@androidx.compose.runtime.Immutable
data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val businessId: String = "biz_default",
    val partyId: String,
    val amount: Double,
    val type: TransactionType,
    val note: String = "",
    val paymentMethod: String = "Cash",
    val source: EntrySource = EntrySource.MANUAL,
    val status: EntryStatus = EntryStatus.CONFIRMED,
    val imageUrl: String? = null,
    val rawExtractedText: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
