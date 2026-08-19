package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.khatabook.data.local.entity.PendingOpEntity

/**
 * KhatabookRepository keeps the Outbox as a single in-memory
 * `MutableList<PendingOp>`, mutated and then persisted as one whole list on
 * every change (`saveOutbox()`) — exactly the same "overwrite everything"
 * contract the old SharedPreferences JSON blob had. [replaceAll] preserves
 * that contract on Room: the small, bounded size of a shop's pending-writes
 * queue makes a delete-then-reinsert cheap, and it lets `sequence` (this
 * table's durable insertion-order column) simply follow the reinserted
 * list's own order, which the repository has already always kept correct.
 */
@Dao
interface OutboxDao {
    @Query("SELECT * FROM pending_ops WHERE ownerPhone = :ownerPhone ORDER BY sequence ASC")
    fun getAllSync(ownerPhone: String): List<PendingOpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(ops: List<PendingOpEntity>)

    @Query("DELETE FROM pending_ops WHERE ownerPhone = :ownerPhone")
    fun deleteAllForOwner(ownerPhone: String)

    @Transaction
    fun replaceAll(ownerPhone: String, ops: List<PendingOpEntity>) {
        deleteAllForOwner(ownerPhone)
        insertAll(ops)
    }
}
