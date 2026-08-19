package com.example.khatabook.data.repository

import androidx.annotation.Keep
import java.util.UUID

/**
 * A mutation that still has to reach the server.
 *
 * Writes were previously fire-and-forget: the call was launched into a
 * background scope and its failure swallowed by an empty catch, so anything
 * made while offline was simply lost. Updates and deletes were never sent at
 * all. These records are persisted alongside the data itself, so a mutation
 * survives the process being killed.
 */
@Keep
enum class PendingOpType {
    CREATE_PARTY,
    UPDATE_PARTY_INTEREST,
    DELETE_PARTY,
    CREATE_ENTRY,
    UPDATE_ENTRY,
    DELETE_ENTRY
}

/**
 * Where a queued operation stands.
 *
 * FAILED is terminal but not destructive: the record stays in the queue,
 * visible via [com.example.khatabook.data.repository.KhatabookRepository.failedOperationCount],
 * instead of being deleted. A financial mutation the user made is never
 * discarded just because it could not be delivered — only once it is
 * confirmed delivered (or confirmed already-achieved, e.g. deleting
 * something already gone) does the record disappear.
 */
@Keep
enum class OpStatus { PENDING, FAILED }

@Keep
data class PendingOp(
    val opId: String = UUID.randomUUID().toString(),
    val type: PendingOpType,
    /** Party id or entry id, depending on [type]. */
    val targetId: String,
    /** Gson-serialised request body. Empty for deletes, which carry none. */
    val payload: String = "",
    /** Failed sends so far, used to decide when to stop auto-retrying. */
    val attempts: Int = 0,
    /**
     * The business this operation was made in, fixed at the moment it was
     * queued. An operation is sent under this id for its entire life, no
     * matter what business becomes active later — it is never re-derived
     * from whatever happens to be active when the flush runs. Blank means
     * "unknown" (only possible for a record queued before this field
     * existed); such a record is never sent, only marked [OpStatus.FAILED],
     * because guessing which business it belonged to is exactly the bug
     * this field exists to prevent.
     */
    val businessId: String = "",
    val status: OpStatus = OpStatus.PENDING,
    /**
     * Gson-serialised [com.example.khatabook.data.repository.MutationSnapshot],
     * captured at the moment of the optimistic local mutation for
     * DELETE_PARTY, DELETE_ENTRY and UPDATE_ENTRY. Used to restore local state
     * when the server refuses the operation with 403 — the caller's role does
     * not permit it — so the device does not keep showing a mutation the
     * server never actually applied. Null for operation types that have
     * nothing to roll back (a rejected create simply leaves the locally
     * fabricated row queued and visible as failed).
     */
    val rollbackPayload: String? = null
)

/**
 * Repairs a value straight out of [com.google.gson.Gson].
 *
 * Gson constructs data classes via unsafe allocation, bypassing the
 * constructor entirely — so a field absent from JSON persisted before it
 * existed comes back as a JVM-default null, never the Kotlin default declared
 * in the constructor, no matter the field's declared non-null type. Trusting
 * that declared type on a freshly-deserialised [PendingOp] would risk a
 * `NullPointerException` the first time such a field is read (e.g.
 * `businessId.isBlank()`), so every load goes through this first.
 */
@Suppress("SENSELESS_COMPARISON")
internal fun sanitize(op: PendingOp): PendingOp = op.copy(
    opId = if (op.opId == null) UUID.randomUUID().toString() else op.opId,
    payload = if (op.payload == null) "" else op.payload,
    businessId = if (op.businessId == null) "" else op.businessId,
    status = if (op.status == null) OpStatus.PENDING else op.status,
)

/** What to do with an operation after attempting it. */
internal enum class FlushOutcome { SENT, RETRY, PERMANENT_FAILURE }

/**
 * After this many failed attempts a still-failing operation is marked
 * [OpStatus.FAILED] rather than kept retrying on every flush trigger. It is
 * not deleted, and a fresh login retries every failed operation (see
 * [com.example.khatabook.data.repository.KhatabookRepository.retryFailedOperations]),
 * so this only bounds how often an already-known-bad request is repeated,
 * never whether the underlying write is kept.
 */
internal const val MAX_OP_ATTEMPTS = 8

/**
 * Classifies an HTTP status for a given operation type.
 *
 * 401 is retryable rather than terminal: the token is refreshed by logging in
 * again, and the pending work must survive that. Other 4xx responses mean the
 * server will not accept this exact request again, so they are terminal — but
 * terminal now means [FlushOutcome.PERMANENT_FAILURE] (kept, marked failed),
 * not discarded. The one exception is a 404 on a delete: the target is
 * already gone, which is the outcome the delete wanted, so it counts as sent.
 */
internal fun classify(code: Int, opType: PendingOpType): FlushOutcome = when {
    // A replayed create returns 200 with the stored row, so success covers the
    // "sent but the response was lost" case too.
    code in 200..299 -> FlushOutcome.SENT
    code == 404 && (opType == PendingOpType.DELETE_PARTY || opType == PendingOpType.DELETE_ENTRY) ->
        FlushOutcome.SENT
    code == 401 -> FlushOutcome.RETRY
    code == 408 || code == 429 -> FlushOutcome.RETRY
    code >= 500 -> FlushOutcome.RETRY
    // Everything else, 409 included, will not succeed by resending the same
    // bytes. The server reserves 409 for an id already held by a different
    // business, which — now that every send carries its operation's own,
    // fixed businessId — should not happen in normal operation.
    else -> FlushOutcome.PERMANENT_FAILURE
}

/**
 * The operations eligible to be attempted right now: the first PENDING
 * operation of each business's sub-sequence, in the order those businesses
 * first appear in the queue.
 *
 * A business whose head is FAILED is skipped entirely — nothing queued
 * behind a blocked operation for that business is ever attempted, so a
 * failed parent (e.g. a party create) cannot cause a dependent child (e.g.
 * its ledger entries) to be sent out of order and rejected. Other
 * businesses are unaffected: one business's stuck operation never blocks
 * another business's queue.
 */
internal fun nextAttempts(outbox: List<PendingOp>): List<PendingOp> {
    val seenBusiness = mutableSetOf<String>()
    val heads = mutableListOf<PendingOp>()
    for (op in outbox) {
        if (!seenBusiness.add(op.businessId)) continue
        if (op.status == OpStatus.PENDING) heads.add(op)
    }
    return heads
}
