package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.BillResponse
import com.example.khatabook.data.api.CreateBillItemRequest
import com.example.khatabook.data.api.CreateBillRequest
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.model.Party
import com.example.khatabook.ui.components.BounceButton
import com.example.khatabook.ui.components.BounceExtendedFloatingActionButton
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.BounceTextButton
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.launch

data class Bill(val id: String, val customerName: String, val date: String, val amount: Double, val status: String, val receiptUrl: String?)

/**
 * Maps a server bill onto the row model.
 *
 * `GET /bills` does not join the party, so the customer name is not available
 * here; the bill's own reference is shown instead of inventing one. Status is
 * derived from the GST flag, the only status-like field the entity carries.
 */
private fun BillResponse.toUiBill(): Bill = Bill(
    id = id,
    customerName = "Bill #${id.take(8)}",
    date = created_at.take(10),
    amount = totalAmount,
    status = if (isGst) "GST" else "Non-GST",
    receiptUrl = receiptUrl
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    onBackClick: () -> Unit,
    /** The active business's parties, so a bill can be attributed to one. */
    parties: List<Party> = emptyList(),
    repository: com.example.khatabook.data.repository.KhatabookRepository
) {
    // Repository-cached: renders whatever was cached instantly, refreshes in
    // the background. This used to be a bare NetworkClient call on every
    // visit, with a blocking spinner and nothing shown offline.
    val cachedBills by repository.bills.collectAsState()
    val bills = cachedBills.map { it.toUiBill() }
    var isLoading by remember { mutableStateOf(cachedBills.isEmpty()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showCreateBillDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun reload(force: Boolean = false) {
        isLoading = cachedBills.isEmpty()
        loadError = null
        val ok = repository.syncBills(force = force)
        if (!ok && cachedBills.isEmpty()) {
            // Only surface an error when there is nothing cached to fall back
            // on — a failed background refresh over an existing cache is not
            // something the user needs to be told about.
            loadError = "Could not reach the server. Check your connection."
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Bills & Invoices", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        },
        floatingActionButton = {
            BounceExtendedFloatingActionButton(
                onClick = {
                    if (parties.isEmpty()) {
                        android.widget.Toast.makeText(context, "Add a party first — every bill is recorded against one", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        showCreateBillDialog = true
                    }
                },
                containerColor = KhatabookHeaderBlue,
                contentColor = Color.White,
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Bill")
                Spacer(modifier = Modifier.width(8.dp))
                Text("CREATE NEW BILL", fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KhatabookBgLight)
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Recent Bills", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                when {
                    isLoading -> item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = KhatabookHeaderBlue
                            )
                        }
                    }

                    loadError != null -> item {
                        Text(
                            text = loadError!!,
                            fontSize = 13.sp,
                            color = KhatabookGiveRed,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }

                    bills.isEmpty() -> item {
                        Text(
                            text = "No bills yet. Create one to get started.",
                            fontSize = 13.sp,
                            color = KhatabookTextMuted,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }

                    else -> items(bills.size) { index ->
                        BillItemCard(
                            bill = bills[index],
                            onViewPdf = {
                                val url = bills[index].receiptUrl
                                if (url.isNullOrBlank()) {
                                    android.widget.Toast.makeText(context, "No PDF available for this bill", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    try {
                                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No app available to open this PDF", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onShare = {
                                val url = bills[index].receiptUrl
                                if (url.isNullOrBlank()) {
                                    android.widget.Toast.makeText(context, "No PDF available for this bill", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share bill"))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateBillDialog) {
        var selectedParty by remember { mutableStateOf<Party?>(null) }
        var showPartyMenu by remember { mutableStateOf(false) }
        var amountText by remember { mutableStateOf("") }
        var isGst by remember { mutableStateOf(false) }
        var partyError by remember { mutableStateOf<String?>(null) }
        var amountError by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSaving) showCreateBillDialog = false },
            title = { Text("Create Bill", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Box {
                        OutlinedTextField(
                            value = selectedParty?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Party") },
                            isError = partyError != null,
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

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it; amountError = null },
                        label = { Text("Total amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = amountError != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    if (amountError != null) {
                        Text(amountError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("GST bill", fontSize = 13.sp)
                        Switch(checked = isGst, onCheckedChange = { isGst = it })
                    }
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
                                isSaving = true
                                scope.launch {
                                    try {
                                        val response = NetworkClient.apiService.createBill(
                                            CreateBillRequest(
                                                partyId = party.id,
                                                totalAmount = amount,
                                                items = listOf(CreateBillItemRequest(description = "Bill", quantity = 1.0, price = amount)),
                                                isGst = isGst
                                            )
                                        )
                                        if (response.isSuccessful) {
                                            showCreateBillDialog = false
                                            reload(force = true)
                                        } else {
                                            android.widget.Toast.makeText(context, "Could not create bill (HTTP ${response.code()})", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Could not reach the server", android.widget.Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue)
                ) {
                    Text(if (isSaving) "Saving..." else "CREATE BILL", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { if (!isSaving) showCreateBillDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BillItemCard(bill: Bill, onViewPdf: () -> Unit = {}, onShare: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(KhatabookHeaderBlue.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Receipt, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(bill.customerName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        Text("${bill.id} • ${bill.date}", fontSize = 12.sp, color = KhatabookTextMuted)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("₹ ${bill.amount.toInt()}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    val statusColor = if (bill.status == "PAID") KhatabookGotGreen else KhatabookGiveRed
                    Text(bill.status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = KhatabookBorderLight)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bounceClick { onViewPdf() }) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View PDF", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookHeaderBlue)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bounceClick { onShare() }) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookHeaderBlue)
                }
            }
        }
    }
}
