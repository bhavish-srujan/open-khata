package com.example.khatabook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.theme.KhatabookGreen
import com.example.khatabook.ui.theme.KhatabookGreenLight
import com.example.khatabook.ui.theme.KhatabookNavy
import com.example.khatabook.ui.theme.KhatabookRed
import com.example.khatabook.ui.theme.KhatabookRedLight
import kotlin.math.abs

@Composable
fun BalanceSummaryCard(
    youWillGet: Double,
    youWillGive: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KhatabookNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // You will GET
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(KhatabookGreenLight, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "You Will Get (आपने दिया)",
                    fontSize = 11.sp,
                    color = KhatabookGreen,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "₹ ${String.format("%.2f", youWillGet)}",
                    fontSize = 18.sp,
                    color = KhatabookGreen,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // You will GIVE
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(KhatabookRedLight, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "You Will Give (आपने लिया)",
                    fontSize = 11.sp,
                    color = KhatabookRed,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "₹ ${String.format("%.2f", abs(youWillGive))}",
                    fontSize = 18.sp,
                    color = KhatabookRed,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
