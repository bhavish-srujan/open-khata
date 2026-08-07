package com.example.khatabook

import com.example.khatabook.data.repository.FlushOutcome
import com.example.khatabook.data.repository.MAX_OP_ATTEMPTS
import com.example.khatabook.data.repository.PendingOp
import com.example.khatabook.data.repository.PendingOpType
import com.example.khatabook.data.repository.classify
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
 * dropping a merely-offline record would lose data all over again.
 */
class OutboxTest {

    // ── how a response decides the record's fate ──────────────────────
    @Test
    fun `success removes the record`() {
        assertEquals(FlushOutcome.SENT, classify(200))
        assertEquals(FlushOutcome.SENT, classify(201))
        assertEquals(FlushOutcome.SENT, classify(204))
    }

    @Test
    fun `server errors are retried rather than dropped`() {
        assertEquals(FlushOutcome.RETRY, classify(500))
        assertEquals(FlushOutcome.RETRY, classify(502))
        assertEquals(FlushOutcome.RETRY, classify(503))
    }

    @Test
    fun `an expired token is retried, not discarded`() {
        // Logging in again fixes this; the queued work must survive it.
        assertEquals(FlushOutcome.RETRY, classify(401))
    }

    @Test
    fun `throttling and timeouts are retried`() {
        assertEquals(FlushOutcome.RETRY, classify(429))
        assertEquals(FlushOutcome.RETRY, classify(408))
    }

    @Test
    fun `permanent rejections are dropped so they cannot wedge the queue`() {
        // 400 malformed, 403 refused, 404 target gone, 409 id held by another
        // business — none of these will ever succeed on retry.
        assertEquals(FlushOutcome.DROP, classify(400))
        assertEquals(FlushOutcome.DROP, classify(403))
        assertEquals(FlushOutcome.DROP, classify(404))
        assertEquals(FlushOutcome.DROP, classify(409))
    }

    @Test
    fun `409 is not treated as success`() {
        // The server reserves 409 for an id already held by a different
        // business. Treating it as sent would silently discard the write.
        assertNotEquals(FlushOutcome.SENT, classify(409))
    }

    // ── records survive process death ─────────────────────────────────
    @Test
    fun `a queued operation round-trips through serialisation`() {
        val gson = Gson()
        val original = listOf(
            PendingOp(type = PendingOpType.CREATE_PARTY, targetId = "party-1", payload = """{"name":"Rahul"}"""),
            PendingOp(type = PendingOpType.DELETE_ENTRY, targetId = "entry-9"),
            PendingOp(type = PendingOpType.UPDATE_ENTRY, targetId = "entry-3", payload = """{"amount":800.0}""", attempts = 2)
        )

        val type = TypeToken.getParameterized(List::class.java, PendingOp::class.java).type
        val restored: List<PendingOp> = gson.fromJson(gson.toJson(original), type)

        assertEquals(original, restored)
        assertEquals(PendingOpType.UPDATE_ENTRY, restored[2].type)
        assertEquals(2, restored[2].attempts)
    }

    @Test
    fun `every operation type survives serialisation`() {
        val gson = Gson()
        PendingOpType.values().forEach { type ->
            val op = PendingOp(type = type, targetId = "t")
            val restored = gson.fromJson(gson.toJson(op), PendingOp::class.java)
            assertEquals("$type must round-trip", type, restored.type)
        }
    }

    // ── the poison-record escape hatch ────────────────────────────────
    @Test
    fun `the attempt ceiling is low enough to unwedge but high enough to ride out an outage`() {
        // A brief outage must not discard work, but a permanently failing
        // record must eventually stop blocking the queue.
        assertTrue("must tolerate several transient failures", MAX_OP_ATTEMPTS >= 5)
        assertTrue("must not retry indefinitely", MAX_OP_ATTEMPTS <= 20)
    }

    @Test
    fun `attempts accumulate on the record itself`() {
        var op = PendingOp(type = PendingOpType.CREATE_ENTRY, targetId = "e1")
        repeat(3) { op = op.copy(attempts = op.attempts + 1) }

        assertEquals(3, op.attempts)
        assertTrue(op.attempts < MAX_OP_ATTEMPTS)
    }
}
