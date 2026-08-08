package com.example.khatabook.data.repository

import com.example.khatabook.data.api.CreateLedgerEntryRequest
import com.example.khatabook.data.api.CreateBusinessRequest
import com.example.khatabook.data.api.CreatePartyRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.api.UpdateLedgerEntryRequest
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.EntrySource
import com.example.khatabook.data.model.EntryStatus
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KhatabookRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("khatabook_repository_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _parties = MutableStateFlow<List<Party>>(emptyList())
    val parties: StateFlow<List<Party>> = _parties.asStateFlow()

    private val _ledgerEntries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val ledgerEntries: StateFlow<List<LedgerEntry>> = _ledgerEntries.asStateFlow()

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
     * Cache key for the active user *and* business. Data from two businesses
     * must not share a bucket, or switching would show the other's parties.
     */
    private fun dataKey(kind: String): String =
        if (activeBusinessId.isBlank()) "${kind}_data_$activePhoneNumber"
        else "${kind}_data_${activePhoneNumber}_$activeBusinessId"

    init {
        val savedUrl = getServerUrl()
        NetworkClient.updateBaseUrl(savedUrl)
        loadData()
        startConnectivityFlush()
    }

    fun getServerUrl(): String {
        return prefs.getString("custom_server_url", "http://10.0.2.2:3000/") ?: "http://10.0.2.2:3000/"
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("custom_server_url", url).apply()
        NetworkClient.updateBaseUrl(url)
    }

    // ─── Outbox ────────────────────────────────────────────────────

    private fun loadOutbox() {
        if (activePhoneNumber.isBlank()) {
            outbox = mutableListOf()
            return
        }
        val json = prefs.getString("outbox_$activePhoneNumber", null)
        outbox = if (json != null) {
            val type = TypeToken.getParameterized(List::class.java, PendingOp::class.java).type
            (gson.fromJson<List<PendingOp>>(json, type) ?: emptyList()).toMutableList()
        } else {
            mutableListOf()
        }
    }

    private fun saveOutbox() {
        if (activePhoneNumber.isBlank()) return
        prefs.edit().putString("outbox_$activePhoneNumber", gson.toJson(outbox)).apply()
    }

    private fun enqueue(op: PendingOp) {
        outbox.add(op)
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

    /** Pending operation count, for tests and diagnostics. */
    fun pendingOperationCount(): Int = outbox.size

    private fun pendingTargetIds(type: PendingOpType): Set<String> =
        outbox.filter { it.type == type }.map { it.targetId }.toSet()

    /**
     * Sends queued mutations in order, stopping at the first that cannot be
     * delivered so ordering is preserved. Safe to call repeatedly.
     *
     * @return true when the queue drained completely.
     */
    suspend fun flushOutbox(): Boolean = flushMutex.withLock {
        if (activePhoneNumber.isBlank()) return@withLock false

        while (outbox.isNotEmpty()) {
            val op = outbox.first()

            val outcome = try {
                classify(send(op))
            } catch (_: Exception) {
                // No connectivity, DNS failure, timeout: keep everything queued.
                FlushOutcome.RETRY
            }

            when (outcome) {
                FlushOutcome.SENT -> {
                    outbox.removeAt(0)
                    saveOutbox()
                }

                FlushOutcome.DROP -> {
                    // The server will never accept this record. Discard it
                    // rather than blocking everything queued behind it.
                    outbox.removeAt(0)
                    saveOutbox()
                }

                FlushOutcome.RETRY -> {
                    val attempted = op.copy(attempts = op.attempts + 1)
                    if (attempted.attempts >= MAX_OP_ATTEMPTS) {
                        // Poison record: drop it so the queue can move again.
                        outbox.removeAt(0)
                    } else {
                        outbox[0] = attempted
                    }
                    saveOutbox()
                    return@withLock false
                }
            }
        }
        true
    }

    /** Performs the HTTP call for one queued operation, returning its status. */
    private suspend fun send(op: PendingOp): Int = when (op.type) {
        PendingOpType.CREATE_PARTY ->
            NetworkClient.apiService.createParty(
                gson.fromJson(op.payload, CreatePartyRequest::class.java)
            ).code()

        PendingOpType.DELETE_PARTY ->
            NetworkClient.apiService.deleteParty(op.targetId).code()

        PendingOpType.CREATE_ENTRY ->
            NetworkClient.apiService.createLedgerEntry(
                gson.fromJson(op.payload, CreateLedgerEntryRequest::class.java)
            ).code()

        PendingOpType.UPDATE_ENTRY ->
            NetworkClient.apiService.updateLedgerEntry(
                op.targetId,
                gson.fromJson(op.payload, UpdateLedgerEntryRequest::class.java)
            ).code()

        PendingOpType.DELETE_ENTRY ->
            NetworkClient.apiService.deleteLedgerEntry(op.targetId).code()
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
                )
            }
            _businesses.value = mapped

            // Keep the previous selection if it is still one of ours; otherwise
            // fall back to the remembered one, then to the first.
            val remembered = prefs.getString("active_business_$activePhoneNumber", null)
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
     * server. Anything queued is flushed beforehand so it is delivered against
     * the business it was created in.
     */
    suspend fun switchBusiness(businessId: String): Boolean {
        if (businessId.isBlank() || businessId == activeBusinessId) return false

        flushOutbox()
        saveData()

        activeBusinessId = businessId
        prefs.edit().putString("active_business_$activePhoneNumber", businessId).apply()
        _activeBusiness.value = _businesses.value.firstOrNull { it.id == businessId }

        loadData()
        syncPartiesFromServer()
        return true
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
                val mapped = remoteParties.map { r ->
                    Party(
                        id = r.id,
                        name = r.name,
                        phone = r.phone,
                        type = if (r.type == "SUPPLIER") PartyType.SUPPLIER else PartyType.CUSTOMER,
                        gstin = r.gstin ?: "",
                        currentBalance = r.currentBalance,
                        businessId = r.businessId ?: "biz_1"
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

                _parties.value = unsynced + mapped
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
                        status = EntryStatus.CONFIRMED
                    )
                }
                val serverIds = mapped.map { it.id }.toSet()
                val awaitingCreate = pendingTargetIds(PendingOpType.CREATE_ENTRY)
                // Same rule as parties: keep undelivered local entries for this
                // party, take everything else from the server.
                val unsynced = _ledgerEntries.value.filter {
                    it.partyId == partyId && it.id !in serverIds && it.id in awaitingCreate
                }
                val others = _ledgerEntries.value.filter { it.partyId != partyId }
                _ledgerEntries.value = unsynced + mapped + others
                saveData()
                true
            } else false
        } catch (_: Exception) { false }
    }

    fun switchUser(phone: String) {
        if (activePhoneNumber == phone) return
        activePhoneNumber = phone
        loadData()
    }

    private fun loadData() {
        loadOutbox()
        if (activePhoneNumber.isBlank()) return
        
        val partiesJson = prefs.getString(dataKey("parties"), null)
        if (partiesJson != null) {
            val type = TypeToken.getParameterized(List::class.java, Party::class.java).type
            val savedParties: List<Party> = gson.fromJson(partiesJson, type)
            _parties.value = savedParties
        } else {
            _parties.value = emptyList()
        }

        val entriesJson = prefs.getString(dataKey("entries"), null)
        if (entriesJson != null) {
            val type = TypeToken.getParameterized(List::class.java, LedgerEntry::class.java).type
            val savedEntries: List<LedgerEntry> = gson.fromJson(entriesJson, type)
            _ledgerEntries.value = savedEntries
        } else {
            _ledgerEntries.value = emptyList()
        }
    }

    private fun saveData() {
        if (activePhoneNumber.isBlank()) return
        
        val partiesJson = gson.toJson(_parties.value)
        val entriesJson = gson.toJson(_ledgerEntries.value)
        prefs.edit()
            .putString(dataKey("parties"), partiesJson)
            .putString(dataKey("entries"), entriesJson)
            .apply()
    }
    fun addParty(
        name: String,
        phone: String,
        type: PartyType,
        address: String = "",
        frequency: CollectionFrequency = CollectionFrequency.DAILY,
        businessId: String = "biz_1"
    ): Party {
        val newParty = Party(
            businessId = businessId,
            name = name,
            phone = phone,
            type = type,
            collectionFrequency = frequency,
            address = address
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
                        name = name,
                        phone = phone,
                        type = type.name
                    )
                )
            )
        )
        // No SMS intent here. The server sends the welcome message when the
        // queued create is delivered, so firing one locally produced two
        // messages per customer — and launching the SMS composer with
        // FLAG_ACTIVITY_NEW_TASK threw the user out of the app on every add.
        return newParty
    }

    fun addOrUpdateSheetRecord(customerCodeOrName: String, amountCollected: Double, date: String, businessId: String = "biz_1") {
        if (amountCollected <= 0) return

        // 1. Find existing party or create a new party for this AI scanned customer inside current business account
        val existingParty = _parties.value.find {
            it.businessId == businessId && (
                it.name.equals(customerCodeOrName, ignoreCase = true) ||
                it.name.contains(customerCodeOrName, ignoreCase = true)
            )
        } ?: return // Skip unknown customers from sheet scan

        // 2. Add GOT entry (money collected) and update running balance
        addLedgerEntry(
            partyId = existingParty.id,
            amount = amountCollected,
            type = TransactionType.GOT,
            note = "Scan Khata Collection: $date",
            source = EntrySource.OCR
        )
    }

    fun addLedgerEntry(
        partyId: String,
        amount: Double,
        type: TransactionType,
        note: String = "",
        paymentMethod: String = "Cash",
        source: EntrySource = EntrySource.MANUAL
    ): LedgerEntry? {
        if (amount <= 0) return null

        val newEntry = LedgerEntry(
            partyId = partyId,
            amount = amount,
            type = type,
            note = note,
            paymentMethod = paymentMethod,
            source = source,
            status = EntryStatus.CONFIRMED
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
                        source = source.name
                    )
                )
            )
        )
        // No SMS intent here either. The server sends the transaction
        // message when the queued create is delivered. The local one also
        // reported "Total dues" without direction and carried a fabricated
        // khatabook.com history link.
        return newEntry
    }

    fun deleteLedgerEntry(entryId: String) {
        val entry = _ledgerEntries.value.find { it.id == entryId } ?: return

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

        // Previously local-only: the entry stayed on the server forever and the
        // two balances silently diverged.
        enqueue(PendingOp(type = PendingOpType.DELETE_ENTRY, targetId = entryId))
    }

    fun updateLedgerEntry(entryId: String, amount: Double, note: String) {
        val oldEntry = _ledgerEntries.value.find { it.id == entryId } ?: return
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

        _ledgerEntries.value = _ledgerEntries.value.map { entry ->
            if (entry.id == entryId) {
                entry.copy(amount = amount, note = note)
            } else {
                entry
            }
        }
        saveData()

        // Previously local-only, so an edited amount never reached the server
        // and the running balances diverged permanently.
        enqueue(
            PendingOp(
                type = PendingOpType.UPDATE_ENTRY,
                targetId = entryId,
                payload = gson.toJson(UpdateLedgerEntryRequest(amount = amount, note = note))
            )
        )
    }


    fun deleteParty(partyId: String) {
        _parties.value = _parties.value.filter { it.id != partyId }
        _ledgerEntries.value = _ledgerEntries.value.filter { it.partyId != partyId }
        saveData()
        enqueue(PendingOp(type = PendingOpType.DELETE_PARTY, targetId = partyId))
    }
}
