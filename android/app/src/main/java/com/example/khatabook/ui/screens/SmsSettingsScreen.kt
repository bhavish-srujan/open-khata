package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

enum class SmsSenderMode {
    MY_NUMBER,
    KHATABOOK_SMS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var senderMode by remember { mutableStateOf(SmsSenderMode.MY_NUMBER) }
    var selectedSim by remember { mutableStateOf("Default") }
    var showSimDropdown by remember { mutableStateOf(false) }
    val simOptions = listOf("Default", "SIM 1 (Jio)", "SIM 2 (Airtel)")
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("SMS Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
                Box(modifier = Modifier.padding(14.dp)) {
                    BounceButton(
                        onClick = onBackClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (senderMode == SmsSenderMode.MY_NUMBER) KhatabookHeaderBlue else Color(0xFF991B1B)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp).bounceClick { onBackClick() }
                    ) {
                        Text("SAVE", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send SMS from:", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.Default.Info, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(18.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // MY NUMBER TAB
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick { senderMode = SmsSenderMode.MY_NUMBER }
                            .clickable { senderMode = SmsSenderMode.MY_NUMBER },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (senderMode == SmsSenderMode.MY_NUMBER) KhatabookHeaderBlue else Color.Transparent
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (senderMode == SmsSenderMode.MY_NUMBER) 2.dp else 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = if (senderMode == SmsSenderMode.MY_NUMBER) Color.White else KhatabookTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "My Number",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (senderMode == SmsSenderMode.MY_NUMBER) Color.White else KhatabookTextMuted
                            )
                        }
                    }

                    // KHATABOOK SMS TAB
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick { senderMode = SmsSenderMode.KHATABOOK_SMS }
                            .clickable { senderMode = SmsSenderMode.KHATABOOK_SMS },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (senderMode == SmsSenderMode.KHATABOOK_SMS) Color(0xFF991B1B) else Color.Transparent
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (senderMode == SmsSenderMode.KHATABOOK_SMS) 2.dp else 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.MarkEmailRead,
                                contentDescription = null,
                                tint = if (senderMode == SmsSenderMode.KHATABOOK_SMS) Color.White else KhatabookTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Khatabook SMS",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (senderMode == SmsSenderMode.KHATABOOK_SMS) Color.White else KhatabookTextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (senderMode == SmsSenderMode.MY_NUMBER) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
                ) {
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSimDropdown = true }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select SIM card for SMS", fontSize = 13.sp, color = KhatabookTextDark, fontWeight = FontWeight.SemiBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selectedSim, fontSize = 13.sp, color = KhatabookHeaderBlue, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = KhatabookHeaderBlue)
                            }
                        }

                        DropdownMenu(expanded = showSimDropdown, onDismissRequest = { showSimDropdown = false }) {
                            simOptions.forEach { sim ->
                                DropdownMenuItem(
                                    text = { Text(sim, fontSize = 13.sp) },
                                    onClick = {
                                        selectedSim = sim
                                        showSimDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Benefits:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BenefitRow("SMS automatically sent with each entry")
                    BenefitRow("Customer receives SMS from your trusted number")
                    BenefitRow("Complete details with SMS - entry & balance")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Message, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Free SMS Limit", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text("You can use Khatabook Coins to send SMS after monthly limit is crossed", fontSize = 11.sp, color = KhatabookTextMuted)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                .border(1.dp, KhatabookBorderLight, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("0", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text("SMS sent this month", fontSize = 10.sp, color = KhatabookTextMuted)
                            }
                            Box(modifier = Modifier.width(1.dp).height(28.dp).background(KhatabookBorderLight))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("100", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text("Monthly SMS limit", fontSize = 10.sp, color = KhatabookTextMuted)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Send SMS via Khatabook", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BulletPointRow("SMS received from a Khatabook number", Color(0xFFB91C1C))
                    BulletPointRow("Limited details on the basic SMS", Color(0xFFB91C1C))
                    BulletPointRow("Can be marked as Spam or Promotional", Color(0xFFB91C1C))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (senderMode == SmsSenderMode.MY_NUMBER) Color(0xFFEFF6FF) else Color(0xFFFEF2F2)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (senderMode == SmsSenderMode.MY_NUMBER) KhatabookHeaderBlue else Color(0xFF991B1B))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Sample", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (senderMode == SmsSenderMode.MY_NUMBER) "← My Business" else "← JM-KHTABK",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookTextDark
                    )

                    Text("Sunday, 2 Aug, 06:21 PM", fontSize = 11.sp, color = KhatabookTextMuted, modifier = Modifier.padding(top = 2.dp))

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (senderMode == SmsSenderMode.MY_NUMBER) {
                                Text(
                                    "Hi, Entry added for Rs -200 on 03 Apr. Total dues : Rs +200.\nCheck history: https://khatabook.com/t/XXX\nThank you\nMy Business",
                                    fontSize = 12.sp,
                                    color = KhatabookTextDark,
                                    lineHeight = 18.sp
                                )
                            } else {
                                Text(
                                    "You gave: ₹ 400\nBalance: +(₹ 200)\nSent by: 6366605032\n\nDetails: https://khatabook.com/t/XXX",
                                    fontSize = 12.sp,
                                    color = KhatabookTextDark,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (senderMode == SmsSenderMode.MY_NUMBER) "Max SMS Limit: As per your mobile plan" else "Max SMS Limit: 100 per month",
                            fontSize = 11.sp,
                            color = KhatabookTextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = KhatabookTextDark)
    }
}

@Composable
private fun BulletPointRow(text: String, bulletColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(bulletColor))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = KhatabookTextDark)
    }
}
