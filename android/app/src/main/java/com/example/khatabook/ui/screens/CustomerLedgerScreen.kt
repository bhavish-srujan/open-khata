package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.example.khatabook.utils.isDialable
import com.example.khatabook.utils.SheetDates
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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Notifications
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
import com.example.khatabook.ui.components.LedgerEntryCard
import com.example.khatabook.ui.components.RemindBottomSheet
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.utils.SmsSender
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerLedgerScreen(
    party: Party,
    entries: List<LedgerEntry>,
    onBackClick: () -> Unit,
    onAddEntry: (partyId: String, amount: Double, type: TransactionType, note: String, transactionDate: Long) -> Unit,
    onDeleteParty: (partyId: String) -> Unit = {},
    onEditEntry: (entryId: String, newAmount: Double, newNote: String) -> Unit = { _, _, _ -> },
    onDeleteEntry: (entryId: String) -> Unit = {},
    onViewProfileClick: () -> Unit = {},
    onSendBulkReminders: () -> Unit = {},
    /** OWNER/MANAGER only, matching the server's own restriction on edit/delete. */
    canManageLedgerEntries: Boolean = true,
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
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(party.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(modifier = Modifier.background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                            Text("Customer", color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                    Text("View settings >", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.clickable { onViewProfileClick() })
                                }
                            }
                        },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        actions = {
                            // Only offered when there is a real number to dial.
                            // Hidden rather than disabled: a permanently-dead
                            // control invites tapping and explains nothing, and
                            // the placeholder number some older Scan Khata
                            // records carry is not dialable either.
                            if (isDialable(party.phone)) {
                                BounceIconButton(onClick = {
                                    // ACTION_DIAL, not ACTION_CALL: it opens the
                                    // dialer pre-filled and lets the user press
                                    // call, so the app needs no CALL_PHONE
                                    // permission.
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_DIAL,
                                        android.net.Uri.parse("tel:${android.net.Uri.encode(party.phone.trim())}")
                                    )
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        // A device with no dialer at all (rare,
                                        // but tablets exist) must not crash.
                                        android.widget.Toast.makeText(
                                            context, "No dialer app available", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }) {
                                    Icon(Icons.Default.Call, contentDescription = "Call ${party.name}", tint = Color.White)
                                }
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
            // Net Balance Header Banner & Actions
            Surface(
                color = Color.White,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    val isGet = party.currentBalance > 0
                    val isGive = party.currentBalance < 0
                    val balanceAmount = kotlin.math.abs(party.currentBalance).toInt()
                    
                    // Main Balance Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(
                                color = if (isGet) KhatabookGotGreen.copy(alpha = 0.1f) 
                                        else if (isGive) KhatabookGiveRed.copy(alpha = 0.1f) 
                                        else Color(0xFFF3F4F6),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isGet) "You will get" else if (isGive) "You will give" else "Settled up",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isGet) KhatabookGotGreen else if (isGive) KhatabookGiveRed else KhatabookTextDark
                        )
                        Text(
                            text = "₹ $balanceAmount",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isGet) KhatabookGotGreen else if (isGive) KhatabookGiveRed else KhatabookTextDark
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Collection Reminder
                    if (isGet) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Set collection reminder", fontSize = 12.sp, color = KhatabookTextDark)
                            Text(
                                "SET DATE", 
                                fontSize = 12.sp, 
                                fontWeight = FontWeight.Bold, 
                                color = KhatabookHeaderBlue,
                                modifier = Modifier.clickable { /* Handle Set Date */ }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Divider(color = Color(0xFFF1F5F9))

                    // 3 Quick Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QuickActionButton(icon = Icons.Default.Description, label = "Report", onClick = { showBillPdfDialog = true })
                        QuickActionButton(icon = Icons.Default.Notifications, label = "Reminder", onClick = { showRemindBottomSheet = true })
                        QuickActionButton(icon = Icons.Default.Message, label = "SMS", onClick = {
                            // Sent directly rather than opening the Messages app,
                            // which used to throw the user out of the ledger.
                            val message = "Dear ${party.name}, your total outstanding balance is ₹ $balanceAmount. Please clear your dues on Open Khata."
                            when (val result = SmsSender.send(context, party.phone, message, party.businessId)) {
                                is SmsSender.Result.Sent ->
                                    Toast.makeText(context, "Reminder sent to ${party.name}", Toast.LENGTH_SHORT).show()
                                is SmsSender.Result.PermissionMissing ->
                                    Toast.makeText(context, "Allow SMS permission in Settings to send reminders", Toast.LENGTH_LONG).show()
                                is SmsSender.Result.Failed ->
                                    Toast.makeText(context, "Could not send: ${result.reason}", Toast.LENGTH_LONG).show()
                            }
                        })
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
                run {
                    val message = "Dear ${party.name}, your total outstanding balance is ₹ ${kotlin.math.abs(party.currentBalance).toInt()}. Please clear your dues on Open Khata."
                    when (val result = SmsSender.send(context, party.phone, message, party.businessId)) {
                        is SmsSender.Result.Sent ->
                            Toast.makeText(context, "Reminder sent", Toast.LENGTH_SHORT).show()
                        is SmsSender.Result.PermissionMissing ->
                            Toast.makeText(context, "Allow SMS permission in Settings to send reminders", Toast.LENGTH_LONG).show()
                        is SmsSender.Result.Failed ->
                            Toast.makeText(context, "Could not send: ${result.reason}", Toast.LENGTH_LONG).show()
                    }
                }
                showRemindBottomSheet = false
            },
            onWhatsappClick = {
                try {
                    val message = "Dear ${party.name}, your total outstanding balance is ₹ ${kotlin.math.abs(party.currentBalance).toInt()}. Please clear your dues on Open Khata."
                    val encodedMessage = Uri.encode(message)
                    val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${party.phone}&text=$encodedMessage"))
                    context.startActivity(whatsappIntent)
                } catch (e: Exception) {
                    Toast.makeText(context, "WhatsApp not found", Toast.LENGTH_SHORT).show()
                }
                showRemindBottomSheet = false
            },
            onSendBulkReminders = {
                onSendBulkReminders()
                showRemindBottomSheet = false
            }
        )
    }

    // Add Entry Dialog Modal
    if (showAddEntryDialog) {
        var amountText by remember { mutableStateOf("") }
        var noteText by remember { mutableStateOf("") }
        var amountError by remember { mutableStateOf<String?>(null) }
        // Defaults to today, so the common case needs no interaction at all.
        // Held as text because the field is directly typeable as well as
        // picker-driven; it is parsed (and rejected) on save.
        var dateText by remember { mutableStateOf(SheetDates.format(SheetDates.today())) }
        var dateError by remember { mutableStateOf<String?>(null) }
        var showDatePicker by remember { mutableStateOf(false) }

        if (showDatePicker) {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = SheetDates.parseOrNull(dateText) ?: SheetDates.today()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let {
                            dateText = SheetDates.format(it)
                            dateError = null
                        }
                        showDatePicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("CANCEL") }
                }
            ) {
                DatePicker(state = pickerState, colors = khatabookDatePickerColors())
            }
        }

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
                        onValueChange = { amountText = it; amountError = null },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = amountError != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    if (amountError != null) {
                        Text(amountError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Enter Details (e.g. Rice, Oil, Bill #)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Typeable *and* pickable: entering a backdated run of
                    // transactions is faster by keyboard, while the picker
                    // avoids format guesswork for a one-off.
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it; dateError = null },
                        label = { Text("Date") },
                        singleLine = true,
                        isError = dateError != null,
                        placeholder = { Text(SheetDates.DISPLAY_PATTERN.lowercase(), fontSize = 12.sp) },
                        trailingIcon = {
                            BounceIconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.CalendarToday, contentDescription = "Pick date", tint = KhatabookHeaderBlue)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    if (dateError != null) {
                        Text(dateError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        val amount = amountText.toDoubleOrNull()
                        // Rejected, never coerced: silently turning 31/02 into
                        // 03/03 would file money under a day the user did not
                        // choose.
                        val parsedDate = SheetDates.parseOrNull(dateText)
                        when {
                            amount == null -> amountError = "Enter a valid amount"
                            amount <= 0 -> amountError = "Amount must be greater than zero"
                            parsedDate == null -> dateError = "Enter a valid date (${SheetDates.DISPLAY_PATTERN.lowercase()})"
                            !SheetDates.isNotInFuture(parsedDate) -> dateError = "Date cannot be in the future"
                            else -> {
                                onAddEntry(party.id, amount, selectedEntryType, noteText, parsedDate)
                                showAddEntryDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedEntryType == TransactionType.GAVE) KhatabookGiveRed else KhatabookGotGreen
                    )
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
                },
                canManage = canManageLedgerEntries
            )
        }
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFFF1F5F9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = KhatabookTextDark)
    }
}
