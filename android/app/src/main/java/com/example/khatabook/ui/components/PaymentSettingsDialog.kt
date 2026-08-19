package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.theme.*

@Composable
fun PaymentSettingsDialog(
    onDismiss: () -> Unit
) {
    var voiceNotificationsEnabled by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Payment Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark
                )
                BounceIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Row 1: Voice Notifications
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Voice Notifications",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = KhatabookTextDark
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = KhatabookTextDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(KhatabookGiveRed)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("NEW", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Text(
                        text = "Receive voice alert on payment completion",
                        fontSize = 12.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Switch(
                    checked = voiceNotificationsEnabled,
                    onCheckedChange = { voiceNotificationsEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = KhatabookHeaderBlue)
                )
            }

            HorizontalDivider(color = KhatabookBorderLight)

            // Row 2: Daily Settlements
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
            ) {
                Text(
                    text = "Daily Settlements",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark
                )
                Text(
                    text = "All payments settles automatically at midnight",
                    fontSize = 12.sp,
                    color = KhatabookTextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            HorizontalDivider(color = KhatabookBorderLight)

            // Row 3: Bank Accounts. Not clickable: there is no bank-account
            // management screen behind it, so it's informational only rather
            // than a `.clickable {}` that did nothing when tapped.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bank Accounts",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookTextDark
                    )
                    Text(
                        text = "Manage your bank accounts",
                        fontSize = 12.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            HorizontalDivider(color = KhatabookBorderLight)

            // Row 4: Help. Not clickable: PaymentSettingsDialog has no
            // navigation callback wired in (only onDismiss), so there was
            // nowhere for this to go — informational only rather than a
            // `.clickable {}` that did nothing when tapped.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Help",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookTextDark
                    )
                    Text(
                        text = "Payment FAQs or contact customer support for any queries",
                        fontSize = 12.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
