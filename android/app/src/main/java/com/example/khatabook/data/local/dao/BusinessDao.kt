package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.khatabook.data.local.entity.BusinessEntity

@Dao
interface BusinessDao {
    /**
     * Synchronous (blocking) reads are used deliberately throughout this
     * DAO — see KhatabookDatabase's `allowMainThreadQueries()` doc comment
     * for why: they preserve the exact synchronous-read contract
     * KhatabookRepository's public API already has today via
     * SharedPreferences.getString(), so no caller anywhere in the ~50
     * existing screens needs to change.
     */
    @Query("SELECT * FROM businesses WHERE ownerPhone = :ownerPhone ORDER BY isPrimary DESC")
    fun getAllSync(ownerPhone: String): List<BusinessEntity>

    @Query("SELECT COUNT(*) FROM businesses WHERE ownerPhone = :ownerPhone")
    fun countSync(ownerPhone: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(businesses: List<BusinessEntity>)

    @Query("DELETE FROM businesses WHERE ownerPhone = :ownerPhone")
    fun deleteAllForOwner(ownerPhone: String)

    /** Replaces this account's whole business list atomically — see
     *  KhatabookRepository.loadBusinesses(), which treats the server as
     *  authoritative for the list (not a merge, unlike parties/entries,
     *  since a business has no local-only unsynced-create path). */
    @androidx.room.Transaction
    fun replaceAll(ownerPhone: String, businesses: List<BusinessEntity>) {
        deleteAllForOwner(ownerPhone)
        upsertAll(businesses)
    }
}
