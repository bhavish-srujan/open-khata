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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpVerificationScreen(
    phoneNumber: String,
    onBackClick: () -> Unit,
    /** Returns the JWT access token and whether the user is new */
    onVerifySuccess: (accessToken: String, isNewUser: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var otpCode by remember { mutableStateOf("") }
    var lastFailedOtp by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableStateOf(0) }
    var lockoutEndTime by remember { mutableStateOf(0L) }
    var lockoutSecondsRemaining by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    // Requesting focus alone does not show the IME on most devices/keyboards
    // when it's granted programmatically rather than from a direct tap — the
    // keyboard must be told to show explicitly. The screen also enters via a
    // Crossfade (AnimatedScreenTransition), so a short delay after requesting
    // focus lets that settle before asking the IME to show, rather than racing it.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        delay(100)
        keyboardController?.show()
    }

    // Removed timer logic
    LaunchedEffect(lockoutEndTime) {
        while (lockoutEndTime > System.currentTimeMillis()) {
            lockoutSecondsRemaining = ((lockoutEndTime - System.currentTimeMillis()) / 1000).toInt()
            delay(1000L)
        }
        lockoutSecondsRemaining = 0
    }

    // Auto-fill via the SMS User Consent API. Unlike RECEIVE_SMS/READ_SMS
    // (which Play Store restricts heavily and mostly reserves for default
    // SMS-handler apps), this needs no declared permission at all — Google
    // Play Services itself watches for an incoming SMS matching this app's
    // foreground request, then shows a one-time system dialog asking the
    // user's explicit consent before handing this screen the message text.
    // Denied consent, no matching SMS, or Play Services being unavailable
    // all fail silently into the existing manual-entry flow, which was the
    // only path before this and stays fully correct on its own.
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
            val extractedCode = message?.let { Regex("\\d{6}").find(it)?.value }
            if (extractedCode != null) {
                otpCode = extractedCode
                errorMessage = null
                lastFailedOtp = null
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                    consentIntent?.let {
                        try {
                            consentLauncher.launch(it)
                        } catch (_: Exception) {
                            // Consent screen could not be launched — the user can
                            // still type the code manually, same as always.
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        SmsRetriever.getClient(context).startSmsUserConsent(null)
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
                text = "Enter the 6-digit code sent to $phoneNumber",
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
                        onClick = {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
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
                        if (it != lastFailedOtp) {
                            errorMessage = null
                            lastFailedOtp = null
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(0.dp)
                    .focusRequester(focusRequester),
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

            // Resend Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Didn't receive code?",
                    fontSize = 13.sp,
                    color = KhatabookTextMuted
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "RESEND",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookHeaderBlue,
                    modifier = Modifier.clickable {
                        if (!isLoading) {
                            otpCode = ""
                            errorMessage = null
                            // A fresh code means a fresh attempt budget: the server resets its
                            // own counter on every new issue, and the client-side one must match
                            // or a legitimate resend could still run into the old lockout.
                            lastFailedOtp = null
                            failedAttempts = 0
                            isLoading = true
                            coroutineScope.launch {
                                try {
                                    val request = com.example.khatabook.data.api.RequestOtpRequest(phoneNumber)
                                    val response = NetworkClient.apiService.requestOtp(request)
                                    if (!response.isSuccessful || response.body()?.success != true) {
                                        errorMessage = com.example.khatabook.utils.parseApiErrorMessage(response.errorBody()?.string(), "Failed to resend OTP")
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Network error: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            BounceButton(
                onClick = {
                    if (otpCode.length == 6) {
                        isLoading = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                val request = com.example.khatabook.data.api.VerifyOtpRequest(phoneNumber, otpCode)
                                val response = NetworkClient.apiService.verifyOtp(request)
                                if (response.isSuccessful && response.body() != null) {
                                    failedAttempts = 0
                                    val token = response.body()!!.access_token
                                    val isNewUser = response.body()!!.is_new_user
                                    onVerifySuccess(token, isNewUser)
                                } else {
                                    failedAttempts++
                                    if (failedAttempts >= 5) {
                                        val backoffSeconds = 30L * (1 shl (failedAttempts - 5).coerceAtMost(5))
                                        lockoutEndTime = System.currentTimeMillis() + (backoffSeconds * 1000)
                                        errorMessage = "Too many failed attempts. Try again in $backoffSeconds seconds."
                                    } else {
                                        // The server clears the code on expiry and on burning the
                                        // last attempt, so both leave nothing left to verify —
                                        // retyping digits can never succeed. Told apart from a
                                        // plain wrong code, where the live code is still guessable.
                                        val parsedMessage = com.example.khatabook.utils.parseApiErrorMessage(
                                            response.errorBody()?.string(),
                                            "Invalid OTP",
                                        )
                                        errorMessage = when {
                                            parsedMessage.contains("expired", ignoreCase = true) ->
                                                "This code has expired. Tap Resend to get a new one."
                                            parsedMessage.contains("No OTP requested", ignoreCase = true) ->
                                                "No active code for this number. Tap Resend to get a new one."
                                            else -> parsedMessage
                                        }
                                    }
                                    lastFailedOtp = otpCode
                                }
                            } catch (e: Exception) {
                                errorMessage = "Network error: ${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = otpCode.length == 6 && otpCode != lastFailedOtp && !isLoading && lockoutSecondsRemaining <= 0,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KhatabookHeaderBlue,
                    disabledContainerColor = KhatabookHeaderBlue.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    val buttonText = if (lockoutSecondsRemaining > 0) "TRY AGAIN IN ${lockoutSecondsRemaining}S" else "VERIFY"
                    Text(buttonText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
