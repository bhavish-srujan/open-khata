package com.example.khatabook.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import com.example.khatabook.data.model.Party
import com.example.khatabook.ui.theme.KhatabookHeaderBlue
import com.example.khatabook.ui.theme.KhatabookTextDark
import com.example.khatabook.ui.theme.KhatabookTextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindBottomSheet(
    party: Party,
    onDismiss: () -> Unit,
    onSmsClick: () -> Unit,
    onWhatsappClick: () -> Unit,
    onSendBulkReminders: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        dragHandle = null, // Custom handle or no handle based on UI
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Request ${party.name} to pay you",
                    fontSize = 14.sp,
                    color = KhatabookTextDark
                )
                BounceIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                }
            }
            
            // Amount
            Text(
                text = "₹ ${party.currentBalance.toInt()}",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            
            // Promo Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color(0xFFF8F9FA), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Send Bulk Reminders",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookTextDark
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color.Red,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "NEW",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Send reminders to multiple parties at once",
                            fontSize = 12.sp,
                            color = KhatabookTextMuted,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BounceButton(
                            onClick = onSendBulkReminders,
                            colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Try Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    // Simple placeholder icon for the banner illustration
                    Icon(
                        Icons.Default.Message, 
                        contentDescription = null,
                        tint = KhatabookHeaderBlue,
                        modifier = Modifier.size(60.dp).padding(start = 16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BounceButton(
                    onClick = onSmsClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue)
                ) {
                    Icon(Icons.Default.Message, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SMS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                
                BounceButton(
                    onClick = onWhatsappClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)) // WhatsApp Green
                ) {
                    Icon(Icons.Default.Message, contentDescription = null, modifier = Modifier.size(18.dp)) // Placeholder for WA icon
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("WHATSAPP", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
