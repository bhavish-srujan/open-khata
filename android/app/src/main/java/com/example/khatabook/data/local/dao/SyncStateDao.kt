package com.example.khatabook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.khatabook.data.local.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE ownerPhone = :ownerPhone LIMIT 1")
    fun getSync(ownerPhone: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: SyncStateEntity)
}
