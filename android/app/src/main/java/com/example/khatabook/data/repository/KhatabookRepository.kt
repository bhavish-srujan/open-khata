package com.example.khatabook.data.repository

import com.example.khatabook.data.api.CreateLedgerEntryRequest
import com.example.khatabook.data.api.CreateBusinessRequest
import com.example.khatabook.data.api.CreatePartyRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.api.UpdateLedgerEntryRequest
import com.example.khatabook.data.api.UpdatePartyInterestRequest
import com.example.khatabook.data.api.UpdateBusinessRequest
import com.example.khatabook.data.api.UpdateUserRequest
import com.example.khatabook.data.api.LedgerFeedEntry
import com.example.khatabook.data.api.LedgerEntryResponse
import com.example.khatabook.data.api.PartyResponse
import com.example.khatabook.data.api.InventoryItemResponse
import com.example.khatabook.data.api.StaffMemberResponse
import com.example.khatabook.data.api.BillResponse
import com.example.khatabook.data.api.CashflowResponse
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.EntrySource
import com.example.khatabook.data.model.EntryStatus
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.local.KhatabookDatabase
import com.example.khatabook.data.local.entity.SyncStateEntity
import com.example.khatabook.data.local.toDomain
import com.example.khatabook.data.local.toEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.content.Context
import com.example.khatabook.utils.SmsSender
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pre-mutation state captured at the moment of an optimistic local write, so a
 * server-confirmed authorization refusal (403) can be undone precisely rather
 * than guessed at. [party] holds the affected party's row exactly as it was
 * before the mutation; [entry] holds the affected ledger entry (absent for
 * DELETE_PARTY, whose entries are covered by [relatedEntries] instead).
 */
internal data class MutationSnapshot(
    val party: Party? = null,
    val entry: LedgerEntry? = null,
    val relatedEntries: List<LedgerEntry> = emptyList()
)

/**
 * A scanned collection row that matched several existing customers closely
 * enough that picking one automatically would be a guess.
 *
 * Carries everything needed to complete the entry once the user chooses, so
 * resolving it never requires re-scanning the sheet.
 */
data class AmbiguousScanRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val scannedName: String,
    val amount: Double,
    val date: String,
    val businessId: String,
    /** Existing parties to offer, by id — resolved against live state at
     *  display time so a deleted party cannot be shown as a choice. */
    val candidateIds: List<String>
)

/**
 * How long a cached cashflow report is trusted before a background refresh
 * is due. Short on purpose: unlike parties/entries/items/staff, a report is a
 * derived figure over financial data, and this app has no server-push to say
 * "the numbers behind this changed" — a short TTL bounds how stale a report
 * can silently be, on top of the explicit invalidation in [KhatabookRepository]
 * whenever a ledger mutation happens locally.
 */
internal const val CASHFLOW_TTL_MS = 2 * 60 * 1000L

/**
 * Legacy stand-in phone number, retained for records already stored with it.
 *
 * It existed because the backend's CreatePartyDto used to require a
 * non-blank, phone-shaped value, so a Scan Khata party with no number on the
 * sheet had to send *something*. The phone is optional on both sides now and
 * new parties store a genuinely blank value instead — a fabricated number is
 * indistinguishable from a real one downstream, so the app would happily try
 * to dial or message it.
 *
 * Still referenced (not deleted) because deployed devices and the production
 * database hold parties carrying this value: every "is this a real, contactable
 * number?" check must keep excluding it.
 */
internal const val PLACEHOLDER_CUSTOMER_PHONE = "+910000000000"

/**
 * What the "Send transaction SMS" toggle means for an account that has never
 * configured it — off.
 *
 * Only a *default*: SharedPreferences returns a stored value whenever one
 * exists, so an existing user who explicitly turned the toggle on keeps it on.
 * This changes nothing for them; it only stops a brand-new account from
 * messaging customers before its owner has asked for that.
 *
 * Every read of the key goes through this constant so the default cannot
 * drift between the repository, the settings screen and the entry dialog —
 * which is exactly how the key's business-scoping bug went unnoticed before.
 */
internal const val TRANSACTION_SMS_DEFAULT = false

/**
 * Production persistence for [KhatabookRepository]: `EncryptedSharedPreferences`
 * backed by the real AndroidKeyStore, with a one-time migration from the old
 * plaintext prefs file. Unchanged from what [KhatabookRepository] used to build
 * inline — pulled out to a standalone function only so it can be the *default*
 * for [KhatabookRepository]'s `prefsFactory` parameter rather than the only
 * option, which is what let JVM unit tests substitute a real, deterministic,
 * in-memory [SharedPreferences] (see `FakeSharedPreferences` in the test
 * sources) instead of hitting AndroidKeyStore, unavailable outside a real
 * device/emulator.
 */
internal fun createEncryptedPrefs(context: Context): SharedPreferences {
    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    val encryptedPrefs = EncryptedSharedPreferences.create(
        "khatabook_repository_prefs_enc",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // One-time migration from plaintext prefs to encrypted prefs
    val oldPrefsFile = File(context.applicationInfo.dataDir, "shared_prefs/khatabook_repository_prefs.xml")
    if (oldPrefsFile.exists()) {
        val oldPrefs = context.getSharedPreferences("khatabook_repository_prefs", Context.MODE_PRIVATE)
        val editor = encryptedPrefs.edit()
        oldPrefs.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.apply()

        // Delete the old plaintext file to avoid leaving sensitive data behind
        oldPrefs.edit().clear().apply() // clear in memory and disk
        oldPrefsFile.delete()
    }

    return encryptedPrefs
}

class KhatabookRepository(
    private val context: Context,
    prefsFactory: (Context) -> SharedPreferences = ::createEncryptedPrefs,
    databaseFactory: (Context) -> KhatabookDatabase = KhatabookDatabase::getInstance,
) {

    /**
     * The persistent local-first store for all domain data (businesses,
     * parties, ledger entries, items, staff, bills, Outbox) — see
     * [KhatabookDatabase]'s class doc. [prefs] remains only for genuine
     * lightweight app preferences (currently just the custom server URL);
     * see loadData()/saveData() for where each domain type now lives.
     */
    private val db: KhatabookDatabase by lazy { databaseFactory(context) }
    private val prefs: SharedPreferences by lazy { prefsFactory(context) }
    private val gson = Gson()

    private val _parties = MutableStateFlow<List<Party>>(emptyList())
    val parties: StateFlow<List<Party>> = _parties.asStateFlow()

    private val _ledgerEntries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val ledgerEntries: StateFlow<List<LedgerEntry>> = _ledgerEntries.asStateFlow()

    // ─── Caches for screens that used to call NetworkClient directly ──────
    //
    // Cashbook, Bills, Items, Staff and the report screens each fetched their
    // own data from a bare LaunchedEffect(Unit)/LaunchedEffect(key) with no
    // persistence at all: every visit — including a plain back-and-forward
    // navigation, since this app recreates the composable rather than keeping
    // a backstack entry alive — was a full network round trip behind a
    // blocking spinner, offline included. These follow the exact shape the
    // parties/entries cache above already uses: an in-memory StateFlow for
    // instant reads, persisted per business in Room, read-through on a
    // cache miss or an explicit force refresh.
    //
    // The raw `*Response` DTOs are cached as-is (they are already @Keep,
    // Gson-friendly, same shapes returned by the API) rather than mapped to
    // a new domain type — the screens keep their
    // existing DTO-to-UI mapping functions unchanged; only where the data
    // comes from changes.
    private val _businessLedgerFeed = MutableStateFlow<List<LedgerFeedEntry>>(emptyList())
    val businessLedgerFeed: StateFlow<List<LedgerFeedEntry>> = _businessLedgerFeed.asStateFlow()

    private val _items = MutableStateFlow<List<InventoryItemResponse>>(emptyList())
    val items: StateFlow<List<InventoryItemResponse>> = _items.asStateFlow()

    private val _staff = MutableStateFlow<List<StaffMemberResponse>>(emptyList())
    val staff: StateFlow<List<StaffMemberResponse>> = _staff.asStateFlow()

    private val _bills = MutableStateFlow<List<BillResponse>>(emptyList())
    val bills: StateFlow<List<BillResponse>> = _bills.asStateFlow()

    /**
     * Cashflow is a derived report, not a record — it is kept in memory only
     * (never written to [prefs]) and is deliberately short-lived: see
     * [syncCashflow] and [CASHFLOW_TTL_MS]. [cashflowReportKey] is the
     * `(businessId, startDate, endDate)` the cached value was computed for, so
     * a different date range or a different active business is always a
     * cache miss rather than showing another range's numbers.
     */
    private val _cashflowReport = MutableStateFlow<CashflowResponse?>(null)
    val cashflowReport: StateFlow<CashflowResponse?> = _cashflowReport.asStateFlow()
    private var cashflowReportKey: String = ""
    private var cashflowReportFetchedAtMs: Long = 0L

    // Background scope used to kick the outbox after a local write.
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Mutations awaiting delivery, oldest first.
     *
     * Order is load-bearing: an entry's create must not be sent before the
     * create of the party it references, so a failure stops the flush rather
     * than skipping ahead.
     */
    private var outbox: MutableList<PendingOp> = mutableListOf()

    /** Guards the flush so two callers cannot send the same record twice. */
    private val flushMutex = Mutex()

    /** Guards business switching so rapid clicks cannot overwrite the wrong cache bucket. */
    private val switchMutex = Mutex()

    /**
     * Reactive mirror of [failedOperationCount], so the UI can show a banner
     * without polling. A permanently-failed write used to be recorded only in
     * [outbox] with no observable signal at all, so nothing on screen ever
     * told the user a mutation they made had not actually reached the server.
     */
    private val _failedOperationCountFlow = MutableStateFlow(0)
    val failedOperationCountFlow: StateFlow<Int> = _failedOperationCountFlow.asStateFlow()

    private var activePhoneNumber: String = ""

    /**
     * The business every read and write is scoped to.
     *
     * Blank until the real list has been fetched. It used to be the invented
     * string "biz_1", which matched nothing on the server, so the switcher,
     * the profile locks and the per-business filtering were all operating on a
     * value the backend had never heard of.
     */
    private var activeBusinessId: String = ""

    private val _businesses = MutableStateFlow<List<BusinessAccount>>(emptyList())
    val businesses: StateFlow<List<BusinessAccount>> = _businesses.asStateFlow()

    private val _activeBusiness = MutableStateFlow<BusinessAccount?>(null)
    val activeBusiness: StateFlow<BusinessAccount?> = _activeBusiness.asStateFlow()

    /** Read by the HTTP interceptor on every request. */
    fun currentBusinessId(): String? = activeBusinessId.takeIf { it.isNotBlank() }

/**
     * Persists [_items]/[_staff]/[_bills] for the active business —
     * business-scoped read-through caches written immediately after every
     * update to the corresponding StateFlow, same contract as before (each
     * had its own "kind" SharedPreferences key; each now has its own Room
     * table instead). The cashbook feed no longer has an equivalent pair of
     * functions: it is derived from [LedgerEntryEntity] via a join (see
     * loadData()/refreshBusinessLedgerFeedFromLocal()), which is already
     * kept current by every ledger CRUD's own Room write, so there is
     * nothing separate left to persist.
     */
    private fun saveCachedItems(value: List<InventoryItemResponse>) {
        if (activePhoneNumber.isBlank() || activeBusinessId.isBlank()) return
        db.itemDao().replaceForBusiness(activePhoneNumber, activeBusinessId, value.map { it.toEntity(activePhoneNumber, activeBusinessId) })
    }

    private fun saveCachedStaff(value: List<StaffMemberResponse>) {
        if (activePhoneNumber.isBlank() || activeBusinessId.isBlank()) return
        db.staffDao().replaceForBusiness(activePhoneNumber, activeBusinessId, value.map { it.toEntity(activePhoneNumber, activeBusinessId) })
    }

    private fun saveCachedBills(value: List<BillResponse>) {
        if (activePhoneNumber.isBlank() || activeBusinessId.isBlank()) return
        db.billDao().replaceForBusiness(activePhoneNumber, activeBusinessId, value.map { it.toEntity(activePhoneNumber, activeBusinessId) })
    }

    /**
     * Rebuilds [_businessLedgerFeed] from [LedgerEntryEntity] (joined with
     * party name/phone) rather than from a separately-synced cache — see
     * [LedgerEntryEntity]'s class doc for why the old dedicated feed cache
     * was retired rather than ported.
     */
    private fun refreshBusinessLedgerFeedFromLocal() {
        if (activePhoneNumber.isBlank() || activeBusinessId.isBlank()) {
            _businessLedgerFeed.value = emptyList()
            return
        }
        _businessLedgerFeed.value = db.ledgerEntryDao()
            .getBusinessFeedSync(activePhoneNumber, activeBusinessId)
            .map { it.toDomain() }
    }

    /**
     * The business list itself is scoped to the phone, not to a business.
     */
    private fun loadCachedBusinesses(): List<BusinessAccount> {
        if (activePhoneNumber.isBlank()) return emptyList()
        return db.businessDao().getAllSync(activePhoneNumber).map { it.toDomain() }
    }

    private fun saveCachedBusinesses(value: List<BusinessAccount>) {
        if (activePhoneNumber.isBlank()) return
        db.businessDao().replaceAll(activePhoneNumber, value.map { it.toEntity(activePhoneNumber) })
    }

    init {
        val savedUrl = getServerUrl()
        NetworkClient.updateBaseUrl(savedUrl)
        loadData()
        startConnectivityFlush()
    }

    fun getServerUrl(): String {
        return prefs.getString("custom_server_url", NetworkClient.BASE_URL) ?: NetworkClient.BASE_URL
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("custom_server_url", url).apply()
        NetworkClient.updateBaseUrl(url)
    }

    // ─── Outbox ────────────────────────────────────────────────────

    private fun loadOutbox() {
        if (activePhoneNumber.isBlank()) {
            outbox = mutableListOf()
            _failedOperationCountFlow.value = 0
            return
        }
        val loaded = db.outboxDao().getAllSync(activePhoneNumber).map { it.toDomain() }.toMutableList()

        // A PENDING op somehow missing its businessId cannot be attributed to
        // any business with certainty — guessing (e.g. stamping it with
        // whatever is active on this load) is exactly the bug this field
        // exists to prevent, so it is marked failed and left visible instead
        // of being sent (possibly to the wrong business) or silently
        // dropped. flushOutbox() enforces this same invariant independently
        // before ever sending, so this is defense in depth, not the only
        // guard — there is no JSON-migration case to handle here any more
        // (every Room row was written by enqueue(), which always stamps a
        // businessId before persisting).
        var repaired = false
        for (i in loaded.indices) {
            if (loaded[i].businessId.isBlank() && loaded[i].status == OpStatus.PENDING) {
                loaded[i] = loaded[i].copy(status = OpStatus.FAILED)
                repaired = true
            }
        }
        outbox = loaded
        _failedOperationCountFlow.value = outbox.count { it.status == OpStatus.FAILED }
        if (repaired) saveOutbox()
    }

    private fun saveOutbox() {
        _failedOperationCountFlow.value = outbox.count { it.status == OpStatus.FAILED }
        if (activePhoneNumber.isBlank()) return
        db.outboxDao().replaceAll(activePhoneNumber, outbox.map { it.toEntity(activePhoneNumber) })
    }

    private fun enqueue(op: PendingOp) {
        // businessId is stamped here, from whatever is active right now — the
        // only moment a queued mutation's business is allowed to be decided.
        // It is never re-derived later, no matter what becomes active next.
        val stamped = op.copy(businessId = activeBusinessId)
        outbox.add(stamped)
        saveOutbox()
        // Try immediately; if there is no network the record simply stays queued.
        remoteScope.launch { flushOutbox() }
    }

    /**
     * Flushes whenever the device regains connectivity.
     *
     * Without this, delivery depended entirely on the user reaching a screen
     * that happens to flush — login or a customer ledger. Someone who worked
     * offline and then stayed on the parties list could hold unsent writes
     * indefinitely.
     */
    private fun startConnectivityFlush() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            manager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        remoteScope.launch { flushOutbox() }
                    }
                },
            )
        } catch (_: Exception) {
            // Registration can fail if too many callbacks are already active.
            // The queue is still drained on login and on opening a ledger.
        }
    }

    /** Operations still awaiting a delivery attempt. */
    fun pendingOperationCount(): Int = outbox.count { it.status == OpStatus.PENDING }

    /**
     * Operations that could not be delivered and are not being retried
     * automatically. Nothing here has been discarded — see
     * [retryFailedOperations].
     */
    fun failedOperationCount(): Int = outbox.count { it.status == OpStatus.FAILED }

    /** Every queued operation regardless of status, for tests and diagnostics. */
    fun totalQueuedOperationCount(): Int = outbox.size

    private fun pendingTargetIds(type: PendingOpType): Set<String> =
        outbox.filter {
            it.type == type && it.businessId == activeBusinessId
        }.map { it.targetId }.toSet()

    /**
     * Gives every failed operation another chance. Called after a fresh
     * login, since a new token is exactly the kind of change that turns a
     * previously-failed operation — an expired session above all — into one
     * that can now succeed. Operations already pending are untouched.
     */
    fun retryFailedOperations() {
        var changed = false
        for (i in outbox.indices) {
            if (outbox[i].status == OpStatus.FAILED) {
                outbox[i] = outbox[i].copy(status = OpStatus.PENDING, attempts = 0)
                changed = true
            }
        }
        if (changed) saveOutbox()
    }

    /**
     * Attempts every business's queue head in parallel-by-business order,
     * stopping only a business whose head cannot currently be resolved —
     * never the whole queue. Safe to call repeatedly and safe to call while
     * another flush is already running (see [flushMutex]).
     *
     * A failure is never deleted: it is marked [OpStatus.FAILED] and stays in
     * the queue, visible via [failedOperationCount], so a financial mutation
     * the user made is lost only if the server has genuinely already applied
     * it (or, for a delete, already achieved it).
     *
     * @return true when nothing PENDING remains — every operation is either
     * delivered or parked as FAILED for a later retry.
     */
    suspend fun flushOutbox(): Boolean = flushMutex.withLock {
        if (activePhoneNumber.isBlank()) return@withLock false

        // Each operation is attempted at most once per call: a business that
        // keeps failing must not be hammered just because other businesses'
        // queues keep advancing and re-triggering another pass.
        val attemptedThisCall = mutableSetOf<String>()
        var progressed = true
        while (progressed) {
            progressed = false
            val heads = nextAttempts(outbox).filter { it.opId !in attemptedThisCall }
            if (heads.isEmpty()) break

            for (op in heads) {
                attemptedThisCall.add(op.opId)
                val idx = outbox.indexOfFirst { it.opId == op.opId }
                if (idx == -1) continue

                if (op.businessId.isBlank()) {
                    // Never guess which business an operation belongs to.
                    outbox[idx] = op.copy(status = OpStatus.FAILED)
                    saveOutbox()
                    continue
                }

                val result = try {
                    send(op)
                } catch (_: Exception) {
                    null
                }
                val outcome = if (result == null) FlushOutcome.RETRY else classify(result.code, op.type)

                when (outcome) {
                    FlushOutcome.SENT -> {
                        // The server's confirmation is authoritative — reconcile
                        // before dropping the op, so a server-side difference
                        // cannot silently outlive the record that would have
                        // explained it.
                        result?.entry?.let { reconcileEntryFromServer(op, it) }
                        outbox.removeAt(idx)
                        saveOutbox()
                        // This business's next item, if any, is now the head —
                        // worth another pass within this same call.
                        progressed = true
                    }

                    FlushOutcome.PERMANENT_FAILURE -> {
                        outbox[idx] = op.copy(status = OpStatus.FAILED)
                        // 403 means the caller's role does not permit this
                        // mutation — the optimistic local change was never
                        // actually applied server-side, so it must not keep
                        // showing on this device as if it had been. The op
                        // itself stays queued and FAILED (not removed): a
                        // later retry — e.g. after a role change — can still
                        // succeed.
                        if (result?.code == 403) rollbackMutation(op)
                        saveOutbox()
                    }

                    FlushOutcome.RETRY -> {
                        val attempted = op.copy(attempts = op.attempts + 1)
                        outbox[idx] = if (attempted.attempts >= MAX_OP_ATTEMPTS) {
                            attempted.copy(status = OpStatus.FAILED)
                        } else {
                            attempted
                        }
                        saveOutbox()
                    }
                }
            }
        }

        outbox.none { it.status == OpStatus.PENDING }
    }

    /**
     * The HTTP status for one queued operation, plus — for CREATE_ENTRY and
     * UPDATE_ENTRY only — the server's authoritative row, used to reconcile
     * local state once delivery is confirmed (see [reconcileEntryFromServer]).
     * Null for every other op type: a delete has nothing to reconcile to, and
     * a fresh party's balance is always zero.
     */
    private data class SendResult(val code: Int, val entry: LedgerEntryResponse? = null)

    /**
     * Performs the HTTP call for one queued operation, returning its status.
     *
     * Always sends [PendingOp.businessId] explicitly — the business this
     * operation was created in — rather than relying on whatever business is
     * currently active. That is what keeps a write correctly attributed even
     * if the user has since switched businesses one or more times.
     */
    private suspend fun send(op: PendingOp): SendResult = when (op.type) {
        PendingOpType.CREATE_PARTY ->
            SendResult(
                NetworkClient.apiService.createParty(
                    gson.fromJson(op.payload, CreatePartyRequest::class.java),
                    op.businessId
                ).code()
            )

        PendingOpType.UPDATE_PARTY_INTEREST ->
            SendResult(
                NetworkClient.apiService.updatePartyInterest(
                    op.targetId,
                    gson.fromJson(op.payload, UpdatePartyInterestRequest::class.java),
                    op.businessId
                ).code()
            )

        PendingOpType.DELETE_PARTY ->
            SendResult(NetworkClient.apiService.deleteParty(op.targetId, op.businessId).code())

        PendingOpType.CREATE_ENTRY -> {
            val response = NetworkClient.apiService.createLedgerEntry(
                gson.fromJson(op.payload, CreateLedgerEntryRequest::class.java),
                op.businessId
            )
            SendResult(response.code(), response.body())
        }

        PendingOpType.UPDATE_ENTRY -> {
            val response = NetworkClient.apiService.updateLedgerEntry(
                op.targetId,
                gson.fromJson(op.payload, UpdateLedgerEntryRequest::class.java),
                op.businessId
            )
            SendResult(response.code(), response.body())
        }

        PendingOpType.DELETE_ENTRY ->
            SendResult(NetworkClient.apiService.deleteLedgerEntry(op.targetId, op.businessId).code())
    }

    /**
     * Applies the server's confirmed row for a just-delivered CREATE_ENTRY or
     * UPDATE_ENTRY back onto local state. Without this, the optimistic
     * client-side arithmetic in [addLedgerEntry]/[updateLedgerEntry] was
     * trusted forever, so any difference between what the client computed and
     * what the server actually stored (rounding, validation, accumulated
     * float drift) would silently persist until some unrelated full sync
     * happened to overwrite it.
     *
     * Reuses the exact balance-delta arithmetic [updateLedgerEntry] already
     * uses for the optimistic path — not a new formula, the same one re-run
     * against the authoritative server figure instead of the requested one.
     * A no-op (diff of zero) in the ordinary case where the server simply
     * confirms what was sent.
     *
     * Always reconciles [op.businessId]'s Room rows directly, whichever
     * business that is — unlike the old SharedPreferences architecture,
     * where an in-memory StateFlow only ever held the *active* business's
     * rows and a background business's cache had to be patched through a
     * second, hand-rolled JSON-editing code path (raw prefs get/edit), a
     * Room query needs no such split: reads are already business-scoped by
     * argument, not by "what happens to be loaded in memory right now".
     * Only the StateFlow (and therefore the visible UI) is updated
     * conditionally, since that legitimately only ever represents the
     * active business.
     */
    private fun reconcileEntryFromServer(op: PendingOp, serverEntry: LedgerEntryResponse) {
        if (activePhoneNumber.isBlank()) return
        val cachedEntry = db.ledgerEntryDao().getByIdSync(serverEntry.id) ?: return
        val serverAmount = serverEntry.amount
        val diff = serverAmount - cachedEntry.amount
        val cachedType = runCatching { TransactionType.valueOf(cachedEntry.type) }.getOrDefault(TransactionType.GAVE)

        val reconciledEntry = cachedEntry.copy(amount = serverAmount, note = serverEntry.note ?: cachedEntry.note)
        db.ledgerEntryDao().upsert(reconciledEntry)

        if (diff != 0.0) {
            val party = db.partyDao().getByIdSync(activePhoneNumber, op.businessId, cachedEntry.partyId)
            val partyType = party?.type?.let { runCatching { PartyType.valueOf(it) }.getOrNull() } ?: PartyType.CUSTOMER
            val delta = if (partyType == PartyType.CUSTOMER) {
                if (cachedType == TransactionType.GAVE) diff else -diff
            } else {
                if (cachedType == TransactionType.GAVE) -diff else diff
            }
            db.partyDao().adjustBalance(cachedEntry.partyId, delta)
        }

        if (op.businessId != activeBusinessId) return

        if (diff != 0.0) {
            _parties.value = _parties.value.map { p ->
                if (p.id == cachedEntry.partyId) p.copy(currentBalance = db.partyDao().getByIdSync(activePhoneNumber, activeBusinessId, p.id)?.currentBalance ?: p.currentBalance) else p
            }
        }
        _ledgerEntries.value = _ledgerEntries.value.map {
            if (it.id == serverEntry.id) it.copy(amount = serverAmount, note = serverEntry.note ?: it.note) else it
        }
        refreshBusinessLedgerFeedFromLocal()
    }

    /**
     * Restores local state from [PendingOp.rollbackPayload] after the server
     * has confirmed — with a 403 — that [op] was never actually applied.
     * Only restores a row that is not already present, so this is safe to run
     * even if some other write has since touched the same party or entry.
     */
    /** Test-only accessor: the raw queue, for asserting on a specific op's fields. */
    internal fun peekOutbox(): List<PendingOp> = outbox.toList()

    /**
     * Test-only seam: sets the active business directly, bypassing the
     * network round trip [switchBusiness] requires (it calls
     * [loadBusinesses]/[syncPartiesFromServer]). Without a non-blank business
     * id, every op [enqueue]s gets stamped with businessId = "" and is
     * immediately marked FAILED by the "never guess which business" guard in
     * [flushOutbox] — before ever reaching the network — so a unit test that
     * mocks [NetworkClient] to exercise a real send/flush/rollback/reconcile
     * path needs this to make that path reachable at all.
     */
    internal fun setActiveBusinessIdForTesting(businessId: String) {
        activeBusinessId = businessId
    }

    /**
     * Test-only: cancels this instance's background work (the fire-and-forget
     * [enqueue] flush attempts, the connectivity-triggered flush, and any
     * in-flight SMS coroutine) and *waits* for it to actually stop.
     * [NetworkClient] is a process-wide singleton reconfigured by each test's
     * setup, so a straggler coroutine from a *previous* test's repository —
     * created fresh per test, but never explicitly torn down — can otherwise
     * still be running (including mid-flight, past cancellation's first
     * checkpoint) when the next test starts, and would then execute against
     * that next test's token/business-id configuration instead of its own,
     * and persist into the *same on-disk prefs* real instrumented tests share
     * across method boundaries. A bare `remoteScope.cancel()` only requests
     * cancellation — it does not block until in-flight children have actually
     * wound down, which a plain `Job.cancel()` (as opposed to `cancelAndJoin`)
     * does not guarantee. In production exactly one repository instance ever
     * exists for the process's lifetime, so this has no production call site.
     */
    internal suspend fun cancelBackgroundWorkForTesting() {
        remoteScope.coroutineContext[Job]?.cancelAndJoin()
    }

    internal fun rollbackMutation(op: PendingOp) {
        val payload = op.rollbackPayload ?: return
        val snapshot = try {
            gson.fromJson(payload, MutationSnapshot::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        when (op.type) {
            PendingOpType.DELETE_PARTY -> {
                snapshot.party?.let { p ->
                    if (_parties.value.none { it.id == p.id }) {
                        _parties.value = listOf(p) + _parties.value
                    }
                }
                val restoreEntries = snapshot.relatedEntries.filter { e ->
                    _ledgerEntries.value.none { it.id == e.id }
                }
                if (restoreEntries.isNotEmpty()) {
                    _ledgerEntries.value = restoreEntries + _ledgerEntries.value
                }
            }

            PendingOpType.DELETE_ENTRY -> {
                snapshot.entry?.let { e ->
                    if (_ledgerEntries.value.none { it.id == e.id }) {
                        _ledgerEntries.value = listOf(e) + _ledgerEntries.value
                    }
                }
                restorePartyBalance(snapshot.party)
            }

            PendingOpType.UPDATE_ENTRY -> {
                snapshot.entry?.let { e ->
                    _ledgerEntries.value = _ledgerEntries.value.map { if (it.id == e.id) e else it }
                }
                restorePartyBalance(snapshot.party)
            }

            PendingOpType.UPDATE_PARTY_INTEREST -> {
                // Restore only the interest fields from the pre-edit snapshot.
                // currentBalance is deliberately NOT restored here: an interest
                // *charge* is a separate ledger entry with its own rollback, and
                // copying a stale balance back from this snapshot would undo any
                // unrelated transaction that landed in between.
                snapshot.party?.let { p ->
                    _parties.value = _parties.value.map {
                        if (it.id == p.id) it.copy(
                            interestEnabled = p.interestEnabled,
                            interestRate = p.interestRate,
                            interestPeriodValue = p.interestPeriodValue,
                            interestPeriodUnit = p.interestPeriodUnit
                        ) else it
                    }
                }
            }

            PendingOpType.CREATE_PARTY, PendingOpType.CREATE_ENTRY -> {
                // Nothing to undo: the row only ever existed locally, and stays
                // queued/FAILED — visible, and retryable if it can ever succeed.
            }
        }
        saveData()
    }

    private fun restorePartyBalance(snapshotParty: Party?) {
        val p = snapshotParty ?: return
        _parties.value = _parties.value.map {
            if (it.id == p.id) it.copy(currentBalance = p.currentBalance) else it
        }
    }

    // ─── Businesses ────────────────────────────────────────────────

    /**
     * Fetches the businesses this user can act in and selects one.
     *
     * The list was previously invented on the device — a single hardcoded
     * "biz_1" — so nothing the switcher did corresponded to a real business.
     * Returns false when the server cannot be reached, leaving any previously
     * cached selection in place.
     */
    suspend fun loadBusinesses(): Boolean {
        return try {
            val response = NetworkClient.apiService.getBusinesses()
            val body = response.body()
            if (!response.isSuccessful || body == null) return false

            val mapped = body.mapIndexed { index, b ->
                BusinessAccount(
                    id = b.id,
                    name = b.name,
                    type = b.type ?: "General Store",
                    isPrimary = index == 0,
                    gstNumber = b.gstNumber,
                    address = b.address,
                    category = b.category,
                    bankAccount = b.bankAccount,
                    profileImageUrl = b.profileImageUrl,
                    role = b.role
                )
            }
            _businesses.value = mapped
            // Long-lived, phone-scoped (not per-business — this *is* the list
            // of businesses): a cold start while offline can still show the
            // switcher instead of an empty screen.
            saveCachedBusinesses(mapped)

            // Keep the previous selection if it is still one of ours; otherwise
            // fall back to the remembered one, then to the first.
            val remembered = db.syncStateDao().getSync(activePhoneNumber)?.activeBusinessId
            val chosen = mapped.firstOrNull { it.id == activeBusinessId }
                ?: mapped.firstOrNull { it.id == remembered }
                ?: mapped.firstOrNull()

            if (chosen != null && chosen.id != activeBusinessId) {
                switchBusiness(chosen.id)
            } else {
                _activeBusiness.value = chosen
            }
            true
        } catch (_: Exception) { false }
    }

    /**
     * Makes another business active.
     *
     * Cached data is per business, so this swaps buckets rather than filtering
     * one shared list: the previous business's parties and entries are written
     * out first, then the new business's are loaded and refreshed from the
     * server. Anything queued is flushed beforehand on a best-effort basis —
     * it is not required for correctness, since every queued operation
     * already carries the business id it was created under and is sent under
     * that id regardless of what is active when it finally goes out. This
     * flush only affects how promptly it happens.
     */
    suspend fun switchBusiness(businessId: String): Boolean = switchMutex.withLock {
        if (businessId.isBlank() || businessId == activeBusinessId) return@withLock false

        flushOutbox()
        // Save the old business data explicitly, in case it was modified
        saveData(businessIdToSave = activeBusinessId)

        // Clear in-memory state before loading new data to avoid flickering
        _parties.value = emptyList()
        _ledgerEntries.value = emptyList()
        _businessLedgerFeed.value = emptyList()
        _items.value = emptyList()
        _staff.value = emptyList()
        _bills.value = emptyList()
        _cashflowReport.value = null
        cashflowReportKey = ""
        cashflowReportFetchedAtMs = 0L

        activeBusinessId = businessId
        rememberActiveBusiness(businessId)
        _activeBusiness.value = _businesses.value.firstOrNull { it.id == businessId }

        loadData()
        syncPartiesFromServer()
        // Also fetch ledger entries for all parties? 
        // Syncing all entries for all parties is expensive. Wait, syncLedgerEntriesFromServer(partyId) is per party. 
        // Since we want to ensure the current cache is up to date, we can load businesses to refresh settings
        loadBusinesses()
        return@withLock true
    }

    /** Creates a business server-side and switches to it. */
    suspend fun createBusiness(name: String, type: String? = null): BusinessAccount? {
        return try {
            val response = NetworkClient.apiService.createBusiness(
                CreateBusinessRequest(name = name, type = type),
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) return null

            loadBusinesses()
            switchBusiness(body.id)
            _businesses.value.firstOrNull { it.id == body.id }
        } catch (_: Exception) { null }
    }

    // ─── Remote Sync ───────────────────────────────────────────────

    /**
     * Pulls the party list and merges it into local state.
     *
     * This used to assign the server's list wholesale. Because it runs straight
     * after login, any party created while offline whose create had not yet
     * been delivered was destroyed at that moment. Local rows that still have a
     * pending create are now preserved; everything else comes from the server,
     * which is authoritative for anything already synced.
     */
    suspend fun syncPartiesFromServer(): Boolean {
        return try {
            val response = NetworkClient.apiService.getParties()
            if (response.isSuccessful) {
                val remoteParties = response.body() ?: emptyList()
                // This device's current classifications, keyed by party id. The
                // server is authoritative for everything else, but it only
                // learned about collection frequency recently: for a party
                // created before that, the server sends null and this device
                // holds the only copy. Taking the server's null as DAILY would
                // silently reclassify a Weekly/Monthly party on the next sync.
                val localFrequencies = _parties.value.associate { it.id to it.collectionFrequency }
                val mapped = remoteParties.map { r ->
                    Party(
                        id = r.id,
                        name = r.name,
                        phone = r.phone,
                        type = if (r.type == "SUPPLIER") PartyType.SUPPLIER else PartyType.CUSTOMER,
                        collectionFrequency = r.collectionFrequency
                            ?.let { runCatching { CollectionFrequency.valueOf(it) }.getOrNull() }
                            ?: localFrequencies[r.id]
                            ?: CollectionFrequency.DAILY,
                        gstin = r.gstin ?: "",
                        currentBalance = r.currentBalance,
                        businessId = r.businessId ?: "",
                        interestEnabled = r.interestEnabled ?: false,
                        interestRate = r.interestRate ?: 0.0,
                        interestPeriodValue = r.interestPeriodValue ?: 0,
                        interestPeriodUnit = runCatching { com.example.khatabook.data.model.InterestPeriodUnit.valueOf(r.interestPeriodUnit ?: "DAYS") }.getOrDefault(com.example.khatabook.data.model.InterestPeriodUnit.DAYS)
                    )
                }

                val serverIds = mapped.map { it.id }.toSet()
                val awaitingCreate = pendingTargetIds(PendingOpType.CREATE_PARTY)
                // A row absent from the server but still queued for creation has
                // simply not been delivered yet. A row absent with nothing
                // queued was deleted server-side and should go.
                val unsynced = _parties.value.filter {
                    it.id !in serverIds && it.id in awaitingCreate
                }

                // A party whose delete is still queued (not yet confirmed by the
                // server) must not be resurrected just because this GET raced
                // ahead of that delete being delivered — the optimistic removal
                // already reflects what the user asked for, and the pending
                // DELETE_PARTY will either confirm it (server agrees, nothing to
                // undo) or roll it back itself on a 403 (see rollbackMutation).
                val awaitingDelete = pendingTargetIds(PendingOpType.DELETE_PARTY)
                val serverRows = mapped.filter { it.id !in awaitingDelete }

                _parties.value = unsynced + serverRows
                saveData()
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Fetches ledger entries for a specific party from the server and
     * merges them into the local state.
     */
    suspend fun syncLedgerEntriesFromServer(partyId: String): Boolean {
        return try {
            val response = NetworkClient.apiService.getLedgerEntries(partyId)
            if (response.isSuccessful) {
                val remoteEntries = response.body() ?: emptyList()
                val mapped = remoteEntries.map { r ->
                    LedgerEntry(
                        id = r.id,
                        partyId = r.partyId,
                        amount = r.amount,
                        type = if (r.type == "GOT") TransactionType.GOT else TransactionType.GAVE,
                        note = r.note ?: "",
                        paymentMethod = r.paymentMethod,
                        source = if (r.source == "OCR") EntrySource.OCR else EntrySource.MANUAL,
                        status = EntryStatus.CONFIRMED,
                        // The server's own timestamp, not "now". Omitting this
                        // let LedgerEntry's default stamp the restore time onto
                        // every synced row, so after a local-data wipe the whole
                        // history collapsed onto a single day and every
                        // daily/weekly/monthly report derived from it was wrong.
                        createdAt = com.example.khatabook.utils.IsoTime.parseOrDefault(
                            r.createdAt,
                            System.currentTimeMillis()
                        ),
                        // Business date as the server holds it. Falls back to
                        // the creation instant for rows written before the
                        // field existed, matching the backend's own backfill.
                        transactionDate = com.example.khatabook.utils.IsoTime.parseOrDefault(
                            r.transactionDate,
                            com.example.khatabook.utils.IsoTime.parseOrDefault(
                                r.createdAt,
                                System.currentTimeMillis()
                            )
                        )
                    )
                }
                val serverIds = mapped.map { it.id }.toSet()
                val awaitingCreate = pendingTargetIds(PendingOpType.CREATE_ENTRY)
                // Same rule as parties: keep undelivered local entries for this
                // party, take everything else from the server.
                val unsynced = _ledgerEntries.value.filter {
                    it.partyId == partyId && it.id !in serverIds && it.id in awaitingCreate
                }

                // An entry with a queued but not-yet-delivered UPDATE_ENTRY or
                // DELETE_ENTRY must not be clobbered by this GET, which can only
                // ever reflect the pre-mutation server state until that op is
                // actually delivered. Overwriting it here would silently revert
                // an edit the user just made (or resurrect a row they just
                // deleted) purely because of ordering, not because the server
                // ever disagreed with it.
                val awaitingUpdate = pendingTargetIds(PendingOpType.UPDATE_ENTRY)
                val awaitingDelete = pendingTargetIds(PendingOpType.DELETE_ENTRY)
                val localOverrides = _ledgerEntries.value.filter {
                    it.partyId == partyId && it.id in awaitingUpdate
                }
                val serverRows = mapped.filter { it.id !in awaitingUpdate && it.id !in awaitingDelete }

                val others = _ledgerEntries.value.filter { it.partyId != partyId }
                _ledgerEntries.value = unsynced + localOverrides + serverRows + others
                saveData()
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Business-wide cashbook feed. Cache-first: [businessLedgerFeed] already
     * holds whatever was last persisted (or optimistically patched by
     * [addLedgerEntry]/[updateLedgerEntry]/[deleteLedgerEntry]) the moment
     * this repository is constructed, so a caller does not need to await this
     * before rendering — it only decides whether a background refresh is
     * worth doing right now. [force] skips that decision (pull-to-refresh).
     *
     * On failure the existing cached feed is left exactly as it was: a failed
     * refresh must never blank out data that was already showing.
     */
    suspend fun syncBusinessLedgerFeed(force: Boolean = false): Boolean {
        if (!force && _businessLedgerFeed.value.isNotEmpty()) {
            // Cache hit — still worth a best-effort background refresh, but
            // the caller does not have to wait for it.
            remoteScope.launch { syncBusinessLedgerFeed(force = true) }
            return true
        }
        return try {
            val response = NetworkClient.apiService.getBusinessLedger()
            val body = response.body()
            if (response.isSuccessful && body != null && activePhoneNumber.isNotBlank() && activeBusinessId.isNotBlank()) {
                // Upserted into the same ledger_entries table the per-party
                // sync path writes to (see LedgerEntryEntity's class doc) —
                // the feed is then re-derived from local via the join, not
                // assigned directly from the server body.
                db.ledgerEntryDao().upsertAll(body.map { it.toEntity(activePhoneNumber, activeBusinessId) })
                refreshBusinessLedgerFeedFromLocal()
                true
            } else false
        } catch (_: Exception) { false }
    }

    /** Inventory. Same cache-first contract as [syncBusinessLedgerFeed]. */
    suspend fun syncItems(force: Boolean = false): Boolean {
        if (!force && _items.value.isNotEmpty()) {
            remoteScope.launch { syncItems(force = true) }
            return true
        }
        return try {
            val response = NetworkClient.apiService.getInventoryItems()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                _items.value = body
                saveCachedItems(body)
                true
            } else false
        } catch (_: Exception) { false }
    }

    /** Business staff/members. Same cache-first contract as above. */
    suspend fun syncStaff(force: Boolean = false): Boolean {
        if (!force && _staff.value.isNotEmpty()) {
            remoteScope.launch { syncStaff(force = true) }
            return true
        }
        return try {
            val response = NetworkClient.apiService.getStaff()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                _staff.value = body
                saveCachedStaff(body)
                true
            } else false
        } catch (_: Exception) { false }
    }

    /** Bills. Same cache-first contract as above. */
    suspend fun syncBills(force: Boolean = false): Boolean {
        if (!force && _bills.value.isNotEmpty()) {
            remoteScope.launch { syncBills(force = true) }
            return true
        }
        return try {
            val response = NetworkClient.apiService.getBills()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                _bills.value = body
                saveCachedBills(body)
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Cashflow report for a date range. Unlike the caches above this is
     * short-lived and in-memory only (see [CASHFLOW_TTL_MS] and
     * [cashflowReportKey]): a report over financial data has no server-push
     * to say "this changed", so it is bounded by a short TTL on top of the
     * explicit invalidation [invalidateCashflowCache] performs whenever a
     * ledger mutation happens locally in the active business.
     */
    suspend fun syncCashflow(startDate: String? = null, endDate: String? = null, force: Boolean = false): Boolean {
        val key = "$activeBusinessId|$startDate|$endDate"
        val fresh = key == cashflowReportKey &&
            (System.currentTimeMillis() - cashflowReportFetchedAtMs) < CASHFLOW_TTL_MS
        if (!force && fresh && _cashflowReport.value != null) return true

        return try {
            val response = NetworkClient.apiService.getCashflow(startDate, endDate)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                _cashflowReport.value = body
                cashflowReportKey = key
                cashflowReportFetchedAtMs = System.currentTimeMillis()
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Called after any local ledger mutation so the next [syncCashflow] call
     * is a guaranteed miss rather than serving numbers computed before the
     * mutation. Cheap: clears the freshness marker, does not touch
     * [_cashflowReport] itself, so a screen that is not re-reading it right
     * now still has something to show until it does ask again.
     */
    private fun invalidateCashflowCache() {
        cashflowReportFetchedAtMs = 0L
    }

    /**
     * Day-wise breakdown for the Cashbook report, derived from the already
     * business-isolated, already-cached [businessLedgerFeed] — not a second
     * network fetch or a second cache. The feed holds the business's full
     * ledger history, so filtering it to [startIso]..[endIso] and grouping by
     * calendar day is a real aggregation of real, already-authoritative
     * entries, not an invented number: the per-range *totals* still come from
     * the server via [syncCashflow]; this only explains how each range's
     * total is distributed across days, and backs the "tap a date to see that
     * day's entries" drill-down.
     */
    /**
     * The one Cashbook dataset: the active business's feed, narrowed by date
     * range and by collection type.
     *
     * Everything the Cashbook shows — the day-wise list, Cash In, Cash Out,
     * the net — is derived from this single result, so the totals can never
     * disagree with the rows above them. They previously could: the list was
     * filtered on the device while the totals came from a separate,
     * unfiltered server report.
     *
     * The date compared is the *business* date the user stated, not when the
     * row happened to be recorded. Rows written before that field existed
     * fall back to createdAt, which is exactly what they used to be grouped
     * by, so no historical row moves.
     *
     * [collectionFilter] is null for "All". It is matched against the party's
     * own stored classification — never inferred from the date, which is a
     * separate dimension entirely.
     */
    fun cashbookRows(
        startIso: String,
        endIso: String,
        collectionFilter: CollectionFrequency? = null,
    ): List<LedgerFeedEntry> {
        // Party lookup for this business only. An entry whose party is not in
        // the active business cannot be classified and is not ours to show.
        val frequencyByParty = _parties.value
            .filter { it.businessId == activeBusinessId }
            .associate { it.id to it.collectionFrequency }

        return _businessLedgerFeed.value.filter { entry ->
            val businessDate = entry.transactionDate ?: entry.createdAt
            val inRange = businessDate >= startIso && businessDate <= endIso
            val matchesCollection = collectionFilter == null ||
                frequencyByParty[entry.partyId] == collectionFilter
            inRange && matchesCollection
        }
    }

    /** Cash In / Cash Out over exactly the rows [cashbookRows] returns. */
    fun cashbookTotals(
        startIso: String,
        endIso: String,
        collectionFilter: CollectionFrequency? = null,
    ): Pair<Double, Double> {
        val rows = cashbookRows(startIso, endIso, collectionFilter)
        // GOT is money received, GAVE is credit extended — the same convention
        // the ledger and balances use everywhere else.
        val cashIn = rows.filter { it.type == "GOT" }.sumOf { it.amount }
        val cashOut = rows.filter { it.type == "GAVE" }.sumOf { it.amount }
        return cashIn to cashOut
    }

    fun dayWiseBreakdown(
        startIso: String,
        endIso: String,
        collectionFilter: CollectionFrequency? = null,
    ): List<Pair<String, List<LedgerFeedEntry>>> {
        return cashbookRows(startIso, endIso, collectionFilter)
            .groupBy { (it.transactionDate ?: it.createdAt).take(10) }
            .toSortedMap(compareByDescending { it })
            .map { (day, entries) -> day to entries }
    }

    /**
     * Downloads the server-generated, business-wide statement PDF
     * (`GET /reports/statement/pdf`, no `partyId` — see
     * `ReportsController.downloadStatement` / `PdfService.generateBusinessStatement`,
     * which already handles a null party) for the "Detailed Report" download —
     * the authoritative per-entry statement, not a client-side reconstruction
     * of it. Returns the saved file, or null on any failure (caller decides
     * how to surface that).
     */
    suspend fun downloadDetailedStatementPdf(startDate: String?, endDate: String?): java.io.File? {
        return try {
            val response = NetworkClient.apiService.downloadStatementPdf(
                partyId = null,
                startDate = startDate,
                endDate = endDate,
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) return null
            val dir = java.io.File(context.cacheDir, "pdfs").apply { mkdirs() }
            val file = java.io.File(dir, "CashbookStatement_${System.currentTimeMillis()}.pdf")
            body.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    /**
     * Best-effort [LedgerFeedEntry] for the optimistic cashbook-feed patch in
     * [addLedgerEntry]/[updateLedgerEntry] — built entirely from local data,
     * so `createdAt` is "now" rather than the server's own timestamp. The
     * next real [syncBusinessLedgerFeed] replaces it with the authoritative
     * row; this only avoids the cashbook screen looking stale until that
     * happens.
     */
    private fun LedgerEntry.toFeedEntry(party: Party?): LedgerFeedEntry = LedgerFeedEntry(
        id = id,
        partyId = partyId,
        amount = amount,
        type = type.name,
        note = note,
        paymentMethod = paymentMethod,
        source = source.name,
        createdAt = nowIso(),
        // The entry's own business date, not "now". Without this the
        // optimistically-patched row falls back to createdAt, so a backdated
        // entry would count towards today until the next server refresh
        // quietly moved it — the Cashbook would disagree with itself offline.
        transactionDate = com.example.khatabook.utils.IsoTime.format(transactionDate),
        party = party?.let {
            PartyResponse(
                id = it.id,
                name = it.name,
                phone = it.phone,
                type = it.type.name,
                gstin = it.gstin.ifBlank { null },
                currentBalance = it.currentBalance,
                businessId = it.businessId,
                createdAt = nowIso()
            )
        }
    )

    /**
     * Patches the business-wide cashbook feed and invalidates the cashflow
     * report cache after a local ledger mutation, so both reflect the change
     * immediately rather than only after the next explicit sync. Called from
     * [addLedgerEntry]/[updateLedgerEntry]/[deleteLedgerEntry].
     */
    private fun patchLedgerFeedOnCreate(entry: LedgerEntry, party: Party?) {
        // No separate persistence call here: the entry itself is already
        // durably written to ledger_entries by saveData(), called just
        // before this from every one of this function's callers — this only
        // patches the in-memory StateFlow so the cashbook screen updates
        // immediately, ahead of the next refreshBusinessLedgerFeedFromLocal().
        _businessLedgerFeed.value = listOf(entry.toFeedEntry(party)) + _businessLedgerFeed.value
        invalidateCashflowCache()
    }

    private fun patchLedgerFeedOnUpdate(entry: LedgerEntry, party: Party?) {
        _businessLedgerFeed.value = _businessLedgerFeed.value.map {
            if (it.id == entry.id) entry.toFeedEntry(party) else it
        }
        invalidateCashflowCache()
    }

    private fun patchLedgerFeedOnDelete(entryId: String) {
        _businessLedgerFeed.value = _businessLedgerFeed.value.filter { it.id != entryId }
        invalidateCashflowCache()
    }

    fun switchUser(phone: String) {
        if (activePhoneNumber == phone) return
        activePhoneNumber = phone
        // Clear the previous account's business context synchronously. Without
        // this, activeBusinessId/_activeBusiness kept the previous account's
        // values until the async loadBusinesses() call for the new phone
        // resolved — a window in which a screen reading activeBusiness (e.g.
        // BookProfileScreen prefilling name/GSTIN/bank account) would show the
        // previous account's business, and MainActivity's own
        // "selectedBusinessId.isEmpty()" auto-recovery check would never fire
        // because activeBusiness was non-null.
        activeBusinessId = ""
        _activeBusiness.value = null
        loadData()
    }

    private fun loadData() {
        loadOutbox()
        if (activePhoneNumber.isBlank()) return

        // Phone-scoped, not business-scoped — loaded first (before any
        // business-scoped read below) so a remembered-or-first business can
        // be selected synchronously right here. Selecting it later (or only
        // via the async loadBusinesses() network call) left a window where
        // activeBusinessId was blank while _businesses was already populated
        // from cache, and — worse — meant the business-scoped reads below
        // would have already run against the wrong (blank) business id by
        // the time a business was finally chosen.
        _businesses.value = loadCachedBusinesses()
        val syncState = db.syncStateDao().getSync(activePhoneNumber)
        if (activeBusinessId.isBlank() && _businesses.value.isNotEmpty()) {
            val remembered = syncState?.activeBusinessId
            val chosen = _businesses.value.firstOrNull { it.id == remembered } ?: _businesses.value.first()
            activeBusinessId = chosen.id
            _activeBusiness.value = chosen
        }

        _parties.value = if (activeBusinessId.isBlank()) emptyList()
            else db.partyDao().getForBusinessSync(activePhoneNumber, activeBusinessId).map { it.toDomain() }

        _ledgerEntries.value = if (activeBusinessId.isBlank()) emptyList()
            else db.ledgerEntryDao().getForBusinessSync(activePhoneNumber, activeBusinessId).map { it.toDomain() }

        // Business-scoped caches for the screens that used to call
        // NetworkClient directly. Same cache-first contract as parties/entries
        // above: whatever was last persisted for this business shows
        // immediately, and the relevant syncX() call refreshes it in the
        // background.
        refreshBusinessLedgerFeedFromLocal()
        _items.value = if (activeBusinessId.isBlank()) emptyList()
            else db.itemDao().getForBusinessSync(activePhoneNumber, activeBusinessId).map { it.toDomain() }
        _staff.value = if (activeBusinessId.isBlank()) emptyList()
            else db.staffDao().getForBusinessSync(activePhoneNumber, activeBusinessId).map { it.toDomain() }
        _bills.value = if (activeBusinessId.isBlank()) emptyList()
            else db.billDao().getForBusinessSync(activePhoneNumber, activeBusinessId).map { it.toDomain() }
        // Cashflow is intentionally not persisted (see CASHFLOW_TTL_MS) — a
        // fresh repository/business always starts with no cached report.
        _cashflowReport.value = null
        cashflowReportKey = ""
        cashflowReportFetchedAtMs = 0L

        // Deliberately NOT triggered automatically from here: loadData() runs
        // on every switchUser()/switchBusiness() call, including from tests
        // that construct a bare repository and never mock the network at
        // all — an automatic background restore attempt here would fire an
        // unmocked, real network call in all of them. restoreFromCloudIfNeeded()
        // is instead called explicitly from MainActivity's login and
        // account-switch flows, right alongside the existing
        // retryFailedOperations()/flushOutbox()/syncPartiesFromServer() calls
        // those already make.
    }

    /**
     * Persists whatever [_parties]/[_ledgerEntries] currently hold, tagged
     * with [businessIdToSave] — deliberately *not* each row's own
     * `Party.businessId`/`LedgerEntry.businessId` field. `addLedgerEntry()`
     * has no businessId parameter at all (every entry it builds carries
     * `LedgerEntry`'s bare class default), and even `addParty()`'s caller-
     * supplied value is only ever correct because MainActivity happens to
     * pass the active business's id — nothing enforces it. The old
     * SharedPreferences architecture never depended on either being
     * accurate: a row's business was entirely determined by which
     * `dataKey(kind, businessId)` blob it was written into, using the
     * *repository's* own tracked business, not the row's self-reported one.
     * This keeps that same authority model on Room: [_parties]/[_ledgerEntries]
     * only ever hold one business's rows at a time (switchBusiness() clears
     * them before loading another's), so whichever business is being saved
     * for is passed in explicitly, exactly as [businessIdToSave] already
     * named it.
     */
    private fun saveData(businessIdToSave: String = activeBusinessId) {
        if (activePhoneNumber.isBlank() || businessIdToSave.isBlank()) return
        db.partyDao().upsertAll(_parties.value.map { it.toEntity(activePhoneNumber).copy(businessId = businessIdToSave) })
        db.ledgerEntryDao().upsertAll(_ledgerEntries.value.map { it.toEntity(activePhoneNumber, businessIdToSave) })
    }

    private fun rememberActiveBusiness(businessId: String) {
        if (activePhoneNumber.isBlank()) return
        val existing = db.syncStateDao().getSync(activePhoneNumber)
        db.syncStateDao().upsert(
            SyncStateEntity(
                ownerPhone = activePhoneNumber,
                activeBusinessId = businessId,
                hasCompletedInitialSync = existing?.hasCompletedInitialSync ?: false,
            ),
        )
    }

    // ─── Cloud restore (empty local DB, valid session) ──────────────

    /**
     * True while this account's very first cloud restore is in flight on
     * this device — see [restoreFromCloudIfNeeded]. A screen must treat this
     * as "loading", never as "confirmed empty": [parties]/[ledgerEntries]
     * being empty *while this is true* means "not fetched yet", not "this
     * user has no data". Once this goes false (restore finished, or was
     * never needed because it already ran before), an empty list is honest.
     */
    private val _isRestoringFromCloud = MutableStateFlow(false)
    val isRestoringFromCloud: StateFlow<Boolean> = _isRestoringFromCloud.asStateFlow()

    /**
     * True when the last cloud restore attempt did not complete.
     *
     * Distinct from "restoring" and from "restored, and there is nothing
     * here": a returning user whose restore failed must be told it failed and
     * offered a retry, not shown an empty book that looks like data loss.
     */
    private val _restoreFailed = MutableStateFlow(false)
    val restoreFailed: StateFlow<Boolean> = _restoreFailed.asStateFlow()

    /** Clears the failure flag and attempts the restore again. */
    suspend fun retryRestoreFromCloud(): Boolean {
        _restoreFailed.value = false
        return restoreFromCloudIfNeeded()
    }

    /**
     * Scanned rows that resemble more than one existing customer, held for the
     * user to resolve.
     *
     * Deliberately in memory only, and deliberately not auto-resolved: money
     * is never attached to a guessed party, and a scan the user has not
     * confirmed is not a mutation worth persisting to the Outbox. If the
     * process dies before they answer, the scan is simply re-run — which is
     * strictly safer than having silently written to the wrong ledger.
     */
    private val _pendingScanMatches = MutableStateFlow<List<AmbiguousScanRecord>>(emptyList())
    val pendingScanMatches: StateFlow<List<AmbiguousScanRecord>> = _pendingScanMatches.asStateFlow()

    /** Clears a pending scan once the user has decided what to do with it. */
    fun resolvePendingScanMatch(record: AmbiguousScanRecord) {
        _pendingScanMatches.value = _pendingScanMatches.value.filterNot { it.id == record.id }
    }

    /** Attaches an ambiguous scan to the party the user picked. */
    fun confirmScanMatch(record: AmbiguousScanRecord, partyId: String) {
        resolvePendingScanMatch(record)
        addLedgerEntry(
            partyId = partyId,
            amount = record.amount,
            type = TransactionType.GOT,
            note = "Scan Khata Collection: ${record.date}",
            source = EntrySource.OCR
        )
    }

    /**
     * Rebuilds this account's local database from PostgreSQL — the path a
     * fresh install, a cleared app storage, or a brand new device all take.
     * PostgreSQL is the authoritative cloud copy; Room is empty here only
     * because nothing has been pulled down onto *this* device yet, not
     * because the account has no data.
     *
     * Idempotent and safe to call whenever [SyncStateEntity.hasCompletedInitialSync]
     * is false for the active account (see [loadData]) — a second call for
     * an account that already has one in flight or already completed is a
     * harmless no-op via the same cache-first `syncPartiesFromServer()` /
     * `loadBusinesses()` this app already runs on every login, just awaited
     * here instead of fired-and-forgotten, and only for the "never
     * confirmed synced" case.
     */
    suspend fun restoreFromCloudIfNeeded(): Boolean {
        if (activePhoneNumber.isBlank()) return false
        if (db.syncStateDao().getSync(activePhoneNumber)?.hasCompletedInitialSync == true) return false

        _isRestoringFromCloud.value = true
        try {
            val gotBusinesses = loadBusinesses()
            // loadBusinesses() already selects/persists an active business
            // when one exists. Nothing to restore into for an account with
            // zero businesses (a state that should not exist server-side,
            // but is not this method's job to fabricate around) — still
            // marks completion below so this device stops re-attempting a
            // restore on every load for an account that genuinely has none.
            // Whether the restore actually retrieved anything. Both of these
            // swallow their own failures and report false, so their result has
            // to be checked — see below for why that matters.
            var partiesRestored = true
            if (activeBusinessId.isNotBlank()) {
                partiesRestored = syncPartiesFromServer()
                syncBusinessLedgerFeed(force = true)
            }

            // Only claim the initial sync is done when it genuinely is.
            //
            // This used to be set unconditionally. A returning user whose
            // restore failed — no network, a server hiccup — was therefore
            // marked as fully restored with zero rows: the app showed "No
            // customers" as if their books were empty, and never retried,
            // because this flag is what gates the retry. Leaving it unset on
            // failure means the next launch (or a manual retry) restores
            // properly instead.
            val succeeded = gotBusinesses && partiesRestored
            if (succeeded) {
                val existing = db.syncStateDao().getSync(activePhoneNumber)
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        ownerPhone = activePhoneNumber,
                        activeBusinessId = existing?.activeBusinessId ?: activeBusinessId,
                        hasCompletedInitialSync = true,
                    ),
                )
            }
            _restoreFailed.value = !succeeded
            return succeeded
        } finally {
            _isRestoringFromCloud.value = false
        }
    }

    fun addParty(
        name: String,
        phone: String,
        type: PartyType,
        address: String = "",
        frequency: CollectionFrequency = CollectionFrequency.DAILY,
        businessId: String = "",
        interestEnabled: Boolean = false,
        interestRate: Double = 0.0,
        interestPeriodValue: Int = 0,
        interestPeriodUnit: com.example.khatabook.data.model.InterestPeriodUnit = com.example.khatabook.data.model.InterestPeriodUnit.DAYS
    ): Party {
        // Canonical name form, applied here so every creation path — typed
        // entry, contacts picker, Scan Khata — stores the same shape and
        // matching never has to guess about casing. Purely local: no network
        // call is involved in normalising a name.
        val canonicalName = com.example.khatabook.utils.PartyNames.normalize(name)
        // A blank phone is stored blank. It is deliberately NOT replaced with
        // a placeholder number: a fabricated "+910000000000" is
        // indistinguishable from a real one downstream, and the app would
        // happily try to message or dial it.
        val cleanedPhone = phone.trim()
        val newParty = Party(
            businessId = businessId,
            name = canonicalName,
            phone = cleanedPhone,
            type = type,
            collectionFrequency = frequency,
            address = address,
            interestEnabled = interestEnabled,
            interestRate = interestRate,
            interestPeriodValue = interestPeriodValue,
            interestPeriodUnit = interestPeriodUnit
        )
        _parties.value = listOf(newParty) + _parties.value
        saveData()
        // Remote write (fire-and-forget — local state already updated)
        enqueue(
            PendingOp(
                type = PendingOpType.CREATE_PARTY,
                targetId = newParty.id,
                payload = gson.toJson(
                    CreatePartyRequest(
                        id = newParty.id,
                        name = canonicalName,
                        phone = cleanedPhone,
                        type = type.name,
                        // Sent so the classification survives a reinstall. It
                        // was previously local-only, which is why restoring a
                        // device put every party back under Daily.
                        collectionFrequency = frequency.name,
                        // Omitted entirely (null) when interest isn't
                        // configured, so a plain create is byte-for-byte
                        // unchanged from before this feature existed.
                        interestEnabled = if (interestEnabled) true else null,
                        interestRate = if (interestEnabled) interestRate else null,
                        interestPeriodValue = if (interestEnabled) interestPeriodValue else null,
                        interestPeriodUnit = if (interestEnabled) interestPeriodUnit.name else null
                    )
                )
            )
        )
        if (newParty.phone.isNotBlank() && newParty.phone != PLACEHOLDER_CUSTOMER_PHONE && newParty.type == PartyType.CUSTOMER) {
            val smsSettings = context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE)
            val smsBizId = activeBusiness.value?.id ?: ""
            // Business-scoped, matching what SmsSettingsScreen actually writes
            // ("${key}_$bizId" on Save) — this used to read the bare, never-written
            // key name, so every setting on that screen except the on/off toggle
            // (which happened to already be scoped) silently had no effect: every
            // send used the hardcoded default template and MY_NUMBER mode
            // regardless of what was configured.
            if (smsSettings.getString("sender_mode_$smsBizId", "MY_NUMBER") == "MY_NUMBER") {
                remoteScope.launch {
                    val businessName = activeBusiness.value?.name ?: "Business"
                    val template = smsSettings.getString("template_customer_created_$smsBizId", "Dear {{customer_name}}, your record was created at {{business_name}}.")!!
                    val msg = com.example.khatabook.utils.SmsTemplateParser.parse(template, mapOf(
                        "customer_name" to newParty.name,
                        "business_name" to businessName
                    ))
                    val result = SmsSender.send(context, newParty.phone, msg, smsBizId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (result is SmsSender.Result.Sent) {
                            android.widget.Toast.makeText(context, "Background SMS Sent!", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.Failed) {
                            android.widget.Toast.makeText(context, "SMS Failed: ${result.reason}", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.PermissionMissing) {
                            android.widget.Toast.makeText(context, "SMS Permission Missing", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        
        return newParty
    }

    /**
     * Changes a party's interest configuration. Optimistic locally, then
     * queued for the server exactly like every other mutation, so an edit
     * made offline survives and syncs later.
     *
     * Changes no balance and creates no ledger entry: configuring a rate only
     * decides what a *later*, explicit [applyInterest] charge would compute.
     * Turning interest off zeroes the other fields, matching the server, so a
     * disabled party can never carry a stale rate.
     */
    fun updatePartyInterest(
        partyId: String,
        enabled: Boolean,
        rate: Double,
        periodValue: Int,
        periodUnit: com.example.khatabook.data.model.InterestPeriodUnit
    ): Party? {
        val existing = _parties.value.find { it.id == partyId } ?: return null

        val effectiveRate = if (enabled) rate else 0.0
        val effectivePeriod = if (enabled) periodValue else 0
        val effectiveUnit = if (enabled) periodUnit else com.example.khatabook.data.model.InterestPeriodUnit.DAYS

        val updated = existing.copy(
            interestEnabled = enabled,
            interestRate = effectiveRate,
            interestPeriodValue = effectivePeriod,
            interestPeriodUnit = effectiveUnit
        )
        _parties.value = _parties.value.map { if (it.id == partyId) updated else it }
        saveData()

        enqueue(
            PendingOp(
                type = PendingOpType.UPDATE_PARTY_INTEREST,
                targetId = partyId,
                payload = gson.toJson(
                    UpdatePartyInterestRequest(
                        interestEnabled = enabled,
                        interestRate = effectiveRate,
                        interestPeriodValue = effectivePeriod,
                        interestPeriodUnit = effectiveUnit.name
                    )
                ),
                // Pre-edit copy, so a server refusal restores what was actually
                // configured rather than leaving the device showing an edit the
                // server never accepted.
                rollbackPayload = gson.toJson(MutationSnapshot(party = existing))
            )
        )
        return updated
    }

    /**
     * Charges the configured interest as a real ledger entry.
     *
     * Deliberately manual and explicit — there is no scheduler and nothing
     * posts on its own. Each call creates exactly one normal entry through
     * [addLedgerEntry], so it inherits that path's whole contract for free:
     * the client-minted id the server replays idempotently, the optimistic
     * balance update, offline queueing, and the transaction-SMS toggle. That
     * is the reason interest is charged this way rather than by adjusting a
     * balance directly — an interest charge is money, and money in this app
     * only ever moves via a ledger entry the user can see, audit and delete.
     *
     * The principal is the party's current outstanding balance, so nothing is
     * charged to a settled or in-credit party ([InterestCalculator.calculate]
     * returns 0 for a non-positive principal). Returns null when there is
     * nothing to charge.
     */
    fun applyInterest(partyId: String): LedgerEntry? {
        val party = _parties.value.find { it.id == partyId } ?: return null
        if (!party.interestEnabled) return null

        // Interest is charged on what the party owes *us*, never on a credit
        // balance — a customer who paid in advance is owed money by us, and
        // charging them interest on their own credit would be plainly wrong.
        // The sign convention differs by type (see addLedgerEntry's delta): a
        // CUSTOMER owes us when their balance is positive, a SUPPLIER when
        // theirs is negative. Using abs() here instead would silently charge
        // exactly the in-credit case this normalisation exists to exclude.
        val owedToUs = if (party.type == PartyType.CUSTOMER) {
            party.currentBalance
        } else {
            -party.currentBalance
        }

        // Returns 0 for a non-positive principal, so a settled or in-credit
        // party falls out here rather than being charged anything.
        val interest = com.example.khatabook.utils.InterestCalculator.calculate(party, owedToUs)
        if (interest <= 0.0) return null

        // GAVE increases what the party owes us under both type conventions
        // (CUSTOMER: +amount, SUPPLIER: -amount), which is exactly what
        // charging interest on an outstanding debt should do.
        return addLedgerEntry(
            partyId = partyId,
            amount = interest,
            type = TransactionType.GAVE,
            note = "Interest @ ${com.example.khatabook.utils.InterestCalculator.describePeriod(party)}"
        )
    }

    fun addOrUpdateSheetRecord(
        customerCodeOrName: String,
        amountCollected: Double,
        date: String,
        businessId: String = "",
        isNewCustomer: Boolean = false,
        newPhone: String = "",
        newAddress: String = ""
    ): Boolean {
        // Returns whether the row actually became a ledger entry. Every early
        // return below is a row the user will not see in their book, so the
        // caller has to be able to tell them rather than report a silent
        // success.
        if (amountCollected <= 0) return false

        // 1. Resolve the scanned name to an existing party before considering
        // creating one. See PartyMatcher for the priority order and why fuzzy
        // matching is deliberately conservative — a wrong match here moves
        // real money onto the wrong customer's ledger.
        val candidatesInBusiness = _parties.value.filter { it.businessId == businessId }
        val match = com.example.khatabook.utils.PartyMatcher.match(
            scannedName = customerCodeOrName,
            scannedPhone = newPhone,
            candidates = candidatesInBusiness
        )

        var existingParty = (match as? com.example.khatabook.utils.PartyMatch.Matched)?.party

        if (existingParty == null) {
            // Ambiguous means "several plausible existing customers" — creating
            // a new one here is how "BHAVISH" silently became a second
            // "BHAVISH 10". The record is left for the user to resolve rather
            // than guessed at.
            // The scan dialog asks the user to confirm every row it treats as
            // a new customer before any of this runs. When they have confirmed
            // one, that decision stands: a merely *similar* existing name does
            // not override an explicit "yes, this is new".
            //
            // Ambiguity used to win here, which silently swallowed the row —
            // it went onto the pending-review queue and no further, so the
            // scan appeared to save and the entry never reached the ledger.
            if (!isNewCustomer && match is com.example.khatabook.utils.PartyMatch.Ambiguous) {
                _pendingScanMatches.value = _pendingScanMatches.value + AmbiguousScanRecord(
                    scannedName = customerCodeOrName,
                    amount = amountCollected,
                    date = date,
                    businessId = businessId,
                    candidateIds = match.candidates.map { it.id }
                )
                return false
            }
            if (isNewCustomer) {
                existingParty = addParty(
                    // Stored blank when the scan carried no number: a
                    // fabricated placeholder would be treated as a real,
                    // dialable, messageable number everywhere downstream.
                    name = customerCodeOrName,
                    phone = newPhone.trim(),
                    type = PartyType.CUSTOMER,
                    businessId = businessId
                )
                if (newAddress.isNotBlank()) {
                    existingParty = existingParty.copy(address = newAddress)
                    updateParty(existingParty)
                }
            } else {
                return false // Unknown customer, not confirmed as new — nothing to attach to.
            }
        }

        // 2. Add GOT entry (money collected) and update running balance.
        // The sheet's own date becomes the entry's business date, so a sheet
        // scanned days later still lands in the collection it belongs to. An
        // unparseable or absent date falls back to now rather than being
        // rejected — a scan must not be lost over a smudged date.
        val entry = addLedgerEntry(
            partyId = existingParty.id,
            amount = amountCollected,
            type = TransactionType.GOT,
            note = "Scan Khata Collection: $date",
            source = EntrySource.OCR,
            transactionDate = com.example.khatabook.utils.SheetDates.parseOrNull(date)
        )
        return entry != null
    }

    fun addLedgerEntry(
        partyId: String,
        amount: Double,
        type: TransactionType,
        note: String = "",
        paymentMethod: String = "Cash",
        source: EntrySource = EntrySource.MANUAL,
        /**
         * The business date the user chose. Null means "now" — which keeps an
         * ordinary, non-backdated entry behaving exactly as it did before this
         * parameter existed, including the bytes it puts on the wire.
         */
        transactionDate: Long? = null
    ): LedgerEntry? {
        if (amount <= 0) return null

        // The backend's numeric(14,2) column and its @IsNumber({maxDecimalPlaces:2})
        // validation only accept 2 decimal places. A manually-typed amount is
        // already clean, but a Scan Khata amount comes from Gemini's JSON
        // response and is not guaranteed to be exactly representable in IEEE
        // 754 double (e.g. 449.99999999999994) — sent as-is, the backend
        // correctly rejects it with 400 and the sync is permanently stuck.
        // Rounding once here, before the value is used for anything, keeps
        // the locally-displayed balance and the server-recorded amount in
        // agreement — this is the one place every ledger entry (manual or
        // OCR) already passes through.
        val amount = kotlin.math.round(amount * 100) / 100.0

        val newEntry = LedgerEntry(
            partyId = partyId,
            amount = amount,
            type = type,
            note = note,
            paymentMethod = paymentMethod,
            source = source,
            status = EntryStatus.CONFIRMED,
            // createdAt stays the real creation instant (the model's own
            // default) — only the business date follows the user's choice.
            transactionDate = transactionDate ?: System.currentTimeMillis()
        )

        // Atomic running balance recalculation
        _parties.value = _parties.value.map { party ->
            if (party.id == partyId) {
                val delta = if (party.type == PartyType.CUSTOMER) {
                    if (type == TransactionType.GAVE) amount else -amount
                } else {
                    if (type == TransactionType.GAVE) -amount else amount
                }
                party.copy(
                    currentBalance = party.currentBalance + delta,
                    lastTransactionTime = System.currentTimeMillis()
                )
            } else {
                party
            }
        }

        _ledgerEntries.value = listOf(newEntry) + _ledgerEntries.value
        saveData()

        val updatedParty = _parties.value.find { it.id == partyId }
        patchLedgerFeedOnCreate(newEntry, updatedParty)
        // Read once, at creation time — this is the single source of truth
        // for whether *this* transaction wants an SMS, both for the local
        // MY_NUMBER send below and for the server (via notifyCustomer in the
        // enqueued payload). Reading it later (e.g. when the Outbox actually
        // flushes) would let a toggle flip after this transaction was added
        // retroactively change whether it gets notified.
        val smsSettings = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
        val smsBizId = activeBusiness.value?.id ?: ""
        val sendTransactionSms = smsSettings.getBoolean("send_transaction_sms_$smsBizId", TRANSACTION_SMS_DEFAULT)
        if (updatedParty != null && updatedParty.phone.isNotBlank() && updatedParty.phone != PLACEHOLDER_CUSTOMER_PHONE && updatedParty.type == PartyType.CUSTOMER) {
            if (sendTransactionSms && smsSettings.getString("sender_mode_$smsBizId", "MY_NUMBER") == "MY_NUMBER") {
                remoteScope.launch {
                    val businessName = activeBusiness.value?.name ?: "Business"
                    val action = if (type == TransactionType.GOT) "received ₹${amount.toInt()}" else "gave you ₹${amount.toInt()}"
                    val balanceText = if (updatedParty.currentBalance > 0) "You will give ₹${updatedParty.currentBalance.toInt()}"
                                      else if (updatedParty.currentBalance < 0) "You will get ₹${-updatedParty.currentBalance.toInt()}"
                                      else "Balance is settled"
                    val template = smsSettings.getString("template_transaction_added_$smsBizId", "Dear {{customer_name}}, {{business_name}} {{action}}. {{balance_text}}.")!!
                    val msg = com.example.khatabook.utils.SmsTemplateParser.parse(template, mapOf(
                        "customer_name" to updatedParty.name,
                        "business_name" to businessName,
                        "balance" to Math.abs(updatedParty.currentBalance).toInt().toString(),
                        "action" to action,
                        "balance_text" to balanceText
                    ))
                    val result = SmsSender.send(context, updatedParty.phone, msg, smsBizId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (result is SmsSender.Result.Sent) {
                            android.widget.Toast.makeText(context, "Background SMS Sent!", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.Failed) {
                            android.widget.Toast.makeText(context, "SMS Failed: ${result.reason}", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.PermissionMissing) {
                            android.widget.Toast.makeText(context, "SMS Permission Missing", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Remote write (fire-and-forget)
        enqueue(
            PendingOp(
                type = PendingOpType.CREATE_ENTRY,
                targetId = newEntry.id,
                payload = gson.toJson(
                    CreateLedgerEntryRequest(
                        id = newEntry.id,
                        partyId = partyId,
                        amount = amount,
                        type = type.name,
                        note = note,
                        paymentMethod = paymentMethod,
                        source = source.name,
                        // Sent only when the user actually backdated the entry,
                        // so an ordinary create is byte-identical to what older
                        // builds sent. The value the user picked travels all the
                        // way to Postgres and is never re-derived at sync time —
                        // deriving it again would stamp the delivery date onto a
                        // transaction that happened days earlier.
                        transactionDate = transactionDate?.let {
                            com.example.khatabook.utils.IsoTime.format(it)
                        },
                        // The server sends the transaction message when the
                        // queued create is delivered — this is what actually
                        // gates that send. Previously omitted entirely, which
                        // is exactly the bug: the toggle only ever reached the
                        // local MY_NUMBER send above, never the server, so
                        // turning it off never stopped the server's own SMS.
                        notifyCustomer = sendTransactionSms
                    )
                )
            )
        )
        return newEntry
    }

    fun deleteLedgerEntry(entryId: String) {
        val entry = _ledgerEntries.value.find { it.id == entryId } ?: return
        val partyBeforeMutation = _parties.value.find { it.id == entry.partyId }

        _parties.value = _parties.value.map { party ->
            if (party.id == entry.partyId) {
                val reverseDelta = if (party.type == PartyType.CUSTOMER) {
                    if (entry.type == TransactionType.GAVE) -entry.amount else entry.amount
                } else {
                    if (entry.type == TransactionType.GAVE) entry.amount else -entry.amount
                }
                party.copy(currentBalance = party.currentBalance + reverseDelta)
            } else {
                party
            }
        }

        _ledgerEntries.value = _ledgerEntries.value.filter { it.id != entryId }
        saveData()
        // saveData() only upserts whatever the StateFlows currently hold —
        // it never deletes a row Room already has, so a removed entry needs
        // its own explicit delete or it would keep existing on disk forever.
        db.ledgerEntryDao().deleteById(entryId)
        patchLedgerFeedOnDelete(entryId)

        // Previously local-only: the entry stayed on the server forever and the
        // two balances silently diverged.
        enqueue(
            PendingOp(
                type = PendingOpType.DELETE_ENTRY,
                targetId = entryId,
                rollbackPayload = gson.toJson(MutationSnapshot(party = partyBeforeMutation, entry = entry))
            )
        )
    }

    fun updateLedgerEntry(entryId: String, amount: Double, note: String) {
        val oldEntry = _ledgerEntries.value.find { it.id == entryId } ?: return
        val partyBeforeMutation = _parties.value.find { it.id == oldEntry.partyId }
        val diff = amount - oldEntry.amount

        _parties.value = _parties.value.map { party ->
            if (party.id == oldEntry.partyId) {
                val delta = if (party.type == PartyType.CUSTOMER) {
                    if (oldEntry.type == TransactionType.GAVE) diff else -diff
                } else {
                    if (oldEntry.type == TransactionType.GAVE) -diff else diff
                }
                party.copy(currentBalance = party.currentBalance + delta)
            } else {
                party
            }
        }

        val updatedEntry = oldEntry.copy(amount = amount, note = note)
        _ledgerEntries.value = _ledgerEntries.value.map { if (it.id == entryId) updatedEntry else it }
        saveData()
        patchLedgerFeedOnUpdate(updatedEntry, _parties.value.find { it.id == oldEntry.partyId })

        val smsSettings = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
        val smsBizId = activeBusiness.value?.id ?: ""
        val sendTransactionSms = smsSettings.getBoolean("send_transaction_sms_$smsBizId", TRANSACTION_SMS_DEFAULT)
        if (sendTransactionSms && smsSettings.getString("sender_mode_$smsBizId", "MY_NUMBER") == "MY_NUMBER") {
            val updatedParty = _parties.value.find { it.id == oldEntry.partyId }
            if (updatedParty != null && updatedParty.phone.isNotBlank() && updatedParty.phone != PLACEHOLDER_CUSTOMER_PHONE && updatedParty.type == PartyType.CUSTOMER) {
                remoteScope.launch {
                    val businessName = activeBusiness.value?.name ?: "Business"
                    val template = smsSettings.getString("template_transaction_updated_$smsBizId", "Dear {{customer_name}}, a transaction was updated at {{business_name}}. New Balance: ₹{{balance}}.")!!
                    val msg = com.example.khatabook.utils.SmsTemplateParser.parse(template, mapOf(
                        "customer_name" to updatedParty.name,
                        "business_name" to businessName,
                        "balance" to Math.abs(updatedParty.currentBalance).toInt().toString()
                    ))
                    val result = SmsSender.send(context, updatedParty.phone, msg, smsBizId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (result is SmsSender.Result.Sent) {
                            android.widget.Toast.makeText(context, "Background SMS Sent!", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.Failed) {
                            android.widget.Toast.makeText(context, "SMS Failed: ${result.reason}", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.PermissionMissing) {
                            android.widget.Toast.makeText(context, "SMS Permission Missing", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Previously local-only, so an edited amount never reached the server
        // and the running balances diverged permanently.
        enqueue(
            PendingOp(
                type = PendingOpType.UPDATE_ENTRY,
                targetId = entryId,
                payload = gson.toJson(UpdateLedgerEntryRequest(amount = amount, note = note)),
                rollbackPayload = gson.toJson(MutationSnapshot(party = partyBeforeMutation, entry = oldEntry))
            )
        )
    }


    fun deleteParty(partyId: String) {
        val partyBeforeMutation = _parties.value.find { it.id == partyId }
        val entriesBeforeMutation = _ledgerEntries.value.filter { it.partyId == partyId }

        _parties.value = _parties.value.filter { it.id != partyId }
        _ledgerEntries.value = _ledgerEntries.value.filter { it.partyId != partyId }
        saveData()
        // Explicit delete — see deleteLedgerEntry()'s comment on why
        // saveData()'s upsert-only write is not enough on its own. Ledger
        // entries first: no FK cascade exists between the tables (see
        // LedgerEntryEntity's class doc), so this is done by hand, exactly
        // mirroring what PartyService.delete() does server-side.
        db.ledgerEntryDao().deleteAllForParty(partyId)
        db.partyDao().deleteById(partyId)
        enqueue(
            PendingOp(
                type = PendingOpType.DELETE_PARTY,
                targetId = partyId,
                rollbackPayload = gson.toJson(
                    MutationSnapshot(party = partyBeforeMutation, relatedEntries = entriesBeforeMutation)
                )
            )
        )
    }

    fun updateParty(party: Party) {
        _parties.value = _parties.value.map { if (it.id == party.id) party else it }
        saveData()
        
        if (party.phone.isNotBlank() && party.phone != PLACEHOLDER_CUSTOMER_PHONE && party.type == PartyType.CUSTOMER) {
            val smsSettings = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
            val smsBizId = activeBusiness.value?.id ?: ""
            if (smsSettings.getString("sender_mode_$smsBizId", "MY_NUMBER") == "MY_NUMBER") {
                remoteScope.launch {
                    val businessName = activeBusiness.value?.name ?: "Business"
                    val template = smsSettings.getString("template_customer_updated_$smsBizId", "Dear {{customer_name}}, your record has been updated at {{business_name}}. Your current balance is ₹{{balance}}.")!!
                    val message = com.example.khatabook.utils.SmsTemplateParser.parse(template, mapOf(
                        "customer_name" to party.name,
                        "business_name" to businessName,
                        "balance" to Math.abs(party.currentBalance).toInt().toString()
                    ))
                    val result = SmsSender.send(context, party.phone, message, smsBizId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (result is SmsSender.Result.Sent) {
                            android.widget.Toast.makeText(context, "Background SMS Sent!", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.Failed) {
                            android.widget.Toast.makeText(context, "SMS Failed: ${result.reason}", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (result is SmsSender.Result.PermissionMissing) {
                            android.widget.Toast.makeText(context, "SMS Permission Missing", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        
        // KNOWN GAP: this never reaches the server. There is no
        // PendingOpType.UPDATE_PARTY and no backend PATCH/PUT endpoint for
        // parties (party.controller.ts only has GET/POST create/DELETE) —
        // closing this needs a backend endpoint plus Outbox/rollback support,
        // not just a client-side change, so it's left as a known limitation
        // rather than a silent partial fix. Currently only reachable via
        // addOrUpdateSheetRecord()'s post-create address patch above, not
        // from any "edit party" UI (none calls updateParty() today).
    }

    suspend fun updateBusinessProfile(
        name: String? = null,
        type: String? = null,
        gstNumber: String? = null,
        address: String? = null,
        category: String? = null,
        bankAccount: String? = null,
        profileImageUrl: String? = null
    ) {
        val bizId = activeBusiness.value?.id ?: return
        val request = UpdateBusinessRequest(name, type, gstNumber, address, category, bankAccount, profileImageUrl)
        try {
            val res = NetworkClient.apiService.updateBusiness(bizId, request)
            if (res.isSuccessful) {
                loadBusinesses() // Refresh to get the updated info
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Applies the name/category typed on new-user registration to the
     * server-auto-provisioned business, on [remoteScope] rather than a
     * caller-supplied scope.
     *
     * The registration screen navigates away (`navStack.clear()`) the instant
     * it calls this, which — if this ran on a `rememberCoroutineScope()` tied
     * to that screen — gets cancelled by the resulting recomposition before
     * the post-PATCH [loadBusinesses] refresh finishes. The PATCH itself
     * often still lands (it's a fast localhost round trip that can complete
     * before cancellation propagates), so the server ends up with the typed
     * name while the client's [activeBusiness] silently keeps the old one:
     * the app permanently displays the auto-provisioned name even though the
     * database has the real one.
     */
    fun applyPendingBusinessProfile(name: String, type: String) {
        remoteScope.launch {
            if (activeBusiness.value == null) {
                loadBusinesses()
            }
            updateBusinessProfile(name = name, type = type)
        }
    }

    /**
     * Persists the name typed on new-user registration to the user's own
     * server record (`PUT /users/me`), on [remoteScope] for the same reason
     * [applyPendingBusinessProfile] is: the registration screen navigates
     * away the instant it calls this, which would cancel a
     * `rememberCoroutineScope()`-tied coroutine before the request lands.
     *
     * This is also what makes the server's own new-vs-returning-user check
     * (`AuthService.verifyOtp`'s `is_new_user = !user.name`) correct on every
     * later login. Before this existed, nothing on the client ever wrote a
     * name to that column — [UserDetailsScreen] only stored it in local
     * SharedPreferences (see MainActivity's `setUserPref` call) — so
     * `is_new_user` stayed permanently true for every account, forever. That
     * was invisible on the original device, because the local
     * "hasSavedProfile" flag independently suppressed the registration
     * screen from showing a second time — but that flag does not survive an
     * uninstall/reinstall or a cleared app, and `is_new_user` was the only
     * other signal MainActivity used to decide whether to show it, so a
     * returning user with a real, already-provisioned account was routed
     * through registration again exactly as if they were new.
     */
    fun applyPendingUserName(name: String) {
        remoteScope.launch {
            updateMe(name)
        }
    }

    suspend fun updateMe(name: String) {
        val request = UpdateUserRequest(name)
        try {
            NetworkClient.apiService.updateMe(request)
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }
}
