package com.example.khatabook.utils

import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import kotlin.math.abs

/** What the Parties screen's summary card shows, for one collection scope. */
data class CollectionTotals(val youWillGet: Double, val youWillGive: Double)

/**
 * The You-will-get / You-will-give figures for a business, narrowed to one
 * collection.
 *
 * Extracted from PartiesScreen so the arithmetic can be tested directly
 * rather than only through Compose. The bug it fixes lived exactly here: the
 * summary was computed over every party in the business while the list beside
 * it was filtered by collection, so Weekly parties were shown above totals
 * that included Daily and Monthly.
 *
 * Balance semantics are unchanged and deliberately left alone: for a CUSTOMER
 * a positive balance is money they owe us (get) and a negative one is money we
 * owe them (give). This only decides *which* parties are summed.
 *
 * @param collectionFilter null means "All collections".
 */
fun collectionTotals(
    parties: List<Party>,
    businessId: String,
    collectionFilter: CollectionFrequency?,
): CollectionTotals {
    val scoped = parties.filter { party ->
        party.businessId == businessId &&
            (collectionFilter == null || party.collectionFrequency == collectionFilter) &&
            party.type == PartyType.CUSTOMER
    }
    return CollectionTotals(
        youWillGet = scoped.filter { it.currentBalance > 0 }.sumOf { it.currentBalance },
        youWillGive = scoped.filter { it.currentBalance < 0 }.sumOf { abs(it.currentBalance) },
    )
}
