package com.example.khatabook.data.api

import retrofit2.Response
import retrofit2.http.*
import androidx.annotation.Keep

// ─── Auth DTOs ───────────────────────────────────────────────
@Keep
data class RequestOtpRequest(val phoneNumber: String)
@Keep
data class RequestOtpResponse(val success: Boolean, val message: String)

@Keep
data class VerifyOtpRequest(val phoneNumber: String, val otp: String)
@Keep
data class VerifyOtpResponse(val access_token: String, val is_new_user: Boolean)

// ─── Reminders DTOs ─────────────────────────────────────────
@Keep
data class SendReminderRequest(val phoneNumber: String, val amount: Double, val partyName: String)
@Keep
data class SendReminderResponse(val success: Boolean, val message: String)

// ─── Scanning DTOs ──────────────────────────────────────────
@Keep
data class ExtractedEntry(val customerName: String, val amount: Double)
@Keep
data class ExtractLedgerResponse(val date: String, val totalCollected: Double, val records: List<ExtractedEntry>)

// ─── Party DTOs ──────────────────────────────────────────────
@Keep
data class PartyResponse(
    val id: String,
    val name: String,
    val phone: String,
    val type: String,
    val gstin: String?,
    val currentBalance: Double,
    val businessId: String?,
    val createdAt: String
)

@Keep
data class CreatePartyRequest(
    /**
     * The locally-minted UUID. Sent so the server stores the same id we already
     * use offline — child ledger entries then reference a party id the server
     * recognises, and a retried create is a no-op rather than a duplicate.
     */
    val id: String,
    val name: String,
    val phone: String,
    val type: String,
    val gstin: String? = null
    // No businessId: the server derives the real business from the JWT. The old
    // hardcoded "biz_1" was never a real id and was ignored server-side.
)

// ─── Ledger DTOs ─────────────────────────────────────────────
@Keep
data class LedgerEntryResponse(
    val id: String,
    val partyId: String,
    val amount: Double,
    val type: String,        // "GAVE" or "GOT"
    val note: String?,
    val paymentMethod: String,
    val source: String,
    val createdAt: String
)

@Keep
data class CreateLedgerEntryRequest(
    /** Locally-minted UUID; makes a retried write idempotent server-side. */
    val id: String,
    val partyId: String,
    val amount: Double,
    val type: String,        // "GAVE" or "GOT"
    val note: String = "",
    val paymentMethod: String = "Cash",
    val source: String = "MANUAL"
)

// ─── Reports DTOs ────────────────────────────────────────────
@Keep
data class CashflowResponse(
    val totalIn: Double,   // money received  (GOT entries)
    val totalOut: Double,  // credit extended (GAVE entries)
    val net: Double
)

// ─── Bills DTOs ──────────────────────────────────────────────
@Keep
data class BillResponse(
    val id: String,
    val partyId: String,
    val totalAmount: Double,
    val isGst: Boolean,
    val receiptUrl: String?,
    val created_at: String
)

// ─── Inventory DTOs ──────────────────────────────────────────
@Keep
data class InventoryItemResponse(
    val id: String,
    val name: String,
    val sku: String?,
    val sellingPrice: Double,
    val purchasePrice: Double,
    val currentStock: Double,
    val lowStockThreshold: Double
)

// ─── Cashbook feed DTOs ──────────────────────────────────────
/** A business-wide ledger row, with the party joined for display. */
@Keep
data class LedgerFeedEntry(
    val id: String,
    val partyId: String,
    val amount: Double,
    val type: String,        // "GAVE" or "GOT"
    val note: String?,
    val paymentMethod: String,
    val source: String,
    val createdAt: String,
    val party: PartyResponse? = null
)

// ─── API Interface ────────────────────────────────────────────
@Keep
interface KhatabookApiService {

    // Auth
    @POST("auth/request-otp")
    suspend fun requestOtp(@Body body: RequestOtpRequest): Response<RequestOtpResponse>

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpRequest): Response<VerifyOtpResponse>

    // Reminders
    @POST("api/v1/reminders/send")
    suspend fun sendReminder(@Body body: SendReminderRequest): Response<SendReminderResponse>

    // Scanning
    @Multipart
    @POST("api/v1/scanning/extract")
    suspend fun extractLedger(@Part image: okhttp3.MultipartBody.Part): Response<ExtractLedgerResponse>

    // Parties
    @GET("api/v1/parties")
    suspend fun getParties(@Query("type") type: String? = null): Response<List<PartyResponse>>

    @POST("api/v1/parties")
    suspend fun createParty(@Body body: CreatePartyRequest): Response<PartyResponse>

    @DELETE("api/v1/parties/{id}")
    suspend fun deleteParty(@Path("id") id: String): Response<Unit>

    // Ledger
    @GET("api/v1/ledger/party/{partyId}")
    suspend fun getLedgerEntries(@Path("partyId") partyId: String): Response<List<LedgerEntryResponse>>

    @POST("api/v1/ledger")
    suspend fun createLedgerEntry(@Body body: CreateLedgerEntryRequest): Response<LedgerEntryResponse>

    @DELETE("api/v1/ledger/{id}")
    suspend fun deleteLedgerEntry(@Path("id") id: String): Response<Unit>

    /**
     * Business-wide ledger feed for the cashbook. Null bounds are omitted from
     * the query string rather than sent empty, which the server would reject as
     * a non-ISO date.
     */
    @GET("api/v1/ledger")
    suspend fun getBusinessLedger(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): Response<List<LedgerFeedEntry>>

    // Reports
    @GET("reports/cashflow")
    suspend fun getCashflow(
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null
    ): Response<CashflowResponse>

    // Bills
    @GET("bills")
    suspend fun getBills(): Response<List<BillResponse>>

    // Inventory
    @GET("inventory")
    suspend fun getInventoryItems(): Response<List<InventoryItemResponse>>
}
