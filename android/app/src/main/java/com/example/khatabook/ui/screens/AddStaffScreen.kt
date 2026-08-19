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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.api.StaffMemberResponse
import kotlinx.coroutines.launch
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStaffScreen(
    onBackClick: () -> Unit,
    onAddStaffClick: () -> Unit,
    /** Bumped by the caller after a member is added, to force a refetch. */
    refreshKey: Int = 0,
    repository: com.example.khatabook.data.repository.KhatabookRepository,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Repository-cached: renders whatever was cached instantly, refreshes in
    // the background. This screen previously called NetworkClient directly on
    // every visit, with a blocking spinner and nothing shown offline.
    val staff by repository.staff.collectAsState()
    var isLoading by remember { mutableStateOf(staff.isEmpty()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<StaffMemberResponse?>(null) }

    suspend fun reload(force: Boolean = false) {
        isLoading = staff.isEmpty()
        loadError = null
        val ok = repository.syncStaff(force = force)
        if (!ok && staff.isEmpty()) {
            // Only surface an error when there is nothing cached to fall back
            // on — a failed background refresh over an existing cache is not
            // something the user needs to be told about.
            loadError = "Could not reach the server. Check your connection."
        }
        isLoading = false
    }

    // refreshKey is bumped by the caller only after a member is actually
    // added or removed — that bump, not the initial composition, is what
    // needs to bypass the cache.
    LaunchedEffect(refreshKey) { reload(force = refreshKey > 0) }

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
        pendingRemoval?.let { member ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text("Remove staff member?") },
                text = {
                    Text(
                        (member.name ?: member.phoneNumber) +
                            " will lose access to this business. Their account, and any " +
                            "other business they work in, are unaffected.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val target = member
                        pendingRemoval = null
                        scope.launch {
                            actionMessage = try {
                                val res = NetworkClient.apiService.removeStaff(target.membershipId)
                                when {
                                    res.isSuccessful -> {
                                        reload(force = true)
                                        "Removed " + (target.name ?: target.phoneNumber)
                                    }
                                    res.code() == 400 -> "An owner cannot be removed."
                                    res.code() == 403 -> "You cannot remove yourself."
                                    res.code() == 404 -> "That person is no longer a member."
                                    else -> "Could not remove (HTTP " + res.code() + ")."
                                }
                            } catch (e: Exception) {
                                "Could not reach the server. Check your connection."
                            }
                        }
                    }) { Text("REMOVE", color = KhatabookGiveRed) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) { Text("CANCEL") }
                },
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(KhatabookBgLight)
                .verticalScroll(scrollState)
        ) {
            // Current staff, fetched from the server.
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "Current Staff",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark,
                )
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isLoading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = KhatabookHeaderBlue,
                        )
                    }

                    loadError != null -> Text(
                        loadError!!,
                        fontSize = 13.sp,
                        color = KhatabookGiveRed,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )

                    staff.isEmpty() -> Text(
                        "No staff yet. Add someone to help run this business.",
                        fontSize = 13.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )

                    else -> staff.forEach { member ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        member.name ?: member.phoneNumber,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = KhatabookTextDark,
                                    )
                                    Text(
                                        member.role + " - " + member.phoneNumber,
                                        fontSize = 12.sp,
                                        color = KhatabookTextMuted,
                                    )
                                }
                                // Owners cannot be removed; the server refuses it
                                // too, so the action is simply not offered.
                                if (member.role != "OWNER") {
                                    TextButton(onClick = { pendingRemoval = member }) {
                                        Text("REMOVE", color = KhatabookGiveRed, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                actionMessage?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

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
