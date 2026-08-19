package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room-backed Outbox row — the persistent form of
 * [com.example.khatabook.data.repository.PendingOp].
 *
 * [sequence] is a separate autoincrement column, not [opId], purely to give
 * the queue a durable insertion order to sort by: a Room table has no
 * concept of "list order" on its own the way the old `MutableList<PendingOp>`
 * did, and operation order is load-bearing (an entry's create must never be
 * attempted before its party's create — see [PendingOpType] ordering
 * comments). [opId] remains the externally-meaningful identity (used by
 * tests and for dedup) and keeps its own unique index.
 */
@Entity(tableName = "pending_ops", indices = [Index(value = ["opId"], unique = true), Index(value = ["ownerPhone", "businessId"])])
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val opId: String,
    val type: String,
    val targetId: String,
    val payload: String,
    val attempts: Int,
    val ownerPhone: String,
    val businessId: String,
    val status: String,
    val rollbackPayload: String?,
)
