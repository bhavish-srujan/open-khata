package com.example.khatabook

import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.utils.collectionTotals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Parties screen's You-will-get / You-will-give summary, per collection.
 *
 * The bug: the list was filtered by the selected collection while the summary
 * was summed over every party in the business, so a user looking at Weekly
 * parties saw totals that silently included Daily and Monthly.
 */
class CollectionTotalsTest {

    private val biz = "biz-1"

    private fun customer(
        name: String,
        balance: Double,
        frequency: CollectionFrequency,
        businessId: String = biz,
    ) = Party(
        id = name,
        name = name,
        phone = "",
        type = PartyType.CUSTOMER,
        businessId = businessId,
        collectionFrequency = frequency,
        currentBalance = balance,
    )

    /** The worked example from the bug report. */
    private fun sampleBook() = listOf(
        customer("A", 1000.0, CollectionFrequency.DAILY),    // get 1000
        customer("B", -500.0, CollectionFrequency.DAILY),    // give 500
        customer("C", 2000.0, CollectionFrequency.WEEKLY),   // get 2000
        customer("D", -300.0, CollectionFrequency.WEEKLY),   // give 300
        customer("E", 5000.0, CollectionFrequency.MONTHLY),  // get 5000
        customer("F", -1000.0, CollectionFrequency.MONTHLY), // give 1000
    )

    // ─── One bucket at a time ───────────────────────────────────

    @Test
    fun `all collections sum everything`() {
        val t = collectionTotals(sampleBook(), biz, null)

        assertEquals(8000.0, t.youWillGet, 0.001)
        assertEquals(1800.0, t.youWillGive, 0.001)
    }

    @Test
    fun `daily sums only daily parties`() {
        val t = collectionTotals(sampleBook(), biz, CollectionFrequency.DAILY)

        assertEquals(1000.0, t.youWillGet, 0.001)
        assertEquals(500.0, t.youWillGive, 0.001)
    }

    @Test
    fun `weekly sums only weekly parties`() {
        val t = collectionTotals(sampleBook(), biz, CollectionFrequency.WEEKLY)

        assertEquals(2000.0, t.youWillGet, 0.001)
        assertEquals(300.0, t.youWillGive, 0.001)
    }

    @Test
    fun `monthly sums only monthly parties`() {
        val t = collectionTotals(sampleBook(), biz, CollectionFrequency.MONTHLY)

        assertEquals(5000.0, t.youWillGet, 0.001)
        assertEquals(1000.0, t.youWillGive, 0.001)
    }

    @Test
    fun `the three buckets add up to the all total`() {
        val book = sampleBook()
        val all = collectionTotals(book, biz, null)
        val parts = CollectionFrequency.values().map { collectionTotals(book, biz, it) }

        // Nothing double-counted, nothing dropped.
        assertEquals(all.youWillGet, parts.sumOf { it.youWillGet }, 0.001)
        assertEquals(all.youWillGive, parts.sumOf { it.youWillGive }, 0.001)
    }

    // ─── Switching filters ──────────────────────────────────────

    @Test
    fun `switching between filters recomputes from the same data`() {
        val book = sampleBook()

        // Each call is pure over the same list — nothing cached between them,
        // which is what makes the UI update on the tap rather than on a
        // refresh.
        assertEquals(8000.0, collectionTotals(book, biz, null).youWillGet, 0.001)
        assertEquals(1000.0, collectionTotals(book, biz, CollectionFrequency.DAILY).youWillGet, 0.001)
        assertEquals(2000.0, collectionTotals(book, biz, CollectionFrequency.WEEKLY).youWillGet, 0.001)
        assertEquals(5000.0, collectionTotals(book, biz, CollectionFrequency.MONTHLY).youWillGet, 0.001)
    }

    // ─── Empty and edge cases ───────────────────────────────────

    @Test
    fun `an empty collection is zero, not another collection's total`() {
        val onlyDaily = listOf(customer("A", 1000.0, CollectionFrequency.DAILY))

        val weekly = collectionTotals(onlyDaily, biz, CollectionFrequency.WEEKLY)

        assertEquals(0.0, weekly.youWillGet, 0.001)
        assertEquals(0.0, weekly.youWillGive, 0.001)
    }

    @Test
    fun `no parties at all is zero`() {
        val t = collectionTotals(emptyList(), biz, CollectionFrequency.DAILY)

        assertEquals(0.0, t.youWillGet, 0.001)
        assertEquals(0.0, t.youWillGive, 0.001)
    }

    @Test
    fun `a settled party contributes to neither side`() {
        val book = listOf(customer("S", 0.0, CollectionFrequency.DAILY))

        val t = collectionTotals(book, biz, CollectionFrequency.DAILY)

        assertEquals(0.0, t.youWillGet, 0.001)
        assertEquals(0.0, t.youWillGive, 0.001)
    }

    @Test
    fun `suppliers are excluded, exactly as before`() {
        // The summary card has always been about customers; this fix changes
        // which parties are in scope, not that rule.
        val book = listOf(
            customer("A", 1000.0, CollectionFrequency.DAILY),
            Party(
                id = "SUP", name = "SUP", phone = "", type = PartyType.SUPPLIER,
                businessId = biz, collectionFrequency = CollectionFrequency.DAILY,
                currentBalance = 9999.0,
            ),
        )

        assertEquals(1000.0, collectionTotals(book, biz, CollectionFrequency.DAILY).youWillGet, 0.001)
    }

    // ─── Isolation ──────────────────────────────────────────────

    @Test
    fun `another business's parties never contribute`() {
        val book = listOf(
            customer("MINE", 100.0, CollectionFrequency.WEEKLY),
            customer("THEIRS", 9999.0, CollectionFrequency.WEEKLY, businessId = "biz-2"),
        )

        assertEquals(100.0, collectionTotals(book, biz, CollectionFrequency.WEEKLY).youWillGet, 0.001)
        assertEquals(9999.0, collectionTotals(book, "biz-2", CollectionFrequency.WEEKLY).youWillGet, 0.001)
    }

    @Test
    fun `switching business scope does not mix totals`() {
        val book = listOf(
            customer("A", 1000.0, CollectionFrequency.DAILY),
            customer("B", -250.0, CollectionFrequency.DAILY, businessId = "biz-2"),
        )

        val a = collectionTotals(book, biz, null)
        val b = collectionTotals(book, "biz-2", null)

        assertEquals(1000.0, a.youWillGet, 0.001)
        assertEquals(0.0, a.youWillGive, 0.001)
        assertEquals(0.0, b.youWillGet, 0.001)
        assertEquals(250.0, b.youWillGive, 0.001)
    }

    @Test
    fun `a restored classification drives the totals`() {
        // Restore writes collectionFrequency onto the party; the summary reads
        // that same field, so a restored book buckets exactly as it did before.
        val restored = listOf(
            customer("R1", 700.0, CollectionFrequency.MONTHLY),
            customer("R2", 300.0, CollectionFrequency.DAILY),
        )

        assertEquals(700.0, collectionTotals(restored, biz, CollectionFrequency.MONTHLY).youWillGet, 0.001)
        assertEquals(300.0, collectionTotals(restored, biz, CollectionFrequency.DAILY).youWillGet, 0.001)
    }

    @Test
    fun `party balances are read, never modified`() {
        val book = sampleBook()
        val before = book.map { it.id to it.currentBalance }

        collectionTotals(book, biz, null)
        collectionTotals(book, biz, CollectionFrequency.WEEKLY)

        assertEquals(before, book.map { it.id to it.currentBalance })
    }
}
