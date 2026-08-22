package com.example.khatabook.utils

import com.example.khatabook.data.model.Party

/** Outcome of resolving a scanned record to an existing party. */
sealed class PartyMatch {
    /** Confident enough to attach money to this party without asking. */
    data class Matched(val party: Party) : PartyMatch()

    /** Plausible candidates exist, but not confidently one of them. */
    data class Ambiguous(val candidates: List<Party>) : PartyMatch()

    /** Nothing resembles this name; safe to treat as a new customer. */
    object None : PartyMatch()
}

/**
 * Resolves an OCR-scanned customer to an existing party.
 *
 * The failure this prevents: an existing "BHAVISH" holding a ₹1000 balance,
 * scanned as "bhavish", became a *second* customer — leaving the balance
 * stranded on the original record and the new payment on a duplicate.
 *
 * Matching runs strongest-signal-first and stops at the first confident
 * answer:
 *
 *  1. **Phone number.** An identifier, not a description. Compared on the last
 *     10 digits so formatting ("+91 98765 43210" vs "9876543210") is
 *     irrelevant.
 *  2. **Exact canonical name.** Case and spacing normalised
 *     ([PartyNames.matchKey]), so casing alone can never fork a record.
 *  3. **Conservative fuzzy name.** Only where the difference is small enough
 *     to be OCR noise rather than a different person.
 *
 * Anything short of confident returns [PartyMatch.Ambiguous] so a human
 * decides. Silently merging two customers corrupts two ledgers at once, and
 * unlike a duplicate it cannot be spotted afterwards — so the bias is always
 * toward asking.
 */
object PartyMatcher {

    /**
     * Maximum single-character edits for two names to be considered the same.
     *
     * One edit, and only for names long enough that one edit is a small
     * proportion of the whole. This admits genuine OCR slips ("BHAVISH" vs
     * "BHAVISHH") while refusing "RAJESH"/"RAJENDRA" (distance 3) and, just
     * as importantly, short pairs like "RAJ"/"RAM" where a single edit is a
     * third of the name and far more likely to be two real people.
     */
    private const val MAX_EDIT_DISTANCE = 1
    private const val MIN_LENGTH_FOR_FUZZY = 5

    fun match(
        scannedName: String,
        scannedPhone: String,
        candidates: List<Party>
    ): PartyMatch {
        if (candidates.isEmpty()) return PartyMatch.None

        // 1. Phone — the strongest identifier available.
        val phoneKey = phoneKey(scannedPhone)
        if (phoneKey != null) {
            val byPhone = candidates.filter { phoneKey(it.phone) == phoneKey }
            if (byPhone.size == 1) return PartyMatch.Matched(byPhone.first())
            if (byPhone.size > 1) return PartyMatch.Ambiguous(byPhone)
        }

        val scannedKey = PartyNames.matchKey(scannedName)
        if (scannedKey.isEmpty()) return PartyMatch.None

        // 2. Exact canonical name. "bhavish" and "BHAVISH" land here.
        val exact = candidates.filter { PartyNames.matchKey(it.name) == scannedKey }
        if (exact.size == 1) return PartyMatch.Matched(exact.first())
        if (exact.size > 1) return PartyMatch.Ambiguous(exact)

        // 3. Conservative fuzzy. Trusted only when exactly one candidate is
        // close — two near-misses means we genuinely cannot tell them apart.
        val near = candidates.filter { isNearMatch(scannedKey, PartyNames.matchKey(it.name)) }
        if (near.size == 1) return PartyMatch.Matched(near.first())
        if (near.size > 1) return PartyMatch.Ambiguous(near)

        // A scanned name that merely *contains* an existing one ("BHAVISH 10")
        // is reported for confirmation rather than matched or created. The
        // trailing token is usually a stray amount the OCR folded into the
        // name, but it can equally be a real distinguishing suffix
        // ("COUNTER 2"), and the two are indistinguishable from the name
        // alone — so the user decides instead of the parser guessing.
        val overlapping = candidates.filter { sharesLeadingToken(scannedKey, PartyNames.matchKey(it.name)) }
        if (overlapping.isNotEmpty()) return PartyMatch.Ambiguous(overlapping)

        return PartyMatch.None
    }

    /** Last 10 digits, or null when there is no usable number. */
    private fun phoneKey(phone: String?): String? {
        val digits = phone?.filter { it.isDigit() }.orEmpty()
        return if (digits.length >= 10) digits.takeLast(10) else null
    }

    private fun isNearMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < MIN_LENGTH_FOR_FUZZY || b.length < MIN_LENGTH_FOR_FUZZY) return false
        // A length gap wider than the edit budget can't be within it.
        if (kotlin.math.abs(a.length - b.length) > MAX_EDIT_DISTANCE) return false
        return editDistanceAtMost(a, b, MAX_EDIT_DISTANCE)
    }

    /**
     * True when [a] and [b] share their first whitespace-delimited token and
     * differ only by extra trailing tokens — the "BHAVISH" vs "BHAVISH 10"
     * shape.
     */
    private fun sharesLeadingToken(a: String, b: String): Boolean {
        val aTokens = a.split(' ').filter { it.isNotEmpty() }
        val bTokens = b.split(' ').filter { it.isNotEmpty() }
        if (aTokens.isEmpty() || bTokens.isEmpty()) return false
        if (aTokens.size == bTokens.size) return false
        val shorter = if (aTokens.size < bTokens.size) aTokens else bTokens
        val longer = if (aTokens.size < bTokens.size) bTokens else aTokens
        // Require the whole shorter name to prefix the longer one, and the
        // shared part to be substantial — so "A 10" doesn't latch onto "A B".
        if (shorter.first().length < MIN_LENGTH_FOR_FUZZY) return false
        return longer.take(shorter.size) == shorter
    }

    /** Levenshtein distance, short-circuited once it exceeds [max]. */
    private fun editDistanceAtMost(a: String, b: String, max: Int): Boolean {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > max) return false
            previous = current
        }
        return previous[b.length] <= max
    }
}
