package com.example.khatabook.data.model

import java.util.UUID

@androidx.compose.runtime.Immutable
data class BusinessAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String = "General Store",
    val isPrimary: Boolean = false,
    val gstNumber: String? = null,
    val address: String? = null,
    val category: String? = null,
    val bankAccount: String? = null,
    val profileImageUrl: String? = null,
    /**
     * The caller's own role in this business: "OWNER", "MANAGER" or
     * "CASHIER". Null only when not yet known (e.g. before the businesses
     * list has loaded) — UI gating treats that as permissive, matching the
     * status quo, since the server is the actual authority either way.
     */
    val role: String? = null
)
