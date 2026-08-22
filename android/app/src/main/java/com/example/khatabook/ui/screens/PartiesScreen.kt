package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType

import com.example.khatabook.ui.components.EmphasizedSmoothEasing
import com.example.khatabook.ui.components.LedgerSheetRecord
import com.example.khatabook.ui.components.SmartAiSheetScannerDialog
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.utils.SmsSender
import com.example.khatabook.ui.theme.*

@Composable
fun PartiesScreen(
    parties: List<Party>,
    // No demo default: businesses are real, server-issued records supplied by
    // the caller. The old default invented two shops that did not exist.
    businesses: List<BusinessAccount> = emptyList(),
    selectedBusinessId: String = "",
    profileLocksMap: Map<String, String> = emptyMap(),
    /** Opens the business switcher (MainActivity owns that dialog, including profile-lock verification). */
    onBusinessHeaderClick: () -> Unit = {},
    onAddPartyClick: () -> Unit,
    onCashbookClick: () -> Unit,
    onViewReportsClick: () -> Unit,
    onBusinessStatisticsClick: () -> Unit = {},
    onMoreTabClick: () -> Unit,
    onPartyClick: (Party) -> Unit,
    onDeleteParty: (partyId: String) -> Unit = {},
    onSheetRecordBulkSync: (records: List<LedgerSheetRecord>, date: String) -> Unit = { _, _ -> },
    /** OWNER/MANAGER only, matching the server's own restriction on delete. */
    canManageParty: Boolean = true,
    /** Queued writes the server has permanently refused — not lost, but not delivered either. */
    failedOperationCount: Int = 0,
    onRetryFailedOperations: () -> Unit = {},
    /**
     * True while this account's first-ever cloud restore is in flight on
     * this device (see KhatabookRepository.isRestoringFromCloud). An empty
     * [parties] list while this is true means "not fetched yet", not "this
     * business genuinely has none" — the empty state below must not claim
     * the latter until this goes false.
     */
    isRestoringFromCloud: Boolean = false,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(PartyType.CUSTOMER) }
    var searchQuery by remember { mutableStateOf("") }
    var frequencyFilter by remember { mutableStateOf<CollectionFrequency?>(null) }
    var showAiSheetScannerDialog by remember { mutableStateOf(false) }

    // Long Press Delete Customer Flow States
    var partyToDelete by remember { mutableStateOf<Party?>(null) }
    var showLongPressDeleteStep1 by remember { mutableStateOf(false) }
    var showLongPressDeleteStep2 by remember { mutableStateOf(false) }
    var longPressConfirmNameText by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState()

    // Wrapped in derivedStateOf so keyboard inset animation doesn't re-compose the entire screen
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isKeyboardVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    // Ultra-Fast Stable Scroll Check (only evaluates on item index boundaries)
    val isScrolledFromTop by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }

    // derivedStateOf: only re-reads when any of the three inputs change AND result changes
    val shouldShowSummaryCard by remember {
        derivedStateOf { searchQuery.isEmpty() && !isKeyboardVisible && !isScrolledFromTop }
    }

    val activeBusiness = businesses.find { it.id == selectedBusinessId }
        ?: businesses.firstOrNull()
        ?: BusinessAccount(id = "", name = "My Business")
    val context = LocalContext.current

    // Derived state: does the active tab have any parties? (used to hide FAB on empty state)
    // Keyed on parties/selectedBusinessId too — they're plain parameters, not
    // State, so a keyless derivedStateOf here would only ever read their
    // first-composition values and never notice a party being added/removed
    // without navigating away and back (which recreates this composable).
    val hasPartiesInCurrentTab by remember(parties, selectedBusinessId) {
        derivedStateOf {
            parties.any { it.businessId == selectedBusinessId && it.type == selectedTab }
        }
    }

    var partyToRemind by remember { mutableStateOf<Party?>(null) }

    // STRICT DATA ISOLATION PER BUSINESS ACCOUNT
    val activeBusinessParties = remember(parties, selectedBusinessId) {
        parties.filter { it.businessId == selectedBusinessId }
    }

    // Memoized: only recalculate when the filtered party list changes (not on every recomposition)
    val youWillGive = remember(activeBusinessParties) {
        activeBusinessParties.filter { it.type == PartyType.CUSTOMER && it.currentBalance < 0 }.sumOf { kotlin.math.abs(it.currentBalance) }
    }
    val youWillGet = remember(activeBusinessParties) {
        activeBusinessParties.filter { it.type == PartyType.CUSTOMER && it.currentBalance > 0 }.sumOf { it.currentBalance }
    }

    Box(modifier = modifier.fillMaxSize().background(KhatabookBgLight)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 4.dp)
                                .bounceClick { onBusinessHeaderClick() }
                        ) {
                            Icon(Icons.Default.Store, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeBusiness.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Switch business",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Cashbook Badge
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                                .bounceClick { onCashbookClick() }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("₹ Cashbook", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // SUB TABS: CUSTOMERS / SUPPLIERS WITH GOLD INDICATOR LINE
                    Row(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .bounceClick { selectedTab = PartyType.CUSTOMER },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "CUSTOMERS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == PartyType.CUSTOMER) Color.White else Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (selectedTab == PartyType.CUSTOMER) {
                                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(KhatabookGoldCoin, RoundedCornerShape(2.dp)))
                            } else {
                                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(Color.Transparent))
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .bounceClick { selectedTab = PartyType.SUPPLIER },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "SUPPLIERS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == PartyType.SUPPLIER) Color.White else Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (selectedTab == PartyType.SUPPLIER) {
                                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(KhatabookGoldCoin, RoundedCornerShape(2.dp)))
                            } else {
                                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(Color.Transparent))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // SILKY SMOOTH SHUTTER ANIMATION FOR YOU WILL GIVE / YOU WILL GET CARD
                    AnimatedVisibility(
                        visible = shouldShowSummaryCard,
                        enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300)) + shrinkVertically(animationSpec = tween(300))
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("You will give", fontSize = 12.sp, color = KhatabookTextDark)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "₹ ${youWillGive.toInt()}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = KhatabookGotGreen
                                        )
                                    }

                                    Box(modifier = Modifier.width(1.dp).height(30.dp).background(KhatabookBorderLight))

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("You will get", fontSize = 12.sp, color = KhatabookTextDark)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "₹ ${youWillGet.toInt()}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = KhatabookGiveRed
                                        )
                                    }
                                }

                                // View Reports Bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF1F5F9)) // Light bluish-grey background
                                        .bounceClick { onViewReportsClick() }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("View Reports", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sync issue banner: writes the server has permanently refused
            // (most commonly a role no longer permitting an action) used to
            // be recorded only in the outbox with no visible signal at all —
            // this is the one place in the app that surfaces it.
            if (failedOperationCount > 0) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (failedOperationCount == 1) {
                                "1 change could not be saved to the server"
                            } else {
                                "$failedOperationCount changes could not be saved to the server"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF92400E),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "RETRY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = KhatabookHeaderBlue,
                            modifier = Modifier.bounceClick { onRetryFailedOperations() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // UNIFIED SEARCH RECTANGLE WITH FILTER BUTTON INSIDE ON THE RIGHT
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight),
                        shadowElevation = 0.5.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Crossfade(
                                targetState = searchQuery.isNotEmpty(),
                                animationSpec = tween(220, easing = EmphasizedSmoothEasing),
                                label = "searchToXMorph"
                            ) { isSearching ->
                                if (isSearching) {
                                    // BounceIconButton, not a bare Box: gives this a
                                    // real ~44dp touch target (the previous 24dp Box
                                    // was also the click area itself, well under the
                                    // recommended minimum) while keeping the same
                                    // 24dp/15dp visual. Also drops the redundant
                                    // .clickable{} stacked on top of .bounceClick{}
                                    // doing the exact same thing.
                                    BounceIconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(KhatabookGiveRed.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Clear Search",
                                                tint = KhatabookGiveRed,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = KhatabookHeaderBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text("Search Customer", fontSize = 14.sp, color = KhatabookTextMuted)
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 14.sp, color = KhatabookTextDark, fontWeight = FontWeight.SemiBold),
                                    cursorBrush = SolidColor(KhatabookHeaderBlue),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // AI Sheet Scan Button
                    BounceOutlinedButton(
                        onClick = { showAiSheetScannerDialog = true },
                        modifier = Modifier.height(46.dp).bounceClick { showAiSheetScannerDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookHeaderBlue),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scan Khata", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                    }
                }

                // Always visible: the collection-frequency filter is part of
                // browsing the list, not a mode to be entered, so it no longer
                // sits behind a Filters toggle. A single scrollable chip row
                // keeps it compact — the filtering logic itself is unchanged.
                run {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = frequencyFilter == null,
                                onClick = { frequencyFilter = null },
                                label = { Text("ALL", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = KhatabookHeaderBlue, selectedLabelColor = Color.White)
                            )
                        }
                        item {
                            FilterChip(
                                selected = frequencyFilter == CollectionFrequency.DAILY,
                                onClick = { frequencyFilter = CollectionFrequency.DAILY },
                                label = { Text("Daily", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFF7ED), selectedLabelColor = Color(0xFFC2410C))
                            )
                        }
                        item {
                            FilterChip(
                                selected = frequencyFilter == CollectionFrequency.WEEKLY,
                                onClick = { frequencyFilter = CollectionFrequency.WEEKLY },
                                label = { Text("Weekly", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFECFDF5), selectedLabelColor = Color(0xFF047857))
                            )
                        }
                        item {
                            FilterChip(
                                selected = frequencyFilter == CollectionFrequency.MONTHLY,
                                onClick = { frequencyFilter = CollectionFrequency.MONTHLY },
                                label = { Text("Monthly", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFF3E8FF), selectedLabelColor = Color(0xFF6B21A8))
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // CUSTOMERS / SUPPLIERS LIST - HIGH PERFORMANCE KEYED LAZY COLUMN (ISOLATED PER BUSINESS)
            // Use a stable String key instead of Pair<> to avoid per-recomposition object allocation
            val tabAndBizKey = remember(selectedTab, selectedBusinessId) { "${selectedTab.name}_$selectedBusinessId" }
            Crossfade(
                targetState = tabAndBizKey,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                animationSpec = tween(150, easing = EmphasizedSmoothEasing),
                label = "businessAndTabShiftAnimation"
            ) { tabBizKey ->
                val (tabName, currentBizId) = tabBizKey.split("_", limit = 2)
                val currentTab = PartyType.entries.first { it.name == tabName }
                val listForTab = remember(parties, currentTab, currentBizId, searchQuery, frequencyFilter) {
                    parties.filter { party ->
                        val matchesBusiness = party.businessId == currentBizId
                        val matchesTab = party.type == currentTab
                        val matchesSearch = party.name.contains(searchQuery, ignoreCase = true) || party.phone.contains(searchQuery)
                        val matchesFreq = frequencyFilter == null || party.collectionFrequency == frequencyFilter
                        matchesBusiness && matchesTab && matchesSearch && matchesFreq
                    }
                }

                if (listForTab.isEmpty() && isRestoringFromCloud) {
                    // Local database not yet populated on this device — not
                    // the same as "this business has no parties". See
                    // KhatabookRepository.restoreFromCloudIfNeeded().
                    Box(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = KhatabookHeaderBlue)
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "Restoring your data…",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookTextDark
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Fetching your parties and ledger from the cloud",
                                fontSize = 12.sp,
                                color = KhatabookTextMuted
                            )
                        }
                    }
                } else if (listForTab.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(130.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEFF6FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(64.dp))
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Text(
                                text = "No ${if (currentTab == PartyType.CUSTOMER) "customers" else "suppliers"} in ${activeBusiness.name}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookTextDark
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Add parties to manage transactions for this business account",
                                fontSize = 12.sp,
                                color = KhatabookTextMuted
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            BounceButton(
                                onClick = onAddPartyClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedTab == PartyType.CUSTOMER) KhatabookMagentaFab else KhatabookSupplierGreen
                                ),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 160.dp)
                                    .bounceClick { onAddPartyClick() }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedTab == PartyType.CUSTOMER) "ADD CUSTOMER" else "ADD SUPPLIER",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = listForTab,
                            key = { party -> party.id }
                        ) { party ->
                            PartyItemRow(
                                party = party,
                                onClick = { onPartyClick(party) },
                                onLongPress = {
                                    if (canManageParty) {
                                        partyToDelete = party
                                        showLongPressDeleteStep1 = true
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Only owners and managers can delete parties",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onRemindClick = { partyToRemind = party }
                            )
                        }
                    }
                }
            }

            // BOTTOM NAVIGATION BAR WITH BLUE TOP INDICATOR LINE (SHADOW FREE)
            Surface(
                color = Color.White,
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // PARTIES TAB (ACTIVE WITH BLUE TOP LINE)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick { }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(3.dp)
                                .background(KhatabookHeaderBlue, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = "Parties", tint = KhatabookHeaderBlue, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Parties", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                    }

                    // MORE TAB (INACTIVE)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick { onMoreTabClick() }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(3.dp)
                                .background(Color.Transparent)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = KhatabookTextMuted, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("More", fontSize = 11.sp, color = KhatabookTextMuted)
                        }
                    }
                }
            }
        }

        // Floating Action Button — only shown when list has items (empty state has its own ADD button)
        AnimatedVisibility(
            visible = hasPartiesInCurrentTab,
            enter = scaleIn(initialScale = 0.7f) + fadeIn(),
            exit = scaleOut(targetScale = 0.7f) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 108.dp, end = 16.dp)
        ) {
            BounceFloatingActionButton(
                onClick = { onAddPartyClick() },
                containerColor = if (selectedTab == PartyType.CUSTOMER) KhatabookMagentaFab else KhatabookSupplierGreen,
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.bounceClick { onAddPartyClick() }
            ) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 160.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (selectedTab == PartyType.CUSTOMER) "ADD CUSTOMER" else "ADD SUPPLIER",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }



        // Smart AI Ledger Sheet Scanner Dialog
        if (showAiSheetScannerDialog) {
            SmartAiSheetScannerDialog(
                parties = parties.filter { it.businessId == selectedBusinessId },
                onDismiss = { showAiSheetScannerDialog = false },
                onBulkLedgerUpdate = { records, date ->
                    onSheetRecordBulkSync(records, date)
                    showAiSheetScannerDialog = false
                }
            )
        }

        // LONG PRESS DELETE CUSTOMER - STEP 1 DIALOG
        if (showLongPressDeleteStep1 && partyToDelete != null) {
            val party = partyToDelete!!
            AlertDialog(
                onDismissRequest = { showLongPressDeleteStep1 = false },
                title = {
                    Text(
                        text = "Delete ${party.name}?",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookGiveRed
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete '${party.name}' and all their transaction history? Deleting customer records is crucial and requires double confirmation.",
                        fontSize = 13.sp,
                        color = KhatabookTextDark,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    BounceButton(
                        onClick = {
                            showLongPressDeleteStep1 = false
                            longPressConfirmNameText = ""
                            showLongPressDeleteStep2 = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGiveRed),
                        modifier = Modifier.bounceClick {
                            showLongPressDeleteStep1 = false
                            longPressConfirmNameText = ""
                            showLongPressDeleteStep2 = true
                        }
                    ) {
                        Text("PROCEED TO DELETE", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    BounceTextButton(onClick = { showLongPressDeleteStep1 = false }) {
                        Text("Cancel", color = KhatabookTextMuted)
                    }
                }
            )
        }

        // LONG PRESS DELETE CUSTOMER - STEP 2 FINAL TYPE-TO-CONFIRM SECURITY DIALOG
        if (showLongPressDeleteStep2 && partyToDelete != null) {
            val party = partyToDelete!!
            val isNameMatched = longPressConfirmNameText.trim().equals(party.name.trim(), ignoreCase = true)

            AlertDialog(
                onDismissRequest = { showLongPressDeleteStep2 = false },
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
                            value = longPressConfirmNameText,
                            onValueChange = { longPressConfirmNameText = it },
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
                                showLongPressDeleteStep2 = false
                                onDeleteParty(party.id)
                                partyToDelete = null
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
                                showLongPressDeleteStep2 = false
                                onDeleteParty(party.id)
                                partyToDelete = null
                            }
                        }
                    ) {
                        Text("PERMANENTLY DELETE", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    BounceTextButton(onClick = { showLongPressDeleteStep2 = false }) {
                        Text("Cancel", color = KhatabookTextMuted)
                    }
                }
            )
        }

        partyToRemind?.let { party ->
            com.example.khatabook.ui.components.RemindBottomSheet(
                party = party,
                onDismiss = { partyToRemind = null },
                onSmsClick = {
                    run {
                        val message = "Dear ${party.name}, your total outstanding balance is ₹ ${kotlin.math.abs(party.currentBalance).toInt()}. Please clear your dues on Open Khata."
                        val toast = when (val result = SmsSender.send(context, party.phone, message, party.businessId)) {
                            is SmsSender.Result.Sent -> "Reminder sent to ${party.name}"
                            is SmsSender.Result.PermissionMissing -> "Allow SMS permission in Settings to send reminders"
                            is SmsSender.Result.Failed -> "Could not send: ${result.reason}"
                        }
                        android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_LONG).show()
                    }
                    partyToRemind = null
                },
                onWhatsappClick = {
                    try {
                        val message = "Dear ${party.name}, your total outstanding balance is ₹ ${kotlin.math.abs(party.currentBalance).toInt()}. Please clear your dues on Open Khata."
                        val encodedMessage = Uri.encode(message)
                        val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${party.phone}&text=$encodedMessage"))
                        context.startActivity(whatsappIntent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "WhatsApp not found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    partyToRemind = null
                },
                onSendBulkReminders = {
                    // Same real, local send used for a single party's
                    // reminder above — applied to every customer in this
                    // business who currently owes money.
                    val owing = activeBusinessParties.filter {
                        it.type == PartyType.CUSTOMER && it.currentBalance > 0
                    }
                    if (owing.isEmpty()) {
                        android.widget.Toast.makeText(context, "No customers currently owe you money", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        var sent = 0
                        owing.forEach { p ->
                            val message = "Dear ${p.name}, your total outstanding balance is ₹ ${kotlin.math.abs(p.currentBalance).toInt()}. Please clear your dues on Open Khata."
                            if (SmsSender.send(context, p.phone, message, p.businessId) is SmsSender.Result.Sent) sent++
                        }
                        android.widget.Toast.makeText(context, "Reminders sent to $sent of ${owing.size} customers", android.widget.Toast.LENGTH_LONG).show()
                    }
                    partyToRemind = null
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartyItemRow(
    party: Party,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemindClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF6FF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (party.name.isNotEmpty()) party.name.take(1).uppercase() else "P",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookHeaderBlue
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = party.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    when (party.collectionFrequency) {
                        CollectionFrequency.DAILY -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFFF7ED))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text("Daily", fontSize = 9.sp, color = Color(0xFFC2410C), fontWeight = FontWeight.Bold)
                            }
                        }
                        CollectionFrequency.WEEKLY -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFECFDF5))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text("Weekly", fontSize = 9.sp, color = Color(0xFF047857), fontWeight = FontWeight.Bold)
                            }
                        }
                        CollectionFrequency.MONTHLY -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFF3E8FF))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text("Monthly", fontSize = 9.sp, color = Color(0xFF6B21A8), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = formatRelativeTime(party.lastTransactionTime),
                        fontSize = 11.sp,
                        color = KhatabookTextMuted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val isGet = party.currentBalance > 0
                Text(
                    text = "₹ ${kotlin.math.abs(party.currentBalance).toInt()}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (party.currentBalance == 0.0) KhatabookTextDark else if (isGet) KhatabookGiveRed else KhatabookGotGreen
                )
                if (isGet) {
                    Text(
                        text = "REMIND",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookHeaderBlue,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .bounceClick { onRemindClick() }
                    )
                }
            }
        }
    }
}

fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 1 -> "Just now"
        hours < 1 -> "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
        days < 1 -> "$hours ${if (hours == 1L) "hour" else "hours"} ago"
        else -> "$days ${if (days == 1L) "day" else "days"} ago"
    }
}
