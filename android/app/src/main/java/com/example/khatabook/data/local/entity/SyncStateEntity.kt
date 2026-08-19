package com.example.khatabook.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Small per-account synchronization bookkeeping — currently just which
 * business is active. Not a UI preference (it is account-scoped domain
 * state, and matters for restore/isolation correctness), so it lives in
 * Room rather than SharedPreferences, unlike genuine UI/app preferences
 * (server URL, onboarding, permissions) which stay where they already are.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val ownerPhone: String,
    val activeBusinessId: String,
    /** Set once a full parties+businesses restore has completed for this
     *  account on this device — see restoreFromCloudIfEmpty(). Lets the
     *  repository tell "never synced yet" apart from "synced, genuinely
     *  has zero records" without guessing from row counts alone. */
    val hasCompletedInitialSync: Boolean = false,
)
