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
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessStatisticsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPeriod by remember { mutableStateOf("This Month") } // "This Month", "This Year", "All Time"
    val scrollState = rememberScrollState()

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
                        actions = {
                            BounceIconButton(onClick = {}) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                            }
                        },
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
                // Dynamic stats values computed based on the animated target state
                val (cashCollected, creditGiven, netFlow, totalTxns, topCustomer) = when (targetPeriod) {
                    "This Month" -> Quintuple(14500.0, 3800.0, 10700.0, 24, "Ramesh Kumar")
                    "This Year" -> Quintuple(165000.0, 42000.0, 123000.0, 186, "Vikram Singh")
                    else -> Quintuple(380000.0, 95000.0, 285000.0, 412, "Ramesh Kumar")
                }

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
private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
