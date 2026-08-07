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
    DELETE_PARTY,
    CREATE_ENTRY,
    UPDATE_ENTRY,
    DELETE_ENTRY
}

@Keep
data class PendingOp(
    val opId: String = UUID.randomUUID().toString(),
    val type: PendingOpType,
    /** Party id or entry id, depending on [type]. */
    val targetId: String,
    /** Gson-serialised request body. Empty for deletes, which carry none. */
    val payload: String = "",
    /** Failed sends so far, used to discard a poison record. */
    val attempts: Int = 0
)

/**
 * What to do with an operation after attempting it.
 *
 * RETRY stops the whole flush rather than skipping ahead, because order
 * matters: an entry's create must not be sent before its party's.
 */
internal enum class FlushOutcome { SENT, RETRY, DROP }

/** Discarded after this many failures so one bad record cannot wedge the queue. */
internal const val MAX_OP_ATTEMPTS = 8

/**
 * Classifies an HTTP status.
 *
 * 401 is retryable rather than fatal: the token is refreshed by logging in
 * again, and the pending work should survive that. Other 4xx responses mean the
 * server will never accept this record, so it is dropped instead of retried
 * forever.
 */
internal fun classify(code: Int): FlushOutcome = when {
    // A replayed create returns 200 with the stored row, so success covers the
    // "sent but the response was lost" case too.
    code in 200..299 -> FlushOutcome.SENT
    code == 401 -> FlushOutcome.RETRY
    code == 408 || code == 429 -> FlushOutcome.RETRY
    code >= 500 -> FlushOutcome.RETRY
    // Everything else, 409 included, is permanent. The server reserves 409 for
    // an id already held by a different business, which no retry will resolve.
    else -> FlushOutcome.DROP
}
