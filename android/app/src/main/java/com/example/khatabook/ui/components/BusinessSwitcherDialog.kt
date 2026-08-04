package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import com.example.khatabook.ui.theme.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.khatabook.data.model.BusinessAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessSwitcherDialog(
    businesses: List<BusinessAccount>,
    activeBusinessId: String,
    onBusinessSelect: (BusinessAccount) -> Unit,
    onCreateNewBusiness: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateBusinessModal by remember { mutableStateOf(false) }
    var newBusinessName by remember { mutableStateOf("") }

    if (showCreateBusinessModal) {
        AlertDialog(
            onDismissRequest = { showCreateBusinessModal = false },
            title = {
                Text("Add New Business", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = KhatabookHeaderBlue)
            },
            text = {
                Column {
                    Text("Create a new isolated business to manage separate parties & transactions.", fontSize = 12.sp, color = KhatabookTextMuted)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = newBusinessName,
                        onValueChange = { newBusinessName = it },
                        label = { Text("Shop / Business Name") },
                        placeholder = { Text("e.g. Shop 2 (Hardware Store)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        if (newBusinessName.isNotBlank()) {
                            onCreateNewBusiness(newBusinessName.trim())
                            showCreateBusinessModal = false
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue)
                ) {
                    Text("CREATE & SWITCH", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { showCreateBusinessModal = false }) {
                    Text("Cancel", color = KhatabookTextMuted)
                }
            }
        )
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Switch Business", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Business List
                    businesses.forEach { biz ->
                        val isSelected = biz.id == activeBusinessId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFFEFF6FF) else Color.Transparent)
                                .clickable {
                                    onBusinessSelect(biz)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) KhatabookHeaderBlue else Color(0xFF94A3B8)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = biz.name.take(1).uppercase(),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(biz.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text(biz.type, fontSize = 11.sp, color = KhatabookTextMuted)
                            }

                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = "Active", tint = KhatabookHeaderBlue)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Add New Business Option (max 2)
                    if (businesses.size < 2) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = KhatabookBorderLight)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showCreateBusinessModal = true
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEFF6FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = KhatabookHeaderBlue)
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text("Add Another Business", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                    }
                }
            }
        }
    }
}
