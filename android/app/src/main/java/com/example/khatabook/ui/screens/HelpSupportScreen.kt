package com.example.khatabook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

data class FaqItem(val question: String, val answer: String)
data class FaqCategory(val name: String, val faqs: List<FaqItem>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportScreen(
    onBackClick: () -> Unit
) {
    val faqCategories = listOf(
        FaqCategory(
            name = "Getting Started",
            faqs = listOf(
                FaqItem("How to add a new customer?", "Click on the '+' button on the main Parties screen, enter the customer name and phone number, and click Save."),
                FaqItem("Can I use this app on multiple devices?", "Yes, you can log in with your phone number on any device and your data will sync automatically.")
            )
        ),
        FaqCategory(
            name = "Security & Backup",
            faqs = listOf(
                FaqItem("Is my data safe?", "Yes, your data is 100% safe and backed up automatically to the cloud.")
            )
        ),
        FaqCategory(
            name = "Reports & Export",
            faqs = listOf(
                FaqItem("How to generate a PDF report?", "Go to the customer ledger and click on the 'Report' button on the top right to download a PDF.")
            )
        )
    )

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

                faqCategories.forEach { category ->
                    item {
                        FaqCategorySection(category)
                    }
                }
            }
        }
    }
}

@Composable
fun ContactCard(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        // Previously an empty lambda: tapping "Call Us"/"Email Us" — despite
        // showing a real phone number/email as the subtitle — did nothing.
        // Launches the dialer or an email composer against the exact contact
        // info already shown, rather than inventing anything new.
        modifier = modifier.bounceClick {
            val intent = if (subtitle.contains("@")) {
                android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.fromParts("mailto", subtitle, null))
            } else {
                android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", subtitle, null))
            }
            try {
                context.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "No app found to handle this", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
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
fun FaqCategorySection(category: FaqCategory) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = category.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = KhatabookTextDark
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = KhatabookTextMuted
                )
            }
            
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(300))
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    category.faqs.forEachIndexed { index, faq ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "Q: ${faq.question}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = KhatabookTextDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "A: ${faq.answer}",
                                fontSize = 13.sp,
                                color = KhatabookTextMuted,
                                lineHeight = 18.sp
                            )
                        }
                        if (index < category.faqs.size - 1) {
                            HorizontalDivider(color = KhatabookBorderLight, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
