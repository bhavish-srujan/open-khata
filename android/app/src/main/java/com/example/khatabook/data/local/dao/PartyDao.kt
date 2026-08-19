package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.khatabook.data.local.entity.PartyEntity

@Dao
interface PartyDao {
    @Query("SELECT * FROM parties WHERE ownerPhone = :ownerPhone AND businessId = :businessId ORDER BY createdAt DESC")
    fun getForBusinessSync(ownerPhone: String, businessId: String): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE ownerPhone = :ownerPhone AND businessId = :businessId AND id = :partyId LIMIT 1")
    fun getByIdSync(ownerPhone: String, businessId: String, partyId: String): PartyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(party: PartyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(parties: List<PartyEntity>)

    @Query("DELETE FROM parties WHERE id = :partyId")
    fun deleteById(partyId: String)

    /** Adds [delta] to a party's stored balance in one atomic UPDATE, so two
     *  concurrent local writers (e.g. an optimistic create racing a
     *  server-reconciliation adjustment) cannot lose one's change to the
     *  other's read-modify-write — the same hazard PartyService.updateBalance
     *  avoids server-side via Repository.increment(). */
    @Query("UPDATE parties SET currentBalance = currentBalance + :delta WHERE id = :partyId")
    fun adjustBalance(partyId: String, delta: Double)

    @Query("DELETE FROM parties WHERE ownerPhone = :ownerPhone AND businessId = :businessId")
    fun deleteAllForBusiness(ownerPhone: String, businessId: String)
}
