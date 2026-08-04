package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.data.api.VerifyRequest
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsMessage
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import com.example.khatabook.data.api.VerifyTokenRequest
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpVerificationScreen(
    phoneNumber: String,
    verificationId: String,
    onBackClick: () -> Unit,
    /** Returns the JWT access token and whether the user is new */
    onVerifySuccess: (accessToken: String, isNewUser: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var otpCode by remember { mutableStateOf("") }
    var timerSeconds by remember { mutableStateOf(30) }
    var canResend by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(key1 = timerSeconds) {
        if (timerSeconds > 0) {
            delay(1000L)
            timerSeconds--
        } else {
            canResend = true
        }
    }

    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
                    val bundle = intent.extras
                    if (bundle != null) {
                        val pdus = bundle.get("pdus") as? Array<*>
                        if (pdus != null) {
                            for (pdu in pdus) {
                                val format = bundle.getString("format")
                                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                                } else {
                                    @Suppress("DEPRECATION")
                                    SmsMessage.createFromPdu(pdu as ByteArray)
                                }
                                val messageBody = sms.messageBody
                                if (messageBody != null && messageBody.contains("OTP for") && messageBody.contains("is:")) {
                                    // Extract 6-digit OTP using Regex
                                    val regex = Regex("is: (\\d{6})")
                                    val matchResult = regex.find(messageBody)
                                    val otp = matchResult?.groupValues?.get(1)
                                    if (otp != null) {
                                        otpCode = otp
                                        // Auto-submit logic
                                        if (!isLoading) {
                                            isLoading = true
                                            coroutineScope.launch {
                                                try {
                                                    val credential = PhoneAuthProvider.getCredential(verificationId, otpCode)
                                                    val authResult = FirebaseAuth.getInstance().signInWithCredential(credential).await()
                                                    val tokenResult = authResult.user?.getIdToken(false)?.await()
                                                    val idToken = tokenResult?.token ?: throw Exception("Token missing")
                                                    
                                                    val response = NetworkClient.apiService.verifyToken(VerifyTokenRequest(idToken))
                                                    if (response.isSuccessful && response.body() != null) {
                                                        val body = response.body()!!
                                                        onVerifySuccess(body.access_token, body.is_new_user)
                                                    } else {
                                                        errorMessage = "Invalid OTP"
                                                    }
                                                } catch (e: Exception) {
                                                    errorMessage = "Invalid OTP"
                                                } finally {
                                                    isLoading = false
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify OTP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color(0xFFEFF6FF), shape = RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🔐", fontSize = 36.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "OTP Verification",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark
            )
            Text(
                text = "Enter 6-digit code sent to $phoneNumber",
                fontSize = 13.sp,
                color = KhatabookTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // OTP Code Boxes (6 Digits)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = { focusRequester.requestFocus() }
                    )
            ) {
                for (i in 0 until 6) {
                    val char = if (i < otpCode.length) otpCode[i].toString() else ""
                    val isFocused = i == otpCode.length

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .border(
                                width = if (isFocused) 2.dp else 1.dp,
                                color = if (errorMessage != null) KhatabookGiveRed
                                       else if (isFocused) KhatabookHeaderBlue
                                       else KhatabookBorderLight,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(KhatabookBgLight, shape = RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = KhatabookTextDark
                        )
                    }
                }
            }

            // Hidden Keyboard Receiver
            OutlinedTextField(
                value = otpCode,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        otpCode = it
                        errorMessage = null
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(0.dp)
                    .focusRequester(focusRequester)
                    .onGloballyPositioned { focusRequester.requestFocus() },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = KhatabookGiveRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hint removed as it's no longer from our backend console

            Spacer(modifier = Modifier.height(16.dp))

            // Resend Timer Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (timerSeconds > 0) "Resend OTP in ${timerSeconds}s" else "Didn't receive code?",
                    fontSize = 13.sp,
                    color = KhatabookTextMuted
                )
                if (canResend) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "RESEND",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookHeaderBlue,
                        modifier = Modifier.clickable {
                            timerSeconds = 30
                            canResend = false
                            otpCode = ""
                            errorMessage = null
                                // Cannot easily resend via Firebase without Activity context here, 
                                // so usually you let them go back and request again, or pass context here.
                                // For simplicity, we just clear and tell them to go back.
                                errorMessage = "Please go back to request a new OTP"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            BounceButton(
                onClick = {
                    if (otpCode.length == 6) {
                        isLoading = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                val credential = PhoneAuthProvider.getCredential(verificationId, otpCode)
                                val authResult = FirebaseAuth.getInstance().signInWithCredential(credential).await()
                                val tokenResult = authResult.user?.getIdToken(false)?.await()
                                val idToken = tokenResult?.token ?: throw Exception("Token missing")
                                
                                val response = NetworkClient.apiService.verifyToken(
                                    VerifyTokenRequest(idToken = idToken)
                                )
                                if (response.isSuccessful && response.body() != null) {
                                    val body = response.body()!!
                                    onVerifySuccess(body.access_token, body.is_new_user)
                                } else {
                                    errorMessage = "Invalid OTP. Please check and try again."
                                    otpCode = ""
                                }
                            } catch (e: Exception) {
                                errorMessage = "Cannot reach server or invalid OTP."
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = otpCode.length == 6 && !isLoading,
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
                        text = "VERIFY & CONTINUE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}
