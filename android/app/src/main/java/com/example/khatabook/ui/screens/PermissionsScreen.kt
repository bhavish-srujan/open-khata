package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

/** Builds the full list of runtime permissions based on the device's Android version. */
fun buildRuntimePermissions(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {          // Android 13+
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {                 // Android 12+
        perms.add(Manifest.permission.BLUETOOTH_SCAN)
        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    return perms.toTypedArray()
}

private data class PermissionGroup(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val accentColor: Color
)

private val permissionGroups = listOf(
    PermissionGroup(
        icon = Icons.Default.Group,
        title = "Contacts & Calls",
        description = "Access contacts to add customers instantly and make direct calls from the app.",
        accentColor = KhatabookHeaderBlue
    ),
    PermissionGroup(
        icon = Icons.Default.LocationOn,
        title = "Location",
        description = "Detect your business location to auto-fill addresses and enable nearby features.",
        accentColor = KhatabookGreen
    ),
    PermissionGroup(
        icon = Icons.Default.Sms,
        title = "SMS & Messages",
        description = "Send payment reminders and collection alerts directly to customers via SMS.",
        accentColor = KhatabookHeaderBlue
    ),
    PermissionGroup(
        icon = Icons.Default.Notifications,
        title = "Notifications",
        description = "Receive timely alerts for due payments, new entries and important business updates.",
        accentColor = KhatabookRed
    ),
    PermissionGroup(
        icon = Icons.Default.CameraAlt,
        title = "Camera & Microphone",
        description = "Scan ledger sheets with AI, capture payment proofs, and use voice entry.",
        accentColor = KhatabookHeaderBlue
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onPermissionsHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Proceed regardless of individual grant results — user can manage later in Settings
        onPermissionsHandled()
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("App Permissions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .navigationBarsPadding()
                ) {
                    BounceButton(
                        onClick = { permissionsLauncher.launch(buildRuntimePermissions()) },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "ALLOW PERMISSIONS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    BounceTextButton(
                        onClick = onPermissionsHandled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Skip for now",
                            fontSize = 14.sp,
                            color = KhatabookTextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(KhatabookBgLight)
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Top lock icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(KhatabookHeaderBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = KhatabookHeaderBlue,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Secure & Private",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Khatabook needs a few permissions to unlock\nthe full experience for your business.",
                fontSize = 14.sp,
                color = KhatabookTextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Permission group cards
            permissionGroups.forEach { group ->
                PermissionGroupCard(group = group)
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionGroupCard(group: PermissionGroup) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon bubble
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(group.accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = group.icon,
                    contentDescription = null,
                    tint = group.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark
                )
                Text(
                    text = group.description,
                    fontSize = 12.sp,
                    color = KhatabookTextMuted,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
