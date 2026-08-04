package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.api.OtpRequest
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileAuthScreen(
    onBackClick: () -> Unit,
    onSendOtp: (phoneNumber: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Authentication", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    BounceIconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Graphic/Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color(0xFFEFF6FF), shape = RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("📱", fontSize = 36.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Enter your Mobile Number",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark
            )
            Text(
                text = "We will send an OTP verification code to verify your phone number",
                fontSize = 13.sp,
                color = KhatabookTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )

            // Phone Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = "🇮🇳 +91",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.width(100.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledBorderColor = KhatabookBorderLight,
                        disabledTextColor = KhatabookTextDark,
                        disabledContainerColor = KhatabookBgLight
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = {
                        if (it.length <= 10 && it.all { char -> char.isDigit() }) {
                            phoneNumber = it
                            errorMessage = null
                        }
                    },
                    placeholder = { Text("Mobile Number") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = KhatabookTextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KhatabookHeaderBlue,
                        unfocusedBorderColor = KhatabookBorderLight
                    )
                )
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = KhatabookGiveRed,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 6.dp, start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            BounceButton(
                onClick = {
                    if (phoneNumber.length == 10) {
                        val fullPhone = "+91$phoneNumber"
                        isLoading = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                val response = NetworkClient.apiService.requestOtp(OtpRequest(phone = fullPhone))
                                if (response.isSuccessful) {
                                    onSendOtp(fullPhone)
                                } else {
                                    errorMessage = "Server error (${response.code()}). Try again."
                                }
                            } catch (e: Exception) {
                                errorMessage = "Cannot reach server. Check your connection."
                            } finally {
                                isLoading = false
                            }
                        }
                    } else {
                        errorMessage = "Please enter a valid 10-digit mobile number"
                    }
                },
                enabled = phoneNumber.length == 10 && !isLoading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KhatabookHeaderBlue,
                    disabledContainerColor = KhatabookHeaderBlue.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "GET OTP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "By continuing, you agree to Khatabook's Terms of Service & Privacy Policy",
                fontSize = 11.sp,
                color = KhatabookTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}
