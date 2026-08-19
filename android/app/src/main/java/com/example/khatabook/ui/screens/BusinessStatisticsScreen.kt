package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.theme.*

/** ISO-8601 UTC lower bound for a period label. */
private fun statisticsStartDate(period: String): String {
    val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    when (period) {
        "This Month" -> calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        "This Year" -> calendar.set(java.util.Calendar.DAY_OF_YEAR, 1)
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
fun BusinessStatisticsScreen(
    onBackClick: () -> Unit,
    repository: com.example.khatabook.data.repository.KhatabookRepository,
    modifier: Modifier = Modifier
) {
    var selectedPeriod by remember { mutableStateOf("This Month") } // "This Month", "This Year", "All Time"
    val scrollState = rememberScrollState()

    // Cashflow totals: repository-cached, short-lived (see CASHFLOW_TTL_MS).
    // The "top customer"/"total transactions" figures reuse the same
    // business-wide ledger feed cache CashbookScreen renders from, filtered
    // to the period locally rather than fetched again with its own date
    // range — one cache, not two overlapping ones.
    val cashflow by repository.cashflowReport.collectAsState()
    val feed by repository.businessLedgerFeed.collectAsState()
    var isLoading by remember { mutableStateOf(cashflow == null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedPeriod) {
        isLoading = repository.cashflowReport.value == null
        loadError = null
        val from = statisticsStartDate(selectedPeriod)
        val ok = repository.syncCashflow(startDate = from)
        repository.syncBusinessLedgerFeed()
        if (!ok && repository.cashflowReport.value == null) {
            // Only surface an error when there is nothing cached to fall back
            // on — a failed background refresh over an existing cache is not
            // something the user needs to be told about.
            loadError = "Could not reach the server. Check your connection."
        }
        isLoading = false
    }

    val cashCollectedState = cashflow?.totalIn ?: 0.0
    val creditGivenState = cashflow?.totalOut ?: 0.0
    val netFlowState = cashflow?.net ?: 0.0

    val periodStart = remember(selectedPeriod) { statisticsStartDate(selectedPeriod) }
    val periodRows = remember(feed, periodStart) { feed.filter { it.createdAt >= periodStart } }
    val totalTxnsState = periodRows.size
    // Whoever accounts for the most received money in the period.
    val topCustomerState = remember(periodRows) {
        periodRows
            .filter { it.type == "GOT" }
            .groupBy { it.party?.name }
            .mapNotNull { (name, entries) ->
                name?.let { it to entries.sumOf { e -> e.amount } }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("📊 Business Statistics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        // Share action removed: there was no sharing implementation
                        // behind it (no PDF/text export wired up for this screen),
                        // so tapping it silently did nothing.
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(KhatabookBgLight)
                .verticalScroll(scrollState)
                .padding(14.dp)
        ) {
            // Time Period Selector Row
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    listOf("This Month", "This Year", "All Time").forEach { period ->
                        val isSelected = selectedPeriod == period
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) KhatabookHeaderBlue else Color.Transparent)
                                .clickable { selectedPeriod = period }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = period,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else KhatabookTextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Animated Statistics Cards
            AnimatedContent(
                targetState = selectedPeriod,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "statsTransition"
            ) { targetPeriod ->
                // Figures for the selected period, fetched above.
                @Suppress("UNUSED_PARAMETER") val period = targetPeriod
                val cashCollected = cashCollectedState
                val creditGiven = creditGivenState
                val netFlow = netFlowState
                val totalTxns = totalTxnsState
                val topCustomer = topCustomerState ?: "No activity yet"

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Net Balance Hero Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Net Business Growth & Cashflow", fontSize = 12.sp, color = KhatabookTextMuted)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "₹ ${netFlow.toInt()}",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookHeaderBlue
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Visual Progress Bar
                            val total = cashCollected + creditGiven
                            val collectedRatio = (cashCollected / total).toFloat()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Collected (${(collectedRatio * 100).toInt()}%)", fontSize = 11.sp, color = KhatabookGotGreen, fontWeight = FontWeight.Bold)
                                Text("Credit (${((1 - collectedRatio) * 100).toInt()}%)", fontSize = 11.sp, color = KhatabookGiveRed, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
                                Box(modifier = Modifier.weight(collectedRatio).fillMaxHeight().background(KhatabookGotGreen))
                                Box(modifier = Modifier.weight(1f - collectedRatio).fillMaxHeight().background(KhatabookGiveRed))
                            }
                        }
                    }

                    // Cash Collected vs Credit Given Cards Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🟢", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cash Collected", fontSize = 11.sp, color = KhatabookTextMuted)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "₹ ${cashCollected.toInt()}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = KhatabookGotGreen
                                )
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔴", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Credit Given", fontSize = 11.sp, color = KhatabookTextMuted)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "₹ ${creditGiven.toInt()}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = KhatabookGiveRed
                                )
                            }
                        }
                    }

                    // Detailed Key Metrics Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Key Highlights", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Total Transactions Logged", fontSize = 12.sp, color = KhatabookTextMuted)
                                Text("$totalTxns entries", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = KhatabookBorderLight)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Top Customer by Activity", fontSize = 12.sp, color = KhatabookTextMuted)
                                Text(topCustomer, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Simple Helper data class
