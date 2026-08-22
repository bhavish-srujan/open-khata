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
    /**
     * When this entry was *recorded by the app*. Never user-editable — it is
     * the audit trail for when the row came into existence, including for
     * offline writes that sync days later.
     */
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * When the transaction actually *happened*, as stated by the user.
     *
     * Deliberately separate from [createdAt]: entering yesterday's sale today
     * must land in yesterday's daily collection while still recording that the
     * row was created today. Every business-date question — daily/weekly/
     * monthly collections, statements, grouping — reads this field;
     * [createdAt] answers "when did we learn about it".
     *
     * Defaults to the creation instant, so an entry nobody backdates behaves
     * exactly as before this field existed.
     */
    val transactionDate: Long = createdAt
)
