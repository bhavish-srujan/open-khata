package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.khatabook.data.model.InterestPeriodUnit
import com.example.khatabook.data.model.Party
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.BounceOutlinedButton
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*
import com.example.khatabook.utils.InterestCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerProfileScreen(
    party: Party,
    onBackClick: () -> Unit,
    onDeleteParty: (String) -> Unit,
    /** Saves the party's interest configuration. */
    onUpdateInterest: (enabled: Boolean, rate: Double, periodValue: Int, unit: InterestPeriodUnit) -> Unit = { _, _, _, _ -> },
    /** Charges the configured interest as a real ledger entry. Returns the
     *  amount charged, or null when there was nothing to charge. */
    onApplyInterest: () -> Double? = { null },
    /** OWNER/MANAGER only, matching the server's own restriction on delete. */
    canManageParty: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showInterestDialog by remember { mutableStateOf(false) }
    var showApplyInterestConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Preview of what an Apply Interest charge would post right now, so the
    // confirmation states a real number instead of asking the user to trust
    // an unseen calculation.
    val pendingInterest = InterestCalculator.calculate(party, kotlin.math.abs(party.currentBalance))

    // There is no party-update endpoint on the backend yet (only create and
    // delete), so these fields cannot be saved anywhere real. Rather than
    // silently doing nothing on tap — indistinguishable from a bug — or
    // faking a local-only save that would never reach the server, tapping
    // says plainly that editing isn't available yet.
    val notYetAvailable = { android.widget.Toast.makeText(context, "Editing this isn't available yet", android.widget.Toast.LENGTH_SHORT).show() }

    if (showInterestDialog) {
        InterestConfigDialog(
            party = party,
            onDismiss = { showInterestDialog = false },
            onSave = { enabled, rate, periodValue, unit ->
                showInterestDialog = false
                onUpdateInterest(enabled, rate, periodValue, unit)
            }
        )
    }

    if (showApplyInterestConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyInterestConfirm = false },
            title = { Text("Charge interest?") },
            text = {
                Text(
                    "This adds a real entry of ₹${"%.2f".format(pendingInterest)} to ${party.name}'s ledger, " +
                        "calculated as ${InterestCalculator.describePeriod(party)} on the current balance of " +
                        "₹${"%.2f".format(kotlin.math.abs(party.currentBalance))}.\n\n" +
                        "It will change their balance and can be deleted from the ledger like any other entry."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showApplyInterestConfirm = false
                    val charged = onApplyInterest()
                    android.widget.Toast.makeText(
                        context,
                        if (charged != null) "Interest of ₹${"%.2f".format(charged)} added"
                        else "Nothing to charge",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) { Text("CHARGE INTEREST") }
            },
            dismissButton = {
                TextButton(onClick = { showApplyInterestConfirm = false }) { Text("CANCEL") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Customer") },
            text = { Text("Are you sure you want to delete ${party.name}? All ledger entries will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteParty(party.id)
                }) {
                    Text("Delete", color = KhatabookGiveRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Customer Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
                    )
                }
            }
        },
        bottomBar = {
            if (canManageParty) {
                Surface(
                    color = Color.White,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        BounceOutlinedButton(
                            onClick = { showDeleteDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KhatabookGiveRed),
                            border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookGiveRed),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DELETE CUSTOMER", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
                .verticalScroll(rememberScrollState())
        ) {
            // Profile Header area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F6))
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(80.dp)) {
                        // Main avatar
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(1.dp, Color.LightGray, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Person, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(60.dp))
                        }
                        
                        // Camera badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(KhatabookHeaderBlue)
                                .border(2.dp, Color(0xFFF3F4F6), CircleShape)
                                .bounceClick {},
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "Add Photo", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add photo", fontSize = 12.sp, color = KhatabookHeaderBlue, modifier = Modifier.bounceClick {})
                }
            }

            // Customer Details List
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ProfileListItem(
                    icon = Icons.Outlined.Person,
                    title = "Name",
                    subtitle = party.name,
                    onClick = notYetAvailable
                )
                ProfileListItem(
                    icon = Icons.Outlined.Phone,
                    title = "Mobile Number",
                    subtitle = party.phone,
                    onClick = {
                        android.widget.Toast.makeText(context, "Mobile number can't be changed here", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                ProfileListItem(
                    icon = Icons.Default.LocationOn,
                    title = "Add Address",
                    onClick = notYetAvailable
                )
                ProfileListItem(
                    icon = Icons.Outlined.Assignment,
                    title = "GSTIN",
                    onClick = notYetAvailable
                )
                ProfileListItem(
                    icon = Icons.Default.SyncAlt,
                    title = "Change to Supplier",
                    onClick = notYetAvailable
                )
                ProfileListItem(
                    icon = Icons.Default.Percent,
                    title = "Interest",
                    subtitle = if (party.interestEnabled) InterestCalculator.describePeriod(party) else "Not set",
                    onClick = { showInterestDialog = true }
                )

                // Only offered when there is genuinely something to charge:
                // interest configured AND an outstanding balance. Otherwise
                // the action would be a no-op the user cannot distinguish
                // from a bug.
                if (party.interestEnabled && pendingInterest > 0.0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showApplyInterestConfirm = true }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AddCard, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Charge interest now", fontSize = 16.sp, color = KhatabookHeaderBlue, fontWeight = FontWeight.Bold)
                            Text(
                                "Adds ₹${"%.2f".format(pendingInterest)} as a ledger entry",
                                fontSize = 12.sp,
                                color = KhatabookTextMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Customer Settings Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F6))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("Customer Settings", fontSize = 12.sp, color = KhatabookTextMuted)
            }

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ProfileListItem(
                    icon = Icons.Default.Message,
                    title = "Customer SMS Settings",
                    subtitle = "SMS will be sent on each entry",
                    onClick = notYetAvailable
                )
                ProfileListItem(
                    icon = Icons.Default.Translate,
                    title = "SMS Language",
                    subtitle = "English",
                    onClick = notYetAvailable
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Edits a party's interest configuration. Same fields and same validation as
 * the Add Party screen's interest section — a rate applies as-is to whatever
 * period is configured, never annualized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterestConfigDialog(
    party: Party,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, rate: Double, periodValue: Int, unit: InterestPeriodUnit) -> Unit
) {
    // Seeded from the party's stored values so opening the dialog on a
    // configured party shows what is actually set, not an empty form.
    var enabled by remember { mutableStateOf(party.interestEnabled) }
    var rateText by remember { mutableStateOf(if (party.interestRate > 0) party.interestRate.toString().removeSuffix(".0") else "") }
    var periodText by remember { mutableStateOf(if (party.interestPeriodValue > 0) party.interestPeriodValue.toString() else "") }
    var unit by remember { mutableStateOf(party.interestPeriodUnit) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interest") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it; error = null },
                        colors = SwitchDefaults.colors(checkedTrackColor = KhatabookHeaderBlue)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Charge interest on this account", fontSize = 13.sp, color = KhatabookTextDark)
                }

                if (enabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it; error = null },
                        label = { Text("Interest Rate (%)", fontSize = 12.sp) },
                        placeholder = { Text("e.g. 5", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = periodText,
                        onValueChange = { periodText = it; error = null },
                        label = { Text("Period", fontSize = 12.sp) },
                        placeholder = { Text("e.g. 100", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row {
                        FilterChip(
                            selected = unit == InterestPeriodUnit.DAYS,
                            onClick = { unit = InterestPeriodUnit.DAYS },
                            label = { Text("Days", fontSize = 12.sp) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = unit == InterestPeriodUnit.MONTHS,
                            onClick = { unit = InterestPeriodUnit.MONTHS },
                            label = { Text("Months", fontSize = 12.sp) }
                        )
                    }

                    error?.let {
                        Text(it, fontSize = 12.sp, color = KhatabookGiveRed, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!enabled) {
                    // Turning it off needs no rate/period at all.
                    onSave(false, 0.0, 0, InterestPeriodUnit.DAYS)
                    return@TextButton
                }
                val rate = rateText.toDoubleOrNull()
                val period = periodText.toIntOrNull()
                error = when {
                    rate == null || rate < 0.0 -> "Enter a valid interest rate"
                    period == null || period <= 0 -> "Enter a valid period (greater than 0)"
                    else -> null
                }
                if (error == null) onSave(true, rate!!, period!!, unit)
            }) { Text("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
fun ProfileListItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, 
                fontSize = if (subtitle != null) 12.sp else 16.sp, 
                color = if (subtitle != null) KhatabookTextMuted else KhatabookTextDark
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = 16.sp, color = KhatabookTextDark)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(20.dp))
    }
}
