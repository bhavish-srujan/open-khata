package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.khatabook.ui.theme.*

@Composable
fun BusinessQrCodeDialog(
    businessName: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("My Payment QR Code", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(2.dp, KhatabookHeaderBlue, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QrCode2, contentDescription = "QR Code", tint = KhatabookHeaderBlue, modifier = Modifier.size(140.dp))
                        Text("UPI ID: khatabook@upi", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(businessName.ifBlank { "My Business" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Khatabook Verified UPI Merchant", fontSize = 11.sp, color = KhatabookHeaderBlue, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Accept payments via GPay, PhonePe, Paytm, BHIM", fontSize = 11.sp, color = KhatabookTextMuted)

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BounceOutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookHeaderBlue),
                        modifier = Modifier.weight(1f).height(44.dp).bounceClick { onDismiss() }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("DOWNLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                    }

                    BounceButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        modifier = Modifier.weight(1f).height(44.dp).bounceClick { onDismiss() }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SHARE QR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessCardDialog(
    businessName: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Digital Business Card", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Card Graphic Container
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = KhatabookHeaderBlue,
                    shadowElevation = 4.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(businessName.ifBlank { "My Business" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("VERIFIED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        Text("Retail & Wholesale Store", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.2f))

                        Text("Owner: Srujan Kumar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Phone: +91 98765 43210", fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
                        Text("Address: Shop #14, Main Market, Delhi", fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                BounceButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                    modifier = Modifier.fillMaxWidth().height(46.dp).bounceClick { onDismiss() }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SHARE BUSINESS CARD", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
