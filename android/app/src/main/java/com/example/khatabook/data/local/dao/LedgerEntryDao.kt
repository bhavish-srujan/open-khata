package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.khatabook.data.local.entity.LedgerEntryEntity

/**
 * A ledger row joined with the small slice of its party the cashbook feed
 * displays — replaces the separately-synced `LedgerFeedEntry` cache the old
 * architecture needed (see [LedgerEntryEntity]'s class doc).
 */
data class LedgerFeedRow(
    @Embedded val entry: LedgerEntryEntity,
    val partyName: String?,
    val partyPhone: String?,
)

@Dao
interface LedgerEntryDao {
    @Query("SELECT * FROM ledger_entries WHERE ownerPhone = :ownerPhone AND businessId = :businessId AND partyId = :partyId ORDER BY createdAt DESC")
    fun getForPartySync(ownerPhone: String, businessId: String, partyId: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries WHERE ownerPhone = :ownerPhone AND businessId = :businessId ORDER BY createdAt DESC")
    fun getForBusinessSync(ownerPhone: String, businessId: String): List<LedgerEntryEntity>

    /**
     * Business-wide feed with the party joined in, newest first, capped —
     * mirrors the server's own MAX_LEDGER_FEED_ROWS bound (LedgerService)
     * so a busy business's local read is never unbounded either.
     */
    @Query(
        """
        SELECT le.*, p.name AS partyName, p.phone AS partyPhone
        FROM ledger_entries le
        LEFT JOIN parties p ON p.id = le.partyId
        WHERE le.ownerPhone = :ownerPhone AND le.businessId = :businessId
        ORDER BY le.createdAt DESC
        LIMIT 500
        """,
    )
    fun getBusinessFeedSync(ownerPhone: String, businessId: String): List<LedgerFeedRow>

    @Query("SELECT * FROM ledger_entries WHERE id = :id LIMIT 1")
    fun getByIdSync(id: String): LedgerEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entries: List<LedgerEntryEntity>)

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    fun deleteById(id: String)

    /** Explicit cascade for a party deletion — see [LedgerEntryEntity]'s
     *  class doc on why this is not a database-level FK cascade. */
    @Query("DELETE FROM ledger_entries WHERE partyId = :partyId")
    fun deleteAllForParty(partyId: String)
}
