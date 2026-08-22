package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.theme.*

@Composable
fun AppLockScreen(
    correctPin: String,
    onUnlockSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableStateOf(0) }
    var lockoutEndTime by remember { mutableStateOf(0L) }
    var lockoutSecondsRemaining by remember { mutableStateOf(0) }

    LaunchedEffect(lockoutEndTime) {
        while (lockoutEndTime > System.currentTimeMillis()) {
            lockoutSecondsRemaining = ((lockoutEndTime - System.currentTimeMillis()) / 1000).toInt()
            kotlinx.coroutines.delay(1000L)
        }
        lockoutSecondsRemaining = 0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KhatabookHeaderBlue),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(28.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Open Khata App Lock", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                Text("Enter your 4-digit security PIN", fontSize = 12.sp, color = KhatabookTextMuted)

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        if (lockoutSecondsRemaining > 0) return@OutlinedTextField
                        if (it.length <= 4) {
                            enteredPin = it
                            errorMsg = null
                            if (it.length == 4) {
                                if (it == correctPin) {
                                    failedAttempts = 0
                                    onUnlockSuccess()
                                } else {
                                    failedAttempts++
                                    if (failedAttempts >= 5) {
                                        val backoffSeconds = 30L * (1 shl (failedAttempts - 5).coerceAtMost(5))
                                        lockoutEndTime = System.currentTimeMillis() + (backoffSeconds * 1000)
                                        errorMsg = "Too many failed attempts. Try again in $backoffSeconds seconds."
                                    } else {
                                        errorMsg = "Incorrect PIN. Please try again."
                                    }
                                    enteredPin = ""
                                }
                            }
                        }
                    },
                    enabled = lockoutSecondsRemaining <= 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KhatabookHeaderBlue)
                )

                if (errorMsg != null) {
                    Text(errorMsg!!, color = KhatabookGiveRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                BounceButton(
                    onClick = {
                        if (enteredPin == correctPin) {
                            onUnlockSuccess()
                        } else {
                            errorMsg = "Incorrect PIN"
                            enteredPin = ""
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("UNLOCK APP", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
