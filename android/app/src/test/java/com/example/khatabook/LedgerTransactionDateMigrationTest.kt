package com.example.khatabook

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.khatabook.data.local.KhatabookDatabase
import com.example.khatabook.data.local.entity.BillEntity
import com.example.khatabook.data.local.entity.BusinessEntity
import com.example.khatabook.data.local.entity.ItemEntity
import com.example.khatabook.data.local.entity.PartyEntity
import com.example.khatabook.data.local.entity.PendingOpEntity
import com.example.khatabook.data.local.entity.StaffMemberEntity
import com.example.khatabook.data.local.entity.SyncStateEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * `ledger_entries` exactly as it stood at schema version 2 — before the
 * business date existed. Every other entity is the production one (none of
 * them changed between v2 and v3), so this produces a genuine v2 database
 * rather than a hand-approximated one.
 */
@Entity(
    tableName = "ledger_entries",
    // Exactly the v2 index set, so the only schema difference this test
    // exercises is the new column itself.
    indices = [
        Index(value = ["ownerPhone", "businessId", "partyId"]),
        Index(value = ["ownerPhone", "businessId", "createdAt"]),
        Index(value = ["partyId"]),
    ],
)
private data class LedgerEntryEntityV2(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val businessId: String,
    val partyId: String,
    val amount: Double,
    val type: String,
    val note: String,
    val paymentMethod: String,
    val source: String,
    val status: String,
    val imageUrl: String?,
    val rawExtractedText: String?,
    val createdAt: Long,
)

@Dao
private interface LedgerDaoV2 {
    @Insert
    fun insert(entry: LedgerEntryEntityV2)
}

@Database(
    entities = [
        BusinessEntity::class,
        PartyEntity::class,
        LedgerEntryEntityV2::class,
        ItemEntity::class,
        StaffMemberEntity::class,
        BillEntity::class,
        PendingOpEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
private abstract class LedgerDatabaseV2 : RoomDatabase() {
    abstract fun ledgerDaoV2(): LedgerDaoV2
}

/**
 * Upgrading must not move a single historical transaction to a different day.
 *
 * MIGRATION_2_3 backfills the new business date from `createdAt` rather than
 * defaulting it to the upgrade time. Getting that wrong would silently rewrite
 * every past daily, weekly and monthly collection the moment users install the
 * update — invisible until someone reconciled their books.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LedgerTransactionDateMigrationTest {

    @Test
    fun `existing entries keep their original date after the v2 to v3 upgrade`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "ledger-migration-${System.nanoTime()}.db"

        // Two entries from clearly different months.
        val januaryMillis = 1_767_225_600_000L  // 2026-01-01T00:00:00Z
        val marchMillis = 1_772_496_000_000L    // 2026-03-03T00:00:00Z

        val legacy = Room.databaseBuilder(context, LedgerDatabaseV2::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        listOf("e-jan" to januaryMillis, "e-mar" to marchMillis).forEach { (id, at) ->
            legacy.ledgerDaoV2().insert(
                LedgerEntryEntityV2(
                    id = id,
                    ownerPhone = "+919876543210",
                    businessId = "biz-1",
                    partyId = "party-1",
                    amount = 500.0,
                    type = "GAVE",
                    note = "",
                    paymentMethod = "Cash",
                    source = "MANUAL",
                    status = "CONFIRMED",
                    imageUrl = null,
                    rawExtractedText = null,
                    createdAt = at,
                )
            )
        }
        legacy.close()

        // The real app database opens the same file and must migrate it.
        val migrated = Room.databaseBuilder(context, KhatabookDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(KhatabookDatabase.MIGRATION_1_2, KhatabookDatabase.MIGRATION_2_3)
            .build()

        val rows = migrated.ledgerEntryDao().getForBusinessSync("+919876543210", "biz-1")
        assertEquals(2, rows.size)

        val jan = rows.first { it.id == "e-jan" }
        val mar = rows.first { it.id == "e-mar" }

        // Backfilled from createdAt — not stamped with the upgrade time.
        assertEquals(januaryMillis, jan.transactionDate)
        assertEquals(marchMillis, mar.transactionDate)
        // And createdAt itself is untouched.
        assertEquals(januaryMillis, jan.createdAt)
        assertEquals(marchMillis, mar.createdAt)
        // The two entries stay in different months, so historical monthly
        // collections are unchanged by the upgrade.
        assertEquals(true, jan.transactionDate < mar.transactionDate)

        migrated.close()
    }
}
