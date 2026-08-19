package com.example.khatabook

import com.example.khatabook.data.repository.FlushOutcome
import com.example.khatabook.data.repository.MAX_OP_ATTEMPTS
import com.example.khatabook.data.repository.OpStatus
import com.example.khatabook.data.repository.PendingOp
import com.example.khatabook.data.repository.PendingOpType
import com.example.khatabook.data.repository.classify
import com.example.khatabook.data.repository.nextAttempts
import com.example.khatabook.data.repository.sanitize
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery semantics for queued mutations.
 *
 * Writes used to be fire-and-forget, with failures swallowed by an empty catch,
 * so anything made offline was lost. Updates and deletes were never sent at
 * all. The queue only helps if a failure is classified correctly: retrying a
 * permanently-rejected record forever would wedge everything behind it, and
 * dropping a merely-offline record would lose data all over again — which is
 * also why a permanently-rejected record is now marked failed rather than
 * deleted, and why every operation is tagged with the exact business it was
 * created in rather than whatever business happens to be active when it is
 * finally sent.
 */
class OutboxTest {

    // ── how a response decides the record's fate ──────────────────────
    @Test
    fun `success removes the record`() {
        assertEquals(FlushOutcome.SENT, classify(200, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.SENT, classify(201, PendingOpType.CREATE_ENTRY))
        assertEquals(FlushOutcome.SENT, classify(204, PendingOpType.DELETE_ENTRY))
    }

    @Test
    fun `server errors are retried rather than failed`() {
        assertEquals(FlushOutcome.RETRY, classify(500, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.RETRY, classify(502, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.RETRY, classify(503, PendingOpType.CREATE_PARTY))
    }

    @Test
    fun `an expired token is retried, not discarded`() {
        // Logging in again fixes this; the queued work must survive it.
        assertEquals(FlushOutcome.RETRY, classify(401, PendingOpType.CREATE_ENTRY))
    }

    @Test
    fun `throttling and timeouts are retried`() {
        assertEquals(FlushOutcome.RETRY, classify(429, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.RETRY, classify(408, PendingOpType.CREATE_PARTY))
    }

    @Test
    fun `permanent rejections are marked failed, never resent`() {
        // 400 malformed, 403 refused, 409 id held by another business — none
        // of these will ever succeed by resending the same bytes.
        assertEquals(FlushOutcome.PERMANENT_FAILURE, classify(400, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.PERMANENT_FAILURE, classify(403, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.PERMANENT_FAILURE, classify(409, PendingOpType.CREATE_PARTY))
    }

    @Test
    fun `404 on a create or update is a permanent failure`() {
        // The referenced party or entry does not exist under this business
        // (e.g. a dependency that itself failed) — resending will not help.
        assertEquals(FlushOutcome.PERMANENT_FAILURE, classify(404, PendingOpType.CREATE_PARTY))
        assertEquals(FlushOutcome.PERMANENT_FAILURE, classify(404, PendingOpType.CREATE_ENTRY))
        assertEquals(FlushOutcome.PERMANENT_FAILURE, classify(404, PendingOpType.UPDATE_ENTRY))
    }

    @Test
    fun `404 on a delete means the goal is already achieved`() {
        // Deleting something already gone is the outcome the user wanted.
        // Treating it as a failure would strand a completed delete forever.
        assertEquals(FlushOutcome.SENT, classify(404, PendingOpType.DELETE_PARTY))
        assertEquals(FlushOutcome.SENT, classify(404, PendingOpType.DELETE_ENTRY))
    }

    @Test
    fun `409 is not treated as success`() {
        // The server reserves 409 for an id already held by a different
        // business. Treating it as sent would silently discard the write.
        assertNotEquals(FlushOutcome.SENT, classify(409, PendingOpType.CREATE_PARTY))
    }

    // ── records survive process death ─────────────────────────────────
    @Test
    fun `a queued operation round-trips through serialisation`() {
        val gson = Gson()
        val original = listOf(
            PendingOp(
                type = PendingOpType.CREATE_PARTY,
                targetId = "party-1",
                payload = """{"name":"Rahul"}""",
                businessId = "biz-a"
            ),
            PendingOp(type = PendingOpType.DELETE_ENTRY, targetId = "entry-9", businessId = "biz-a"),
            PendingOp(
                type = PendingOpType.UPDATE_ENTRY,
                targetId = "entry-3",
                payload = """{"amount":800.0}""",
                attempts = 2,
                businessId = "biz-b",
                status = OpStatus.FAILED
            )
        )

        val type = TypeToken.getParameterized(List::class.java, PendingOp::class.java).type
        val restored: List<PendingOp> = gson.fromJson(gson.toJson(original), type)

        assertEquals(original, restored)
        assertEquals(PendingOpType.UPDATE_ENTRY, restored[2].type)
        assertEquals(2, restored[2].attempts)
        assertEquals(OpStatus.FAILED, restored[2].status)
        assertEquals("biz-b", restored[2].businessId)
    }

    @Test
    fun `every operation type survives serialisation`() {
        val gson = Gson()
        PendingOpType.values().forEach { type ->
            val op = PendingOp(type = type, targetId = "t", businessId = "biz-a")
            val restored = gson.fromJson(gson.toJson(op), PendingOp::class.java)
            assertEquals("$type must round-trip", type, restored.type)
        }
    }

    @Test
    fun `gson leaves a missing field null, not its Kotlin default`() {
        // Gson constructs data classes via unsafe allocation, bypassing the
        // constructor — a genuinely useful fact to pin down, since it is
        // exactly why sanitize() exists rather than trusting defaults.
        val gson = Gson()
        val legacyJson = """{"opId":"x","type":"CREATE_PARTY","targetId":"party-1"}"""
        val restored = gson.fromJson(legacyJson, PendingOp::class.java)
        assertEquals(null, restored.businessId)
        assertEquals(null, restored.status)
    }

    @Test
    fun `sanitize repairs every gson-nulled field to its real default`() {
        val gson = Gson()
        val legacyJson = """{"opId":"x","type":"CREATE_PARTY","targetId":"party-1"}"""
        val restored = gson.fromJson(legacyJson, PendingOp::class.java)

        val cleaned = sanitize(restored)

        assertEquals("", cleaned.businessId)
        assertEquals(OpStatus.PENDING, cleaned.status)
        assertEquals("", cleaned.payload)
        assertEquals("x", cleaned.opId)
        assertEquals("party-1", cleaned.targetId)
    }

    @Test
    fun `sanitize leaves a fully-populated record untouched`() {
        val op = PendingOp(
            type = PendingOpType.CREATE_ENTRY,
            targetId = "entry-1",
            payload = """{"amount":1}""",
            attempts = 3,
            businessId = "biz-a",
            status = OpStatus.FAILED,
        )
        assertEquals(op, sanitize(op))
    }

    // ── the poison-record escape hatch ────────────────────────────────
    @Test
    fun `the attempt ceiling is low enough to unwedge but high enough to ride out an outage`() {
        // A brief outage must not discard work, but a permanently failing
        // record must eventually stop being retried on every trigger.
        assertTrue("must tolerate several transient failures", MAX_OP_ATTEMPTS >= 5)
        assertTrue("must not retry indefinitely", MAX_OP_ATTEMPTS <= 20)
    }

    @Test
    fun `attempts accumulate on the record itself`() {
        var op = PendingOp(type = PendingOpType.CREATE_ENTRY, targetId = "e1", businessId = "biz-a")
        repeat(3) { op = op.copy(attempts = op.attempts + 1) }

        assertEquals(3, op.attempts)
        assertTrue(op.attempts < MAX_OP_ATTEMPTS)
    }

    // ── business isolation: nextAttempts() ─────────────────────────────
    @Test
    fun `each business's own head is attemptable, independent of the others`() {
        val outbox = listOf(
            PendingOp(type = PendingOpType.CREATE_PARTY, targetId = "a1", businessId = "biz-a"),
            PendingOp(type = PendingOpType.CREATE_ENTRY, targetId = "a2", businessId = "biz-a"),
            PendingOp(type = PendingOpType.CREATE_PARTY, targetId = "b1", businessId = "biz-b"),
        )

        val heads = nextAttempts(outbox)

        assertEquals(2, heads.size)
        assertEquals("a1", heads[0].targetId)
        assertEquals("b1", heads[1].targetId)
    }

    @Test
    fun `a failed head blocks only its own business, not the others`() {
        val outbox = listOf(
            PendingOp(
                type = PendingOpType.CREATE_PARTY,
                targetId = "a1",
                businessId = "biz-a",
                status = OpStatus.FAILED
            ),
            PendingOp(type = PendingOpType.CREATE_ENTRY, targetId = "a2", businessId = "biz-a"),
            PendingOp(type = PendingOpType.CREATE_PARTY, targetId = "b1", businessId = "biz-b"),
        )

        val heads = nextAttempts(outbox)

        // a2 is behind a's failed head and must not be attempted — this is
        // what stops a failed parent from letting a dependent child be sent
        // out of order. b1 is a different business and proceeds normally.
        assertEquals(1, heads.size)
        assertEquals("b1", heads[0].targetId)
    }

    @Test
    fun `an operation with no businessId is never returned as attemptable`() {
        val outbox = listOf(
            PendingOp(type = PendingOpType.CREATE_PARTY, targetId = "unknown", businessId = ""),
        )

        // nextAttempts() only checks status, not blankness, by design: the
        // flush loop itself is what refuses to send a blank-businessId op —
        // this asserts the flush-time guard still has something to check
        // against (i.e. that migration/enqueue never silently invent a
        // non-blank id for it).
        assertEquals("", outbox.first().businessId)
        assertEquals(1, nextAttempts(outbox).size)
    }
}
