package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.ui.theme.*

data class ExtractedPaymentResult(
    val party: Party?,
    val extractedName: String?,
    val amount: Double,
    val transactionType: TransactionType,
    val rawText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAiMoneyLoggerDialog(
    parties: List<Party>,
    onDismiss: () -> Unit,
    onLogPaymentConfirmed: (partyId: String, amount: Double, type: TransactionType, note: String) -> Unit,
    onNewCustomerDetected: (name: String, amount: Double, type: TransactionType, note: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var aiInputText by remember { mutableStateOf("") }
    var extractedResult by remember { mutableStateOf<ExtractedPaymentResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val presetPromptSuggestions = listOf(
        "Ramesh Kumar paid ₹500 via UPI",
        "Collected ₹1200 from Vikram Singh",
        "Gave ₹250 to Priya Verma",
        "Amma paid ₹1000 cash"
    )

    fun parseTextWithAi(text: String) {
        if (text.isBlank()) {
            errorMessage = "Please enter or select a payment note"
            return
        }

        // 1. Extract numeric amount
        val regexAmount = Regex("""(?:₹|\$|rs\.?|INR)?\s*(\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        val matchAmount = regexAmount.find(text)
        val amount = matchAmount?.groupValues?.get(1)?.toDoubleOrNull()

        if (amount == null || amount <= 0.0) {
            errorMessage = "Could not find a valid amount in text (e.g. ₹500)"
            return
        }

        // 2. Extract matching customer from party list
        val matchedParty = parties.firstOrNull { party ->
            text.contains(party.name, ignoreCase = true) ||
                    party.name.split(" ").any { part -> part.length > 2 && text.contains(part, ignoreCase = true) }
        } ?: parties.firstOrNull()

        val nameRegex = Regex("""([A-Za-z\s]+)(?:paid|gave|collected|received|took)""", RegexOption.IGNORE_CASE)
        val extractedName = matchedParty?.name ?: nameRegex.find(text)?.groupValues?.get(1)?.trim() ?: "Unknown Customer"

        // 3. Extract transaction type (GOT = received/collected/paid, GAVE = gave/sent/lend)
        val textLower = text.lowercase()
        val isGot = textLower.contains("got") ||
                textLower.contains("collected") ||
                textLower.contains("received") ||
                textLower.contains("paid") ||
                textLower.contains("took") ||
                textLower.contains("payment from")

        val type = if (isGot) TransactionType.GOT else TransactionType.GAVE

        extractedResult = ExtractedPaymentResult(
            party = matchedParty,
            extractedName = extractedName,
            amount = amount,
            transactionType = type,
            rawText = text
        )
        errorMessage = null
    }

    Dialog(onDismissRequest = onDismiss) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(200)
            ) + fadeIn(tween(200))
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✨", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "AI Smart Money Logger",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookTextDark
                            )
                        }

                        BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                        }
                    }

                    Text(
                        text = "Type or select payment note — AI will auto-extract customer & log money into ledger",
                        fontSize = 11.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    // Quick AI Suggestions
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        items(presetPromptSuggestions) { suggestion ->
                            SuggestionChip(
                                onClick = {
                                    aiInputText = suggestion
                                    parseTextWithAi(suggestion)
                                },
                                label = { Text(suggestion, fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = Color(0xFFEFF6FF),
                                    labelColor = KhatabookHeaderBlue
                                )
                            )
                        }
                    }

                    // Input Field
                    OutlinedTextField(
                        value = aiInputText,
                        onValueChange = {
                            aiInputText = it
                            errorMessage = null
                        },
                        placeholder = { Text("e.g. Collected ₹500 from Ramesh Kumar", fontSize = 13.sp) },
                        leadingIcon = { Text("✨", fontSize = 16.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KhatabookHeaderBlue,
                            unfocusedBorderColor = KhatabookBorderLight
                        )
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = KhatabookGiveRed,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Extracted AI Result Card Preview
                    if (extractedResult != null) {
                        val res = extractedResult!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (res.transactionType == TransactionType.GOT) KhatabookGotGreenBg else KhatabookGiveRedBg
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (res.transactionType == TransactionType.GOT) KhatabookGotGreen else KhatabookGiveRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "AI Extracted Details",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (res.transactionType == TransactionType.GOT) KhatabookGotGreen else KhatabookGiveRed
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = KhatabookBorderLight
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Customer:", fontSize = 12.sp, color = KhatabookTextDark)
                                    if (res.party != null) {
                                        Text(res.party.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                    } else {
                                        Text("${res.extractedName} (New Customer)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookGiveRed)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Amount:", fontSize = 12.sp, color = KhatabookTextDark)
                                    Text("₹ ${res.amount.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Type:", fontSize = 12.sp, color = KhatabookTextDark)
                                    Text(
                                        text = if (res.transactionType == TransactionType.GOT) "YOU GOT (Reduced Balance)" else "YOU GAVE (Increased Balance)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (res.transactionType == TransactionType.GOT) KhatabookGotGreen else KhatabookGiveRed
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Action Buttons
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (extractedResult == null) {
                            BounceButton(
                                onClick = { parseTextWithAi(aiInputText) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                                modifier = Modifier.fillMaxWidth().height(44.dp)
                            ) {
                                Text("✨ EXTRACT WITH AI", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        } else {
                            val res = extractedResult!!
                            BounceButton(
                                onClick = {
                                    if (res.party != null) {
                                        onLogPaymentConfirmed(res.party.id, res.amount, res.transactionType, res.rawText)
                                    } else {
                                        onNewCustomerDetected(res.extractedName ?: "", res.amount, res.transactionType, res.rawText)
                                    }
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (res.transactionType == TransactionType.GOT) KhatabookGotGreen else KhatabookGiveRed
                                ),
                                modifier = Modifier.fillMaxWidth().height(44.dp)
                            ) {
                                Text(if (res.party != null) "CONFIRM & LOG MONEY INTO LEDGER" else "ADD CUSTOMER & LOG MONEY", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
