package com.example.khatabook

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.khatabook.data.local.KhatabookDatabase
import com.example.khatabook.data.local.entity.BillEntity
import com.example.khatabook.data.local.entity.BusinessEntity
import com.example.khatabook.data.local.entity.ItemEntity
import com.example.khatabook.data.local.entity.LedgerEntryEntity
import com.example.khatabook.data.local.entity.PendingOpEntity
import com.example.khatabook.data.local.entity.StaffMemberEntity
import com.example.khatabook.data.local.entity.SyncStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * A byte-for-byte replica of `parties` as it existed at schema version 1,
 * before interest columns were added — every other production entity below
 * is reused unchanged (they didn't change between v1 and v2), so this
 * produces a real Room-generated v1 database file rather than a hand-guessed
 * approximation.
 */
@Entity(
    tableName = "parties",
    indices = [Index(value = ["ownerPhone", "businessId"]), Index(value = ["businessId"])],
)
private data class LegacyPartyEntityV1(
    @PrimaryKey val id: String,
    val ownerPhone: String,
    val businessId: String,
    val name: String,
    val phone: String,
    val type: String,
    val collectionFrequency: String,
    val address: String,
    val gstin: String,
    val flatBuilding: String,
    val areaLocality: String,
    val pincode: String,
    val city: String,
    val state: String,
    val isShippingSame: Boolean,
    val currentBalance: Double,
    val openingBalance: Double,
    val creditLimit: Double,
    val tagsCsv: String,
    val avatar: String,
    val lastTransactionTime: Long,
    val createdAt: Long,
)

@Dao
private interface LegacyPartyDaoV1 {
    @Insert
    fun insert(party: LegacyPartyEntityV1)
}

@Database(
    entities = [
        BusinessEntity::class,
        LegacyPartyEntityV1::class,
        LedgerEntryEntity::class,
        ItemEntity::class,
        StaffMemberEntity::class,
        BillEntity::class,
        PendingOpEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
private abstract class LegacyDatabaseV1 : RoomDatabase() {
    abstract fun legacyPartyDao(): LegacyPartyDaoV1
}

/**
 * Exercises KhatabookDatabase's real MIGRATION_1_2 against a real (not
 * hand-approximated) version-1 schema, on the same on-disk file real Room
 * migration code runs against on an actual device — the same thing that
 * would happen on every existing user's phone the first time they open the
 * app after this feature ships. A missing/wrong migration here fails with
 * Room's own "migration was required but not found" or an identity-hash
 * mismatch, exactly as it would on-device.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartyInterestMigrationTest {

    @Test
    fun `existing party survives the v1 to v2 migration with interest defaulted off`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration-test-${System.nanoTime()}.db"

        // 1. A real v1 database, as an existing install would have.
        val legacyDb = Room.databaseBuilder(context, LegacyDatabaseV1::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        legacyDb.legacyPartyDao().insert(
            LegacyPartyEntityV1(
                id = "party-1",
                ownerPhone = "+919876543210",
                businessId = "biz-1",
                name = "Existing Customer",
                phone = "+919999999999",
                type = "CUSTOMER",
                collectionFrequency = "DAILY",
                address = "",
                gstin = "",
                flatBuilding = "",
                areaLocality = "",
                pincode = "",
                city = "",
                state = "",
                isShippingSame = true,
                currentBalance = 500.0,
                openingBalance = 0.0,
                creditLimit = 5000.0,
                tagsCsv = "",
                avatar = "",
                lastTransactionTime = 1000L,
                createdAt = 1000L,
            )
        )
        legacyDb.close()

        // 2. The real app database class opens that same file and must migrate it.
        val migratedDb = Room.databaseBuilder(context, KhatabookDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(KhatabookDatabase.MIGRATION_1_2)
            .build()

        val survivors = migratedDb.partyDao().getForBusinessSync("+919876543210", "biz-1")
        assertEquals(1, survivors.size)
        val migrated = survivors.first()
        assertEquals("Existing Customer", migrated.name)
        assertEquals(500.0, migrated.currentBalance, 0.0001)
        assertFalse(migrated.interestEnabled)
        assertEquals(0.0, migrated.interestRate, 0.0001)
        assertEquals(0, migrated.interestPeriodValue)
        assertEquals("DAYS", migrated.interestPeriodUnit)

        migratedDb.close()
    }
}
