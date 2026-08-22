package com.example.khatabook.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A ledger entry, scoped to [ownerPhone] and [businessId] like [PartyEntity].
 *
 * One table backs both the per-party ledger view and the business-wide
 * cashbook feed — [com.example.khatabook.data.local.dao.LedgerEntryDao]
 * queries the same rows either filtered to one partyId or joined against
 * [PartyEntity] for the whole business. The old architecture kept a second,
 * separately-synced cache (`_businessLedgerFeed` / "ledgerFeed" prefs key)
 * purely because a SharedPreferences JSON blob cannot be queried two ways;
 * a relational table does not have that limitation, so that duplicate store
 * is retired rather than ported.
 *
 * No `@ForeignKey` to [PartyEntity], for the same reason [PartyEntity] has
 * none to `BusinessEntity`: deletion cascades are performed explicitly by
 * KhatabookRepository (mirroring exactly what deleteParty() already deletes
 * today) rather than implicitly by the database, so a sync ordering hiccup
 * can never silently wipe rows via cascade.
 */
@Entity(
    tableName = "ledger_entries",
    indices = [
        Index(value = ["ownerPhone", "businessId", "partyId"]),
        Index(value = ["ownerPhone", "businessId", "createdAt"]),
        Index(value = ["partyId"]),
    ],
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val businessId: String,
    val partyId: String,
    val amount: Double,
    val type: String,
    val note: String,
    val paymentMethod: String,
    val source: String,
    val status: String,
    val imageUrl: String?,
    val rawExtractedText: String?,
    val createdAt: Long,
    /**
     * Business date — see [com.example.khatabook.data.model.LedgerEntry].
     *
     * Added by MIGRATION_2_3, which backfills existing rows from createdAt so
     * no historical entry silently changes the day it belongs to.
     *
     * `defaultValue` is declared because SQLite requires a default when adding
     * a NOT NULL column to a table that already has rows, and Room validates
     * the live schema against this declaration on open. Without it, Room
     * rejects the upgraded database with "Migration didn't properly handle"
     * and the app crashes on first launch after updating.
     */
    @ColumnInfo(defaultValue = "0")
    val transactionDate: Long = 0L,
)
