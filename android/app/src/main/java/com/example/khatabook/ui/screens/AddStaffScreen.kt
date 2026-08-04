package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
fun AddStaffScreen(
    onBackClick: () -> Unit,
    onAddStaffClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Staff", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    BounceIconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
            )
        },
        floatingActionButton = {
            BounceFloatingActionButton(
                onClick = onAddStaffClick,
                containerColor = KhatabookMagentaFab,
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ADD STAFF", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        ) {
            // Hero Visual Graphic Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color(0xFFE2E8F0).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Node: Add Users
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("👨‍💼", fontSize = 36.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Add Users", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        }

                        // Right Node 1: Give Permission
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🔑", fontSize = 28.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Give Permission", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Node 2: Manage Salary
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("💵", fontSize = 28.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Manage Salary", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        }

                        // Node 3: Mark Attendance
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📅", fontSize = 28.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Mark Attendance", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        }
                    }
                }
            }

            // How It Works Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HOW IT WORKS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookTextDark
                    )
                    Text(
                        text = "Add Staff, Business Partners or Family members",
                        fontSize = 12.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                    )

                    // Feature Item 1
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE0F2FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📝", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Give access to Partners & Staff on their phone number",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = KhatabookTextDark
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Feature Item 2
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFEF3C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔔", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Manage Salary, Attendance & Payment for staff",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = KhatabookTextDark
                        )
                    }
                }
            }
        }
    }
}
