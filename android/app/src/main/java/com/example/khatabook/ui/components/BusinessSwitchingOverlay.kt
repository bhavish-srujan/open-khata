package com.example.khatabook.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
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
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun BusinessSwitchingOverlay(
    targetBusinessName: String,
    onAnimationComplete: () -> Unit
) {
    var isSuccessPhase by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(400)
        isSuccessPhase = true
        delay(450)
        onAnimationComplete()
    }

    val scaleAnimate by animateFloatAsState(
        targetValue = if (isSuccessPhase) 1.02f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "accountSwitchPulse"
    )

    Dialog(onDismissRequest = {}) {
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
                    text = if (isSuccessPhase) "Account Switched!" else "Switching Business Account...",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Store, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = targetBusinessName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookHeaderBlue
                    )
                }

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
