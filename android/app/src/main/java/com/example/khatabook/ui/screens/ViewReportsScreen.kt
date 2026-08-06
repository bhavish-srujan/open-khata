package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.ui.theme.*
import com.example.khatabook.utils.PdfGenerator
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.LedgerEntry

data class ReportItem(
    val title: String,
    val subtitle: String,
    val icon: String = "📄"
)

/**
 * ISO-8601 UTC start bound for a time period label.
 *
 * Uses Calendar rather than java.time because minSdk is 24 and core library
 * desugaring is not enabled.
 */
private fun startDateFor(period: String): String {
    val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    when (period) {
        "This Month" -> calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        "This Year" -> calendar.set(java.util.Calendar.DAY_OF_YEAR, 1)
        // "All Time": an epoch-ish floor. The server defaults to the last month
        // when startDate is omitted, so an explicit early bound is required.
        else -> calendar.timeInMillis = 0L
    }
    if (period != "All Time") {
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
    }
    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(calendar.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewReportsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTimePeriod by remember { mutableStateOf("This Month") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = listOf("All", "Customer", "Bills", "GST", "Day-wise", "Inventory", "Supplier")

    // Real cashflow for the selected period. Refetches whenever the period
    // changes; previously these were three hardcoded Triples.
    var collected by remember { mutableStateOf(0.0) }
    var creditGiven by remember { mutableStateOf(0.0) }
    var netCashflow by remember { mutableStateOf(0.0) }
    var isLoadingCashflow by remember { mutableStateOf(true) }
    var cashflowError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedTimePeriod) {
        isLoadingCashflow = true
        cashflowError = null
        try {
            val response = NetworkClient.apiService.getCashflow(
                startDate = startDateFor(selectedTimePeriod)
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                collected = body.totalIn
                creditGiven = body.totalOut
                netCashflow = body.net
            } else {
                cashflowError = when (response.code()) {
                    401 -> "Session expired. Please log in again."
                    403 -> "You do not have permission to view reports."
                    else -> "Could not load reports (HTTP ${response.code()})."
                }
            }
        } catch (e: Exception) {
            cashflowError = "Could not reach the server. Check your connection."
        } finally {
            isLoadingCashflow = false
        }
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("View Reports & Statistics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    BounceButton(
                        onClick = {
                            val dummyParty = Party(id = "dummy", name = "My Business", phone = "", type = PartyType.CUSTOMER)
                            val dummyEntries = emptyList<LedgerEntry>()
                            PdfGenerator.generateAndShareLedgerReport(context, dummyParty, dummyEntries)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("📄 DOWNLOAD FULL BUSINESS STATEMENT", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📊 Business Statistics", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("This Month", "This Year", "All Time").forEach { time ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selectedTimePeriod == time) KhatabookHeaderBlue else Color(0xFFF1F5F9))
                                        .clickable { selectedTimePeriod = time }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = time,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTimePeriod == time) Color.White else KhatabookTextMuted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isLoadingCashflow) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = KhatabookHeaderBlue
                            )
                        }
                    } else if (cashflowError != null) {
                        Text(
                            text = cashflowError!!,
                            fontSize = 12.sp,
                            color = KhatabookGiveRed,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        )
                    } else {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Cash Collected", fontSize = 10.sp, color = KhatabookTextMuted)
                            Text("₹ ${collected.toInt()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookGotGreen)
                        }

                        Box(modifier = Modifier.width(1.dp).height(30.dp).background(KhatabookBorderLight))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Credit Given", fontSize = 10.sp, color = KhatabookTextMuted)
                            Text("₹ ${creditGiven.toInt()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                        }

                        Box(modifier = Modifier.width(1.dp).height(30.dp).background(KhatabookBorderLight))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Net Cashflow", fontSize = 10.sp, color = KhatabookTextMuted)
                            Text("₹ ${netCashflow.toInt()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                    }

                    } // end cashflow loaded branch
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = KhatabookHeaderBlue,
                            selectedLabelColor = Color.White,
                            containerColor = Color.White,
                            labelColor = KhatabookTextDark
                        )
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selectedCategory == "All" || selectedCategory == "Customer") {
                    item {
                        ReportGroupCard(
                            groupTitle = "Customer Reports",
                            items = listOf(
                                ReportItem("Customer Transactions report", "Summary of all customer transactions"),
                                ReportItem("Customer list pdf", "List of all Customers")
                            )
                        )
                    }
                }

                if (selectedCategory == "All" || selectedCategory == "Bills") {
                    item {
                        ReportGroupCard(
                            groupTitle = "Bills Reports",
                            items = listOf(
                                ReportItem("Sales Report", "Summary of all Sales"),
                                ReportItem("Purchase Report", "Summary of all Purchases"),
                                ReportItem("Cashbook Report", "Summary of all Cashflows")
                            )
                        )
                    }
                }

                if (selectedCategory == "All" || selectedCategory == "GST") {
                    item {
                        ReportGroupCard(
                            groupTitle = "GST Reports",
                            items = listOf(
                                ReportItem("GSTR 1 Report", "Sales GST return summary"),
                                ReportItem("GSTR 2 Report", "Purchase GST tax credit"),
                                ReportItem("GSTR 3B Report", "Monthly consolidated GST summary")
                            )
                        )
                    }
                }

                if (selectedCategory == "All" || selectedCategory == "Day-wise") {
                    item {
                        ReportGroupCard(
                            groupTitle = "Day-wise Reports",
                            items = listOf(
                                ReportItem("Sales Day-wise Report", "Daily Sales Summary"),
                                ReportItem("Purchase Day-wise Report", "Daily Purchases Summary")
                            )
                        )
                    }
                }

                if (selectedCategory == "All" || selectedCategory == "Inventory") {
                    item {
                        ReportGroupCard(
                            groupTitle = "Inventory Reports",
                            items = listOf(
                                ReportItem("Stock Summary", "Summary of all items"),
                                ReportItem("Low Stock Summary Report", "Summary of all low stock items"),
                                ReportItem("Profit & Loss Report", "Summary of Item level profit & loss")
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReportGroupCard(
    groupTitle: String,
    items: List<ReportItem>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = groupTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark
            )

            Spacer(modifier = Modifier.height(6.dp))

            items.forEachIndexed { idx, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.icon, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookTextDark)
                        Text(item.subtitle, fontSize = 11.sp, color = KhatabookTextMuted)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = KhatabookTextMuted)
                }
                if (idx < items.size - 1) {
                    HorizontalDivider(color = KhatabookBorderLight)
                }
            }
        }
    }
}
