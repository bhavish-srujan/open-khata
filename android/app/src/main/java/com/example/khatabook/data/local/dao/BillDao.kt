package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.khatabook.data.local.entity.BillEntity

@Dao
interface BillDao {
    @Query("SELECT * FROM bills WHERE ownerPhone = :ownerPhone AND businessId = :businessId ORDER BY createdAt DESC")
    fun getForBusinessSync(ownerPhone: String, businessId: String): List<BillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(bills: List<BillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(bill: BillEntity)

    @Query("DELETE FROM bills WHERE ownerPhone = :ownerPhone AND businessId = :businessId")
    fun deleteAllForBusiness(ownerPhone: String, businessId: String)

    @Transaction
    fun replaceForBusiness(ownerPhone: String, businessId: String, bills: List<BillEntity>) {
        deleteAllForBusiness(ownerPhone, businessId)
        upsertAll(bills)
    }
}
