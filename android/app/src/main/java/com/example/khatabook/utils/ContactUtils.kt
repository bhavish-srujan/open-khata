package com.example.khatabook.utils

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.ui.graphics.Color
import com.example.khatabook.ui.screens.ContactItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun fetchDeviceContacts(context: Context): List<ContactItem> = withContext(Dispatchers.IO) {
    val contactsList = mutableListOf<ContactItem>()
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ),
        null,
        null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )

    cursor?.use {
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

        val seenIds = mutableSetOf<String>()

        while (it.moveToNext()) {
            val id = it.getString(idIndex) ?: continue
            val name = it.getString(nameIndex) ?: "Unknown"
            val rawPhone = it.getString(numberIndex) ?: ""

            if (!seenIds.contains(id)) {
                seenIds.add(id)
                val initials = if (name.isNotBlank()) name.take(1).uppercase() else "C"
                // Pick a color deterministically based on name hash
                val colors = listOf(
                    Color(0xFFEF4444), Color(0xFF3B82F6), Color(0xFF10B981),
                    Color(0xFFF59E0B), Color(0xFF8B5CF6), Color(0xFFEC4899),
                    Color(0xFF06B6D4), Color(0xFF84CC16)
                )
                val color = colors[kotlin.math.abs(name.hashCode()) % colors.size]
                
                contactsList.add(
                    ContactItem(
                        id = id,
                        name = name,
                        phone = rawPhone,
                        avatarText = initials,
                        avatarBg = color
                    )
                )
            }
        }
    }
    contactsList
}
