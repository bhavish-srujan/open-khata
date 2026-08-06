package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.model.Party
import com.example.khatabook.ui.components.BounceButton
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.BounceOutlinedButton
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerProfileScreen(
    party: Party,
    onBackClick: () -> Unit,
    onDeleteParty: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Customer") },
            text = { Text("Are you sure you want to delete ${party.name}? All ledger entries will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteParty(party.id)
                }) {
                    Text("Delete", color = KhatabookGiveRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Customer Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
                Box(modifier = Modifier.padding(16.dp)) {
                    BounceOutlinedButton(
                        onClick = { showDeleteDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KhatabookGiveRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookGiveRed),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("DELETE CUSTOMER", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                .verticalScroll(rememberScrollState())
        ) {
            // Profile Header area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F6))
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(80.dp)) {
                        // Main avatar
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(1.dp, Color.LightGray, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Person, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(60.dp))
                        }
                        
                        // Camera badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(KhatabookHeaderBlue)
                                .border(2.dp, Color(0xFFF3F4F6), CircleShape)
                                .bounceClick {},
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "Add Photo", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add photo", fontSize = 12.sp, color = KhatabookHeaderBlue, modifier = Modifier.bounceClick {})
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    BounceOutlinedButton(
                        onClick = {},
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = KhatabookHeaderBlue),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookHeaderBlue),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.ContactMail, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SHARE YOUR BUSINESS CARD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Customer Details List
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ProfileListItem(
                    icon = Icons.Outlined.Person,
                    title = "Name",
                    subtitle = party.name,
                    onClick = {}
                )
                ProfileListItem(
                    icon = Icons.Outlined.Phone,
                    title = "Mobile Number",
                    subtitle = party.phone,
                    onClick = {}
                )
                ProfileListItem(
                    icon = Icons.Default.LocationOn,
                    title = "Add Address",
                    onClick = {}
                )
                ProfileListItem(
                    icon = Icons.Outlined.Assignment,
                    title = "GSTIN",
                    onClick = {}
                )
                ProfileListItem(
                    icon = Icons.Default.SyncAlt,
                    title = "Change to Supplier",
                    onClick = {}
                )
            }

            // Customer Settings Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F6))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("Customer Settings", fontSize = 12.sp, color = KhatabookTextMuted)
            }

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ProfileListItem(
                    icon = Icons.Default.Message,
                    title = "Customer SMS Settings",
                    subtitle = "SMS will be sent on each entry",
                    onClick = {}
                )
                ProfileListItem(
                    icon = Icons.Default.Translate,
                    title = "SMS Language",
                    subtitle = "English",
                    onClick = {}
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ProfileListItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, 
                fontSize = if (subtitle != null) 12.sp else 16.sp, 
                color = if (subtitle != null) KhatabookTextMuted else KhatabookTextDark
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = 16.sp, color = KhatabookTextDark)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(20.dp))
    }
}
