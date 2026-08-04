package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.window.Dialog
import com.example.khatabook.ui.theme.*

@Composable
fun ProfileLockVerificationDialog(
    targetBusinessName: String,
    correctPin: String,
    onPinSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var pinText by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Profile Switch Lock", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    }

                    BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(28.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Enter PIN to switch to", fontSize = 13.sp, color = KhatabookTextMuted)
                Text(targetBusinessName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = pinText,
                    onValueChange = {
                        if (it.length <= 4) {
                            pinText = it
                            pinError = null
                            if (it.length == 4) {
                                if (it == correctPin) {
                                    onPinSuccess()
                                } else {
                                    pinError = "Incorrect PIN for $targetBusinessName. Try again."
                                }
                            }
                        }
                    },
                    label = { Text("4-Digit PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KhatabookHeaderBlue,
                        unfocusedBorderColor = KhatabookBorderLight
                    )
                )

                if (pinError != null) {
                    Text(pinError!!, color = KhatabookGiveRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BounceOutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(46.dp).bounceClick { onDismiss() }
                    ) {
                        Text("Cancel", color = KhatabookTextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }

                    BounceButton(
                        onClick = {
                            if (pinText == correctPin) {
                                onPinSuccess()
                            } else {
                                pinError = "Incorrect PIN for $targetBusinessName. Try again."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(46.dp).bounceClick {
                            if (pinText == correctPin) {
                                onPinSuccess()
                            } else {
                                pinError = "Incorrect PIN for $targetBusinessName. Try again."
                            }
                        }
                    ) {
                        Text("Unlock & Switch", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
