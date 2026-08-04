package com.example.khatabook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.ui.theme.KhatabookGreen
import com.example.khatabook.ui.theme.KhatabookGreenLight
import com.example.khatabook.ui.theme.KhatabookRed
import com.example.khatabook.ui.theme.KhatabookRedLight
import com.example.khatabook.ui.theme.KhatabookTextDark
import com.example.khatabook.ui.theme.KhatabookTextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LedgerEntryCard(
    entry: LedgerEntry,
    onClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isGave = entry.type == TransactionType.GAVE
    val amountColor = if (isGave) KhatabookRed else KhatabookGreen
    val bgColor = if (isGave) KhatabookRedLight else KhatabookGreenLight
    // Memoized: only re-formats when the timestamp changes (never during scrolling)
    val dateStr = remember(entry.createdAt) {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(entry.createdAt))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isGave) "You Gave" else "You Got",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• ${entry.paymentMethod}",
                        fontSize = 11.sp,
                        color = KhatabookTextMuted
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (entry.note.isNotEmpty()) entry.note else "No note added",
                    fontSize = 12.sp,
                    color = KhatabookTextDark
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    fontSize = 10.sp,
                    color = KhatabookTextMuted
                )
            }

            Text(
                text = "${if (isGave) "-" else "+"} ₹ ${String.format("%.2f", entry.amount)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                modifier = Modifier
                    .background(bgColor, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
