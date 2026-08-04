package com.example.khatabook.data.model

import java.util.UUID

data class BusinessAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String = "General Store",
    val isPrimary: Boolean = false
)
