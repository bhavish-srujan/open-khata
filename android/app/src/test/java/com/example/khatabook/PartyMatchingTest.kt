package com.example.khatabook

import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.utils.PartyMatch
import com.example.khatabook.utils.PartyMatcher
import com.example.khatabook.utils.PartyNames
import com.example.khatabook.utils.isDialable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name canonicalisation and Scan Khata customer matching.
 *
 * The defect being pinned: an existing "BHAVISH" with a ₹1000 balance, scanned
 * as "bhavish", used to become a second customer — stranding the balance on
 * one record and the payment on another.
 */
class PartyMatchingTest {

    private fun party(name: String, phone: String = "", id: String = name) =
        Party(id = id, name = name, phone = phone, type = PartyType.CUSTOMER, businessId = "biz")

    // ─── Canonical form ─────────────────────────────────────────

    @Test
    fun `casing variants all normalise to one canonical name`() {
        assertEquals("BHAVISH", PartyNames.normalize("bhavish"))
        assertEquals("BHAVISH", PartyNames.normalize("Bhavish"))
        assertEquals("BHAVISH", PartyNames.normalize("BHAVISH"))
        assertEquals("BHAVISH", PartyNames.normalize("  bHaViSh  "))
    }

    @Test
    fun `internal whitespace is collapsed so OCR spacing does not fork a record`() {
        assertEquals("RAVI KUMAR", PartyNames.normalize("RAVI  KUMAR"))
        assertEquals(PartyNames.matchKey("RAVI KUMAR"), PartyNames.matchKey("ravi   kumar"))
    }

    @Test
    fun `digits are preserved because they distinguish real customers`() {
        // COUNTER 2 and COUNTER 3 are different shops; stripping digits would
        // silently merge them.
        assertTrue(PartyNames.matchKey("COUNTER 2") != PartyNames.matchKey("COUNTER 3"))
    }

    // ─── Matching priority ──────────────────────────────────────

    @Test
    fun `phone number wins over name`() {
        val candidates = listOf(
            party("BHAVISH", "9876543210", id = "by-phone"),
            party("SOMEONE ELSE", "9000000000", id = "other")
        )

        // Name disagrees, phone agrees — phone is the identifier.
        val result = PartyMatcher.match("B", "+91 98765 43210", candidates)

        assertEquals("by-phone", (result as PartyMatch.Matched).party.id)
    }

    @Test
    fun `phone matching tolerates formatting differences`() {
        val candidates = listOf(party("BHAVISH", "+919876543210", id = "p"))

        val result = PartyMatcher.match("UNRELATED", "09876543210", candidates)

        assertEquals("p", (result as PartyMatch.Matched).party.id)
    }

    @Test
    fun `lowercase OCR name matches the existing uppercase customer`() {
        val existing = party("BHAVISH", id = "existing")

        val result = PartyMatcher.match("bhavish", "", listOf(existing))

        assertEquals("existing", (result as PartyMatch.Matched).party.id)
    }

    @Test
    fun `existing balance is untouched because the same party id is returned`() {
        val existing = party("BHAVISH", id = "party-1").copy(currentBalance = 1000.0)

        val result = PartyMatcher.match("bhavish", "", listOf(existing)) as PartyMatch.Matched

        assertEquals("party-1", result.party.id)
        assertEquals(1000.0, result.party.currentBalance, 0.0001)
    }

    // ─── Conservative fuzzy behaviour ───────────────────────────

    @Test
    fun `genuinely different similar names are never auto-merged`() {
        val candidates = listOf(party("RAJESH"), party("RAJENDRA"))

        val result = PartyMatcher.match("RAJENDRA", "", listOf(party("RAJESH")))

        // RAJESH is 3 edits from RAJENDRA — must not match.
        assertTrue(result is PartyMatch.None)
        // And with both present, neither is silently chosen.
        val both = PartyMatcher.match("RAJESH", "", candidates)
        assertEquals("RAJESH", (both as PartyMatch.Matched).party.name)
    }

    @Test
    fun `short names are not fuzzy matched at all`() {
        // RAJ vs RAM is one edit, but on a 3-letter name that is far more
        // likely to be two different people than an OCR slip.
        val result = PartyMatcher.match("RAJ", "", listOf(party("RAM")))

        assertTrue(result is PartyMatch.None)
    }

    @Test
    fun `a single-character OCR slip on a long name is matched`() {
        val result = PartyMatcher.match("BHAVISHH", "", listOf(party("BHAVISH", id = "b")))

        assertEquals("b", (result as PartyMatch.Matched).party.id)
    }

    @Test
    fun `two equally-close candidates are reported as ambiguous, not guessed`() {
        val candidates = listOf(party("RAVIN", id = "a"), party("RAVIR", id = "b"))

        val result = PartyMatcher.match("RAVIS", "", candidates)

        assertTrue("expected Ambiguous but was $result", result is PartyMatch.Ambiguous)
        assertEquals(2, (result as PartyMatch.Ambiguous).candidates.size)
    }

    // ─── The "bhavish 10" case ──────────────────────────────────

    @Test
    fun `name with a trailing stray token is queued for confirmation, not created blindly`() {
        val existing = party("BHAVISH", id = "existing")

        val result = PartyMatcher.match("bhavish 10", "", listOf(existing))

        // Critically: not None (which would create "BHAVISH 10" as a duplicate)
        // and not a silent match (the token might be a real suffix).
        assertTrue("expected Ambiguous but was $result", result is PartyMatch.Ambiguous)
        assertEquals("existing", (result as PartyMatch.Ambiguous).candidates.single().id)
    }

    @Test
    fun `an unrelated scanned name creates nothing to match against`() {
        val result = PartyMatcher.match("COMPLETELY DIFFERENT", "", listOf(party("BHAVISH")))

        assertTrue(result is PartyMatch.None)
    }

    @Test
    fun `no candidates means no match`() {
        assertTrue(PartyMatcher.match("ANY", "", emptyList()) is PartyMatch.None)
    }

    // ─── Dialability ────────────────────────────────────────────

    @Test
    fun `dial button is offered only for real numbers`() {
        assertTrue(isDialable("9876543210"))
        assertTrue(isDialable("+91 98765 43210"))
        assertFalse(isDialable(""))
        assertFalse(isDialable(null))
        assertFalse(isDialable("   "))
        // Fragments left by imports are not dialable.
        assertFalse(isDialable("+91"))
        // The legacy Scan Khata placeholder is not a real number.
        assertFalse(isDialable("+910000000000"))
    }
}
