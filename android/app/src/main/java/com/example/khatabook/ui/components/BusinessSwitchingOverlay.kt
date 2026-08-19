package com.example.khatabook.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.delay

enum class AccountTransitionMode {
    BUSINESS_SWITCH,
    ACCOUNT_SWITCH,
    LOGOUT,
}

/**
 * Full-screen, non-dismissible overlay shown while a business switch, account
 * switch, or logout is actually happening.
 *
 * This used to be business-switch-only, on a fixed timer (400ms + 450ms)
 * unconnected to the real [repository work][isOperationComplete] — the
 * "success" phase always fired on the clock, whether or not the underlying
 * switch had actually finished, and its copy hardcoded "Account Switched!"
 * even though only a business had changed. That made every business switch
 * visually read as an account switch, and meant the screen behind the overlay
 * could already be showing the *old* business/account's data by the time the
 * fake "success" fired.
 *
 * Now the spinner phase lasts exactly as long as the real operation takes —
 * [isOperationComplete] is flipped by the caller only after the actual
 * suspend call (switchBusiness / switchUser+loadBusinesses / logout) returns.
 * Only then does the brief, fixed-duration success flourish run, purely for
 * legibility (a checkmark that appeared and vanished in the same frame would
 * be worse UX than a short, deliberate pause) — never a substitute for the
 * real work.
 */
@Composable
fun BusinessSwitchingOverlay(
    mode: AccountTransitionMode,
    targetLabel: String,
    isOperationComplete: Boolean,
    onFinished: () -> Unit
) {
    var isSuccessPhase by remember { mutableStateOf(false) }

    LaunchedEffect(isOperationComplete) {
        if (isOperationComplete) {
            isSuccessPhase = true
            delay(450)
            onFinished()
        }
    }

    val scaleAnimate by animateFloatAsState(
        targetValue = if (isSuccessPhase) 1.02f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "accountSwitchPulse"
    )

    val (inProgressText, successText, targetIcon) = when (mode) {
        AccountTransitionMode.BUSINESS_SWITCH -> Triple("Switching Business...", "Business Switched!", Icons.Default.Store)
        AccountTransitionMode.ACCOUNT_SWITCH -> Triple("Switching Account...", "Account Switched!", Icons.Default.Person)
        AccountTransitionMode.LOGOUT -> Triple("Logging Out...", "Logged Out", Icons.AutoMirrored.Filled.Logout)
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .scale(scaleAnimate),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (isSuccessPhase) Color(0xFFDCFCE7) else Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isSuccessPhase,
                        transitionSpec = {
                            (fadeIn() + scaleIn(initialScale = 0.6f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.6f))
                        },
                        label = "switchIconMorph"
                    ) { success ->
                        if (success) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = KhatabookGotGreen,
                                modifier = Modifier.size(36.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = KhatabookHeaderBlue,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isSuccessPhase) successText else inProgressText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark
                )

                if (targetLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(targetIcon, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = targetLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = KhatabookHeaderBlue
                        )
                    }
                }

                if (mode != AccountTransitionMode.LOGOUT) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "100% Isolated & Private Account Data",
                            fontSize = 10.sp,
                            color = KhatabookTextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
