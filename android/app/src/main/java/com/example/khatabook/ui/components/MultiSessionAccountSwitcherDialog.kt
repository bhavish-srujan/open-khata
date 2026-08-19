package com.example.khatabook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.khatabook.ui.theme.*

/**
 * Resolves what to show for one account row: its own stored name, trimmed,
 * or a safe generic fallback — never another account's name, never the raw
 * phone number, and never a fabricated name. [accountNames] is keyed by
 * exact phone number, so a lookup for one phone can never return a value
 * stored under a different one; a phone missing from the map (or mapped to
 * blank/whitespace) falls back to [fallback].
 */
internal fun resolveAccountDisplayName(
    phone: String,
    accountNames: Map<String, String>,
    fallback: String = "User Account"
): String = accountNames[phone]?.trim()?.takeIf { it.isNotBlank() } ?: fallback

@Composable
fun MultiSessionAccountSwitcherDialog(
    phoneNumbers: List<String>,
    /** Each phone's stored display name (e.g. via the "user_name" per-account
     *  preference) — a phone missing here, or mapped to a blank string, falls
     *  back to a generic label rather than ever showing another account's
     *  name or the phone number itself as a name. */
    accountNames: Map<String, String> = emptyMap(),
    activePhoneNumber: String,
    onPhoneSelect: (String) -> Unit,
    onAddNewAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Switch User Account", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Account List
                phoneNumbers.forEach { phone ->
                    val isSelected = phone == activePhoneNumber
                    val displayName = resolveAccountDisplayName(phone, accountNames)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0xFFEFF6FF) else Color.Transparent)
                            .clickable {
                                onPhoneSelect(phone)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) KhatabookHeaderBlue else Color(0xFF94A3B8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "U",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            Text(phone, fontSize = 11.sp, color = KhatabookTextMuted)
                        }

                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Active", tint = KhatabookHeaderBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = KhatabookBorderLight)

                // Add New Account Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onAddNewAccount()
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEFF6FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = KhatabookHeaderBlue)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text("Add Another Account", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                }
            }
        }
    }
}
