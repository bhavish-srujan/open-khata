package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportScreen(
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Help & Support", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                .background(KhatabookBgLight)
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text("Contact Us", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ContactCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Call,
                            title = "Call Us",
                            subtitle = "+91 80000 12345"
                        )
                        ContactCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Email,
                            title = "Email Us",
                            subtitle = "support@khata.book"
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Frequently Asked Questions", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                items(4) { index ->
                    val faqs = listOf(
                        "How to add a new customer?" to "Click on the '+' button on the main Parties screen, enter the customer name and phone number, and click Save.",
                        "Is my data safe?" to "Yes, your data is 100% safe and backed up automatically to the cloud.",
                        "How to generate a PDF report?" to "Go to the customer ledger and click on the 'Report' button on the top right to download a PDF.",
                        "Can I use this app on multiple devices?" to "Yes, you can log in with your phone number on any device and your data will sync automatically."
                    )
                    FaqCard(question = faqs[index].first, answer = faqs[index].second)
                }
            }
        }
    }
}

@Composable
fun ContactCard(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Card(
        modifier = modifier.bounceClick { },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(KhatabookHeaderBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, fontSize = 12.sp, color = KhatabookTextMuted)
        }
    }
}

@Composable
fun FaqCard(question: String, answer: String) {
    Card(
        modifier = Modifier.fillMaxWidth().bounceClick { },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Help, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(question, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(answer, fontSize = 13.sp, color = KhatabookTextMuted, modifier = Modifier.padding(start = 32.dp))
        }
    }
}
