package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalContext
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.utils.PdfGenerator
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleCustomerReportScreen(
    party: Party,
    entries: List<LedgerEntry>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("View Report", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    BounceIconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
            )
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
                            val partyEntries = entries.filter { it.partyId == party.id }
                            PdfGenerator.generateAndShareLedgerReport(context, party, partyEntries)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📄", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DOWNLOAD", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                .background(KhatabookBgLight)
        ) {
            // Blue Header Filters Container
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    // Date Filter Buttons: START DATE / END DATE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BounceButton(
                            onClick = {},
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DateRange, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("START DATE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            }
                        }

                        BounceButton(
                            onClick = {},
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DateRange, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("END DATE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Search Entries + ALL Dropdown Box
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search Entries", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = KhatabookTextMuted) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )

                        Surface(
                            color = Color(0xFFEFF6FF),
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ALL  ▼", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                            }
                        }
                    }
                }
            }

            // Net Balance Header Banner (Page 8)
            Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Net Balance", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        Text("₹ 7,000", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = KhatabookBorderLight)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("TOTAL", fontSize = 10.sp, color = KhatabookTextMuted)
                            Text("1 Entries", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("YOU GAVE", fontSize = 10.sp, color = KhatabookTextMuted)
                            Text("₹ 7,000", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("YOU GOT", fontSize = 10.sp, color = KhatabookTextMuted)
                            Text("₹ 0", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookGotGreen)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Entry List Row
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Amma", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text("24 Jul 26 • 02:31 PM", fontSize = 11.sp, color = KhatabookTextMuted)
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(KhatabookGiveRedBg)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("₹ 7,000", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                            }
                        }
                    }
                }
            }
        }
    }
}
