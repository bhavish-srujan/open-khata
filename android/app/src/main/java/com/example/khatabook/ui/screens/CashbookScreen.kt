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
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.example.khatabook.data.api.LedgerFeedEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.TransactionType
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

/**
 * Maps a ledger row onto a cashbook line.
 *
 * GOT means money came in, GAVE means credit went out — the same convention
 * LedgerService uses to sign balances. The note falls back to the party name so
 * a row is never blank.
 */
private fun LedgerFeedEntry.toCashbookEntry(): LocalCashbookEntry = LocalCashbookEntry(
    id = id,
    amount = amount,
    isCashIn = type == "GOT",
    note = note?.takeIf { it.isNotBlank() }
        ?: party?.name
        ?: if (type == "GOT") "Payment received" else "Credit given",
    time = createdAt.take(10)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashbookScreen(
    onBackClick: () -> Unit,
    /** The active business's parties, so a cash entry can be attributed to one. */
    parties: List<Party>,
    /**
     * Records a real transaction via [com.example.khatabook.data.repository.KhatabookRepository.addLedgerEntry]
     * — the same persistent, Outbox-backed flow every other entry point in the
     * app uses. Cash In/Cash Out entries used to be appended to a purely
     * local, in-memory list that was never sent anywhere and vanished the
     * next time this screen loaded.
     */
    onAddEntry: (partyId: String, amount: Double, type: TransactionType, note: String) -> Unit,
    onViewReportClick: () -> Unit = {},
    repository: com.example.khatabook.data.repository.KhatabookRepository,
    modifier: Modifier = Modifier
) {
    // Removed redundant BackHandler; MainActivity handles navStack popping centrally

    var showAddCashInDialog by remember { mutableStateOf(false) }
    var showAddCashOutDialog by remember { mutableStateOf(false) }

    // Repository-cached business-wide ledger feed: whatever was cached (or
    // optimistically patched by a create/edit/delete elsewhere in the app)
    // renders immediately, while syncBusinessLedgerFeed() refreshes it in the
    // background. This used to be a bare NetworkClient call on every visit,
    // with a blocking spinner and nothing shown offline.
    val feed by repository.businessLedgerFeed.collectAsState()
    val entries = feed.map { it.toCashbookEntry() }
    var isLoading by remember { mutableStateOf(feed.isEmpty()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = feed.isEmpty()
        loadError = null
        val ok = repository.syncBusinessLedgerFeed()
        if (!ok && feed.isEmpty()) {
            // Only surface an error when there is nothing cached to fall back
            // on — a failed background refresh over an existing cache is not
            // something the user needs to be told about.
            loadError = "Could not reach the server. Check your connection."
        }
        isLoading = false
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
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CASH OUT ₹", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    BounceButton(
                        onClick = { showAddCashInDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGotGreen),
                        modifier = Modifier.weight(1f).height(48.dp)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onViewReportClick),
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

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = KhatabookHeaderBlue
                    )
                }
            } else if (loadError != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(loadError!!, fontSize = 13.sp, color = KhatabookGiveRed)
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No cash entries yet", fontSize = 13.sp, color = KhatabookTextMuted)
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
        var amountError by remember { mutableStateOf<String?>(null) }
        var selectedParty by remember { mutableStateOf<Party?>(null) }
        var partyError by remember { mutableStateOf<String?>(null) }
        var showPartyMenu by remember { mutableStateOf(false) }
        val isCashIn = showAddCashInDialog

        fun close() {
            showAddCashInDialog = false
            showAddCashOutDialog = false
        }

        AlertDialog(
            onDismissRequest = { close() },
            title = { Text(if (isCashIn) "Add Cash In Entry" else "Add Cash Out Entry", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (parties.isEmpty()) {
                        Text(
                            "Add a party first — every cash entry is recorded against one, the same as the rest of the ledger.",
                            fontSize = 12.sp,
                            color = KhatabookTextMuted
                        )
                    } else {
                        Box {
                            OutlinedTextField(
                                value = selectedParty?.name ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Party") },
                                isError = partyError != null,
                                trailingIcon = {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            // A readOnly OutlinedTextField still consumes touches for its own
                            // focus/cursor handling, so a .clickable{} on the field itself never
                            // fires. This transparent overlay intercepts the tap first.
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { showPartyMenu = true }
                            )
                            DropdownMenu(expanded = showPartyMenu, onDismissRequest = { showPartyMenu = false }) {
                                parties.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            selectedParty = p
                                            partyError = null
                                            showPartyMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        if (partyError != null) {
                            Text(partyError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

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
                        val amount = amountText.toDoubleOrNull()
                        val party = selectedParty
                        when {
                            party == null -> partyError = "Select a party"
                            amount == null -> amountError = "Enter a valid amount"
                            amount <= 0 -> amountError = "Amount must be greater than zero"
                            else -> {
                                onAddEntry(
                                    party.id,
                                    amount,
                                    if (isCashIn) TransactionType.GOT else TransactionType.GAVE,
                                    noteText
                                )
                                close()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCashIn) KhatabookGotGreen else KhatabookGiveRed
                    )
                ) {
                    Text("SAVE ENTRY", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { close() }) {
                    Text("Cancel")
                }
            }
        )
    }
}
