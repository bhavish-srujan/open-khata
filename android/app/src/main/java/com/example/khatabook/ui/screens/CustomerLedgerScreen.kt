package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.example.khatabook.utils.PdfGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Share
import android.widget.Toast
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
import androidx.compose.ui.window.Dialog
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.ui.components.EntryDetailsDialog
import com.example.khatabook.ui.components.ItemPopIn
import com.example.khatabook.ui.components.LedgerEntryCard
import com.example.khatabook.ui.components.RemindBottomSheet
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerLedgerScreen(
    party: Party,
    entries: List<LedgerEntry>,
    onBackClick: () -> Unit,
    onAddEntry: (partyId: String, amount: Double, type: TransactionType, note: String) -> Unit,
    onDeleteParty: (partyId: String) -> Unit = {},
    onEditEntry: (entryId: String, newAmount: Double, newNote: String) -> Unit = { _, _, _ -> },
    onDeleteEntry: (entryId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Removed redundant BackHandler; MainActivity handles navStack popping centrally

    var showAddEntryDialog by remember { mutableStateOf(false) }
    var showBillPdfDialog by remember { mutableStateOf(false) }
    var showRemindBottomSheet by remember { mutableStateOf(false) }
    var selectedEntryType by remember { mutableStateOf(TransactionType.GAVE) }
    var selectedEntryForDetails by remember { mutableStateOf<LedgerEntry?>(null) }
    val context = LocalContext.current

    // 2-Step (Double Confirmation) Delete Customer Flow States
    var showDeleteStep1Dialog by remember { mutableStateOf(false) }
    var showDeleteStep2Dialog by remember { mutableStateOf(false) }
    var confirmNameText by remember { mutableStateOf("") }

    val partyEntries = entries.filter { it.partyId == party.id }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = party.name.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(party.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(party.phone, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                                }
                            }
                        },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        actions = {
                            BounceIconButton(onClick = {
                                try {
                                    val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${party.phone}"))
                                    context.startActivity(callIntent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "No Phone App found", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Call, contentDescription = "Call", tint = Color.White)
                            }
                            BounceIconButton(onClick = { showDeleteStep1Dialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Customer", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // YOU GAVE Button with Pop-in Zoom Press Animation
                    BounceButton(
                        onClick = {
                            selectedEntryType = TransactionType.GAVE
                            showAddEntryDialog = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGiveRed),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .bounceClick {
                                selectedEntryType = TransactionType.GAVE
                                showAddEntryDialog = true
                            }
                    ) {
                        Text("YOU GAVE ₹", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // YOU GOT Button with Pop-in Zoom Press Animation
                    BounceButton(
                        onClick = {
                            selectedEntryType = TransactionType.GOT
                            showAddEntryDialog = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGotGreen),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .bounceClick {
                                selectedEntryType = TransactionType.GOT
                                showAddEntryDialog = true
                            }
                    ) {
                        Text("YOU GOT ₹", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(KhatabookBgLight)
        ) {
            // Net Balance Header Banner
            Surface(
                color = Color.White,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Net Balance", fontSize = 12.sp, color = KhatabookTextMuted)
                        val isGet = party.currentBalance > 0
                        Text(
                            text = "₹ ${kotlin.math.abs(party.currentBalance).toInt()}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (party.currentBalance == 0.0) KhatabookTextDark else if (isGet) KhatabookGiveRed else KhatabookGotGreen
                        )
                    }

                    Row {
                        BounceOutlinedButton(
                            onClick = { showRemindBottomSheet = true },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookHeaderBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Message, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remind", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        BounceOutlinedButton(
                            onClick = { showBillPdfDialog = true },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookHeaderBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Bill", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Entries List with smooth pop-in animations
            if (partyEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No entries added yet for ${party.name}", fontSize = 14.sp, color = KhatabookTextMuted)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tap 'YOU GAVE' or 'YOU GOT' to record a transaction", fontSize = 12.sp, color = KhatabookTextMuted)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp, top = 6.dp)
                ) {
                    // Stable key prevents full-list recomposition when a single entry changes
                    items(partyEntries, key = { entry -> entry.id }) { entry ->
                        LedgerEntryCard(
                            entry = entry,
                            onClick = { selectedEntryForDetails = entry }
                        )
                    }
                }
            }
        }
    }

    if (showRemindBottomSheet) {
        RemindBottomSheet(
            party = party,
            onDismiss = { showRemindBottomSheet = false },
            onSmsClick = {
                try {
                    val message = "Dear ${party.name}, your total outstanding balance is ₹ ${kotlin.math.abs(party.currentBalance).toInt()}. Please clear your dues on Khatabook."
                    val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:${party.phone}")).apply {
                        putExtra("sms_body", message)
                    }
                    context.startActivity(smsIntent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No SMS app found", Toast.LENGTH_SHORT).show()
                }
                showRemindBottomSheet = false
            },
            onWhatsappClick = {
                try {
                    val message = "Dear ${party.name}, your total outstanding balance is ₹ ${kotlin.math.abs(party.currentBalance).toInt()}. Please clear your dues on Khatabook."
                    val encodedMessage = Uri.encode(message)
                    val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${party.phone}&text=$encodedMessage"))
                    context.startActivity(whatsappIntent)
                } catch (e: Exception) {
                    Toast.makeText(context, "WhatsApp not found", Toast.LENGTH_SHORT).show()
                }
                showRemindBottomSheet = false
            }
        )
    }

    // Add Entry Dialog Modal
    if (showAddEntryDialog) {
        var amountText by remember { mutableStateOf("") }
        var noteText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddEntryDialog = false },
            title = {
                Text(
                    text = if (selectedEntryType == TransactionType.GAVE) "You Gave Money to ${party.name}" else "You Got Money from ${party.name}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Enter Details (e.g. Rice, Oil, Bill #)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        val amount = amountText.toDoubleOrNull() ?: 0.0
                        if (amount > 0) {
                            onAddEntry(party.id, amount, selectedEntryType, noteText)
                            showAddEntryDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedEntryType == TransactionType.GAVE) KhatabookGiveRed else KhatabookGotGreen
                    ),
                    modifier = Modifier.bounceClick {
                        val amount = amountText.toDoubleOrNull() ?: 0.0
                        if (amount > 0) {
                            onAddEntry(party.id, amount, selectedEntryType, noteText)
                            showAddEntryDialog = false
                        }
                    }
                ) {
                    Text("Save Transaction")
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { showAddEntryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ----------------------------------------------------
    // STEP 1 DELETE CONFIRMATION DIALOG (Crucial Action Warning)
    // ----------------------------------------------------
    if (showDeleteStep1Dialog) {
        AlertDialog(
            onDismissRequest = { showDeleteStep1Dialog = false },
            title = {
                Text(
                    text = "Delete Customer Record?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookGiveRed
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${party.name}' and all their transaction history? Deleting customer records is crucial and requires double confirmation to prevent accidental loss.",
                    fontSize = 13.sp,
                    color = KhatabookTextDark,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        showDeleteStep1Dialog = false
                        confirmNameText = ""
                        showDeleteStep2Dialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookGiveRed),
                    modifier = Modifier.bounceClick {
                        showDeleteStep1Dialog = false
                        confirmNameText = ""
                        showDeleteStep2Dialog = true
                    }
                ) {
                    Text("PROCEED TO DELETE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { showDeleteStep1Dialog = false }) {
                    Text("Cancel", color = KhatabookTextMuted)
                }
            }
        )
    }

    // ----------------------------------------------------
    // STEP 2 FINAL SECURITY TYPE-TO-CONFIRM DIALOG
    // ----------------------------------------------------
    if (showDeleteStep2Dialog) {
        val isNameMatched = confirmNameText.trim().equals(party.name.trim(), ignoreCase = true)

        AlertDialog(
            onDismissRequest = { showDeleteStep2Dialog = false },
            title = {
                Text(
                    text = "⚠️ Final Deletion Security Check",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookGiveRed
                )
            },
            text = {
                Column {
                    Text(
                        text = "To permanently delete '${party.name}', type '${party.name}' below to confirm:",
                        fontSize = 13.sp,
                        color = KhatabookTextDark,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = confirmNameText,
                        onValueChange = { confirmNameText = it },
                        placeholder = { Text("Type '${party.name}'", color = KhatabookTextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KhatabookGiveRed,
                            unfocusedBorderColor = KhatabookBorderLight
                        )
                    )
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        if (isNameMatched) {
                            showDeleteStep2Dialog = false
                            onDeleteParty(party.id)
                            onBackClick()
                        }
                    },
                    enabled = isNameMatched,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KhatabookGiveRed,
                        disabledContainerColor = Color(0xFFD1D5DB),
                        disabledContentColor = Color.White
                    ),
                    modifier = Modifier.bounceClick {
                        if (isNameMatched) {
                            showDeleteStep2Dialog = false
                            onDeleteParty(party.id)
                            onBackClick()
                        }
                    }
                ) {
                    Text("PERMANENTLY DELETE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { showDeleteStep2Dialog = false }) {
                    Text("Cancel", color = KhatabookTextMuted)
                }
            }
        )
    }

    // Bill Statement PDF Dialog Modal
    if (showBillPdfDialog) {
        Dialog(onDismissRequest = { showBillPdfDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 Bill Statement PDF", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        BounceIconButton(onClick = { showBillPdfDialog = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = KhatabookBorderLight)

                    Text("Customer: ${party.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Phone: ${party.phone}", fontSize = 12.sp, color = KhatabookTextMuted)

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Total Outstanding Dues: ₹ ${kotlin.math.abs(party.currentBalance).toInt()}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)

                    Spacer(modifier = Modifier.height(16.dp))

                    BounceButton(
                        onClick = {
                            showBillPdfDialog = false
                            PdfGenerator.generateAndShareLedgerReport(context, party, partyEntries)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        modifier = Modifier.fillMaxWidth().height(44.dp).bounceClick {
                            showBillPdfDialog = false
                            PdfGenerator.generateAndShareLedgerReport(context, party, partyEntries)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SHARE PDF STATEMENT", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // Entry Details Modal Dialog
    if (selectedEntryForDetails != null) {
        Dialog(onDismissRequest = { selectedEntryForDetails = null }) {
            EntryDetailsDialog(
                party = party,
                entry = selectedEntryForDetails!!,
                onDismiss = { selectedEntryForDetails = null },
                onEditEntry = { entryId, newAmount, newNote ->
                    onEditEntry(entryId, newAmount, newNote)
                    selectedEntryForDetails = null
                },
                onDeleteEntry = { entryId ->
                    onDeleteEntry(entryId)
                    selectedEntryForDetails = null
                }
            )
        }
    }
}
