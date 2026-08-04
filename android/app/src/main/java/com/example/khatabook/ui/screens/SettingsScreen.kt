package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.PaymentSettingsDialog
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onSmsSettingsClick: () -> Unit = {},
    onPaymentSettingsClick: () -> Unit = {},
    onRecycleBinClick: () -> Unit = {},
    onBackupInfoClick: () -> Unit = {},
    onDeleteKhataClick: () -> Unit = {},
    onChangeLanguageClick: () -> Unit = {},
    onLogoutClick: () -> Unit = {},
    isAppLockEnabled: Boolean,
    onToggleAppLock: (Boolean, String?) -> Unit,
    isProfileLockEnabled: Boolean = false,
    profileLockPin: String = "",
    onToggleProfileLock: (Boolean, String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }

    // Profile Lock PIN dialog states
    var showProfilePinDialog by remember { mutableStateOf(false) }
    var showChangeProfilePinDialog by remember { mutableStateOf(false) }

    var showPaymentSettingsModal by remember { mutableStateOf(false) }

    var pinText by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    var profilePinText by remember { mutableStateOf("") }
    var profilePinError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = KhatabookHeaderBlue)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Settings", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue, modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "SMS Settings",
                        onClick = onSmsSettingsClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "Payment Settings",
                        onClick = {
                            showPaymentSettingsModal = true
                            onPaymentSettingsClick()
                        }
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "Recycle Bin",
                        onClick = onRecycleBinClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    // App Lock Toggle Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("App Lock", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookTextDark)
                        Switch(
                            checked = isAppLockEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showPinDialog = true
                                } else {
                                    onToggleAppLock(false, null)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = KhatabookHeaderBlue)
                        )
                    }

                    if (isAppLockEnabled) {
                        HorizontalDivider(color = KhatabookBorderLight)
                        SettingsItemRow(
                            title = "Change Security PIN / Password",
                            onClick = { showChangePasswordDialog = true }
                        )
                    }

                    HorizontalDivider(color = KhatabookBorderLight)

                    // PROFILE SWITCH LOCK CONTROL SECTION
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Profile Switch Lock", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookTextDark)
                            Text(
                                if (isProfileLockEnabled) "PIN active: Require PIN when switching to this account" else "No lock set for profile switching",
                                fontSize = 11.sp,
                                color = if (isProfileLockEnabled) KhatabookGotGreen else KhatabookTextMuted
                            )
                        }
                        Switch(
                            checked = isProfileLockEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    profilePinText = ""
                                    profilePinError = null
                                    showProfilePinDialog = true
                                } else {
                                    // Completely Remove Profile Lock
                                    onToggleProfileLock(false, null)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = KhatabookHeaderBlue)
                        )
                    }

                    if (isProfileLockEnabled) {
                        HorizontalDivider(color = KhatabookBorderLight)
                        SettingsItemRow(
                            title = "Modify / Change Profile Lock PIN",
                            onClick = {
                                profilePinText = ""
                                profilePinError = null
                                showChangeProfilePinDialog = true
                            }
                        )
                    }

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "Language",
                        onClick = onChangeLanguageClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "Backup Information",
                        onClick = onBackupInfoClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "Delete Khata",
                        onClick = onDeleteKhataClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "App Update",
                        onClick = {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    SettingsItemRow(
                        title = "Help & Support",
                        onClick = {}
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    SettingsItemRow(
                        title = "About Us",
                        onClick = {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            BounceButton(
                onClick = onLogoutClick,
                colors = ButtonDefaults.buttonColors(containerColor = KhatabookGiveRed),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp).bounceClick { onLogoutClick() }
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("LOGOUT FROM KHATABOOK", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Payment Settings Bottom Sheet Modal
        if (showPaymentSettingsModal) {
            ModalBottomSheet(
                onDismissRequest = { showPaymentSettingsModal = false },
                containerColor = Color.Transparent
            ) {
                PaymentSettingsDialog(
                    onDismiss = { showPaymentSettingsModal = false }
                )
            }
        }

        // App Lock PIN Dialog
        if (showPinDialog || showChangePasswordDialog) {
            AlertDialog(
                onDismissRequest = {
                    showPinDialog = false
                    showChangePasswordDialog = false
                },
                title = { Text(if (showChangePasswordDialog) "Change 4-Digit Security PIN" else "Set 4-Digit App Lock PIN", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Enter a 4-digit security PIN to lock your Khatabook app", fontSize = 12.sp, color = KhatabookTextMuted)
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = pinText,
                            onValueChange = {
                                if (it.length <= 4) {
                                    pinText = it
                                    pinError = null
                                }
                            },
                            label = { Text("4-Digit PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        if (pinError != null) {
                            Text(pinError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                },
                confirmButton = {
                    BounceButton(
                        onClick = {
                            if (pinText.length == 4) {
                                onToggleAppLock(true, pinText)
                                showPinDialog = false
                                showChangePasswordDialog = false
                            } else {
                                pinError = "PIN must be exactly 4 digits"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        modifier = Modifier.bounceClick {
                            if (pinText.length == 4) {
                                onToggleAppLock(true, pinText)
                                showPinDialog = false
                                showChangePasswordDialog = false
                            } else {
                                pinError = "PIN must be exactly 4 digits"
                            }
                        }
                    ) {
                        Text("Save PIN")
                    }
                },
                dismissButton = {
                    BounceTextButton(onClick = {
                        showPinDialog = false
                        showChangePasswordDialog = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Profile Lock PIN Set / Modify Dialog
        if (showProfilePinDialog || showChangeProfilePinDialog) {
            AlertDialog(
                onDismissRequest = {
                    showProfilePinDialog = false
                    showChangeProfilePinDialog = false
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (showChangeProfilePinDialog) "Modify Profile Lock PIN" else "Set Profile Switch Lock PIN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Column {
                        Text("Require this 4-digit PIN whenever switching to this profile / business account.", fontSize = 12.sp, color = KhatabookTextMuted)
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = profilePinText,
                            onValueChange = {
                                if (it.length <= 4) {
                                    profilePinText = it
                                    profilePinError = null
                                }
                            },
                            label = { Text("4-Digit Profile PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        if (profilePinError != null) {
                            Text(profilePinError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                },
                confirmButton = {
                    BounceButton(
                        onClick = {
                            if (profilePinText.length == 4) {
                                onToggleProfileLock(true, profilePinText)
                                showProfilePinDialog = false
                                showChangeProfilePinDialog = false
                            } else {
                                profilePinError = "PIN must be exactly 4 digits"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        modifier = Modifier.bounceClick {
                            if (profilePinText.length == 4) {
                                onToggleProfileLock(true, profilePinText)
                                showProfilePinDialog = false
                                showChangeProfilePinDialog = false
                            } else {
                                profilePinError = "PIN must be exactly 4 digits"
                            }
                        }
                    ) {
                        Text("SAVE PROFILE LOCK")
                    }
                },
                dismissButton = {
                    BounceTextButton(onClick = {
                        showProfilePinDialog = false
                        showChangeProfilePinDialog = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsItemRow(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookTextDark)
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = KhatabookTextMuted)
    }
}
