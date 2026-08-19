package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.utils.SmsSender
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetCollectionDateScreen(
    parties: List<Party> = emptyList(),
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("Highest Amount") }
    var searchQuery by remember { mutableStateOf("") }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    var selectedPartyForDate by remember { mutableStateOf<Party?>(null) }
    var selectedPickerDay by remember { mutableStateOf(15) }
    val partyDatesMap = remember { mutableStateMapOf<String, String>() }

    val customerList = remember(parties, searchQuery) {
        parties.filter { it.type == PartyType.CUSTOMER && (searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true)) }
    }

    val totalDues = remember(customerList) { customerList.sumOf { maxOf(0.0, it.currentBalance) } }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Set Collection Date", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        // "SELECT ALL" removed: this screen has no multi-select
                        // (no per-row checkbox/selection state exists here), so
                        // the action had nothing to select and did nothing when
                        // tapped.
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
        ) {
            // Blue Header Banner
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("₹ ${totalDues.toInt()}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Collect from ${customerList.size} Customers faster", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Highest Amount", "Oldest Due", "Pick Date").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedFilter = filter
                            if (filter == "Pick Date" && customerList.isNotEmpty()) {
                                selectedPartyForDate = customerList.first()
                                showDatePickerDialog = true
                            }
                        },
                        label = { Text(filter, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = KhatabookHeaderBlue,
                            selectedLabelColor = Color.White,
                            containerColor = Color.White,
                            labelColor = KhatabookTextDark
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Box
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search from ${customerList.size} Customers", fontSize = 12.sp, color = KhatabookTextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = KhatabookTextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = KhatabookHeaderBlue,
                        unfocusedBorderColor = KhatabookBorderLight
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real Customers Collection List
            if (customerList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No customers found", fontSize = 14.sp, color = KhatabookTextMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(customerList, key = { it.id }) { party ->
                        val dateText = partyDatesMap[party.id] ?: "Set Date >"
                        val hasDateSet = partyDatesMap.containsKey(party.id)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(KhatabookHeaderBlue),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (party.name.isNotEmpty()) party.name.take(1).uppercase() else "C",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(
                                            text = party.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = KhatabookTextDark
                                        )
                                        Text(
                                            text = party.phone,
                                            fontSize = 11.sp,
                                            color = KhatabookTextMuted
                                        )

                                        if (hasDateSet) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFFEFF6FF))
                                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                            ) {
                                                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(11.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Collection: $dateText", fontSize = 10.sp, color = KhatabookHeaderBlue, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "₹ ${party.currentBalance.toInt()}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (party.currentBalance > 0) KhatabookGiveRed else KhatabookGotGreen
                                    )

                                    Text(
                                        text = if (hasDateSet) "REMIND AGAIN >" else "Set Date >",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = KhatabookHeaderBlue,
                                        modifier = Modifier
                                            .bounceClick {
                                                selectedPartyForDate = party
                                                if (hasDateSet) {
                                                    showReminderDialog = true
                                                } else {
                                                    showDatePickerDialog = true
                                                }
                                            }
                                            .clickable {
                                                selectedPartyForDate = party
                                                if (hasDateSet) {
                                                    showReminderDialog = true
                                                } else {
                                                    showDatePickerDialog = true
                                                }
                                            }
                                            .padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // FULLY INTERACTIVE CALENDAR DATEPICKER DIALOG (1 to 31 Days Selection Grid)
        if (showDatePickerDialog && selectedPartyForDate != null) {
            val targetParty = selectedPartyForDate!!
            Dialog(onDismissRequest = { showDatePickerDialog = false }) {
                AnimatedVisibility(
                    visible = showDatePickerDialog,
                    enter = slideInVertically(
                        initialOffsetY = { 50 },
                        animationSpec = tween(200)
                    ) + fadeIn(tween(200)),
                    exit = fadeOut(tween(200))
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    ) {
                        Column {
                            // Dialog Header showing selected interactive date
                            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Pick Collection Date for ${targetParty.name}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("2026", fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
                                    Text("$selectedPickerDay Aug 2026", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }

                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Month navigation is not implemented — only a single fixed
                                    // month is ever rendered below — so these are intentionally
                                    // inert rather than wired to a `.clickable {}` that silently
                                    // did nothing when tapped.
                                    Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = KhatabookHeaderBlue.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
                                    Text(
                                        text = "August 2026",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = KhatabookTextDark
                                    )
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KhatabookHeaderBlue.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                                        Text(day, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // 7-Column Interactive Calendar Grid for 31 days
                                val daysList = remember {
                                    // August 2026 starts on Saturday (5 empty leading slots) + 31 days
                                    List(5) { 0 } + (1..31).toList()
                                }

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(7),
                                    modifier = Modifier.height(220.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(daysList) { day ->
                                        if (day == 0) {
                                            Box(modifier = Modifier.size(32.dp))
                                        } else {
                                            val isSelected = day == selectedPickerDay
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSelected) KhatabookHeaderBlue else Color.Transparent)
                                                    .bounceClick { selectedPickerDay = day }
                                                    .clickable { selectedPickerDay = day },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = day.toString(),
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) Color.White else KhatabookTextDark
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Action Buttons: CANCEL & OK
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    BounceTextButton(onClick = { showDatePickerDialog = false }) {
                                        Text("CANCEL", color = KhatabookTextMuted, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    BounceButton(
                                        onClick = {
                                            partyDatesMap[targetParty.id] = "$selectedPickerDay Aug 2026"
                                            showDatePickerDialog = false
                                            showReminderDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.bounceClick {
                                            partyDatesMap[targetParty.id] = "$selectedPickerDay Aug 2026"
                                            showDatePickerDialog = false
                                            showReminderDialog = true
                                        }
                                    ) {
                                        Text("SET DATE & REMIND", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Automatic Send Payment Reminder Dialog
        if (showReminderDialog && selectedPartyForDate != null) {
            val party = selectedPartyForDate!!
            val dateStr = partyDatesMap[party.id] ?: "$selectedPickerDay Aug 2026"
            val messageText = "Dear ${party.name}, your pending balance of ₹ ${party.currentBalance.toInt()} is scheduled for payment collection on $dateStr. Please make payment on time."

            Dialog(onDismissRequest = { showReminderDialog = false }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📩 Send Payment Reminder", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            BounceIconButton(onClick = { showReminderDialog = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Customer Details Card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF8FAFC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(KhatabookHeaderBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(party.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(party.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                    Text("Scheduled: $dateStr", fontSize = 11.sp, color = KhatabookHeaderBlue, fontWeight = FontWeight.SemiBold)
                                }
                                Text("₹ ${party.currentBalance.toInt()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text("Automated Reminder Preview:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        Spacer(modifier = Modifier.height(6.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEFF6FF),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBFDBFE))
                        ) {
                            Text(
                                text = messageText,
                                fontSize = 12.sp,
                                color = KhatabookTextDark,
                                modifier = Modifier.padding(12.dp),
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Send Action Buttons
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BounceButton(
                                onClick = {
                                    val encodedMessage = Uri.encode(messageText)
                                    val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${party.phone}&text=$encodedMessage"))
                                    context.startActivity(whatsappIntent)
                                    showReminderDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.weight(1f).height(46.dp).bounceClick {
                                    val encodedMessage = Uri.encode(messageText)
                                    val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${party.phone}&text=$encodedMessage"))
                                    context.startActivity(whatsappIntent)
                                    showReminderDialog = false
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                            }

                            BounceButton(
                                onClick = {
                                    // Was previously duplicated verbatim into an
                                    // external .bounceClick{} on this button's own
                                    // modifier too — BounceButton already registers
                                    // onClick internally (see KhatabookButtons.kt), so
                                    // that second copy was a redundant handler for the
                                    // same tap, not a distinct control.
                                    val toast = when (val r = SmsSender.send(context, party.phone, messageText, party.businessId)) {
                                        is SmsSender.Result.Sent -> "Reminder sent to ${party.name}"
                                        is SmsSender.Result.PermissionMissing -> "Allow SMS permission in Settings"
                                        is SmsSender.Result.Failed -> "Could not send: ${r.reason}"
                                    }
                                    android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_LONG).show()
                                    showReminderDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Icon(Icons.Default.Message, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SMS Reminder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
