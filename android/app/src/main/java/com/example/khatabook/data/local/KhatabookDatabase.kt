package com.example.khatabook.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.khatabook.data.local.dao.BillDao
import com.example.khatabook.data.local.dao.BusinessDao
import com.example.khatabook.data.local.dao.ItemDao
import com.example.khatabook.data.local.dao.LedgerEntryDao
import com.example.khatabook.data.local.dao.OutboxDao
import com.example.khatabook.data.local.dao.PartyDao
import com.example.khatabook.data.local.dao.StaffDao
import com.example.khatabook.data.local.dao.SyncStateDao
import com.example.khatabook.data.local.entity.BillEntity
import com.example.khatabook.data.local.entity.BusinessEntity
import com.example.khatabook.data.local.entity.ItemEntity
import com.example.khatabook.data.local.entity.LedgerEntryEntity
import com.example.khatabook.data.local.entity.PendingOpEntity
import com.example.khatabook.data.local.entity.PartyEntity
import com.example.khatabook.data.local.entity.StaffMemberEntity
import com.example.khatabook.data.local.entity.SyncStateEntity

/**
 * KhatabookRepository's persistent local-first store — the on-device source
 * of truth for normal reads. PostgreSQL remains the authoritative *cloud*
 * copy, restored into this database on first login / after local data loss
 * (see KhatabookRepository.restoreFromCloudIfEmpty).
 *
 * `allowMainThreadQueries()`: every read/write path in KhatabookRepository
 * today is a plain, non-suspend function that reads/writes
 * SharedPreferences synchronously, including from Compose UI callbacks —
 * `prefs.getString(...)` and `prefs.edit()...apply()` both run on whatever
 * thread calls them, main thread included, and that is the exact contract
 * every one of the ~50 existing call sites across the app's screens is
 * written against. Making every one of those methods `suspend` to satisfy
 * Room's normal main-thread restriction would touch nearly every screen in
 * the app for a storage-engine swap that is not supposed to change
 * behavior. Enabling this instead preserves that identical synchronous
 * contract — a local, indexed SQLite read over the row counts this app
 * deals with (a small shop's parties/entries) is not meaningfully slower
 * than the main-thread Gson JSON parse of a whole prefs blob it replaces,
 * so this is a lateral move, not a new performance risk. Reactive/async
 * (Flow-based) reads are the natural next step if this ever needs to change,
 * deliberately deferred rather than folded into this migration's scope.
 */
@Database(
    entities = [
        BusinessEntity::class,
        PartyEntity::class,
        LedgerEntryEntity::class,
        ItemEntity::class,
        StaffMemberEntity::class,
        BillEntity::class,
        PendingOpEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class KhatabookDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao
    abstract fun partyDao(): PartyDao
    abstract fun ledgerEntryDao(): LedgerEntryDao
    abstract fun itemDao(): ItemDao
    abstract fun staffDao(): StaffDao
    abstract fun billDao(): BillDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile
        private var instance: KhatabookDatabase? = null

        /**
         * The first schema change since this database shipped (version 1 had
         * no migrations at all). Every existing install has a `parties` table
         * without these columns — omitting this migration would crash Room
         * with "migration ... was required but not found" on next launch for
         * every current user, not just skip the feature. Plain `ALTER TABLE
         * ADD COLUMN` with defaults matches SQLite's and Room's expectations
         * for a purely-additive column and needs no data backfill.
         */
        // Internal rather than private: PartyInterestMigrationTest exercises
        // this directly against a real v1-schema file to prove it runs
        // cleanly, the same way it will on an existing user's device.
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parties ADD COLUMN interestEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE parties ADD COLUMN interestRate REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE parties ADD COLUMN interestPeriodValue INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE parties ADD COLUMN interestPeriodUnit TEXT NOT NULL DEFAULT 'DAYS'")
            }
        }

        fun getInstance(context: Context): KhatabookDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): KhatabookDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                KhatabookDatabase::class.java,
                "khatabook.db",
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
