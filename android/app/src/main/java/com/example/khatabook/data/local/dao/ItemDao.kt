package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.khatabook.data.local.entity.ItemEntity

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE ownerPhone = :ownerPhone AND businessId = :businessId")
    fun getForBusinessSync(ownerPhone: String, businessId: String): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE ownerPhone = :ownerPhone AND businessId = :businessId")
    fun deleteAllForBusiness(ownerPhone: String, businessId: String)

    @Transaction
    fun replaceForBusiness(ownerPhone: String, businessId: String, items: List<ItemEntity>) {
        deleteAllForBusiness(ownerPhone, businessId)
        upsertAll(items)
    }
}
