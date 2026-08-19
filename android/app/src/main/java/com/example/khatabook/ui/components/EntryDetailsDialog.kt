package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailsDialog(
    party: Party,
    entry: LedgerEntry,
    onDismiss: () -> Unit,
    onEditEntry: (entryId: String, newAmount: Double, newNote: String) -> Unit,
    onDeleteEntry: (entryId: String) -> Unit,
    /** OWNER/MANAGER only, matching the server's own restriction on edit/delete. */
    canManage: Boolean = true
) {
    var isEditing by remember { mutableStateOf(false) }
    var editAmountText by remember { mutableStateOf(entry.amount.toInt().toString()) }
    var editNoteText by remember { mutableStateOf(entry.note) }

    val isGave = entry.type == TransactionType.GAVE
    val amountColor = if (isGave) KhatabookGiveRed else KhatabookGotGreen
    val dateStr = SimpleDateFormat("dd MMM yy • hh:mm a", Locale.getDefault()).format(Date(entry.createdAt))

    val context = androidx.compose.ui.platform.LocalContext.current
    // Previously this card's heading always read the hardcoded literal "SMS
    // disabled" regardless of the real setting, and showed a fabricated
    // "Sent by..." line plus a fake khatabook.com link that went nowhere —
    // this reads the same business-scoped setting SmsSettingsScreen actually
    // saves (see KhatabookRepository's addLedgerEntry, which reads the
    // identical key when this entry's SMS was originally sent).
    val smsEnabled = remember(party.businessId) {
        context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("send_transaction_sms_${party.businessId}", true)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = KhatabookBgLight
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Blue Header Bar matching Screenshot
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Entry Details", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                // Card 1: Top Entry Header Card matching Screenshot
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(KhatabookHeaderBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = party.name.take(1).uppercase(),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(party.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                    Text(dateStr, fontSize = 11.sp, color = KhatabookTextMuted)
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "₹ ${entry.amount.toInt()}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = amountColor
                                )
                                Text(
                                    text = if (isGave) "You gave" else "You got",
                                    fontSize = 11.sp,
                                    color = KhatabookTextMuted
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = KhatabookBorderLight)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Running Balance", fontSize = 13.sp, color = KhatabookTextDark)
                            Text(
                                text = "₹ ${party.currentBalance.toInt()}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = amountColor
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = KhatabookBorderLight)

                        // EDIT ENTRY Button Form — OWNER/MANAGER only
                        if (!isEditing && canManage) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bounceClick { isEditing = true }
                                    .clickable { isEditing = true },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("EDIT ENTRY", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            }
                        } else if (isEditing) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Edit Logged Amount & Note", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = editAmountText,
                                    onValueChange = { editAmountText = it },
                                    label = { Text("Amount (₹)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = editNoteText,
                                    onValueChange = { editNoteText = it },
                                    label = { Text("Note / Details") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    BounceTextButton(onClick = { isEditing = false }) {
                                        Text("Cancel", color = KhatabookTextMuted)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    BounceButton(
                                        onClick = {
                                            val newAmt = editAmountText.toDoubleOrNull() ?: entry.amount
                                            onEditEntry(entry.id, newAmt, editNoteText)
                                            isEditing = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue)
                                    ) {
                                        Text("Save Changes", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Card 2: SMS status — reflects the real per-business setting now,
                // not a hardcoded "disabled" label, and no longer claims a fake
                // delivery record or links to a URL that was never real.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Message, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (smsEnabled) "Transaction SMS enabled" else "Transaction SMS disabled",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookTextDark
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = KhatabookBorderLight)

                        Text("You ${if (isGave) "gave" else "got"}: ₹ ${entry.amount.toInt()}", fontSize = 13.sp, color = KhatabookTextMuted)
                        Text("Balance: -(₹ ${party.currentBalance.toInt()})", fontSize = 13.sp, color = KhatabookTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Card 3: Entry Cloud Backup Info matching Screenshot
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Entry is backed up", fontSize = 13.sp, color = KhatabookTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 100% Safe and Secure Badge matching Screenshot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = KhatabookGotGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("100% Safe and Secure", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookGotGreen)
                }
            }

            // Bottom Action Bar matching Screenshot: [ DELETE ] (Red Outlined) and [ SHARE ] (Solid Blue)
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (canManage) {
                        BounceOutlinedButton(
                            onClick = {
                                onDeleteEntry(entry.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookGiveRed),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = KhatabookGiveRed, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("DELETE", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = KhatabookGiveRed)
                            }
                        }
                    }

                    BounceButton(
                        onClick = {
                            // Previously wired to onDismiss — tapping "SHARE" only
                            // closed the dialog, sharing nothing. This opens the
                            // system share sheet with a plain-text summary instead.
                            val shareText = "${party.name} ${if (isGave) "gave" else "got"} ₹ ${entry.amount.toInt()} on $dateStr" +
                                (entry.note.takeIf { it.isNotBlank() }?.let { "\nNote: $it" } ?: "") +
                                "\nBalance: ₹ ${party.currentBalance.toInt()}"
                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(android.content.Intent.createChooser(shareIntent, "Share entry"))
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SHARE", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
