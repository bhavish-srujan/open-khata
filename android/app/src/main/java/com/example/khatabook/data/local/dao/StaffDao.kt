package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.khatabook.data.local.entity.StaffMemberEntity

@Dao
interface StaffDao {
    @Query("SELECT * FROM staff_members WHERE ownerPhone = :ownerPhone AND businessId = :businessId")
    fun getForBusinessSync(ownerPhone: String, businessId: String): List<StaffMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(staff: List<StaffMemberEntity>)

    @Query("DELETE FROM staff_members WHERE ownerPhone = :ownerPhone AND businessId = :businessId")
    fun deleteAllForBusiness(ownerPhone: String, businessId: String)

    @Transaction
    fun replaceForBusiness(ownerPhone: String, businessId: String, staff: List<StaffMemberEntity>) {
        deleteAllForBusiness(ownerPhone, businessId)
        upsertAll(staff)
    }
}
