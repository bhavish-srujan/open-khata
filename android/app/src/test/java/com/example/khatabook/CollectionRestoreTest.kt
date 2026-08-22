package com.example.khatabook

import com.example.khatabook.data.api.PartyResponse
import com.example.khatabook.data.local.toEntity
import com.example.khatabook.data.model.CollectionFrequency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Collection classification must survive a restore.
 *
 * The bug: this value lived only in Room and was never sent to the server, so
 * PartyResponse.toEntity hardcoded DAILY. A device that lost its local data
 * restored every party into Daily and left Weekly and Monthly empty — the
 * user's own classification of their book, silently flattened.
 */
class CollectionRestoreTest {

    private fun serverParty(
        id: String,
        name: String,
        collectionFrequency: String?,
    ) = PartyResponse(
        id = id,
        name = name,
        phone = "",
        type = "CUSTOMER",
        gstin = null,
        currentBalance = 0.0,
        businessId = "biz-1",
        createdAt = "2026-01-01T00:00:00.000Z",
        collectionFrequency = collectionFrequency,
    )

    // ─── Restore preserves each bucket ──────────────────────────

    @Test
    fun `a daily party restores as daily`() {
        val restored = serverParty("a", "A", "DAILY").toEntity("+91999", "biz-1")

        assertEquals(CollectionFrequency.DAILY.name, restored.collectionFrequency)
    }

    @Test
    fun `a weekly party restores as weekly`() {
        val restored = serverParty("b", "B", "WEEKLY").toEntity("+91999", "biz-1")

        assertEquals(CollectionFrequency.WEEKLY.name, restored.collectionFrequency)
    }

    @Test
    fun `a monthly party restores as monthly`() {
        val restored = serverParty("c", "C", "MONTHLY").toEntity("+91999", "biz-1")

        assertEquals(CollectionFrequency.MONTHLY.name, restored.collectionFrequency)
    }

    @Test
    fun `a mixed dataset restores into three distinct buckets`() {
        val restored = listOf(
            serverParty("a", "A", "DAILY"),
            serverParty("b", "B", "WEEKLY"),
            serverParty("c", "C", "MONTHLY"),
        ).map { it.toEntity("+91999", "biz-1") }

        // The exact failure being pinned: all three used to come back DAILY.
        assertEquals(
            listOf("DAILY", "WEEKLY", "MONTHLY"),
            restored.map { it.collectionFrequency },
        )
        assertEquals(3, restored.map { it.collectionFrequency }.toSet().size)
    }

    // ─── Backward compatibility ─────────────────────────────────

    @Test
    fun `a server row with no classification falls back to daily`() {
        // Rows created before the server stored this. DAILY is how they
        // already behaved, so this is not a change for them.
        val restored = serverParty("d", "D", null).toEntity("+91999", "biz-1")

        assertEquals(CollectionFrequency.DAILY.name, restored.collectionFrequency)
    }

    @Test
    fun `a server row with no classification never overwrites a known local one`() {
        // The regression this prevents: for a party classified WEEKLY before
        // the field reached the server, the server sends null. Treating that
        // as DAILY would silently reclassify it on the very next sync.
        val restored = serverParty("e", "E", null)
            .toEntity("+91999", "biz-1", localFallback = CollectionFrequency.WEEKLY.name)

        assertEquals(CollectionFrequency.WEEKLY.name, restored.collectionFrequency)
    }

    @Test
    fun `an explicit server classification wins over the local one`() {
        // The server is authoritative when it actually knows: a change made on
        // another device must not be reverted by this one's stale copy.
        val restored = serverParty("f", "F", "MONTHLY")
            .toEntity("+91999", "biz-1", localFallback = CollectionFrequency.WEEKLY.name)

        assertEquals(CollectionFrequency.MONTHLY.name, restored.collectionFrequency)
    }

    @Test
    fun `an unrecognised classification degrades to daily rather than crashing`() {
        val restored = serverParty("g", "G", "FORTNIGHTLY").toEntity("+91999", "biz-1")

        // Unknown value is not a crash and not a silent blank.
        assertEquals("FORTNIGHTLY", restored.collectionFrequency)
        assertEquals(
            CollectionFrequency.DAILY,
            runCatching { CollectionFrequency.valueOf(restored.collectionFrequency) }
                .getOrDefault(CollectionFrequency.DAILY),
        )
    }

    // ─── Business isolation ─────────────────────────────────────

    @Test
    fun `restored parties stay scoped to their own owner and business`() {
        val a = serverParty("x", "X", "WEEKLY").toEntity("+91111", "biz-A")
        val b = serverParty("y", "Y", "MONTHLY").toEntity("+91222", "biz-B")

        assertEquals("+91111", a.ownerPhone)
        assertEquals("biz-1", a.businessId) // server-supplied businessId wins
        assertEquals("+91222", b.ownerPhone)
        // Each keeps its own classification — one account's restore cannot
        // reclassify another's.
        assertEquals(CollectionFrequency.WEEKLY.name, a.collectionFrequency)
        assertEquals(CollectionFrequency.MONTHLY.name, b.collectionFrequency)
    }
}
