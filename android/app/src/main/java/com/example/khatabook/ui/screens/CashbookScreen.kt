package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
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
import com.example.khatabook.ui.components.ItemPopIn
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

data class LocalCashbookEntry(
    val id: String,
    val amount: Double,
    val isCashIn: Boolean,
    val note: String,
    val time: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashbookScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Removed redundant BackHandler; MainActivity handles navStack popping centrally

    var showAddCashInDialog by remember { mutableStateOf(false) }
    var showAddCashOutDialog by remember { mutableStateOf(false) }

    val entries = remember {
        mutableStateListOf(
            LocalCashbookEntry("1", 500.0, true, "Counter Cash Sales", "Today, 10:30 AM"),
            LocalCashbookEntry("2", 150.0, false, "Tea & Snacks Expense", "Today, 11:15 AM"),
            LocalCashbookEntry("3", 1200.0, true, "Customer Payment Received", "Yesterday, 04:45 PM")
        )
    }

    val totalCashIn = remember(entries) { entries.filter { it.isCashIn }.sumOf { it.amount } }
    val totalCashOut = remember(entries) { entries.filter { !it.isCashIn }.sumOf { it.amount } }
    val netCashBalance = remember(totalCashIn, totalCashOut) { totalCashIn - totalCashOut }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cashbook", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                        },
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
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BounceButton(
                        onClick = { showAddCashOutDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGiveRed),
                        modifier = Modifier.weight(1f).height(48.dp).bounceClick { showAddCashOutDialog = true }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CASH OUT ₹", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    BounceButton(
                        onClick = { showAddCashInDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGotGreen),
                        modifier = Modifier.weight(1f).height(48.dp).bounceClick { showAddCashInDialog = true }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CASH IN ₹", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                .padding(14.dp)
        ) {
            ItemPopIn {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Total Cash In", fontSize = 11.sp, color = KhatabookTextMuted)
                                Text("₹ ${totalCashIn.toInt()}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookGotGreen)
                            }
                            Box(modifier = Modifier.width(1.dp).height(28.dp).background(KhatabookBorderLight))
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Total Cash Out", fontSize = 11.sp, color = KhatabookTextMuted)
                                Text("₹ ${totalCashOut.toInt()}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                            }
                            Box(modifier = Modifier.width(1.dp).height(28.dp).background(KhatabookBorderLight))
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Net Balance", fontSize = 11.sp, color = KhatabookTextMuted)
                                Text("₹ ${netCashBalance.toInt()}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = KhatabookBorderLight)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("VIEW CASHBOOK REPORT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text("Daily Cash Entries", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
            Spacer(modifier = Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No cash entries added today", fontSize = 13.sp, color = KhatabookTextMuted)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(entry.note, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                    Text(entry.time, fontSize = 11.sp, color = KhatabookTextMuted)
                                }

                                Text(
                                    text = "${if (entry.isCashIn) "+ ₹ " else "- ₹ "}${entry.amount.toInt()}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (entry.isCashIn) KhatabookGotGreen else KhatabookGiveRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddCashInDialog || showAddCashOutDialog) {
        var amountText by remember { mutableStateOf("") }
        var noteText by remember { mutableStateOf("") }
        val isCashIn = showAddCashInDialog

        AlertDialog(
            onDismissRequest = {
                showAddCashInDialog = false
                showAddCashOutDialog = false
            },
            title = { Text(if (isCashIn) "Add Cash In Entry" else "Add Cash Out Entry", fontWeight = FontWeight.Bold) },
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
                        label = { Text("Remark / Description") },
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
                            entries.add(0, LocalCashbookEntry("${entries.size + 1}", amount, isCashIn, noteText.ifBlank { if (isCashIn) "Cash In" else "Cash Out" }, "Just now"))
                            showAddCashInDialog = false
                            showAddCashOutDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCashIn) KhatabookGotGreen else KhatabookGiveRed
                    ),
                    modifier = Modifier.bounceClick {
                        val amount = amountText.toDoubleOrNull() ?: 0.0
                        if (amount > 0) {
                            entries.add(0, LocalCashbookEntry("${entries.size + 1}", amount, isCashIn, noteText.ifBlank { if (isCashIn) "Cash In" else "Cash Out" }, "Just now"))
                            showAddCashInDialog = false
                            showAddCashOutDialog = false
                        }
                    }
                ) {
                    Text("SAVE ENTRY", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = {
                    showAddCashInDialog = false
                    showAddCashOutDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
