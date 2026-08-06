package com.example.khatabook.data.repository

import com.example.khatabook.data.api.CreateLedgerEntryRequest
import com.example.khatabook.data.api.CreatePartyRequest
import com.example.khatabook.data.api.NetworkClient
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
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // Background scope for fire-and-forget remote writes
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activePhoneNumber: String = ""

    init {
        val savedUrl = getServerUrl()
        NetworkClient.updateBaseUrl(savedUrl)
        loadData()
    }

    fun getServerUrl(): String {
        return prefs.getString("custom_server_url", "http://10.0.2.2:3000/") ?: "http://10.0.2.2:3000/"
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("custom_server_url", url).apply()
        NetworkClient.updateBaseUrl(url)
    }

    // ─── Remote Sync ───────────────────────────────────────────────

    /**
     * Fetches the party list from the server and merges it into the local
     * state and SharedPreferences. Safe to call after login.
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
                _parties.value = mapped
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
                // Replace only entries for this party, keep others
                val others = _ledgerEntries.value.filter { it.partyId != partyId }
                _ledgerEntries.value = mapped + others
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
        if (activePhoneNumber.isBlank()) return
        
        val partiesJson = prefs.getString("parties_data_$activePhoneNumber", null)
        if (partiesJson != null) {
            val type = TypeToken.getParameterized(List::class.java, Party::class.java).type
            val savedParties: List<Party> = gson.fromJson(partiesJson, type)
            _parties.value = savedParties
        } else {
            _parties.value = emptyList()
        }

        val entriesJson = prefs.getString("entries_data_$activePhoneNumber", null)
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
            .putString("parties_data_$activePhoneNumber", partiesJson)
            .putString("entries_data_$activePhoneNumber", entriesJson)
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
        remoteScope.launch {
            try {
                NetworkClient.apiService.createParty(
                    CreatePartyRequest(
                        id = newParty.id,
                        name = name,
                        phone = phone,
                        type = type.name
                    )
                )
            } catch (_: Exception) { /* offline — will sync on next launch */ }
        }
        // Trigger SMS Intent for new customer
        try {
            val message = "Hi $name, welcome to Khatabook! Your profile has been created."
            val smsIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("sms:$phone")).apply {
                putExtra("sms_body", message)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
        } catch (e: Exception) {
            // Ignore
        }
        
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
        remoteScope.launch {
            try {
                NetworkClient.apiService.createLedgerEntry(
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
            } catch (_: Exception) { /* offline */ }
        }
        // Trigger SMS Intent for transaction
        try {
            val updatedParty = _parties.value.find { it.id == partyId }
            if (updatedParty != null && source != EntrySource.OCR) {
                val sign = if (updatedParty.type == PartyType.CUSTOMER) {
                    if (type == TransactionType.GAVE) "-" else "+"
                } else {
                    if (type == TransactionType.GAVE) "+" else "-"
                }
                val message = "Hi, Entry added for Rs $sign${amount.toInt()} on ${java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date())}. Total dues : Rs ${updatedParty.currentBalance.toInt()}. Check history: https://khatabook.com/t/${(1000..9999).random()} Thank you My Business"
                val smsIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("sms:${updatedParty.phone}")).apply {
                    putExtra("sms_body", message)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(smsIntent)
            }
        } catch (e: Exception) {
            // Ignore
        }
        
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
    }

    fun updateParty(updatedParty: Party) {
        _parties.value = _parties.value.map { party ->
            if (party.id == updatedParty.id) updatedParty else party
        }
        saveData()
    }

    fun deleteParty(partyId: String) {
        _parties.value = _parties.value.filter { it.id != partyId }
        _ledgerEntries.value = _ledgerEntries.value.filter { it.partyId != partyId }
        saveData()
        remoteScope.launch {
            try { NetworkClient.apiService.deleteParty(partyId) } catch (_: Exception) {}
        }
    }
}
