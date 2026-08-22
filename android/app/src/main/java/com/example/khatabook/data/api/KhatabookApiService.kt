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

// ─── Scanning DTOs ──────────────────────────────────────────
@Keep
data class ExtractedEntry(
    val customerName: String,
    val amount: Double,
    /** Set only when this row carried its own date differing from the sheet
     *  date. Null otherwise, so the row follows the sheet date. */
    val transactionDate: String? = null
)
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
    val createdAt: String,
    // Nullable rather than defaulted: an old backend response predating this
    // feature simply omits these keys, and Gson leaves nullable fields null
    // rather than failing to parse — the caller (toEntity) supplies the
    // false/0/0/DAYS defaults.
    val interestEnabled: Boolean? = null,
    val interestRate: Double? = null,
    val interestPeriodValue: Int? = null,
    val interestPeriodUnit: String? = null
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
    val gstin: String? = null,
    // No businessId: the server derives the real business from the JWT. The old
    // hardcoded "biz_1" was never a real id and was ignored server-side.
    // Omitted (null) entirely when interest isn't configured, rather than sent
    // as false/0/0/DAYS — the backend DTO defaults identically either way, and
    // this keeps a plain create-with-no-interest request byte-for-byte the
    // same as before this feature existed.
    val interestEnabled: Boolean? = null,
    val interestRate: Double? = null,
    val interestPeriodValue: Int? = null,
    val interestPeriodUnit: String? = null
)

/**
 * A party's interest configuration, sent whole rather than as a patch of
 * changed fields — the server assigns these absolute values, so a retried
 * send from the Outbox converges on the same row instead of compounding.
 */
@Keep
data class UpdatePartyInterestRequest(
    val interestEnabled: Boolean,
    val interestRate: Double,
    val interestPeriodValue: Int,
    val interestPeriodUnit: String
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
    val createdAt: String,
    /** Business date from the server. Null for rows created before the field
     *  existed; callers fall back to createdAt. */
    val transactionDate: String? = null
)

/** Editable fields on an existing entry. Type and party are not changeable. */
@Keep
data class UpdateLedgerEntryRequest(
    val amount: Double? = null,
    val note: String? = null
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
    val source: String = "MANUAL",
    /** ISO-8601 business date chosen by the user. Omitted (null) when the
     *  entry is simply "now", keeping such a request byte-identical to what
     *  older app builds sent. */
    val transactionDate: String? = null,
    /** The "Send transaction SMS" toggle's value at creation time — the
     *  single source of truth LedgerService gates its own SMS send on. Null
     *  is never sent deliberately by this client; it exists only so an old
     *  cached Outbox payload from before this field existed still decodes. */
    val notifyCustomer: Boolean? = null
)

// ─── Business DTOs ───────────────────────────────────────────
/** A business as the server knows it. `id` is the real UUID everything scopes by. */
@Keep
data class BusinessResponse(
    val id: String,
    val name: String,
    val type: String?,
    val gstNumber: String?,
    val ownerId: String?,
    val address: String?,
    val category: String?,
    val bankAccount: String?,
    val profileImageUrl: String?,
    /** The caller's own role in this business: "OWNER", "MANAGER" or "CASHIER". */
    val role: String? = null
)

@Keep
data class CreateBusinessRequest(
    val name: String,
    val type: String? = null
)

@Keep
data class UpdateBusinessRequest(
    val name: String? = null,
    val type: String? = null,
    val gstNumber: String? = null,
    val address: String? = null,
    val category: String? = null,
    val bankAccount: String? = null,
    val profileImageUrl: String? = null
)

@Keep
data class UpdateUserRequest(
    val name: String? = null
)

// ─── Staff DTOs ──────────────────────────────────────────────
/** A member of the active business. `membershipId` is what removal takes. */
@Keep
data class StaffMemberResponse(
    val membershipId: String,
    val userId: String,
    val name: String?,
    val phoneNumber: String,
    val role: String,
    val addedAt: String
)

@Keep
data class AddStaffRequest(
    val phoneNumber: String,
    val name: String,
    val role: String   // MANAGER or CASHIER; OWNER is refused server-side
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

@Keep
data class CreateBillItemRequest(
    val description: String,
    val quantity: Double,
    val price: Double,
    val tax: Double? = null
)

@Keep
data class CreateBillRequest(
    val partyId: String,
    val totalAmount: Double,
    val items: List<CreateBillItemRequest>,
    val isGst: Boolean? = null
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

@Keep
data class CreateItemRequest(
    val name: String,
    val sku: String? = null,
    val sellingPrice: Double? = null,
    val purchasePrice: Double? = null,
    val currentStock: Double? = null,
    val lowStockThreshold: Double? = null
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
    /** Business date; null for rows predating the field — see toEntity. */
    val transactionDate: String? = null,
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

    /**
     * Revokes the current session server-side by bumping sessionVersion, which
     * invalidates this JWT immediately rather than leaving it usable for the
     * remainder of its 7-day life.
     */
    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    // Scanning
    @Multipart
    @POST("api/v1/scanning/extract")
    suspend fun extractLedger(@Part image: okhttp3.MultipartBody.Part): Response<ExtractLedgerResponse>

    // Parties
    @GET("api/v1/parties")
    suspend fun getParties(@Query("type") type: String? = null): Response<List<PartyResponse>>

    @POST("api/v1/parties")
    suspend fun createParty(
        @Body body: CreatePartyRequest,
        @Header("x-business-id") businessId: String
    ): Response<PartyResponse>

    @PATCH("api/v1/parties/{id}/interest")
    suspend fun updatePartyInterest(
        @Path("id") id: String,
        @Body body: UpdatePartyInterestRequest,
        @Header("x-business-id") businessId: String
    ): Response<PartyResponse>

    @DELETE("api/v1/parties/{id}")
    suspend fun deleteParty(
        @Path("id") id: String,
        @Header("x-business-id") businessId: String
    ): Response<Unit>

    // Ledger
    @GET("api/v1/ledger/party/{partyId}")
    suspend fun getLedgerEntries(@Path("partyId") partyId: String): Response<List<LedgerEntryResponse>>

    @POST("api/v1/ledger")
    suspend fun createLedgerEntry(
        @Body body: CreateLedgerEntryRequest,
        @Header("x-business-id") businessId: String
    ): Response<LedgerEntryResponse>

    @PATCH("api/v1/ledger/{id}")
    suspend fun updateLedgerEntry(
        @Path("id") id: String,
        @Body body: UpdateLedgerEntryRequest,
        @Header("x-business-id") businessId: String
    ): Response<LedgerEntryResponse>

    @DELETE("api/v1/ledger/{id}")
    suspend fun deleteLedgerEntry(
        @Path("id") id: String,
        @Header("x-business-id") businessId: String
    ): Response<Unit>

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

    // Staff
    @GET("users/staff")
    suspend fun getStaff(): Response<List<StaffMemberResponse>>

    @POST("users/staff")
    suspend fun addStaff(@Body body: AddStaffRequest): Response<StaffMemberResponse>

    @DELETE("users/staff/{membershipId}")
    suspend fun removeStaff(@Path("membershipId") membershipId: String): Response<Unit>

    // Businesses
    @GET("businesses")
    suspend fun getBusinesses(): Response<List<BusinessResponse>>

    @POST("businesses")
    suspend fun createBusiness(@Body body: CreateBusinessRequest): Response<BusinessResponse>

    @PUT("businesses/{id}")
    suspend fun updateBusiness(@Path("id") id: String, @Body body: UpdateBusinessRequest): Response<BusinessResponse>

    @PUT("users/me")
    suspend fun updateMe(@Body body: UpdateUserRequest): Response<Unit>

    // Reports
    @GET("reports/cashflow")
    suspend fun getCashflow(
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null
    ): Response<CashflowResponse>

    /**
     * Server-generated PDF statement (`ReportsController.downloadStatement` /
     * `PdfService.generateBusinessStatement`) — business-wide when [partyId] is
     * omitted, which is exactly the "Detailed Report" the Cashbook report
     * screen downloads. `@Streaming` so the whole PDF is not buffered into
     * memory as a single OkHttp response body before it can be written to disk.
     */
    @Streaming
    @GET("reports/statement/pdf")
    suspend fun downloadStatementPdf(
        @Query("partyId") partyId: String? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null
    ): Response<okhttp3.ResponseBody>

    // Bills
    @GET("bills")
    suspend fun getBills(): Response<List<BillResponse>>

    @POST("bills")
    suspend fun createBill(@Body body: CreateBillRequest): Response<BillResponse>

    // Inventory
    @GET("inventory")
    suspend fun getInventoryItems(): Response<List<InventoryItemResponse>>

    @POST("inventory")
    suspend fun createItem(@Body body: CreateItemRequest): Response<InventoryItemResponse>
}
