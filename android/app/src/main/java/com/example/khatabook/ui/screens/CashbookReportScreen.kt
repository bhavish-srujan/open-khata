package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.LedgerFeedEntry
import com.example.khatabook.data.repository.KhatabookRepository
import com.example.khatabook.ui.components.BounceButton
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.BounceTextButton
import com.example.khatabook.ui.components.khatabookDatePickerColors
import com.example.khatabook.ui.theme.*
import com.example.khatabook.utils.PdfGenerator
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.utils.ReportPeriod
import com.example.khatabook.utils.ReportPeriodUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private fun displayDate(isoDay: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDay)
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed!!)
} catch (_: Exception) {
    isoDay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashbookReportScreen(
    onBackClick: () -> Unit,
    businessName: String,
    repository: KhatabookRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var period by remember { mutableStateOf(ReportPeriod.THIS_MONTH) }
    /** Null means "All Collections". */
    var collectionFilter by remember { mutableStateOf<CollectionFrequency?>(null) }
    var customStartMs by remember { mutableStateOf<Long?>(null) }
    var customEndMs by remember { mutableStateOf<Long?>(null) }
    var showDateRangePicker by remember { mutableStateOf(false) }
    var rangeValidationError by remember { mutableStateOf<String?>(null) }
    var expandedDay by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var showDownloadMenu by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val isoFormat = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    val range = remember(period, customStartMs, customEndMs) {
        ReportPeriodUtils.rangeFor(
            period,
            customStartMs?.let { isoFormat.format(java.util.Date(it)) },
            customEndMs?.let { isoFormat.format(java.util.Date(it)) },
        )
    }

    val isRestoringFromCloud by repository.isRestoringFromCloud.collectAsState()
    val cashflow by repository.cashflowReport.collectAsState()
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(range) {
        isLoading = repository.cashflowReport.value == null
        loadError = null
        val ok = repository.syncCashflow(startDate = range.startIso, endDate = range.endIso)
        if (!ok && repository.cashflowReport.value == null) {
            loadError = "Could not reach the server. Check your connection."
        }
        isLoading = false
    }

    // Collection type and date range are independent dimensions: this narrows
    // by which parties a row belongs to, the range narrows by when it
    // happened. Neither is derived from the other.
    val feedSnapshot = repository.businessLedgerFeed.collectAsState().value
    val partiesSnapshot = repository.parties.collectAsState().value

    val dayWiseRows = remember(range, collectionFilter, feedSnapshot, partiesSnapshot) {
        repository.dayWiseBreakdown(range.startIso, range.endIso, collectionFilter)
    }
    val (cashIn, cashOut) = remember(range, collectionFilter, feedSnapshot, partiesSnapshot) {
        repository.cashbookTotals(range.startIso, range.endIso, collectionFilter)
    }
    // Running/total balance is cumulative across the whole reported range, in
    // chronological order — dayWiseBreakdown returns newest-first for display,
    // so the running total is computed over the reversed (oldest-first) list.
    val runningTotals = remember(dayWiseRows) {
        var running = 0.0
        dayWiseRows.reversed().associate { (day, entries) ->
            val dayIn = entries.filter { it.type == "GOT" }.sumOf { it.amount }
            val dayOut = entries.filter { it.type == "GAVE" }.sumOf { it.amount }
            running += (dayIn - dayOut)
            day to running
        }
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Cashbook Report", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        actions = {
                            Box {
                                BounceIconButton(onClick = { showDownloadMenu = true }, enabled = !isDownloading) {
                                    if (isDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = "Download report", tint = Color.White)
                                    }
                                }
                                DropdownMenu(expanded = showDownloadMenu, onDismissRequest = { showDownloadMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Detailed Report") },
                                        onClick = {
                                            showDownloadMenu = false
                                            scope.launch {
                                                isDownloading = true
                                                downloadError = null
                                                val file = repository.downloadDetailedStatementPdf(range.startIso, range.endIso)
                                                isDownloading = false
                                                if (file != null) {
                                                    PdfGenerator.sharePdf(context, file, "$businessName — Detailed Statement")
                                                } else {
                                                    downloadError = "Could not generate the report. Check your connection and try again."
                                                }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Day-wise Summary") },
                                        onClick = {
                                            showDownloadMenu = false
                                            scope.launch {
                                                isDownloading = true
                                                downloadError = null
                                                val rows = dayWiseRows.reversed().map { (day, entries) ->
                                                    val dayIn = entries.filter { it.type == "GOT" }.sumOf { it.amount }
                                                    val dayOut = entries.filter { it.type == "GAVE" }.sumOf { it.amount }
                                                    PdfGenerator.DaySummaryRow(
                                                        dateLabel = displayDate(day),
                                                        cashIn = dayIn,
                                                        cashOut = dayOut,
                                                        dailyBalance = dayIn - dayOut,
                                                        runningBalance = runningTotals[day] ?: 0.0,
                                                    )
                                                }.reversed()
                                                val file = PdfGenerator.generateDayWiseSummaryPdf(
                                                    context = context,
                                                    businessName = businessName,
                                                    periodLabel = period.label,
                                                    rangeLabel = "${displayDate(range.startIso.take(10))} to ${displayDate(range.endIso.take(10))}",
                                                    rows = rows,
                                                    totalIn = cashflow?.totalIn ?: 0.0,
                                                    totalOut = cashflow?.totalOut ?: 0.0,
                                                )
                                                isDownloading = false
                                                if (file != null) {
                                                    PdfGenerator.sharePdf(context, file, "$businessName — Day-wise Summary")
                                                } else {
                                                    downloadError = "Could not generate the report file."
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(KhatabookBgLight)
        ) {
            // ─── Period selector ───────────────────────────────────────
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ReportPeriod.values().toList()) { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = {
                            period = p
                            rangeValidationError = null
                            if (p == ReportPeriod.DATE_RANGE) {
                                showDateRangePicker = true
                            }
                        },
                        label = { Text(p.label, fontSize = 12.sp) }
                    )
                }
            }

            if (period == ReportPeriod.DATE_RANGE && customStartMs != null && customEndMs != null) {
                Text(
                    text = "${displayDate(range.startIso.take(10))} — ${displayDate(range.endIso.take(10))}",
                    fontSize = 12.sp,
                    color = KhatabookTextMuted,
                    modifier = Modifier.padding(horizontal = 14.dp).clickable { showDateRangePicker = true }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            downloadError?.let {
                Text(it, color = KhatabookGiveRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }

            // ─── Summary card (authoritative server totals) ───────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = KhatabookHeaderBlue)
                    }
                } else if (loadError != null) {
                    Text(loadError!!, color = KhatabookGiveRed, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                } else {
                    // Computed from the same rows listed below, not from the
                    // server's own report. Two reasons: the server cannot
                    // filter by collection, so a filtered list beside
                    // unfiltered totals would contradict itself; and deriving
                    // them locally means the figures are right offline and
                    // update the instant an entry is added.
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SummaryStat("Cash In", cashIn, KhatabookGotGreen)
                        SummaryStat("Cash Out", cashOut, KhatabookGiveRed)
                        SummaryStat("Net", cashIn - cashOut, KhatabookHeaderBlue)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Collection filter — a separate dimension from the date chips
            // above, so the two combine rather than override each other.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp)
            ) {
                listOf(
                    "All Collections" to null,
                    "Daily Collection" to CollectionFrequency.DAILY,
                    "Weekly Collection" to CollectionFrequency.WEEKLY,
                    "Monthly Collection" to CollectionFrequency.MONTHLY,
                ).forEach { (label, value) ->
                    FilterChip(
                        selected = collectionFilter == value,
                        onClick = { collectionFilter = value },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = KhatabookHeaderBlue,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            Text(
                // Names the active collection, so the heading cannot imply a
                // filter is applied when it is not (or vice versa).
                when (collectionFilter) {
                    CollectionFrequency.DAILY -> "Daily Collection"
                    CollectionFrequency.WEEKLY -> "Weekly Collection"
                    CollectionFrequency.MONTHLY -> "Monthly Collection"
                    null -> "All Collections"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (isRestoringFromCloud && dayWiseRows.isEmpty()) {
                // Nothing has been fetched yet — saying "no transactions"
                // here would read as an empty book rather than a pending one.
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = KhatabookHeaderBlue)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Restoring your data…", fontSize = 13.sp, color = KhatabookTextMuted)
                    }
                }
            } else if (!isLoading && loadError == null && dayWiseRows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        // Names the collection, so an empty Weekly does not
                        // look like an empty Cashbook.
                        when (collectionFilter) {
                            CollectionFrequency.DAILY -> "No transactions in Daily Collection"
                            CollectionFrequency.WEEKLY -> "No transactions in Weekly Collection"
                            CollectionFrequency.MONTHLY -> "No transactions in Monthly Collection"
                            null -> "No transactions in this period"
                        },
                        fontSize = 13.sp,
                        color = KhatabookTextMuted,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(dayWiseRows, key = { it.first }) { (day, entries) ->
                        val dayIn = entries.filter { it.type == "GOT" }.sumOf { it.amount }
                        val dayOut = entries.filter { it.type == "GAVE" }.sumOf { it.amount }
                        val dailyBalance = dayIn - dayOut
                        val totalBalance = runningTotals[day] ?: 0.0
                        val isExpanded = expandedDay == day

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedDay = if (isExpanded) null else day }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(displayDate(day), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookTextDark)
                                    Text(
                                        "₹${dailyBalance.toInt()}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dailyBalance >= 0) KhatabookGotGreen else KhatabookGiveRed
                                    )
                                    Text("₹${totalBalance.toInt()}", fontSize = 13.sp, color = KhatabookTextMuted)
                                    Icon(
                                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = KhatabookTextMuted
                                    )
                                }
                                if (isExpanded) {
                                    HorizontalDivider(color = KhatabookBorderLight)
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        entries.forEach { entry -> DayEntryRow(entry) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDateRangePicker) {
        val startState = rememberDatePickerState(initialSelectedDateMillis = customStartMs)
        val endState = rememberDatePickerState(initialSelectedDateMillis = customEndMs)
        var pickingStart by remember { mutableStateOf(true) }

        // DatePickerDialog, not AlertDialog: a calendar grid needs about 360dp
        // for its seven columns, and AlertDialog caps its content at the
        // narrower standard dialog width. The Saturday column was being
        // clipped — six weekday headers instead of seven, with the dates in
        // that column sliced down the middle.
        DatePickerDialog(
            onDismissRequest = {
                // Cancel preserves the previous selection — nothing is applied.
                showDateRangePicker = false
                if (period == ReportPeriod.DATE_RANGE && (customStartMs == null || customEndMs == null)) {
                    period = ReportPeriod.THIS_MONTH
                }
            },
            confirmButton = {
                BounceButton(onClick = {
                    if (pickingStart) {
                        pickingStart = false
                    } else {
                        val s = startState.selectedDateMillis
                        val e = endState.selectedDateMillis
                        if (s == null || e == null) {
                            rangeValidationError = "Pick both a start and end date"
                        } else {
                            val error = ReportPeriodUtils.validateCustomRange(s, e)
                            if (error != null) {
                                rangeValidationError = error
                            } else {
                                customStartMs = s
                                customEndMs = e
                                rangeValidationError = null
                                showDateRangePicker = false
                            }
                        }
                    }
                }) {
                    Text(if (pickingStart) "NEXT" else "APPLY")
                }
            },
            dismissButton = {
                BounceTextButton(onClick = {
                    showDateRangePicker = false
                    if (customStartMs == null || customEndMs == null) {
                        period = ReportPeriod.THIS_MONTH
                    }
                }) { Text("CANCEL") }
            }
        ) {
            if (rangeValidationError != null) {
                Text(
                    rangeValidationError!!,
                    color = KhatabookGiveRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            // key(pickingStart): the start and end steps must be separate
            // composables, not one picker with its state swapped underneath.
            // DatePicker remembers its own month-scrolling state internally
            // and without a key that state survived the swap, so the end step
            // inherited the start step's scroll position and its month arrows
            // then did nothing — navigation worked for the start date and was
            // frozen for the end date. Keying disposes the first picker and
            // composes the second fresh, in sync with its own state.
            key(pickingStart) {
                DatePicker(
                    state = if (pickingStart) startState else endState,
                    showModeToggle = false,
                    // The picker carries its own title, so the dialog no longer
                    // adds a second one above it — previously "Select start date"
                    // sat directly above the picker's own "Select date", with the
                    // pair eating enough height to crowd the calendar.
                    title = {
                        Text(
                            if (pickingStart) "Select start date" else "Select end date",
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                            fontWeight = FontWeight.Bold,
                            color = KhatabookTextDark,
                        )
                    },
                    // App blue rather than the stock Material purple, matching
                    // every other control on this screen.
                    colors = khatabookDatePickerColors(),
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = KhatabookTextMuted)
        Text("₹${value.toInt()}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun DayEntryRow(entry: LedgerFeedEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            entry.note?.takeIf { it.isNotBlank() } ?: entry.party?.name ?: "Entry",
            fontSize = 12.sp,
            color = KhatabookTextDark
        )
        Text(
            "${if (entry.type == "GOT") "+" else "-"} ₹${entry.amount.toInt()}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.type == "GOT") KhatabookGotGreen else KhatabookGiveRed
        )
    }
}
