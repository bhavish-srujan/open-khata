package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.BillResponse
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.ui.components.BounceExtendedFloatingActionButton
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

data class Bill(val id: String, val customerName: String, val date: String, val amount: Double, val status: String)

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
    status = if (isGst) "GST" else "Non-GST"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    onBackClick: () -> Unit
) {
    var bills by remember { mutableStateOf<List<Bill>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        loadError = null
        try {
            val response = NetworkClient.apiService.getBills()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                bills = body.map { it.toUiBill() }
            } else {
                loadError = when (response.code()) {
                    401 -> "Session expired. Please log in again."
                    403 -> "You do not have permission to view bills."
                    else -> "Could not load bills (HTTP ${response.code()})."
                }
            }
        } catch (e: Exception) {
            loadError = "Could not reach the server. Check your connection."
        } finally {
            isLoading = false
        }
    }

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
                onClick = { /* TODO: Create bill */ },
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
                        BillItemCard(bill = bills[index])
                    }
                }
            }
        }
    }
}

@Composable
fun BillItemCard(bill: Bill) {
    Card(
        modifier = Modifier.fillMaxWidth().bounceClick { },
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bounceClick { }) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View PDF", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookHeaderBlue)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bounceClick { }) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookHeaderBlue)
                }
            }
        }
    }
}
