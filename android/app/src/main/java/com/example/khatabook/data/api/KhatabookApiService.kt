package com.example.khatabook.data.api

import retrofit2.Response
import retrofit2.http.*

// ─── Auth DTOs ───────────────────────────────────────────────
data class VerifyTokenRequest(val idToken: String)
data class VerifyTokenResponse(val access_token: String, val is_new_user: Boolean)

// ─── Party DTOs ──────────────────────────────────────────────
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

data class CreatePartyRequest(
    val name: String,
    val phone: String,
    val type: String,
    val gstin: String? = null,
    val businessId: String = "biz_1"
)

// ─── Ledger DTOs ─────────────────────────────────────────────
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

data class CreateLedgerEntryRequest(
    val partyId: String,
    val amount: Double,
    val type: String,        // "GAVE" or "GOT"
    val note: String = "",
    val paymentMethod: String = "Cash",
    val source: String = "MANUAL"
)

// ─── API Interface ────────────────────────────────────────────
interface KhatabookApiService {

    // Auth
    @POST("auth/verify-token")
    suspend fun verifyToken(@Body body: VerifyTokenRequest): Response<VerifyTokenResponse>

    // Parties
    @GET("api/v1/parties")
    suspend fun getParties(@Query("type") type: String? = null): Response<List<PartyResponse>>

    @POST("api/v1/parties")
    suspend fun createParty(@Body body: CreatePartyRequest): Response<PartyResponse>

    @DELETE("api/v1/parties/{id}")
    suspend fun deleteParty(@Path("id") id: String): Response<Unit>

    // Ledger
    @GET("api/v1/ledger/entries")
    suspend fun getLedgerEntries(@Query("partyId") partyId: String): Response<List<LedgerEntryResponse>>

    @POST("api/v1/ledger/entries")
    suspend fun createLedgerEntry(@Body body: CreateLedgerEntryRequest): Response<LedgerEntryResponse>

    @DELETE("api/v1/ledger/entries/{id}")
    suspend fun deleteLedgerEntry(@Path("id") id: String): Response<Unit>
}
