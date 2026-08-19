package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A party (customer/supplier), scoped to [ownerPhone] (account isolation)
 * and [businessId] (business isolation) — every read in
 * [com.example.khatabook.data.local.dao.PartyDao] filters by both, matching
 * the two-level isolation [KhatabookRepository] already enforces today via
 * its `dataKey(kind, businessId)` cache-bucket scheme.
 *
 * Deliberately no `@ForeignKey` to [BusinessEntity]: a business's row list is
 * refreshed wholesale from the server (see BusinessDao.replaceAll), and a
 * transient/partial server response briefly omitting a business must never
 * cascade-delete that business's parties and ledger entries — the old
 * SharedPreferences architecture had no such coupling either (each was a
 * separately-keyed blob), so this keeps that same loose, string-scoped
 * association rather than introducing a new failure mode.
 *
 * [tagsCsv] is stored comma-joined rather than via a separate table or a
 * Room TypeConverter — a party's tag list is small and never queried by
 * individual tag, so a normalized join table would add complexity with no
 * behavioral benefit; see the entity/domain mapper for the split/join.
 */
@Entity(
    tableName = "parties",
    indices = [Index(value = ["ownerPhone", "businessId"]), Index(value = ["businessId"])],
)
data class PartyEntity(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val businessId: String,
    val name: String,
    val phone: String,
    val type: String,
    val collectionFrequency: String,
    val address: String,
    val gstin: String,
    val flatBuilding: String,
    val areaLocality: String,
    val pincode: String,
    val city: String,
    val state: String,
    val isShippingSame: Boolean,
    val currentBalance: Double,
    val openingBalance: Double,
    val creditLimit: Double,
    val tagsCsv: String,
    val avatar: String,
    val lastTransactionTime: Long,
    val createdAt: Long,
    /** See [com.example.khatabook.data.model.Party]'s matching fields — added
     *  via KhatabookDatabase's MIGRATION_1_2, default-valued for every row
     *  that existed before this column did. */
    val interestEnabled: Boolean = false,
    val interestRate: Double = 0.0,
    val interestPeriodValue: Int = 0,
    val interestPeriodUnit: String = "DAYS",
)
